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

public final class PreemptiveAuth {
    private final JayoHttpClient client;

    public PreemptiveAuth() {
        client = JayoHttpClient.builder()
                .addInterceptor(
                        new BasicAuthInterceptor("httpbin.org", "foo", "bar"))
                .build();
    }

    public void run() throws Exception {
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

    public static void main(String... args) throws Exception {
        new PreemptiveAuth().run();
    }

    static final class BasicAuthInterceptor implements Interceptor {
        private final String credentials;
        private final String host;

        BasicAuthInterceptor(String host, String username, String password) {
            this.credentials = Credentials.basic(username, password);
            this.host = host;
        }

        @Override
        public ClientResponse intercept(Chain chain) {
            ClientRequest request = chain.request();
            if (request.getUrl().getHost().equals(host)) {
                request = request.newBuilder()
                        .header("Authorization", credentials)
                        .build();
            }
            return chain.proceed(request);
        }
    }
}
