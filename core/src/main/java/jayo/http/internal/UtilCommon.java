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

package jayo.http.internal;

import jayo.external.NonNegative;
import org.jspecify.annotations.NonNull;

final class UtilCommon {
    // un-instantiable
    private UtilCommon() {
    }

    /**
     * Returns the index of the first character in this string that contains a character in
     * [delimiters]. Returns endIndex if there is no such character.
     */
    static @NonNegative int delimiterOffset(
            final @NonNull String source,
            final @NonNull String delimiters,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
            ) {
        for (var i = startIndex; i < endIndex; i++) {
            if (delimiters.indexOf(source.charAt(i)) >= 0) {
                return i;
            }
        }
        return endIndex;
    }

    static @NonNegative int delimiterOffset(
            final @NonNull String source,
            final char delimiter,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        for (var i = startIndex; i < endIndex; i++) {
            if (source.charAt(i) == delimiter) {
                return i;
            }
        }
        return endIndex;
    }
}
