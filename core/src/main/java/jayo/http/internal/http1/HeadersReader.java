/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2012 The Android Open Source Project
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

package jayo.http.internal.http1;

import jayo.Reader;
import jayo.http.Headers;
import jayo.http.internal.RealHeaders;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;

final class HeadersReader {
    private static final int HEADER_LIMIT = 256 * 1024;

    private final @NonNull Reader reader;
    private long headerLimit = HEADER_LIMIT;

    HeadersReader(final @NonNull Reader reader) {
        assert reader != null;
        this.reader = reader;
    }

    /**
     * Read a single line counted against the header size limit.
     */
    @NonNull
    String readLine() {
        final var line = reader.readLineStrict(headerLimit, StandardCharsets.ISO_8859_1);
        headerLimit -= line.length();
        return line;
    }

    /**
     * Reads headers or trailers.
     */
    @NonNull
    Headers readHeaders() {
        final var result = (RealHeaders.Builder) Headers.builder();
        while (true) {
            final var line = readLine();
            if (line.isEmpty()) {
                break;
            }
            result.addLenient(line);
        }
        return result.build();
    }
}
