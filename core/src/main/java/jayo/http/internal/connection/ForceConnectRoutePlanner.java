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
import jayo.http.HttpUrl;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Deque;

/**
 * A RoutePlanner that will always establish a new connection, ignoring any connection pooling
 */
final class ForceConnectRoutePlanner implements RoutePlanner {
    private final @NonNull RealRoutePlanner delegate;

    ForceConnectRoutePlanner(final @NonNull RealRoutePlanner delegate) {
        assert delegate != null;
        this.delegate = delegate;
    }

    @Override
    public @NonNull Address getAddress() {
        return delegate.getAddress();
    }

    @Override
    public @NonNull Deque<Plan> getDeferredPlans() {
        return delegate.getDeferredPlans();
    }

    @Override
    public boolean isCanceled() {
        return delegate.isCanceled();
    }

    @Override
    public @NonNull Plan plan() {
        return delegate.planConnect(); // not delegate.plan() !
    }

    @Override
    public boolean hasNext(final @Nullable RealConnection failedConnection) {
        return delegate.hasNext(failedConnection);
    }

    @Override
    public boolean sameHostAndPort(final @NonNull HttpUrl url) {
        return delegate.sameHostAndPort(url);
    }
}
