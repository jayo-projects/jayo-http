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

package jayo.http.internal.connection

import jayo.http.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.*
import java.util.concurrent.SynchronousQueue

class CallTagsTest {
    @JvmField
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    private var client = clientTestRule.newClient()

    @Test
    fun noTag() {
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call = client.newCall(request)
        assertThat(call.tag(Any::class.java)).isNull()
        assertThat(call.tag(UUID::class.java)).isNull()
        assertThat(call.tag(String::class.java)).isNull()

        // Alternate access APIs also work.
        assertThat(call.tag<String>()).isNull()
        assertThat(call.tag(String::class)).isNull()
    }

    @Test
    fun webSocketTag() {
        val uuidTag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val blockingQueue = SynchronousQueue<UUID>()
        val client = client.newBuilder()
            .addInterceptor { chain ->
                val call = chain.call()
                blockingQueue.put(call.tag(UUID::class.java)!!)
                chain.proceed(chain.request())
            }.build()

        val webSocket = client.newWebSocket(request, object : WebSocketListener {}, Tag(UUID::class.java, uuidTag))
        assertThat(blockingQueue.take()).isSameAs(uuidTag)
        webSocket.close(1000, null)
    }

    @Test
    fun javaClassTag() {
        val uuidTag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call = client.newCall(request, Tag(UUID::class.java, uuidTag))
        assertThat(call.tag(Any::class.java)).isNull()
        assertThat(call.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(call.tag(String::class.java)).isNull()

        // Alternate access APIs also work.
        assertThat(call.tag(UUID::class)).isSameAs(uuidTag)
        assertThat(call.tag<UUID>()).isSameAs(uuidTag)
    }

    @Test
    fun kotlinReifiedTag() {
        val uuidTag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call = client.newCall(request, Tag<UUID>(uuidTag))
        assertThat(call.tag(Any::class.java)).isNull()
        assertThat(call.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(call.tag(String::class.java)).isNull()

        // Alternate access APIs also work.
        assertThat(call.tag(UUID::class)).isSameAs(uuidTag)
        assertThat(call.tag<UUID>()).isSameAs(uuidTag)
    }

    @Test
    fun kotlinClassTag() {
        val uuidTag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call = client.newCall(request, Tag(UUID::class, uuidTag))
        assertThat(call.tag(Any::class.java)).isNull()
        assertThat(call.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(call.tag(String::class.java)).isNull()

        // Alternate access APIs also work.
        assertThat(call.tag(UUID::class)).isSameAs(uuidTag)
        assertThat(call.tag<UUID>()).isSameAs(uuidTag)
    }

    @Test
    fun replaceOnlyTag() {
        val uuidTag1 = UUID.randomUUID()
        val uuidTag2 = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call = client.newCall(
            request,
            Tag(UUID::class.java, uuidTag1),
            Tag(UUID::class.java, uuidTag2)
        )
        assertThat(call.tag(UUID::class.java)).isSameAs(uuidTag2)
    }

    @Test
    fun multipleTags() {
        val uuidTag = UUID.randomUUID()
        val stringTag = "dilophosaurus"
        val longTag = 20170815L
        val objectTag = Any()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call = client.newCall(
            request,
            Tag(Any::class.java, objectTag),
            Tag(UUID::class.java, uuidTag),
            Tag(String::class.java, stringTag),
            Tag(Long::class.javaObjectType, longTag)
        )
        assertThat(call.tag(Any::class.java)).isSameAs(objectTag)
        assertThat(call.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(call.tag(String::class.java)).isSameAs(stringTag)
        assertThat(call.tag(Long::class.javaObjectType)).isEqualTo(longTag)
    }

    @Test
    fun tagsAreLogged() {
        val uuidTag = UUID.randomUUID()
        val stringTag = "dilophosaurus"
        val longTag = 20170815L
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call = client.newCall(
            request,
            Tag(UUID::class.java, uuidTag),
            Tag(String::class.java, stringTag),
            Tag(Long::class.javaObjectType, longTag)
        )
        assertThat(call.toString())
            .isEqualTo(
                "Call{originalRequest=ClientRequest{method=GET, url=https://jayo.dev/}" +
                        ", state=Call not started" +
                        ", tags={" +
                        "class java.util.UUID=$uuidTag" +
                        ", class java.lang.String=$stringTag" +
                        ", class java.lang.Long=$longTag" +
                        "}" +
                        ", forWebSocket=false" +
                        "}"
            )
    }

    @Test
    fun tagsCanBeComputed() {
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call = client.newCall(request)

        // Check the Kotlin-focused APIs.
        assertThat(call.tag(String::class) { "a" }).isEqualTo("a")
        assertThat(call.tag<String> { "b" }).isEqualTo("a")
        assertThat(call.tag(String::class)).isEqualTo("a")

        // Check the Java-focused APIs.
        assertThat(call.tag(Integer::class) { 1 as Integer }).isEqualTo(1)
        assertThat(call.tag(Integer::class) { 2 as Integer }).isEqualTo(1)
        assertThat(call.tag(Integer::class)).isEqualTo(1)
    }

    /** Confirm that we don't accidentally share the backing map between objects. */
    @Test
    fun tagsAreImmutable() {
        val uuidTag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        val call1 = client.newCall(request, Tag(UUID::class.java, uuidTag))
        call1.tag(String::class.java) { "a" }
        val call2 = call1.clone()

        assertThat(call1.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(call1.tag(String::class.java)).isSameAs("a")
        assertThat(call2.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(call2.tag(String::class.java)).isNull()

        // add a String tag to call2
        call2.tag(String::class.java) { "b" }
        assertThat(call1.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(call1.tag(String::class.java)).isSameAs("a")
        assertThat(call2.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(call2.tag(String::class.java)).isSameAs("b")
    }
}
