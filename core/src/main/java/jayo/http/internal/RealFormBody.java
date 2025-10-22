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

package jayo.http.internal;

import jayo.Buffer;
import jayo.Writer;
import jayo.http.FormBody;
import jayo.tools.JayoUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static jayo.http.internal.UrlUtils.*;

public final class RealFormBody extends FormBody {
    final @NonNull List<@NonNull String> encodedNames;
    final @NonNull List<@NonNull String> encodedValues;

    private RealFormBody(final @NonNull List<@NonNull String> encodedNames,
                         final @NonNull List<@NonNull String> encodedValues) {
        assert encodedNames != null;
        assert encodedValues != null;

        this.encodedNames = List.copyOf(encodedNames);
        this.encodedValues = List.copyOf(encodedValues);
    }

    @Override
    public int getSize() {
        return encodedNames.size();
    }

    @Override
    public @NonNull String encodedName(final int index) {
        return encodedNames.get(index);
    }

    @Override
    public @NonNull String name(final int index) {
        final var encodedName = encodedNames.get(index);
        return percentDecode(
                encodedName,
                0,
                encodedName.length(),
                true);
    }

    @Override
    public @NonNull String encodedValue(final int index) {
        return encodedValues.get(index);
    }

    @Override
    public @NonNull String value(final int index) {
        final var encodedValue = encodedValues.get(index);
        return percentDecode(
                encodedValue,
                0,
                encodedValue.length(),
                true);
    }

    public long contentByteSize() {
        return writeOrCountBytes(null, true);
    }

    @Override
    public void writeTo(final @NonNull Writer destination) {
        assert destination != null;
        writeOrCountBytes(destination, false);
    }

    /**
     * Either writes this request to {@code destination} or measures its content length. We have one method do
     * double-duty to make sure the counting and content are consistent, particularly when it comes to awkward
     * operations like measuring the encoded length of header strings, or the length-in-digits of an encoded integer.
     */
    private long writeOrCountBytes(final @Nullable Writer destination, final boolean countBytes) {
        var byteCount = 0L;
        final Buffer buffer;
        if (countBytes) {
            buffer = Buffer.create();
        } else {
            assert destination != null;
            buffer = JayoUtils.buffer(destination);
        }

        for (var i = 0; i < encodedNames.size(); i++) {
            if (i > 0) {
                buffer.writeByte((byte) '&');
            }
            buffer.write(encodedNames.get(i));
            buffer.writeByte((byte) '=');
            buffer.write(encodedValues.get(i));
        }

        if (countBytes) {
            byteCount = buffer.bytesAvailable();
            buffer.clear();
        }

        return byteCount;
    }

    public static final class Builder implements FormBody.Builder {
        private final @NonNull Charset charset;
        private final @NonNull List<@NonNull String> names = new ArrayList<>();
        private final @NonNull List<@NonNull String> values = new ArrayList<>();

        public Builder(final @NonNull Charset charset) {
            assert charset != null;
            this.charset = charset;
        }

        @Override
        public @NonNull Builder add(final @NonNull String name, final @NonNull String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);

            names.add(canonicalizeWithCharset(
                    name,
                    FORM_ENCODE_SET,
                    // Plus is encoded as `%2B`, space is encoded as plus.
                    false,
                    0,
                    name.length(),
                    false,
                    false,
                    false,
                    charset
            ));
            values.add(canonicalizeWithCharset(
                    value,
                    FORM_ENCODE_SET,
                    // Plus is encoded as `%2B`, space is encoded as plus.
                    false,
                    0,
                    value.length(),
                    false,
                    false,
                    false,
                    charset
            ));

            return this;
        }

        @Override
        public @NonNull Builder addEncoded(final @NonNull String name, final @NonNull String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);

            names.add(canonicalizeWithCharset(
                    name,
                    FORM_ENCODE_SET,
                    true,
                    0,
                    name.length(),
                    false,
                    true,
                    false,
                    charset
            ));
            values.add(canonicalizeWithCharset(
                    value,
                    FORM_ENCODE_SET,
                    true,
                    0,
                    value.length(),
                    false,
                    true,
                    false,
                    charset
            ));

            return this;
        }

        @Override
        public @NonNull FormBody build() {
            return new RealFormBody(names, values);
        }
    }
}
