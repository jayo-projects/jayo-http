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

import jayo.JayoException
import jayo.Writer
import jayo.http.ClientRequest
import jayo.http.ClientRequestBody
import jayo.http.JayoHttpClient
import jayo.http.toMediaType

class PostStreaming {
    private val client = JayoHttpClient()

    fun run() {
        val requestBody =
            object : ClientRequestBody {
                override fun contentType() = MEDIA_TYPE_MARKDOWN
                override fun contentByteSize() = -1L

                override fun writeTo(writer: Writer) {
                    writer.write("Numbers\n")
                    writer.write("-------\n")
                    for (i in 2..997) {
                        writer.write(String.format(" * $i = ${factor(i)}\n"))
                    }
                }

                private fun factor(n: Int): String {
                    for (i in 2 until n) {
                        val x = n / i
                        if (x * i == n) {
                          return "${factor(x)} × $i"
                        }
                    }
                    return n.toString()
                }
            }

        val request = ClientRequest.builder()
            .url("https://api.github.com/markdown/raw")
            .post(requestBody)

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw JayoException("Unexpected code ${response.statusCode}")
            }

            println(response.body.string())
        }
    }

    companion object {
        val MEDIA_TYPE_MARKDOWN = "text/x-markdown; charset=utf-8".toMediaType()
    }
}

fun main() {
    PostStreaming().run()
}
