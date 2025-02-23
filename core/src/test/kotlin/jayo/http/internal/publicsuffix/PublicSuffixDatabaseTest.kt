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

import jayo.Buffer
import jayo.buffered
import jayo.bytestring.ByteString
import jayo.gzip
import jayo.http.internal.HostnameUtils
import jayo.reader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PublicSuffixDatabaseTest {
    private val list = ResourcePublicSuffixList()
    private val publicSuffixDatabase = PublicSuffixDatabase(list)

    @Test
    fun longestMatchWins() {
        val buffer =
            Buffer.create()
                .write("com\n")
                .write("my.jayo.com\n")
                .write("jayo.com\n")
        list.setListBytes(buffer.readByteString(), ByteString.of())
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("example.com"))
            .isEqualTo("example.com")
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.example.com"))
            .isEqualTo("example.com")
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.bar.jayo.com"))
            .isEqualTo("jayo.com")
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.jayo.com"))
            .isEqualTo("foo.my.jayo.com")
    }

    @Test
    fun wildcardMatch() {
        val buffer =
            Buffer()
                .write("*.jayo.com\n")
                .write("com\n")
                .write("example.com\n")
        list.setListBytes(buffer.readByteString(), ByteString.of())
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my.jayo.com")).isNull()
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.jayo.com"))
            .isEqualTo("foo.my.jayo.com")
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("bar.foo.my.jayo.com"))
            .isEqualTo("foo.my.jayo.com")
    }

    @Test
    fun boundarySearches() {
        val buffer =
            Buffer()
                .write("bbb\n")
                .write("ddd\n")
                .write("fff\n")
        list.setListBytes(buffer.readByteString(), ByteString.of())
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("aaa")).isNull()
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("ggg")).isNull()
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("ccc")).isNull()
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("eee")).isNull()
    }

    @Test
    fun exceptionRule() {
        val exception =
            Buffer()
                .write("my.jayo.jp\n")
        val buffer =
            Buffer()
                .write("*.jp\n")
                .write("*.jayo.jp\n")
                .write("example.com\n")
                .write("jayo.com\n")
        list.setListBytes(buffer.readByteString(), exception.readByteString())
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my.jayo.jp"))
            .isEqualTo("my.jayo.jp")
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.jayo.jp"))
            .isEqualTo("my.jayo.jp")
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my1.jayo.jp")).isNull()
    }

    @Test
    fun noEffectiveTldPlusOne() {
        val exception =
            Buffer()
                .write("my.jayo.jp\n")
        val buffer =
            Buffer()
                .write("*.jp\n")
                .write("*.jayo.jp\n")
                .write("example.com\n")
                .write("jayo.com\n")
        list.setListBytes(buffer.readByteString(), exception.readByteString())
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("example.com")).isNull()
        assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.jayo.jp")).isNull()
    }

    @Test
    fun allPublicSuffixes() {
        val buffer = Buffer()
        ResourcePublicSuffixList.PUBLIC_SUFFIX_RESOURCE.reader().buffered().gzip().buffered().use { source ->
            val length = source.readInt()
            buffer.writeFrom(source, length.toLong())
        }
        while (!buffer.exhausted()) {
            var publicSuffix = buffer.readLineStrict()
            if (publicSuffix.contains("*")) {
                // A wildcard rule, let's replace the wildcard with a value.
                publicSuffix = publicSuffix.replace("\\*".toRegex(), "jayo")
            }
            assertThat(publicSuffixDatabase.getEffectiveTldPlusOne(publicSuffix)).isNull()
            val test = "foobar.$publicSuffix"
            assertThat(publicSuffixDatabase.getEffectiveTldPlusOne(test)).isEqualTo(test)
        }
    }

    @Test
    fun publicSuffixExceptions() {
        val buffer = Buffer()
        ResourcePublicSuffixList.PUBLIC_SUFFIX_RESOURCE.reader().buffered().gzip().buffered().use { source ->
            var length = source.readInt()
            source.skip(length.toLong())
            length = source.readInt()
            buffer.writeFrom(source, length.toLong())
        }
        while (!buffer.exhausted()) {
            val exception = buffer.readLineStrict()
            assertThat(publicSuffixDatabase.getEffectiveTldPlusOne(exception)).isEqualTo(
                exception,
            )
            val test = "foobar.$exception"
            assertThat(publicSuffixDatabase.getEffectiveTldPlusOne(test)).isEqualTo(exception)
        }
    }

    @Test
    fun threadIsInterruptedOnFirstRead() {
        Thread.currentThread().interrupt()
        try {
            val result = publicSuffixDatabase.getEffectiveTldPlusOne("jayoup.com")
            assertThat(result).isEqualTo("jayoup.com")
        } finally {
            assertThat(Thread.interrupted()).isTrue()
        }
    }

    @Test
    fun secondReadFailsSameAsFirst(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("file.txt")
        filePath.createFile()
        val badPublicSuffixDatabase =
            PublicSuffixDatabase(ResourcePublicSuffixList(filePath))
        lateinit var firstFailure: Exception
        assertFailsWith<Exception> {
            badPublicSuffixDatabase.getEffectiveTldPlusOne("squareup.com")
        }.also { e ->
            firstFailure = e
        }
        assertFailsWith<Exception> {
            badPublicSuffixDatabase.getEffectiveTldPlusOne("squareup.com")
        }.also { e ->
            assertEquals(firstFailure.toString(), e.toString())
        }
    }

    /** These tests are provided by [publicsuffix.org](https://publicsuffix.org/list/). */
    @Test
    fun publicSuffixDotOrgTestCases() {
        // Any copyright is dedicated to the Public Domain.
        // https://creativecommons.org/publicdomain/zero/1.0/

        // Mixed case.
        checkPublicSuffix("COM", null)
        checkPublicSuffix("example.COM", "example.com")
        checkPublicSuffix("WwW.example.COM", "example.com")
        // Leading dot.
        checkPublicSuffix(".com", null)
        checkPublicSuffix(".example", null)
        checkPublicSuffix(".example.com", null)
        checkPublicSuffix(".example.example", null)
        // Unlisted TLD.
        checkPublicSuffix("example", null)
        checkPublicSuffix("example.example", "example.example")
        checkPublicSuffix("b.example.example", "example.example")
        checkPublicSuffix("a.b.example.example", "example.example")
        // Listed, but non-Internet, TLD.
        // checkPublicSuffix("local", null);
        // checkPublicSuffix("example.local", null);
        // checkPublicSuffix("b.example.local", null);
        // checkPublicSuffix("a.b.example.local", null);
        // TLD with only 1 rule.
        checkPublicSuffix("biz", null)
        checkPublicSuffix("domain.biz", "domain.biz")
        checkPublicSuffix("b.domain.biz", "domain.biz")
        checkPublicSuffix("a.b.domain.biz", "domain.biz")
        // TLD with some 2-level rules.
        checkPublicSuffix("com", null)
        checkPublicSuffix("example.com", "example.com")
        checkPublicSuffix("b.example.com", "example.com")
        checkPublicSuffix("a.b.example.com", "example.com")
        checkPublicSuffix("uk.com", null)
        checkPublicSuffix("example.uk.com", "example.uk.com")
        checkPublicSuffix("b.example.uk.com", "example.uk.com")
        checkPublicSuffix("a.b.example.uk.com", "example.uk.com")
        checkPublicSuffix("test.ac", "test.ac")
        // TLD with only 1 (wildcard) rule.
        checkPublicSuffix("mm", null)
        checkPublicSuffix("c.mm", null)
        checkPublicSuffix("b.c.mm", "b.c.mm")
        checkPublicSuffix("a.b.c.mm", "b.c.mm")
        // More complex TLD.
        checkPublicSuffix("jp", null)
        checkPublicSuffix("test.jp", "test.jp")
        checkPublicSuffix("www.test.jp", "test.jp")
        checkPublicSuffix("ac.jp", null)
        checkPublicSuffix("test.ac.jp", "test.ac.jp")
        checkPublicSuffix("www.test.ac.jp", "test.ac.jp")
        checkPublicSuffix("kyoto.jp", null)
        checkPublicSuffix("test.kyoto.jp", "test.kyoto.jp")
        checkPublicSuffix("ide.kyoto.jp", null)
        checkPublicSuffix("b.ide.kyoto.jp", "b.ide.kyoto.jp")
        checkPublicSuffix("a.b.ide.kyoto.jp", "b.ide.kyoto.jp")
        checkPublicSuffix("c.kobe.jp", null)
        checkPublicSuffix("b.c.kobe.jp", "b.c.kobe.jp")
        checkPublicSuffix("a.b.c.kobe.jp", "b.c.kobe.jp")
        checkPublicSuffix("city.kobe.jp", "city.kobe.jp")
        checkPublicSuffix("www.city.kobe.jp", "city.kobe.jp")
        // TLD with a wildcard rule and exceptions.
        checkPublicSuffix("ck", null)
        checkPublicSuffix("test.ck", null)
        checkPublicSuffix("b.test.ck", "b.test.ck")
        checkPublicSuffix("a.b.test.ck", "b.test.ck")
        checkPublicSuffix("www.ck", "www.ck")
        checkPublicSuffix("www.www.ck", "www.ck")
        // US K12.
        checkPublicSuffix("us", null)
        checkPublicSuffix("test.us", "test.us")
        checkPublicSuffix("www.test.us", "test.us")
        checkPublicSuffix("ak.us", null)
        checkPublicSuffix("test.ak.us", "test.ak.us")
        checkPublicSuffix("www.test.ak.us", "test.ak.us")
        checkPublicSuffix("k12.ak.us", null)
        checkPublicSuffix("test.k12.ak.us", "test.k12.ak.us")
        checkPublicSuffix("www.test.k12.ak.us", "test.k12.ak.us")
        // IDN labels.
        checkPublicSuffix("食狮.com.cn", "食狮.com.cn")
        checkPublicSuffix("食狮.公司.cn", "食狮.公司.cn")
        checkPublicSuffix("www.食狮.公司.cn", "食狮.公司.cn")
        checkPublicSuffix("shishi.公司.cn", "shishi.公司.cn")
        checkPublicSuffix("公司.cn", null)
        checkPublicSuffix("食狮.中国", "食狮.中国")
        checkPublicSuffix("www.食狮.中国", "食狮.中国")
        checkPublicSuffix("shishi.中国", "shishi.中国")
        checkPublicSuffix("中国", null)
        // Same as above, but punycoded.
        checkPublicSuffix("xn--85x722f.com.cn", "xn--85x722f.com.cn")
        checkPublicSuffix("xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn")
        checkPublicSuffix("www.xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn")
        checkPublicSuffix("shishi.xn--55qx5d.cn", "shishi.xn--55qx5d.cn")
        checkPublicSuffix("xn--55qx5d.cn", null)
        checkPublicSuffix("xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s")
        checkPublicSuffix("www.xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s")
        checkPublicSuffix("shishi.xn--fiqs8s", "shishi.xn--fiqs8s")
        checkPublicSuffix("xn--fiqs8s", null)
    }

    private fun checkPublicSuffix(
        domain: String,
        registrablePart: String?,
    ) {
        val canonicalDomain = HostnameUtils.toCanonicalHost(domain) ?: return
        val result = publicSuffixDatabase.getEffectiveTldPlusOne(canonicalDomain)
        if (registrablePart == null) {
            assertThat(result).isNull()
        } else {
            assertThat(result).isEqualTo(HostnameUtils.toCanonicalHost(registrablePart))
        }
    }
}
