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

package jayo.http;

import org.jspecify.annotations.NonNull;

import java.time.Duration;

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object represents a single
 * request/response pair (stream), it cannot be executed twice.
 */
public interface Call extends Cloneable {
    /**
     * @return the original request that initiated this call.
     */
    @NonNull
    ClientRequest request();

    /**
     * Invokes the request immediately and blocks until the response can be processed or is in error.
     * <h3>Remember to close the response</h3>
     * To avoid leaking resources, callers should close the {@link ClientResponse response} which in turn will close the
     * underlying {@linkplain ClientResponseBody response body}.
     * <pre>
     * {@code
     * Call call = client.newCall(request);
     * // ensure the response (and underlying response body) is closed
     * try (ClientResponse response = call.execute()) {
     *   ...
     * }
     * }
     * </pre>
     * The caller may read the response body with the response's {@link ClientResponse#getBody()} method. To avoid
     * leaking resources callers must {@linkplain ClientResponseBody#close() close the response body} or the response.
     * <h3>Receiving a response does not mean your call is a success</h3>
     * Note that transport-layer success (receiving HTTP response code, headers and body) does not necessarily
     * indicate application-layer success: the {@linkplain ClientResponse#getStatus() response status} may still
     * indicate an unhappy HTTP response code like {@code 404} or {@code 500}.
     * <h3>Cancellation and timeout</h3>
     * You can use Jayo's cancellation support to configure timeout and manually cancel this call using the cancellable
     * scope.
     * <pre>
     * {@code
     * Call call = client.newCall(request);
     * // ensure the response (and underlying response body) is closed
     * try (ClientResponse response = Cancellable.callWithTimeout(Duration.ofMillis(500), cs -> call.execute())) {
     *   ...
     * }
     * }
     * </pre>
     * If a specific timeout was defined, it will be used. Otherwise, this execution will use the client's default
     * timeout configured with {@link JayoHttpClient.Builder#callTimeout(java.time.Duration)}, if any. This timeout
     * spans the entire call: resolving DNS, connecting, writing the request body, server processing, and reading the
     * response body. If the call requires redirects or retries, all must be complete within one timeout period.
     *
     * @throws jayo.JayoException    if the request could not be executed due to cancellation, a connectivity problem or
     *                               timeout. Because networks can fail during an exchange, it is possible that the
     *                               remote server accepted the request before the failure.
     * @throws IllegalStateException if the call has already been executed.
     */
    @NonNull
    ClientResponse execute();

    /**
     * Schedules the request to be executed at some point in the future.
     * <p>
     * The {@linkplain JayoHttpClient#getDispatcher() dispatcher} defines when the request will run: usually immediately
     * unless there are several other requests currently being executed.
     * <p>
     * This client will later call back {@code responseCallback} with either an HTTP response or a failure exception.
     * <h3>Timeout</h3>
     * This execution will use the client's default timeout configured with
     * {@link JayoHttpClient.Builder#callTimeout(java.time.Duration)}, if any. This timeout spans the entire call:
     * resolving DNS, connecting, writing the request body, server processing, and reading the response body. If the
     * call requires redirects or retries, all must be complete within one timeout period.
     *
     * @throws IllegalStateException if the call has already been executed.
     */
    void enqueue(final @NonNull Callback responseCallback);

    /**
     * Schedules the request to be executed at some point in the future, with a specific timeout.
     * <p>
     * The {@linkplain JayoHttpClient#getDispatcher() dispatcher} defines when the request will run: usually immediately
     * unless there are several other requests currently being executed.
     * <p>
     * This client will later call back {@code responseCallback} with either an HTTP response or a failure exception.
     * <h3>Timeout</h3>
     * This execution will use the provided timeout. This timeout spans the entire call: resolving DNS, connecting,
     * writing the request body, server processing, and reading the response body. If the call requires redirects or
     * retries, all must be complete within one timeout period.
     *
     * @throws IllegalStateException if the call has already been executed.
     */
    void enqueueWithTimeout(final @NonNull Duration timeout, final @NonNull Callback responseCallback);

    /**
     * Cancels the request, if possible. Requests that are already executed cannot be canceled.
     */
    void cancel();

    /**
     * @return true if this call has been {@linkplain #execute() executed}. It is an error to execute a call more than
     * once.
     */
    boolean isExecuted();

    boolean isCanceled();

    /**
     * Create a new, identical call to this one which can be executed even if this call has already been.
     */
    @NonNull
    Call clone();

    interface Factory {
        @NonNull
        Call newCall(final @NonNull ClientRequest request);
    }
}
