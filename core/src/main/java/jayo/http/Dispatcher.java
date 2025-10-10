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
import jayo.http.internal.connection.RealDispatcher;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.ExecutorService;

/**
 * Policy on when async requests are executed.
 * <p>
 * Each dispatcher uses an {@link ExecutorService} to run async calls internally. If you supply your own executor, it
 * should be able to run the {@linkplain #getMaxRequests() configured maximum} number of calls concurrently.
 */
public sealed interface Dispatcher permits RealDispatcher {
    static @NonNull Builder builder() {
        return new RealDispatcher.Builder();
    }

    /**
     * @return the maximum number of async requests to execute concurrently. Above this, requests are queued in memory,
     * waiting for the running calls to complete.
     */
    int getMaxRequests();

    /**
     * @return the maximum number of async requests for each host to execute concurrently. This limits requests by the
     * URL's host name.
     * <p>
     * Note1: Concurrent requests to a single IP address may still exceed this limit: multiple hostnames may share an IP
     * address or be routed through the same HTTP proxy.
     * <p>
     * Note2: WebSocket connections to hosts <b>do not</b> count against this limit.
     */
    int getMaxRequestsPerHost();

    /**
     * Cancels all calls currently enqueued or executing. Includes calls executed both
     * {@linkplain jayo.http.Call#execute() synchronously} and
     * {@linkplain jayo.http.Call#enqueue(Callback) asynchronously}.
     */
    void cancelAll();

    @NonNull
    ExecutorService getExecutorService();

    int queuedCallsCount();

    /**
     * The builder used to create a {@link Dispatcher} instance.
     */
    sealed interface Builder permits RealDispatcher.Builder {
        /**
         * Sets the maximum number of async requests to execute concurrently. Above this, requests are queued in memory,
         * waiting for the running calls to complete. Default is {@code 64}.
         */
        @NonNull
        Builder maxRequests(final int maxRequests);

        /**
         * Sets the maximum number of async requests for each host to execute concurrently. This limits requests by the
         * URL's host name. Default is {@code 5}.
         * <p>
         * Note1: Concurrent requests to a single IP address may still exceed this limit: multiple hostnames may share
         * an IP address or be routed through the same HTTP proxy.
         * <p>
         * Note2: WebSocket connections to hosts <b>do not</b> count against this limit.
         */
        @NonNull
        Builder maxRequestsPerHost(final int maxRequestsPerHost);

        /**
         * Sets the {@link ExecutorService} to run async calls internally. It should be able to run the
         * {@linkplain #maxRequests(int) configured maximum} number of calls concurrently.
         */
        @NonNull
        Builder executorService(final @NonNull ExecutorService executorService);

        /**
         * Sets the callback function to be invoked each time the dispatcher becomes idle (when the total number of
         * running sync and async calls returns to zero).
         * <p>
         * Note1: The time at which a {@linkplain Call call} is considered idle is different depending on whether it was
         * run {@linkplain Call#execute() synchronously} or {@linkplain Call#enqueue(Callback) asynchronously}.
         * <ul>
         * <li>Synchronous calls become idle once {@linkplain Call#execute() execute} returns.
         * <li>Asynchronous calls become idle after the
         * {@linkplain Callback#onResponse(Call, ClientResponse) onResponse} or
         * {@linkplain Callback#onFailure(Call, JayoException) onFailure} callback has returned.
         * </ul>
         * Note2: The network layer will not truly be idle until every returned {@link ClientResponse} has been closed.
         */
        @NonNull
        Builder idleCallback(final @NonNull Runnable idleCallback);

        @NonNull
        Dispatcher build();
    }
}
