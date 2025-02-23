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

import jayo.Endpoint;
import jayo.JayoException;
import jayo.http.Connection;
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
 * The object that is asking for a connection. Either a call or a connect policy from the pool.
 */
sealed interface ConnectionUser permits CallConnectionUser, PoolConnectionUser {
    void addPlanToCancel(final @NonNull ConnectPlan connectPlan);

    void removePlanToCancel(final @NonNull ConnectPlan connectPlan);

    void updateRouteDatabaseAfterSuccess(final @NonNull Route route);

    void connectStart(final @NonNull Route route);

    void secureConnectStart();

    void secureConnectEnd(final @Nullable Handshake handshake);

    void callConnectEnd(final @NonNull Route route, final @Nullable Protocol protocol);

    void connectionConnectEnd(final @NonNull Connection connection, final @NonNull Route route);

    void connectFailed(final @NonNull Route route,
                       final @Nullable Protocol protocol,
                       final @NonNull JayoException e);

    void connectionAcquired(final @NonNull Connection connection);

    void acquireConnectionNoEvents(final @NonNull RealConnection connection);

    @Nullable
    Endpoint releaseConnectionNoEvents();

    void connectionReleased(final @NonNull Connection connection);

    void connectionConnectionAcquired(final @NonNull RealConnection connection);

    void connectionConnectionReleased(final @NonNull RealConnection connection);

    void connectionConnectionClosed(final @NonNull RealConnection connection);

    void noNewExchanges(final @NonNull RealConnection connection);

    boolean doExtensiveHealthChecks();

    boolean isCanceled();

    @Nullable
    RealConnection candidateConnection();

    void proxySelected(final @NonNull HttpUrl url, final @Nullable Proxy proxy);
    
    void dnsStart(final @NonNull String socketHost);

    void dnsEnd(final @NonNull String socketHost, final @NonNull List<@NonNull InetAddress> result);
}
