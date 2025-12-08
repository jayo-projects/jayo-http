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

package jayo.http.internal.ws

import jayo.*
import jayo.Jayo.inMemorySocketPair
import jayo.bytestring.decodeHex
import jayo.bytestring.encodeToByteString
import jayo.http.ClientRequest
import jayo.http.ClientResponse
import jayo.http.FailingCall
import jayo.http.Headers
import jayo.http.internal.TestUtils.repeat
import jayo.http.internal.ws.WebSocketExtensions.parse
import jayo.scheduler.internal.TaskFaker
import jayo.tls.Protocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*
import kotlin.test.assertFailsWith

class RealWebSocketTest {
    // NOTE: Fields are named 'client' and 'server' for cognitive simplicity. This differentiation has no effect on the
    // behavior of the WebSocket API, which is why tests are only written once from the perspective of a single peer.
    private val random = Random(0)
    private val taskFaker = TaskFaker()
    private val sockets = inMemorySocketPair(8192L)
    private val client = TestStreams(taskFaker, sockets[0], client = true)
    private val server = TestStreams(taskFaker, sockets[1], client = false)

    @BeforeEach
    fun setUp() {
        client.initWebSocket(random)
        server.initWebSocket(random)
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        client.listener.assertExhausted()
        server.listener.assertExhausted()
        server.reader.close()
        client.reader.close()
        taskFaker.runTasks()
        server.webSocket!!.tearDown()
        client.webSocket!!.tearDown()
        taskFaker.close()
    }

    @Test
    fun close() {
        client.webSocket!!.close(1000, "Hello!")
        // This will trigger a close response.
        assertThat(server.processNextFrame()).isFalse()
        server.listener.assertClosing(1000, "Hello!")
        server.webSocket!!.finishReader()
        server.webSocket!!.close(1000, "Goodbye!")
        assertThat(client.processNextFrame()).isFalse()
        client.listener.assertClosing(1000, "Goodbye!")
        client.webSocket!!.finishReader()
        server.listener.assertClosed(1000, "Hello!")
        client.listener.assertClosed(1000, "Goodbye!")
    }

    @Test
    fun clientCloseThenMethodsReturnFalse() {
        client.webSocket!!.close(1000, "Hello!")
        assertThat(client.webSocket!!.close(1000, "Hello!")).isFalse()
        assertThat(client.webSocket!!.send("Hello!")).isFalse()
    }

    @Test
    fun clientCloseWith0Fails() {
        assertFailsWith<IllegalArgumentException> {
            client.webSocket!!.close(0, null)
        }.also { expected ->
            assertThat("Code must be in range [1000,5000): 0")
                .isEqualTo(expected.message)
        }
    }

    @Test
    fun afterSocketClosedPingFailsWebSocket() {
        server.reader.close()
        client.webSocket!!.pong("Ping!".encodeToByteString())
        taskFaker.runTasks()
        client.listener.assertFailure(JayoException::class.java, "reader is closed")
        assertThat(client.webSocket!!.send("Hello!")).isFalse()
    }

    @Test
    fun socketClosedDuringMessageKillsWebSocket() {
        server.reader.close()
        assertThat(client.webSocket!!.send("Hello!")).isTrue()
        taskFaker.runTasks()
        client.listener.assertFailure(JayoException::class.java, "reader is closed")

        // A failed write prevents further use of the WebSocket instance.
        assertThat(client.webSocket!!.send("Hello!")).isFalse()
        assertThat(client.webSocket!!.pong("Ping!".encodeToByteString())).isFalse()
    }

    @Test
    fun serverCloseThenWritingPingSucceeds() {
        server.webSocket!!.close(1000, "Hello!")
        client.processNextFrame()
        client.listener.assertClosing(1000, "Hello!")
        assertThat(client.webSocket!!.pong("Pong?".encodeToByteString())).isTrue()
    }

    @Test
    fun clientCanWriteMessagesAfterServerClose() {
        server.webSocket!!.close(1000, "Hello!")
        client.processNextFrame()
        client.listener.assertClosing(1000, "Hello!")
        assertThat(client.webSocket!!.send("Hi!")).isTrue()
        server.processNextFrame()
        server.listener.assertTextMessage("Hi!")
    }

    @Test
    fun serverCloseThenClientClose() {
        server.webSocket!!.close(1000, "Hello!")
        client.processNextFrame()
        client.listener.assertClosing(1000, "Hello!")
        assertThat(client.webSocket!!.close(1000, "Bye!")).isTrue()
        client.webSocket!!.finishReader()
        taskFaker.runTasks()
        client.listener.assertClosed(1000, "Hello!")
        server.processNextFrame()
        server.listener.assertClosing(1000, "Bye!")
        server.webSocket!!.finishReader()
        server.listener.assertClosed(1000, "Bye!")
    }

    @Test
    fun emptyCloseInitiatesShutdown() {
        server.writer.write("8800".decodeHex()).emit() // Close without code.
        client.processNextFrame()
        client.listener.assertClosing(1005, "")
        client.webSocket!!.finishReader()
        assertThat(client.webSocket!!.close(1000, "Bye!")).isTrue()
        server.processNextFrame()
        server.listener.assertClosing(1000, "Bye!")
        server.webSocket!!.finishReader()
        client.listener.assertClosed(1005, "")
    }

    @Test
    fun clientCloseClosesConnection() {
        client.webSocket!!.close(1000, "Hello!")
        taskFaker.runTasks()
        assertThat(client.closed).isFalse()
        server.processNextFrame() // Read client closing, send server close.
        server.listener.assertClosing(1000, "Hello!")
        server.webSocket!!.finishReader()
        server.webSocket!!.close(1000, "Goodbye!")
        client.processNextFrame() // Read server closing, close connection.
        taskFaker.runTasks()
        client.listener.assertClosing(1000, "Goodbye!")
        client.webSocket!!.finishReader()
        taskFaker.runTasks()
        assertThat(client.closed).isTrue()

        // Server and client both finished closing, connection is closed.
        server.listener.assertClosed(1000, "Hello!")
        client.listener.assertClosed(1000, "Goodbye!")
    }

    @Test
    fun clientCloseCancelsConnectionAfterTimeout() {
        client.webSocket!!.close(1000, "Hello!")
        taskFaker.runTasks()
        // Note: we don't process server frames, so our client 'close' doesn't receive a server 'close'.
        assertThat(client.canceled).isFalse()

        taskFaker.advanceUntil(ns(RealWebSocket.CANCEL_AFTER_CLOSE_TIMEOUT.toMillis() - 1))
        assertThat(client.canceled).isFalse()

        taskFaker.advanceUntil(ns(RealWebSocket.CANCEL_AFTER_CLOSE_TIMEOUT.toMillis()))
        assertThat(client.canceled).isTrue()

        client.processNextFrame() // This won't get a frame, but it will get a closed pipe.
        client.listener.assertFailure(JayoException::class.java, "canceled")
        taskFaker.runTasks()
    }

    @Test
    fun clientCloseCancelsConnectionAfterCustomTimeout() {
        client.initWebSocket(random, webSocketCloseTimeout = Duration.ofMillis(5_000))
        client.webSocket!!.close(1000, "Hello!")
        taskFaker.runTasks()
        // Note: we don't process server frames, so our client 'close' doesn't receive a server 'close'.
        assertThat(client.canceled).isFalse()

        taskFaker.advanceUntil(ns(4_999))
        assertThat(client.canceled).isFalse()

        taskFaker.advanceUntil(ns(5_000))
        assertThat(client.canceled).isTrue()

        client.processNextFrame() // This won't get a frame, but it will get a closed pipe.
        client.listener.assertFailure(JayoException::class.java, "canceled")
        taskFaker.runTasks()
    }

    @Test
    fun serverCloseClosesConnection() {
        server.webSocket!!.close(1000, "Hello!")
        client.processNextFrame() // Read server close, send client close, close connection.
        assertThat(client.closed).isFalse()
        client.listener.assertClosing(1000, "Hello!")
        client.webSocket!!.finishReader()
        client.webSocket!!.close(1000, "Hello!")
        server.processNextFrame()
        server.listener.assertClosing(1000, "Hello!")
        server.webSocket!!.finishReader()
        client.listener.assertClosed(1000, "Hello!")
        server.listener.assertClosed(1000, "Hello!")
    }

    @Test
    fun clientAndServerCloseClosesConnection() {
        // Send close from both sides at the same time.
        server.webSocket!!.close(1000, "Hello!")
        client.processNextFrame() // Read close, close connection close.
        assertThat(client.closed).isFalse()
        client.webSocket!!.close(1000, "Hi!")
        server.processNextFrame()
        client.listener.assertClosing(1000, "Hello!")
        server.listener.assertClosing(1000, "Hi!")
        client.webSocket!!.finishReader()
        server.webSocket!!.finishReader()
        client.listener.assertClosed(1000, "Hello!")
        server.listener.assertClosed(1000, "Hi!")
        taskFaker.runTasks()
        assertThat(client.closed).isTrue()
        server.listener.assertExhausted() // Client should not have sent the second close.
        client.listener.assertExhausted() // Server should not have sent the second close.
    }

    @Test
    fun serverCloseBreaksReadMessageLoop() {
        server.webSocket!!.send("Hello!")
        server.webSocket!!.close(1000, "Bye!")
        assertThat(client.processNextFrame()).isTrue()
        client.listener.assertTextMessage("Hello!")
        assertThat(client.processNextFrame()).isFalse()
        client.listener.assertClosing(1000, "Bye!")
    }

    @Test
    fun protocolErrorBeforeCloseSendsFailure() {
        server.writer.write("0a00".decodeHex()).emit() // Invalid non-final ping frame.
        client.processNextFrame() // Detects error, send close, close connection.
        taskFaker.runTasks()
        client.webSocket!!.finishReader()
        assertThat(client.closed).isTrue()
        client.listener.assertFailure(
            JayoProtocolException::class.java,
            "Control frames must be final.",
        )
        server.processNextFrame()
        taskFaker.runTasks()
        server.listener.assertFailure()
    }

    @Test
    fun protocolErrorInCloseResponseClosesConnection() {
        client.webSocket!!.close(1000, "Hello")
        server.processNextFrame()
        // Not closed until a close reply is received.
        assertThat(client.closed).isFalse()

        // Manually write an invalid masked close frame.
        server.writer.write("888760b420bb635c68de0cd84f".decodeHex()).emit()
        client.processNextFrame() // Detects error, disconnects immediately since close already sent.
        client.webSocket!!.finishReader()
        taskFaker.runTasks()
        assertThat(client.closed).isTrue()
        client.listener.assertFailure(
            JayoProtocolException::class.java,
            "Server-sent frames must not be masked.",
        )
        server.listener.assertClosing(1000, "Hello")
        server.listener.assertExhausted() // Client should not have sent second close.
    }

    @Test
    fun protocolErrorAfterCloseDoesNotSendClose() {
        client.webSocket!!.close(1000, "Hello!")
        server.processNextFrame()

        // Not closed until a close reply is received.
        assertThat(client.closed).isFalse()
        server.writer.write("0a00".decodeHex()).emit() // Invalid non-final ping frame.
        client.processNextFrame() // Detects error, disconnects immediately since close already sent.
        client.webSocket!!.finishReader()
        taskFaker.runTasks()
        assertThat(client.closed).isTrue()
        client.listener.assertFailure(
            JayoProtocolException::class.java,
            "Control frames must be final.",
        )
        server.listener.assertClosing(1000, "Hello!")
        server.listener.assertExhausted() // Client should not have sent a second close.
    }

    @Test
    fun networkErrorReportedAsFailure() {
        server.writer.close()
        client.processNextFrame()
        taskFaker.runTasks()
        client.listener.assertFailure(JayoEOFException::class.java)
    }

    @Test
    fun closeThrowingFailsConnection() {
        server.reader.close()
        client.webSocket!!.close(1000, null)
        taskFaker.runTasks()
        client.listener.assertFailure(JayoException::class.java, "reader is closed")
    }

    @Test
    fun closeMessageAndConnectionCloseThrowingDoesNotMaskOriginal() {
        // So when the client sends close, it throws a JayoException.
        server.reader.close()
        client.webSocket!!.close(1000, "Bye!")
        taskFaker.runTasks()
        client.webSocket!!.finishReader()
        client.listener.assertFailure(JayoException::class.java, "reader is closed")
        assertThat(client.closed).isTrue()
    }

    @Test
    fun pingOnInterval() {
        client.initWebSocket(random, pingInterval = Duration.ofMillis(500))
        taskFaker.advanceUntil(ns(500L))
        server.processNextFrame() // Ping.
        client.processNextFrame() // Pong.
        taskFaker.advanceUntil(ns(1000L))
        server.processNextFrame() // Ping.
        client.processNextFrame() // Pong.
        taskFaker.advanceUntil(ns(1500L))
        server.processNextFrame() // Ping.
        client.processNextFrame() // Pong.
    }

    @Test
    fun unacknowledgedPingFailsConnection() {
        client.initWebSocket(random, pingInterval = Duration.ofMillis(500))

        // Don't process the ping and pong frames!
        taskFaker.advanceUntil(ns(500L))
        taskFaker.advanceUntil(ns(1000L))
        client.listener.assertFailure(
            JayoTimeoutException::class.java,
            "sent ping but didn't receive pong within 500ms (after 0 successful ping/pongs)",
        )
    }

    @Test
    fun unexpectedPongsDoNotInterfereWithFailureDetection() {
        client.initWebSocket(random, pingInterval = Duration.ofMillis(500))

        // At 0ms the server sends 3 unexpected pongs. The client accepts 'em and ignores 'em.
        server.webSocket!!.pong("pong 1".encodeToByteString())
        client.processNextFrame()
        server.webSocket!!.pong("pong 2".encodeToByteString())
        client.processNextFrame()
        taskFaker.runTasks()
        server.webSocket!!.pong("pong 3".encodeToByteString())
        client.processNextFrame()

        // After 500ms the client automatically pings and the server pongs back.
        taskFaker.advanceUntil(ns(500L))
        server.processNextFrame() // Ping.
        client.processNextFrame() // Pong.

        // After 1000ms the client will attempt a ping 2, but we don't process it. That'll cause the client to fail at
        // 1500ms when it's time to send ping 3 because pong 2 hasn't been received.
        taskFaker.advanceUntil(ns(1000L))
        taskFaker.advanceUntil(ns(1500L))
        client.listener.assertFailure(
            JayoTimeoutException::class.java,
            "sent ping but didn't receive pong within 500ms (after 1 successful ping/pongs)",
        )
    }

    @Test
    fun messagesNotCompressedWhenNotConfigured() {
        val message = repeat('a', RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE.toInt())
        server.webSocket!!.send(message)
        taskFaker.runTasks()
        assertThat(client.clientReaderBufferSize())
            .isGreaterThan(message.length.toLong()) // Not compressed.
        assertThat(client.processNextFrame()).isTrue()
        client.listener.assertTextMessage(message)
    }

    @Test
    fun messagesCompressedWhenConfigured() {
        val headers = Headers.of("Sec-WebSocket-Extensions", "permessage-deflate")
        client.initWebSocket(random, responseHeaders = headers)
        server.initWebSocket(random, responseHeaders = headers)
        val message = repeat('a', RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE.toInt())
        server.webSocket!!.send(message)
        taskFaker.runTasks()
        assertThat(client.clientReaderBufferSize())
            .isLessThan(message.length.toLong()) // Compressed!
        assertThat(client.processNextFrame()).isTrue()
        client.listener.assertTextMessage(message)
    }

    @Test
    fun smallMessagesNotCompressed() {
        val headers = Headers.of("Sec-WebSocket-Extensions", "permessage-deflate")
        client.initWebSocket(random, responseHeaders = headers)
        server.initWebSocket(random, responseHeaders = headers)
        val message = repeat('a', RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE.toInt() - 1)
        server.webSocket!!.send(message)
        taskFaker.runTasks()
        assertThat(client.clientReaderBufferSize())
            .isGreaterThan(message.length.toLong()) // Not compressed.
        assertThat(client.processNextFrame()).isTrue()
        client.listener.assertTextMessage(message)
    }

    /** One peer's streams, listener, and web socket in the test.  */
    private class TestStreams(
        private val taskFaker: TaskFaker,
        private val delegate: RawSocket,
        private val client: Boolean,
    ) : Socket {
        private val name = if (client) "client" else "server"
        val listener = WebSocketRecorder(name)
        var webSocket: RealWebSocket? = null
        var readerClosed = false
        var writerClosed = false
        val closed: Boolean
            get() = readerClosed && writerClosed
        var canceled = false

        private val _reader: Reader by lazy {
            val delegateReader = delegate.reader
            object : RawReader {
                override fun readAtMostTo(destination: Buffer, byteCount: Long): Long =
                    delegateReader.readAtMostTo(destination, byteCount)

                override fun close() {
                    readerClosed = true
                    delegateReader.close()
                }
            }.buffered()
        }

        private val _writer: Writer by lazy {
            val delegateWriter = delegate.writer
            object : RawWriter {
                override fun writeFrom(source: Buffer, byteCount: Long) {
                    delegateWriter.writeFrom(source, byteCount)
                }

                override fun flush() {
                    delegateWriter.flush()
                }

                override fun close() {
                    writerClosed = true
                    delegateWriter.close()
                }

            }.buffered()
        }

        override fun getReader(): Reader = _reader

        override fun getWriter(): Writer = _writer

        fun initWebSocket(
            random: Random?,
            pingInterval: Duration = Duration.ZERO,
            responseHeaders: Headers? = Headers.of(),
            webSocketCloseTimeout: Duration = RealWebSocket.CANCEL_AFTER_CLOSE_TIMEOUT,
        ) {
            val url = "http://example.com/websocket"
            val response = ClientResponse.builder()
                .statusCode(101)
                .statusMessage("OK")
                .request(ClientRequest.builder().url(url).get())
                .headers(responseHeaders!!)
                .protocol(Protocol.HTTP_1_1)
                .build()
            webSocket =
                RealWebSocket(
                    taskFaker.taskRunner,
                    response.request,
                    listener,
                    random!!,
                    pingInterval,
                    parse(responseHeaders),
                    RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE,
                    webSocketCloseTimeout,
                ).apply {
                    if (client) {
                        call =
                            object : FailingCall() {
                                override fun cancel() {
                                    this@TestStreams.cancel()
                                }
                            }
                    }
                }
            webSocket!!.initReaderAndWriter(name, this, client)
        }

        /**
         * Peeks the number of bytes available for the client to read immediately. This doesn't block, so it requires
         * that bytes have already been flushed by the server.
         */
        fun clientReaderBufferSize(): Long {
            reader.request(1L)
            return reader.bytesAvailable()
        }

        fun processNextFrame(): Boolean {
            taskFaker.runTasks()
            return webSocket!!.processNextFrame()
        }

        override fun cancel() {
            canceled = true
            delegate.cancel()
        }

        override fun isOpen(): Boolean = TODO("Not needed here")
    }

    companion object {
        private fun ns(millis: Long): Long = millis * 1000000L
    }
}
