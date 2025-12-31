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

import jayo.*;
import jayo.http.*;

public final class Progress {

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/initial/samples/src/main/resources/jayo-http.txt")
                .get();

        final ProgressListener progressListener = new ProgressListener() {
            boolean firstUpdate = true;

            @Override
            public void update(long bytesRead, long contentLength, boolean done) {
                if (done) {
                    System.out.println("completed");
                } else {
                    if (firstUpdate) {
                        firstUpdate = false;
                        if (contentLength == -1) {
                            System.out.println("content-length: unknown");
                        } else {
                            System.out.format("content-length: %d\n", contentLength);
                        }
                    }

                    System.out.println(bytesRead);

                    if (contentLength != -1) {
                        System.out.format("%d%% done\n", (100 * bytesRead) / contentLength);
                    }
                }
            }
        };

        JayoHttpClient client = JayoHttpClient.builder()
                .addNetworkInterceptor(chain -> {
                    ClientResponse originalResponse = chain.proceed(chain.request());
                    return originalResponse.newBuilder()
                            .body(new ProgressResponseBody(originalResponse.getBody(), progressListener))
                            .build();
                })
                .build();

        try (ClientResponse response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new JayoException("Unexpected code " + response.getStatusCode());
            }

            System.out.println(response.getBody().string());
        }
    }

    public static void main(String... args) {
        new Progress().run();
    }

    private static class ProgressResponseBody extends ClientResponseBody {

        private final ClientResponseBody responseBody;
        private final ProgressListener progressListener;
        private Reader reader;

        ProgressResponseBody(ClientResponseBody responseBody, ProgressListener progressListener) {
            this.responseBody = responseBody;
            this.progressListener = progressListener;
        }

        @Override
        public MediaType contentType() {
            return responseBody.contentType();
        }

        @Override
        public long contentByteSize() {
            return responseBody.contentByteSize();
        }

        @Override
        public Reader reader() {
            if (reader == null) {
                reader = Jayo.buffer(reader(responseBody.reader()));
            }
            return reader;
        }

        private RawReader reader(RawReader reader) {
            return new RawReader() {
                long totalBytesRead = 0L;

                @Override
                public long readAtMostTo(final Buffer destination, final long byteCount) {
                    long bytesRead = reader.readAtMostTo(destination, byteCount);
                    // read() returns the number of bytes read, or -1 if this reader is exhausted.
                    totalBytesRead += bytesRead != -1 ? bytesRead : 0;
                    progressListener.update(totalBytesRead, responseBody.contentByteSize(), bytesRead == -1);
                    return bytesRead;
                }

                @Override
                public void close() {
                    reader.close();
                }
            };
        }
    }

    interface ProgressListener {
        void update(long bytesRead, long contentLength, boolean done);
    }
}
