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
import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.JayoHttpClient;

import java.time.Duration;

public final class PerCallSettings {
    private final JayoHttpClient client = JayoHttpClient.create();

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://httpbin.org/delay/1") // This URL is served with a 1-second delay.
                .get();

        // Copy to customize Jayo HTTP for this request.
        JayoHttpClient client1 = client.newBuilder()
                .readTimeout(Duration.ofMillis(500))
                .build();
        try (ClientResponse response = client1.newCall(request).execute()) {
            System.out.println("Response 1 succeeded: " + response);
        } catch (JayoException je) {
            System.out.println("Response 1 failed: " + je);
        }

        // Copy to customize Jayo HTTP for this request.
        JayoHttpClient client2 = client.newBuilder()
                .readTimeout(Duration.ofMillis(3000))
                .build();
        try (ClientResponse response = client2.newCall(request).execute()) {
            System.out.println("Response 2 succeeded: " + response);
        } catch (JayoException je) {
            System.out.println("Response 2 failed: " + je);
        }
    }

    public static void main(String... args) {
        new PerCallSettings().run();
    }
}
