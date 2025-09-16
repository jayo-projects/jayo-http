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

import jayo.*;
import jayo.http.*;
import jayo.http.http2.ErrorCode;
import jayo.http.internal.RealClientResponse;
import jayo.http.internal.RealHeaders;
import jayo.http.internal.Utils;
import jayo.http.internal.http.ExchangeCodec;
import jayo.http.internal.http.RequestLine;
import jayo.http.internal.http.StatusLine;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static jayo.http.internal.http.HttpHeaders.promisesBody;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_CONTINUE;
import static jayo.http.internal.http2.RealBinaryHeader.*;

/**
 * Encode requests and responses using HTTP/2 frames.
 */
public final class Http2ExchangeCodec implements ExchangeCodec {
    private final @NonNull JayoHttpClient client;
    private final @NonNull Carrier carrier;
    private final @NonNull Http2Connection http2Connection;

    private volatile @Nullable Http2Stream stream = null;

    private final Protocol protocol;

    private volatile boolean canceled = false;

    public Http2ExchangeCodec(final @NonNull JayoHttpClient client,
                              final @NonNull Carrier carrier,
                              final @NonNull Http2Connection http2Connection) {
        assert client != null;
        assert carrier != null;
        assert http2Connection != null;

        this.client = client;
        this.carrier = carrier;
        this.http2Connection = http2Connection;
        this.protocol = client.getProtocols().contains(Protocol.H2_PRIOR_KNOWLEDGE)
                ? Protocol.H2_PRIOR_KNOWLEDGE
                : Protocol.HTTP_2;
    }

    @Override
    public @NonNull Carrier getCarrier() {
        return carrier;
    }

    @Override
    public boolean isResponseComplete() {
        final var currentStream = stream;
        return currentStream != null && currentStream.isSourceComplete();
    }

    @Override
    public @NonNull RawSocket getSocket() {
        final var currentStream = stream;
        assert currentStream != null;
        return currentStream;
    }

    @Override
    public @NonNull RawWriter createRequestBody(final @NonNull ClientRequest request, final long contentLength) {
        assert request != null;

        final var currentStream = stream;
        assert currentStream != null;
        return currentStream.writer;
    }

    @Override
    public void writeRequestHeaders(final @NonNull ClientRequest request) {
        assert request != null;

        if (stream != null) {
            return;
        }

        final var hasRequestBody = (request.getBody() != null);
        final var requestHeaders = http2HeadersList(request);
        final var newStream = http2Connection.newStream(requestHeaders, hasRequestBody);
        stream = newStream;

        // We may have been asked to cancel while creating the new stream and sending the request headers, but there was
        // still no stream to close.
        if (canceled) {
            newStream.closeLater(ErrorCode.CANCEL);
            throw new JayoException("Canceled");
        }
        newStream.readTimeoutNanos = client.getReadTimeout().toNanos();
        newStream.writeTimeoutNanos = client.getWriteTimeout().toNanos();
    }

    @Override
    public void flushRequest() {
        http2Connection.flush();
    }

    @Override
    public void finishRequest() {
        final var currentStream = stream;
        assert currentStream != null;
        currentStream.writer.close();
    }

    @Override
    public ClientResponse.@Nullable Builder readResponseHeaders(final boolean expectContinue) {
        final var currentStream = stream;
        if (currentStream == null) {
            throw new JayoException("stream wasn't created");
        }

        final var headers = currentStream.takeHeaders(expectContinue);
        final var responseBuilder = readHttp2HeadersList(headers, protocol);
        return (expectContinue && responseBuilder.code() == HTTP_CONTINUE) ? null : responseBuilder;
    }

    @Override
    public long reportedContentByteSize(final @NonNull ClientResponse response) {
        assert response != null;

        if (!promisesBody(response)) {
            return 0L;
        }
        return Utils.headersContentLength(response);
    }

    @Override
    public @NonNull RawReader openResponseBodyReader(final @NonNull ClientResponse response) {
        final var currentStream = stream;
        assert currentStream != null;
        return currentStream.reader;
    }

    @Override
    public @Nullable Headers peekTrailers() {
        final var currentStream = stream;
        assert currentStream != null;
        return currentStream.peekTrailers();
    }

    @Override
    public void cancel() {
        canceled = true;
        final var currentStream = stream;
        if (currentStream != null) {
            currentStream.closeLater(ErrorCode.CANCEL);
        }
    }

    private static final @NonNull String CONNECTION = "connection";
    private static final @NonNull String HOST = "host";
    private static final @NonNull String KEEP_ALIVE = "keep-alive";
    private static final @NonNull String PROXY_CONNECTION = "proxy-connection";
    private static final @NonNull String TRANSFER_ENCODING = "transfer-encoding";
    private static final @NonNull String TE = "te";
    private static final @NonNull String ENCODING = "encoding";
    private static final @NonNull String UPGRADE = "upgrade";

    /**
     * See <a href="https://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3">HTTP/2 Headers spec</a>.
     */
    private static final @NonNull List<@NonNull String> HTTP_2_SKIPPED_REQUEST_HEADERS = List.of(
            CONNECTION,
            HOST,
            KEEP_ALIVE,
            PROXY_CONNECTION,
            TE,
            TRANSFER_ENCODING,
            ENCODING,
            UPGRADE,
            TARGET_METHOD_UTF8,
            TARGET_PATH_UTF8,
            TARGET_SCHEME_UTF8,
            TARGET_AUTHORITY_UTF8
    );

    private static final @NonNull List<@NonNull String> HTTP_2_SKIPPED_RESPONSE_HEADERS = List.of(
            CONNECTION,
            HOST,
            KEEP_ALIVE,
            PROXY_CONNECTION,
            TE,
            TRANSFER_ENCODING,
            ENCODING,
            UPGRADE
    );

    public static @NonNull List<@NonNull RealBinaryHeader> http2HeadersList(final @NonNull ClientRequest request) {
        assert request != null;

        final var headers = request.getHeaders();
        final var result = new ArrayList<RealBinaryHeader>(headers.size() + 4);
        result.add(new RealBinaryHeader(TARGET_METHOD, request.getMethod()));
        result.add(new RealBinaryHeader(TARGET_PATH, RequestLine.requestPath(request.getUrl())));

        final var host = request.header("Host");
        if (host != null) {
            result.add(new RealBinaryHeader(TARGET_AUTHORITY, host)); // Optional.
        }

        result.add(new RealBinaryHeader(TARGET_SCHEME, request.getUrl().getScheme()));

        for (var i = 0; i < headers.size(); i++) {
            // header names must be lowercase.
            final var name = headers.name(i).toLowerCase(Locale.US);
            if (!HTTP_2_SKIPPED_REQUEST_HEADERS.contains(name) ||
                    (TE.equals(name) && "trailers".equals(headers.value(i)))) {
                result.add(new RealBinaryHeader(name, headers.value(i)));
            }
        }
        return result;
    }

    /**
     * Returns headers for a name value block containing an HTTP/2 response.
     */
    public static RealClientResponse.@NonNull Builder readHttp2HeadersList(final @NonNull Headers headerBlock,
                                                                           final @NonNull Protocol protocol) {
        assert headerBlock != null;
        assert protocol != null;

        StatusLine statusLine = null;
        final var headersBuilder = new RealHeaders.Builder();
        for (var i = 0; i < headerBlock.size(); i++) {
            final var name = headerBlock.name(i);
            final var value = headerBlock.value(i);
            if (RESPONSE_STATUS_UTF8.equals(name)) {
                statusLine = StatusLine.parse("HTTP/1.1 " + value);
            } else if (!HTTP_2_SKIPPED_RESPONSE_HEADERS.contains(name)) {
                headersBuilder.addLenient(name, value);
            }
        }

        if (statusLine == null) {
            throw new JayoProtocolException("Expected ':status' header not present");
        }

        return new RealClientResponse.Builder()
                .protocol(protocol)
                .code(statusLine.code)
                .message(statusLine.message)
                .headers(headersBuilder.build());
    }
}
