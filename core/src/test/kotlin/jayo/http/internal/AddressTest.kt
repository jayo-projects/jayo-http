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

package jayo.http.internal

import jayo.http.internal.connection.TestValueFactory
import jayo.network.Proxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class AddressTest {
    private val factory =
        TestValueFactory().apply {
            uriHost = "example.com"
            uriPort = 80
        }

    @AfterEach
    fun tearDown() {
        factory.close()
    }

    @Test
    fun equalsAndHashcode() {
        val a = factory.newAddress()
        val b = factory.newAddress()
        assertThat(b).isEqualTo(a)
        assertThat(b.hashCode()).isEqualTo(a.hashCode())
    }

    @Test
    fun addressToString() {
        val address = factory.newAddress()
        assertThat(address.toString())
            .isEqualTo("Address{example.com:80, proxy=no proxy}")
    }

    @Test
    fun addressWithProxyToString() {
        val proxy = Proxy.http(InetSocketAddress(0))
        val address = factory.newAddress(proxy = proxy)
        assertThat(address.toString())
            .isEqualTo("Address{example.com:80, proxy=HTTP @ 0.0.0.0:0}")
    }
}
