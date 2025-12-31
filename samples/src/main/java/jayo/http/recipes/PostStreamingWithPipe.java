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

import jayo.Jayo;
import jayo.JayoException;
import jayo.Pipe;
import jayo.Writer;
import jayo.http.*;

public final class PostStreamingWithPipe {
    public static final MediaType MEDIA_TYPE_MARKDOWN = MediaType.get("text/x-markdown; charset=utf-8");

    private final JayoHttpClient client = JayoHttpClient.create();

    public void run() {
        final PipeBody pipeBody = new PipeBody();

        ClientRequest request = ClientRequest.builder()
                .url("https://api.github.com/markdown/raw")
                .post(pipeBody);

        streamPrimesToSinkAsynchronously(pipeBody.writer());

        try (ClientResponse response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new JayoException("Unexpected code " + response.getStatusCode());
            }

            System.out.println(response.getBody().string());
        }
    }

    private void streamPrimesToSinkAsynchronously(final Writer writer) {
        Thread thread = new Thread("writer") {
            @Override
            public void run() {
                try {
                    writer.write("Numbers\n");
                    writer.write("-------\n");
                    for (int i = 2; i <= 997; i++) {
                        System.out.println(i);
                        Thread.sleep(10);
                        writer.write(String.format(" * %s = %s\n", i, factor(i)));
                    }
                    writer.close();
                } catch (JayoException | InterruptedException e) {
                    e.printStackTrace();
                }
            }

            private String factor(int n) {
                for (int i = 2; i < n; i++) {
                    int x = n / i;
                    if (x * i == n) return factor(x) + " × " + i;
                }
                return Integer.toString(n);
            }
        };

        thread.start();
    }

    /**
     * This request body makes it possible for another thread to stream data to the uploading request. This is potentially
     * useful for posting live event streams like video capture. Callers should write to {@code sink()} and close it to complete the post.
     */
    static final class PipeBody implements ClientRequestBody {
        private final Pipe pipe = Pipe.create(8192L);
        private final Writer writer = Jayo.buffer(pipe.getWriter());

        public Writer writer() {
            return writer;
        }

        @Override
        public MediaType contentType() {
            return MEDIA_TYPE_MARKDOWN;
        }

        @Override
        public long contentByteSize() {
            return -1L;
        }

        @Override
        public void writeTo(Writer destination) {
            destination.writeAllFrom(pipe.getReader());
        }
    }

    public static void main(String... args) {
        new PostStreamingWithPipe().run();
    }
}
