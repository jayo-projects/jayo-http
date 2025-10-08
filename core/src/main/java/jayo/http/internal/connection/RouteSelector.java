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

import jayo.http.Address;
import jayo.http.Route;
import jayo.network.JayoSocketException;
import jayo.network.JayoUnknownHostException;
import jayo.network.Proxy;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static jayo.tools.HostnameUtils.canParseAsIpAddress;

/**
 * Selects routes to connect to an origin server. Each connection requires a choice of proxy server, IP address, and
 * TLS mode. Connections may also be recycled.
 */
final class RouteSelector {
    private final @NonNull Address address;
    private final @NonNull RouteDatabase routeDatabase;
    private final @NonNull RealCall call;
    private final boolean fastFallback;

    private boolean firstRoute = true;

    // State for negotiating the next socket address to use.
    private @NonNull List<@NonNull InetSocketAddress> inetSocketAddresses = List.of();

    // State for negotiating failed routes
    private final @NonNull List<Route> postponedRoutes = new ArrayList<>();

    RouteSelector(final @NonNull Address address,
                  final @NonNull RouteDatabase routeDatabase,
                  final @NonNull RealCall call,
                  final boolean fastFallback) {
        assert address != null;
        assert routeDatabase != null;
        assert call != null;

        this.address = address;
        this.routeDatabase = routeDatabase;
        this.call = call;
        this.fastFallback = fastFallback;

        // Prepares the proxy to try.
        call.eventListener.proxySelected(call, address.getUrl(), address.getProxy());
    }

    /**
     * @return true if there's another set of routes to attempt. Every address has at least one route.
     */
    boolean hasNext() {
        return firstRoute || !postponedRoutes.isEmpty();
    }

    @NonNull
    Selection next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // Compute the next set of routes to attempt.
        final var routes = new ArrayList<Route>();
        if (firstRoute) {
            try {
                final var proxy = address.getProxy();
                resetNextInetSocketAddresses(proxy);

                for (final var inetSocketAddress : inetSocketAddresses) {
                    final var route = new RealRoute(address, inetSocketAddress);
                    if (routeDatabase.shouldPostpone(route)) {
                        postponedRoutes.add(route);
                    } else {
                        routes.add(route);
                    }
                }
            } finally {
                firstRoute = false;
            }
        }

        if (routes.isEmpty()) {
            // We've exhausted all Proxies so fallback to the postponed routes.
            routes.addAll(postponedRoutes);
            postponedRoutes.clear();
        }

        return new Selection(routes);
    }

    private void resetNextInetSocketAddresses(final @Nullable Proxy proxy) {
        // Clear the addresses. Necessary if getAllByName() below throws!
        final var mutableInetSocketAddresses = new ArrayList<InetSocketAddress>();
        inetSocketAddresses = mutableInetSocketAddresses;

        final String socketHost;
        final int socketPort;
        if (proxy == null || proxy instanceof Proxy.Socks) {
            socketHost = address.getUrl().getHost();
            socketPort = address.getUrl().getPort();
        } else {
            socketHost = proxy.getHost();
            socketPort = proxy.getPort();
        }

        if (socketPort < 1 || socketPort > 65535) {
            throw new JayoSocketException("No route to " + socketHost + ":" + socketPort + "; port is out of range");
        }

        if (proxy instanceof Proxy.Socks) {
            mutableInetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort));
        } else {
            final List<InetAddress> addresses;
            if (canParseAsIpAddress(socketHost)) {
                try {
                    addresses = List.of(InetAddress.getByName(socketHost));
                } catch (UnknownHostException e) {
                    throw new JayoUnknownHostException(e);
                }
            } else {
                call.eventListener.dnsStart(call, socketHost);

                final var result = address.getDns().lookup(socketHost);
                if (result.isEmpty()) {
                    throw new JayoUnknownHostException(address.getDns() + " returned no addresses for " + socketHost);
                }

                call.eventListener.dnsEnd(call, socketHost, result);
                addresses = result;
            }

            // Try each address for the best behavior in mixed IPv4/IPv6 environments.
            final var orderedAddresses = fastFallback
                    ? reorderForHappyEyeballs(addresses)
                    : addresses;

            for (final var inetAddress : orderedAddresses) {
                mutableInetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
            }
        }
    }

    /**
     * A set of selected Routes.
     */
    static final class Selection {
        final @NonNull List<@NonNull Route> routes;
        private int nextRouteIndex = 0;

        private Selection(final @NonNull List<Route> routes) {
            assert routes != null;
            this.routes = routes;
        }

        boolean hasNext() {
            return nextRouteIndex < routes.size();
        }

        @NonNull
        Route next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return routes.get(nextRouteIndex++);
        }
    }

    /**
     * Implementation of HappyEyeballs Sorting Addresses.
     * <p>
     * The current implementation does not address any of:
     * <ul>
     * <li>Async DNS split by IP class
     * <li>Stateful handling of connectivity results
     * <li>The prioritization of addresses
     * </ul>
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc8305#section-4">RFC 8305 - section 4</a>
     */
    static @NonNull List<@NonNull InetAddress> reorderForHappyEyeballs(
            final @NonNull List<@NonNull InetAddress> addresses) {
        assert addresses != null;

        if (addresses.size() < 2) {
            return addresses;
        }

        final var ipv6 = new ArrayList<InetAddress>();
        final var ipv4 = new ArrayList<InetAddress>();
        for (final var address : addresses) {
            if (address instanceof Inet6Address) {
                ipv6.add(address);
            } else {
                ipv4.add(address);
            }
        }

        if (ipv6.isEmpty() || ipv4.isEmpty()) {
            return addresses;
        } else {
            return interleave(ipv6, ipv4);
        }
    }

    private static @NonNull List<InetAddress> interleave(final @NonNull Iterable<@NonNull InetAddress> ipv6,
                                                         final @NonNull Iterable<@NonNull InetAddress> ipv4) {
        final var itIpv6 = ipv6.iterator();
        final var itIpv4 = ipv4.iterator();

        final var result = new ArrayList<InetAddress>();
        while (itIpv6.hasNext() || itIpv4.hasNext()) {
            if (itIpv6.hasNext()) {
                result.add(itIpv6.next());
            }
            if (itIpv4.hasNext()) {
                result.add(itIpv4.next());
            }
        }
        return result;
    }
}
