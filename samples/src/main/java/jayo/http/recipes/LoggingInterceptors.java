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

import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.Interceptor;
import jayo.http.JayoHttpClient;

import java.util.logging.Logger;

public final class LoggingInterceptors {
    private static final Logger logger = Logger.getLogger(LoggingInterceptors.class.getName());
    private final JayoHttpClient client = JayoHttpClient.builder()
            .addNetworkInterceptor(new LoggingInterceptor())
            .build();

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/main/samples/src/main/resources/jayo-http.txt")
                .get();

        ClientResponse response = client.newCall(request).execute();
        response.getBody().close();
    }

    private static class LoggingInterceptor implements Interceptor {
        @Override
        public ClientResponse intercept(Chain chain) {
            long t1 = System.nanoTime();
            ClientRequest request = chain.request();
            logger.info(String.format("Sending request %s on %s%n%s",
                    request.getUrl(), chain.connection(), request.getHeaders()));
            ClientResponse response = chain.proceed(request);

            long t2 = System.nanoTime();
            logger.info(String.format("Received response for %s in %.1fms%n%s",
                    request.getUrl(), (t2 - t1) / 1e6d, response.getHeaders()));
            return response;
        }
    }

    public static void main(String... args) {
        new LoggingInterceptors().run();
    }
}
