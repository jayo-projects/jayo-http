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

package jayo.http.internal.ws;

import jayo.http.Headers;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Models the contents of a {@code Sec-WebSocket-Extensions} response header. Jayo HTTP honors one extension
 * {@code permessage-deflate} and four parameters, {@code client_max_window_bits}, {@code client_no_context_takeover},
 * {@code server_max_window_bits}, and {@code server_no_context_takeover}.
 * <p>
 * Typically, this will look like one of the following:
 * <pre>
 * {@code
 * Sec-WebSocket-Extensions: permessage-deflate
 * Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits="15"
 * Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits=15
 * Sec-WebSocket-Extensions: permessage-deflate; client_no_context_takeover
 * Sec-WebSocket-Extensions: permessage-deflate; server_max_window_bits="15"
 * Sec-WebSocket-Extensions: permessage-deflate; server_max_window_bits=15
 * Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover
 * Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover;
 * client_no_context_takeover
 * Sec-WebSocket-Extensions: permessage-deflate; server_max_window_bits="15";
 * client_max_window_bits="15"; server_no_context_takeover; client_no_context_takeover
 * }
 * </pre>
 * If any other extension or parameter is specified, then {@link #unknownValues} will be true. Such responses should be
 * refused as their web socket extensions will not be understood.
 * <p>
 * Note that {@link java.util.zip.Deflater} is hardcoded to use 15 bits (32 KiB) for {@code client_max_window_bits} and
 * {@link java.util.zip.Inflater} is hardcoded to use 15 bits (32 KiB) for {@code server_max_window_bits}. This harms
 * our ability to support these parameters:
 * <ul>
 * <li>If {@code client_max_window_bits} is less than 15, Jayo HTTP must close the web socket with code 1010. Otherwise,
 * it would compress values in a way that servers could not decompress.
 * <li>If {@code server_max_window_bits} is less than 15, Jayo HTTP will waste memory on an oversized buffer.
 * </ul>
 * See <a href="https://tools.ietf.org/html/rfc7692#section-7.1">RFC 7692</a> for details on the negotiation process.
 */
final class WebSocketExtensions {
    /**
     * True if the agreed upon extensions includes the {@code permessage-deflate} extension.
     */
    final boolean perMessageDeflate;
    /**
     * Should be a value in [8..15]. Only 15 is acceptable by Jayo HTTP as Java APIs are limited.
     */
    final @Nullable Integer clientMaxWindowBits;
    /**
     * True if the agreed upon extension parameters includes "client_no_context_takeover".
     */
    final boolean clientNoContextTakeover;
    /**
     * Should be a value in [8..15]. Any value in that range is acceptable by Jayo HTTP.
     */
    final @Nullable Integer serverMaxWindowBits;
    /**
     * True if the agreed upon extension parameters includes "server_no_context_takeover".
     */
    final boolean serverNoContextTakeover;
    /**
     * True if the agreed upon extensions or parameters contained values unrecognized by Jayo HTTP. Typically, this
     * indicates that the client will need to close the web socket with code 1010.
     */
    final boolean unknownValues;

    WebSocketExtensions(final boolean perMessageDeflate,
                        final @Nullable Integer clientMaxWindowBits,
                        final boolean clientNoContextTakeover,
                        final @Nullable Integer serverMaxWindowBits,
                        final boolean serverNoContextTakeover,
                        final boolean unknownValues) {
        this.perMessageDeflate = perMessageDeflate;
        this.clientMaxWindowBits = clientMaxWindowBits;
        this.clientNoContextTakeover = clientNoContextTakeover;
        this.serverMaxWindowBits = serverMaxWindowBits;
        this.serverNoContextTakeover = serverNoContextTakeover;
        this.unknownValues = unknownValues;
    }

    boolean noContextTakeover(final boolean clientOriginated) {
        return clientOriginated ?
                clientNoContextTakeover : // Client is deflating.
                serverNoContextTakeover; // Server is deflating.
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof WebSocketExtensions that)) {
            return false;
        }
        return perMessageDeflate == that.perMessageDeflate
                && clientNoContextTakeover == that.clientNoContextTakeover
                && serverNoContextTakeover == that.serverNoContextTakeover
                && unknownValues == that.unknownValues
                && Objects.equals(clientMaxWindowBits, that.clientMaxWindowBits)
                && Objects.equals(serverMaxWindowBits, that.serverMaxWindowBits);
    }

    @Override
    public @NonNull String toString() {
        return "WebSocketExtensions{" +
                "perMessageDeflate=" + perMessageDeflate +
                ", clientMaxWindowBits=" + clientMaxWindowBits +
                ", clientNoContextTakeover=" + clientNoContextTakeover +
                ", serverMaxWindowBits=" + serverMaxWindowBits +
                ", serverNoContextTakeover=" + serverNoContextTakeover +
                ", unknownValues=" + unknownValues +
                '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(perMessageDeflate, clientMaxWindowBits, clientNoContextTakeover, serverMaxWindowBits,
                serverNoContextTakeover, unknownValues);
    }

    private static final String HEADER_WEB_SOCKET_EXTENSION = "Sec-WebSocket-Extensions";

    static @NonNull WebSocketExtensions parse(final @NonNull Headers responseHeaders) {
        // Note that this code does case-insensitive comparisons, even though the spec doesn't specify whether
        // extension tokens and parameters are case-insensitive or not.

        var compressionEnabled = false;
        Integer clientMaxWindowBits = null;
        var clientNoContextTakeover = false;
        Integer serverMaxWindowBits = null;
        var serverNoContextTakeover = false;
        var unexpectedValues = false;

        // Parse each header.
        for (var i = 0; i < responseHeaders.size(); i++) {
            if (!responseHeaders.name(i).equalsIgnoreCase(HEADER_WEB_SOCKET_EXTENSION)) {
                continue; // Not a header we're interested in.
            }
            final var header = responseHeaders.value(i);

            // Parse each extension.
            var pos = 0;
            while (pos < header.length()) {
                var extensionEnd = header.indexOf(',', pos);
                if (extensionEnd == -1) {
                    extensionEnd = header.length();
                }
                var extensionTokenEnd = header.indexOf(';', pos);
                if (extensionTokenEnd == -1 || extensionTokenEnd > extensionEnd) {
                    extensionTokenEnd = extensionEnd;
                }
                final var extensionToken = header.substring(pos, extensionTokenEnd).trim();
                pos = extensionTokenEnd + 1;

                if (extensionToken.equalsIgnoreCase("permessage-deflate")) {
                    if (compressionEnabled) {
                        unexpectedValues = true; // Repeated extension!
                    }
                    compressionEnabled = true;

                    // Parse each permessage-deflate parameter.
                    while (pos < extensionEnd) {
                        var parameterEnd = header.indexOf(';', pos);
                        if (parameterEnd == -1 || parameterEnd > extensionEnd) {
                            parameterEnd = extensionEnd;
                        }
                        var equals = header.indexOf('=', pos);
                        if (equals == -1 || equals > parameterEnd) {
                            equals = parameterEnd;
                        }
                        final var name = header.substring(pos, equals).trim();
                        final var value = (equals < parameterEnd)
                                ? removeSurroundingDoubleQuote(header.substring(equals + 1, parameterEnd).trim())
                                : null;
                        pos = parameterEnd + 1;

                        if (name.equalsIgnoreCase("client_max_window_bits")) {
                            if (clientMaxWindowBits != null) {
                                unexpectedValues = true; // Repeated parameter!
                            }
                            clientMaxWindowBits = toIntOrNull(value);
                            if (clientMaxWindowBits == null) {
                                unexpectedValues = true; // Not an int!
                            }
                        } else if (name.equalsIgnoreCase("client_no_context_takeover")) {
                            if (clientNoContextTakeover) {
                                unexpectedValues = true; // Repeated parameter!
                            }
                            if (value != null) {
                                unexpectedValues = true; // Unexpected value!
                            }
                            clientNoContextTakeover = true;
                        } else if (name.equalsIgnoreCase("server_max_window_bits")) {
                            if (serverMaxWindowBits != null) {
                                unexpectedValues = true; // Repeated parameter!
                            }
                            serverMaxWindowBits = toIntOrNull(value);
                            if (serverMaxWindowBits == null) {
                                unexpectedValues = true; // Not an int!
                            }
                        } else if (name.equalsIgnoreCase("server_no_context_takeover")) {
                            if (serverNoContextTakeover) {
                                unexpectedValues = true; // Repeated parameter!
                            }
                            if (value != null) {
                                unexpectedValues = true; // Unexpected value!
                            }
                            serverNoContextTakeover = true;
                        } else {
                            unexpectedValues = true; // Unexpected parameter.
                        }
                    }
                } else {
                    unexpectedValues = true; // Unexpected extension.
                }
            }
        }

        return new WebSocketExtensions(
                compressionEnabled,
                clientMaxWindowBits,
                clientNoContextTakeover,
                serverMaxWindowBits,
                serverNoContextTakeover,
                unexpectedValues
        );
    }

    private static final @NonNull String DOUBLE_QUOTE = "\"";

    /**
     * Removes the double quote from both the start and the end of this string if and only if it starts with and ends
     * with them. Otherwise, returns this string unchanged.
     */
    private static String removeSurroundingDoubleQuote(final @NonNull String string) {
        assert string != null;

        if ((string.length() > 1) && string.startsWith(DOUBLE_QUOTE) && string.endsWith(DOUBLE_QUOTE)) {
            return string.substring(1, string.length() - 1);
        }
        return string;
    }

    private static @Nullable Integer toIntOrNull(final @Nullable String value) {
        try {
            return (value != null) ? Integer.parseInt(value) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
