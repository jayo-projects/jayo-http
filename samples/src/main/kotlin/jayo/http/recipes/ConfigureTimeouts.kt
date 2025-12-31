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

import jayo.http.ClientRequest
import jayo.http.JayoHttpClient
import java.time.Duration

class ConfigureTimeouts {
    private val client: JayoHttpClient = JayoHttpClient.builder()
        .networkConfig { netConfig ->
            netConfig.connectTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
        }
        .callTimeout(Duration.ofSeconds(10))
        .build()

    fun run() {
        val request = ClientRequest.builder()
            .url("http://httpbin.org/delay/2") // This URL is served with a 2-second delay.
            .get()

        client.newCall(request).execute().use { response ->
            println("Response completed: $response")
        }
    }
}

fun main() {
    ConfigureTimeouts().run()
}
