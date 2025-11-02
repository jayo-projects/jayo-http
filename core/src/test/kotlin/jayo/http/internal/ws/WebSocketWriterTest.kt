/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package jayo.http.internal.ws

import jayo.Buffer
import jayo.bytestring.ByteString
import jayo.bytestring.ByteString.EMPTY
import jayo.bytestring.decodeHex
import jayo.bytestring.encodeToByteString
import jayo.bytestring.toByteString
import jayo.http.internal.TestUtils.repeat
import jayo.http.internal.Utils.format
import jayo.http.internal.ws.WebSocketProtocol.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.*
import kotlin.test.assertFailsWith

class WebSocketWriterTest {
    private val data = Buffer()
    private val random = Random(0)

    /**
     * Check all data as verified inside of the test. We do this in an AfterEachCallback so that
     * exceptions thrown from the test do not cause this check to fail.
     */
    @RegisterExtension
    val noDataLeftBehind =
        AfterEachCallback { context: ExtensionContext ->
            if (context.executionException.isPresent) return@AfterEachCallback
            assertThat(data.readByteString().hex()).isEqualTo("")
        }

    // Mutually exclusive. Use the one corresponding to the peer whose behavior you wish to test.
    private val serverWriter =
        WebSocketWriter(
            false,
            data,
            random,
            false,
            false,
            0L,
        )
    private val clientWriter =
        WebSocketWriter(
            true,
            data,
            random,
            false,
            false,
            0L,
        )

    @Test
    fun serverTextMessage() {
        serverWriter.writeMessageFrame(OPCODE_TEXT, "Hello".encodeToByteString())
        assertData("810548656c6c6f")
    }

    @Test
    fun serverCompressedTextMessage() {
        val serverWriter =
            WebSocketWriter(
                false,
                data,
                random,
                true,
                false,
                0L,
            )
        serverWriter.writeMessageFrame(OPCODE_TEXT, "Hello".encodeToByteString())
        assertData("c107f248cdc9c90700")
    }

    @Test
    fun serverSmallBufferedPayloadWrittenAsOneFrame() {
        val length = 5
        val payload: ByteString = (binaryData(length))
        serverWriter.writeMessageFrame(OPCODE_TEXT, payload)
        assertData("8105")
        assertData(payload)
    }

    @Test
    fun serverLargeBufferedPayloadWrittenAsOneFrame() {
        val length = 12345
        val payload: ByteString = (binaryData(length))
        serverWriter.writeMessageFrame(OPCODE_TEXT, payload)
        assertData("817e")
        assertData(format("%04x", length))
        assertData(payload)
    }

    @Test
    fun clientTextMessage() {
        clientWriter.writeMessageFrame(OPCODE_TEXT, "Hello".encodeToByteString())
        assertData("818560b420bb28d14cd70f")
    }

    @Test
    fun clientCompressedTextMessage() {
        val clientWriter =
            WebSocketWriter(
                false,
                data,
                random,
                true,
                false,
                0L,
            )
        clientWriter.writeMessageFrame(OPCODE_TEXT, "Hello".encodeToByteString())
        assertData("c107f248cdc9c90700")
    }

    @Test
    fun serverBinaryMessage() {
        val payload =
            (
                    "60b420bb3851d9d47acb933dbe70399bf6c92da33af01d4fb770e98c0325f41d3ebaf8986da71" +
                            "2c82bcd4d554bf0b54023c2"
                    ).decodeHex()
        serverWriter.writeMessageFrame(OPCODE_BINARY, payload)
        assertData("8232")
        assertData(payload)
    }

    @Test
    fun serverMessageLengthShort() {
        // Create a payload which will overflow the normal payload byte size.
        val payload = Buffer()
        while (payload.completeSegmentByteCount() <= PAYLOAD_BYTE_MAX) {
            payload.writeByte('0'.code.toByte())
        }
        serverWriter.writeMessageFrame(OPCODE_BINARY, payload.snapshot())

        // Write directly to the unbuffered sink. This ensures it will become single frame.
        assertData("827e") // 'e' == 4-byte follow-up length.
        assertData(format("%04X", payload.completeSegmentByteCount()))
        assertData(payload.readByteString())
    }

    @Test
    fun serverMessageLengthLong() {
        // Create a payload which will overflow the normal and short payload byte size.
        val payload = Buffer()
        while (payload.completeSegmentByteCount() <= PAYLOAD_SHORT_MAX) {
            payload.writeByte('0'.code.toByte())
        }
        serverWriter.writeMessageFrame(OPCODE_BINARY, payload.snapshot())

        // Write directly to the unbuffered sink. This ensures it will become single frame.
        assertData("827f") // 'f' == 16-byte follow-up length.
        assertData(format("%016X", payload.bytesAvailable()))
        assertData(payload.readByteString())
    }

    @Test
    fun clientBinary() {
        val payload =
            (
                    "60b420bb3851d9d47acb933dbe70399bf6c92da33af01d4fb770e98c0325f41d3ebaf8986da71" +
                            "2c82bcd4d554bf0b54023c2"
                    ).decodeHex()
        clientWriter.writeMessageFrame(OPCODE_BINARY, payload)
        assertData("82b2")
        assertData("60b420bb")
        assertData(
            "0000000058e5f96f1a7fb386dec41920967d0d185a443df4d7c4c9376391d4a65e0ed8230d1332734b796dee2" +
                    "b4495fb4376",
        )
    }

    @Test
    fun serverEmptyClose() {
        serverWriter.writeClose(0, null)
        assertData("8800")
    }

    @Test
    fun serverCloseWithCode() {
        serverWriter.writeClose(1001, null)
        assertData("880203e9")
    }

    @Test
    fun serverCloseWithCodeAndReason() {
        serverWriter.writeClose(1001, "Hello".encodeToByteString())
        assertData("880703e948656c6c6f")
    }

    @Test
    fun clientEmptyClose() {
        clientWriter.writeClose(0, null)
        assertData("888060b420bb")
    }

    @Test
    fun clientCloseWithCode() {
        clientWriter.writeClose(1001, null)
        assertData("888260b420bb635d")
    }

    @Test
    fun clientCloseWithCodeAndReason() {
        clientWriter.writeClose(1001, "Hello".encodeToByteString())
        assertData("888760b420bb635d68de0cd84f")
    }

    @Test
    fun closeWithOnlyReasonThrows() {
        clientWriter.writeClose(0, "Hello".encodeToByteString())
        assertData("888760b420bb60b468de0cd84f")
    }

    @Test
    fun closeCodeOutOfRangeThrows() {
        assertFailsWith<IllegalArgumentException> {
            clientWriter.writeClose(5042.toShort(), "Hello".encodeToByteString())
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Code must be in range [1000,5000): 5042")
        }
    }

    @Test
    fun closeReservedThrows() {
        assertFailsWith<IllegalArgumentException> {
            clientWriter.writeClose(1005, "Hello".encodeToByteString())
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Code 1005 is reserved and may not be used.")
        }
    }

    @Test
    fun serverEmptyPing() {
        serverWriter.writePing(EMPTY)
        assertData("8900")
    }

    @Test
    fun clientEmptyPing() {
        clientWriter.writePing(EMPTY)
        assertData("898060b420bb")
    }

    @Test
    fun serverPingWithPayload() {
        serverWriter.writePing("Hello".encodeToByteString())
        assertData("890548656c6c6f")
    }

    @Test
    fun clientPingWithPayload() {
        clientWriter.writePing("Hello".encodeToByteString())
        assertData("898560b420bb28d14cd70f")
    }

    @Test
    fun serverEmptyPong() {
        serverWriter.writePong(EMPTY)
        assertData("8a00")
    }

    @Test
    fun clientEmptyPong() {
        clientWriter.writePong(EMPTY)
        assertData("8a8060b420bb")
    }

    @Test
    fun serverPongWithPayload() {
        serverWriter.writePong("Hello".encodeToByteString())
        assertData("8a0548656c6c6f")
    }

    @Test
    fun clientPongWithPayload() {
        clientWriter.writePong("Hello".encodeToByteString())
        assertData("8a8560b420bb28d14cd70f")
    }

    @Test
    fun pingTooLongThrows() {
        assertFailsWith<IllegalArgumentException> {
            serverWriter.writePing(binaryData(1000))
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "Payload size must be less than or equal to 125",
            )
        }
    }

    @Test
    fun pongTooLongThrows() {
        assertFailsWith<IllegalArgumentException> {
            serverWriter.writePong((binaryData(1000)))
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "Payload size must be less than or equal to 125",
            )
        }
    }

    @Test
    fun closeTooLongThrows() {
        assertFailsWith<IllegalArgumentException> {
            val longReason: ByteString = repeat('X', 124).encodeToByteString()
            serverWriter.writeClose(1000, longReason)
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "Payload size must be less than or equal to 125",
            )
        }
    }

    private fun assertData(hex: String) {
        assertData(hex.decodeHex())
    }

    private fun assertData(expected: ByteString) {
        val actual = data.readByteString(Math.min(expected.byteSize().toLong(), data.bytesAvailable()))
        assertThat(actual).isEqualTo(expected)
    }

    companion object {
        private fun binaryData(length: Int): ByteString {
            val junk = ByteArray(length)
            Random(0).nextBytes(junk)
            return junk.toByteString()
        }
    }
}
