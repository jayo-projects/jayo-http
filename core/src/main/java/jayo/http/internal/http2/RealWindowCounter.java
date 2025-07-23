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

package jayo.http.internal.http2;

import jayo.http.http2.WindowCounter;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class RealWindowCounter implements WindowCounter {
    private final int streamId;
    private long total = 0L;
    private long acknowledged = 0L;

    private final @NonNull Lock lock = new ReentrantLock();

    RealWindowCounter(final int streamId) {
        this.streamId = streamId;
    }

    @Override
    public int getStreamId() {
        return streamId;
    }

    @Override
    public long getTotal() {
        return total;
    }

    @Override
    public long getAcknowledged() {
        return acknowledged;
    }

    @Override
    public long getUnacknowledged() {
        lock.lock();
        try {
            return total - acknowledged;
        } finally {
            lock.unlock();
        }
    }

    void update(final long total, final long acknowledged) {
        assert total >= 0;
        assert acknowledged >= 0;

        lock.lock();
        try {
            this.total += total;
            this.acknowledged += acknowledged;

            if (this.acknowledged > this.total) {
                throw new IllegalArgumentException();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public @NonNull String toString() {
        return "WindowCounter(streamId=" + streamId +
                ", total=" + total +
                ", acknowledged=" + acknowledged +
                ", unacknowledged=" + getUnacknowledged() + ")";
    }
}
