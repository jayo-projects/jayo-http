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
import jayo.http.Cache;
import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.JayoHttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CacheResponse {
    private final JayoHttpClient client;

    public CacheResponse(Path cacheDirectory) {
        int cacheSize = 10 * 1024 * 1024; // 10 MiB
        Cache cache = Cache.create(cacheDirectory, cacheSize);

        client = JayoHttpClient.builder()
                .cache(cache)
                .build();
    }

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/initial/samples/src/main/resources/jayo-http.txt")
                .get();

        String response1Body;
        try (ClientResponse response1 = client.newCall(request).execute()) {
            if (!response1.isSuccessful()) {
              throw new JayoException("Unexpected code " + response1.getStatusCode());
            }

            response1Body = response1.getBody().string();
            System.out.println("Response 1 response:          " + response1);
            System.out.println("Response 1 cache response:    " + response1.getCacheResponse());
            System.out.println("Response 1 network response:  " + response1.getNetworkResponse());
        }

        String response2Body;
        try (ClientResponse response2 = client.newCall(request).execute()) {
            if (!response2.isSuccessful()) {
              throw new JayoException("Unexpected code " + response2.getStatusCode());
            }

            response2Body = response2.getBody().string();
            System.out.println("Response 2 response:          " + response2);
            System.out.println("Response 2 cache response:    " + response2.getCacheResponse());
            System.out.println("Response 2 network response:  " + response2.getNetworkResponse());
        }

        System.out.println("Response 2 equals Response 1 ? " + response1Body.equals(response2Body));
    }

    public static void main(String... args) throws IOException {
        new CacheResponse(Files.createTempDirectory("CacheResponse.tmp")).run();
    }
}
