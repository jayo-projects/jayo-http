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

package jayo.http.internal.publicsuffix

import jayo.*
import jayo.bytestring.ByteString
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal class ResourcePublicSuffixList(
    val path: Path = PUBLIC_SUFFIX_RESOURCE,
) : PublicSuffixList {
    /** True after we've attempted to read the list for the first time. */
    private val listRead = AtomicBoolean(false)

    /** Used for concurrent threads reading the list for the first time. */
    private val readCompleteLatch = CountDownLatch(1)

    // The lists are held as a large array of UTF-8 bytes. This is to avoid allocating lots of strings
    // that will likely never be used. Each rule is separated by '\n'. Please see the
    // PublicSuffixListGenerator class for how these lists are generated.
    // Guarded by this.
    private lateinit var liBytes: ByteString
    private lateinit var liExceptionBytes: ByteString

    private fun readTheList() {
        var publicSuffixListBytes: ByteString?
        var publicSuffixExceptionListBytes: ByteString?

        try {
            path.reader().buffered().gzip().buffered().use { bufferedSource ->
                val totalBytes = bufferedSource.readInt()
                publicSuffixListBytes = bufferedSource.readByteString(totalBytes.toLong())

                val totalExceptionBytes = bufferedSource.readInt()
                publicSuffixExceptionListBytes = bufferedSource.readByteString(totalExceptionBytes.toLong())
            }

            synchronized(this) {
                this.liBytes = publicSuffixListBytes!!
                this.liExceptionBytes = publicSuffixExceptionListBytes!!
            }
        } finally {
            readCompleteLatch.countDown()
        }
    }

    override fun ensureLoaded() {
        if (!listRead.get() && listRead.compareAndSet(false, true)) {
            readTheListUninterruptibly()
        } else {
            try {
                readCompleteLatch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt() // Retain interrupted status.
            }
        }

        check(::liBytes.isInitialized) {
            // May have failed with an IOException
            "Unable to load $PUBLIC_SUFFIX_RESOURCE resource from the classpath."
        }
    }

    override fun bytes(): ByteString {
        return liBytes
    }

    override fun exceptionBytes(): ByteString {
        return liExceptionBytes
    }

    /**
     * Reads the public suffix list treating the operation as uninterruptible. We always want to read
     * the list otherwise we'll be left in a bad state. If the thread was interrupted prior to this
     * operation, it will be re-interrupted after the list is read.
     */
    private fun readTheListUninterruptibly() {
        var interrupted = false
        try {
            while (true) {
                try {
                    readTheList()
                    return
                } catch (_: JayoInterruptedIOException) {
                    Thread.interrupted() // Temporarily clear the interrupted state.
                    interrupted = true
                } catch (e: JayoException) {
                    println("JayoException thrown : ${e.message}")
                    return
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt() // Retain interrupted status.
            }
        }
    }

    /** Visible for testing. */
    fun setListBytes(
        publicSuffixListBytes: ByteString,
        publicSuffixExceptionListBytes: ByteString,
    ) {
        this.liBytes = publicSuffixListBytes
        this.liExceptionBytes = publicSuffixExceptionListBytes
        listRead.set(true)
        readCompleteLatch.countDown()
    }

    companion object {
        @JvmField
        val PUBLIC_SUFFIX_RESOURCE: Path =
            Path.of("src/test/resources/jayo/http/internal/publicsuffix/${PublicSuffixDatabase::class.java.simpleName}.gz")
    }
}
