/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2012 The Android Open Source Project
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

package jayo.http.internal.http1;

import jayo.*;
import jayo.http.*;
import jayo.http.internal.http.ExchangeCodec;
import jayo.http.internal.http.RequestLine;
import jayo.http.internal.http.StatusLine;
import jayo.network.Proxy;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static java.net.HttpURLConnection.HTTP_OK;
import static jayo.http.internal.Utils.*;
import static jayo.http.internal.http.HttpHeaders.promisesBody;
import static jayo.http.internal.http.HttpHeaders.receiveHeaders;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_CONTINUE;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_PROCESSING;
import static jayo.tools.JayoUtils.checkOffsetAndCount;

/**
 * A socket connection that can be used to send HTTP/1.1 messages. This class strictly enforces the
 * following lifecycle:
 * <ol>
 * <li>{@linkplain #writeRequest Send request headers}.
 * <li>Open a writer to write the request body. Either {@linkplain #newKnownLengthWriter known} or
 * {@linkplain #newChunkedWriter chunked}.
 * <li>Write to and then close that writer.
 * <li>{@linkplain #readResponseHeaders(boolean) Read response headers}.
 * <li>Open a reader to read the response body. Either {@linkplain #newFixedLengthReader fixed-length},
 * {@linkplain #newChunkedReader chunked} or {@linkplain #newUnknownLengthReader unknown}.
 * <li>Read from and close that reader.
 * </ol>
 * Exchanges that do not have a request body may skip creating and closing the request body writer. Exchanges that do
 * not have a response body can call {@linkplain #newFixedLengthReader newFixedLengthReader(0)} and may skip reading and
 * closing that reader.
 */
public final class Http1ExchangeCodec implements ExchangeCodec {
    private static final long NO_CHUNK_YET = -1L;

    private static final int STATE_IDLE = 0; // Idle connections are ready to write request headers.
    private static final int STATE_OPEN_REQUEST_BODY = 1;
    private static final int STATE_WRITING_REQUEST_BODY = 2;
    private static final int STATE_READ_RESPONSE_HEADERS = 3;
    private static final int STATE_OPEN_RESPONSE_BODY = 4;
    private static final int STATE_READING_RESPONSE_BODY = 5;
    private static final int STATE_CLOSED = 6;

    private static final @NonNull Headers TRAILERS_RESPONSE_BODY_TRUNCATED =
            Headers.of("JayoHttp-Response-Body", "Truncated");

    private final @Nullable JayoHttpClient client;
    private final @NonNull Carrier carrier;
    private final @NonNull Endpoint endpoint;

    private int state = STATE_IDLE;
    private final @NonNull HeadersReader headersReader;

    /**
     * Trailers received when the response body became exhausted.
     * <p>
     * If the response body was successfully read until the end, this is the headers that followed, or empty headers if
     * there were none that followed.
     * <p>
     * If the response body was closed prematurely or failed with an error, this will be the sentinel value
     * {@link #TRAILERS_RESPONSE_BODY_TRUNCATED}. In that case, attempts to read the trailers should not return the
     * value but instead throw an exception.
     */
    private @Nullable Headers trailers = null;

    /**
     * @param client The client that configures this stream. It may be null for HTTPS proxy tunnels.
     */
    public Http1ExchangeCodec(final @Nullable JayoHttpClient client,
                              final @NonNull Carrier carrier,
                              final @NonNull Endpoint endpoint) {
        assert carrier != null;
        assert endpoint != null;

        this.client = client;
        this.carrier = carrier;
        this.endpoint = endpoint;

        headersReader = new HeadersReader(endpoint.getReader());
    }

    @Override
    public boolean isResponseComplete() {
        return state == STATE_CLOSED;
    }

    @Override
    public @NonNull RawWriter createRequestBody(final @NonNull ClientRequest request, final long contentLength) {
        assert request != null;

        if (request.getBody() != null && request.getBody().isDuplex()) {
            throw new JayoProtocolException("Duplex connections are not supported for HTTP/1");
        }

        // Stream a request body of unknown length.
        if (isChunked(request)) {
            return newChunkedWriter();
        }

        // Stream a request body of a known length.
        if (contentLength != -1L) {
            return newKnownLengthWriter();
        }

        throw new IllegalStateException(
                "Cannot stream a request body without chunked encoding or a known content length!");
    }

    private @NonNull RawWriter newChunkedWriter() {
        if (state != STATE_OPEN_REQUEST_BODY) {
            throw new IllegalStateException("state: " + state);
        }
        state = STATE_WRITING_REQUEST_BODY;
        return new ChunkedRawWriter();
    }

    private @NonNull RawWriter newKnownLengthWriter() {
        if (state != STATE_OPEN_REQUEST_BODY) {
            throw new IllegalStateException("state: " + state);
        }
        state = STATE_WRITING_REQUEST_BODY;
        return new KnownLengthRawWriter();
    }

    /**
     * Prepares the HTTP headers and sends them to the server.
     * <p>
     * For streaming requests with a body, headers must be prepared <b>before</b> the output stream has been written to.
     * Otherwise, the body would need to be buffered!
     * <p>
     * For non-streaming requests with a body, headers must be prepared <b>after</b> the output stream has been written
     * to and closed. This ensures that the {@code Content-Length} header field receives the proper value.
     */
    @Override
    public void writeRequestHeaders(final @NonNull ClientRequest request) {
        assert request != null;

        final var requestLine = RequestLine.get(request, carrier.route().getAddress().getProxy() instanceof Proxy.Http);
        writeRequest(request.getHeaders(), requestLine);
    }

    @Override
    public long reportedContentByteSize(final @NonNull ClientResponse response) {
        assert response != null;

        if (!promisesBody(response)) {
            return 0L;
        }
        if (isChunked(response)) {
            return -1L;
        }
        return headersContentLength(response);
    }

    @Override
    public @NonNull RawReader openResponseBodyReader(final @NonNull ClientResponse response) {
        assert response != null;

        if (!promisesBody(response)) {
            return newFixedLengthReader(response.getRequest().getUrl(), 0L);
        }
        if (isChunked(response)) {
            return newChunkedReader(response.getRequest().getUrl());
        }

        final var contentLength = headersContentLength(response);
        if (contentLength != -1L) {
            return newFixedLengthReader(response.getRequest().getUrl(), contentLength);
        }
        return newUnknownLengthReader(response.getRequest().getUrl());
    }

    private RawReader newFixedLengthReader(final @NonNull HttpUrl url, final long length) {
        assert url != null;

        if (state != STATE_OPEN_RESPONSE_BODY) {
            throw new IllegalStateException("state: " + state);
        }
        state = STATE_READING_RESPONSE_BODY;
        return new FixedLengthRawReader(url, length);
    }

    private RawReader newChunkedReader(final @NonNull HttpUrl url) {
        assert url != null;

        if (state != STATE_OPEN_RESPONSE_BODY) {
            throw new IllegalStateException("state: " + state);
        }
        state = STATE_READING_RESPONSE_BODY;
        return new ChunkedRawReader(url);
    }

    private RawReader newUnknownLengthReader(final @NonNull HttpUrl url) {
        assert url != null;

        if (state != STATE_OPEN_RESPONSE_BODY) {
            throw new IllegalStateException("state: " + state);
        }
        state = STATE_READING_RESPONSE_BODY;
        carrier.noNewExchanges();
        return new UnknownLengthRawReader(url);
    }

    @Override
    public @Nullable Headers peekTrailers() {
        if (trailers == TRAILERS_RESPONSE_BODY_TRUNCATED) {
            throw new JayoException("Trailers cannot be read because the response body was truncated");
        }
        if (state != STATE_READING_RESPONSE_BODY && state != STATE_CLOSED) {
            throw new IllegalStateException("Trailers cannot be read because the state is " + state);
        }
        return trailers;
    }

    @Override
    public void flushRequest() {
        endpoint.getWriter().flush();
    }

    @Override
    public void finishRequest() {
        endpoint.getWriter().flush();
    }

    /**
     * Write bytes of a request header for sending on an HTTP transport.
     */
    public void writeRequest(final @NonNull Headers headers, final @NonNull String requestLine) {
        assert headers != null;
        assert requestLine != null;

        if (state != STATE_IDLE) {
            throw new IllegalStateException("state: " + state);
        }
        endpoint.getWriter().write(requestLine).write("\r\n");
        for (final var header : headers) {
            endpoint.getWriter()
                    .write(header.name())
                    .write(": ")
                    .write(header.value())
                    .write("\r\n");
        }
        endpoint.getWriter().write("\r\n");
        state = STATE_OPEN_REQUEST_BODY;
    }

    @Override
    public ClientResponse.@Nullable Builder readResponseHeaders(final boolean expectContinue) {
        if (state != STATE_IDLE &&
                state != STATE_OPEN_REQUEST_BODY &&
                state != STATE_WRITING_REQUEST_BODY &&
                state != STATE_READ_RESPONSE_HEADERS) {
            throw new IllegalStateException("state: " + state);
        }

        try {
            final var statusLine = StatusLine.parse(headersReader.readLine());

            final var responseBuilder = ClientResponse.builder()
                    .protocol(statusLine.protocol)
                    .code(statusLine.code)
                    .message(statusLine.message)
                    .headers(headersReader.readHeaders());

            if (expectContinue && statusLine.code == HTTP_CONTINUE) {
                return null;
            }
            if (statusLine.code == HTTP_CONTINUE
                    // Processing and Early Hints will mean a second Headers is coming. Treat others the same for now.
                    || (statusLine.code >= HTTP_PROCESSING && statusLine.code < HTTP_OK)) {
                state = STATE_READ_RESPONSE_HEADERS;
                return responseBuilder;
            }

            state = STATE_OPEN_RESPONSE_BODY;
            return responseBuilder;
        } catch (JayoEOFException e) {
            // Provide more context if the server ends the stream before sending a response.
            final var address = carrier.route().getAddress().getUrl()
                    .redact();
            throw new JayoException("unexpected end of stream on " + address, e.getCause());
        }
    }

    /**
     * The response body from a CONNECT should be empty, but if it is not, then we should consume it before proceeding.
     */
    public void skipConnectBody(final @NonNull ClientResponse response) {
        assert response != null;

        final var contentLength = headersContentLength(response);
        if (contentLength == -1L) {
            return;
        }
        final var body = newFixedLengthReader(response.getRequest().getUrl(), contentLength);
        skipAll(body, Duration.ofMillis(Integer.MAX_VALUE));
        body.close();
    }

    @Override
    public @NonNull Carrier getCarrier() {
        return carrier;
    }

    @Override
    public void cancel() {
        carrier.cancel();
    }

    private static boolean isChunked(final @NonNull ClientResponse response) {
        assert response != null;
        return "chunked".equalsIgnoreCase(response.header("Transfer-Encoding"));
    }

    private static boolean isChunked(final @NonNull ClientRequest request) {
        assert request != null;
        return "chunked".equalsIgnoreCase(request.header("Transfer-Encoding"));
    }

    /**
     * An HTTP body with alternating chunk sizes and chunk bodies. It is the caller's responsibility to buffer chunks;
     * typically by using a buffered writer with this writer.
     */
    private final class ChunkedRawWriter implements RawWriter {
        private boolean closed = false;

        @Override
        public void writeFrom(final @NonNull Buffer reader, long byteCount) {
            assert reader != null;
            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (byteCount < 0) {
                return;
            }

            endpoint.getWriter().writeHexadecimalUnsignedLong(byteCount)
            .write("\r\n")
            .writeFrom(reader, byteCount);
            endpoint.getWriter().write("\r\n");
        }

        @Override
        public void flush() {
            if (closed) {
                return; // Don't throw; this stream might have been closed on the caller's behalf.
            }
            endpoint.getWriter().flush();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            endpoint.getWriter().write("0\r\n\r\n");
            state = STATE_READ_RESPONSE_HEADERS;
        }
    }

    /**
     * An HTTP request body.
     */
    private final class KnownLengthRawWriter implements RawWriter {
        private boolean closed = false;

        @Override
        public void writeFrom(final @NonNull Buffer reader, long byteCount) {
            assert reader != null;
            if (closed) {
                throw new JayoClosedResourceException();
            }
            checkOffsetAndCount(reader.bytesAvailable(), 0L, byteCount);
            endpoint.getWriter().writeFrom(reader, byteCount);
        }

        @Override
        public void flush() {
            if (closed) {
                return; // Don't throw; this stream might have been closed on the caller's behalf.
            }
            endpoint.getWriter().flush();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            state = STATE_READ_RESPONSE_HEADERS;
        }
    }

    private abstract class AbstractRawReader implements RawReader {
        private final @NonNull HttpUrl url;
        boolean closed = false;

        private AbstractRawReader(final @NonNull HttpUrl url) {
            assert url != null;
            this.url = url;
        }

        long readAtMostToInternal(final @NonNull Buffer writer, final long byteCount) {
            assert writer != null;

            try {
                return endpoint.getReader().readAtMostTo(writer, byteCount);
            } catch (JayoException e) {
                carrier.noNewExchanges();
                responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED);
                throw e;
            }
        }

        /**
         * Closes the cache entry and makes the socket available for reuse. This should be invoked when the end of the
         * body has been reached.
         */
        void responseBodyComplete(final @NonNull Headers trailers) {
            assert trailers != null;

            if (state == STATE_CLOSED) {
                return;
            }
            if (state != STATE_READING_RESPONSE_BODY) {
                throw new IllegalStateException("state: " + state);
            }

            Http1ExchangeCodec.this.trailers = trailers;
            state = STATE_CLOSED;
            if (!trailers.isEmpty() && client != null) {
                receiveHeaders(client.getCookieJar(), url, trailers);
            }
        }
    }

    /**
     * An HTTP body with a fixed length specified in advance.
     */
    private class FixedLengthRawReader extends AbstractRawReader {
        private long bytesRemaining;

        private FixedLengthRawReader(final @NonNull HttpUrl url, final long bytesRemaining) {
            super(url);
            this.bytesRemaining = bytesRemaining;
            if (bytesRemaining == 0L) {
                responseBodyComplete(Headers.EMPTY);
            }
        }

        @Override
        public long readAtMostTo(final @NonNull Buffer writer, final long byteCount) {
            assert writer != null;
            assert byteCount >= 0L;
            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (bytesRemaining == 0L) {
                return -1L;
            }

            final var read = readAtMostToInternal(writer, Math.min(bytesRemaining, byteCount));
            if (read == -1L) {
                carrier.noNewExchanges(); // The server didn't supply the promised content length.
                final var e = new JayoProtocolException("unexpected end of stream");
                responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED);
                throw e;
            }

            bytesRemaining -= read;
            if (bytesRemaining == 0L) {
                responseBodyComplete(Headers.EMPTY);
            }
            return read;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            if (bytesRemaining != 0L && !discard(this, DISCARD_STREAM_TIMEOUT)) {
                carrier.noNewExchanges(); // Unread bytes remain on the stream.
                responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED);
            }
            closed = true;
        }
    }

    /**
     * An HTTP body with alternating chunk sizes and chunk bodies.
     */
    private class ChunkedRawReader extends AbstractRawReader {
        private long bytesRemainingInChunk = NO_CHUNK_YET;
        private boolean hasMoreChunks = true;

        private ChunkedRawReader(final @NonNull HttpUrl url) {
            super(url);
        }

        @Override
        public long readAtMostTo(final @NonNull Buffer writer, final long byteCount) {
            assert writer != null;
            assert byteCount >= 0L;
            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (!hasMoreChunks) {
                return -1L;
            }

            if (bytesRemainingInChunk == 0L || bytesRemainingInChunk == NO_CHUNK_YET) {
                readChunkSize();
                if (!hasMoreChunks) {
                    return -1L;
                }
            }

            final var read = readAtMostToInternal(writer, Math.min(bytesRemainingInChunk, byteCount));
            if (read == -1L) {
                carrier.noNewExchanges(); // The server didn't supply the promised chunk length.
                final var e = new JayoProtocolException("unexpected end of stream");
                responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED);
                throw e;
            }

            bytesRemainingInChunk -= read;
            return read;
        }

        private void readChunkSize() {
            // Read the suffix of the previous chunk.
            if (bytesRemainingInChunk != NO_CHUNK_YET) {
                endpoint.getReader().readLineStrict(StandardCharsets.ISO_8859_1);
            }
            try {
                bytesRemainingInChunk = endpoint.getReader().readHexadecimalUnsignedLong();
                String extensions = endpoint.getReader().readLineStrict(StandardCharsets.ISO_8859_1).strip();
                if (bytesRemainingInChunk < 0L || (!extensions.isEmpty() && !extensions.startsWith(";"))) {
                    throw new JayoProtocolException(
                            "expected chunk size and optional extensions" +
                                    " but was \"" + bytesRemainingInChunk + extensions + "\""
                    );
                }
            } catch (NumberFormatException e) {
                throw new JayoProtocolException(e.getMessage());
            }

            if (bytesRemainingInChunk == 0L) {
                hasMoreChunks = false;
                final var trailers = headersReader.readHeaders();
                responseBodyComplete(trailers);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            if (hasMoreChunks && !discard(this, DISCARD_STREAM_TIMEOUT)) {
                carrier.noNewExchanges(); // Unread bytes remain on the stream.
                responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED);
            }
            closed = true;
        }
    }

    /**
     * An HTTP message body terminated by the end of the underlying stream.
     */
    private class UnknownLengthRawReader extends AbstractRawReader {
        private boolean inputExhausted = false;

        public UnknownLengthRawReader(final @NonNull HttpUrl url) {
            super(url);
        }

        @Override
        public long readAtMostTo(final @NonNull Buffer writer, final long byteCount) {
            assert writer != null;
            assert byteCount >= 0L;
            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (inputExhausted) {
                return -1L;
            }

            final var read = readAtMostToInternal(writer, byteCount);
            if (read == -1L) {
                inputExhausted = true;
                responseBodyComplete(Headers.EMPTY);
                return -1L;
            }
            return read;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            if (!inputExhausted) {
                responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED);
            }
            closed = true;
        }
    }
}
