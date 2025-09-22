/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2013 Square, Inc.
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jayo.http.internal

import jayo.JayoException
import jayo.http.ClientRequest
import jayo.http.ClientResponse
import jayo.http.Headers
import jayo.http.HttpUrl
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant

/**
 * A received response or failure recorded by the response recorder.
 */
class RecordedResponse(
    @JvmField val request: ClientRequest,
    val response: ClientResponse?,
    val body: String?,
    val failure: JayoException?,
) {
    fun assertRequestUrl(url: HttpUrl) =
        apply {
            assertThat(request.url).isEqualTo(url)
        }

    fun assertRequestMethod(method: String) =
        apply {
            assertThat(request.method).isEqualTo(method)
        }

    fun assertRequestHeader(
        name: String,
        vararg values: String,
    ) = apply {
        assertThat(request.headers(name)).containsExactly(*values)
    }

    fun assertCode(expectedCode: Int) =
        apply {
            assertThat(response!!.statusCode).isEqualTo(expectedCode)
        }

    fun assertSuccessful() =
        apply {
            assertThat(failure).isNull()
            assertThat(response!!.isSuccessful).isTrue()
        }

    fun assertNotSuccessful() =
        apply {
            assertThat(response!!.isSuccessful).isFalse()
        }

    fun assertHeader(
        name: String,
        vararg values: String?,
    ) = apply {
        assertThat(response!!.headers(name)).containsExactly(*values)
    }

    fun assertHeaders(headers: Headers) =
        apply {
            assertThat(response!!.headers).isEqualTo(headers)
        }

    fun assertBody(expectedBody: String) =
        apply {
            assertThat(body).isEqualTo(expectedBody)
        }

    fun assertHandshake() =
        apply {
            val handshake = response!!.handshake!!
            assertThat(handshake.tlsVersion).isNotNull()
            assertThat(handshake.cipherSuite).isNotNull()
            assertThat(handshake.peerPrincipal).isNotNull()
            assertThat(handshake.peerCertificates.size).isEqualTo(1)
            assertThat(handshake.localPrincipal).isNull()
            assertThat(handshake.localCertificates.size).isEqualTo(0)
        }

    /**
     * Asserts that the current response was redirected and returns the prior response.
     */
    fun priorResponse(): RecordedResponse {
        val priorResponse = response!!.priorResponse!!
        return RecordedResponse(priorResponse.request, priorResponse, null, null)
    }

    /**
     * Asserts that the current response used the network and returns the network response.
     */
    fun networkResponse(): RecordedResponse {
        val networkResponse = response!!.networkResponse!!
        return RecordedResponse(networkResponse.request, networkResponse, null, null)
    }

    /** Asserts that the current response didn't use the network.  */
    fun assertNoNetworkResponse() =
        apply {
            assertThat(response!!.networkResponse).isNull()
        }

    /** Asserts that the current response didn't use the cache.  */
    fun assertNoCacheResponse() =
        apply {
            assertThat(response!!.cacheResponse).isNull()
        }

    /**
     * Asserts that the current response used the cache and returns the cache response.
     */
    fun cacheResponse(): RecordedResponse {
        val cacheResponse = response!!.cacheResponse!!
        return RecordedResponse(cacheResponse.request, cacheResponse, null, null)
    }

    fun assertFailure(vararg allowedExceptionTypes: Class<*>) =
        apply {
            var found = false
            for (expectedClass in allowedExceptionTypes) {
                if (expectedClass.isInstance(failure)) {
                    found = true
                    break
                }
            }
            assertThat(found).isTrue()
        }

    fun assertFailure(vararg messages: String) =
        apply {
            assertThat(failure).isNotNull()
            assertThat(messages).contains(failure!!.message)
        }

    fun assertFailureMatches(vararg patterns: String) =
        apply {
            val message = failure!!.message!!
            assertThat(
                patterns.firstOrNull { pattern ->
                    message.matches(pattern.toRegex())
                },
            ).isNotNull()
        }

    fun assertSentRequestAtMillis(
        minimum: Long,
        maximum: Long,
    ) = apply {
        assertDateInRange(minimum, response!!.sentRequestAt, maximum)
    }

    fun assertReceivedResponseAtMillis(
        minimum: Long,
        maximum: Long,
    ) = apply {
        assertDateInRange(minimum, response!!.receivedResponseAt, maximum)
    }

    private fun assertDateInRange(
        minimum: Long,
        actual: Instant,
        maximum: Long,
    ) {
        assertThat(actual.toEpochMilli()).isBetween(minimum, maximum)
    }
}
