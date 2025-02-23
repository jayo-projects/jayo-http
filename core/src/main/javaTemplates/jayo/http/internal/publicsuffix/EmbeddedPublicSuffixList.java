/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2024 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jayo.http.internal.publicsuffix;

//Note that PublicSuffixDatabase.gz is compiled from The Public Suffix List:
//https://publicsuffix.org/list/public_suffix_list.dat
//
//It is subject to the terms of the Mozilla Public License, v. 2.0:
//https://mozilla.org/MPL/2.0/

import jayo.Buffer;
import jayo.bytestring.ByteString;
import jayo.Jayo;
import jayo.RawReader;
import org.jspecify.annotations.NonNull;

enum EmbeddedPublicSuffixList implements PublicSuffixList {
    INSTANCE;

    private final ByteString bytes;
    private final ByteString exceptionBytes;

    EmbeddedPublicSuffixList() {
        final var buffer = Buffer.create();
        final var publicSuffixListString = ByteString.decodeBase64($publicSuffixListBytes);
        assert publicSuffixListString != null;
        buffer.write(publicSuffixListString);
        try (final var gzipedSrc = Jayo.buffer(Jayo.gzip((RawReader) buffer))) {
            final var totalBytes = gzipedSrc.readInt();
            bytes = gzipedSrc.readByteString(totalBytes);

            final var totalExceptionBytes = gzipedSrc.readInt();
            exceptionBytes = gzipedSrc.readByteString(totalExceptionBytes);
        }
    }

    @Override
    public void ensureLoaded() {
    }

    @Override
    public @NonNull ByteString bytes() {
        return bytes;
    }

    @Override
    public @NonNull ByteString exceptionBytes() {
        return exceptionBytes;
    }
}