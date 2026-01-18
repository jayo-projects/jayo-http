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
import java.time.Duration

class PerCallSettings {
    private val client = JayoHttpClient()

    fun run() {
        val request = ClientRequest.builder()
            .url("https://httpbin.org/delay/1") // This URL is served with a 1-second delay.
            .get()

        // Copy to customize Jayo HTTP for this request.
        val client1 = client.newBuilder()
            .readTimeout(Duration.ofMillis(500))
            .build()
        try {
            client1.newCall(request).execute().use { response ->
                println("Response 1 succeeded: $response")
            }
        } catch (je: JayoException) {
            println("Response 1 failed: $je")
        }

        // Copy to customize Jayo HTTP for this request.
        val client2 = client.newBuilder()
            .readTimeout(Duration.ofMillis(3000))
            .build()
        try {
            client2.newCall(request).execute().use { response ->
                println("Response 2 succeeded: $response")
            }
        } catch (je: JayoException) {
            println("Response 2 failed: $je")
        }
    }
}

fun main() {
    PerCallSettings().run()
}
