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
import jayo.http.ClientRequest
import jayo.http.ClientRequestBody
import jayo.http.JayoHttpClient
import jayo.http.MediaType

class PostString {
    private val client = JayoHttpClient()

    fun run() {
        val postBody =
            """
      |Releases
      |--------
      |
      | * _1.0_ May 6, 2013
      | * _1.1_ June 15, 2013
      | * _1.2_ August 11, 2013
      |
      """.trimMargin()

        val request = ClientRequest.builder()
            .url("https://api.github.com/markdown/raw")
            .post(ClientRequestBody.create(postBody, MEDIA_TYPE_MARKDOWN))

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw JayoException("Unexpected code ${response.statusCode}")
            }

            println(response.body.string())
        }
    }

    companion object {
        val MEDIA_TYPE_MARKDOWN = MediaType.get("text/x-markdown; charset=utf-8")
    }
}

fun main() {
    PostString().run()
}
