/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
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

import jayo.Buffer;
import jayo.JayoEOFException;
import jayo.bytestring.ByteString;
import jayo.http.*;
import jayo.http.internal.RealChallenge;
import jayo.http.internal.Utils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static java.lang.System.Logger.Level.WARNING;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static jayo.http.internal.Utils.headersContentLength;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_CONTINUE;

public final class HttpHeaders {
    private static final System.Logger LOGGER = System.getLogger("jayo.http.HttpHeaders");
    private static final @NonNull ByteString QUOTED_STRING_DELIMITERS = ByteString.encode("\"\\");
    private static final @NonNull ByteString TOKEN_DELIMITERS = ByteString.encode("\t ,=");

    // un-instantiable
    private HttpHeaders() {
    }

    /**
     * Parse RFC 7235 challenges. This is awkward because we need to look ahead to know how to interpret a token.
     * <p>
     * For example, the first line has a parameter name/value pair and the second line has a single token68:
     * <pre>
     * {@code
     * WWW-Authenticate: Digest foo=bar
     * WWW-Authenticate: Digest foo=
     * }
     * </pre>
     * Similarly, the first line has one challenge and the second line has two challenges:
     * <pre>
     * {@code
     * WWW-Authenticate: Digest ,foo=bar
     * WWW-Authenticate: Digest ,foo
     * }
     * </pre>
     */
    public static @NonNull List<Challenge> parseChallenges(final @NonNull Headers headers, final @NonNull String headerName) {
        assert headers != null;
        assert headerName != null;

        final var result = new ArrayList<Challenge>();
        for (var h = 0; h < headers.size(); h++) {
            if (headerName.equalsIgnoreCase(headers.name(h))) {
                final var header = Buffer.create()
                        .write(headers.value(h));
                try {
                    readChallengeHeader(header, result);
                } catch (JayoEOFException e) {
                    LOGGER.log(WARNING, "Unable to parse challenge", e);
                }
            }
        }
        return result;
    }

    private static void readChallengeHeader(final @NonNull Buffer buffer, final @NonNull List<Challenge> result) {
        assert buffer != null;
        assert result != null;

        String peek = null;

        while (true) {
            // Read a scheme name for this challenge if we don't have one already.
            if (peek == null) {
                skipCommasAndWhitespace(buffer);
                peek = readToken(buffer);
                if (peek == null) {
                    return;
                }
            }

            final var schemeName = peek;

            // Read a token68, a sequence of parameters, or nothing.
            final var commaPrefixed = skipCommasAndWhitespace(buffer);
            peek = readToken(buffer);
            if (peek == null) {
                if (!buffer.exhausted()) {
                    return; // Expected a token; got something else.
                }
                result.add(new RealChallenge(schemeName, Map.of()));
                return;
            }

            var eqCount = Utils.skipAll(buffer, (byte) ((int) '='));
            final var commaSuffixed = skipCommasAndWhitespace(buffer);

            // It's a token68 because there isn't a value after it.
            if (!commaPrefixed && (commaSuffixed || buffer.exhausted())) {
                result.add(
                        new RealChallenge(
                                schemeName,
                                // must use Collections.singletonMap because of null key
                                Collections.singletonMap(null, peek + "=".repeat(eqCount))
                        ));
                peek = null;
                continue;
            }

            // It's a series of parameter names and values.
            final var parameters = new HashMap<String, String>();
            eqCount += Utils.skipAll(buffer, (byte) ((int) '='));
            while (true) {
                if (peek == null) {
                    peek = readToken(buffer);
                    if (skipCommasAndWhitespace(buffer)) {
                        break; // We peeked a scheme name followed by ','.
                    }
                    eqCount = Utils.skipAll(buffer, (byte) ((int) '='));
                }
                if (eqCount == 0) {
                    break; // We peeked a scheme name.
                }
                if (eqCount > 1) {
                    return; // Unexpected '=' characters.
                }
                if (skipCommasAndWhitespace(buffer)) {
                    return; // Unexpected ','.
                }

                final String parameterValue;
                if (startsWithQuote(buffer)) {
                    parameterValue = readQuotedString(buffer);
                } else {
                    parameterValue = readToken(buffer);
                }
                if (parameterValue == null) {
                    return; // Expected a value.
                }

                final var replaced = parameters.put(peek, parameterValue);
                peek = null;
                if (replaced != null) {
                    return; // Unexpected duplicate parameter.
                }
                if (!skipCommasAndWhitespace(buffer) && !buffer.exhausted()) {
                    return; // Expected ',' or EOF.
                }
            }
            result.add(new RealChallenge(schemeName, parameters));
        }
    }

    /**
     * @return true if any commas were skipped.
     */
    private static boolean skipCommasAndWhitespace(final @NonNull Buffer buffer) {
        assert buffer != null;

        var commaFound = false;

        loop:
        while (!buffer.exhausted()) {
            switch (buffer.getByte(0)) {
                case (byte) ((int) ',') -> {
                    // Consume ','.
                    buffer.readByte();
                    commaFound = true;
                }

                case (byte) ((int) ' '), (byte) ((int) '\t') -> // Consume space or tab.
                        buffer.readByte();
                default -> {
                    break loop;
                }
            }
        }
        return commaFound;
    }

    /**
     * Consumes and returns a non-empty token, terminating at special characters in {@link #TOKEN_DELIMITERS}. Returns
     * null if the buffer is empty or prefixed with a delimiter.
     */
    private static @Nullable String readToken(final @NonNull Buffer buffer) {
        assert buffer != null;

        var tokenSize = buffer.indexOfElement(TOKEN_DELIMITERS);
        if (tokenSize == -1L) {
            tokenSize = buffer.bytesAvailable();
        }

        return (tokenSize != 0L) ? buffer.readString(tokenSize) : null;
    }

    public static void receiveHeaders(final @NonNull CookieJar cookieJar,
                                      final @NonNull HttpUrl url,
                                      final @NonNull Headers headers) {
        assert cookieJar != null;
        assert url != null;
        assert headers != null;

        if (cookieJar == CookieJar.NO_COOKIES) {
            return;
        }

        final var cookies = Cookie.parseAll(url, headers);
        if (cookies.isEmpty()) {
            return;
        }

        cookieJar.saveFromResponse(url, cookies);
    }

    /**
     * @return true if any commas were skipped.
     */
    private static boolean startsWithQuote(final @NonNull Buffer buffer) {
        assert buffer != null;

        return !buffer.exhausted() && buffer.getByte(0) == (byte) ((int) '"');
    }

    /**
     * Reads a double-quoted string, unescaping quoted pairs like {@code \"} to the 2nd character in each sequence.
     *
     * @return the unescaped string, or null if the buffer isn't prefixed with a double-quoted string.
     */
    private static String readQuotedString(final @NonNull Buffer buffer) {
        assert buffer != null;

        if (buffer.readByte() != (byte) ((int) '\"')) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        final var result = Buffer.create();
        while (true) {
            final var i = buffer.indexOfElement(QUOTED_STRING_DELIMITERS);
            if (i == -1L) {
                return null; // Unterminated quoted string.
            }

            if (buffer.getByte(i) == (byte) ((int) '"')) {
                result.writeFrom(buffer, i);
                // Consume '"'.
                buffer.readByte();
                return result.readString();
            }

            if (buffer.bytesAvailable() == i + 1L) {
                return null; // Dangling escape.
            }
            result.writeFrom(buffer, i);
            // Consume '\'.
            buffer.readByte();
            result.writeFrom(buffer, 1L); // The escaped character.
        }
    }

    /**
     * @return true if the response headers and status indicate that this response has a (possibly 0-length) body.
     * See RFC 7231.
     */
    public static boolean promisesBody(final @NonNull ClientResponse response) {
        assert response != null;

        // HEAD requests never yield a body regardless of the response headers.
        if (response.getRequest().getMethod().equals("HEAD")) {
            return false;
        }

        final var responseCode = response.getStatusCode();
        if ((responseCode < HTTP_CONTINUE || responseCode >= 200) &&
                responseCode != HTTP_NO_CONTENT &&
                responseCode != HTTP_NOT_MODIFIED
        ) {
            return true;
        }

        // If the Content-Length or Transfer-Encoding headers disagree with the response code, the
        // response is malformed. For better compatibility, we honor the headers.
        if (headersContentLength(response) != -1L ||
                "chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
            return true;
        }

        return false;
    }
}
