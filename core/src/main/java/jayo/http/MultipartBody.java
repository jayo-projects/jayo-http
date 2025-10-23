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

import jayo.http.internal.RealMultipartBody;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A <a href="https://www.ietf.org/rfc/rfc2387.txt">RFC 2387</a>-compliant request body.
 */
public sealed interface MultipartBody extends ClientRequestBody, List<MultipartBody.@NonNull Part>
        permits RealMultipartBody {

    static @NonNull Builder builder() {
        return new RealMultipartBody.Builder();
    }

    @NonNull MediaType getType();

    @NonNull String getBoundary();

    /**
     * @return the number of parts in this multipart body.
     */
    @Override
    int size();

    @NonNull Part get(final int index);

    /**
     * @return a combination of {@linkplain #getType() type} and {@linkplain #getBoundary() boundary}.
     */
    @NonNull MediaType contentType();

    sealed interface Part permits RealMultipartBody.RealPart {
        static @NonNull Part create(final @NonNull ClientRequestBody body) {
            Objects.requireNonNull(body);
            return RealMultipartBody.RealPart.create(null, body);
        }

        static @NonNull Part create(final @NonNull Headers headers, final @NonNull ClientRequestBody body) {
            Objects.requireNonNull(headers);
            Objects.requireNonNull(body);

            return RealMultipartBody.RealPart.create(headers, body);
        }

        static @NonNull Part createFormData(final @NonNull String name, final @NonNull String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);

            return RealMultipartBody.RealPart.createFormData(name, null, ClientRequestBody.create(value));
        }

        static @NonNull Part createFormData(final @NonNull String name,
                                            final @Nullable String filename,
                                            final @NonNull ClientRequestBody body) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(body);

            return RealMultipartBody.RealPart.createFormData(name, filename, body);
        }

        @Nullable Headers getHeaders();

        @NonNull ClientRequestBody getBody();
    }

    sealed interface Builder permits RealMultipartBody.Builder {
        @NonNull Builder boundary(final @NonNull String boundary);

        /**
         * Set the MIME type. Expected values for {@code type} are {@link #MIXED} (the default), {@link #ALTERNATIVE},
         * {@link #DIGEST}, {@link #PARALLEL} and {@link #FORM}.
         */
        @NonNull Builder type(final @NonNull MediaType type);

        /**
         * Add a part to the body.
         */
        @NonNull Builder addPart(final @NonNull ClientRequestBody body);

        /**
         * Add a part to the body.
         */
        @NonNull Builder addPart(final @NonNull Headers headers, final @NonNull ClientRequestBody body);

        /**
         * Add a form data part to the body.
         */
        @NonNull Builder addFormDataPart(final @NonNull String name, final @NonNull String value);

        /**
         * Add a form data part to the body.
         */
        @NonNull Builder addFormDataPart(final @NonNull String name,
                                         final @Nullable String filename,
                                         final @NonNull ClientRequestBody body);

        /**
         * Add a part to the body.
         */
        @NonNull Builder addPart(final @NonNull Part part);

        /**
         * Assemble the specified parts into a request body.
         */
        @NonNull MultipartBody build();
    }

    /**
     * The "mixed" subtype of "multipart" is intended for use when the body parts are independent and need to be bundled
     * in a particular order. Any "multipart" subtypes that an implementation does not recognize must be treated as
     * being of subtype "mixed".
     */
    MediaType MIXED = MediaType.get("multipart/mixed");

    /**
     * The "multipart/alternative" type is syntactically identical to "multipart/mixed", but the semantics are
     * different. In particular, each of the body parts is an "alternative" version of the same information.
     */
    MediaType ALTERNATIVE = MediaType.get("multipart/alternative");

    /**
     * This type is syntactically identical to "multipart/mixed", but the semantics are different. In particular, in a
     * digest, the default {@code Content-Type} value for a body part is changed from "text/plain" to "message/rfc822".
     */
    MediaType DIGEST = MediaType.get("multipart/digest");

    /**
     * This type is syntactically identical to "multipart/mixed", but the semantics are different. In particular, in a
     * parallel entity, the order of body parts is not significant.
     */
    MediaType PARALLEL = MediaType.get("multipart/parallel");

    /**
     * The media-type multipart/form-data follows the rules of all multipart MIME data streams as outlined in RFC 2046.
     * In forms, there are a series of fields to be supplied by the user who fills out the form. Each field has a name.
     * Within a given form, the names are unique.
     */
    MediaType FORM = MediaType.get("multipart/form-data");
}
