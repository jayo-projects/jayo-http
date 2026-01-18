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
import jayo.http.JayoHttpClient;

import java.time.Duration;

public final class ConfigureTimeouts {
    private final JayoHttpClient client;

    public ConfigureTimeouts() {
        client = JayoHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .callTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://httpbin.org/delay/2") // This URL is served with a 2-second delay.
                .get();

        try (ClientResponse response = client.newCall(request).execute()) {
            System.out.println("Response completed: " + response);
        }
    }

    public static void main(String... args) {
        new ConfigureTimeouts().run();
    }
}
