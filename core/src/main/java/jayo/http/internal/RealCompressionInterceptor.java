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

import jayo.Jayo;
import jayo.http.ClientResponse;
import jayo.http.CompressionInterceptor;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.stream.Collectors;

import static jayo.http.tools.JayoHttpUtils.promisesBody;

public final class RealCompressionInterceptor implements CompressionInterceptor {
    private final @NonNull DecompressionAlgorithm @NonNull [] algorithms;
    private final @NonNull String acceptEncoding;

    public RealCompressionInterceptor(final @NonNull DecompressionAlgorithm @NonNull [] algorithms) {
        assert algorithms != null;
        this.algorithms = algorithms;
        this.acceptEncoding = Arrays.stream(algorithms)
                .map(DecompressionAlgorithm::getEncoding)
                .collect(Collectors.joining(", "));
    }

    @Override
    public @NonNull ClientResponse intercept(final @NonNull Chain chain) {
        assert chain != null;

        if (algorithms.length != 0 && chain.request().header("Accept-Encoding") == null) {
            final var request = chain
                    .request()
                    .newBuilder()
                    .header("Accept-Encoding", acceptEncoding)
                    .build();

            final var response = chain.proceed(request);

            return decompress(response);
        }

        return chain.proceed(chain.request());
    }

    @Override
    public @NonNull DecompressionAlgorithm @NonNull [] getAlgorithms() {
        return algorithms;
    }

    @Override
    public @NonNull ClientResponse decompress(final @NonNull ClientResponse response) {
        assert response != null;

        if (!promisesBody(response)) {
            return response;
        }
        final var body = response.getBody();
        final var encoding = response.header("Content-Encoding");
        if (encoding == null) {
            return response;
        }

        final var algorithm = Arrays.stream(algorithms)
                .filter(it -> it.getEncoding().equalsIgnoreCase(encoding))
                .findFirst();
        if (algorithm.isEmpty()) {
            return response;
        }

        final var decompressedReader = Jayo.buffer(algorithm.get().decompress(body.reader()));

        return response
                .newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .body(StandardClientResponseBodies.create(decompressedReader, body.contentType(), -1))
                .build();
    }
}
