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

package jayo.http.internal

import jayo.JayoException
import jayo.http.*
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Test

class DispatcherCleanupTest {
    @StartStop
    private val server = MockWebServer()

    @Test
    fun testFinish() {
        val jayoHttp = JayoHttpClient.create()
        val callback =
            object : Callback {
                override fun onFailure(
                    call: Call,
                    je: JayoException,
                ) {
                }

                override fun onResponse(
                    call: Call,
                    response: ClientResponse,
                ) {
                    response.close()
                }
            }
        repeat(10_000) {
            jayoHttp.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .get()
            )
                .enqueue(callback)
        }
        jayoHttp.dispatcher.executorService.shutdown()
    }
}
