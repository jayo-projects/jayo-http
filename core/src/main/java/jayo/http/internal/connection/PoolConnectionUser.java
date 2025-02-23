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
 * A user that is a connection pool creating connections in the background without an intent to immediately use them.
 */
public enum PoolConnectionUser implements ConnectionUser {
    INSTANCE;

    @Override
    public void addPlanToCancel(final @NonNull ConnectPlan connectPlan) {
    }

    @Override
    public void removePlanToCancel(final @NonNull ConnectPlan connectPlan) {
    }

    @Override
    public void updateRouteDatabaseAfterSuccess(final @NonNull Route route) {
    }

    @Override
    public void connectStart(final @NonNull Route route) {
    }

    @Override
    public void secureConnectStart() {
    }

    @Override
    public void secureConnectEnd(final @Nullable Handshake handshake) {
    }

    @Override
    public void callConnectEnd(final @NonNull Route route, final @Nullable Protocol protocol) {
    }

    @Override
    public void connectionConnectEnd(final @NonNull Connection connection, final @NonNull Route route) {
    }

    @Override
    public void connectFailed(final @NonNull Route route,
                              final @Nullable Protocol protocol,
                              final @NonNull JayoException e) {
    }

    @Override
    public void connectionAcquired(final @NonNull Connection connection) {
    }

    @Override
    public void acquireConnectionNoEvents(final @NonNull RealConnection connection) {
    }

    @Override
    public @Nullable Endpoint releaseConnectionNoEvents() {
        return null;
    }

    @Override
    public void connectionReleased(final @NonNull Connection connection) {
    }

    @Override
    public void connectionConnectionAcquired(final @NonNull RealConnection connection) {
    }

    @Override
    public void connectionConnectionReleased(final @NonNull RealConnection connection) {
    }

    @Override
    public void connectionConnectionClosed(final @NonNull RealConnection connection) {
    }

    @Override
    public void noNewExchanges(final @NonNull RealConnection connection) {
    }

    @Override
    public boolean doExtensiveHealthChecks() {
        return false;
    }

    @Override
    public boolean isCanceled() {
        return false;
    }

    @Override
    public @Nullable RealConnection candidateConnection() {
        return null;
    }

    @Override
    public void dnsStart(final @NonNull String socketHost) {
    }

    @Override
    public void dnsEnd(final @NonNull String socketHost, final @NonNull List<InetAddress> result) {
    }

    @Override
    public void proxySelected(final @NonNull HttpUrl url, final @Nullable Proxy proxy) {
    }
}
