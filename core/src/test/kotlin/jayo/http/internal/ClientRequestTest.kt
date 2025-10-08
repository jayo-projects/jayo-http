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
import jayo.Reader
import jayo.buffered
import jayo.bytestring.decodeHex
import jayo.bytestring.encodeToByteString
import jayo.gzip
import jayo.http.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileWriter
import java.net.URI
import java.util.*
import kotlin.test.assertFailsWith

class ClientRequestTest {
    @Test
    fun kotlinBuilderPut() {
        val httpUrl = "https://example.com/".toHttpUrl()
        val body = "hello".toRequestBody()
        val httpHeaders = Headers.of("User-Agent", "RequestTest")
        val request = ClientRequest.builder().build {
            url = httpUrl
            headers = httpHeaders
        }.put(body) as RealClientRequest

        assertThat(request.url).isEqualTo(httpUrl)
        assertThat(request.headers).isEqualTo(httpHeaders)
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.body).isEqualTo(body)
        assertThat(request.tags).isEmpty()
    }

    @Test
    fun kotlinBuilderGet() {
        val httpUrl = "https://example.com/".toHttpUrl()
        val httpHeaders = Headers.of("User-Agent", "RequestTest")
        val request = ClientRequest.builder().build {
            url = httpUrl
            headers = httpHeaders
        }.get() as RealClientRequest
        assertThat(request.url).isEqualTo(httpUrl)
        assertThat(request.headers).isEqualTo(httpHeaders)
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.body).isNull()
        assertThat(request.tags).isEmpty()
    }

    @Test
    fun kotlinBuilderPost() {
        val httpUrl = "https://example.com/".toHttpUrl()
        val body = "hello".toRequestBody()
        val httpHeaders = Headers.of("User-Agent", "RequestTest")
        val request = ClientRequest.builder().build {
            url = httpUrl
            headers = httpHeaders
        }.post(body) as RealClientRequest

        assertThat(request.url).isEqualTo(httpUrl)
        assertThat(request.headers).isEqualTo(httpHeaders)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.body).isEqualTo(body)
        assertThat(request.tags).isEmpty()
    }

    @Test
    fun kotlinBuilderDeleteNoBody() {
        val httpUrl = "https://example.com/".toHttpUrl()
        val httpHeaders = Headers.of("User-Agent", "RequestTest")
        val request = ClientRequest.builder().build {
            url = httpUrl
            headers = httpHeaders
        }.delete() as RealClientRequest
        assertThat(request.url).isEqualTo(httpUrl)
        assertThat(request.headers).isEqualTo(httpHeaders)
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.body).isEqualTo(ClientRequestBody.EMPTY)
        assertThat(request.tags).isEmpty()
    }

    @Test
    fun kotlinBuilderDeleteWithBody() {
        val httpUrl = "https://example.com/".toHttpUrl()
        val body = "hello".toRequestBody()
        val httpHeaders = Headers.of("User-Agent", "RequestTest")
        val request = ClientRequest.builder().build {
            url = httpUrl
            headers = httpHeaders
        }.delete(body) as RealClientRequest

        assertThat(request.url).isEqualTo(httpUrl)
        assertThat(request.headers).isEqualTo(httpHeaders)
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.body).isEqualTo(body)
        assertThat(request.tags).isEmpty()
    }

    @Test
    fun string() {
        val contentType = "text/plain; charset=utf-8".toMediaType()
        val body = "abc".toByteArray().toRequestBody(contentType)
        assertThat(body.contentType()).isEqualTo(contentType)
        assertThat(body.contentByteSize()).isEqualTo(3)
        assertThat(bodyToHex(body)).isEqualTo("616263")
        // Retransmit body
        assertThat(bodyToHex(body)).isEqualTo("616263")
    }


    @Test
    fun stringWithDefaultCharsetAdded() {
        val contentType = "text/plain".toMediaType()
        val body = "\u0800".toRequestBody(contentType)
        assertThat(body.contentType()).isEqualTo("text/plain; charset=utf-8".toMediaType())
        assertThat(body.contentByteSize()).isEqualTo(3)
        assertThat(bodyToHex(body)).isEqualTo("e0a080")
    }

    @Test
    fun stringWithNonDefaultCharsetSpecified() {
        val contentType = "text/plain; charset=utf-16be".toMediaType()
        val body = "\u0800".toRequestBody(contentType)
        assertThat(body.contentType()).isEqualTo(contentType)
        assertThat(body.contentByteSize()).isEqualTo(2)
        assertThat(bodyToHex(body)).isEqualTo("0800")
    }

    @Test
    fun byteArray() {
        val contentType = "text/plain".toMediaType()
        val body = "abc".toByteArray().toRequestBody(contentType)
        assertThat(body.contentType()).isEqualTo(contentType)
        assertThat(body.contentByteSize()).isEqualTo(3)
        assertThat(bodyToHex(body)).isEqualTo("616263")
        // Retransmit body
        assertThat(bodyToHex(body)).isEqualTo("616263")
    }

    @Test
    fun byteArrayRange() {
        val contentType = "text/plain".toMediaType()
        val body = ".abcd".toByteArray().toRequestBody(contentType, 1, 3)
        assertThat(body.contentType()).isEqualTo(contentType)
        assertThat(body.contentByteSize()).isEqualTo(3)
        assertThat(bodyToHex(body)).isEqualTo("616263")
        // Retransmit body
        assertThat(bodyToHex(body)).isEqualTo("616263")
    }

    @Test
    fun byteString() {
        val contentType = "text/plain".toMediaType()
        val body = "Hello".encodeToByteString().toRequestBody(contentType)
        assertThat(body.contentType()).isEqualTo(contentType)
        assertThat(body.contentByteSize()).isEqualTo(5)
        assertThat(bodyToHex(body)).isEqualTo("48656c6c6f")
        // Retransmit body
        assertThat(bodyToHex(body)).isEqualTo("48656c6c6f")
    }

    @Test
    fun file() {
        val file = File.createTempFile("RequestTest", "tmp")
        val writer = FileWriter(file)
        writer.write("abc")
        writer.close()
        val contentType = "text/plain".toMediaType()
        val body = file.asRequestBody(contentType)
        assertThat(body.contentType()).isEqualTo(contentType)
        assertThat(body.contentByteSize()).isEqualTo(3)
        assertThat(bodyToHex(body)).isEqualTo("616263")
        // Retransmit body
        assertThat(bodyToHex(body)).isEqualTo("616263")
    }

    /** Common verbs used for apis such as GitHub, AWS, and Google Cloud.  */
    @Test
    fun crudVerbs() {
        val contentType = "application/json".toMediaType()
        val body = "{}".toRequestBody(contentType)

        val get = ClientRequest.builder().url("http://localhost/api").get()
        assertThat(get.method).isEqualTo("GET")
        assertThat(get.body).isNull()

        val head = ClientRequest.builder().url("http://localhost/api").head()
        assertThat(head.method).isEqualTo("HEAD")
        assertThat(head.body).isNull()

        val delete = ClientRequest.builder().url("http://localhost/api").delete()
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(head.body).isNull()

        val post = ClientRequest.builder().url("http://localhost/api").post(body)
        assertThat(post.method).isEqualTo("POST")
        assertThat(post.body).isEqualTo(body)

        val put = ClientRequest.builder().url("http://localhost/api").put(body)
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.body).isEqualTo(body)

        val patch = ClientRequest.builder().url("http://localhost/api").patch(body)
        assertThat(patch.method).isEqualTo("PATCH")
        assertThat(patch.body).isEqualTo(body)
    }

    @Test
    fun uninitializedURI() {
        val request = ClientRequest.builder().url("http://localhost/api").get()
        assertThat(request.url.toUri()).isEqualTo(URI("http://localhost/api"))
        assertThat(request.url).isEqualTo("http://localhost/api".toHttpUrl())
    }

    @Test
    fun newBuilderUrlResetsUrl() {
        val requestWithoutCache = ClientRequest.builder().url("http://localhost/api").get()
        val builtRequestWithoutCache = requestWithoutCache.newBuilder().url("http://localhost/api/foo").build()
        assertThat(builtRequestWithoutCache.url).isEqualTo(
            "http://localhost/api/foo".toHttpUrl(),
        )
        val requestWithCache =
            ClientRequest.builder()
                .url("http://localhost/api")
                .get()
        // cache url object
        requestWithCache.url
        val builtRequestWithCache =
            requestWithCache.newBuilder()
                .url("http://localhost/api/foo")
                .build()
        assertThat(builtRequestWithCache.url)
            .isEqualTo("http://localhost/api/foo".toHttpUrl())
    }

    @Test
    fun cacheControl() {
        val request =
            ClientRequest.builder()
                .cacheControl(CacheControl.builder().noCache().build())
                .url("https://jayo.dev")
                .get()
        assertThat(request.headers("Cache-Control")).containsExactly("no-cache")
        assertThat(request.cacheControl.noCache()).isTrue()
    }

    @Test
    fun emptyCacheControlClearsAllCacheControlHeaders() {
        val request =
            ClientRequest.builder()
                .header("Cache-Control", "foo")
                .cacheControl(CacheControl.builder().build())
                .url("https://jayo.dev")
                .get()
        assertThat(request.headers("Cache-Control")).isEmpty()
    }

    @Test
    fun headerAcceptsPermittedCharacters() {
        val builder = ClientRequest.builder()
        builder.header("AZab09~", "AZab09 ~")
        builder.addHeader("AZab09~", "AZab09 ~")
    }

    @Test
    fun emptyNameForbidden() {
        val builder = ClientRequest.builder()
        assertFailsWith<IllegalArgumentException> {
            builder.header("", "Value")
        }
        assertFailsWith<IllegalArgumentException> {
            builder.addHeader("", "Value")
        }
    }

    @Test
    fun headerAllowsTabOnlyInValues() {
        val builder = ClientRequest.builder()
        builder.header("key", "sample\tvalue")
        assertFailsWith<IllegalArgumentException> {
            builder.header("sample\tkey", "value")
        }
    }

    @Test
    fun headerForbidsControlCharacters() {
        assertForbiddenHeader("\u0000")
        assertForbiddenHeader("\r")
        assertForbiddenHeader("\n")
        assertForbiddenHeader("\u001f")
        assertForbiddenHeader("\u007f")
        assertForbiddenHeader("\u0080")
        assertForbiddenHeader("\ud83c\udf69")
    }

    private fun assertForbiddenHeader(s: String) {
        val builder = ClientRequest.builder()
        assertFailsWith<IllegalArgumentException> {
            builder.header(s, "Value")
        }
        assertFailsWith<IllegalArgumentException> {
            builder.addHeader(s, "Value")
        }
        assertFailsWith<IllegalArgumentException> {
            builder.header("Name", s)
        }
        assertFailsWith<IllegalArgumentException> {
            builder.addHeader("Name", s)
        }
    }

    @Test
    fun noTag() {
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .get()
        assertThat(request.tag(Any::class.java)).isNull()
        assertThat(request.tag(UUID::class.java)).isNull()
        assertThat(request.tag(String::class.java)).isNull()

        // Alternate access APIs also work.
        assertThat(request.tag<String>()).isNull()
        assertThat(request.tag(String::class)).isNull()
    }

    @Test
    fun defaultTag() {
        val tag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .tag(tag)
                .get()
        assertThat(request.tag()).isSameAs(tag)
        assertThat(request.tag(Any::class.java)).isSameAs(tag)
        assertThat(request.tag(UUID::class.java)).isNull()
        assertThat(request.tag(String::class.java)).isNull()

        // Alternate access APIs also work.
        assertThat(request.tag<Any>()).isSameAs(tag)
        assertThat(request.tag(Any::class)).isSameAs(tag)
    }

    @Test
    fun nullRemovesTag() {
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .tag("a")
                .tag(null)
                .get()
        assertThat(request.tag()).isNull()
    }

    @Test
    fun removeAbsentTag() {
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .tag("a")
                .tag(null)
                .get()
        assertThat(request.tag()).isNull()
    }

    @Test
    fun objectTag() {
        val tag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .tag(Any::class.java, tag)
                .get()
        assertThat(request.tag()).isSameAs(tag)
        assertThat(request.tag(Any::class.java)).isSameAs(tag)
        assertThat(request.tag(UUID::class.java)).isNull()
        assertThat(request.tag(String::class.java)).isNull()

        // Alternate access APIs also work.
        assertThat(request.tag(Any::class)).isSameAs(tag)
        assertThat(request.tag<Any>()).isSameAs(tag)
    }

    @Test
    fun javaClassTag() {
        val uuidTag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .tag(UUID::class.java, uuidTag) // Use the Class<*> parameter.
                .get()
        assertThat(request.tag()).isNull()
        assertThat(request.tag(Any::class.java)).isNull()
        assertThat(request.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(request.tag(String::class.java)).isNull()

        // Alternate access APIs also work.
        assertThat(request.tag(UUID::class)).isSameAs(uuidTag)
        assertThat(request.tag<UUID>()).isSameAs(uuidTag)
    }

    @Test
    fun kotlinReifiedTag() {
        val uuidTag = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .tag<UUID>(uuidTag) // Use the type parameter.
                .get()
        assertThat(request.tag()).isNull()
        assertThat(request.tag<Any>()).isNull()
        assertThat(request.tag<UUID>()).isSameAs(uuidTag)
        assertThat(request.tag<String>()).isNull()

        // Alternate access APIs also work.
        assertThat(request.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(request.tag(UUID::class)).isSameAs(uuidTag)
    }

    @Test
    fun kotlinClassTag() {
        val uuidTag = UUID.randomUUID()
        val request =
            ClientRequest.builder().build {
                url = "https://jayo.dev".toHttpUrl()
                tag(UUID::class, uuidTag) // Use the KClass<*> parameter.
            }.get()
        assertThat(request.tag()).isNull()
        assertThat(request.tag(Any::class)).isNull()
        assertThat(request.tag(UUID::class)).isSameAs(uuidTag)
        assertThat(request.tag(String::class)).isNull()

        // Alternate access APIs also work.
        assertThat(request.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(request.tag<UUID>()).isSameAs(uuidTag)
    }

    @Test
    fun replaceOnlyTag() {
        val uuidTag1 = UUID.randomUUID()
        val uuidTag2 = UUID.randomUUID()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .tag(UUID::class.java, uuidTag1)
                .tag(UUID::class.java, uuidTag2)
                .get()
        assertThat(request.tag(UUID::class.java)).isSameAs(uuidTag2)
    }

    @Test
    fun multipleTags() {
        val uuidTag = UUID.randomUUID()
        val stringTag = "dilophosaurus"
        val longTag = 20170815L as Long?
        val objectTag = Any()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .tag(Any::class.java, objectTag)
                .tag(UUID::class.java, uuidTag)
                .tag(String::class.java, stringTag)
                .tag(Long::class.javaObjectType, longTag)
                .get()
        assertThat(request.tag()).isSameAs(objectTag)
        assertThat(request.tag(Any::class.java)).isSameAs(objectTag)
        assertThat(request.tag(UUID::class.java)).isSameAs(uuidTag)
        assertThat(request.tag(String::class.java)).isSameAs(stringTag)
        assertThat(request.tag(Long::class.javaObjectType)).isSameAs(longTag)
    }

    /** Confirm that we don't accidentally share the backing map between objects. */
    @Test
    fun tagsAreImmutable() {
        val builder =
            ClientRequest.builder()
                .url("https://jayo.dev")
        val requestA = builder.tag(String::class.java, "a").get()
        val requestB = builder.tag(String::class.java, "b").delete()
        val requestC = requestA.newBuilder().tag(String::class.java, "c").build()
        assertThat(requestA.tag(String::class.java)).isSameAs("a")
        assertThat(requestB.tag(String::class.java)).isSameAs("b")
        assertThat(requestC.tag(String::class.java)).isSameAs("c")
    }

    @Test
    fun requestToStringRedactsSensitiveHeaders() {
        val headers =
            Headers.builder()
                .add("content-length", "99")
                .add("authorization", "peanutbutter")
                .add("proxy-authorization", "chocolate")
                .add("cookie", "drink=coffee")
                .add("set-cookie", "accessory=sugar")
                .add("user-agent", "JayoHttp")
                .build()
        val request =
            ClientRequest.builder()
                .url("https://jayo.dev")
                .headers(headers)
                .get()
        assertThat(request.toString()).isEqualTo(
            "ClientRequest{method=GET, url=https://jayo.dev/, headers=[" +
                    "content-length:99," +
                    " authorization:██," +
                    " proxy-authorization:██," +
                    " cookie:██," +
                    " set-cookie:██," +
                    " user-agent:JayoHttp" +
                    "]}",
        )
    }

    @Test
    fun gzip() {
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val originalBody = "This is the original message".toRequestBody(mediaType)
        assertThat(originalBody.contentByteSize()).isEqualTo(28L)
        assertThat(originalBody.contentType()).isEqualTo(mediaType)

        val request = ClientRequest.builder()
            .url("https://example.com/")
            .gzip(true)
            .post(originalBody)
        assertThat(request.headers["Content-Encoding"]).isEqualTo("gzip")
        assertThat(request.body?.contentByteSize()).isEqualTo(-1L)
        assertThat(request.body?.contentType()).isEqualTo(mediaType)

        val requestBodyBytes: Reader = Buffer()
            .apply {
                request.body?.writeTo(this)
            }

        val decompressedRequestBody = requestBodyBytes.gzip().use {
            it.buffered().readString()
        }
        assertThat(decompressedRequestBody).isEqualTo("This is the original message")
    }

    @Test
    fun gzipIsNopWithoutABody() {
        ClientRequest.builder()
            .url("https://example.com/")
            .gzip(true)
            .get()
    }

    @Test
    fun cannotGzipWithAnotherContentEncoding() {
        assertFailsWith<IllegalStateException> {
            ClientRequest.builder()
                .url("https://example.com/")
                .addHeader("Content-Encoding", "deflate")
                .gzip(true)
                .post("This is the original message".toRequestBody())
        }.also {
            assertThat(it).hasMessage("Content-Encoding already set: deflate")
        }
    }

    @Test
    fun canGzipTwice() {
        ClientRequest.builder()
            .url("https://example.com/")
            .gzip(true)
            .gzip(true)
            .post("This is the original message".toRequestBody())
    }

    @Test
    fun curlGet() {
        val request =
            ClientRequest.builder()
                .url("https://example.com")
                .header("Authorization", "Bearer abc123")
                .get()

        val curl = request.toCurl(true)
        assertThat(curl)
            .isEqualTo(
                """
        |curl 'https://example.com/' \
        |  -H 'Authorization: Bearer abc123'
        """.trimMargin(),
            )
    }

    @Test
    fun curlPostWithBody() {
        val body = "{\"key\":\"value\"}".toRequestBody("application/json".toMediaType())

        val request =
            ClientRequest.builder()
                .url("https://api.example.com/data")
                .addHeader("Authorization", "Bearer abc123")
                .post(body)

        assertThat(request.toCurl(true))
            .isEqualTo(
                """
        |curl 'https://api.example.com/data' \
        |  -H 'Authorization: Bearer abc123' \
        |  -H 'Content-Type: application/json; charset=utf-8' \
        |  --data '{"key":"value"}'
        """.trimMargin(),
            )
    }

    @Test
    fun bodyContentTypeTakesPrecedence() {
        val body = "{\"key\":\"value\"}".toRequestBody("application/json".toMediaType())

        val request =
            ClientRequest.builder()
                .url("https://api.example.com/data")
                .addHeader("Content-Type", "text/plain")
                .post(body)

        assertThat(request.toCurl(true))
            .isEqualTo(
                """
        |curl 'https://api.example.com/data' \
        |  -H 'Content-Type: application/json; charset=utf-8' \
        |  --data '{"key":"value"}'
        """.trimMargin(),
            )
    }

    @Test
    fun requestContentTypeIsFallback() {
        val body = "{\"key\":\"value\"}".toRequestBody(contentType = null)

        val request =
            ClientRequest.builder()
                .url("https://api.example.com/data")
                .addHeader("Content-Type", "text/plain")
                .post(body)

        assertThat(request.toCurl(true))
            .isEqualTo(
                """
        |curl 'https://api.example.com/data' \
        |  -H 'Content-Type: text/plain' \
        |  --data '{"key":"value"}'
        """.trimMargin(),
            )
    }

    /** Put is not the default method so `-X 'PUT'` is included. */
    @Test
    fun curlPutWithBody() {
        val body = "{\"key\":\"value\"}".toRequestBody("application/json".toMediaType())

        val request =
            ClientRequest.builder()
                .url("https://api.example.com/data")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer abc123")
                .put(body)

        assertThat(request.toCurl(true))
            .isEqualTo(
                """
        |curl 'https://api.example.com/data' \
        |  -X 'PUT' \
        |  -H 'Authorization: Bearer abc123' \
        |  -H 'Content-Type: application/json; charset=utf-8' \
        |  --data '{"key":"value"}'
        """.trimMargin(),
            )
    }

    @Test
    fun curlPostWithComplexBody() {
        val jsonBody =
            """
      |{
      |  "user": {
      |    "id": 123,
      |    "name": "Tim O'Reilly"
      |  },
      |  "roles": ["admin", "editor"],
      |  "active": true
      |}
      |
      """.trimMargin()

        val body = jsonBody.toRequestBody("application/json".toMediaType())

        val request =
            ClientRequest.builder()
                .url("https://api.example.com/users")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer xyz789")
                .post(body)

        assertThat(request.toCurl(true))
            .isEqualTo(
                """
        |curl 'https://api.example.com/users' \
        |  -H 'Authorization: Bearer xyz789' \
        |  -H 'Content-Type: application/json; charset=utf-8' \
        |  --data '{
        |  "user": {
        |    "id": 123,
        |    "name": "Tim O'\''Reilly"
        |  },
        |  "roles": ["admin", "editor"],
        |  "active": true
        |}
        |'
        """.trimMargin(),
            )
    }

    @Test
    fun curlPostWithBinaryBody() {
        val binaryData = "00010203".decodeHex()
        val body = binaryData.toRequestBody("application/octet-stream".toMediaType())

        val request =
            ClientRequest.builder()
                .url("https://api.example.com/upload")
                .addHeader("Content-Type", "application/octet-stream")
                .post(body)

        val curl = request.toCurl(true)
        assertThat(curl)
            .isEqualTo(
                """
        |curl 'https://api.example.com/upload' \
        |  -H 'Content-Type: application/octet-stream' \
        |  --data-binary '00010203'
        """.trimMargin(),
            )
    }

    @Test
    fun curlPostWithBinaryBodyOmitted() {
        val binaryData = "1020".decodeHex()
        val body = binaryData.toRequestBody("application/octet-stream".toMediaType())

        val request =
            ClientRequest.builder()
                .url("https://api.example.com/upload")
                .addHeader("Content-Type", "application/octet-stream")
                .post(body)

        val curl = request.toCurl(false)
        assertThat(curl)
            .isEqualTo(
                """
        |curl 'https://api.example.com/upload' \
        |  -X 'POST' \
        |  -H 'Content-Type: application/octet-stream'
        """.trimMargin(),
            )
    }

    private fun bodyToHex(body: ClientRequestBody): String {
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readByteString().hex()
    }
}
