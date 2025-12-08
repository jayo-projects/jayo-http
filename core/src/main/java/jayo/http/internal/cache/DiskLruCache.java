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

package jayo.http.internal.cache;

import jayo.*;
import jayo.files.Directory;
import jayo.files.File;
import jayo.files.JayoFileNotFoundException;
import jayo.http.internal.Utils;
import jayo.scheduler.TaskQueue;
import jayo.scheduler.TaskRunner;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import static java.lang.System.Logger.Level.WARNING;

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache entry has a string key and a fixed number of
 * values. Each key must match the regex {@code [a-z0-9_-]{1,64}}. Values are byte sequences, accessible as streams or
 * files. Each value must be between {@code 0} and {@code Integer.MAX_VALUE} bytes in length.
 * <p>
 * The cache stores its data in a directory on the filesystem. This directory must be exclusive to the cache; the cache
 * may delete or overwrite files from its directory. It is an error for multiple processes to use the same cache
 * directory at the same time.
 * <p>
 * This cache limits the number of bytes that it will store on the filesystem. When the number of stored bytes exceeds
 * the limit, the cache will remove entries in the background until the limit is satisfied. The limit is not strict: the
 * cache may temporarily exceed it while waiting for files to be deleted. The limit does not include filesystem overhead
 * or the cache journal, so space-sensitive applications should set a conservative limit.
 * <p>
 * Clients call {@link #edit(String)} to create or update the values of an entry. An entry may have only one editor
 * at one time; if a value is not available to be edited, then {@link #edit(String)} will return null.
 * <ul>
 * <li>When an entry is being <b>created</b> it is necessary to supply a full set of values; the empty value should be
 * used as a placeholder if necessary.
 * <li>When an entry is being <b>edited</b>, it is not necessary to supply data for every value; values default to their
 * previous value.
 * </ul>
 * Every {@link #edit(String)} call must be matched by a call to {@link Editor#commit()} or {@link Editor#abort()}.
 * Committing is atomic: a read observes the full set of values as they were before or after the commit, but never a
 * mix of values.
 * <p>
 * Clients call {@link #get(String)} to read a snapshot of an entry. The read will observe the value at the time that
 * {@link #get(String)} was called. Updates and removals after the call do not impact ongoing reads.
 * <p>
 * This class is tolerant of some I/O errors. If files are missing from the filesystem, the corresponding entries will
 * be dropped from the cache. If an error occurs while writing a cache value, the edit will fail silently. Callers
 * should handle other problems by catching {@link jayo.JayoException} and responding appropriately.
 */
final class DiskLruCache implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("jayo.http.DiskLruCache");

    final @NonNull Directory directory;
    private final int appVersion;
    private final int valueCount;

    /**
     * This cache uses a journal file named "journal". A typical journal file looks like this:
     * <pre>
     * {@code
     *     jayo.dev.io.DiskLruCache
     *     1
     *     100
     *     2
     *
     *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
     *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
     *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
     *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
     *     DIRTY 1ab96a171faeeee38496d8b330771a7a
     *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
     *     READ 335c4c6028171cfddfbaae1a9c313c52
     *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
     * }
     * </pre>
     * The first five lines of the journal form its header. They are the constant string "jayo.http.cache.DiskLruCache",
     * the disk cache's version, the application's version, the value count, and a blank line.
     * <p>
     * Each of the subsequent lines in the file is a record of the state of a cache entry. Each line contains
     * space-separated values: a state, a key, and optional state-specific values.
     * <ul>
     * <li>DIRTY lines track that an entry is actively being created or updated. Every successful DIRTY action should be
     * followed by a CLEAN or REMOVE action. DIRTY lines without a matching CLEAN or REMOVE indicate that temporary
     * files may need to be deleted.
     * <li>CLEAN lines track a cache entry that has been successfully published and may be read. A publish line is
     * followed by the lengths of each of its values.
     * <li>READ lines track accesses for LRU.
     * <li>REMOVE lines track entries that have been deleted.
     * </ul>
     * The journal file is appended to as cache operations occur. The journal may occasionally be compacted by dropping
     * redundant lines. A temporary file named "journal.tmp" will be used during compaction; that file should be deleted
     * if it exists when the cache is opened.
     */
    private final @NonNull Path journalFile;
    private final @NonNull Path journalFileTmp;
    private final @NonNull Path journalFileBackup;
    private long byteSize = 0L;
    private @Nullable Writer journalWriter = null;
    private final @NonNull Map<@NonNull String, @NonNull Entry> lruEntries =
            new LinkedHashMap<>(0, 0.75f, true);
    private int redundantOpCount = 0;
    private boolean hasJournalErrors = false;
    private boolean civilizedFileSystem = false;

    // Must be read and written using `lock`.
    private long maxSize;
    private boolean initialized = false;
    private boolean closed = false;
    private boolean mostRecentTrimFailed = false;
    private boolean mostRecentRebuildFailed = false;
    private final @NonNull Lock lock = new ReentrantLock();

    /**
     * To differentiate between old and current snapshots, each entry is given a sequence number each time an edit is
     * committed. A snapshot is stale if its sequence number is not equal to its entry's sequence number.
     */
    private long nextSequenceNumber = 0;

    private final @NonNull TaskQueue cleanupQueue;
    private static final @NonNull String CLEANUP_TASK_NAME = Utils.JAYO_HTTP_NAME + " Cache";
    private final @NonNull Runnable cleanupTask = () -> {
        lock.lock();
        try {
            if (!initialized || closed) {
                return; // Nothing to do.
            }

            try {
                trimToSize();
            } catch (JayoException ignore) {
                mostRecentTrimFailed = true;
            }

            try {
                if (journalRebuildRequired()) {
                    rebuildJournal();
                    redundantOpCount = 0;
                }
            } catch (JayoException ignore) {
                mostRecentRebuildFailed = true;
                if (journalWriter != null) {
                    Utils.closeQuietly(journalWriter);
                }
                journalWriter = Jayo.buffer(Jayo.discardingWriter());
            }
        } finally {
            lock.unlock();
        }
    };

    /**
     * Create a cache which will reside in {@code directory}. This cache is lazily initialized on first access and will
     * be created if it does not exist.
     *
     * @param directory  the writable directory where this cache stores its data.
     * @param valueCount the number of values per cache entry. Must be positive.
     * @param maxSize    the maximum number of bytes this cache should use to store.
     * @param taskRunner used for asynchronous journal rebuilds.
     */
    DiskLruCache(final @NonNull Path directory,
                 final int appVersion,
                 final int valueCount,
                 final long maxSize,
                 final @NonNull TaskRunner taskRunner) {
        assert directory != null;
        assert taskRunner != null;
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (valueCount < 1) {
            throw new IllegalArgumentException("valueCount <= 0");
        }

        this.directory = Directory.createIfNotExists(directory);
        this.appVersion = appVersion;
        this.valueCount = valueCount;
        this.maxSize = maxSize;

        this.journalFile = directory.resolve(JOURNAL_FILE);
        this.journalFileTmp = directory.resolve(JOURNAL_FILE_TEMP);
        this.journalFileBackup = directory.resolve(JOURNAL_FILE_BACKUP);

        this.cleanupQueue = taskRunner.newQueue();
    }

    long getMaxByteSize() {
        lock.lock();
        try {
            return maxSize;
        } finally {
            lock.unlock();
        }
    }

    /**
     * For tests
     */
    void setMaxByteSize(final long maxByteSize) {
        lock.lock();
        try {
            this.maxSize = maxByteSize;
            if (initialized) {
                // Trim the existing store if necessary.
                cleanupQueue.execute(Utils.JAYO_HTTP_NAME + " Cache", true, cleanupTask);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Must be called from a {@linkplain #lock locked} block
     */
    void initialize() {
        if (initialized) {
            return; // Already initialized.
        }

        // If a bkp file exists, use it instead.
        if (File.exists(journalFileBackup)) {
            final var journalFileBackupFile = File.open(journalFileBackup);
            // If the journal file also exists, just delete the backup file.
            if (File.exists(journalFile)) {
                journalFileBackupFile.delete();
            } else {
                journalFileBackupFile.atomicMove(journalFile);
            }
        }

        civilizedFileSystem = isCivilized(journalFileBackup);

        // Prefer to pick up where we left off.
        if (File.exists(journalFile)) {
            try {
                readJournal();
                processJournal();
                initialized = true;
                return;
            } catch (JayoException journalIsCorrupt) {
                LOGGER.log(WARNING, "DiskLruCache " + directory + " is corrupt: " +
                        journalIsCorrupt.getMessage() + ", removing", journalIsCorrupt);
            }

            // The cache is corrupted, attempt to delete the contents of the directory. This can throw, and we'll
            // let that propagate out as it likely means there is a severe filesystem problem.
            try {
                delete();
            } finally {
                closed = false;
            }
        }

        rebuildJournal();

        initialized = true;
    }

    /**
     * @param filePath a file in the directory to check. This file shouldn't exist yet!
     * @return true if file streams can be manipulated independently of their paths. This is typically true for systems
     * like Mac, Unix, and Linux that use inodes in their file system interface. It is typically false on Windows.
     * <p>
     * If this returns false, we won't permit simultaneous reads and writes. When writes commit, we need to delete the
     * previous snapshots, and that won't succeed if the file is open. (We do permit multiple simultaneous reads.)
     */
    private static boolean isCivilized(final @NonNull Path filePath) {
        assert filePath != null;

        final var file = File.create(filePath);
        try (final var ignored = file.writer()) {
            file.delete();
            return true;
        } catch (JayoException ignored) {
        }
        file.delete();
        return false;
    }

    /**
     * Read the journal file, it must exist.
     *
     * @throws JayoException if the journal file is malformed.
     */
    private void readJournal() {
        try (final var source = Jayo.buffer(File.open(journalFile).reader())) {
            final var magic = source.readLineStrict();
            final var version = source.readLineStrict();
            final var appVersionString = source.readLineStrict();
            final var valueCountString = source.readLineStrict();
            final var blank = source.readLineStrict();

            if (!MAGIC.equals(magic) ||
                    !VERSION_1.equals(version) ||
                    !String.valueOf(appVersion).equals(appVersionString) ||
                    !String.valueOf(valueCount).equals(valueCountString) ||
                    !blank.isEmpty()) {
                throw new JayoException("unexpected journal header: [" + magic + ", " + version + ", " +
                        valueCountString + ", " + blank + "]");
            }

            var lineCount = 0;
            while (true) {
                try {
                    readJournalLine(source.readLineStrict());
                    lineCount++;
                } catch (JayoEOFException e) {
                    break; // End of journal.
                }
            }

            redundantOpCount = lineCount - lruEntries.size();

            // If we ended on a truncated line, rebuild the journal before appending to it.
            if (!source.exhausted()) {
                rebuildJournal();
            } else {
                if (journalWriter != null) {
                    Utils.closeQuietly(journalWriter);
                }
                journalWriter = newJournalWriter();
            }
        }
    }

    private @NonNull Writer newJournalWriter() {
        final var fileSink = File.open(journalFile).writer(StandardOpenOption.APPEND);
        final var faultHidingSink = new FaultHidingRawWriter(fileSink, e -> hasJournalErrors = true);
        return Jayo.buffer(faultHidingSink);
    }

    private void readJournalLine(final @NonNull String line) {
        assert line != null;

        final var firstSpace = line.indexOf(' ');
        if (firstSpace == -1) {
            throw new JayoException("unexpected journal line: " + line);
        }

        final var keyBegin = firstSpace + 1;
        final var secondSpace = line.indexOf(' ', keyBegin);
        final String key;
        if (secondSpace == -1) {
            key = line.substring(keyBegin);
            if (firstSpace == REMOVE.length() && line.startsWith(REMOVE)) {
                lruEntries.remove(key);
                return;
            }
        } else {
            key = line.substring(keyBegin, secondSpace);
        }

        var entry = lruEntries.computeIfAbsent(key, Entry::new);

        if (secondSpace != -1 && firstSpace == CLEAN.length() && line.startsWith(CLEAN)) {
            final var parts = line.substring(secondSpace + 1).split(" ");
            entry.readable = true;
            entry.currentEditor = null;
            entry.setLengths(parts);
        } else if (secondSpace == -1 && firstSpace == DIRTY.length() && line.startsWith(DIRTY)) {
            entry.currentEditor = new Editor(entry);
        } else if (secondSpace == -1 && firstSpace == READ.length() && line.startsWith(READ)) {
            // This work was already done by calling lruEntries.get().
        } else {
            throw new JayoException("unexpected journal line: " + line);
        }
    }

    /**
     * Computes the initial size and collects garbage as a part of opening the cache. Dirty entries are assumed to be
     * inconsistent and will be deleted.
     */
    private void processJournal() {
        try {
            Files.deleteIfExists(journalFileTmp);
            final var i = lruEntries.values().iterator();
            while (i.hasNext()) {
                final var entry = i.next();
                if (entry.currentEditor == null) {
                    for (var t = 0; t < valueCount; t++) {
                        byteSize += entry.lengths[t];
                    }
                } else {
                    entry.currentEditor = null;
                    for (var t = 0; t < valueCount; t++) {
                        Files.deleteIfExists(entry.cleanFiles.get(t));
                        Files.deleteIfExists(entry.dirtyFiles.get(t));
                    }
                    i.remove();
                }
            }
        } catch (IOException e) {
            throw JayoException.buildJayoException(e);
        }
    }

    /**
     * Creates a new journal that omits redundant information. This replaces the current journal if it exists.
     */
    private void rebuildJournal() {
        lock.lock();
        try {
            if (journalWriter != null) {
                journalWriter.close();
            }

            final var journalFileTmpFile = File.createIfNotExists(journalFileTmp);
            try (final var writer = Jayo.buffer(journalFileTmpFile.writer())) {
                writer.write(MAGIC).writeByte((byte) '\n');
                writer.write(VERSION_1).writeByte((byte) '\n');
                writer.writeDecimalLong(appVersion).writeByte((byte) '\n');
                writer.writeDecimalLong(valueCount).writeByte((byte) '\n');
                writer.writeByte((byte) '\n');

                for (final var entry : lruEntries.entrySet()) {
                    if (entry.getValue().currentEditor != null) {
                        writer.write(DIRTY).writeByte((byte) ' ');
                        writer.write(entry.getValue().key);
                        writer.writeByte((byte) '\n');
                    } else {
                        writer.write(CLEAN).writeByte((byte) ' ');
                        writer.write(entry.getValue().key);
                        entry.getValue().writeLengths(writer);
                        writer.writeByte((byte) '\n');
                    }
                }
            }

            if (File.exists(journalFile)) {
                // fixme: atomicMove, then deleted 2 lines below. Is it faster than deleting both files ?
                File.open(journalFile).atomicMove(journalFileBackup);
                journalFileTmpFile.atomicMove(journalFile);
                Files.deleteIfExists(journalFileBackup);
            } else {
                journalFileTmpFile.atomicMove(journalFile);
            }

            if (journalWriter != null) {
                Utils.closeQuietly(journalWriter);
            }
            journalWriter = newJournalWriter();
            hasJournalErrors = false;
            mostRecentRebuildFailed = false;

        } catch (IOException e) {
            throw JayoException.buildJayoException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return a snapshot of the entry named {@code key}, or null if it doesn't exist is not currently readable. If a
     * value is returned, it is moved to the head of the LRU queue.
     */
    public @Nullable Snapshot get(final @NonNull String key) {
        assert key != null;

        lock.lock();
        try {
            initialize();

            checkNotClosed();
            validateKey(key);
            final var entry = lruEntries.get(key);
            if (entry == null) {
                return null;
            }
            final var snapshot = entry.snapshot();
            if (snapshot == null) {
                return null;
            }

            redundantOpCount++;
            assert journalWriter != null;
            journalWriter
                    .write(READ)
                    .writeByte((byte) ' ')
                    .write(key)
                    .writeByte((byte) '\n');
            if (journalRebuildRequired()) {
                cleanupQueue.execute(CLEANUP_TASK_NAME, true, cleanupTask);
            }

            return snapshot;
        } finally {
            lock.unlock();
        }
    }

    @Nullable Editor edit(final @NonNull String key) {
        return edit(key, ANY_SEQUENCE_NUMBER, false);
    }

    @Nullable Editor edit(final @NonNull String key, final long expectedSequenceNumber, boolean force) {
        assert key != null;

        lock.lock();
        try {
            initialize();

            checkNotClosed();
            validateKey(key);
            var entry = lruEntries.get(key);
            if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER &&
                    (entry == null || entry.sequenceNumber != expectedSequenceNumber)
            ) {
                return null; // Snapshot is stale.
            }

            if (entry != null && entry.currentEditor != null) {
                return null; // Another edit is in progress.
            }

            if (entry != null && !force && entry.lockingSourceCount != 0) {
                return null; // We can't write this file because a reader is still reading it.
            }

            if (mostRecentTrimFailed || mostRecentRebuildFailed) {
                // The OS has become our enemy! If the trim job fails, it means we are storing more data than requested
                // by the user. Do not allow edits so we do not go over that limit any further. If the journal rebuild
                //  fails, the journal writer will not be active, meaning we will not be able to record the edit,
                //  causing file leaks. In both cases, we want to retry the cleanup so we can get out of this state!
                cleanupQueue.execute(CLEANUP_TASK_NAME, true, cleanupTask);
                return null;
            }

            // Flush the journal before creating files to prevent file leaks.
            final var journalWriter = this.journalWriter;
            if (journalWriter != null) {
                journalWriter
                        .write(DIRTY)
                        .writeByte((byte) ' ')
                        .write(key)
                        .writeByte((byte) '\n');
                journalWriter.flush();
            }

            if (hasJournalErrors) {
                return null; // Don't edit; the journal can't be written.
            }

            if (entry == null) {
                entry = new Entry(key);
                lruEntries.put(key, entry);
            }
            final var editor = new Editor(entry);
            entry.currentEditor = editor;
            return editor;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the number of bytes currently being used to store the values in this cache. This may be greater than the
     * max size if a background deletion is pending.
     */
    public long byteSize() {
        lock.lock();
        try {
            initialize();
            return byteSize;
        } finally {
            lock.unlock();
        }
    }

    private void completeEdit(final @NonNull Editor editor, final boolean success) {
        assert editor != null;

        lock.lock();
        try {
            final var entry = editor.entry;
            if (entry.currentEditor != editor) {
                throw new IllegalStateException("Check failed.");
            }

            // If this edit is creating the entry for the first time, every index must have a value.
            if (success && !entry.readable) {
                for (var i = 0; i < valueCount; i++) {
                    assert editor.written != null;
                    if (!editor.written[i]) {
                        editor.abort();
                        throw new IllegalStateException("Newly created entry didn't create value for index " + i);
                    }
                    if (!File.exists(entry.dirtyFiles.get(i))) {
                        editor.abort();
                        return;
                    }
                }
            }

            for (var i = 0; i < valueCount; i++) {
                final var dirty = entry.dirtyFiles.get(i);
                if (success && !entry.zombie) {
                    if (File.exists(dirty)) {
                        final var dirtyFile = File.open(dirty);
                        final var clean = entry.cleanFiles.get(i);
                        var newLength = dirtyFile.getSize();
                        dirtyFile.atomicMove(clean);
                        // TODO check unknown size behaviour
                        newLength = (newLength != -1) ? newLength : 0;
                        final var oldLength = entry.lengths[i];
                        // TODO check null behaviour
                        entry.lengths[i] = newLength;
                        byteSize = byteSize - oldLength + newLength;
                    }
                } else {
                    Files.deleteIfExists(dirty);
                }
            }

            entry.currentEditor = null;
            if (entry.zombie) {
                removeEntry(entry);
                return;
            }

            redundantOpCount++;
            assert journalWriter != null;
            if (entry.readable || success) {
                entry.readable = true;
                journalWriter.write(CLEAN).writeByte((byte) ' ');
                journalWriter.write(entry.key);
                entry.writeLengths(journalWriter);
                journalWriter.writeByte((byte) '\n');
                if (success) {
                    entry.sequenceNumber = nextSequenceNumber++;
                }
            } else {
                lruEntries.remove(entry.key);
                journalWriter.write(REMOVE).writeByte((byte) ' ');
                journalWriter.write(entry.key);
                journalWriter.writeByte((byte) '\n');
            }
            journalWriter.flush();

            if (byteSize > maxSize || journalRebuildRequired()) {
                cleanupQueue.execute(CLEANUP_TASK_NAME, true, cleanupTask);
            }
        } catch (IOException e) {
            throw JayoException.buildJayoException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * We only rebuild the journal when it will halve the size of the journal and eliminate at least 2000 ops.
     */
    private boolean journalRebuildRequired() {
        return redundantOpCount >= REDUNDANT_OP_COMPACT_THRESHOLD &&
                redundantOpCount >= lruEntries.size();
    }

    /**
     * Drops the entry for {@code key} if it exists and can be removed. If the entry for {@code key} is currently being
     * edited, that edit will complete normally but its value will not be stored.
     */
    void remove(final @NonNull String key) {
        assert key != null;

        lock.lock();
        try {
            initialize();

            checkNotClosed();
            validateKey(key);
            final var entry = lruEntries.get(key);
            if (entry == null) {
                return;
            }
            removeEntry(entry);
            if (byteSize <= maxSize) {
                mostRecentTrimFailed = false;
            }
        } finally {
            lock.unlock();
        }
    }

    private void removeEntry(final @NonNull Entry entry) {
        assert entry != null;

        // If we can't delete files that are still open, mark this entry as a zombie so its files will be deleted when
        // those files are closed.
        if (!civilizedFileSystem) {
            if (entry.lockingSourceCount > 0) {
                // Mark this entry as 'DIRTY' so that if the process crashes, this entry won't be used.
                if (journalWriter != null) {
                    journalWriter.write(DIRTY)
                            .writeByte((byte) ' ')
                            .write(entry.key)
                            .writeByte((byte) '\n');
                    journalWriter.flush();
                }
            }
            if (entry.lockingSourceCount > 0 || entry.currentEditor != null) {
                entry.zombie = true;
                return;
            }
        }

        if (entry.currentEditor != null) {
            entry.currentEditor.detach(); // Prevent the edit from completing normally.
        }

        for (var i = 0; i < valueCount; i++) {
            try {
                Files.deleteIfExists(entry.cleanFiles.get(i));
            } catch (IOException e) {
                throw JayoException.buildJayoException(e);
            }
            byteSize -= entry.lengths[i];
            entry.lengths[i] = 0;
        }

        redundantOpCount++;
        if (journalWriter != null) {
            journalWriter.write(REMOVE)
                    .writeByte((byte) ' ')
                    .write(entry.key)
                    .writeByte((byte) '\n');
        }
        lruEntries.remove(entry.key);

        if (journalRebuildRequired()) {
            cleanupQueue.execute(CLEANUP_TASK_NAME, true, cleanupTask);
        }
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
    }

    /**
     * Force buffered operations to the filesystem.
     */
    void flush() {
        lock.lock();
        try {
            if (!initialized) {
                return;
            }

            checkNotClosed();
            trimToSize();
            assert journalWriter != null;
            journalWriter.flush();
        } finally {
            lock.unlock();
        }
    }

    boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes this cache. Stored values will remain on the filesystem.
     */
    @Override
    public void close() {
        if (!initialized || closed) {
            closed = true;
            return;
        }

        // Copying to an array for concurrent iteration.
        for (final var entry : lruEntries.values().toArray(new Entry[0])) {
            if (entry.currentEditor != null) {
                entry.currentEditor.detach(); // Prevent the edit from completing normally.
            }
        }

        trimToSize();
        if (journalWriter != null) {
            Utils.closeQuietly(journalWriter);
        }
        journalWriter = null;
        closed = true;
    }

    void trimToSize() {
        while (byteSize > maxSize) {
            if (!removeOldestEntry()) {
                return;
            }
        }
        mostRecentTrimFailed = false;
    }

    /**
     * @return true if an entry was removed. This will return false if all entries are zombies.
     */
    private boolean removeOldestEntry() {
        for (final var toEvict : lruEntries.values()) {
            if (!toEvict.zombie) {
                removeEntry(toEvict);
                return true;
            }
        }
        return false;
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete all files in the cache
     * directory including files that weren't created by the cache.
     */
    void delete() {
        close();
        deleteContents(directory);
    }

    /**
     * Tolerant delete, try to clear as many files as possible even after a failure.
     */
    private static void deleteContents(final @NonNull Directory directory) {
        assert directory != null;

        JayoException exception = null;
        final List<Path> entries;
        try {
            entries = directory.listEntries();
        } catch (JayoFileNotFoundException ignored) {
            return;
        }
        for (final var entry : entries) {
            try {
                if (Files.isDirectory(entry)) {
                    deleteContents(Directory.open(entry)); // recursive call
                }

                File.open(entry).delete();
            } catch (JayoException je) {
                if (exception == null) {
                    exception = je;
                }
            }
        }
        if (exception != null) {
            throw exception;
        }
    }

    /**
     * Deletes all stored values from the cache. In-flight edits will complete normally, but their values will not be
     * stored.
     */
    void evictAll() {
        lock.lock();
        try {
            initialize();
            // Copying for concurrent iteration.
            for (final var entry : lruEntries.values().toArray(new Entry[0])) {
                removeEntry(entry);
            }
            mostRecentTrimFailed = false;
        } finally {
            lock.unlock();
        }
    }

    private static void validateKey(String key) {
        if (!LEGAL_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
        }
    }

    /**
     * Returns an iterator over the cache's current entries. This iterator doesn't throw
     * `ConcurrentModificationException`, but if new entries are added while iterating, those new
     * entries will not be returned by the iterator. If existing entries are removed during iteration,
     * they will be absent (unless they were already returned).
     * <p>
     * If there are I/O problems during iteration, this iterator fails silently. For example, if the
     * hosting filesystem becomes unreachable, the iterator will omit elements rather than throwing
     * exceptions.
     * <p>
     * **The caller must [close][Snapshot.close]** each snapshot returned by [Iterator.next]. Failing
     * to do so leaks open files!
     */
    @NonNull Iterator<@NonNull Snapshot> snapshots() {
        lock.lock();
        try {
            initialize();
            return new Iterator<>() {
                /**
                 * Iterate a copy of the entries to defend against concurrent modification errors.
                 */
                private final @NonNull Iterator<@NonNull Entry> delegate =
                        new ArrayList<>(lruEntries.values()).iterator();

                /**
                 * The snapshot to return from {@link #next()}. Null if we haven't computed that yet.
                 */
                private @Nullable Snapshot nextSnapshot = null;

                /**
                 * The snapshot to remove with {@link #remove()}. Null if removal is illegal.
                 */
                private @Nullable Snapshot removeSnapshot = null;

                @Override
                public boolean hasNext() {
                    if (nextSnapshot != null) {
                        return true;
                    }

                    lock.lock();
                    try {
                        // If the cache is closed, truncate the iterator.
                        if (closed) {
                            return false;
                        }

                        while (delegate.hasNext()) {
                            final var candidate = delegate.next();
                            nextSnapshot = (candidate != null) ? candidate.snapshot() : null;
                            if (nextSnapshot != null) {
                                return true;
                            }
                        }
                    } finally {
                        lock.unlock();
                    }

                    return false;
                }

                @Override
                public @NonNull Snapshot next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    removeSnapshot = nextSnapshot;
                    nextSnapshot = null;
                    assert removeSnapshot != null;
                    return removeSnapshot;
                }

                @Override
                public void remove() {
                    if (removeSnapshot == null) {
                        throw new IllegalStateException("remove() before next()");
                    }
                    try {
                        DiskLruCache.this.remove(removeSnapshot.key);
                    } catch (JayoException ignored) {
                        // Nothing useful to do here. We failed to remove from the cache. Most likely that's because we
                        // couldn't update the journal, but the cached entry will still be gone.
                    } finally {
                        removeSnapshot = null;
                    }
                }
            };
        } finally {
            lock.unlock();
        }
    }

    /**
     * A snapshot of the values for an entry.
     */
    class Snapshot implements AutoCloseable {
        final @NonNull String key;
        private final long sequenceNumber;
        private final @NonNull List<@NonNull RawReader> sources;
        private final long @NonNull [] lengths;

        private Snapshot(final @NonNull String key,
                         final long sequenceNumber,
                         final @NonNull List<@NonNull RawReader> sources,
                         final long @NonNull [] lengths) {
            assert key != null;
            assert sources != null;
            assert lengths != null;

            this.key = key;
            this.sequenceNumber = sequenceNumber;
            this.sources = sources;
            this.lengths = lengths;
        }

        /**
         * @return an editor for this snapshot's entry, or null if either the entry has changed since this snapshot was
         * created or if another edit is in progress.
         */
        @Nullable Editor edit() {
            return DiskLruCache.this.edit(key, sequenceNumber, true);
        }

        /**
         * @return the unbuffered stream with the value for {@code index}.
         */
        @NonNull RawReader getRawReader(final int index) {
            return sources.get(index);
        }

        /**
         * For tests
         *
         * @return the byte length of the value for {@code index}.
         */
        long getLength(final int index) {
            return lengths[index];
        }

        @Override
        public void close() {
            for (final var source : sources) {
                Utils.closeQuietly(source);
            }
        }
    }

    /**
     * Edits the values for an entry.
     */
    class Editor {
        private final @NonNull Entry entry;
        private final boolean @Nullable [] written;
        private boolean done = false;

        private Editor(final @NonNull Entry entry) {
            assert entry != null;

            this.entry = entry;
            this.written = entry.readable ? null : new boolean[valueCount];
        }

        /**
         * Prevents this editor from completing normally. This is necessary either when the edit causes an I/O error or
         * if the target entry is evicted while this editor is active. In either case we delete the editor's created
         * files and prevent new files from being created. Note that once an editor has been detached, it is possible
         * for another editor to edit the entry.
         */
        private void detach() {
            if (this.equals(entry.currentEditor)) {
                if (civilizedFileSystem) {
                    completeEdit(this, false); // Delete it now.
                } else {
                    entry.zombie = true; // We can't delete it until the current edit completes.
                }
            }
        }

        /**
         * For tests
         *
         * @return an unbuffered input stream to read the last committed value, or null if no value has been committed.
         */
        @Nullable RawReader newSource(final int index) {
            lock.lock();
            try {
                if (done) {
                    throw new IllegalStateException();
                }
                if (!entry.readable || entry.currentEditor != this || entry.zombie) {
                    return null;
                }
                try {
                    return File.open(entry.cleanFiles.get(index)).reader();
                } catch (JayoFileNotFoundException ignored) {
                    return null;
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * @return a new RawWriter to write the value at {@code index}. If the underlying writer encounters errors when
         * writing to the filesystem, this edit will be aborted when {@link #commit()} is called. The returned RawWriter
         * does not throw JayoExceptions.
         */
        @NonNull RawWriter newRawWriter(final int index) {
            lock.lock();
            try {
                if (done) {
                    throw new IllegalStateException();
                }
                if (entry.currentEditor != this) {
                    return Jayo.discardingWriter();
                }
                if (!entry.readable) {
                    assert written != null;
                    written[index] = true;
                }
                final var dirtyFile = entry.dirtyFiles.get(index);
                final RawWriter rawWriter;
                try {
                    rawWriter = File.createIfNotExists(dirtyFile).writer();
                } catch (JayoFileNotFoundException ignored) {
                    return Jayo.discardingWriter();
                }
                return new FaultHidingRawWriter(rawWriter, ignored -> {
                    lock.lock();
                    try {
                        detach();
                    } finally {
                        lock.unlock();
                    }
                });
            } finally {
                lock.unlock();
            }
        }

        /**
         * Commits this edit so it is visible to readers. This releases the edit lock so another edit may be started on
         * the same key.
         */
        void commit() {
            lock.lock();
            try {
                if (done) {
                    throw new IllegalStateException();
                }
                if (this.equals(entry.currentEditor)) {
                    completeEdit(this, true);
                }
                done = true;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Aborts this edit. This releases the edit lock so another edit may be started on the same key.
         */
        void abort() {
            lock.lock();
            try {
                if (done) {
                    throw new IllegalStateException();
                }
                if (this.equals(entry.currentEditor)) {
                    completeEdit(this, false);
                }
                done = true;
            } finally {
                lock.unlock();
            }
        }
    }

    private class Entry {
        private final @NonNull String key;
        /**
         * Lengths of this entry's files.
         */
        private final long @NonNull [] lengths = new long[valueCount];
        private final @NonNull List<@NonNull Path> cleanFiles = new ArrayList<>();
        private final @NonNull List<@NonNull Path> dirtyFiles = new ArrayList<>();

        /**
         * True if this entry has ever been published.
         */
        private boolean readable = false;

        /**
         * True if this entry must be deleted when the current edit or read completes.
         */
        private boolean zombie = false;

        /**
         * The ongoing edit or null if this entry is not being edited. When setting this to null the entry must be
         * removed if it is a zombie.
         */
        private @Nullable Editor currentEditor = null;

        /**
         * Sources currently reading this entry before a write or delete can proceed. When decrementing this to zero,
         * the entry must be removed if it is a zombie.
         */
        private int lockingSourceCount = 0;

        /**
         * The sequence number of the most recently committed edit to this entry.
         */
        private long sequenceNumber = 0;

        private Entry(final @NonNull String key) {
            assert key != null;
            this.key = key;

            // The names are repetitive, so re-use the same String builder to avoid allocations.
            final var fileBuilder = new StringBuilder(key).append('.');
            final var truncateTo = fileBuilder.length();
            for (var i = 0; i < valueCount; i++) {
                fileBuilder.append(i);
                cleanFiles.add(directory.getPath().resolve(fileBuilder.toString()));
                fileBuilder.append(".tmp");
                dirtyFiles.add(directory.getPath().resolve(fileBuilder.toString()));
                fileBuilder.setLength(truncateTo);
            }
        }

        /**
         * Set lengths using decimal numbers like "10123".
         */
        private void setLengths(final @NonNull String @NonNull [] strings) {
            assert strings != null;

            if (strings.length != valueCount) {
                throw new JayoException("unexpected journal line: " + Arrays.toString(strings));
            }

            try {
                for (var i = 0; i < strings.length; i++) {
                    lengths[i] = Long.parseLong(strings[i]);
                }
            } catch (NumberFormatException ignored) {
                throw new JayoException("unexpected journal line: " + Arrays.toString(strings));
            }
        }

        /**
         * Append space-prefixed lengths to {@code writer}.
         */
        private void writeLengths(final @NonNull Writer writer) {
            for (final var length : lengths) {
                writer.writeByte((byte) ' ').writeDecimalLong(length);
            }
        }

        /**
         * @return a snapshot of this entry. This opens all streams eagerly to guarantee that we see a single published
         * snapshot. If we opened streams lazily, then the streams could come from different edits.
         */
        Snapshot snapshot() {
            if (!readable) {
                return null;
            }
            if (!civilizedFileSystem && (currentEditor != null || zombie)) {
                return null;
            }

            final var sources = new ArrayList<RawReader>();
            final var lengths = this.lengths.clone(); // Defensive copy since these can be zeroed out.
            try {
                for (var i = 0; i < valueCount; i++) {
                    sources.add(newSource(i));
                }
                return new Snapshot(key, sequenceNumber, sources, lengths);
            } catch (JayoFileNotFoundException ignored) {
                // A file must have been deleted manually!
                for (final var source : sources) {
                    Utils.closeQuietly(source);
                }
                // Since the entry is no longer valid, remove it so the metadata is accurate (i.e. the cache size.)
                try {
                    removeEntry(this);
                } catch (JayoException ignored2) {
                }
                return null;
            }
        }

        private @NonNull RawReader newSource(final int index) {
            final var fileSource = File.open(cleanFiles.get(index)).reader();
            if (civilizedFileSystem) {
                return fileSource;
            }

            lockingSourceCount++;
            return new RawReader() {
                private boolean closed = false;

                @Override
                public long readAtMostTo(final @NonNull Buffer destination, final long byteCount) {
                    return fileSource.readAtMostTo(destination, byteCount);
                }

                @Override
                public void close() {
                    fileSource.close();
                    if (!closed) {
                        closed = true;
                        lock.lock();
                        try {
                            lockingSourceCount--;
                            if (lockingSourceCount == 0 && zombie) {
                                removeEntry(Entry.this);
                            }
                        } finally {
                            lock.unlock();
                        }
                    }
                }
            };
        }
    }

    private static final String JOURNAL_FILE = "journal";

    private static final String JOURNAL_FILE_TEMP = "journal.tmp";

    private static final String JOURNAL_FILE_BACKUP = "journal.bkp";

    private static final String MAGIC = "jayo.http.cache.DiskLruCache";

    private static final String VERSION_1 = "1";

    private static final long ANY_SEQUENCE_NUMBER = -1L;

    private static final Pattern LEGAL_KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,120}");

    private static final String CLEAN = "CLEAN";

    private static final String DIRTY = "DIRTY";

    private static final String REMOVE = "REMOVE";

    private static final String READ = "READ";

    private static final int REDUNDANT_OP_COMPACT_THRESHOLD = 2000;
}
