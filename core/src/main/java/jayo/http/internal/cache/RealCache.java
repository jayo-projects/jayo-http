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

package jayo.http.internal.cache;

import jayo.RawWriter;
import jayo.http.Cache;
import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class RealCache implements Cache {
    private final @NonNull Lock lock = new ReentrantLock();

    @Override
    public void close() {

    }

    @Override
    public void flush() {

    }

    @Nullable
    ClientResponse get(final @NonNull ClientRequest clientRequest) {
        return null;
    }

    void trackResponse(final @NonNull CacheStrategy strategy) {
        assert strategy != null;

        lock.lock();
        try {

        } finally {
            lock.unlock();
        }
    }

    void trackConditionalCacheHit() {
        try {

        } finally {
            lock.unlock();
        }
    }

    void update(final @NonNull ClientResponse cached, final @NonNull ClientResponse network) {
        assert cached != null;
        assert network != null;
    }

    @Nullable CacheRequest put(final @NonNull ClientResponse response) {
        assert response != null;
        return null;
    }

    void remove(final @NonNull ClientRequest request) {
        assert request != null;
    }

    final class RealCacheRequest implements CacheRequest {
        @Override
        public RawWriter body() {
            return null;
        }

        @Override
        public void abort() {}
    }
}
