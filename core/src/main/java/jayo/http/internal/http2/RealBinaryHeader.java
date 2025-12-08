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

package jayo.http.internal.http2;

import jayo.bytestring.ByteString;
import jayo.http.http2.BinaryHeader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;

public final class RealBinaryHeader implements BinaryHeader {
    private final @NonNull ByteString name;
    private final @NonNull ByteString value;
    final int hpackSize;

    // TODO: search for toLowerCase and consider moving logic here.
    RealBinaryHeader(final @NonNull String name, final @NonNull String value) {
        this(ByteString.encode(name, StandardCharsets.ISO_8859_1), ByteString.encode(value, StandardCharsets.ISO_8859_1));
    }

    RealBinaryHeader(final @NonNull ByteString name, final @NonNull String value) {
        this(name, ByteString.encode(value, StandardCharsets.ISO_8859_1));
    }

    RealBinaryHeader(final @NonNull ByteString name, final @NonNull ByteString value) {
        assert name != null;
        assert value != null;

        this.name = name;
        this.value = value;
        this.hpackSize = 32 + name.byteSize() + value.byteSize();
    }

    @Override
    public String toString() {
        return name.decodeToString(StandardCharsets.ISO_8859_1) + ": " + value.decodeToString(StandardCharsets.ISO_8859_1);
    }

    // Special header names defined in HTTP/2 spec.
    static final @NonNull ByteString PSEUDO_PREFIX = ByteString.encode(":");

    static final @NonNull String RESPONSE_STATUS_UTF8 = ":status";
    static final @NonNull String TARGET_METHOD_UTF8 = ":method";
    static final @NonNull String TARGET_PATH_UTF8 = ":path";
    static final @NonNull String TARGET_SCHEME_UTF8 = ":scheme";
    static final @NonNull String TARGET_AUTHORITY_UTF8 = ":authority";

    static final @NonNull ByteString RESPONSE_STATUS = ByteString.encode(RESPONSE_STATUS_UTF8);
    static final @NonNull ByteString TARGET_METHOD = ByteString.encode(TARGET_METHOD_UTF8);
    static final @NonNull ByteString TARGET_PATH = ByteString.encode(TARGET_PATH_UTF8);
    static final @NonNull ByteString TARGET_SCHEME = ByteString.encode(TARGET_SCHEME_UTF8);
    static final @NonNull ByteString TARGET_AUTHORITY = ByteString.encode(TARGET_AUTHORITY_UTF8);

    @Override
    public @NonNull ByteString getName() {
        return name;
    }

    @Override
    public @NonNull ByteString getValue() {
        return value;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof RealBinaryHeader that)) {
            return false;
        }

        return name.equals(that.name) &&
                value.equals(that.value);
    }
}
