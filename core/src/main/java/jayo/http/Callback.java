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

package jayo.http;

import jayo.JayoException;
import org.jspecify.annotations.NonNull;

public interface Callback {
    /**
     * Called when the request could not be executed due to cancellation, a connectivity problem or timeout. Because
     * networks can fail during an exchange, it is possible that the remote server accepted the request before the
     * failure.
     */
    void onFailure(final @NonNull Call call, final @NonNull JayoException je);

    /**
     * Called when the HTTP response was successfully returned by the remote server. The callback may proceed to read
     * the response body with {@link ClientResponse#getBody()}. The response is still alive until its response body is
     * {@linkplain ClientResponseBody#close() closed}. The recipient of the {@linkplain Call#enqueue(Callback) callback}
     * may consume the response body on another thread.
     * <h3>Remember to close the response</h3>
     * To avoid leaking resources, callers should close the {@link ClientResponse response} which in turn will close the
     * underlying {@linkplain ClientResponseBody response body}.
     * <pre>
     * {@code
     * public void onResponse(final @NonNull Call call, final @NonNull ClientResponse response) {
     *   // ensure the response (and underlying response body) is closed
     *   try (response) {
     *     ...
     *   }
     * }
     * }
     * </pre>
     * The caller may read the response body with the response's {@link ClientResponse#getBody()} method. To avoid
     * leaking resources callers must {@linkplain ClientResponseBody#close() close the response body} or the response.
     * <h3>Receiving a response does not mean your call is a success</h3>
     * Note that transport-layer success (receiving HTTP response code, headers and body) does not necessarily
     * indicate application-layer success: the {@linkplain ClientResponse#getStatusCode() response status} may still
     * indicate an unhappy HTTP response code like {@code 404} or {@code 500}.
     */
    void onResponse(final @NonNull Call call, final @NonNull ClientResponse response);
}
