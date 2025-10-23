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

package jayo.http

import jayo.Buffer
import jayo.RawWriter
import jayo.Writer
import jayo.buffered

/** Rewrites the request body sent to the server to be all uppercase.  */
class UppercaseRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): ClientResponse =
        chain.proceed(uppercaseRequest(chain.request()))

    /** Returns a request that transforms `request` to be all uppercase.  */
    private fun uppercaseRequest(request: ClientRequest): ClientRequest {
        val uppercaseBody: ClientRequestBody =
            object : ClientRequestBody {
                override fun writeTo(destination: Writer) {
                    request.body!!.writeTo(uppercaseRawWriter(destination).buffered())
                }

                override fun contentType() = request.body!!.contentType()

                override fun contentByteSize() = request.body!!.contentByteSize()

                override fun isDuplex() = request.body!!.isDuplex

                override fun isOneShot() = request.body!!.isOneShot
            }
        return request
            .newBuilder()
            .method(request.method, uppercaseBody)
    }

    private fun uppercaseRawWriter(writer: Writer): RawWriter =
        object : RawWriter {
            override fun writeFrom(source: Buffer, byteCount: Long) {
                val bytes = source.readByteString(byteCount)
                writer.writeFrom(
                    Buffer()
                        .write(bytes.toAsciiUppercase()),
                    byteCount,
                )
            }

            override fun flush() {
                writer.flush()
            }

            override fun close() {
                writer.close()
            }
        }
}
