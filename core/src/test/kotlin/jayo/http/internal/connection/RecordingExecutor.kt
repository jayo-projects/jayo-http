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

package jayo.http.internal.connection

import jayo.http.*
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * A fake executor for testing that never executes anything! Instead, it just keeps track of what's been enqueued.
 */
internal class RecordingExecutor(
    private val dispatcherTest: DispatcherTest,
) : AbstractExecutorService(), EventListener, WebSocketListener {
    private var shutdown: Boolean = false
    private val calls = mutableListOf<Pair<Call?, RealCall.AsyncCall?>>()

    override fun execute(command: Runnable) {
        if (shutdown) throw RejectedExecutionException()
        // do not execute
    }

    override fun dispatcherExecution(asyncCall: Call.AsyncCall, dispatcher: Dispatcher) {
        if (!shutdown) {
            calls.add(null to asyncCall as RealCall.AsyncCall)
        }
    }

    override fun onEnqueued(call: Call, dispatcher: Dispatcher) {
        calls.add(call to null)
    }

    fun assertJobs(vararg expectedUrls: String) {
        val actualUrls = calls.map {
            if (it.first != null) {
                it.first!!.request().url.toString()
            } else {
                it.second!!.call().request().url.toString()
            }
        }
        assertThat(actualUrls).containsExactly(*expectedUrls)
    }

    fun finishJob(url: String) {
        val i = calls.iterator()
        while (i.hasNext()) {
            val asyncCall = i.next().second
            if (asyncCall?.call()?.request()?.url.toString() == url) {
                i.remove()
                dispatcherTest.dispatcher.finished(asyncCall!!)
                return
            }
        }
        throw AssertionError("No such job: $url")
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = throw UnsupportedOperationException()

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = throw UnsupportedOperationException()

    override fun shutdownNow(): List<Runnable> = throw UnsupportedOperationException()
}