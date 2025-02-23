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

import jayo.JayoException;
import org.jspecify.annotations.NonNull;

/**
 * Attempt routes one at a time until one connects.
 */
record SequentialExchangeFinder(@NonNull RoutePlanner routePlanner) implements ExchangeFinder {
    @Override
    public @NonNull RealConnection find() {
        JayoException firstException = null;
        while (true) {
            if (routePlanner.isCanceled()) {
                throw new JayoException("Canceled");
            }

            try {
                final var plan = routePlanner.plan();

                if (!plan.isReady()) {
                    final var tcpConnectResult = plan.connectTcp();
                    final var connectResult = tcpConnectResult.isSuccess()
                            ? plan.connectTlsEtc()
                            : tcpConnectResult;

                    final var exception = connectResult.throwable();
                    if (exception instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    } else if (exception != null) {
                        throw new RuntimeException(exception);
                    }

                    final var nextPlan = connectResult.nextPlan();
                    if (nextPlan != null) {
                        routePlanner.getDeferredPlans().addFirst(nextPlan);
                        continue;
                    }
                }
                return plan.handleSuccess();
            } catch (JayoException e) {
                if (firstException == null) {
                    firstException = e;
                } else {
                    firstException.addSuppressed(e);
                }
                if (!routePlanner.hasNext(null)) {
                    throw firstException;
                }
            }
        }
    }
}
