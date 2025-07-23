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

package jayo.http.internal.http2;

import jayo.Buffer;
import jayo.Jayo;
import jayo.JayoException;
import jayo.RawReader;
import jayo.bytestring.ByteString;
import org.jspecify.annotations.NonNull;

import java.util.*;

import static jayo.http.internal.http2.RealBinaryHeader.*;

final class Hpack {
    // un-instantiable
    private Hpack() {
    }

    private static final int PREFIX_4_BITS = 0x0f;
    private static final int PREFIX_5_BITS = 0x1f;
    private static final int PREFIX_6_BITS = 0x3f;
    private static final int PREFIX_7_BITS = 0x7f;

    private static final int SETTINGS_HEADER_TABLE_SIZE = 4_096;

    /**
     * The decoder has ultimate control of the maximum size of the dynamic table, but we can choose to use less. We'll
     * put a cap at 16K. This is arbitrary but should be enough for most purposes.
     */
    private static final int SETTINGS_HEADER_TABLE_SIZE_LIMIT = 16_384;

    private static final @NonNull RealBinaryHeader @NonNull [] STATIC_HEADER_TABLE = new RealBinaryHeader[]{
            new RealBinaryHeader(TARGET_AUTHORITY, ""),
            new RealBinaryHeader(TARGET_METHOD, "GET"),
            new RealBinaryHeader(TARGET_METHOD, "POST"),
            new RealBinaryHeader(TARGET_PATH, "/"),
            new RealBinaryHeader(TARGET_PATH, "/index.html"),
            new RealBinaryHeader(TARGET_SCHEME, "http"),
            new RealBinaryHeader(TARGET_SCHEME, "https"),
            new RealBinaryHeader(RESPONSE_STATUS, "200"),
            new RealBinaryHeader(RESPONSE_STATUS, "204"),
            new RealBinaryHeader(RESPONSE_STATUS, "206"),
            new RealBinaryHeader(RESPONSE_STATUS, "304"),
            new RealBinaryHeader(RESPONSE_STATUS, "400"),
            new RealBinaryHeader(RESPONSE_STATUS, "404"),
            new RealBinaryHeader(RESPONSE_STATUS, "500"),
            new RealBinaryHeader("accept-charset", ""),
            new RealBinaryHeader("accept-encoding", "gzip, deflate"),
            new RealBinaryHeader("accept-language", ""),
            new RealBinaryHeader("accept-ranges", ""),
            new RealBinaryHeader("accept", ""),
            new RealBinaryHeader("access-control-allow-origin", ""),
            new RealBinaryHeader("age", ""),
            new RealBinaryHeader("allow", ""),
            new RealBinaryHeader("authorization", ""),
            new RealBinaryHeader("cache-control", ""),
            new RealBinaryHeader("content-disposition", ""),
            new RealBinaryHeader("content-encoding", ""),
            new RealBinaryHeader("content-language", ""),
            new RealBinaryHeader("content-length", ""),
            new RealBinaryHeader("content-location", ""),
            new RealBinaryHeader("content-range", ""),
            new RealBinaryHeader("content-type", ""),
            new RealBinaryHeader("cookie", ""),
            new RealBinaryHeader("date", ""),
            new RealBinaryHeader("etag", ""),
            new RealBinaryHeader("expect", ""),
            new RealBinaryHeader("expires", ""),
            new RealBinaryHeader("from", ""),
            new RealBinaryHeader("host", ""),
            new RealBinaryHeader("if-match", ""),
            new RealBinaryHeader("if-modified-since", ""),
            new RealBinaryHeader("if-none-match", ""),
            new RealBinaryHeader("if-range", ""),
            new RealBinaryHeader("if-unmodified-since", ""),
            new RealBinaryHeader("last-modified", ""),
            new RealBinaryHeader("link", ""),
            new RealBinaryHeader("location", ""),
            new RealBinaryHeader("max-forwards", ""),
            new RealBinaryHeader("proxy-authenticate", ""),
            new RealBinaryHeader("proxy-authorization", ""),
            new RealBinaryHeader("range", ""),
            new RealBinaryHeader("referer", ""),
            new RealBinaryHeader("refresh", ""),
            new RealBinaryHeader("retry-after", ""),
            new RealBinaryHeader("server", ""),
            new RealBinaryHeader("set-cookie", ""),
            new RealBinaryHeader("strict-transport-security", ""),
            new RealBinaryHeader("transfer-encoding", ""),
            new RealBinaryHeader("user-agent", ""),
            new RealBinaryHeader("vary", ""),
            new RealBinaryHeader("via", ""),
            new RealBinaryHeader("www-authenticate", "")
    };

    private static final @NonNull Map<@NonNull ByteString, Integer> NAME_TO_FIRST_INDEX = nameToFirstIndex();

    private static @NonNull Map<@NonNull ByteString, Integer> nameToFirstIndex() {
        final var result = new LinkedHashMap<@NonNull ByteString, @NonNull Integer>(STATIC_HEADER_TABLE.length, 1.0F);
        for (var i = 0; i < STATIC_HEADER_TABLE.length; i++) {
            result.putIfAbsent(STATIC_HEADER_TABLE[i].getName(), i);
        }
        return Map.copyOf(result);
    }

    // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-3.1
    static final class Reader {
        private final @NonNull List<@NonNull RealBinaryHeader> headerList = new ArrayList<>();
        private final jayo.@NonNull Reader source;

        // Visible for testing.
        @NonNull
        RealBinaryHeader @NonNull [] dynamicTable = new RealBinaryHeader[8];

        // Array is populated back to front, so new entries always have the lowest index.
        private int nextHeaderIndex = dynamicTable.length - 1;

        int headerCount = 0;

        int dynamicTableByteCount = 0;

        private final int headerTableSizeSetting;
        private int maxDynamicTableByteCount;

        Reader(final @NonNull RawReader rawSource,
               final int headerTableSizeSetting,
               final int maxDynamicTableByteCount) {
            assert rawSource != null;

            this.source = Jayo.buffer(rawSource);
            this.headerTableSizeSetting = headerTableSizeSetting;
            this.maxDynamicTableByteCount = maxDynamicTableByteCount > 0 ? maxDynamicTableByteCount : headerTableSizeSetting;
        }

        @NonNull
        List<@NonNull RealBinaryHeader> getAndResetHeaderList() {
            final var result = List.copyOf(headerList);
            headerList.clear();
            return result;
        }

        int maxDynamicTableByteCount() {
            return maxDynamicTableByteCount;
        }

        private void adjustDynamicTableByteCount() {
            if (maxDynamicTableByteCount < dynamicTableByteCount) {
                if (maxDynamicTableByteCount == 0) {
                    clearDynamicTable();
                } else {
                    evictToRecoverBytes(dynamicTableByteCount - maxDynamicTableByteCount);
                }
            }
        }

        private void clearDynamicTable() {
            Arrays.fill(dynamicTable, null);
            nextHeaderIndex = dynamicTable.length - 1;
            headerCount = 0;
            dynamicTableByteCount = 0;
        }

        /**
         * @return the count of entries evicted.
         */
        private int evictToRecoverBytes(final int bytesToRecover) {
            var _bytesToRecover = bytesToRecover;
            var entriesToEvict = 0;
            if (_bytesToRecover > 0) {
                // determine how many headers need to be evicted.
                var j = dynamicTable.length - 1;
                while (j >= nextHeaderIndex && _bytesToRecover > 0) {
                    final var toEvict = dynamicTable[j];
                    assert toEvict != null;
                    _bytesToRecover -= toEvict.hpackSize;
                    dynamicTableByteCount -= toEvict.hpackSize;
                    headerCount--;
                    entriesToEvict++;
                    j--;
                }
                System.arraycopy(
                        dynamicTable,
                        nextHeaderIndex + 1,
                        dynamicTable,
                        nextHeaderIndex + 1 + entriesToEvict,
                        headerCount
                );
                nextHeaderIndex += entriesToEvict;
            }
            return entriesToEvict;
        }

        /**
         * Read some bytes of headers from the source stream. This implementation does not propagate the never indexed
         * flag of a header.
         */
        void readHeaders() {
            while (!source.exhausted()) {
                final var b = readByte() & 0xff;
                if (b == 0x80) {
                    // 10000000
                    throw new JayoException("index == 0");
                } else if ((b & 0x80) == 0x80) {
                    // 1NNNNNNN
                    final var index = readInt(b, PREFIX_7_BITS);
                    readIndexedHeader(index - 1);
                } else if (b == 0x40) {
                    // 01000000
                    readLiteralHeaderWithIncrementalIndexingNewName();
                } else if ((b & 0x40) == 0x40) {
                    // 01NNNNNN
                    final var index = readInt(b, PREFIX_6_BITS);
                    readLiteralHeaderWithIncrementalIndexingIndexedName(index - 1);
                } else if ((b & 0x20) == 0x20) {
                    // 001NNNNN
                    maxDynamicTableByteCount = readInt(b, PREFIX_5_BITS);
                    if (maxDynamicTableByteCount < 0 || maxDynamicTableByteCount > headerTableSizeSetting) {
                        throw new JayoException("Invalid dynamic table size update " + maxDynamicTableByteCount);
                    }
                    adjustDynamicTableByteCount();
                } else if (b == 0x10 || b == 0) {
                    // 000?0000 - Ignore never indexed bit.
                    readLiteralHeaderWithoutIndexingNewName();
                } else {
                    // 000?NNNN - Ignore never indexed bit.
                    final var index = readInt(b, PREFIX_4_BITS);
                    readLiteralHeaderWithoutIndexingIndexedName(index - 1);
                }
            }
        }

        private void readIndexedHeader(final int index) {
            if (isStaticHeader(index)) {
                final var staticEntry = STATIC_HEADER_TABLE[index];
                headerList.add(staticEntry);
            } else {
                final var dynamicTableIndex = dynamicTableIndex(index - STATIC_HEADER_TABLE.length);
                if (dynamicTableIndex < 0 || dynamicTableIndex >= dynamicTable.length) {
                    throw new JayoException("Header index too large " + (index + 1));
                }
                headerList.add(dynamicTable[dynamicTableIndex]);
            }
        }

        // referencedHeaders is relative to nextHeaderIndex + 1.
        private int dynamicTableIndex(final int index) {
            return nextHeaderIndex + 1 + index;
        }

        private void readLiteralHeaderWithoutIndexingIndexedName(final int index) {
            final var name = getName(index);
            final var value = readByteString();
            headerList.add(new RealBinaryHeader(name, value));
        }

        private void readLiteralHeaderWithoutIndexingNewName() {
            final var name = checkLowercase(readByteString());
            final var value = readByteString();
            headerList.add(new RealBinaryHeader(name, value));
        }

        private void readLiteralHeaderWithIncrementalIndexingIndexedName(final int nameIndex) {
            final var name = getName(nameIndex);
            final var value = readByteString();
            insertIntoDynamicTable(new RealBinaryHeader(name, value));
        }

        private void readLiteralHeaderWithIncrementalIndexingNewName() {
            final var name = checkLowercase(readByteString());
            final var value = readByteString();
            insertIntoDynamicTable(new RealBinaryHeader(name, value));
        }

        private ByteString getName(final int index) {
            if (isStaticHeader(index)) {
                return STATIC_HEADER_TABLE[index].getName();
            } else {
                final var dynamicTableIndex = dynamicTableIndex(index - STATIC_HEADER_TABLE.length);
                if (dynamicTableIndex < 0 || dynamicTableIndex >= dynamicTable.length) {
                    throw new JayoException("Header index too large " + (index + 1));
                }
                return dynamicTable[dynamicTableIndex].getName();
            }
        }

        private boolean isStaticHeader(final int index) {
            return index >= 0 && index <= STATIC_HEADER_TABLE.length - 1;
        }

        private void insertIntoDynamicTable(final @NonNull RealBinaryHeader entry) {
            assert entry != null;

            headerList.add(entry);

            var index = -1;
            final var delta = entry.hpackSize;

            // if the new or replacement header is too big, drop all entries.
            if (delta > maxDynamicTableByteCount) {
                clearDynamicTable();
                return;
            }

            // Evict headers to the required length.
            final var bytesToRecover = dynamicTableByteCount + delta - maxDynamicTableByteCount;
            final var entriesEvicted = evictToRecoverBytes(bytesToRecover);

            if (index == -1) { // Adding a value to the dynamic table.
                if (headerCount + 1 > dynamicTable.length) { // Need to grow the dynamic table.
                    final var doubled = new RealBinaryHeader[dynamicTable.length * 2];
                    System.arraycopy(dynamicTable, 0, doubled, dynamicTable.length, dynamicTable.length);
                    nextHeaderIndex = dynamicTable.length - 1;
                    dynamicTable = doubled;
                }
                index = nextHeaderIndex--;
                dynamicTable[index] = entry;
                headerCount++;
            } else { // Replace value at same position.
                index += dynamicTableIndex(index) + entriesEvicted;
                dynamicTable[index] = entry;
            }
            dynamicTableByteCount += delta;
        }

        private int readByte() {
            return source.readByte() & 0xff;
        }

        int readInt(final int firstByte, final int prefixMask) {
            final var prefix = firstByte & prefixMask;
            if (prefix < prefixMask) {
                return prefix; // This was a single byte value.
            }

            // This is a multibyte value. Read 7 bits at a time.
            var result = prefixMask;
            var shift = 0;
            while (true) {
                final var b = readByte();
                if ((b & 0x80) != 0) { // Equivalent to (b >= 128) since b is in [0..255].
                    result += (b & 0x7f) << shift;
                    shift += 7;
                } else {
                    result += (b << shift); // Last byte.
                    break;
                }
            }
            return result;
        }

        /**
         * Reads a potentially Huffman encoded byte string.
         */
        @NonNull
        ByteString readByteString() {
            final var firstByte = readByte();
            final var huffmanDecode = (firstByte & 0x80) == 0x80; // 1NNNNNNN
            final var length = readInt(firstByte, PREFIX_7_BITS);

            if (huffmanDecode) {
                final var decodeBuffer = Buffer.create();
                Huffman.decode(source, length, decodeBuffer);
                return decodeBuffer.readByteString();
            } else {
                return source.readByteString(length);
            }
        }

        /**
         * An HTTP/2 response cannot contain uppercase header characters and must be treated as malformed.
         */
        private static @NonNull ByteString checkLowercase(final @NonNull ByteString name) {
            assert name != null;

            for (var i = 0; i < name.byteSize(); i++) {
                if (name.getByte(i) >= (byte) 'A' && name.getByte(i) <= (byte) 'Z') {
                    throw new JayoException(
                            "PROTOCOL_ERROR response malformed: mixed case name: " + name.decodeToString());
                }
            }
            return name;
        }
    }

    static final class Writer {
        // for tests
        int headerTableSizeSetting;
        private final boolean useCompression;
        private final Buffer out;

        /**
         * In the scenario where the dynamic table size changes multiple times between transmission of header blocks, we
         * need to keep track of the smallest value in that interval.
         */
        private int smallestHeaderTableSizeSetting = Integer.MAX_VALUE;
        private boolean emitDynamicTableSizeUpdate = false;

        public int maxDynamicTableByteCount;

        // Visible for testing.
        RealBinaryHeader @NonNull [] dynamicTable = new RealBinaryHeader[8];

        // Array is populated back to front, so new entries always have the lowest index.
        private int nextHeaderIndex = dynamicTable.length - 1;

        int headerCount = 0;

        int dynamicTableByteCount = 0;

        Writer(final @NonNull Buffer out) {
            this(SETTINGS_HEADER_TABLE_SIZE, true, out);
        }

        Writer(final int headerTableSizeSetting,
               final boolean useCompression,
               final @NonNull Buffer out) {
            assert out != null;

            this.headerTableSizeSetting = headerTableSizeSetting;
            this.useCompression = useCompression;
            this.out = out;
            this.maxDynamicTableByteCount = headerTableSizeSetting;
        }

        private void clearDynamicTable() {
            Arrays.fill(dynamicTable, null);
            nextHeaderIndex = dynamicTable.length - 1;
            headerCount = 0;
            dynamicTableByteCount = 0;
        }

        private void evictToRecoverBytes(final int bytesToRecover) {
            var _bytesToRecover = bytesToRecover;
            var entriesToEvict = 0;
            if (_bytesToRecover > 0) {
                // determine how many headers need to be evicted.
                var j = dynamicTable.length - 1;
                while (j >= nextHeaderIndex && _bytesToRecover > 0) {
                    _bytesToRecover -= dynamicTable[j].hpackSize;
                    dynamicTableByteCount -= dynamicTable[j].hpackSize;
                    headerCount--;
                    entriesToEvict++;
                    j--;
                }
                System.arraycopy(
                        dynamicTable,
                        nextHeaderIndex + 1,
                        dynamicTable,
                        nextHeaderIndex + 1 + entriesToEvict,
                        headerCount);
                Arrays.fill(dynamicTable, nextHeaderIndex + 1, nextHeaderIndex + 1 + entriesToEvict, null);
                nextHeaderIndex += entriesToEvict;
            }
        }

        /**
         * This does not use "never indexed" semantics for sensitive headers.
         * <p>
         * See <a href="https://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-6.2.3">
         * Header compression</a>
         */
        void writeHeaders(final @NonNull List<@NonNull RealBinaryHeader> headerBlock) {
            assert headerBlock != null;

            if (emitDynamicTableSizeUpdate) {
                if (smallestHeaderTableSizeSetting < maxDynamicTableByteCount) {
                    // Multiple dynamic table size updates!
                    writeInt(smallestHeaderTableSizeSetting, PREFIX_5_BITS, 0x20);
                }
                emitDynamicTableSizeUpdate = false;
                smallestHeaderTableSizeSetting = Integer.MAX_VALUE;
                writeInt(maxDynamicTableByteCount, PREFIX_5_BITS, 0x20);
            }

            for (final var header : headerBlock) {
                final var name = header.getName().toAsciiLowercase();
                final var value = header.getValue();
                var headerIndex = -1;
                var headerNameIndex = -1;

                final var staticIndex = NAME_TO_FIRST_INDEX.get(name);
                if (staticIndex != null) {
                    headerNameIndex = staticIndex + 1;
                    if (headerNameIndex >= 2 && headerNameIndex <= 7) {
                        // Only search a subset of the static header table. Most entries have an empty value, so it's
                        // unnecessary to waste cycles looking at them. This check is built on the observation that the
                        // header entries we care about are in adjacent pairs, and we always know the first index of the
                        // pair.
                        if (STATIC_HEADER_TABLE[headerNameIndex - 1].getValue().equals(value)) {
                            headerIndex = headerNameIndex;
                        } else if (STATIC_HEADER_TABLE[headerNameIndex].getValue().equals(value)) {
                            headerIndex = headerNameIndex + 1;
                        }
                    }
                }

                if (headerIndex == -1) {
                    for (var j = nextHeaderIndex + 1; j < dynamicTable.length; j++) {
                        if (dynamicTable[j].getName().equals(name)) {
                            if (dynamicTable[j].getValue().equals(value)) {
                                headerIndex = j - nextHeaderIndex + STATIC_HEADER_TABLE.length;
                                break;
                            } else if (headerNameIndex == -1) {
                                headerNameIndex = j - nextHeaderIndex + STATIC_HEADER_TABLE.length;
                            }
                        }
                    }
                }

                if (headerIndex != -1) {
                    // Indexed Header Field.
                    writeInt(headerIndex, PREFIX_7_BITS, 0x80);
                } else if (headerNameIndex == -1) {
                    // Literal Header Field with Incremental Indexing - New Name.
                    out.writeByte((byte) 0x40);
                    writeByteString(name);
                    writeByteString(value);
                    insertIntoDynamicTable(header);
                } else if (name.startsWith(RealBinaryHeader.PSEUDO_PREFIX) && !TARGET_AUTHORITY.equals(name)) {
                    // Follow Chromes lead - only include the :authority pseudo header, but exclude all other pseudo
                    // headers. Literal Header Field without Indexing - Indexed Name.
                    writeInt(headerNameIndex, PREFIX_4_BITS, 0);
                    writeByteString(value);
                } else {
                    // Literal Header Field with Incremental Indexing - Indexed Name.
                    writeInt(headerNameIndex, PREFIX_6_BITS, 0x40);
                    writeByteString(value);
                    insertIntoDynamicTable(header);
                }
            }
        }

        private void insertIntoDynamicTable(final @NonNull RealBinaryHeader entry) {
            assert entry != null;

            final var delta = entry.hpackSize;

            // if the new or replacement header is too big, drop all entries.
            if (delta > maxDynamicTableByteCount) {
                clearDynamicTable();
                return;
            }

            // Evict headers to the required length.
            final var bytesToRecover = dynamicTableByteCount + delta - maxDynamicTableByteCount;
            evictToRecoverBytes(bytesToRecover);

            if (headerCount + 1 > dynamicTable.length) { // Need to grow the dynamic table.
                final var doubled = new RealBinaryHeader[dynamicTable.length * 2];
                System.arraycopy(dynamicTable, 0, doubled, dynamicTable.length, dynamicTable.length);
                nextHeaderIndex = dynamicTable.length - 1;
                dynamicTable = doubled;
            }
            final var index = nextHeaderIndex--;
            dynamicTable[index] = entry;
            headerCount++;
            dynamicTableByteCount += delta;
        }

        // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-4.1.1
        void writeInt(final int value,
                      final int prefixMask,
                      final int bits) {
            // Write the raw value for a single byte value.
            if (value < prefixMask) {
                out.writeByte((byte) (bits | value));
                return;
            }
            var _value = value;

            // Write the mask to start a multibyte value.
            out.writeByte((byte) (bits | prefixMask));
            _value -= prefixMask;

            // Write 7 bits at a time 'til we're done.
            while (_value >= 0x80) {
                final var b = _value & 0x7f;
                out.writeByte((byte) (b | 0x80));
                _value >>>= 7;
            }
            out.writeByte((byte) _value);
        }

        void writeByteString(final @NonNull ByteString data) {
            assert data != null;

            if (useCompression && Huffman.encodedLength(data) < data.byteSize()) {
                final var huffmanBuffer = Buffer.create();
                Huffman.encode(data, huffmanBuffer);
                final var huffmanBytes = huffmanBuffer.readByteString();
                writeInt(huffmanBytes.byteSize(), PREFIX_7_BITS, 0x80);
                out.write(huffmanBytes);
            } else {
                writeInt(data.byteSize(), PREFIX_7_BITS, 0);
                out.write(data);
            }
        }

        void resizeHeaderTable(final int headerTableSizeSetting) {
            this.headerTableSizeSetting = headerTableSizeSetting;
            final var effectiveHeaderTableSize = Math.min(headerTableSizeSetting, SETTINGS_HEADER_TABLE_SIZE_LIMIT);

            if (maxDynamicTableByteCount == effectiveHeaderTableSize) {
                return; // No change.
            }

            if (effectiveHeaderTableSize < maxDynamicTableByteCount) {
                smallestHeaderTableSizeSetting = Math.min(smallestHeaderTableSizeSetting, effectiveHeaderTableSize);
            }
            emitDynamicTableSizeUpdate = true;
            maxDynamicTableByteCount = effectiveHeaderTableSize;
            adjustDynamicTableByteCount();
        }

        private void adjustDynamicTableByteCount() {
            if (maxDynamicTableByteCount < dynamicTableByteCount) {
                if (maxDynamicTableByteCount == 0) {
                    clearDynamicTable();
                } else {
                    evictToRecoverBytes(dynamicTableByteCount - maxDynamicTableByteCount);
                }
            }
        }
    }
}
