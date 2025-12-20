/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
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

import jayo.Buffer
import jayo.RawReader
import jayo.bytestring.ByteString
import jayo.http.Cookie
import jayo.http.Headers
import jayo.http.HttpUrl
import jayo.http.Proxies
import jayo.http.tools.JayoHttpUtils
import jayo.tls.Protocol
import jayo.tls.ServerHandshakeCertificates
import jayo.tools.JayoTlsUtils
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.*
import java.util.concurrent.ThreadFactory

object TestUtils {
    @JvmField
    val UNREACHABLE_ADDRESS_IPV4 = InetSocketAddress("198.51.100.1", 8080)
    val UNREACHABLE_ADDRESS_IPV6 = InetSocketAddress("::ffff:198.51.100.1", 8080)

    /** See `org.graalvm.nativeimage.ImageInfo`. */
    @JvmStatic
    val isGraalVmImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null

    /**
     * See FinalizationTester for discussion on how to best trigger GC in tests.
     * https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
     * java/lang/ref/FinalizationTester.java
     */
    @Throws(Exception::class)
    @JvmStatic
    fun awaitGarbageCollection() {
        Runtime.getRuntime().gc()
        Thread.sleep(100)
        System.runFinalization()
    }

    /**
     * Make assertions about the suppressed exceptions on this. Prefer this over making direct calls
     * so tests pass on GraalVM, where suppressed exceptions are silently discarded.
     *
     * https://github.com/oracle/graal/issues/3008
     */
    @JvmStatic
    fun Throwable.assertSuppressed(block: (List<@JvmSuppressWildcards Throwable>) -> Unit) {
        if (isGraalVmImage) return
        block(suppressed.toList())
    }

    @JvmStatic
    fun threadFactory(name: String): ThreadFactory =
        object : ThreadFactory {
            private var nextId = 1

            override fun newThread(runnable: Runnable): Thread = Thread(runnable, "$name-${nextId++}")
        }

    @JvmStatic
    fun repeat(
        c: Char,
        count: Int,
    ): String {
        val array = CharArray(count)
        Arrays.fill(array, c)
        return String(array)
    }

    /**
     * Jayo buffers are internally implemented as a linked list of byte arrays. Usually this implementation detail is
     * invisible to the caller, but subtle use of certain APIs may depend on these internal structures.
     *
     * We make such subtle calls in [jayo.http.internal.ws.MessageInflater] because we try to read a compressed stream
     * that is terminated in a web socket frame even though the DEFLATE stream is not terminated.
     *
     * Use this method to create a degenerate Jayo Buffer where each byte is in a separate segment of the internal list.
     */
    @JvmStatic
    fun fragmentBuffer(buffer: Buffer): Buffer {
        // Write each byte into a new buffer, then clone it so that the segments are shared.
        // Shared segments cannot be compacted, so we'll get a long chain of short segments.
        val result = Buffer()
        while (!buffer.exhausted()) {
            val box = Buffer()
            box.writeFrom(buffer, 1)
            result.writeFrom(box.clone(), 1)
        }
        return result
    }
}

internal infix fun Byte.and(mask: Int): Int = toInt() and mask

internal infix fun Short.and(mask: Int): Int = toInt() and mask

internal infix fun Int.and(mask: Long): Long = toLong() and mask

internal fun ServerHandshakeCertificates.sslSocketFactory() =
    JayoTlsUtils.handshakeCertSSLContext(this).socketFactory

internal fun Proxy.toJayo() =
    when (type()) {
        Proxy.Type.DIRECT -> Proxies.EMPTY
        Proxy.Type.HTTP -> Proxies.of(jayo.network.Proxy.http(address() as InetSocketAddress))
        // let's forget Socks 4 for simplicity
        Proxy.Type.SOCKS -> Proxies.of(jayo.network.Proxy.socks5(address() as InetSocketAddress))
    }

internal fun List<Protocol>.toOkhttp() =
    this.map { okhttp3.Protocol.get(it.toString()) }

@Suppress("INVISIBLE_REFERENCE")
internal fun Headers.toOkhttp() =
    okhttp3.Headers.Builder().apply {
        forEach { h -> addLenient(h.name, h.value) }
    }.build()

internal fun ByteString.toOkio() = toByteArray().toByteString()

internal abstract class ForwardingRawReader(
    private val delegate: RawReader,
) : RawReader {
    final override fun readAtMostTo(writer: Buffer, byteCount: Long) =
        delegate.readAtMostTo(writer, byteCount)

    override fun close() = delegate.close()
}

internal fun AutoCloseable.closeQuietly() = JayoHttpUtils.closeQuietly(this)

internal fun parseCookie(
    currentTimeMillis: Long,
    url: HttpUrl,
    setCookie: String,
): Cookie? = RealCookie.parse(currentTimeMillis, url, setCookie)
