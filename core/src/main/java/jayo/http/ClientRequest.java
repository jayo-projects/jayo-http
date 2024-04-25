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

package jayo.http;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * An HTTP client request. Instances of this class are immutable if their {@linkplain #getBody() body} is null or itself
 * immutable.
 */
public interface ClientRequest {
    @NonNull
    HttpUrl getUrl();

    @NonNull
    String getMethod();

    @NonNull
    Headers getHeaders();

    @Nullable
    ClientRequestBody getBody();

    @Nullable
    HttpUrl getCacheUrlOverride();

    boolean isHttps();

    @Nullable
    String header(final @NonNull String name);

    @NonNull
    List<String> headers(final @NonNull String name);

    /**
     * @return the tag attached with {@code type} as a key, or null if no tag is attached with that key.
     */
    <T> @Nullable T tag(final @NonNull Class<? extends T> type);

    /**
     * @return a builder based on this URL.
     */
    @NonNull Builder newBuilder();

    interface Builder {

    }
}
