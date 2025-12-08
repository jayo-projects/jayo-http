/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2010 The Android Open Source Project
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
import jayo.bytestring.ByteString;
import jayo.crypto.JdkDigest;
import jayo.http.*;
import jayo.http.internal.RealHeaders;
import jayo.http.internal.Utils;
import jayo.http.internal.http.StatusLine;
import jayo.http.tools.HttpMethodUtils;
import jayo.scheduler.TaskRunner;
import jayo.tls.CipherSuite;
import jayo.tls.Handshake;
import jayo.tls.Protocol;
import jayo.tls.TlsVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.WARNING;

public final class RealCache implements Cache {
    private static final System.Logger LOGGER = System.getLogger("jayo.http.Cache");

    /**
     * Prefix used on custom headers.
     */
    static final @NonNull String PREFIX = "JayoHttp";

    private static final int VERSION = 202512;
    private static final int ENTRY_METADATA = 0;
    private static final int ENTRY_BODY = 1;
    private static final int ENTRY_COUNT = 2;

    private final @NonNull DiskLruCache cache;

    // read and write statistics, all guarded by 'lock'.
    int writeSuccessCount = 0;
    int writeAbortCount = 0;
    private int networkCount = 0;
    private int hitCount = 0;
    private int requestCount = 0;
    private final @NonNull Lock lock = new ReentrantLock();

    public RealCache(final @NonNull Path directory,
                     final long maxSize,
                     final @NonNull TaskRunner taskRunner) {
        assert directory != null;
        assert taskRunner != null;

        cache = new DiskLruCache(
                directory,
                VERSION,
                ENTRY_COUNT,
                maxSize,
                taskRunner
        );
    }

    @Override
    public boolean isClosed() {
        return cache.isClosed();
    }

    @Nullable ClientResponse get(final @NonNull ClientRequest request) {
        assert request != null;

        final var key = key(request.getUrl());
        final DiskLruCache.Snapshot snapshot;
        try {
            snapshot = cache.get(key);
            if (snapshot == null) {
                return null;
            }
        } catch (JayoException ignored) {
            return null; // Give up because the cache cannot be read.
        }

        final Entry entry;
        try {
            entry = new Entry(snapshot.getRawReader(ENTRY_METADATA));
        } catch (JayoException ignored) {
            Utils.closeQuietly(snapshot);
            return null;
        }

        final var response = entry.response(snapshot);
        if (!entry.matches(request, response)) {
            Utils.closeQuietly(response.getBody());
            return null;
        }

        return response;
    }

    static @NonNull String key(final @NonNull HttpUrl url) {
        assert url != null;

        return ByteString.encode(url.toString())
                .hash(JdkDigest.MD5)
                .hex();
    }

    @Nullable CacheRequest put(final @NonNull ClientResponse response) {
        assert response != null;

        final var requestMethod = response.getRequest().getMethod();

        if (HttpMethodUtils.invalidatesCache(requestMethod)) {
            try {
                remove(response.getRequest());
            } catch (JayoException ignored) {
                // The cache cannot be written.
            }
            return null;
        }

        if (!requestMethod.equals("GET")) {
            // Don't cache non-GET responses. We're technically allowed to cache HEAD, QUERY and some POST requests, but
            // the complexity of doing so is high and the benefit is low.
            return null;
        }

        if (Cache.hasVaryAll(response)) {
            return null;
        }

        final var entry = new Entry(response);
        DiskLruCache.Editor editor = null;
        try {
            editor = cache.edit(key(response.getRequest().getUrl()));
            if (editor == null) {
                return null;
            }
            entry.writeTo(editor);
            return new RealCacheRequest(editor);
        } catch (JayoException ignored) {
            abortQuietly(editor);
            return null;
        }
    }

    void remove(final @NonNull ClientRequest request) {
        assert request != null;
        cache.remove(key(request.getUrl()));
    }

    void update(final @NonNull ClientResponse cached, final @NonNull ClientResponse network) {
        assert cached != null;
        assert network != null;

        final var entry = new Entry(network);
        final var snapshot = ((CacheResponseBody) cached.getBody()).snapshot;
        DiskLruCache.Editor editor = null;
        try {
            editor = snapshot.edit();
            if (editor == null) { // edit() returns null if snapshot is not current.
                return;
            }
            entry.writeTo(editor);
            editor.commit();
        } catch (JayoException ignored) {
            abortQuietly(editor);
        }
    }

    private static void abortQuietly(final DiskLruCache.@Nullable Editor editor) {
        // Give up because the cache cannot be written.
        try {
            if (editor != null) {
                editor.abort();
            }
        } catch (JayoException ignored) {
        }
    }

    @Override
    public void initialize() {
        cache.initialize();
    }

    @Override
    public void delete() {
        cache.delete();
    }

    @Override
    public void evictAll() {
        cache.evictAll();
    }

    @Override
    public @NonNull Iterator<@NonNull String> urls() {
        return new Iterator<>() {
            private final @NonNull Iterator<DiskLruCache.@NonNull Snapshot> delegate = cache.snapshots();
            private @Nullable String nextUrl = null;
            private boolean canRemove = false;

            @Override
            public boolean hasNext() {
                if (nextUrl != null) {
                    return true;
                }

                canRemove = false; // Prevent delegate.remove() on the wrong item!
                while (delegate.hasNext()) {
                    try (final var snapshot = delegate.next()) {
                        final var metadata = Jayo.buffer(snapshot.getRawReader(ENTRY_METADATA));
                        nextUrl = metadata.readLineStrict();
                        return true;
                    } catch (JayoException ignored) {
                        // We couldn't read the metadata for this snapshot, possibly because the host filesystem has
                        // disappeared! Skip it.
                    }
                }

                return false;
            }

            @Override
            public @NonNull String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                assert nextUrl != null;
                final var result = nextUrl;
                nextUrl = null;
                canRemove = true;
                return result;
            }

            @Override
            public void remove() {
                if (!canRemove) {
                    throw new IllegalStateException("remove() before next()");
                }
                delegate.remove();
            }
        };
    }

    @Override
    public int writeAbortCount() {
        lock.lock();
        try {
            return writeAbortCount;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int writeSuccessCount() {
        lock.lock();
        try {
            return writeSuccessCount;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long byteSize() {
        return cache.byteSize();
    }

    @Override
    public long maxByteSize() {
        return cache.getMaxByteSize();
    }

    @Override
    public void flush() {
        cache.flush();
    }

    @Override
    public void close() {
        cache.close();
    }

    @Override
    public @NonNull Path getDirectory() {
        return cache.directory.getPath();
    }

    void trackResponse(final @NonNull CacheStrategy cacheStrategy) {
        assert cacheStrategy != null;

        lock.lock();
        try {
            requestCount++;

            if (cacheStrategy.networkRequest != null) {
                // If this is a conditional request, we'll increment hitCount if/when it hits.
                networkCount++;
            } else if (cacheStrategy.cacheResponse != null) {
                // This response uses the cache and not the network. That's a cache hit.
                hitCount++;
            }
        } finally {
            lock.unlock();
        }
    }

    void trackConditionalCacheHit() {
        lock.lock();
        try {
            hitCount++;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int networkCount() {
        lock.lock();
        try {
            return networkCount;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int hitCount() {
        lock.lock();
        try {
            return hitCount;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int requestCount() {
        lock.lock();
        try {
            return requestCount;
        } finally {
            lock.unlock();
        }
    }

    final class RealCacheRequest implements CacheRequest {
        private final DiskLruCache.@NonNull Editor editor;
        private final @NonNull RawWriter cacheOut;
        private final @NonNull RawWriter body;
        private boolean done = false;

        private RealCacheRequest(final DiskLruCache.@NonNull Editor editor) {
            assert editor != null;

            this.editor = editor;
            this.cacheOut = editor.newRawWriter(ENTRY_BODY);

            this.body = new RawWriter() {
                @Override
                public void writeFrom(final @NonNull Buffer source, final long byteCount) {
                    cacheOut.writeFrom(source, byteCount);
                }

                @Override
                public void flush() {
                    cacheOut.flush();
                }

                @Override
                public void close() {
                    lock.lock();
                    try {
                        if (done) {
                            return;
                        }
                        done = true;
                        writeSuccessCount++;
                    } finally {
                        lock.unlock();
                    }
                    cacheOut.close();
                    editor.commit();
                }
            };
        }

        @Override
        public void abort() {
            lock.lock();
            try {
                if (done) {
                    return;
                }
                done = true;
                writeAbortCount++;
            } finally {
                lock.unlock();
            }
            Utils.closeQuietly(cacheOut);
            try {
                editor.abort();
            } catch (JayoException ignored) {
            }
        }

        @Override
        public @NonNull RawWriter body() {
            return body;
        }
    }

    private static class Entry {
        private final @NonNull HttpUrl url;
        private final @NonNull Headers varyHeaders;
        private final @NonNull String requestMethod;
        private final @NonNull Protocol protocol;
        private final int statusCode;
        private final @NonNull String statusMessage;
        private final @NonNull Headers responseHeaders;
        private final @Nullable Handshake handshake;
        private final long sentRequestMillis;
        private final long receivedResponseMillis;

        /**
         * Reads an entry from an input stream. A typical HTTP entry looks like this:
         * <pre>
         * {@code
         * http://google.com/foo
         * GET
         * 2
         * Accept-Language: fr-CA
         * Accept-Charset: UTF-8
         * HTTP/1.1 200 OK
         * 3
         * Content-Type: image/png
         * Content-Length: 100
         * Cache-Control: max-age=600
         * }
         * </pre>
         * <p>
         * A typical HTTPS file looks like this:
         * <pre>
         * {@code
         * https://google.com/foo
         * GET
         * 2
         * Accept-Language: fr-CA
         * Accept-Charset: UTF-8
         * HTTP/1.1 200 OK
         * 3
         * Content-Type: image/png
         * Content-Length: 100
         * Cache-Control: max-age=600
         *
         * AES_256_WITH_MD5
         * 2
         * base64-encoded peerCertificate[0]
         * base64-encoded peerCertificate[1]
         * -1
         * TLSv1.2
         * }
         * </pre>
         * The file is newline separated. The first two lines are the URL and the request method. Next is the number of
         * HTTP Vary request header lines, followed by those lines.
         * <p>
         * Next is the response status line, followed by the number of HTTP response header lines, followed by those
         * lines.
         * <p>
         * HTTPS responses also contain TLS session information. This begins with a blank line, and then a line
         * containing the cipher suite. Next is the length of the peer certificate chain. These certificates are
         * base64-encoded and appear each on their own line. The next line contains the length of the local certificate
         * chain. These certificates are also base64-encoded and appear each on their own line. A length of -1 is used
         * to encode a null array. The last line is optional. If present, it contains the TLS version.
         */
        private Entry(final @NonNull RawReader rawReader) {
            assert rawReader != null;

            try (final var reader = Jayo.buffer(rawReader)) {
                final var urlLine = reader.readLineStrict();
                // The choice here is between failing with a correct RuntimeException or mostly silently with a
                // JayoException
                final var parsedUrl = HttpUrl.parse(urlLine);
                if (parsedUrl == null) {
                    final var je = new JayoException("Cache corruption for " + urlLine);
                    LOGGER.log(WARNING, "Cache corruption", je);
                    throw je;
                }
                url = parsedUrl;
                requestMethod = reader.readLineStrict();
                final var varyHeadersBuilder = new RealHeaders.Builder();
                final var varyRequestHeaderLineCount = readInt(reader);
                for (var i = 0; i < varyRequestHeaderLineCount; i++) {
                    varyHeadersBuilder.addLenient(reader.readLineStrict());
                }
                varyHeaders = varyHeadersBuilder.build();

                final var statusLine = StatusLine.parse(reader.readLineStrict());
                protocol = statusLine.protocol;
                statusCode = statusLine.code;
                statusMessage = statusLine.message;
                final var responseHeadersBuilder = new RealHeaders.Builder();
                final var responseHeaderLineCount = readInt(reader);
                for (var i = 0; i < responseHeaderLineCount; i++) {
                    responseHeadersBuilder.addLenient(reader.readLineStrict());
                }
                final var sendRequestMillisString = responseHeadersBuilder.get(SENT_MILLIS);
                final var receivedResponseMillisString = responseHeadersBuilder.get(RECEIVED_MILLIS);
                responseHeadersBuilder.removeAll(SENT_MILLIS);
                responseHeadersBuilder.removeAll(RECEIVED_MILLIS);
                sentRequestMillis = (sendRequestMillisString != null) ? Long.parseLong(sendRequestMillisString) : 0L;
                receivedResponseMillis = (receivedResponseMillisString != null)
                        ? Long.parseLong(receivedResponseMillisString)
                        : 0L;
                responseHeaders = responseHeadersBuilder.build();

                if (url.isHttps()) {
                    final var blank = reader.readLineStrict();
                    if (!blank.isEmpty()) {
                        throw new JayoException("expected \"\" but was \"" + blank + "\"");
                    }
                    final var cipherSuiteString = reader.readLineStrict();
                    final var cipherSuite = CipherSuite.fromJavaName(cipherSuiteString);
                    final var peerCertificates = readCertificateList(reader);
                    final var localCertificates = readCertificateList(reader);
                    final var tlsVersion = (!reader.exhausted())
                            ? TlsVersion.fromJavaName(reader.readLineStrict())
                            : TlsVersion.SSL_3_0;
                    handshake = Handshake.get(protocol, tlsVersion, cipherSuite, localCertificates, peerCertificates);
                } else {
                    handshake = null;
                }
            }
        }

        private Entry(final @NonNull ClientResponse response) {
            assert response != null;

            this.url = response.getRequest().getUrl();
            this.varyHeaders = varyHeaders(response);
            this.requestMethod = response.getRequest().getMethod();
            this.protocol = response.getProtocol();
            this.statusCode = response.getStatusCode();
            this.statusMessage = response.getStatusMessage();
            this.responseHeaders = response.getHeaders();
            this.handshake = response.getHandshake();
            this.sentRequestMillis = response.getSentRequestAt().toEpochMilli();
            this.receivedResponseMillis = response.getReceivedResponseAt().toEpochMilli();
        }

        /**
         * @return the subset of the headers in this's request that impact the content of this's body.
         */
        private static @NonNull Headers varyHeaders(final @NonNull ClientResponse response) {
            assert response != null;

            // Use the request headers sent over the network, since that's what the response varies on.
            // Otherwise, Jayo HTTP-supplied headers like "Accept-Encoding: gzip" may be lost.
            assert response.getNetworkResponse() != null;
            final var requestHeaders = response.getNetworkResponse().getRequest().getHeaders();
            final var responseHeaders = response.getHeaders();
            return varyHeaders(requestHeaders, responseHeaders);
        }

        /**
         * @return the subset of the headers in {@code requestHeaders} that impact the content of the response's body.
         */
        private static @NonNull Headers varyHeaders(final @NonNull Headers requestHeaders,
                                                    final @NonNull Headers responseHeaders) {
            assert requestHeaders != null;
            assert responseHeaders != null;

            final var varyFields = varyFields(responseHeaders);
            if (varyFields.isEmpty()) {
                return Headers.EMPTY;
            }

            final var result = Headers.builder();
            for (var i = 0; i < requestHeaders.size(); i++) {
                final var fieldName = requestHeaders.name(i);
                if (varyFields.contains(fieldName)) {
                    result.add(fieldName, requestHeaders.value(i));
                }
            }
            return result.build();
        }

        private void writeTo(final DiskLruCache.@NonNull Editor editor) {
            assert editor != null;

            try (final var writer = Jayo.buffer(editor.newRawWriter(ENTRY_METADATA))) {
                writer.write(url.toString()).writeByte((byte) '\n')
                        .write(requestMethod).writeByte((byte) '\n')
                        .writeDecimalLong(varyHeaders.size()).writeByte((byte) '\n');
                for (var i = 0; i < varyHeaders.size(); i++) {
                    writer.write(varyHeaders.name(i))
                            .write(": ")
                            .write(varyHeaders.value(i))
                            .writeByte((byte) '\n');
                }

                writer.write(new StatusLine(protocol, statusCode, statusMessage).toString()).writeByte((byte) '\n');
                writer.writeDecimalLong((responseHeaders.size() + 2)).writeByte((byte) '\n');
                for (var i = 0; i < responseHeaders.size(); i++) {
                    writer.write(responseHeaders.name(i))
                            .write(": ")
                            .write(responseHeaders.value(i))
                            .writeByte((byte) '\n');
                }
                writer.write(SENT_MILLIS)
                        .write(": ")
                        .writeDecimalLong(sentRequestMillis)
                        .writeByte((byte) '\n');
                writer.write(RECEIVED_MILLIS)
                        .write(": ")
                        .writeDecimalLong(receivedResponseMillis)
                        .writeByte((byte) '\n');

                if (url.isHttps()) {
                    writer.writeByte((byte) '\n');
                    assert handshake != null;
                    writer.write(handshake.getCipherSuite().getJavaName()).writeByte((byte) '\n');
                    writeCertList(writer, handshake.getPeerCertificates());
                    writeCertList(writer, handshake.getLocalCertificates());
                    writer.write(handshake.getTlsVersion().getJavaName()).writeByte((byte) '\n');
                }
            }
        }

        private @NonNull List<@NonNull Certificate> readCertificateList(final @NonNull Reader reader) {
            assert reader != null;

            final var length = readInt(reader);
            if (length == -1) {
                return List.of();
            }

            try {
                final var certificateFactory = CertificateFactory.getInstance("X.509");
                final var result = new ArrayList<Certificate>(length);
                for (var i = 0; i < length; i++) {
                    final var line = reader.readLineStrict();
                    final var bytes = Buffer.create();
                    final var certificateBytes = ByteString.decodeBase64(line);
                    if (certificateBytes == null) {
                        throw new JayoException("Corrupt certificate in cache entry");
                    }
                    bytes.write(certificateBytes);
                    result.add(certificateFactory.generateCertificate(bytes.asInputStream()));
                }
                return result;
            } catch (CertificateException e) {
                throw new JayoException(e.getMessage());
            }
        }

        private void writeCertList(final @NonNull Writer writer,
                                   final @NonNull List<@NonNull Certificate> certificates) {
            assert writer != null;
            assert certificates != null;

            writer.writeDecimalLong(certificates.size()).writeByte((byte) '\n');
            for (final var certificate : certificates) {
                try {
                    final var bytes = certificate.getEncoded();
                    final var line = ByteString.of(bytes).base64();
                    writer.write(line).writeByte((byte) '\n');
                } catch (CertificateEncodingException e) {
                    throw new JayoException(e.getMessage());
                }
            }
        }

        private boolean matches(final @NonNull ClientRequest request, final @NonNull ClientResponse response) {
            return url.equals(request.getUrl())
                    && requestMethod.equals(request.getMethod())
                    && Cache.varyMatches(response, varyHeaders, request);
        }

        private @NonNull ClientResponse response(final DiskLruCache.@NonNull Snapshot snapshot) {
            final var contentType = responseHeaders.get("Content-Type");
            final var contentByteSize = responseHeaders.get("Content-Length");
            final var cacheRequest = ClientRequest.builder()
                    .url(url)
                    .headers(varyHeaders)
                    .method(requestMethod, null);
            return ClientResponse.builder()
                    .request(cacheRequest)
                    .protocol(protocol)
                    .statusCode(statusCode)
                    .statusMessage(statusMessage)
                    .headers(responseHeaders)
                    .body(new CacheResponseBody(snapshot, contentType, contentByteSize))
                    .handshake(handshake)
                    .sentRequestAt(Instant.ofEpochMilli(sentRequestMillis))
                    .receivedResponseAt(Instant.ofEpochMilli(receivedResponseMillis))
                    .build();
        }

        /**
         * Synthetic response header: the local time when the request was sent.
         */
        private static final @NonNull String SENT_MILLIS = PREFIX + "-Sent-Millis";
        /**
         * Synthetic response header: the local time when the response was received.
         */
        private static final @NonNull String RECEIVED_MILLIS = PREFIX + "-Received-Millis";

        private static int readInt(final @NonNull Reader reader) {
            assert reader != null;

            final var result = reader.readDecimalLong();
            final var line = reader.readLineStrict();
            if (result < 0L || result > Integer.MAX_VALUE || !line.isEmpty()) {
                throw new JayoException("expected an int but was \"" + result + line + "\"");
            }
            return (int) result;
        }
    }

    private static class CacheResponseBody extends ClientResponseBody {
        private final DiskLruCache.@NonNull Snapshot snapshot;
        private final @Nullable String contentType;
        private final @Nullable String contentByteSize;
        private final Reader bodyReader;

        private CacheResponseBody(final DiskLruCache.@NonNull Snapshot snapshot,
                                  final @Nullable String contentType,
                                  final @Nullable String contentByteSize) {
            assert snapshot != null;

            this.snapshot = snapshot;
            this.contentType = contentType;
            this.contentByteSize = contentByteSize;
            final var reader = snapshot.getRawReader(ENTRY_BODY);
            bodyReader = Jayo.buffer(new RawReader() {
                @Override
                public long readAtMostTo(final @NonNull Buffer destination, final long byteCount) {
                    return reader.readAtMostTo(destination, byteCount);
                }

                @Override
                public void close() {
                    snapshot.close();
                    reader.close();
                }
            });
        }

        @Override
        public @Nullable MediaType contentType() {
            return (contentType != null) ? MediaType.parse(contentType) : null;
        }

        @Override
        public long contentByteSize() {
            return (contentByteSize != null) ? Long.parseLong(contentByteSize) : -1L;
        }

        @Override
        public @NonNull Reader reader() {
            return bodyReader;
        }
    }

    /**
     * @return the names of the request headers that need to be checked for equality when caching.
     */
    public static @NonNull Set<@NonNull String> varyFields(final @NonNull Headers headers) {
        Set<String> result = null;
        for (var i = 0; i < headers.size(); i++) {
            if (!"Vary".equalsIgnoreCase(headers.name(i))) {
                continue;
            }

            final var value = headers.value(i);
            if (result == null) {
                result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            }
            for (final var varyField : value.split(",")) {
                result.add(varyField.trim());
            }
        }
        return (result != null) ? result : Set.of();
    }
}
