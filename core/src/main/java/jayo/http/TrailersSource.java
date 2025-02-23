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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Returns the trailers that follow an HTTP response, blocking if they aren't ready yet.
 * <p>
 * Most callers won't need this interface, and should use {@link ClientResponse#trailers()} instead.
 * <p>
 * This interface is for test and production code that creates {@link ClientResponse} instances without making an HTTP
 * call to a remote server.
 *
 * @implSpec Implementations of this interface should respond to {@link Call#cancel()} by immediately throwing a
 * {@linkplain jayo.JayoException JayoException}.
 */
public interface TrailersSource {
    default @Nullable Headers peek() {
        return null;
    }

    @NonNull
    Headers get();

    @NonNull
    TrailersSource EMPTY = new TrailersSource() {
        @Override
        public @NonNull Headers peek() {
            return Headers.EMPTY;
        }

        @Override
        public @NonNull Headers get() {
            return Headers.EMPTY;
        }
    };
}
