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
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.function.Supplier;

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
     * indicate application-layer success: the {@linkplain ClientResponse#getStatusCode() response status} may still
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
     * Configure this call to publish all future events to {@code eventListener}, in addition to the listeners
     * configured by {@link JayoHttpClient.Builder#eventListener(EventListener)} and other calls to this function.
     * <p>
     * If this call is later {@linkplain #clone() cloned}, {@code eventListener} will not be notified of its events.
     * <p>
     * There is no mechanism to remove an event listener. Implementations should instead ignore events that they are not
     * interested in.
     *
     * @see EventListener for semantics and restrictions on listener implementations.
     */
    void addEventListener(final @NonNull EventListener eventListener);

    /**
     * @return the tag attached with {@code type} as a key, or null if no tag is attached with that key.
     * <p>
     * The tags on a call are seeded from the {@linkplain Factory#newCall(ClientRequest, Tag[]) call creation}. This set
     * will grow if new tags are computed.
     */
    <T> @Nullable T tag(final @NonNull Class<? extends @NonNull T> type);

    /**
     * @return the tag attached with {@code type} as a key. If it is absent, then {@code computeIfAbsent} is called and
     * that value is both inserted and returned.
     * <p>
     * If multiple calls to this function are made concurrently with the same {@code type}, multiple values may be
     * computed. But only one value will be inserted, and that inserted value will be returned to all callers.
     * <p>
     * If computing multiple values is problematic, use an appropriate concurrency mechanism in your
     * {@code computeIfAbsent} implementation. No locks are held while calling this function.
     */
    <T> @NonNull T tag(final @NonNull Class<T> type, final @NonNull Supplier<@NonNull T> computeIfAbsent);

    /**
     * Create a new, identical call to this one which can be enqueued or executed even if this call has already been.
     * <p>
     * The tags on the returned call will equal the tags passed at
     * {@linkplain Factory#newCall(ClientRequest, Tag[]) call creation}. Any tags that were computed for this call
     * afterward will not be included on the cloned call. If necessary, you may manually copy over specific tags by
     * re-computing them:
     * <pre>
     * {@code
     * Call copy = original.clone();
     * MyTag myTag = original.tag(MyTag.class);
     * if (myTag != null) {
     *     copy.tag(MyTag.class, () -> myTag);
     * }
     * }
     * </pre>
     * If any event listeners were installed on this call with {@link #addEventListener(EventListener)}, they will not
     * be installed on this copy.
     */
    @NonNull
    Call clone();

    interface Factory {
        /**
         * Prepares the {@code request} to be executed at some point in the future.
         * <p>
         * You can provide some {@code tags} that will be attached to the call. Tags can be read from the call using
         * {@link Call#tag(Class)}.
         * <p>
         * Note: Use tags to attach timing, debugging, or other application data to a call so that you may read them in
         * interceptors, event listeners, or callbacks.
         */
        @NonNull
        Call newCall(final @NonNull ClientRequest request, final @NonNull Tag<?> @NonNull ... tags);
    }
}
