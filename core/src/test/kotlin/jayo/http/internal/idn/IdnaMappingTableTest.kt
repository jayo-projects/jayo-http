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

package jayo.http.internal.idn

import jayo.http.internal.idn.IdnaMappingTable.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Confirm we get the expected table whether we build it from the .txt file or compact that. */
class IdnaMappingTableTest {
    private val compactTable = IDNA_MAPPING_TABLE

    /** Confirm the compact table satisfies is documented invariants. */
    @Test
    fun validateCompactTableInvariants() {
        // Less than 16,834 bytes, because we binary search on a 14-bit index.
        assertThat(compactTable.sections.length).isLessThan(1 shl 14)

        // Less than 65,536 bytes, because we binary search on a 14-bit index with a stride of 4 bytes.
        assertThat(compactTable.ranges.length).isLessThan((1 shl 14) * 4)

        // Less than 16,384 chars, because we index on a 14-bit index in the ranges table.
        assertThat(compactTable.mappings.length).isLessThan(1 shl 14)

        // Confirm the data strings are ASCII.
        for (dataString in listOf<String>(compactTable.sections, compactTable.ranges)) {
            for (codePoint in dataString.codePoints()) {
                assertThat(codePoint and 0x7f).isEqualTo(codePoint)
            }
        }

        // Confirm the sections are increasing.
        val rangesIndices = mutableListOf<Int>()
        val rangesOffsets = mutableListOf<Int>()
        for (i in 0 until compactTable.sections.length step 4) {
            rangesIndices += read14BitInt(compactTable.sections, i)
            rangesOffsets += read14BitInt(compactTable.sections, i + 2)
        }
        assertThat(rangesIndices).isEqualTo(rangesIndices.sorted())

        // Check the ranges.
        for (r in 0 until rangesOffsets.size) {
            val rangePos = rangesOffsets[r] * 4
            val rangeLimit =
                when {
                    r + 1 < rangesOffsets.size -> rangesOffsets[r + 1] * 4
                    else -> rangesOffsets.size * 4
                }

            // Confirm this range starts with byte 0.
            assertThat(compactTable.ranges[rangePos].code).isEqualTo(0)

            // Confirm this range's index byte is increasing.
            val rangeStarts = mutableListOf<Int>()
            for (i in rangePos until rangeLimit step 4) {
                rangeStarts += compactTable.ranges[i].code
            }
            assertThat(rangeStarts).isEqualTo(rangeStarts.sorted())
        }
    }

    @Test
    fun binarySearchEvenSizedRange() {
        val table = listOf(1, 3, 5, 7, 9, 11)

        // Search for matches.
        assertEquals(0, binarySearch(0, 6) { index -> 1.compareTo(table[index]) })
        assertEquals(1, binarySearch(0, 6) { index -> 3.compareTo(table[index]) })
        assertEquals(2, binarySearch(0, 6) { index -> 5.compareTo(table[index]) })
        assertEquals(3, binarySearch(0, 6) { index -> 7.compareTo(table[index]) })
        assertEquals(4, binarySearch(0, 6) { index -> 9.compareTo(table[index]) })
        assertEquals(5, binarySearch(0, 6) { index -> 11.compareTo(table[index]) })

        // Search for misses.
        assertEquals(-1, binarySearch(0, 6) { index -> 0.compareTo(table[index]) })
        assertEquals(-2, binarySearch(0, 6) { index -> 2.compareTo(table[index]) })
        assertEquals(-3, binarySearch(0, 6) { index -> 4.compareTo(table[index]) })
        assertEquals(-4, binarySearch(0, 6) { index -> 6.compareTo(table[index]) })
        assertEquals(-5, binarySearch(0, 6) { index -> 8.compareTo(table[index]) })
        assertEquals(-6, binarySearch(0, 6) { index -> 10.compareTo(table[index]) })
        assertEquals(-7, binarySearch(0, 6) { index -> 12.compareTo(table[index]) })
    }

    @Test
    fun binarySearchOddSizedRange() {
        val table = listOf(1, 3, 5, 7, 9)

        // Search for matches.
        assertEquals(0, binarySearch(0, 5) { index -> 1.compareTo(table[index]) })
        assertEquals(1, binarySearch(0, 5) { index -> 3.compareTo(table[index]) })
        assertEquals(2, binarySearch(0, 5) { index -> 5.compareTo(table[index]) })
        assertEquals(3, binarySearch(0, 5) { index -> 7.compareTo(table[index]) })
        assertEquals(4, binarySearch(0, 5) { index -> 9.compareTo(table[index]) })

        // Search for misses.
        assertEquals(-1, binarySearch(0, 5) { index -> 0.compareTo(table[index]) })
        assertEquals(-2, binarySearch(0, 5) { index -> 2.compareTo(table[index]) })
        assertEquals(-3, binarySearch(0, 5) { index -> 4.compareTo(table[index]) })
        assertEquals(-4, binarySearch(0, 5) { index -> 6.compareTo(table[index]) })
        assertEquals(-5, binarySearch(0, 5) { index -> 8.compareTo(table[index]) })
        assertEquals(-6, binarySearch(0, 5) { index -> 10.compareTo(table[index]) })
    }

    @Test
    fun binarySearchSingleElementRange() {
        val table = listOf(1)

        // Search for matches.
        assertEquals(0, binarySearch(0, 1) { index -> 1.compareTo(table[index]) })

        // Search for misses.
        assertEquals(-1, binarySearch(0, 1) { index -> 0.compareTo(table[index]) })
        assertEquals(-2, binarySearch(0, 1) { index -> 2.compareTo(table[index]) })
    }

    @Test
    fun binarySearchEmptyRange() {
        assertEquals(-1, binarySearch(0, 0) { error("unexpected call") })
    }
}
