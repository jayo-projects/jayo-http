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

import jayo.http.internal.RealChallenge;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.Map;

/**
 * A <a href="https://tools.ietf.org/html/rfc7235">RFC 7235</a> challenge.
 */
public sealed interface Challenge permits RealChallenge {
    /**
     * @return the authentication scheme, like {@code Basic}.
     */
    @NonNull
    String getScheme();

    /**
     * @return the auth params, including {@link #getRealm()} and {@link #getCharset()} if present, but as strings. The
     * map's keys are lowercase and should be treated case-insensitively.
     */
    @NonNull
    Map<@Nullable String, @NonNull String> getAuthParams();

    /**
     * @return the protection space.
     */
    @Nullable
    String getRealm();

    /**
     * @return the charset that should be used to encode the credentials.
     */
    @NonNull
    Charset getCharset();

    /**
     * @return a copy of this charset that expects a credential encoded with {@code charset}.
     */
    @NonNull
    Challenge withCharset(final @NonNull Charset charset);
}
