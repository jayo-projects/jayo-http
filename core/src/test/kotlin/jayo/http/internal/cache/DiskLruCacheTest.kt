/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2011 The Android Open Source Project
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

package jayo.http.internal.cache

import jayo.RawReader
import jayo.buffered
import jayo.files.File
import jayo.scheduler.internal.TaskFaker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.assertFailsWith
import kotlin.test.fail

@OptIn(ExperimentalPathApi::class)
@Timeout(60)
class DiskLruCacheTest {
    @TempDir
    private lateinit var cacheDir: Path
    private lateinit var journalFile: Path
    private lateinit var journalBkpFile: Path

    private val appVersion = 100
    private val taskFaker = TaskFaker()
    private val taskRunner = taskFaker.taskRunner
    private lateinit var cache: DiskLruCache
    private val toClose = ArrayDeque<DiskLruCache>()

    private fun createNewCache() {
        createNewCacheWithSize(Int.MAX_VALUE)
    }

    private fun createNewCacheWithSize(maxSize: Int) {
        cache =
            DiskLruCache(cacheDir, appVersion, 2, maxSize.toLong(), taskRunner).also {
                toClose.add(it)
            }
        synchronized(cache) { cache.initialize() }
    }

    @BeforeEach
    fun setUp() {
        journalFile = cacheDir.resolve(DiskLruCache.JOURNAL_FILE)
        journalBkpFile = cacheDir.resolve(DiskLruCache.JOURNAL_FILE_BACKUP)
        createNewCache()
    }

    @AfterEach
    fun tearDown() {
        while (!toClose.isEmpty()) {
            toClose.pop().close()
        }
        taskFaker.close()
    }

    @Test
    fun emptyCache() {
        cache.close()
        assertJournalEquals()
    }

    @Test
    fun recoverFromInitializationFailure() {
        // Add an uncommitted entry. This will get detected on initialization, and the cache will attempt to delete the
        // file. Do not explicitly close the cache here, so the entry is left as incomplete.
        val creator = cache.edit("k1")!!
        creator.newRawWriter(0).buffered().use {
            it.write("Hello")
        }

        val snapshot = cache["k1"]
        assertThat(snapshot).isNull()
    }

    @Test
    fun validateKey() {
        var key = ""
        assertFailsWith<IllegalArgumentException> {
            key = "has_space "
            cache.edit(key)
        }.also { expected ->
            assertThat(expected.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        assertFailsWith<IllegalArgumentException> {
            key = "has_CR\r"
            cache.edit(key)
        }.also { expected ->
            assertThat(expected.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        assertFailsWith<IllegalArgumentException> {
            key = "has_LF\n"
            cache.edit(key)
        }.also { expected ->
            assertThat(expected.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        assertFailsWith<IllegalArgumentException> {
            key = "has_invalid/"
            cache.edit(key)
        }.also { expected ->
            assertThat(expected.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        assertFailsWith<IllegalArgumentException> {
            key = "has_invalid\u2603"
            cache.edit(key)
        }.also { expected ->
            assertThat(expected.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        assertFailsWith<IllegalArgumentException> {
            key = (
                    "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long_" +
                            "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long"
                    )
            cache.edit(key)
        }.also { expected ->
            assertThat(expected.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        // Test valid cases.

        // Exactly 120.
        key = (
                "0123456789012345678901234567890123456789012345678901234567890123456789" +
                        "01234567890123456789012345678901234567890123456789"
                )
        cache.edit(key)!!.abort()
        // Contains all valid characters.
        key = "abcdefghijklmnopqrstuvwxyz_0123456789"
        cache.edit(key)!!.abort()
        // Contains dash.
        key = "-20384573948576"
        cache.edit(key)!!.abort()
    }

    @Test
    fun writeAndReadEntry() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "ABC")
        creator.setString(1, "DE")
        assertThat(creator.newRawReader(0)).isNull()
        assertThat(creator.newRawReader(1)).isNull()
        creator.commit()
        val snapshot = cache["k1"]!!
        snapshot.assertValue(0, "ABC")
        snapshot.assertValue(1, "DE")
    }

    @Test
    fun readAndWriteEntryAcrossCacheOpenAndClose() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "A")
        creator.setString(1, "B")
        creator.commit()
        cache.close()
        createNewCache()
        val snapshot = cache["k1"]!!
        snapshot.assertValue(0, "A")
        snapshot.assertValue(1, "B")
        snapshot.close()
    }

    @Test
    fun readAndWriteEntryWithoutProperClose() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "A")
        creator.setString(1, "B")
        creator.commit()

        // Simulate a dirty close of 'cache' by opening the cache directory again.
        createNewCache()
        cache["k1"]!!.use {
            it.assertValue(0, "A")
            it.assertValue(1, "B")
        }
    }

    @Test
    fun journalWithEditAndPublish() {
        val creator = cache.edit("k1")!!
        assertJournalEquals("DIRTY k1") // DIRTY must always be flushed.
        creator.setString(0, "AB")
        creator.setString(1, "C")
        creator.commit()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 2 1")
    }

    @Test
    fun revertedNewFileIsRemoveInJournal() {
        val creator = cache.edit("k1")!!
        assertJournalEquals("DIRTY k1") // DIRTY must always be flushed.
        creator.setString(0, "AB")
        creator.setString(1, "C")
        creator.abort()
        cache.close()
        assertJournalEquals("DIRTY k1", "REMOVE k1")
    }

    @Test
    fun `unterminated edit is reverted on cache close`() {
        val editor = cache.edit("k1")!!
        editor.setString(0, "AB")
        editor.setString(1, "C")
        cache.close()
        val expected = arrayOf("DIRTY k1", "REMOVE k1")
        assertJournalEquals(*expected)
        editor.commit()
        assertJournalEquals(*expected) // 'REMOVE k1' not written because journal is closed.
    }

    @Test
    fun journalDoesNotIncludeReadOfYetUnpublishedValue() {
        val creator = cache.edit("k1")!!
        assertThat(cache["k1"]).isNull()
        creator.setString(0, "A")
        creator.setString(1, "BC")
        creator.commit()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 1 2")
    }

    @Test
    fun journalWithEditAndPublishAndRead() {
        val k1Creator = cache.edit("k1")!!
        k1Creator.setString(0, "AB")
        k1Creator.setString(1, "C")
        k1Creator.commit()
        val k2Creator = cache.edit("k2")!!
        k2Creator.setString(0, "DEF")
        k2Creator.setString(1, "G")
        k2Creator.commit()
        val k1Snapshot = cache["k1"]!!
        k1Snapshot.close()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 2 1", "DIRTY k2", "CLEAN k2 3 1", "READ k1")
    }

    @Test
    fun cannotOperateOnEditAfterPublish() {
        val editor = cache.edit("k1")!!
        editor.setString(0, "A")
        editor.setString(1, "B")
        editor.commit()
        editor.assertInoperable()
    }

    @Test
    fun cannotOperateOnEditAfterRevert() {
        val editor = cache.edit("k1")!!
        editor.setString(0, "A")
        editor.setString(1, "B")
        editor.abort()
        editor.assertInoperable()
    }

    @Test
    fun explicitRemoveAppliedToDiskImmediately() {
        val editor = cache.edit("k1")!!
        editor.setString(0, "ABC")
        editor.setString(1, "B")
        editor.commit()
        val k1 = getCleanFile("k1", 0)
        assertThat(readFile(k1)).isEqualTo("ABC")
        cache.remove("k1")
        assertThat(Files.exists(k1)).isFalse()
    }

    @Test
    fun removePreventsActiveEditFromStoringAValue() {
        set("a", "a", "a")
        val a = cache.edit("a")!!
        a.setString(0, "a1")
        cache.remove("a")
        a.setString(1, "a2")
        a.commit()
        assertAbsent("a")
    }

    /**
     * Each read sees a snapshot of the file at the time read was called. This means that two reads of the same key can
     * see different data.
     */
    @Test
    fun readAndWriteOverlapsMaintainConsistency() {
        val v1Creator = cache.edit("k1")!!
        v1Creator.setString(0, "AAaa")
        v1Creator.setString(1, "BBbb")
        v1Creator.commit()

        cache["k1"]!!.use { snapshot1 ->
            val inV1 = snapshot1.getRawReader(0).buffered()
            assertThat(inV1.readByte()).isEqualTo('A'.code.toByte())
            assertThat(inV1.readByte()).isEqualTo('A'.code.toByte())

            val v1Updater = cache.edit("k1")!!
            v1Updater.setString(0, "CCcc")
            v1Updater.setString(1, "DDdd")
            v1Updater.commit()

            cache["k1"]!!.use { snapshot2 ->
                snapshot2.assertValue(0, "CCcc")
                snapshot2.assertValue(1, "DDdd")
            }

            assertThat(inV1.readByte()).isEqualTo('a'.code.toByte())
            assertThat(inV1.readByte()).isEqualTo('a'.code.toByte())
            snapshot1.assertValue(1, "BBbb")
        }
    }

    @Test
    fun openWithDirtyKeyDeletesAllFilesForThatKey() {
        cache.close()
        val cleanFile0 = getCleanFile("k1", 0)
        val cleanFile1 = getCleanFile("k1", 1)
        val dirtyFile0 = getDirtyFile("k1", 0)
        val dirtyFile1 = getDirtyFile("k1", 1)
        writeFile(cleanFile0, "A")
        writeFile(cleanFile1, "B")
        writeFile(dirtyFile0, "C")
        writeFile(dirtyFile1, "D")
        createJournal("CLEAN k1 1 1", "DIRTY k1")
        createNewCache()
        assertThat(Files.exists(cleanFile0)).isFalse()
        assertThat(Files.exists(cleanFile1)).isFalse()
        assertThat(Files.exists(dirtyFile0)).isFalse()
        assertThat(Files.exists(dirtyFile1)).isFalse()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithInvalidVersionClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "0", "100", "2", "")
        createNewCache()
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidAppVersionClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "101", "2", "")
        createNewCache()
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidValueCountClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "100", "1", "")
        createNewCache()
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidBlankLineClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "100", "2", "x")
        createNewCache()
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidJournalLineClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 1 1", "BOGUS")
        createNewCache()
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithInvalidFileSizeClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 0000x001 1")
        createNewCache()
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithTruncatedLineDiscardsThatLine() {
        cache.close()
        writeFile(getCleanFile("k1", 0), "A")
        writeFile(getCleanFile("k1", 1), "B")
        File.createIfNotExists(journalFile).writer().buffered().use { writer ->
            writer.write(
                // No trailing newline.
                """
        |${DiskLruCache.MAGIC}
        |${DiskLruCache.VERSION_1}
        |100
        |2
        |
        |CLEAN k1 1 1
        """.trimMargin(),
            )
        }
        createNewCache()
        assertThat(cache["k1"]).isNull()

        // The journal is not corrupt when editing after a truncated line.
        set("k1", "C", "D")
        cache.close()
        createNewCache()
        assertValue("k1", "C", "D")
    }

    @Test
    fun openWithTooManyFileSizesClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 1 1 1")
        createNewCache()
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun keyWithSpaceNotPermitted() {
        assertFailsWith<IllegalArgumentException> {
            cache.edit("my key")
        }
    }

    @Test
    fun keyWithNewlineNotPermitted() {
        assertFailsWith<IllegalArgumentException> {
            cache.edit("my\nkey")
        }
    }

    @Test
    fun keyWithCarriageReturnNotPermitted() {
        assertFailsWith<IllegalArgumentException> {
            cache.edit("my\rkey")
        }
    }

    @Test
    fun createNewEntryWithTooFewValuesFails() {
        val creator = cache.edit("k1")!!
        creator.setString(1, "A")
        assertFailsWith<IllegalStateException> {
            creator.commit()
        }
        assertThat(Files.exists(getCleanFile("k1", 0))).isFalse()
        assertThat(Files.exists(getCleanFile("k1", 1))).isFalse()
        assertThat(Files.exists(getDirtyFile("k1", 0))).isFalse()
        assertThat(Files.exists(getDirtyFile("k1", 1))).isFalse()
        assertThat(cache["k1"]).isNull()
        val creator2 = cache.edit("k1")!!
        creator2.setString(0, "B")
        creator2.setString(1, "C")
        creator2.commit()
    }

    @Test
    fun revertWithTooFewValues() {
        val creator = cache.edit("k1")!!
        creator.setString(1, "A")
        creator.abort()
        assertThat(Files.exists(getCleanFile("k1", 0))).isFalse()
        assertThat(Files.exists(getCleanFile("k1", 1))).isFalse()
        assertThat(Files.exists(getDirtyFile("k1", 0))).isFalse()
        assertThat(Files.exists(getDirtyFile("k1", 1))).isFalse()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun updateExistingEntryWithTooFewValuesReusesPreviousValues() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "A")
        creator.setString(1, "B")
        creator.commit()
        val updater = cache.edit("k1")!!
        updater.setString(0, "C")
        updater.commit()
        val snapshot = cache["k1"]!!
        snapshot.assertValue(0, "C")
        snapshot.assertValue(1, "B")
        snapshot.close()
    }

    @Test
    fun growMaxSize() {
        cache.close()
        createNewCacheWithSize(10)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        cache.maxByteSize = 20
        set("c", "c", "c") // size 12
        assertThat(cache.byteSize()).isEqualTo(12)
    }

    @Test
    fun shrinkMaxSizeEvicts() {
        cache.close()
        createNewCacheWithSize(20)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        set("c", "c", "c") // size 12
        cache.maxByteSize = 10
        assertThat(taskFaker.isIdle()).isFalse()
    }

    @Test
    fun evictOnInsert() {
        cache.close()
        createNewCacheWithSize(10)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        assertThat(cache.byteSize()).isEqualTo(10)

        // Cause the size to grow to 12 should evict 'A'.
        set("c", "c", "c")
        cache.flush()
        assertThat(cache.byteSize()).isEqualTo(8)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")

        // Causing the size to grow to 10 should evict nothing.
        set("d", "d", "d")
        cache.flush()
        assertThat(cache.byteSize()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")
        assertValue("d", "d", "d")

        // Causing the size to grow to 18 should evict 'B' and 'C'.
        set("e", "eeee", "eeee")
        cache.flush()
        assertThat(cache.byteSize()).isEqualTo(10)
        assertAbsent("a")
        assertAbsent("b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "eeee", "eeee")
    }

    @Test
    fun evictOnUpdate() {
        cache.close()
        createNewCacheWithSize(10)
        set("a", "a", "aa") // size 3
        set("b", "b", "bb") // size 3
        set("c", "c", "cc") // size 3
        assertThat(cache.byteSize()).isEqualTo(9)

        // Causing the size to grow to 11 should evict 'A'.
        set("b", "b", "bbbb")
        cache.flush()
        assertThat(cache.byteSize()).isEqualTo(8)
        assertAbsent("a")
        assertValue("b", "b", "bbbb")
        assertValue("c", "c", "cc")
    }

    @Test
    fun evictionHonorsLruFromCurrentSession() {
        cache.close()
        createNewCacheWithSize(10)
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        cache["b"]!!.close() // 'B' is now least recently used.

        // Causing the size to grow to 12 should evict 'A'.
        set("f", "f", "f")
        // Causing the size to grow to 12 should evict 'C'.
        set("g", "g", "g")
        cache.flush()
        assertThat(cache.byteSize()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
    }

    @Test
    fun evictionHonorsLruFromPreviousSession() {
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        set("f", "f", "f")
        cache["b"]!!.close() // 'B' is now least recently used.
        assertThat(cache.byteSize()).isEqualTo(12)
        cache.close()
        createNewCacheWithSize(10)
        set("g", "g", "g")
        cache.flush()
        assertThat(cache.byteSize()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
        assertValue("g", "g", "g")
    }

    @Test
    fun cacheSingleEntryOfSizeGreaterThanMaxSize() {
        cache.close()
        createNewCacheWithSize(10)
        set("a", "aaaaa", "aaaaaa") // size=11
        cache.flush()
        assertAbsent("a")
    }

    @Test
    fun cacheSingleValueOfSizeGreaterThanMaxSize() {
        cache.close()
        createNewCacheWithSize(10)
        set("a", "aaaaaaaaaaa", "a") // size=12
        cache.flush()
        assertAbsent("a")
    }

    @Test
    fun constructorDoesNotAllowZeroCacheSize() {
        assertFailsWith<IllegalArgumentException> {
            DiskLruCache(cacheDir, appVersion, 2, 0, taskRunner)
        }
    }

    @Test
    fun constructorDoesNotAllowZeroValuesPerEntry() {
        assertFailsWith<IllegalArgumentException> {
            DiskLruCache(cacheDir, appVersion, 0, 10, taskRunner)
        }
    }

    @Test
    fun removeAbsentElement() {
        cache.remove("a")
    }

    @Test
    fun readingTheSameStreamMultipleTimes() {
        set("a", "a", "b")
        val snapshot = cache["a"]!!
        assertThat(snapshot.getRawReader(0)).isSameAs(snapshot.getRawReader(0))
        snapshot.close()
    }

    @Test
    fun rebuildJournalOnRepeatedReads() {
        set("a", "a", "a")
        set("b", "b", "b")
        while (taskFaker.isIdle()) {
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
        }
    }

    @Test
    fun rebuildJournalOnRepeatedEdits() {
        while (taskFaker.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }
        taskFaker.runNextTask()

        // Check that a rebuilt journal behaves normally.
        assertValue("a", "a", "a")
        assertValue("b", "b", "b")
    }

    /** @see [Issue 28](https://github.com/JakeWharton/DiskLruCache/issues/28) */
    @Test
    fun rebuildJournalOnRepeatedReadsWithOpenAndClose() {
        set("a", "a", "a")
        set("b", "b", "b")
        while (taskFaker.isIdle()) {
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
            cache.close()
            createNewCache()
        }
    }

    /** @see [Issue 28](https://github.com/JakeWharton/DiskLruCache/issues/28) */
    @Test
    fun rebuildJournalOnRepeatedEditsWithOpenAndClose() {
        while (taskFaker.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
            cache.close()
            createNewCache()
        }
    }

    @Test
    fun restoreBackupFile() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "ABC")
        creator.setString(1, "DE")
        creator.commit()
        cache.close()
        File.open(journalFile).atomicMove(journalBkpFile)
        assertThat(Files.exists(journalFile)).isFalse()
        createNewCache()
        val snapshot = cache["k1"]!!
        snapshot.assertValue(0, "ABC")
        snapshot.assertValue(1, "DE")
        assertThat(Files.exists(journalBkpFile)).isFalse()
        assertThat(Files.exists(journalFile)).isTrue()
    }

    @Test
    fun journalFileIsPreferredOverBackupFile() {
        var creator = cache.edit("k1")!!
        creator.setString(0, "ABC")
        creator.setString(1, "DE")
        creator.commit()
        cache.flush()
        File.open(journalFile).copy(journalBkpFile)
        creator = cache.edit("k2")!!
        creator.setString(0, "F")
        creator.setString(1, "GH")
        creator.commit()
        cache.close()
        assertThat(Files.exists(journalFile)).isTrue()
        assertThat(Files.exists(journalBkpFile)).isTrue()
        createNewCache()
        val snapshotA = cache["k1"]!!
        snapshotA.assertValue(0, "ABC")
        snapshotA.assertValue(1, "DE")
        val snapshotB = cache["k2"]!!
        snapshotB.assertValue(0, "F")
        snapshotB.assertValue(1, "GH")
        assertThat(Files.exists(journalBkpFile)).isFalse()
        assertThat(Files.exists(journalFile)).isTrue()
    }

    @Test
    fun openCreatesDirectoryIfNecessary() {
        cache.close()
        val dir = (cacheDir.resolve("testOpenCreatesDirectoryIfNecessary")).also { Files.createDirectories(it) }
        cache =
            DiskLruCache(dir, appVersion, 2, Int.MAX_VALUE.toLong(), taskRunner).also {
                toClose.add(it)
            }
        set("a", "a", "a")
        assertThat(Files.exists(dir.resolve("a.0"))).isTrue()
        assertThat(Files.exists(dir.resolve("a.1"))).isTrue()
        assertThat(Files.exists(dir.resolve("journal"))).isTrue()
    }

    @Test
    fun fileDeletedExternally() {
        set("a", "a", "a")
        File.open(getCleanFile("a", 1)).delete()
        assertThat(cache["a"]).isNull()
        assertThat(cache.byteSize()).isEqualTo(0)
    }

    @Test
    fun editSameVersion() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        snapshot.close()
        val editor = snapshot.edit()!!
        editor.setString(1, "a2")
        editor.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    fun editSnapshotAfterChangeAborted() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        snapshot.close()
        val toAbort = snapshot.edit()!!
        toAbort.setString(0, "b")
        toAbort.abort()
        val editor = snapshot.edit()!!
        editor.setString(1, "a2")
        editor.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    fun editSnapshotAfterChangeCommitted() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        snapshot.close()
        val toAbort = snapshot.edit()!!
        toAbort.setString(0, "b")
        toAbort.commit()
        assertThat(snapshot.edit()).isNull()
    }

    @Test
    fun editSinceEvicted() {
        cache.close()
        createNewCacheWithSize(10)
        set("a", "aa", "aaa") // size 5
        cache["a"]!!.use {
            set("b", "bb", "bbb") // size 5
            set("c", "cc", "ccc") // size 5; will evict 'A'
            cache.flush()
            assertThat(it.edit()).isNull()
        }
    }

    @Test
    fun editSinceEvictedAndRecreated() {
        cache.close()
        createNewCacheWithSize(10)
        set("a", "aa", "aaa") // size 5
        val snapshot = cache["a"]!!
        snapshot.close()
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        set("a", "a", "aaaa") // size 5; will evict 'B'
        cache.flush()
        assertThat(snapshot.edit()).isNull()
    }

    /** @see [Issue 2](https://github.com/JakeWharton/DiskLruCache/issues/2) */
    @Test
    fun aggressiveClearingHandlesWrite() {
        cacheDir.deleteRecursively()
        set("a", "a", "a")
        assertValue("a", "a", "a")
    }

    /** @see [Issue 2](https://github.com/JakeWharton/DiskLruCache/issues/2) */
    @Test
    fun aggressiveClearingHandlesEdit() {
        set("a", "a", "a")
        val a = cache.edit("a")!!
        cacheDir.deleteRecursively()
        a.setString(1, "a2")
        a.commit()
    }

    @Test
    fun removeHandlesMissingFile() {
        set("a", "a", "a")
        File.open(getCleanFile("a", 0)).delete()
        cache.remove("a")
    }

    /** @see [Issue 2](https://github.com/JakeWharton/DiskLruCache/issues/2) */
    @Test
    fun aggressiveClearingHandlesPartialEdit() {
        set("a", "a", "a")
        set("b", "b", "b")
        val a = cache.edit("a")!!
        a.setString(0, "a1")
        cacheDir.deleteRecursively()
        a.setString(1, "a2")
        a.commit()
        assertThat(cache["a"]).isNull()
    }

    /** @see [Issue 2](https://github.com/JakeWharton/DiskLruCache/issues/2) */
    @Test
    fun aggressiveClearingHandlesRead() {
        cacheDir.deleteRecursively()
        assertThat(cache["a"]).isNull()
    }

    /**
     * We had a long-lived bug where [DiskLruCache.trimToSize] could infinite loop if entries
     * being edited required deletion for the operation to complete.
     */
    @Test
    fun trimToSizeWithActiveEdit() {
        val expectedByteCount = 0L
        val afterRemoveFileContents = null

        set("a", "a1234", "a1234")
        val a = cache.edit("a")!!
        a.setString(0, "a123")
        cache.maxByteSize = 8 // Smaller than the sum of active edits!
        cache.flush() // Force trimToSize().
        assertThat(cache.byteSize()).isEqualTo(expectedByteCount)
        assertThat(readFileOrNull(getCleanFile("a", 0))).isEqualTo(afterRemoveFileContents)
        assertThat(readFileOrNull(getCleanFile("a", 1))).isEqualTo(afterRemoveFileContents)

        // After the edit is completed, its entry is still gone.
        a.setString(1, "a1")
        a.commit()
        assertAbsent("a")
        assertThat(cache.byteSize()).isEqualTo(0)
    }

    @Test
    fun evictAll() {
        set("a", "a", "a")
        set("b", "b", "b")
        cache.evictAll()
        assertThat(cache.byteSize()).isEqualTo(0)
        assertAbsent("a")
        assertAbsent("b")
    }

    @Test
    fun evictAllWithPartialCreate() {
        val a = cache.edit("a")!!
        a.setString(0, "a1")
        a.setString(1, "a2")
        cache.evictAll()
        assertThat(cache.byteSize()).isEqualTo(0)
        a.commit()
        assertAbsent("a")
    }

    @Test
    fun evictAllWithPartialEditDoesNotStoreAValue() {
        val expectedByteCount = 0L

        set("a", "a", "a")
        val a = cache.edit("a")!!
        a.setString(0, "a1")
        a.setString(1, "a2")
        cache.evictAll()
        assertThat(cache.byteSize()).isEqualTo(expectedByteCount)
        a.commit()
        assertAbsent("a")
    }

    @Test
    fun evictAllDoesntInterruptPartialRead() {
        val expectedByteCount = 0L
        val afterRemoveFileContents = null

        set("a", "a", "a")
        cache["a"]!!.use {
            it.assertValue(0, "a")
            cache.evictAll()
            assertThat(cache.byteSize()).isEqualTo(expectedByteCount)
            assertThat(readFileOrNull(getCleanFile("a", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getCleanFile("a", 1))).isEqualTo(afterRemoveFileContents)
            it.assertValue(1, "a")
        }
        assertThat(cache.byteSize()).isEqualTo(0L)
    }

    @Test
    fun editSnapshotAfterEvictAllReturnsNullDueToStaleValue() {
        val expectedByteCount = 0L
        val afterRemoveFileContents = null

        set("a", "a", "a")
        cache["a"]!!.use {
            cache.evictAll()
            assertThat(cache.byteSize()).isEqualTo(expectedByteCount)
            assertThat(readFileOrNull(getCleanFile("a", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getCleanFile("a", 1))).isEqualTo(afterRemoveFileContents)
            assertThat(it.edit()).isNull()
        }
        assertThat(cache.byteSize()).isEqualTo(0L)
    }

    @Test
    fun iterator() {
        set("a", "a1", "a2")
        set("b", "b1", "b2")
        set("c", "c1", "c2")
        val iterator = cache.snapshots()
        assertThat(iterator.hasNext()).isTrue()
        iterator.next().use {
            assertThat(it.key).isEqualTo("a")
            it.assertValue(0, "a1")
            it.assertValue(1, "a2")
        }
        assertThat(iterator.hasNext()).isTrue()
        iterator.next().use {
            assertThat(it.key).isEqualTo("b")
            it.assertValue(0, "b1")
            it.assertValue(1, "b2")
        }
        assertThat(iterator.hasNext()).isTrue()
        iterator.next().use {
            assertThat(it.key).isEqualTo("c")
            it.assertValue(0, "c1")
            it.assertValue(1, "c2")
        }
        assertThat(iterator.hasNext()).isFalse()
        assertFailsWith<NoSuchElementException> {
            iterator.next()
        }
    }

    @Test
    fun iteratorElementsAddedDuringIterationAreOmitted() {
        set("a", "a1", "a2")
        set("b", "b1", "b2")
        val iterator = cache.snapshots()
        iterator.next().use { a ->
            assertThat(a.key).isEqualTo("a")
        }
        set("c", "c1", "c2")
        iterator.next().use { b ->
            assertThat(b.key).isEqualTo("b")
        }
        assertThat(iterator.hasNext()).isFalse()
    }

    @Test
    fun iteratorElementsUpdatedDuringIterationAreUpdated() {
        set("a", "a1", "a2")
        set("b", "b1", "b2")
        val iterator = cache.snapshots()
        iterator.next().use {
            assertThat(it.key).isEqualTo("a")
        }
        set("b", "b3", "b4")
        iterator.next().use {
            assertThat(it.key).isEqualTo("b")
            it.assertValue(0, "b3")
            it.assertValue(1, "b4")
        }
    }

    @Test
    fun iteratorElementsRemovedDuringIterationAreOmitted() {
        set("a", "a1", "a2")
        set("b", "b1", "b2")
        val iterator = cache.snapshots()
        cache.remove("b")
        iterator.next().use {
            assertThat(it.key).isEqualTo("a")
        }
        assertThat(iterator.hasNext()).isFalse()
    }

    @Test
    fun iteratorRemove() {
        set("a", "a1", "a2")
        val iterator = cache.snapshots()
        val a = iterator.next()
        a.close()
        iterator.remove()
        assertThat(cache["a"]).isNull()
    }

    @Test
    fun iteratorRemoveBeforeNext() {
        set("a", "a1", "a2")
        val iterator = cache.snapshots()
        assertFailsWith<IllegalStateException> {
            iterator.remove()
        }
    }

    @Test
    fun iteratorRemoveOncePerCallToNext() {
        set("a", "a1", "a2")
        val iterator = cache.snapshots()
        iterator.next().use {
            iterator.remove()
        }
        assertFailsWith<IllegalStateException> {
            iterator.remove()
        }
    }

    @Test
    fun cacheClosedTruncatesIterator() {
        set("a", "a1", "a2")
        val iterator = cache.snapshots()
        cache.close()
        assertThat(iterator.hasNext()).isFalse()
    }

    @Test
    fun isClosed_uninitializedCache() {
        // Create an uninitialized cache.
        cache =
            DiskLruCache(cacheDir, appVersion, 2, Int.MAX_VALUE.toLong(), taskRunner).also {
                toClose.add(it)
            }
        assertThat(cache.isClosed()).isFalse()
        cache.close()
        assertThat(cache.isClosed()).isTrue()
    }

    @Test
    fun noSizeCorruptionAfterCreatorDetached() {
        // Create an editor for k1. Detach it by clearing the cache.
        val editor = cache.edit("k1")!!
        editor.setString(0, "a")
        editor.setString(1, "a")
        cache.evictAll()

        // Create a new value in its place.
        set("k1", "bb", "bb")
        assertThat(cache.byteSize()).isEqualTo(4)

        // Committing the detached editor should not change the cache's size.
        editor.commit()
        assertThat(cache.byteSize()).isEqualTo(4)
        assertValue("k1", "bb", "bb")
    }

    @Test
    fun noSizeCorruptionAfterEditorDetached() {
        set("k1", "a", "a")

        // Create an editor for k1. Detach it by clearing the cache.
        val editor = cache.edit("k1")!!
        editor.setString(0, "bb")
        editor.setString(1, "bb")
        cache.evictAll()

        // Create a new value in its place.
        set("k1", "ccc", "ccc")
        assertThat(cache.byteSize()).isEqualTo(6)

        // Committing the detached editor should not change the cache's size.
        editor.commit()
        assertThat(cache.byteSize()).isEqualTo(6)
        assertValue("k1", "ccc", "ccc")
    }

    @Test
    fun noNewSourceAfterEditorDetached() {
        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        cache.evictAll()
        assertThat(editor.newRawReader(0)).isNull()
    }

    @Test
    fun `edit discarded after editor detached`() {
        set("k1", "a", "a")

        // Create an editor, then detach it.
        val editor = cache.edit("k1")!!
        editor.newRawWriter(0).buffered().use { sink ->
            cache.evictAll()

            // Complete the original edit. It goes into a black hole.
            sink.write("bb")
        }
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun `edit discarded after editor detached with concurrent write`() {
        set("k1", "a", "a")

        // Create an editor, then detach it.
        val editor = cache.edit("k1")!!
        editor.newRawWriter(0).buffered().use { sink ->
            cache.evictAll()

            // Create another value in its place.
            set("k1", "ccc", "ccc")

            // Complete the original edit. It goes into a black hole.
            sink.write("bb")
        }
        assertValue("k1", "ccc", "ccc")
    }

    @Test
    fun abortAfterDetach() {
        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        cache.evictAll()
        editor.abort()
        assertThat(cache.byteSize()).isEqualTo(0)
        assertAbsent("k1")
    }

    @Test
    fun dontRemoveUnfinishedEntryWhenCreatingSnapshot() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "ABC")
        creator.setString(1, "DE")
        assertThat(creator.newRawReader(0)).isNull()
        assertThat(creator.newRawReader(1)).isNull()
        val snapshotWhileEditing = cache.snapshots()
        assertThat(snapshotWhileEditing.hasNext()).isFalse() // entry still is being created/edited
        creator.commit()
        val snapshotAfterCommit = cache.snapshots()
        assertThat(snapshotAfterCommit.hasNext()).isTrue()
        snapshotAfterCommit.next().close()
    }

    @Test
    fun `can read while reading`() {
        set("k1", "a", "a")
        cache["k1"]!!.use { snapshot1 ->
            snapshot1.assertValue(0, "a")
            cache["k1"]!!.use { snapshot2 ->
                snapshot2.assertValue(0, "a")
                snapshot1.assertValue(1, "a")
                snapshot2.assertValue(1, "a")
            }
        }
    }

    @Test
    fun `remove while reading creates zombie that is removed when read finishes`() {
        val afterRemoveFileContents = null

        set("k1", "a", "a")
        cache["k1"]!!.use { snapshot1 ->
            cache.remove("k1")

            // On Windows files still exist with open with 2 open sources.
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()

            // On Windows files still exist with open with 1 open source.
            snapshot1.assertValue(0, "a")
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()

            // On all platforms files are deleted when all sources are closed.
            snapshot1.assertValue(1, "a")
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
        }
    }

    @Test
    fun `remove while writing creates zombie that is removed when write finishes`() {
        val afterRemoveFileContents = null

        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        cache.remove("k1")
        assertThat(cache["k1"]).isNull()

        // On Windows files still exist while being edited.
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveFileContents)
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()

        // On all platforms files are deleted when the edit completes.
        editor.commit()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
    }

    @Test
    fun `cannot read zombie entry`() {
        set("k1", "a", "a")
        cache["k1"]!!.use {
            cache.remove("k1")
            assertThat(cache["k1"]).isNull()
        }
    }

    @Test
    fun `removed entry absent when iterating`() {
        set("k1", "a", "a")
        cache["k1"]!!.use {
            cache.remove("k1")
            val snapshots = cache.snapshots()
            assertThat(snapshots.hasNext()).isFalse()
        }
    }

    @Test
    fun `close with zombie read`() {
        val afterRemoveFileContents = null

        set("k1", "a", "a")
        cache["k1"]!!.use {
            cache.remove("k1")

            // After we close the cache the files continue to exist!
            cache.close()
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()

            // But they disappear when the sources are closed.
            it.assertValue(0, "a")
            it.assertValue(1, "a")
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
        }
    }

    @Test
    fun `close with zombie write`() {
        val afterRemoveCleanFileContents = null
        val afterRemoveDirtyFileContents = null

        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        val sink0 = editor.newRawWriter(0)
        cache.remove("k1")

        // After we close the cache the files continue to exist!
        cache.close()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveCleanFileContents)
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isEqualTo(afterRemoveDirtyFileContents)

        // But they disappear when the edit completes.
        sink0.close()
        editor.commit()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
    }

    @Test
    fun `close with completed zombie write`() {
        val afterRemoveCleanFileContents = null
        val afterRemoveDirtyFileContents = null

        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        editor.setString(0, "b")
        cache.remove("k1")

        // After we close the cache the files continue to exist!
        cache.close()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveCleanFileContents)
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isEqualTo(afterRemoveDirtyFileContents)

        // But they disappear when the edit completes.
        editor.commit()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
    }

    private fun assertJournalEquals(vararg expectedBodyLines: String) {
        assertThat(readJournalLines()).isEqualTo(
            listOf(DiskLruCache.MAGIC, DiskLruCache.VERSION_1, "100", "2", "") + expectedBodyLines,
        )
    }

    private fun createJournal(vararg bodyLines: String) {
        createJournalWithHeader(
            DiskLruCache.MAGIC,
            DiskLruCache.VERSION_1,
            "100",
            "2",
            "",
            *bodyLines,
        )
    }

    private fun createJournalWithHeader(
        magic: String,
        version: String,
        appVersion: String,
        valueCount: String,
        blank: String,
        vararg bodyLines: String,
    ) {
        File.createIfNotExists(journalFile).writer().buffered().use { writer ->
            writer.write(
                """
        |$magic
        |$version
        |$appVersion
        |$valueCount
        |$blank
        |
        """.trimMargin(),
            )
            for (line in bodyLines) {
                writer.write(line)
                writer.write("\n")
            }
        }
    }

    private fun readJournalLines(): List<String> {
        val result = mutableListOf<String>()
        File.open(journalFile).reader().buffered().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                result.add(line)
            }
        }
        return result
    }

    private fun getCleanFile(
        key: String,
        index: Int,
    ) = cacheDir.resolve("$key.$index")

    private fun getDirtyFile(
        key: String,
        index: Int,
    ) = cacheDir.resolve("$key.$index.tmp")

    private fun readFile(file: Path): String =
        File.open(file).reader().buffered().use { reader ->
            reader.readString()
        }

    private fun readFileOrNull(file: Path): String? = if (File.exists(file)) readFile(file) else null

    fun writeFile(
        file: Path,
        content: String,
    ) {
        file.parent?.let {
            Files.createDirectories(it)
        }
        File.createIfNotExists(file).writer().buffered().use { writer ->
            writer.write(content)
        }
    }

    private fun generateSomeGarbageFiles() {
        val dir1 = cacheDir.resolve("dir1")
        val dir2 = dir1.resolve("dir2")
        writeFile(getCleanFile("g1", 0), "A")
        writeFile(getCleanFile("g1", 1), "B")
        writeFile(getCleanFile("g2", 0), "C")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(cacheDir.resolve("otherFile0"), "E")
        writeFile(dir2.resolve("otherFile1"), "F")
    }

    private fun assertGarbageFilesAllDeleted() {
        assertThat(Files.exists(getCleanFile("g1", 0))).isFalse()
        assertThat(Files.exists(getCleanFile("g1", 1))).isFalse()
        assertThat(Files.exists(getCleanFile("g2", 0))).isFalse()
        assertThat(Files.exists(getCleanFile("g2", 1))).isFalse()
        assertThat(Files.exists(cacheDir.resolve("otherFile0"))).isFalse()
        assertThat(Files.exists(cacheDir.resolve("dir1"))).isFalse()
    }

    private operator fun set(
        key: String,
        value0: String,
        value1: String,
    ) {
        val editor = cache.edit(key)!!
        editor.setString(0, value0)
        editor.setString(1, value1)
        editor.commit()
    }

    private fun assertAbsent(key: String) {
        val snapshot = cache[key]
        if (snapshot != null) {
            snapshot.close()
          fail("")
        }
        assertThat(Files.exists(getCleanFile(key, 0))).isFalse()
        assertThat(Files.exists(getCleanFile(key, 1))).isFalse()
        assertThat(Files.exists(getDirtyFile(key, 0))).isFalse()
        assertThat(Files.exists(getDirtyFile(key, 1))).isFalse()
    }

    private fun assertValue(
        key: String,
        value0: String,
        value1: String,
    ) {
        cache[key]!!.use {
            it.assertValue(0, value0)
            it.assertValue(1, value1)
            assertThat(Files.exists(getCleanFile(key, 0))).isTrue()
            assertThat(Files.exists(getCleanFile(key, 1))).isTrue()
        }
    }

    private fun DiskLruCache.Snapshot.assertValue(
        index: Int,
        value: String,
    ) {
        getRawReader(index).use { source ->
            assertThat(rawReaderAsString(source)).isEqualTo(value)
            assertThat(getLength(index)).isEqualTo(value.length.toLong())
        }
    }

    private fun rawReaderAsString(rawReader: RawReader) = rawReader.buffered().readString()

    private fun DiskLruCache.Editor.assertInoperable() {
        assertFailsWith<IllegalStateException> {
            setString(0, "A")
        }
        assertFailsWith<IllegalStateException> {
            newRawReader(0)
        }
        assertFailsWith<IllegalStateException> {
            newRawWriter(0)
        }
        assertFailsWith<IllegalStateException> {
            commit()
        }
        assertFailsWith<IllegalStateException> {
            abort()
        }
    }

    private fun DiskLruCache.Editor.setString(
        index: Int,
        value: String,
    ) {
        newRawWriter(index).buffered().use { writer ->
            writer.write(value)
        }
    }
}
