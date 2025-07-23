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

import jayo.bytestring.ByteString;
import org.jspecify.annotations.NonNull;

import static jayo.http.internal.Utils.format;

final class Http2 {
    // un-instantiable
    private Http2() {
    }

    /**
     * The CONNECTION_PREFACE must be encoded as UTF-8
     */
    static final @NonNull ByteString CONNECTION_PREFACE = ByteString.encode("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

    /**
     * The initial max frame size, applied independently writing to, or reading from the peer.
     */
    static final int INITIAL_MAX_FRAME_SIZE = 0x4000; // 16384

    static final int TYPE_DATA = 0x0;
    static final int TYPE_HEADERS = 0x1;
    static final int TYPE_PRIORITY = 0x2;
    static final int TYPE_RST_STREAM = 0x3;
    static final int TYPE_SETTINGS = 0x4;
    static final int TYPE_PUSH_PROMISE = 0x5;
    static final int TYPE_PING = 0x6;
    static final int TYPE_GOAWAY = 0x7;
    static final int TYPE_WINDOW_UPDATE = 0x8;
    static final int TYPE_CONTINUATION = 0x9;

    static final int FLAG_NONE = 0x0;
    static final int FLAG_ACK = 0x1; // Used for settings and ping.
    static final int FLAG_END_STREAM = 0x1; // Used for headers and data.
    static final int FLAG_END_HEADERS = 0x4; // Used for headers and continuation.
    static final int FLAG_END_PUSH_PROMISE = 0x4;
    static final int FLAG_PADDED = 0x8; // Used for headers and data.
    static final int FLAG_PRIORITY = 0x20; // Used for headers.
    static final int FLAG_COMPRESSED = 0x20; // Used for data.

    /**
     * Lookup table for valid frame types.
     */
    private static final String[] FRAME_NAMES = {
            "DATA",
            "HEADERS",
            "PRIORITY",
            "RST_STREAM",
            "SETTINGS",
            "PUSH_PROMISE",
            "PING",
            "GOAWAY",
            "WINDOW_UPDATE",
            "CONTINUATION"
    };

    /**
     * Lookup table for valid flags for DATA, HEADERS, CONTINUATION. Invalid combinations are
     * represented in binary.
     */
    private static final String[] FLAGS = new String[0x40]; // The highest bit flag is 0x20.
    private static final String[] BINARY = new String[256];

    static {
        for (var i = 0; i < 256; i++) {
            BINARY[i] = format("%8s", Integer.toBinaryString(i)).replace(' ', '0');
        }

        FLAGS[FLAG_NONE] = "";
        FLAGS[FLAG_END_STREAM] = "END_STREAM";

        final var prefixFlags = new int[]{FLAG_END_STREAM};

        FLAGS[FLAG_PADDED] = "PADDED";
        for (final var prefixFlag : prefixFlags) {
            FLAGS[prefixFlag | FLAG_PADDED] = FLAGS[prefixFlag] + "|PADDED";
        }

        FLAGS[FLAG_END_HEADERS] = "END_HEADERS"; // Same as END_PUSH_PROMISE.
        FLAGS[FLAG_PRIORITY] = "PRIORITY"; // Same as FLAG_COMPRESSED.
        FLAGS[FLAG_END_HEADERS | FLAG_PRIORITY] = "END_HEADERS|PRIORITY"; // Only valid on HEADERS.
        final var frameFlags = new int[]{FLAG_END_HEADERS, FLAG_PRIORITY, FLAG_END_HEADERS | FLAG_PRIORITY};

        for (final var frameFlag : frameFlags) {
            for (final var prefixFlag : prefixFlags) {
                FLAGS[prefixFlag | frameFlag] = FLAGS[prefixFlag] + '|' + FLAGS[frameFlag];
                FLAGS[prefixFlag | frameFlag | FLAG_PADDED] =
                        FLAGS[prefixFlag] + '|' + FLAGS[frameFlag] + "|PADDED";
            }
        }

        for (var i = 0; i < FLAGS.length; i++) { // Fill in holes with binary representation.
            if (FLAGS[i] == null) {
                FLAGS[i] = BINARY[i];
            }
        }
    }

    /**
     * @return a human-readable representation of HTTP/2 frame headers.
     * <p>
     * The format is:
     * <pre>
     * {@code
     * direction streamID length type flags
     * }
     * </pre>
     * Where direction is {@code <<} for inbound and {@code >>} for outbound.
     * <p>
     * For example, the following would indicate a HEAD request sent from the client.
     * <pre>
     * {@code
     * << 0x0000000f    12 HEADERS       END_HEADERS|END_STREAM
     * }
     * </pre>
     */
    static @NonNull String frameLog(final boolean inbound,
                                    final int streamId,
                                    final int length,
                                    final int type,
                                    final int flags) {
        final var formattedType = formattedType(type);
        final var formattedFlags = formatFlags(type, flags);
        final var direction = inbound ? "<<" : ">>";
        return format(
                "%s 0x%08x %5d %-13s %s",
                direction,
                streamId,
                length,
                formattedType,
                formattedFlags
        );
    }

    /**
     * @return a human-readable representation of a {@code WINDOW_UPDATE} frame. This frame includes the window size
     * increment instead of flags.
     */
    static @NonNull String frameLogWindowUpdate(final boolean inbound,
                                                final int streamId,
                                                final int length,
                                                final long windowSizeIncrement) {
        final var formattedType = formattedType(TYPE_WINDOW_UPDATE);
        final var direction = inbound ? "<<" : ">>";
        return format(
                "%s 0x%08x %5d %-13s %d",
                direction,
                streamId,
                length,
                formattedType,
                windowSizeIncrement
        );
    }

    static @NonNull String formattedType(final int type) {
        if (type < FRAME_NAMES.length) {
            return FRAME_NAMES[type];
        }
        return format("0x%02x", type); // else
    }

    /**
     * Looks up valid string representing flags from the table. Invalid combinations are represented in binary.
     */
    static @NonNull String formatFlags(final int type, final int flags) {
        if (flags == 0) {
            return "";
        }
        switch (type) {
            // Special case types that have 0 or 1 flag.
            case TYPE_SETTINGS, TYPE_PING -> {
                return (flags == FLAG_ACK) ? "ACK" : BINARY[flags];
            }
            case TYPE_PRIORITY, TYPE_RST_STREAM, TYPE_GOAWAY, TYPE_WINDOW_UPDATE -> {
                return BINARY[flags];
            }
        }
        final var result = (flags < FLAGS.length) ? FLAGS[flags] : BINARY[flags];
        // Special case types that have overlap flag values.
        if (type == TYPE_PUSH_PROMISE && ((flags & FLAG_END_PUSH_PROMISE) != 0)) {
            return result.replace("HEADERS", "PUSH_PROMISE"); // TODO: Avoid allocation.
        }
        if (type == TYPE_DATA && ((flags & FLAG_COMPRESSED) != 0)) {
            return result.replace("PRIORITY", "COMPRESSED"); // TODO: Avoid allocation.
        }
        return result; // else
    }
}
