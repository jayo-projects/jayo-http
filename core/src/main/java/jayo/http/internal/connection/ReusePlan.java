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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import jayo.http.internal.connection.RoutePlanner.ConnectResult;
import jayo.http.internal.connection.RoutePlanner.Plan;

/**
 * Reuse a connection from the pool.
 */
record ReusePlan(@NonNull RealConnection connection) implements Plan {
    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public @NonNull ConnectResult connectTcp() {
        throw new IllegalStateException("already connected");
    }

    @Override
    public @NonNull ConnectResult connectTlsEtc() {
        throw new IllegalStateException("already connected");
    }

    @Override
    public @NonNull RealConnection handleSuccess() {
        return connection;
    }

    @Override
    public void cancel() {
        throw new IllegalStateException("unexpected cancel");
    }

    @Override
    public @Nullable Plan retry() {
        throw new IllegalStateException("unexpected retry");
    }
}
