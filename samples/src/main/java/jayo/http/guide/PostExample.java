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

package jayo.http.guide;

import jayo.http.*;

public class PostExample {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    final JayoHttpClient client = JayoHttpClient.create();

    String post(String url, String json) {
        ClientRequestBody body = ClientRequestBody.create(json, JSON);
        ClientRequest request = ClientRequest.builder()
                .url(url)
                .post(body);
        try (ClientResponse response = client.newCall(request).execute()) {
            return response.getBody().string();
        }
    }

    String newPost(String title, String body, String... tags) {
        return "{\"userId\":42" +
                ",\"title\":\"" + title +
                "\",\"body\":\"" + body +
                "\",\"tags\":[\"" + String.join("\",\"", tags) + "\"]}";
    }

    public static void main(String[] args) {
        PostExample example = new PostExample();
        String json = example
                .newPost("My new post", "This post is the most amazing one !", "awesome", "nice");
        String response = example.post("https://dummyjson.com/posts/add", json);
        System.out.println(response);
    }
}
