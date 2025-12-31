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

import java.io.File;

public final class UploadProgress {
    private static final jayo.http.MediaType MEDIA_TYPE_TXT = jayo.http.MediaType.get("text/plain; charset=utf-8");

    private final JayoHttpClient client = JayoHttpClient.create();

    public void run() throws Exception {
        final ProgressListener progressListener = new ProgressListener() {
            boolean firstUpdate = true;

            @Override
            public void update(long bytesWritten, long contentLength, boolean done) {
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

                    System.out.println(bytesWritten);

                    if (contentLength != -1) {
                        System.out.format("%d%% done\n", (100 * bytesWritten) / contentLength);
                    }
                }
            }
        };

        ClientRequestBody requestBody = ClientRequestBody.create(
                new File("samples/src/main/resources/jayo-http.txt"),
                MEDIA_TYPE_TXT);

        ClientRequest request = ClientRequest.builder()
                .url("https://httpbin.org/anything")
                .post(new ProgressRequestBody(requestBody, progressListener));

        try (ClientResponse response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new JayoException("Unexpected code " + response.getStatusCode());
            }

            System.out.println(response.getBody().string());
        }
    }

    public static void main(String... args) throws Exception {
        new UploadProgress().run();
    }

    private static class ProgressRequestBody implements ClientRequestBody {

        private final ProgressListener progressListener;
        private final ClientRequestBody delegate;

        public ProgressRequestBody(ClientRequestBody delegate, ProgressListener progressListener) {
            this.delegate = delegate;
            this.progressListener = progressListener;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentByteSize() {
            return delegate.contentByteSize();
        }

        @Override
        public void writeTo(Writer destination) {
            RawWriter forwardingRawWriter = new RawWriter() {
                private long totalBytesWritten = 0L;
                private boolean completed = false;

                @Override
                public void writeFrom(Buffer source, long byteCount) {
                    destination.writeFrom(source, byteCount);
                    totalBytesWritten += byteCount;
                    progressListener.update(totalBytesWritten, contentByteSize(), completed);
                }

                @Override
                public void flush() {
                    destination.flush();
                }

                @Override
                public void close() {
                    destination.close();
                    if (!completed) {
                        completed = true;
                        progressListener.update(totalBytesWritten, contentByteSize(), completed);
                    }
                }
            };
            Writer bufferedSink = Jayo.buffer(forwardingRawWriter);
            delegate.writeTo(bufferedSink);
            bufferedSink.flush();
        }
    }

    interface ProgressListener {
        void update(long bytesWritten, long contentLength, boolean done);
    }
}
