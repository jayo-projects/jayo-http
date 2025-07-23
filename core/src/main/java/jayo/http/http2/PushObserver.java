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

package jayo.http.http2;

import jayo.Reader;
import jayo.http.internal.http2.PushObserverCancel;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * {@linkplain jayo.tls.Protocol#HTTP_2 HTTP/2} only. Processes server-initiated HTTP requests on the client.
 * Implementations must quickly dispatch callbacks to avoid creating a bottleneck.
 * <p>
 * While {@link #onReset(int, ErrorCode)} may occur at any time, the following callbacks are expected in order,
 * correlated by stream ID.
 * <ul>
 *  * [onRequest]
 *  * [onHeaders] (unless canceled)
 *  * [onData] (optional sequence of data frames)
 * </ul>
 * Return true to request cancellation of a pushed stream.  Note that this does not guarantee future frames won't
 * arrive on the stream ID.
 * <p>
 * Note: As a stream ID is scoped to a single HTTP/2 connection, implementations which target multiple connections
 * should expect repetition of stream IDs.
 */
public interface PushObserver {
    /**
     * Describes the request that the server intends to push a response for.
     *
     * @param streamId       server-initiated stream ID: an even number.
     * @param requestHeaders minimally includes {@code :method}, {@code :scheme}, {@code :authority}, and {@code :path}.
     */
    <T extends BinaryHeader> boolean onRequest(final int streamId, final @NonNull List<@NonNull T> requestHeaders);

    /**
     * The response headers corresponding to a pushed request.  When {@code last} is true, there are no data frames to
     * follow.
     *
     * @param streamId        server-initiated stream ID: an even number.
     * @param responseHeaders minimally includes {@code :status}.
     * @param last            when true, there is no response data.
     */
    <T extends BinaryHeader> boolean onHeaders(final int streamId,
                                               final @NonNull List<@NonNull T> responseHeaders,
                                               final boolean last);

    /**
     * A chunk of response data corresponding to a pushed request. This data must either be read or skipped.
     *
     * @param streamId  server-initiated stream ID: an even number.
     * @param reader    data corresponding with this stream ID.
     * @param byteCount number of bytes to read or skip from the source.
     * @param last      when true, there are no data frames to follow.
     * @throws jayo.JayoException an IO Exception.
     */
    boolean onData(final int streamId,
                   final @NonNull Reader reader,
                   final int byteCount,
                   final boolean last);

    /**
     * Indicates the reason why this stream was canceled.
     */
    void onReset(final int streamId, final @NonNull ErrorCode errorCode);

    @NonNull
    PushObserver CANCEL = new PushObserverCancel();
}
