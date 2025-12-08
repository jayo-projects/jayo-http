/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jayo.http.internal.connection;

import jayo.Jayo;
import jayo.JayoException;
import jayo.RawSocket;
import jayo.http.*;
import jayo.http.internal.UrlUtils;
import jayo.network.JayoUnknownServiceException;
import jayo.network.NetworkSocket;
import jayo.scheduler.TaskRunner;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static jayo.http.Authenticator.JAYO_PREEMPTIVE_CHALLENGE;
import static jayo.http.internal.UrlUtils.toHostHeader;
import static jayo.http.internal.Utils.USER_AGENT;

final class RealRoutePlanner implements RoutePlanner {
    private final @NonNull TaskRunner taskRunner;
    private final @NonNull RealConnectionPool connectionPool;
    private final NetworkSocket.@NonNull Builder networkSocketBuilder;
    private final @NonNull Duration pingInterval;
    private final boolean retryOnConnectionFailure;
    private final boolean fastFallback;
    private final @NonNull Address address;
    private final @NonNull RouteDatabase routeDatabase;
    private final @NonNull RealCall call;
    private final boolean doExtensiveHealthChecks;

    private RouteSelector.@Nullable Selection routeSelection = null;
    private @Nullable RouteSelector routeSelector = null;
    private @Nullable Route nextRouteToTry = null;

    private final @NonNull ArrayDeque<Plan> deferredPlans = new ArrayDeque<>();

    RealRoutePlanner(final @NonNull TaskRunner taskRunner,
                     final @NonNull RealConnectionPool connectionPool,
                     NetworkSocket.@NonNull Builder networkSocketBuilder,
                     final @NonNull Duration pingInterval,
                     final boolean retryOnConnectionFailure,
                     final boolean fastFallback,
                     final @NonNull Address address,
                     final @NonNull RouteDatabase routeDatabase,
                     final @NonNull RealCall call,
                     final @NonNull ClientRequest request) {
        assert taskRunner != null;
        assert connectionPool != null;
        assert networkSocketBuilder != null;
        assert pingInterval != null;
        assert address != null;
        assert routeDatabase != null;
        assert call != null;
        assert request != null;

        this.taskRunner = taskRunner;
        this.connectionPool = connectionPool;
        this.networkSocketBuilder = networkSocketBuilder;
        this.pingInterval = pingInterval;
        this.retryOnConnectionFailure = retryOnConnectionFailure;
        this.fastFallback = fastFallback;
        this.address = address;
        this.routeDatabase = routeDatabase;
        this.call = call;

        this.doExtensiveHealthChecks = !request.getMethod().equals("GET");
    }

    @Override
    public @NonNull Address getAddress() {
        return address;
    }

    @Override
    public @NonNull Deque<Plan> getDeferredPlans() {
        return deferredPlans;
    }

    @Override
    public boolean isCanceled() {
        return call.isCanceled();
    }

    @Override
    public @NonNull Plan plan() {
        final var reuseCallConnection = planReuseCallConnection();
        if (reuseCallConnection != null) {
            return reuseCallConnection;
        }

        // Attempt to get a connection from the pool.
        final var pooled1 = planReusePooledConnection(null, null);
        if (pooled1 != null) {
            return pooled1;
        }

        // Attempt a deferred plan before new routes.
        if (!deferredPlans.isEmpty()) {
            return deferredPlans.removeFirst();
        }

        // Do blocking calls to plan a route for a new connection.
        final var connect = planConnect();

        // Now that we have a set of IP addresses, make another attempt at getting a connection from the pool. We have a
        // better chance of matching thanks to connection coalescing.
        final var pooled2 = planReusePooledConnection(connect, connect.routes);
        if (pooled2 != null) {
            return pooled2;
        }

        return connect;
    }

    /**
     * @return the connection already attached to the call if it's eligible for a new exchange.
     * <p>
     * If the call's connection exists and is eligible for another exchange, it is returned. If it
     * exists but cannot be used for another exchange, it is closed and this returns null.
     */
    private @Nullable ReusePlan planReuseCallConnection() {
        // This may be mutated by releaseConnectionNoEvents()!
        final var candidate = call.connection;
        if (candidate == null) {
            return null;
        }

        // Make sure this connection is healthy and eligible for new exchanges. If it's no longer necessary, then we're
        // on the hook to close it.
        final var healthy = candidate.isHealthy(doExtensiveHealthChecks);
//        var noNewExchangesEvent = false;
        final RawSocket toClose;
        candidate.lock.lock();
        try {
            if (!healthy) {
//                noNewExchangesEvent = !candidate.noNewExchanges;
                candidate.noNewExchanges = true;
                toClose = call.releaseConnectionNoEvents();
            } else if (candidate.noNewExchanges || !sameHostAndPort(candidate.route().getAddress().getUrl())) {
                toClose = call.releaseConnectionNoEvents();
            } else {
                toClose = null;
            }
        } finally {
            candidate.lock.unlock();
        }

        // If the call's connection wasn't released, reuse it. We don't call connectionAcquired() here because we
        // already acquired it.
        if (call.connection != null) {
            if (toClose != null) {
                throw new IllegalStateException();
            }
            return new ReusePlan(candidate);
        }

        // The call's connection was released.
        if (toClose != null) {
            Jayo.closeQuietly(toClose);
        }
        call.eventListener.connectionReleased(call, candidate);
//        candidate.connectionListener.connectionReleased(candidate, call);
//        if (toClose != null) {
//            candidate.connectionListener.connectionClosed(candidate);
//        } else if (noNewExchangesEvent) {
//            candidate.connectionListener.noNewExchanges(candidate);
//        }
        return null;
    }

    /**
     * Plans to make a new connection by deciding which route to try next.
     */
    @NonNull
    ConnectPlan planConnect() {
        // Use a route from a preceding coalesced connection.
        final var localNextRouteToTry = nextRouteToTry;
        if (localNextRouteToTry != null) {
            nextRouteToTry = null;
            return planConnectToRoute(localNextRouteToTry, null);
        }

        // Use a route from an existing route selection.
        final var existingRouteSelection = routeSelection;
        if (existingRouteSelection != null && existingRouteSelection.hasNext()) {
            return planConnectToRoute(existingRouteSelection.next(), null);
        }

        // Decide which proxy to use, if any.
        RouteSelector newRouteSelector = routeSelector;
        if (newRouteSelector == null) {
            newRouteSelector = new RouteSelector(
                    address,
                    routeDatabase,
                    call,
                    fastFallback
            );
            routeSelector = newRouteSelector;
        }

        // List available IP addresses for the current proxy. This may block in Dns.lookup().
        if (!newRouteSelector.hasNext()) {
            throw new JayoException("exhausted all routes");
        }
        final var newRouteSelection = newRouteSelector.next();
        routeSelection = newRouteSelection;

        if (isCanceled()) {
            throw new JayoException("Canceled");
        }

        return planConnectToRoute(newRouteSelection.next(), newRouteSelection.routes);
    }

    /**
     * @return a plan for the first attempt at {@code route}. This throws if no plan is possible.
     */
    @NonNull
    ConnectPlan planConnectToRoute(final @NonNull Route route, final @Nullable List<Route> routes) {
        assert route != null;

        if (route.getAddress().getClientTlsSocketBuilder() == null) {
            if (!route.getAddress().getConnectionSpecs().contains(ConnectionSpec.CLEARTEXT)) {
                throw new JayoUnknownServiceException("CLEARTEXT communication not enabled for client");
            }
        } else {
            if (route.getAddress().getProtocols().contains(Protocol.H2_PRIOR_KNOWLEDGE)) {
                throw new JayoUnknownServiceException("H2_PRIOR_KNOWLEDGE cannot be used with HTTPS");
            }
        }

        final var tunnelRequest = route.requiresTunnel() ? createTunnelRequest(route) : null;

        return new ConnectPlan(
                taskRunner,
                connectionPool,
                networkSocketBuilder,
                pingInterval,
                retryOnConnectionFailure,
                call,
                this,
                route,
                routes,
                0, // attempt
                tunnelRequest,
                -1, // connectionSpecIndex
                false); // isTlsFallback
    }

    /**
     * @return a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request is sent
     * unencrypted to the proxy server, so tunnels include only the minimum set of headers. This avoids sending
     * potentially sensitive data like HTTP cookies to the proxy unencrypted.
     * <p>
     * To support preemptive authentication, we pass a fake "Auth Failed" response to the authenticator. This gives the
     * authenticator the option to customize the CONNECT request. It can decline to do so by returning null, in which
     * case Jayo HTTP will use it as-is.
     */
    private @NonNull ClientRequest createTunnelRequest(final @NonNull Route route) {
        assert route != null;

        final var proxyConnectRequest =
                ClientRequest.builder()
                        .url(route.getAddress().getUrl())
                        .header("Host", toHostHeader(route.getAddress().getUrl(), true))
                        .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
                        .header("User-Agent", USER_AGENT)
                        .connect();

        final var fakeAuthChallengeResponse =
                ClientResponse.builder()
                        .request(proxyConnectRequest)
                        .protocol(Protocol.HTTP_1_1)
                        .statusCode(HttpURLConnection.HTTP_PROXY_AUTH)
                        .statusMessage("Preemptive Authenticate")
                        .header("Proxy-Authenticate", JAYO_PREEMPTIVE_CHALLENGE)
                        .build();

        final var authenticatedRequest =
                route.getAddress().getProxyAuthenticator().authenticate(route, fakeAuthChallengeResponse);

        return (authenticatedRequest != null) ? authenticatedRequest : proxyConnectRequest;
    }

    @Override
    public boolean hasNext(final @Nullable RealConnection failedConnection) {
        if (!deferredPlans.isEmpty()) {
            return true;
        }

        if (nextRouteToTry != null) {
            return true;
        }

        if (failedConnection != null) {
            final var retryRoute = retryRoute(failedConnection);
            if (retryRoute != null) {
                // Lock in the route because retryRoute() is racy and we don't want to call it twice.
                nextRouteToTry = retryRoute;
                return true;
            }
        }

        // If we have routes left, use 'em.
        if (routeSelection != null && routeSelection.hasNext()) {
            return true;
        }

        // If we haven't initialized the route selector yet, assume it'll have at least one route.
        final var localRouteSelector = routeSelector;
        if (localRouteSelector == null) {
            return true;
        }

        // If we do have a route selector, use its routes.
        return localRouteSelector.hasNext();
    }

    /**
     * @return the route from {@code failedConnection} if it should be retried, even if the connection itself is
     * unhealthy. The biggest gotcha here is that we shouldn't reuse routes from coalesced connections.
     */
    private @Nullable Route retryRoute(final @NonNull RealConnection failedConnection) {
        failedConnection.lock.lock();
        try {
            if (failedConnection.routeFailureCount != 0) {
                return null;

                // This route is still in use.
            } else if (!failedConnection.noNewExchanges) {
                return null;

            } else if (!UrlUtils.canReuseConnection(
                    failedConnection.route().getAddress().getUrl(),
                    address.getUrl())) {
                return null;

            } else {
                return failedConnection.route();
            }
        } finally {
            failedConnection.lock.unlock();
        }
    }

    @Override
    public boolean sameHostAndPort(final @NonNull HttpUrl url) {
        assert url != null;

        final var routeUrl = address.getUrl();
        return url.getPort() == routeUrl.getPort() && url.getHost().equals(routeUrl.getHost());
    }

    /**
     * @return a plan to reuse a pooled connection, or null if the pool doesn't have a connection for this address.
     * <p>
     * If {@code planToReplace} is non-null, this will swap it for a pooled connection if that pooled connection uses
     * HTTP/2. That results in fewer sockets overall and thus fewer TCP slow starts.
     */
    @Nullable
    ReusePlan planReusePooledConnection(final @Nullable ConnectPlan planToReplace,
                                        final @Nullable List<@NonNull Route> routes) {
        final var result = connectionPool.callAcquirePooledConnection(
                doExtensiveHealthChecks,
                address,
                call,
                routes,
                planToReplace != null && planToReplace.isReady());
        if (result == null) {
            return null;
        }

        // If we coalesced our connection, remember the replaced connection's route. That way if the coalesced
        // connection later fails, we don't waste a valid route.
        if (planToReplace != null) {
            nextRouteToTry = planToReplace.route();
            planToReplace.closeQuietly();
        }

        call.eventListener.connectionAcquired(call, result);
//    result.connectionListener.connectionAcquired(result, call);
        return new ReusePlan(result);
    }
}
