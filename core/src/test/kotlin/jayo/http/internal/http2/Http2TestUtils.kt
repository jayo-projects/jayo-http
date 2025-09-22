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

package jayo.http.internal.http2

import jayo.http.internal.connection.RealConnection
import jayo.http.internal.connection.RealJayoHttpClient
import jayo.scheduler.internal.TaskFaker
import org.assertj.core.api.Assertions.assertThat

object Http2TestUtils {
    @JvmStatic
    fun headerEntries(vararg elements: String?): List<RealBinaryHeader> =
        List(elements.size / 2) { RealBinaryHeader(elements[it * 2]!!, elements[it * 2 + 1]!!) }

    fun connectHttp2(
        peer: MockHttp2Peer,
        realConnection: RealConnection,
        maxConcurrentStreams: Int,
        taskFaker: TaskFaker,
    ): Http2Connection {
        // Write the mocking script.
        val settings1 = Settings()
        settings1[Settings.MAX_CONCURRENT_STREAMS] = maxConcurrentStreams
        peer.sendFrame().settings(settings1)
        peer.acceptFrame() // ACK
        peer.sendFrame().ping(false, 2, 0)
        peer.acceptFrame() // PING
        peer.play()

        // Play it back.
        val connection =
            Http2Connection
                .Builder(true, RealJayoHttpClient.DEFAULT_TASK_RUNNER)
                .socket(peer.openSocket(), "peer")
                .pushObserver(Http2ConnectionTest.IGNORE)
                .listener(realConnection)
                .build()
        connection.start(false)

        // verify the peer received the ACK
        val ackFrame = peer.takeFrame()
        assertThat(ackFrame.type).isEqualTo(Http2.TYPE_SETTINGS)
        assertThat(ackFrame.streamId).isEqualTo(0)
        assertThat(ackFrame.ack).isTrue()

        taskFaker.runTasks()

        return connection
    }

    fun updateMaxConcurrentStreams(
        connection: Http2Connection,
        amount: Int,
        taskFaker: TaskFaker,
    ) {
        val settings = Settings()
        settings[Settings.MAX_CONCURRENT_STREAMS] = amount
        connection.readerRunnable.applyAndAckSettings(true, settings)
        assertThat(connection.peerSettings[Settings.MAX_CONCURRENT_STREAMS]).isEqualTo(amount)
        taskFaker.runTasks()
    }
}