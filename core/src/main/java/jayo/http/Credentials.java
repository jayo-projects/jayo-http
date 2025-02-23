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

package jayo.http;

import jayo.bytestring.ByteString;
import org.jspecify.annotations.NonNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Factory for HTTP authorization credentials.
 */
public class Credentials {
    // un-instantiable
    private Credentials() {
    }

    /**
     * Returns an auth credential for the Basic scheme that uses {@code UTF_8} for encoding.
     */
    public static @NonNull String basic(final @NonNull String username, final @NonNull String password) {
        return basic(username, password, StandardCharsets.UTF_8);
    }

    /**
     * Returns an auth credential for the Basic scheme that uses {@code charset} for encoding.
     */
    public static @NonNull String basic(final @NonNull String username,
                                        final @NonNull String password,
                                        final @NonNull Charset charset) {
        final var usernameAndPassword = username + ":" + password;
        final var encoded = ByteString.encode(usernameAndPassword, charset).base64();
        return "Basic " + encoded;
    }
}
