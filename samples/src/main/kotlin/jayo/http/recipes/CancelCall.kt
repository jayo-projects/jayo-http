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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CancelCall {
    private val executor = Executors.newScheduledThreadPool(1)
    private val client = JayoHttpClient()

    fun run() {
        val request = ClientRequest.builder()
            .url("http://httpbin.org/delay/2") // This URL is served with a 2-second delay.
            .get()

        val startNanos = System.nanoTime()
        val call = client.newCall(request)

        // Schedule a job to cancel the call in 1 second.
        executor.schedule({
            System.out.printf("%.2f Canceling call.%n", (System.nanoTime() - startNanos) / 1e9f)
            call.cancel()
            System.out.printf("%.2f Canceled call.%n", (System.nanoTime() - startNanos) / 1e9f)
        }, 1, TimeUnit.SECONDS)

        System.out.printf("%.2f Executing call.%n", (System.nanoTime() - startNanos) / 1e9f)
        try {
            call.execute().use { response ->
                System.out.printf(
                    "%.2f Call was expected to fail, but completed: %s%n",
                    (System.nanoTime() - startNanos) / 1e9f,
                    response,
                )
            }
        } catch (je: JayoException) {
            System.out.printf(
                "%.2f Call failed as expected: %s%n",
                (System.nanoTime() - startNanos) / 1e9f,
                je,
            )
        }

        executor.shutdown()
    }
}

fun main() {
    CancelCall().run()
}
