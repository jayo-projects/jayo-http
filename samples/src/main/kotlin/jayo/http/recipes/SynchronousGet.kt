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
import jayo.http.JayoHttpClient

class SynchronousGet {
    private val client = JayoHttpClient()

    fun run() {
        val request = ClientRequest.builder()
            .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/initial/samples/src/main/resources/jayo-http.txt")
            .get()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw JayoException("Unexpected code ${response.statusCode}")
            }

            val responseHeaders = response.headers
            for (i in 0 until responseHeaders.size) {
                println(responseHeaders.name(i) + ": " + responseHeaders.value(i))
            }

            println(response.body.string())
        }
    }
}

fun main() {
    SynchronousGet().run()
}
