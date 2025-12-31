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

import java.io.File;

public final class PostMultipart {
    private static final MediaType MEDIA_TYPE_TXT = MediaType.get("text/plain; charset=utf-8");

    private final JayoHttpClient client = JayoHttpClient.create();

    public void run() {
        ClientRequestBody requestBody = MultipartBody.builder()
                .type(MultipartBody.FORM)
                .addFormDataPart("title", "Jayo HTTP banner")
                .addFormDataPart("text", "jayo-http.txt",
                        ClientRequestBody.create(
                                new File("samples/src/main/resources/jayo-http.txt"),
                                MEDIA_TYPE_TXT))
                .build();

        ClientRequest request = ClientRequest.builder()
                .url("https://httpbin.org/anything")
                .post(requestBody);

        try (ClientResponse response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new JayoException("Unexpected code " + response.getStatusCode());
            }

            System.out.println(response.getBody().string());
        }
    }

    public static void main(String... args) {
        new PostMultipart().run();
    }
}
