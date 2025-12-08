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

package jayo.http.internal.http;

import jayo.JayoProtocolException;
import jayo.http.ClientResponse;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;

/**
 * An HTTP response status line like {@code "HTTP/1.1 200 OK"}.
 */
public final class StatusLine {
    public static @NonNull StatusLine get(final @NonNull ClientResponse response) {
        assert response != null;
        return new StatusLine(response.getProtocol(), response.getStatusCode(), response.getStatusMessage());
    }


    public static @NonNull StatusLine parse(final @NonNull String statusLine) {
        assert statusLine != null;

        // H T T P / 1 . 1   2 0 0   T e m p o r a r y   R e d i r e c t
        // 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0

        // Parse protocol like "HTTP/1.1" followed by a space.
        final int codeStart;
        final Protocol protocol;
        if (statusLine.startsWith("HTTP/1.")) {
            if (statusLine.length() < 9 || statusLine.charAt(8) != ' ') {
                throw new JayoProtocolException("Unexpected status line: " + statusLine);
            }
            int httpMinorVersion = statusLine.charAt(7) - '0';
            codeStart = 9;
            protocol = switch (httpMinorVersion) {
                case 0 -> Protocol.HTTP_1_0;
                case 1 -> Protocol.HTTP_1_1;
                default -> throw new JayoProtocolException("Unexpected status line: " + statusLine);
            };
        } else if (statusLine.startsWith("ICY ")) {
            // Shoutcast uses ICY instead of "HTTP/1.0".
            protocol = Protocol.HTTP_1_0;
            codeStart = 4;
        } else if (statusLine.startsWith("SOURCETABLE ")) {
            // NTRIP r1 uses SOURCETABLE instead of HTTP/1.1
            protocol = Protocol.HTTP_1_1;
            codeStart = 12;
        } else {
            throw new JayoProtocolException("Unexpected status line: " + statusLine);
        }

        // Parse response code like "200". Always 3 digits.
        if (statusLine.length() < codeStart + 3) {
            throw new JayoProtocolException("Unexpected status line: " + statusLine);
        }
        int code;
        try {
            code = Integer.parseInt(statusLine.substring(codeStart, codeStart + 3));
        } catch (NumberFormatException e) {
            throw new JayoProtocolException("Unexpected status line: " + statusLine);
        }

        // Parse an optional response message like "OK" or "Not Modified". If it exists, it is separated from the
        // response code by a space.
        String message = "";
        if (statusLine.length() > codeStart + 3) {
            if (statusLine.charAt(codeStart + 3) != ' ') {
                throw new JayoProtocolException("Unexpected status line: " + statusLine);
            }
            message = statusLine.substring(codeStart + 4);
        }

        return new StatusLine(protocol, code, message);
    }

    public @NonNull Protocol protocol;
    public int code;
    public @NonNull String message;

    public StatusLine(final @NonNull Protocol protocol,
                       final int code,
                       final @NonNull String message) {
        assert protocol != null;
        assert message != null;

        this.protocol = protocol;
        this.code = code;
        this.message = message;
    }

    @Override
    public String toString() {
        return (protocol == Protocol.HTTP_1_0) ? "HTTP/1.0 " : "HTTP/1.1 " + code + " " + message;
    }
}
