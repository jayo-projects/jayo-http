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

import jayo.http.ClientRequest;
import jayo.http.HttpUrl;
import org.jspecify.annotations.NonNull;

public final class RequestLine {
    // un-instantiable
    private RequestLine() {
    }

    /**
     * @return the request status line, like "GET / HTTP/1.1". It needs to be set even if the transport is HTTP/2.
     */
    public static @NonNull String get(final @NonNull ClientRequest request, final boolean useHttpProxy) {
        assert request != null;

        final var sb = new StringBuilder();
        sb.append(request.getMethod());
        sb.append(' ');
        if (includeAuthorityInRequestLine(request, useHttpProxy)) {
            sb.append(request.getUrl());
        } else {
            sb.append(requestPath(request.getUrl()));
        }
        sb.append(" HTTP/1.1");
        return sb.toString();
    }

    /**
     * @return true if the request line should contain the full URL with host and port (like {@code "GET
     * https://android.com/foo HTTP/1.1"}) or only the path (like {@code "GET /foo HTTP/1.1"}).
     */
    private static boolean includeAuthorityInRequestLine(final @NonNull ClientRequest request,
                                                         final boolean useHttpProxy) {
        assert request != null;
        return !request.isHttps() && useHttpProxy;
    }

    /**
     * @return the path to request, like the '/' in 'GET / HTTP/1.1'. Never empty, even if the request URL is. Includes
     * the query component if it exists.
     */
    public static String requestPath(final @NonNull HttpUrl url) {
        assert url != null;

        final var path = url.getEncodedPath();
        final var query = url.getEncodedQuery();
        return (query != null) ? path + "?" + query : path;
    }
}
