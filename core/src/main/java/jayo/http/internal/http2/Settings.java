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

import org.jspecify.annotations.NonNull;

import java.util.Arrays;

/**
 * Settings describe characteristics of the sending peer, which are used by the receiving peer. Settings are
 * {@linkplain Http2Connection connection} scoped.
 */
public final class Settings {
    /**
     * Bitfield of which flags that values.
     */
    private int set = 0;

    /**
     * Flag values.
     */
    private final int @NonNull [] values = new int[COUNT];

    /**
     * @return -1 if unset.
     */
    int headerTableSize() {
        final var bit = 1 << HEADER_TABLE_SIZE;
        return ((bit & set) != 0) ? values[HEADER_TABLE_SIZE] : -1;
    }

    int initialWindowSize() {
        // val bit = 1 shl INITIAL_WINDOW_SIZE
        final var bit = 1 << INITIAL_WINDOW_SIZE;
        return ((bit & set) != 0) ? values[INITIAL_WINDOW_SIZE] : DEFAULT_INITIAL_WINDOW_SIZE;
    }

    void clear() {
        set = 0;
        Arrays.fill(values, 0);
    }

    @NonNull
    Settings set(final int id, final int value) {
        if (id < 0 || id >= values.length) {
            return this; // Discard unknown settings.
        }

        final var bit = 1 << id;
        set = set | bit;
        values[id] = value;
        return this;
    }

    /**
     * @return true if a value has been assigned for the setting {@code id}.
     */
    boolean isSet(final int id) {
        int bit = 1 << id;
        return (set & bit) != 0;
    }

    /**
     * @return the value for the setting {@code id}, or 0 if unset.
     */
    int get(final int id) {
        return values[id];
    }

    /**
     * @return the number of settings that have values assigned.
     */
    int size() {
        return Integer.bitCount(set);
    }

    // TODO: honor this setting.
    boolean enablePush(final boolean defaultValue) {
        final var bit = 1 << ENABLE_PUSH;
        return ((bit & set) != 0) ? values[ENABLE_PUSH] == 1 : defaultValue;
    }

    public int maxConcurrentStreams() {
        final var bit = 1 << MAX_CONCURRENT_STREAMS;
        return ((bit & set) != 0) ? values[MAX_CONCURRENT_STREAMS] : Integer.MAX_VALUE;
    }

    int maxFrameSize(final int defaultValue) {
        final var bit = 1 << MAX_FRAME_SIZE;
        return ((bit & set) != 0) ? values[MAX_FRAME_SIZE] : defaultValue;
    }

    int maxHeaderListSize(final int defaultValue) {
        final var bit = 1 << MAX_HEADER_LIST_SIZE;
        return ((bit & set) != 0) ? values[MAX_HEADER_LIST_SIZE] : defaultValue;
    }

    /**
     * Writes {@code other} into this. If any setting is populated by this and {@code other}, the value and flags from
     * {@code other} will be kept.
     */
    public @NonNull Settings merge(final @NonNull Settings other) {
        for (var i = 0; i < COUNT; i++) {
            if (!other.isSet(i)) {
                continue;
            }
            set(i, other.get(i));
        }
        return this;
    }

    /**
     * From the HTTP/2 specs, the default initial window size for all streams is 64 KiB. (Chrome 25 uses 10 MiB).
     */
    static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;

    /**
     * HTTP/2: Size in bytes of the table used to decode the sender's header blocks.
     */
    static final int HEADER_TABLE_SIZE = 1;

    /**
     * HTTP/2: The peer must not send a PUSH_PROMISE frame when this is 0.
     */
    static final int ENABLE_PUSH = 2;

    /**
     * Sender's maximum number of concurrent streams.
     */
    static final int MAX_CONCURRENT_STREAMS = 3;

    /**
     * Window size in bytes.
     */
    static final int INITIAL_WINDOW_SIZE = 4;

    /**
     * HTTP/2: Size in bytes of the largest frame payload the sender will accept.
     */
    static final int MAX_FRAME_SIZE = 5;

    /**
     * HTTP/2: Advisory only. Size in bytes of the largest header list the sender will accept.
     */
    static final int MAX_HEADER_LIST_SIZE = 6;

    /**
     * Total number of settings.
     */
    static final int COUNT = 10;
}
