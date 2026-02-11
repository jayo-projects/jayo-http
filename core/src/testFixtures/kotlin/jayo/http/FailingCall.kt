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

import java.time.Duration
import java.util.function.Supplier

open class FailingCall : Call {
    override fun request(): ClientRequest = error("unexpected")

    override fun execute(): ClientResponse = error("unexpected")

    override fun enqueue(responseCallback: Callback): Unit = error("unexpected")

    override fun enqueueWithTimeout(timeout: Duration, responseCallback: Callback): Unit = error("unexpected")

    override fun cancel(): Unit = error("unexpected")

    override fun isExecuted(): Boolean = error("unexpected")

    override fun isCanceled(): Boolean = error("unexpected")

    override fun addEventListener(eventListener: EventListener) = error("unexpected")

    override fun <T> tag(type: Class<out T>): T? = error("unexpected")

    override fun <T> tag(type: Class<T>, computeIfAbsent: Supplier<T>): T & Any = error("unexpected")

    override fun clone(): Call = error("unexpected")

    class Async : Call.AsyncCall {
        override fun call(): Call = FailingCall()
    }
}
