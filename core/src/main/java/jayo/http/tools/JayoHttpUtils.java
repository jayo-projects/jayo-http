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

package jayo.http.tools;

import jayo.JayoEOFException;
import jayo.JayoException;
import jayo.Reader;
import jayo.http.ClientResponse;
import jayo.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static jayo.http.internal.Utils.headersContentLength;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_CONTINUE;

/**
 * A set of tools provided and used internally by Jayo HTTP that can be useful to other libraries.
 */
public class JayoHttpUtils {
    public static @NonNull Charset charsetOrUtf8(final @Nullable MediaType contentType) {
        if (contentType == null || contentType.charset() == null) {
            return UTF_8;
        }

        //noinspection DataFlowIssue
        return contentType.charset();
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

    /**
     * @return true if the body in question probably contains human-readable text. Uses a small sample of code points to
     * detect Unicode control characters commonly used in binary file signatures.
     */
    public static boolean isProbablyUtf8(final @NonNull Reader reader, final long codePointLimit) {
        assert reader != null;

        try {
            final var peek = reader.peek();
            for (var i = 0L; i < codePointLimit; i++) {
                if (peek.exhausted()) {
                    break;
                }
                final var codePoint = peek.readUtf8CodePoint();
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false;
                }
            }
            return true;
        } catch (JayoEOFException e) {
            return false; // Truncated UTF-8 sequence.
        }
    }

    /**
     * Closes this {@code closeable}, ignoring any checked exceptions and any {@link JayoException}.
     */
    public static void closeQuietly(final @NonNull AutoCloseable closeable) {
        assert closeable != null;

        try {
            closeable.close();
        } catch (JayoException ignored) {
        } catch (RuntimeException rethrown) {
            throw rethrown;
        } catch (Exception ignored) {
        }
    }
}
