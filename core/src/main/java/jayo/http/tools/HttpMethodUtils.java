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

import org.jspecify.annotations.NonNull;

public final class HttpMethodUtils {
    // un-instantiable
    private HttpMethodUtils() {
    }

    public static boolean invalidatesCache(final @NonNull String method) {
        return method.equals("POST") ||
                method.equals("PATCH") ||
                method.equals("PUT") ||
                method.equals("DELETE") ||
                method.equals("MOVE");
    }

    public static boolean requiresRequestBody(final @NonNull String method) {
        return method.equals("POST") ||
                method.equals("PUT") ||
                method.equals("PATCH") ||
                method.equals("PROPPATCH") ||
                method.equals("QUERY") ||
                // WebDAV
                method.equals("REPORT");
    }

    public static boolean permitsRequestBody(final @NonNull String method) {
        return !(method.equals("GET") || method.equals("HEAD"));
    }

    public static boolean redirectsWithBody(final @NonNull String method) {
        return method.equals("PROPFIND");
    }

    public static boolean redirectsToGet(final @NonNull String method) {
        return !method.equals("PROPFIND");
    }

    public static boolean isCacheable(final @NonNull String requestMethod) {
        return requestMethod.equals("GET") || requestMethod.equals("QUERY");
    }
}
