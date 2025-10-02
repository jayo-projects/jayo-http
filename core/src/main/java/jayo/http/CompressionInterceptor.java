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

import jayo.RawReader;
import jayo.Reader;
import jayo.http.internal.RealCompressionInterceptor;
import org.jspecify.annotations.NonNull;

/**
 * Transparent Compressed response support.
 * <p>
 * The algorithm map will be turned into a heading such as "Accept-Encoding: br, gzip"
 * <p>
 *
 * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Accept-Encoding">Accept-Encoding spec</a>
 */
public sealed interface CompressionInterceptor extends Interceptor permits RealCompressionInterceptor {
    /**
     * Creates a new compression interceptor that supports the given algorithms.
     * <p>
     * If {@code algorithms} is empty this interceptor has no effect. To disable compression, set a specific
     * "Accept-Encoding: identity" or similar.
     *
     * @see GzipDecompressionAlgorithm#Gzip
     */
    static @NonNull CompressionInterceptor of(final @NonNull DecompressionAlgorithm @NonNull ... algorithms) {
        return new RealCompressionInterceptor(algorithms);
    }

    @NonNull DecompressionAlgorithm @NonNull [] getAlgorithms();

    /**
     * A decompression algorithm such as GzipDecompressionAlgorithm. Must provide the Accept-Encoding value and
     * implement how to decompress a Reader.
     */
    interface DecompressionAlgorithm {
        @NonNull
        String getEncoding();

        @NonNull
        RawReader decompress(final @NonNull Reader compressedReader);
    }
}
