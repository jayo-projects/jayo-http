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

import jayo.http.internal.RealFormBody;
import org.jspecify.annotations.NonNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public sealed interface FormBody extends ClientRequestBody permits RealFormBody {
    /**
     * @return a new {@link FormBody} builder that uses the UTF-8 charset to encode key-value pairs in this form-encoded
     * body.
     */
    static @NonNull Builder builder() {
        return new RealFormBody.Builder(StandardCharsets.UTF_8);
    }

    /**
     * @return a new {@link FormBody} builder that uses {@code charset} to encode key-value pairs in this form-encoded
     * body.
     */
    static @NonNull Builder builder(final @NonNull Charset charset) {
        Objects.requireNonNull(charset);
        return new RealFormBody.Builder(charset);
    }

    /**
     * @return the number of key-value pairs in this form-encoded body.
     */
    int getSize();

    @NonNull String encodedName(final int index);

    @NonNull String name(final int index);

    @NonNull String encodedValue(final int index);

    @NonNull String value(final int index);

    sealed interface Builder permits RealFormBody.Builder {
        @NonNull
        Builder add(final @NonNull String name, final @NonNull String value);

        @NonNull
        Builder addEncoded(final @NonNull String name, final @NonNull String value);

        @NonNull
        FormBody build();
    }
}
