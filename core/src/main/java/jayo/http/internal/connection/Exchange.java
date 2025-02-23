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

package jayo.http.internal.connection;

import jayo.*;
import jayo.http.*;
import jayo.http.internal.RealClientResponse;
import jayo.http.internal.StandardClientResponseBodies;
import jayo.http.internal.http.ExchangeCodec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Transmits a single HTTP request and a response pair. This layers connection management and events on
 * {@link ExchangeCodec}, which handles the actual I/O.
 */
public final class Exchange {
    private final @NonNull RealCall call;
    private final @NonNull EventListener eventListener;
    final @NonNull ExchangeFinder finder;
    private final @NonNull ExchangeCodec codec;

    /**
     * True if the request body need not complete before the response body starts.
     */
    boolean isDuplex = false;

    /**
     * True if there was an exception on the connection to the peer.
     */
    boolean hasFailure = false;

    Exchange(final @NonNull RealCall call,
             final @NonNull EventListener eventListener,
             final @NonNull ExchangeFinder finder,
             final @NonNull ExchangeCodec codec) {
        assert call != null;
        assert eventListener != null;
        assert finder != null;
        assert codec != null;

        this.call = call;
        this.eventListener = eventListener;
        this.finder = finder;
        this.codec = codec;
    }

    public @NonNull RealConnection connection() {
        if (codec.getCarrier() instanceof RealConnection connection) {
            return connection;
        }
        throw new IllegalStateException("no connection for CONNECT tunnels");
    }

    public boolean isCoalescedConnection() {
        return !finder.routePlanner().getAddress().getUrl().getHost().equals(
                codec.getCarrier().route().getAddress().getUrl().getHost());
    }

    void writeRequestHeaders(final @NonNull ClientRequest request) {
        assert request != null;

        try {
            eventListener.requestHeadersStart(call);
            codec.writeRequestHeaders(request);
            eventListener.requestHeadersEnd(call, request);
        } catch (JayoException e) {
            eventListener.requestFailed(call, e);
            trackFailure(e);
            throw e;
        }
    }

    @NonNull
    RawWriter createRequestBody(final @NonNull ClientRequest request, boolean duplex) {
        assert request != null;

        this.isDuplex = duplex;
        assert request.getBody() != null;
        final var contentLength = request.getBody().contentByteSize();
        eventListener.requestBodyStart(call);
        final var rawRequestBody = codec.createRequestBody(request, contentLength);
        return new RequestBodyRawWriter(rawRequestBody, contentLength);
    }

    void flushRequest() {
        try {
            codec.flushRequest();
        } catch (JayoException e) {
            eventListener.requestFailed(call, e);
            trackFailure(e);
            throw e;
        }
    }

    public void finishRequest() {
        try {
            codec.finishRequest();
        } catch (JayoException e) {
            eventListener.requestFailed(call, e);
            trackFailure(e);
            throw e;
        }
    }

    void responseHeadersStart() {
        eventListener.responseHeadersStart(call);
    }

    RealClientResponse.@Nullable Builder readResponseHeaders(boolean expectContinue) {
        try {
            final var result = (RealClientResponse.@Nullable Builder) codec.readResponseHeaders(expectContinue);
            if (result != null) {
                result.initExchange(this);
            }
            return result;
        } catch (JayoException e) {
            eventListener.responseFailed(call, e);
            trackFailure(e);
            throw e;
        }
    }

    void responseHeadersEnd(final @NonNull ClientResponse response) {
        assert response != null;
        eventListener.responseHeadersEnd(call, response);
    }

    @NonNull
    ClientResponseBody openResponseBody(final @NonNull ClientResponse response) {
        try {
            final var contentType = response.header("Content-Type");
            final var contentLength = codec.reportedContentByteSize(response);
            final var rawReader = codec.openResponseBodyReader(response);
            final var reader = new ResponseBodyRawReader(rawReader, contentLength);
            return StandardClientResponseBodies.create(Jayo.buffer(reader), contentType, contentLength);
        } catch (JayoException e) {
            eventListener.responseFailed(call, e);
            trackFailure(e);
            throw e;
        }
    }

    public @Nullable Headers peekTrailers() {
        return codec.peekTrailers();
    }

//    RealWebSocket.Streams newWebSocketStreams() { todo websockets
//        call.timeoutEarlyExit();
//        return ((RealConnection) codec.getCarrier()).newWebSocketStreams(this);
//    }
//
//    void webSocketUpgradeFailed() {
//        bodyComplete(-1L, true, true, null);
//    }

    void noNewExchangesOnConnection() {
        codec.getCarrier().noNewExchanges();
    }

    public void cancel() {
        codec.cancel();
    }

    /**
     * Revoke this exchange's access to streams. This is necessary when a follow-up request is required, but the
     * preceding exchange hasn't completed yet.
     */
    public void detachWithViolence() {
        codec.cancel();
        call.messageDone(this, true, true, null);
    }

    private void trackFailure(final @NonNull JayoException e) {
        hasFailure = true;
        codec.getCarrier().trackFailure(call, e);
    }

    <E extends JayoException> E bodyComplete(final long bytesRead, boolean responseDone, boolean requestDone, E e) {
        if (e != null) {
            trackFailure(e);
        }
        if (requestDone) {
            if (e != null) {
                eventListener.requestFailed(call, e);
            } else {
                eventListener.requestBodyEnd(call, bytesRead);
            }
        }
        if (responseDone) {
            if (e != null) {
                eventListener.responseFailed(call, e);
            } else {
                eventListener.responseBodyEnd(call, bytesRead);
            }
        }
        return call.messageDone(this, requestDone, responseDone, e);
    }

    void noRequestBody() {
        call.messageDone(this, true, false, null);
    }

    /**
     * A request body that fires events when it completes.
     */
    private final class RequestBodyRawWriter implements RawWriter {
        private final @NonNull RawWriter delegate;
        /**
         * The exact number of bytes to be written, or -1L if that is unknown.
         */
        private final long contentLength;
        private boolean completed = false;
        private long bytesReceived = 0L;
        private boolean closed = false;

        private RequestBodyRawWriter(final @NonNull RawWriter delegate, final long contentLength) {
            assert delegate != null;

            this.delegate = delegate;
            this.contentLength = contentLength;
        }

        @Override
        public void writeFrom(final @NonNull Buffer reader, final long byteCount) {
            assert reader != null;

            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (contentLength != -1L && bytesReceived + byteCount > contentLength) {
                throw new JayoProtocolException(
                        "expected " + contentLength + " bytes but received " + (bytesReceived + byteCount));
            }
            try {
                delegate.writeFrom(reader, byteCount);
                bytesReceived += byteCount;
            } catch (JayoException e) {
                throw complete(e);
            }
        }

        @Override
        public void flush() {
            try {
                delegate.flush();
            } catch (JayoException e) {
                throw complete(e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (contentLength != -1L && bytesReceived != contentLength) {
                throw new JayoProtocolException("unexpected end of stream");
            }
            try {
                delegate.close();
                complete(null);
            } catch (JayoException e) {
                throw complete(e);
            }
        }

        private <E extends JayoException> E complete(final E e) {
            if (completed) {
                return e;
            }
            completed = true;
            return bodyComplete(bytesReceived, false, true, e);
        }
    }

    /**
     * A response body that fires events when it completes.
     */
    final class ResponseBodyRawReader implements RawReader {
        private final @NonNull RawReader delegate;
        private final long contentLength;
        private long bytesReceived = 0L;
        private boolean invokeStartEvent = true;
        private boolean completed = false;
        private boolean closed = false;

        ResponseBodyRawReader(final @NonNull RawReader delegate, final long contentLength) {
            assert delegate != null;

            this.delegate = delegate;
            this.contentLength = contentLength;
            if (contentLength == 0L) {
                complete(null);
            }
        }

        @Override
        public long readAtMostTo(final @NonNull Buffer writer, final long byteCount) {
            assert writer != null;

            if (closed) {
                throw new JayoClosedResourceException();
            }
            try {
                final var read = delegate.readAtMostTo(writer, byteCount);

                if (invokeStartEvent) {
                    invokeStartEvent = false;
                    eventListener.responseBodyStart(call);
                }

                if (read == -1L) {
                    complete(null);
                    return -1L;
                }

                final var newBytesReceived = bytesReceived + read;
                if (contentLength != -1L && newBytesReceived > contentLength) {
                    throw new JayoProtocolException(
                            "expected " + contentLength + " bytes but received " + newBytesReceived);
                }

                bytesReceived = newBytesReceived;
                if (codec.isResponseComplete()) {
                    complete(null);
                }

                return read;
            } catch (JayoException e) {
                throw complete(e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                delegate.close();
                complete(null);
            } catch (JayoException e) {
                throw complete(e);
            }
        }

        public <E extends JayoException> E complete(final E e) {
            if (completed) {
                return e;
            }
            completed = true;
            // If the body is closed without reading any bytes, send a responseBodyStart() now.
            if (e == null && invokeStartEvent) {
                invokeStartEvent = false;
                eventListener.responseBodyStart(call);
            }
            return bodyComplete(bytesReceived, true, false, e);
        }
    }
}
