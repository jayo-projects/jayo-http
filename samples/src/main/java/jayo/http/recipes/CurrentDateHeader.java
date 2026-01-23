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

import jayo.http.*;

import java.time.Instant;

public final class CurrentDateHeader {
    private final JayoHttpClient client = JayoHttpClient.builder()
            .addInterceptor(new CurrentDateInterceptor())
            .build();

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/main/samples/src/main/resources/jayo-http.txt")
                .get();

        try (ClientResponse response = client.newCall(request).execute()) {
            System.out.println(response.getRequest().header("Date"));
        }
    }

    static class CurrentDateInterceptor implements Interceptor {
        @Override
        public ClientResponse intercept(Chain chain) {
            ClientRequest request = chain.request();
            Headers newHeaders = request.getHeaders()
                    .newBuilder()
                    .add("Date", Instant.now())
                    .build();
            ClientRequest newRequest = request.newBuilder()
                    .headers(newHeaders)
                    .build();
            return chain.proceed(newRequest);
        }
    }

    public static void main(String... args) {
        new CurrentDateHeader().run();
    }
}
