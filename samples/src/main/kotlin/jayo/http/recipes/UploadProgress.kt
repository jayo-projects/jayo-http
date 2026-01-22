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

package jayo.http.recipes.kt

import jayo.*
import jayo.http.ClientRequest
import jayo.http.ClientRequestBody
import jayo.http.JayoHttpClient
import jayo.http.MediaType
import java.io.File

class UploadProgress {
    companion object {
        private val MEDIA_TYPE_TXT = MediaType.get("text/plain; charset=utf-8")
    }

    private val client = JayoHttpClient()

    fun run() {
        val progressListener =
            object : ProgressListener {
                private var firstUpdate = true

                override fun update(
                    bytesWritten: Long,
                    contentLength: Long,
                    done: Boolean,
                ) {
                    if (done) {
                        println("completed")
                    } else {
                        if (firstUpdate) {
                            firstUpdate = false
                            if (contentLength == -1L) {
                                println("content-length: unknown")
                            } else {
                                println("content-length: $contentLength")
                            }
                        }
                        println(bytesWritten)
                        if (contentLength != -1L) {
                            println("${100 * bytesWritten / contentLength}% done")
                        }
                    }
                }
            }

        val file = File("samples/src/main/resources/jayo-http.txt")
        val requestBody = ClientRequestBody.create(file, MEDIA_TYPE_TXT)

        val request = ClientRequest.builder()
            .url("https://httpbin.org/anything")
            .post(ProgressRequestBody(requestBody, progressListener))

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw JayoException("Unexpected code ${response.statusCode}")
            }

            println(response.body.string())
        }
    }

    private class ProgressRequestBody(
        private val delegate: ClientRequestBody,
        private val progressListener: ProgressListener,
    ) : ClientRequestBody {
        override fun contentType() = delegate.contentType()

        override fun contentByteSize(): Long = delegate.contentByteSize()

        override fun writeTo(destination: Writer) {
            val forwardingRawWriter = object : RawWriter {
                private var totalBytesWritten = 0L
                private var completed = false

                override fun writeFrom(source: Buffer, byteCount: Long) {
                    destination.writeFrom(source, byteCount)
                    totalBytesWritten += byteCount
                    progressListener.update(totalBytesWritten, contentByteSize(), completed)
                }

                override fun flush() {
                    destination.flush()
                }

                override fun close() {
                    destination.close()
                    if (!completed) {
                        completed = true
                        progressListener.update(totalBytesWritten, contentByteSize(), completed)
                    }
                }
            }
            val bufferedSink = forwardingRawWriter.buffered()
            delegate.writeTo(bufferedSink)
            bufferedSink.flush()
        }
    }

    fun interface ProgressListener {
        fun update(
            bytesWritten: Long,
            contentLength: Long,
            done: Boolean,
        )
    }
}

fun main() {
    UploadProgress().run()
}
