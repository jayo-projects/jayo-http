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

import jayo.http.Address;
import jayo.http.HttpUrl;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Deque;
import java.util.List;

/**
 * Policy on choosing which connection to use for an exchange and any retries that follow. This uses the following
 * strategies:
 * <ol>
 * <li>If the current call already has a connection that can satisfy the request, it is used. Using the same connection
 * for an initial exchange and its follow-ups may improve locality.
 * <li>If there is a connection in the pool that can satisfy the request, it is used. Note that it is possible for
 * shared exchanges to make requests to different host names! See {@link RealConnection#isEligible(Address, List)} for
 * details.
 * <li>Attempt plans from prior connect attempts for this call. These occur as either follow-ups to failed connect
 * attempts (such as trying the next {@linkplain jayo.http.ConnectionSpec ConnectionSpec}), or as attempts that lost a
 * race in fast follow-up.
 * <li>If there's no existing connection, make a list of routes (which may require blocking DNS lookups) and attempt new
 * connections to them. When failures occur, retries iterate the list of available routes.
 * </ol>
 * If the pool gains an eligible connection while DNS, TCP, or TLS work is in flight, this finder will prefer pooled
 * connections. Only pooled HTTP/2 connections are used for such de-duplication.
 * <p>
 * It is possible to cancel the finding process by canceling its call.
 * <p>
 * Implementations of this interface are not thread-safe. Each instance is thread-confined to the thread executing the
 * call.
 */
public interface RoutePlanner {
    @NonNull
    Address getAddress();

    /**
     * Follow-ups for failed plans and plans that lost a race.
     */
    @NonNull
    Deque<Plan> getDeferredPlans();

    boolean isCanceled();

    /**
     * @return a plan to attempt.
     */
    @NonNull
    Plan plan();

    /**
     * @param failedConnection an optional connection that resulted in a failure. If the failure is recoverable, the
     *                         connection's route may be recovered for the retry.
     * @return true if there are more route plans to try.
     */
    boolean hasNext(final @Nullable RealConnection failedConnection);

    /**
     * @return true if the host and port are unchanged from when this was created. This is used to detect if followups
     * need to do a full connection-finding process including DNS resolution, and certificate pin checks.
     */
    boolean sameHostAndPort(final @NonNull HttpUrl url);

    /**
     * A plan holds either an immediately usable connection or one that must be connected first. These steps are split
     * so callers can call {@link #connectTcp()} on a background thread if attempting multiple plans concurrently.
     */
    interface Plan {
        boolean isReady();

        @NonNull
        ConnectResult connectTcp();

        @NonNull
        ConnectResult connectTlsEtc();

        @NonNull
        RealConnection handleSuccess();

        void cancel();

        /**
         * @return a plan to attempt if canceling this plan was a mistake! The returned plan is not canceled, even if
         * this plan is canceled.
         */
        @Nullable
        Plan retry();
    }

    /**
     * What to do once a plan has executed.
     * <p>
     * If {@link #nextPlan} is not-null, another attempt should be made by following it. If {@link #throwable} is
     * non-null, it should be reported to the user should all further attempts fail.
     * <p>
     * The two values are independent: results can contain both (recoverable error), neither (success), just an
     * exception (permanent failure), or just a plan (non-exceptional retry).
     */
    record ConnectResult(@NonNull Plan plan, @Nullable Plan nextPlan, @Nullable Throwable throwable) {
        boolean isSuccess() {
            return nextPlan == null && throwable == null;
        }
    }
}
