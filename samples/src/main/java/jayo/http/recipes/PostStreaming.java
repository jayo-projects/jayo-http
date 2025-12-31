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
import jayo.Writer;
import jayo.http.*;

public final class PostStreaming {
    public static final MediaType MEDIA_TYPE_MARKDOWN = MediaType.get("text/x-markdown; charset=utf-8");

    private final JayoHttpClient client = JayoHttpClient.create();

    public void run() {
        ClientRequestBody requestBody = new ClientRequestBody() {
            @Override
            public MediaType contentType() {
                return MEDIA_TYPE_MARKDOWN;
            }

            @Override
            public long contentByteSize() {
                return -1L;
            }

            @Override
            public void writeTo(Writer writer) {
                writer.write("Numbers\n");
                writer.write("-------\n");
                for (int i = 2; i <= 997; i++) {
                    writer.write(String.format(" * %s = %s\n", i, factor(i)));
                }
            }

            private static String factor(int n) {
                for (int i = 2; i < n; i++) {
                    int x = n / i;
                    if (x * i == n) {
                        return factor(x) + " × " + i;
                    }
                }
                return Integer.toString(n);
            }
        };

        ClientRequest request = ClientRequest.builder()
                .url("https://api.github.com/markdown/raw")
                .post(requestBody);

        try (ClientResponse response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new JayoException("Unexpected code " + response.getStatusCode());
            }

            System.out.println(response.getBody().string());
        }
    }

    public static void main(String... args) {
        new PostStreaming().run();
    }
}
