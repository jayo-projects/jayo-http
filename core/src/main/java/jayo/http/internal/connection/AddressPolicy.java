/*
 * Copyright (c)  2025-present, pull-vert and Jayo contributors.
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

/**
 * A policy for how the pool should treat a specific address.
 */
final class AddressPolicy {
    /**
     * How many concurrent calls should be possible to make at any time.
     * The pool will routinely try to pre-emptively open connections to satisfy this minimum.
     * Connections will still be closed if they idle beyond the keep-alive but will be replaced.
     */
    public final int minimumConcurrentCalls;
    /**
     * How long to wait to retry pre-emptive connection attempts that fail.
     */
    public final long backoffDelayMillis;
    /**
     * How much jitter to introduce in connection retry backoff delays
     */
    public final int backoffJitterMillis;

    public AddressPolicy(final int minimumConcurrentCalls,
                         final long backoffDelayMillis,
                         final int backoffJitterMillis) {
        this.minimumConcurrentCalls = minimumConcurrentCalls;
        this.backoffDelayMillis = backoffDelayMillis;
        this.backoffJitterMillis = backoffJitterMillis;
    }

    public AddressPolicy() {
        this(0, 60 * 1000, 100);
    }
}
