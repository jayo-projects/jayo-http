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

package jayo.http.recipes;

import jayo.JayoException;
import jayo.http.*;

public final class AsynchronousGet {
    private final JayoHttpClient client = JayoHttpClient.create();

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/main/samples/src/main/resources/jayo-http.txt")
                .get();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, JayoException je) {
                je.printStackTrace();
            }

            @Override
            public void onResponse(Call call, ClientResponse response) {
                try (ClientResponseBody responseBody = response.getBody()) {
                    if (!response.isSuccessful()) {
                        throw new JayoException("Unexpected code " + response.getStatusCode());
                    }

                    Headers responseHeaders = response.getHeaders();
                    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                        System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
                    }

                    System.out.println(responseBody.string());
                }
            }
        });
    }

    public static void main(String... args) {
        new AsynchronousGet().run();
    }
}
