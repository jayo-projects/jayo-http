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

import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.JayoHttpClient;

public class GetExample {
    final JayoHttpClient client = JayoHttpClient.create();

    String run(String url) {
        ClientRequest request = ClientRequest.builder()
                .url(url)
                .get();

        try (ClientResponse response = client.newCall(request).execute()) {
            return response.getBody().string();
        }
    }

    public static void main(String[] args) {
        GetExample example = new GetExample();
        String response = example.run("https://raw.github.com/jayo-projects/jayo-http/main/README.md");
        System.out.println(response);
    }
}
