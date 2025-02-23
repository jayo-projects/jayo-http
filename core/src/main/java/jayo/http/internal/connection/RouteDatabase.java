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

import jayo.http.Route;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A denylist of failed routes to avoid when creating a new connection to a target address. This is used so that Jayo
 * HTTP can learn from its mistakes: if there was a failure attempting to connect to a specific IP address or proxy
 * server, that failure is remembered and alternate routes are preferred.
 */
final class RouteDatabase {
    private final @NonNull Set<Route> failedRoutes = new HashSet<>();
    private final @NonNull Lock lock = new ReentrantLock(); // todo we could use ReadWriteLock here, but should we ?

    // for tests
    Set<Route> getFailedRoutes() {
        lock.lock();
        try {
            return Set.copyOf(failedRoutes);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a failure connecting to {@code failedRoute}.
     */
    void failed(final @NonNull Route failedRoute) {
        assert failedRoute != null;

        lock.lock();
        try {
            failedRoutes.add(failedRoute);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records success connecting to {@code route}.
     */
    void connected(final @NonNull Route route) {
        assert route != null;

        lock.lock();
        try {
            failedRoutes.remove(route);
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return true if {@code route} has failed recently and should be avoided.
     */
    boolean shouldPostpone(final @NonNull Route route) {
        assert route != null;

        lock.lock();
        try {
            return failedRoutes.contains(route);
        } finally {
            lock.unlock();
        }
    }
}
