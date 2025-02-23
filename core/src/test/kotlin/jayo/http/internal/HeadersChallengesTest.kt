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
import jayo.http.internal.http.HttpHeaders.parseChallenges
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class HeadersChallengesTest {
    /** See https://github.com/square/okhttp/issues/2780.  */
    @Test
    fun testDigestChallengeWithStrictRfc2617Header() {
        val headers =
            Headers.builder()
                .add(
                    "WWW-Authenticate",
                    "Digest realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks" +
                            "jdflkasdf\", qop=\"auth\", stale=\"FALSE\"",
                )
                .build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("Digest")
        assertThat(challenges[0].realm).isEqualTo("myrealm")
        val expectedAuthParams = mutableMapOf<String, String>()
        expectedAuthParams["realm"] = "myrealm"
        expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
        expectedAuthParams["qop"] = "auth"
        expectedAuthParams["stale"] = "FALSE"
        assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
    }

    @Test
    fun testDigestChallengeWithDifferentlyOrderedAuthParams() {
        val headers =
            Headers.builder()
                .add(
                    "WWW-Authenticate",
                    "Digest qop=\"auth\", realm=\"myrealm\", nonce=\"fjalskdflwejrlask" +
                            "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"",
                )
                .build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("Digest")
        assertThat(challenges[0].realm).isEqualTo("myrealm")
        val expectedAuthParams = mutableMapOf<String, String>()
        expectedAuthParams["realm"] = "myrealm"
        expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
        expectedAuthParams["qop"] = "auth"
        expectedAuthParams["stale"] = "FALSE"
        assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
    }

    @Test
    fun testDigestChallengeWithDifferentlyOrderedAuthParams2() {
        val headers =
            Headers.builder()
                .add(
                    "WWW-Authenticate",
                    "Digest qop=\"auth\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaksjdflk" +
                            "asdf\", realm=\"myrealm\", stale=\"FALSE\"",
                )
                .build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("Digest")
        assertThat(challenges[0].realm).isEqualTo("myrealm")
        val expectedAuthParams = mutableMapOf<String, String>()
        expectedAuthParams["realm"] = "myrealm"
        expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
        expectedAuthParams["qop"] = "auth"
        expectedAuthParams["stale"] = "FALSE"
        assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
    }

    @Test
    fun testDigestChallengeWithMissingRealm() {
        val headers =
            Headers.builder()
                .add(
                    "WWW-Authenticate",
                    "Digest qop=\"auth\", underrealm=\"myrealm\", nonce=\"fjalskdflwej" +
                            "rlaskdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"",
                )
                .build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("Digest")
        assertThat(challenges[0].realm).isNull()
        val expectedAuthParams = mutableMapOf<String, String>()
        expectedAuthParams["underrealm"] = "myrealm"
        expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
        expectedAuthParams["qop"] = "auth"
        expectedAuthParams["stale"] = "FALSE"
        assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
    }

    @Test
    fun testDigestChallengeWithAdditionalSpaces() {
        val headers =
            Headers.builder()
                .add(
                    "WWW-Authenticate",
                    "Digest qop=\"auth\",    realm=\"myrealm\", nonce=\"fjalskdflwejrl" +
                            "askdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"",
                )
                .build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("Digest")
        assertThat(challenges[0].realm).isEqualTo("myrealm")
        val expectedAuthParams = mutableMapOf<String, String>()
        expectedAuthParams["realm"] = "myrealm"
        expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
        expectedAuthParams["qop"] = "auth"
        expectedAuthParams["stale"] = "FALSE"
        assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
    }

    @Test
    fun testDigestChallengeWithAdditionalSpacesBeforeFirstAuthParam() {
        val headers =
            Headers.builder()
                .add(
                    "WWW-Authenticate",
                    "Digest    realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjfl" +
                            "aksjdflkasdf\", qop=\"auth\", stale=\"FALSE\"",
                )
                .build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("Digest")
        assertThat(challenges[0].realm).isEqualTo("myrealm")
        val expectedAuthParams = mutableMapOf<String, String>()
        expectedAuthParams["realm"] = "myrealm"
        expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
        expectedAuthParams["qop"] = "auth"
        expectedAuthParams["stale"] = "FALSE"
        assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
    }

    @Test
    fun testDigestChallengeWithCamelCasedNames() {
        val headers =
            Headers.builder()
                .add(
                    "WWW-Authenticate",
                    "DiGeSt qop=\"auth\", rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlask" +
                            "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"",
                )
                .build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("DiGeSt")
        assertThat(challenges[0].realm).isEqualTo("myrealm")
        val expectedAuthParams = mutableMapOf<String, String>()
        expectedAuthParams["realm"] = "myrealm"
        expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
        expectedAuthParams["qop"] = "auth"
        expectedAuthParams["stale"] = "FALSE"
        assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
    }

    @Test
    fun testDigestChallengeWithCamelCasedNames2() {
        // Strict RFC 2617 camelcased.
        val headers =
            Headers.builder()
                .add(
                    "WWW-Authenticate",
                    "DIgEsT rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks" +
                            "jdflkasdf\", qop=\"auth\", stale=\"FALSE\"",
                )
                .build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("DIgEsT")
        assertThat(challenges[0].realm).isEqualTo("myrealm")
        val expectedAuthParams = mutableMapOf<String, String>()
        expectedAuthParams["realm"] = "myrealm"
        expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
        expectedAuthParams["qop"] = "auth"
        expectedAuthParams["stale"] = "FALSE"
        assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
    }

    @Test
    fun testDigestChallengeWithTokenFormOfAuthParam() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest realm=myrealm").build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("Digest")
        assertThat(challenges[0].realm).isEqualTo("myrealm")
        assertThat(challenges[0].authParams)
            .isEqualTo(mapOf("realm" to "myrealm"))
    }

    @Test
    fun testDigestChallengeWithoutAuthParams() {
        // Scheme only.
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest").build()
        val challenges = parseChallenges(headers, "WWW-Authenticate")
        assertThat(challenges.size).isEqualTo(1)
        assertThat(challenges[0].scheme).isEqualTo("Digest")
        assertThat(challenges[0].realm).isNull()
        assertThat(challenges[0].authParams).isEqualTo(emptyMap<Any, Any>())
    }

    @Test
    fun basicChallenge() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate: Basic realm=\"protected area\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate"))
            .isEqualTo(listOf(RealChallenge("Basic", mapOf("realm" to "protected area"))))
    }

    @Test
    fun basicChallengeWithCharset() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"UTF-8\"")
                .build()
        val expectedAuthParams = mutableMapOf<String?, String>()
        expectedAuthParams["realm"] = "protected area"
        expectedAuthParams["charset"] = "UTF-8"
        assertThat(parseChallenges(headers, "WWW-Authenticate"))
            .isEqualTo(listOf(RealChallenge("Basic", expectedAuthParams)))
    }

    @Test
    fun basicChallengeWithUnexpectedCharset() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"US-ASCII\"")
                .build()
        val expectedAuthParams = mutableMapOf<String?, String>()
        expectedAuthParams["realm"] = "protected area"
        expectedAuthParams["charset"] = "US-ASCII"
        assertThat(parseChallenges(headers, "WWW-Authenticate"))
            .isEqualTo(listOf(RealChallenge("Basic", expectedAuthParams)))
    }

    @Test
    fun separatorsBeforeFirstChallenge() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", " ,  , Basic realm=myrealm")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate"))
            .isEqualTo(listOf(RealChallenge("Basic", mapOf("realm" to "myrealm"))))
    }

    @Test
    fun spacesAroundKeyValueSeparator() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Basic realm = \"myrealm\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate"))
            .isEqualTo(listOf(RealChallenge("Basic", mapOf("realm" to "myrealm"))))
    }

    @Test
    fun multipleChallengesInOneHeader() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Basic realm = \"myrealm\",Digest")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Basic", mapOf("realm" to "myrealm")),
            RealChallenge("Digest", mapOf()),
        )
    }

    @Test
    fun multipleChallengesWithSameSchemeButDifferentRealmInOneHeader() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Basic realm = \"myrealm\",Basic realm=myotherrealm")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Basic", mapOf("realm" to "myrealm")),
            RealChallenge("Basic", mapOf("realm" to "myotherrealm")),
        )
    }

    @Test
    fun separatorsBeforeFirstAuthParam() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest, Basic ,,realm=\"myrealm\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
            RealChallenge("Basic", mapOf("realm" to "myrealm")),
        )
    }

    @Test
    fun onlyCommaBetweenChallenges() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest,Basic realm=\"myrealm\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
            RealChallenge("Basic", mapOf("realm" to "myrealm")),
        )
    }

    @Test
    fun multipleSeparatorsBetweenChallenges() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest,,,, Basic ,,realm=\"myrealm\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
            RealChallenge("Basic", mapOf("realm" to "myrealm")),
        )
    }

    @Test
    fun unknownAuthParams() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest,,,, Basic ,,foo=bar,realm=\"myrealm\"")
                .build()
        val expectedAuthParams = mutableMapOf<String?, String>()
        expectedAuthParams["realm"] = "myrealm"
        expectedAuthParams["foo"] = "bar"
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
            RealChallenge("Basic", expectedAuthParams),
        )
    }

    @Test
    fun escapedCharactersInQuotedString() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\"r\\ealm\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
            RealChallenge("Basic", mapOf("realm" to "my\\\"realm")),
        )
    }

    @Test
    fun commaInQuotedStringAndBeforeFirstChallenge() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", ",Digest,,,, Basic ,,,realm=\"my, realm,\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
            RealChallenge("Basic", mapOf("realm" to "my, realm,")),
        )
    }

    @Test
    fun unescapedDoubleQuoteInQuotedStringWithEvenNumberOfBackslashesInFront() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\\\"r\\ealm\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
        )
    }

    @Test
    fun unescapedDoubleQuoteInQuotedString() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\"realm\"")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
        )
    }

    @Disabled("TODO(jwilson): reject parameters that use invalid characters")
    @Test
    fun doubleQuoteInToken() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=my\"realm")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
        )
    }

    @Test
    fun token68InsteadOfAuthParams() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Other abc==")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate"))
            .isEqualTo(
                listOf(RealChallenge("Other", mapOf(null to "abc=="))),
            )
    }

    @Test
    fun token68AndAuthParams() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Other abc==, realm=myrealm")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Other", mapOf(null to "abc==")),
        )
    }

    @Test
    fun repeatedAuthParamKey() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Other realm=myotherrealm, realm=myrealm")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).isEqualTo(listOf<Any>())
    }

    @Test
    fun multipleAuthenticateHeaders() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Digest")
                .add("WWW-Authenticate", "Basic realm=myrealm")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Digest", mapOf()),
            RealChallenge("Basic", mapOf("realm" to "myrealm")),
        )
    }

    @Test
    fun multipleAuthenticateHeadersInDifferentOrder() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Basic realm=myrealm")
                .add("WWW-Authenticate", "Digest")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Basic", mapOf("realm" to "myrealm")),
            RealChallenge("Digest", mapOf()),
        )
    }

    @Test
    fun multipleBasicAuthenticateHeaders() {
        val headers =
            Headers.builder()
                .add("WWW-Authenticate", "Basic realm=myrealm")
                .add("WWW-Authenticate", "Basic realm=myotherrealm")
                .build()
        assertThat(parseChallenges(headers, "WWW-Authenticate")).containsExactly(
            RealChallenge("Basic", mapOf("realm" to "myrealm")),
            RealChallenge("Basic", mapOf("realm" to "myotherrealm")),
        )
    }
}
