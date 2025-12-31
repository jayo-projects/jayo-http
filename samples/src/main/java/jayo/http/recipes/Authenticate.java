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
import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.Credentials;
import jayo.http.JayoHttpClient;

public final class Authenticate {
    private final JayoHttpClient client;

    public Authenticate() {
        client = JayoHttpClient.builder()
                .authenticator((route, response) -> {
                    if (response.getRequest().header("Authorization") != null) {
                        return null; // Give up, we've already attempted to authenticate.
                    }

                    System.out.println("Authenticating for response: " + response);
                    System.out.println("Challenges: " + response.challenges());
                    String credential = Credentials.basic("foo", "bar");
                    return response.getRequest().newBuilder()
                            .header("Authorization", credential)
                            .build();
                })
                .build();
    }

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://httpbin.org/basic-auth/foo/bar")
                .get();

        try (ClientResponse response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new JayoException("Unexpected code " + response.getStatusCode());
            }

            System.out.println(response.getBody().string());
        }
    }

    public static void main(String... args) {
        new Authenticate().run();
    }
}
