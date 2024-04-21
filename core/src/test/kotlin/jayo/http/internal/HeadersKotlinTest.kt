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

import jayo.http.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class HeadersKotlinTest {
    @Test
    fun getOperator() {
        val headers = Headers.of("a", "b", "c", "d")
        assertThat(headers["a"]).isEqualTo("b")
        assertThat(headers["c"]).isEqualTo("d")
        assertThat(headers["e"]).isNull()
    }

    @Test
    fun iteratorOperator() {
        val headers = Headers.of("a", "b", "c", "d")

        val pairs = mutableListOf<Pair<String, String>>()
        for (header in headers) {
            pairs += header.name to header.value
        }

        assertThat(pairs).containsExactly("a" to "b", "c" to "d")
    }

    @Test
    fun builderGetOperator() {
        val builder = Headers.builder()
        builder.add("a", "b")
        builder.add("c", "d")
        assertThat(builder["a"]).isEqualTo("b")
        assertThat(builder["c"]).isEqualTo("d")
        assertThat(builder["e"]).isNull()
    }

    @Test
    fun builderSetOperator() {
        val builder = Headers.builder()
        builder["a"] = "b"
        builder["c"] = "d"
        builder["e"] = Instant.EPOCH
        assertThat(builder["a"]).isEqualTo("b")
        assertThat(builder["c"]).isEqualTo("d")
        assertThat(builder["e"]).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    }
}
