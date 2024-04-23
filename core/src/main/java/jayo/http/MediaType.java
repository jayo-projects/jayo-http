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

import jayo.http.internal.RealMediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;

/**
 * An <a href="http://tools.ietf.org/html/rfc2045">RFC 2045</a> Media Type, appropriate to describe the content type of
 * an HTTP request or response body.
 */
public sealed interface MediaType permits RealMediaType {
    /**
     * @return a media type for {@code mediaType}.
     *
     * @throws IllegalArgumentException if {@code mediaType} is not a well-formed media type.
     */
    static @NonNull MediaType get(final @NonNull String mediaType) {
        return RealMediaType.get(mediaType);
    }

    /**
     * @return a media type for {@code mediaType}, or null if {@code mediaType} is not a well-formed media type.
     */
    static @Nullable MediaType parse(final @NonNull String mediaType) {
        try {
            return MediaType.get(mediaType);
        } catch (IllegalArgumentException _unused) {
            return null;
        }
    }

    /**
     * @return the high-level media type, such as "text", "image", "audio", "video", or "application".
     */
    @NonNull
    String getType();

    /**
     * @return a specific media subtype, such as "plain" or "png", "mpeg", "mp4" or "xml".
     */
    @NonNull
    String getSubtype();

    /**
     * @return the charset of this media type, or null if either this media type doesn't specify a charset, or if its
     * charset is unsupported by the current runtime.
     */
    @Nullable
    Charset charset();

    /**
     * @return the charset of this media type, or {@code defaultCharset} if either this media type doesn't specify a
     * charset, or if its charset is unsupported by the current runtime.
     */
    @NonNull
    Charset charset(final @NonNull Charset defaultCharset);

    /**
     * @return the parameter {@code name} of this media type, or null if this media type does not define such a
     * parameter.
     */
    @Nullable
    String parameter(final @NonNull String name);

    /**
     * @return the encoded media type, like "text/plain; charset=utf-8", appropriate for use in a Content-Type header.
     */
    @Override
    @NonNull
    String toString();
}
