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

import jayo.http.internal.connection.Tags.EmptyTags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TagsTest {
    @Test
    fun emptyTags() {
        val tags = EmptyTags.INSTANCE
        assertThat(tags[String::class.java]).isNull()
    }

    @Test
    fun singleElement() {
        val tags = EmptyTags.INSTANCE.plus(String::class.java, "hello")
        assertThat(tags[String::class.java]).isEqualTo("hello")
    }

    @Test
    fun multipleElements() {
        val tags =
            EmptyTags.INSTANCE
                .plus(String::class.java, "hello")
                .plus(Integer::class.java, 5 as Integer)
        assertThat(tags[String::class.java]).isEqualTo("hello")
        assertThat(tags[Integer::class.java]).isEqualTo(5)
    }

    /** The implementation retains no nodes from the original linked list. */
    @Test
    fun replaceFirstElement() {
        val tags =
            EmptyTags.INSTANCE
                .plus(String::class.java, "a")
                .plus(Integer::class.java, 5 as Integer)
                .plus(Boolean::class.java, true)
                .plus(String::class.java, "b")
        assertThat(tags[String::class.java]).isEqualTo("b")
        assertThat(tags.toString())
            .isEqualTo("{class java.lang.Integer=5, boolean=true, class java.lang.String=b}")
    }

    /** The implementation retains only the first node from the original linked list. */
    @Test
    fun replaceMiddleElement() {
        val tags =
            EmptyTags.INSTANCE
                .plus(Integer::class.java, 5 as Integer)
                .plus(String::class.java, "a")
                .plus(Boolean::class.java, true)
                .plus(String::class.java, "b")
        assertThat(tags[String::class.java]).isEqualTo("b")
        assertThat(tags.toString())
            .isEqualTo("{class java.lang.Integer=5, boolean=true, class java.lang.String=b}")
    }

    /** The implementation retains all but the first node from the original linked list. */
    @Test
    fun replaceLastElement() {
        val tags =
            EmptyTags.INSTANCE
                .plus(Integer::class.java, 5 as Integer)
                .plus(Boolean::class.java, true)
                .plus(String::class.java, "a")
                .plus(String::class.java, "b")
        assertThat(tags[String::class.java]).isEqualTo("b")
        assertThat(tags.toString())
            .isEqualTo("{class java.lang.Integer=5, boolean=true, class java.lang.String=b}")
    }

    /** The implementation retains no nodes from the original linked list. */
    @Test
    fun removeFirstElement() {
        val tags =
            EmptyTags.INSTANCE
                .plus(String::class.java, "a")
                .plus(Integer::class.java, 5 as Integer)
                .plus(Boolean::class.java, true)
                .plus(String::class.java, null)
        assertThat(tags[String::class.java]).isNull()
        assertThat(tags.toString())
            .isEqualTo("{class java.lang.Integer=5, boolean=true}")
    }

    /** The implementation retains only the first node from the original linked list. */
    @Test
    fun removeMiddleElement() {
        val tags =
            EmptyTags.INSTANCE
                .plus(Integer::class.java, 5 as Integer)
                .plus(String::class.java, "a")
                .plus(Boolean::class.java, true)
                .plus(String::class.java, null)
        assertThat(tags[String::class.java]).isNull()
        assertThat(tags.toString())
            .isEqualTo("{class java.lang.Integer=5, boolean=true}")
    }

    /** The implementation retains all but the first node from the original linked list. */
    @Test
    fun removeLastElement() {
        val tags =
            EmptyTags.INSTANCE
                .plus(Integer::class.java, 5 as Integer)
                .plus(Boolean::class.java, true)
                .plus(String::class.java, "a")
                .plus(String::class.java, null)
        assertThat(tags[String::class.java]).isNull()
        assertThat(tags.toString())
            .isEqualTo("{class java.lang.Integer=5, boolean=true}")
    }

    @Test
    fun removeUntilEmpty() {
        val tags =
            EmptyTags.INSTANCE
                .plus(Integer::class.java, 5 as Integer)
                .plus(Boolean::class.java, true)
                .plus(String::class.java, "a")
                .plus(String::class.java, null)
                .plus(Integer::class.java, null)
                .plus(Boolean::class.java, null)
        assertThat(tags).isEqualTo(EmptyTags.INSTANCE)
        assertThat(tags.toString()).isEqualTo("{}")
    }

    @Test
    fun removeAbsentFromEmpty() {
        val tags = EmptyTags.INSTANCE.plus(String::class.java, null)
        assertThat(tags).isEqualTo(EmptyTags.INSTANCE)
        assertThat(tags.toString()).isEqualTo("{}")
    }

    @Test
    fun removeAbsentFromNonEmpty() {
        val tags =
            EmptyTags.INSTANCE
                .plus(String::class.java, "a")
                .plus(Integer::class.java, null)
        assertThat(tags[String::class.java]).isEqualTo("a")
        assertThat(tags.toString()).isEqualTo("{class java.lang.String=a}")
    }
}