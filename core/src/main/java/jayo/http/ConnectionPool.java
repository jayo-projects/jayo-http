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

package jayo.http;

import jayo.http.internal.connection.RealConnectionPool;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.Objects;

/**
 * Manages reuse of HTTP and HTTP/2 connections for reduced network latency. HTTP requests that share the same
 * {@link Address} may share a {@link Connection}. This class implements the policy of which connections to keep open
 * for future use.
 */
public sealed interface ConnectionPool permits RealConnectionPool {
    static @NonNull ConnectionPool create(final int maxIdleConnections, final @NonNull Duration keepAlive) {
        Objects.requireNonNull(keepAlive);
        return new RealConnectionPool(maxIdleConnections, keepAlive);
    }

    /**
     * @return the total number of idle connections in the pool.
     */
    int idleConnectionCount();

    /**
     * @return the total number of connections in the pool.
     */
    int connectionCount();

    /**
     * Close and remove all idle connections in the pool.
     */
    void evictAll();
}
