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

import jayo.http.CacheControl
import jayo.http.Headers
import jayo.http.build
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit.*
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class CacheControlTest {
    @Test
    @Throws(Exception::class)
    fun emptyBuilderIsEmpty() {
        val cacheControl = CacheControl.builder().build()
        assertThat(cacheControl.toString()).isEqualTo("")
        assertThat(cacheControl.noCache()).isFalse()
        assertThat(cacheControl.noStore()).isFalse()
        assertThat(cacheControl.maxAgeSeconds()).isEqualTo(-1)
        assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1)
        assertThat(cacheControl.isPrivate).isFalse()
        assertThat(cacheControl.isPublic).isFalse()
        assertThat(cacheControl.mustRevalidate()).isFalse()
        assertThat(cacheControl.maxStaleSeconds()).isEqualTo(-1)
        assertThat(cacheControl.minFreshSeconds()).isEqualTo(-1)
        assertThat(cacheControl.onlyIfCached()).isFalse()
        assertThat(cacheControl.mustRevalidate()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun completeBuilder() {
        val cacheControl =
            CacheControl.builder()
                .noCache()
                .noStore()
                .maxAge(1.seconds.toJavaDuration())
                .maxStale(2.seconds.toJavaDuration())
                .minFresh(3.seconds.toJavaDuration())
                .onlyIfCached()
                .noTransform()
                .immutable()
                .build()
        assertThat(cacheControl.toString()).isEqualTo(
            "no-cache, no-store, max-age=1, max-stale=2, min-fresh=3, only-if-cached, no-transform, immutable",
        )
        assertThat(cacheControl.noCache()).isTrue()
        assertThat(cacheControl.noStore()).isTrue()
        assertThat(cacheControl.maxAgeSeconds()).isEqualTo(1)
        assertThat(cacheControl.maxStaleSeconds()).isEqualTo(2)
        assertThat(cacheControl.minFreshSeconds()).isEqualTo(3)
        assertThat(cacheControl.onlyIfCached()).isTrue()
        assertThat(cacheControl.noTransform()).isTrue()
        assertThat(cacheControl.immutable()).isTrue()

        // These members are accessible to response headers only.
        assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1)
        assertThat(cacheControl.isPrivate).isFalse()
        assertThat(cacheControl.isPublic).isFalse()
        assertThat(cacheControl.mustRevalidate()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun completeDsl() {
        val cacheControl =
            CacheControl.builder().build {
                noCache()
                noStore()
                maxAge = 1.seconds
                maxStale = 2.seconds
                minFresh = 3.seconds
                onlyIfCached()
                noTransform()
                immutable()
            }
        assertThat(cacheControl.toString()).isEqualTo(
            "no-cache, no-store, max-age=1, max-stale=2, min-fresh=3, only-if-cached, no-transform, immutable",
        )
        assertThat(cacheControl.noCache()).isTrue()
        assertThat(cacheControl.noStore()).isTrue()
        assertThat(cacheControl.maxAgeSeconds()).isEqualTo(1)
        assertThat(cacheControl.maxStaleSeconds()).isEqualTo(2)
        assertThat(cacheControl.minFreshSeconds()).isEqualTo(3)
        assertThat(cacheControl.onlyIfCached()).isTrue()
        assertThat(cacheControl.noTransform()).isTrue()
        assertThat(cacheControl.immutable()).isTrue()

        // These members are accessible to response headers only.
        assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1)
        assertThat(cacheControl.isPrivate).isFalse()
        assertThat(cacheControl.isPublic).isFalse()
        assertThat(cacheControl.mustRevalidate()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun parseEmpty() {
        val cacheControl =
            CacheControl.parse(
                Headers.builder().set("Cache-Control", "").build(),
            )
        assertThat(cacheControl.toString()).isEqualTo("")
        assertThat(cacheControl.noCache()).isFalse()
        assertThat(cacheControl.noStore()).isFalse()
        assertThat(cacheControl.maxAgeSeconds()).isEqualTo(-1)
        assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1)
        assertThat(cacheControl.isPublic).isFalse()
        assertThat(cacheControl.mustRevalidate()).isFalse()
        assertThat(cacheControl.maxStaleSeconds()).isEqualTo(-1)
        assertThat(cacheControl.minFreshSeconds()).isEqualTo(-1)
        assertThat(cacheControl.onlyIfCached()).isFalse()
        assertThat(cacheControl.immutable()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun parse() {
        val header = (
                "no-cache, no-store, max-age=1, s-maxage=2, private, public, must-revalidate, " +
                        "max-stale=3, min-fresh=4, only-if-cached, no-transform"
                )
        val cacheControl =
            CacheControl.parse(
                Headers.builder()
                    .set("Cache-Control", header)
                    .build(),
            )
        assertThat(cacheControl.noCache()).isTrue()
        assertThat(cacheControl.noStore()).isTrue()
        assertThat(cacheControl.maxAgeSeconds()).isEqualTo(1)
        assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(2)
        assertThat(cacheControl.isPrivate).isTrue()
        assertThat(cacheControl.isPublic).isTrue()
        assertThat(cacheControl.mustRevalidate()).isTrue()
        assertThat(cacheControl.maxStaleSeconds()).isEqualTo(3)
        assertThat(cacheControl.minFreshSeconds()).isEqualTo(4)
        assertThat(cacheControl.onlyIfCached()).isTrue()
        assertThat(cacheControl.noTransform()).isTrue()
        assertThat(cacheControl.toString()).isEqualTo(header)
    }

    @Test
    @Throws(Exception::class)
    fun parseIgnoreCacheControlExtensions() {
        // Example from http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.6
        val header = "private, community=\"UCI\""
        val cacheControl =
            CacheControl.parse(
                Headers.builder()
                    .set("Cache-Control", header)
                    .build(),
            )
        assertThat(cacheControl.noCache()).isFalse()
        assertThat(cacheControl.noStore()).isFalse()
        assertThat(cacheControl.maxAgeSeconds()).isEqualTo(-1)
        assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1)
        assertThat(cacheControl.isPrivate).isTrue()
        assertThat(cacheControl.isPublic).isFalse()
        assertThat(cacheControl.mustRevalidate()).isFalse()
        assertThat(cacheControl.maxStaleSeconds()).isEqualTo(-1)
        assertThat(cacheControl.minFreshSeconds()).isEqualTo(-1)
        assertThat(cacheControl.onlyIfCached()).isFalse()
        assertThat(cacheControl.noTransform()).isFalse()
        assertThat(cacheControl.immutable()).isFalse()
        assertThat(cacheControl.toString()).isEqualTo(header)
    }

    @Test
    fun parseCacheControlAndPragmaAreCombined() {
        val headers = Headers.of("Cache-Control", "max-age=12", "Pragma", "must-revalidate", "Pragma", "public")
        val cacheControl = CacheControl.parse(headers)
        assertThat(cacheControl.toString()).isEqualTo("max-age=12, public, must-revalidate")
    }

    @Test
    fun parseCacheControlHeaderValueIsRetained() {
        val value = "max-age=12"
        val headers = Headers.of("Cache-Control", value)
        val cacheControl = CacheControl.parse(headers)
        assertThat(cacheControl.toString()).isSameAs(value)
    }

    @Test
    fun parseCacheControlHeaderValueInvalidatedByPragma() {
        val headers =
            Headers.of(
                "Cache-Control",
                "max-age=12",
                "Pragma",
                "must-revalidate",
            )
        val cacheControl = CacheControl.parse(headers)
        assertThat(cacheControl.toString()).isEqualTo("max-age=12, must-revalidate")
    }

    @Test
    fun parseCacheControlHeaderValueInvalidatedByTwoValues() {
        val headers =
            Headers.of(
                "Cache-Control",
                "max-age=12",
                "Cache-Control",
                "must-revalidate",
            )
        val cacheControl = CacheControl.parse(headers)
        assertThat(cacheControl.toString()).isEqualTo("max-age=12, must-revalidate")
    }

    @Test
    fun parsePragmaHeaderValueIsNotRetained() {
        val headers = Headers.of("Pragma", "must-revalidate")
        val cacheControl = CacheControl.parse(headers)
        assertThat(cacheControl.toString()).isEqualTo("must-revalidate")
    }

    @Test
    fun computedHeaderValueIsCached() {
        val cacheControl =
            CacheControl.builder()
                .maxAge(Duration.of(2, ChronoUnit.DAYS))
                .build()
        assertThat(cacheControl.toString()).isEqualTo("max-age=172800")
        assertThat(cacheControl.toString()).isSameAs(cacheControl.toString())
    }

    @Test
    @Throws(Exception::class)
    fun timeDurationTruncatedToMaxValue() {
        val cacheControl =
            CacheControl.builder()
                .maxAge(Duration.of(365 * 100, ChronoUnit.DAYS)) // Longer than Integer.MAX_VALUE seconds.
                .build()
        assertThat(cacheControl.maxAgeSeconds()).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    @Throws(Exception::class)
    fun secondsMustBeNonNegative() {
        val builder = CacheControl.builder()
        assertFailsWith<IllegalArgumentException> {
            builder.maxAge(Duration.of(-1, ChronoUnit.SECONDS))
        }
    }

    @Test
    @Throws(Exception::class)
    fun timePrecisionIsTruncatedToSeconds() {
        val cacheControl =
            CacheControl.builder()
                .maxAge(Duration.of(4999, ChronoUnit.MILLIS))
                .build()
        assertThat(cacheControl.maxAgeSeconds()).isEqualTo(4)
    }
}
