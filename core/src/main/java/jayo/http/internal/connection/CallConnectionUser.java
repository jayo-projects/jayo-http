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

import jayo.Socket;
import jayo.JayoException;
import jayo.http.Connection;
import jayo.http.EventListener;
import jayo.http.HttpUrl;
import jayo.http.Route;
import jayo.network.Proxy;
import jayo.tls.Handshake;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.util.List;

/**
 * A connection user that is bound with a specific {@link RealCall}.
 */
final class CallConnectionUser implements ConnectionUser {
    private final RealCall call;
    private final RealInterceptorChain chain;

    CallConnectionUser(RealCall call, RealInterceptorChain chain) {
        this.call = call;
        this.chain = chain;
    }

    private EventListener getEventListener() {
        return call.eventListener;
    }

    @Override
    public void addPlanToCancel(final @NonNull ConnectPlan connectPlan) {
        call.plansToCancel.add(connectPlan);
    }

    @Override
    public void removePlanToCancel(final @NonNull ConnectPlan connectPlan) {
        call.plansToCancel.remove(connectPlan);
    }

    @Override
    public void updateRouteDatabaseAfterSuccess(final @NonNull Route route) {
        call.client.routeDatabase.connected(route);
    }

    @Override
    public void connectStart(final @NonNull Route route) {
        getEventListener().connectStart(call, route.getSocketAddress(), route.getAddress().getProxy());
        //poolConnectionListener.connectStart(route, call);
    }

    @Override
    public void connectFailed(final @NonNull Route route,
                              final @Nullable Protocol protocol,
                              final @NonNull JayoException e) {
        getEventListener()
                .connectFailed(call, route.getSocketAddress(), route.getAddress().getProxy(), null, e);
        //poolConnectionListener.connectFailed(route, call, e);
    }

    @Override
    public void secureConnectStart() {
        getEventListener().secureConnectStart(call);
    }

    @Override
    public void secureConnectEnd(final @Nullable Handshake handshake) {
        getEventListener().secureConnectEnd(call, handshake);
    }

    @Override
    public void callConnectEnd(final @NonNull Route route, final @Nullable Protocol protocol) {
        getEventListener().connectEnd(call, route.getSocketAddress(), route.getAddress().getProxy(), protocol);
    }

    @Override
    public void connectionConnectEnd(final @NonNull Connection connection, final @NonNull Route route) {
        //poolConnectionListener.connectEnd(connection, route, call);
    }

    @Override
    public void connectionAcquired(final @NonNull Connection connection) {
        getEventListener().connectionAcquired(call, connection);
    }

    @Override
    public void acquireConnectionNoEvents(final @NonNull RealConnection connection) {
        call.acquireConnectionNoEvents(connection);
    }

    @Override
    public Socket releaseConnectionNoEvents() {
        return call.releaseConnectionNoEvents();
    }

    @Override
    public void connectionReleased(final @NonNull Connection connection) {
        getEventListener().connectionReleased(call, connection);
    }

    @Override
    public void connectionConnectionAcquired(final @NonNull RealConnection connection) {
        //connection.connectionListener.connectionAcquired(connection, call);
    }

    @Override
    public void connectionConnectionReleased(final @NonNull RealConnection connection) {
        //connection.connectionListener.connectionReleased(connection, call);
    }

    @Override
    public void connectionConnectionClosed(final @NonNull RealConnection connection) {
        //connection.connectionListener.connectionClosed(connection);
    }

    @Override
    public void noNewExchanges(final @NonNull RealConnection connection) {
        //connection.connectionListener.noNewExchanges(connection);
    }

    @Override
    public boolean doExtensiveHealthChecks() {
        return !chain.request().getMethod().equals("GET");
    }

    @Override
    public boolean isCanceled() {
        return call.isCanceled();
    }

    @Override
    public RealConnection candidateConnection() {
        return call.connection;
    }

    @Override
    public void proxySelected(final @NonNull HttpUrl url, final @Nullable Proxy proxy) {
        getEventListener().proxySelected(call, url, proxy);
    }

    @Override
    public void dnsStart(final @NonNull String socketHost) {
        getEventListener().dnsStart(call, socketHost);
    }

    @Override
    public void dnsEnd(final @NonNull String socketHost, final @NonNull List<@NonNull InetAddress> result) {
        getEventListener().dnsEnd(call, socketHost, result);
    }
}
