/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
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

package jayo.http.recipes;

import jayo.JayoException;
import jayo.http.*;

import java.nio.file.Path;

public final class RewriteResponseCacheControl {
    /**
     * Dangerous interceptor that rewrites the server's cache-control header.
     */
    private static final Interceptor REWRITE_CACHE_CONTROL_INTERCEPTOR = chain -> {
        ClientResponse originalResponse = chain.proceed(chain.request());
        return originalResponse.newBuilder()
                .header("Cache-Control", "max-age=60")
                .build();
    };

    private final JayoHttpClient client;

    public RewriteResponseCacheControl(Path cacheDirectory) {
        Cache cache = Cache.create(cacheDirectory, 1024 * 1024);
        cache.evictAll();

        client = JayoHttpClient.builder()
                .cache(cache)
                .build();
    }

    public void run() {
        for (int i = 0; i < 5; i++) {
            System.out.println("    Request: " + i);

            ClientRequest request = ClientRequest.builder()
                    .url("https://api.github.com/search/repositories?q=http")
                    .get();

            JayoHttpClient clientForCall;
            if (i == 2) {
                // Force this request's response to be written to the cache. This way, subsequent responses can be read
                // from the cache.
                System.out.println("Force cache: true");
                clientForCall = client.newBuilder()
                        .addNetworkInterceptor(REWRITE_CACHE_CONTROL_INTERCEPTOR)
                        .build();
            } else {
                System.out.println("Force cache: false");
                clientForCall = client;
            }

            try (ClientResponse response = clientForCall.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new JayoException("Unexpected code " + response.getStatusCode());
                }

                System.out.println("    Network: " + (response.getNetworkResponse() != null));
                System.out.println();
            }
        }
    }

    public static void main(String... args) {
        new RewriteResponseCacheControl(Path.of("RewriteResponseCacheControl.tmp")).run();
    }
}
