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
import jayo.http.Cache
import jayo.http.ClientRequest
import jayo.http.JayoHttpClient
import java.nio.file.Files
import java.nio.file.Path

class CacheResponse(cacheDirectory: Path) {
    private val client: JayoHttpClient = JayoHttpClient.builder()
        .cache(
            Cache.create(
                cacheDirectory,
                10L * 1024L * 1024L, // 1 MiB.
            ),
        ).build()

    fun run() {
        val request = ClientRequest.builder()
            .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/main/samples/src/main/resources/jayo-http.txt")
            .get()

        val response1Body =
            client.newCall(request).execute().use {
                if (!it.isSuccessful) {
                    throw JayoException("Unexpected code ${it.statusCode}")
                }

                println("Response 1 response:          $it")
                println("Response 1 cache response:    ${it.cacheResponse}")
                println("Response 1 network response:  ${it.networkResponse}")
                return@use it.body.string()
            }

        val response2Body =
            client.newCall(request).execute().use {
                if (!it.isSuccessful) {
                    throw JayoException("Unexpected code ${it.statusCode}")
                }

                println("Response 2 response:          $it")
                println("Response 2 cache response:    ${it.cacheResponse}")
                println("Response 2 network response:  ${it.networkResponse}")
                return@use it.body.string()
            }

        println("Response 2 equals Response 1? " + (response1Body == response2Body))
    }
}

fun main() {
    CacheResponse(Files.createTempDirectory("CacheResponse.tmp")).run()
}
