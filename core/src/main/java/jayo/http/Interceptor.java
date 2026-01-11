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

import jayo.http.internal.connection.RealInterceptorChain;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding responses coming back in.
 * Typically, interceptors add, remove, or transform headers on the request or response.
 * <p>
 * Implementations of this interface throw {@link jayo.JayoException} to signal connectivity failures. This includes
 * both natural exceptions, such as unreachable servers, and synthetic exceptions when responses are of an unexpected
 * type or cannot be decoded.
 * <p>
 * Other exception types cancel the current call:
 * <ul>
 * <li>For synchronous calls made with {@link Call#execute()}, the exception is propagated to the caller.
 * <li>For asynchronous calls made with {@link Call#enqueue(Callback)} and
 * {@link Call#enqueueWithTimeout(Duration, Callback)}, a {@link jayo.JayoException} is propagated to the caller
 * indicating that the call was canceled. The interceptor's exception is delivered to the current thread's
 * {@linkplain Thread.UncaughtExceptionHandler uncaught exception handler}. By default, this prints a stacktrace on the
 * JVM. (Crash reporting libraries may customize this behavior.)
 * </ul>
 * A good way to signal a failure is with a synthetic HTTP response:
 * <pre>
 * {@code
 *   @Override
 *   public ClientResponse intercept(Interceptor.Chain chain) {
 *     if (myConfig.isInvalid()) {
 *       return ClientResponse.builder()
 *           .request(chain.request())
 *           .protocol(Protocol.HTTP_1_1)
 *           .code(400)
 *           .message("client config invalid")
 *           .body(ClientResponseBody.create("client config invalid"))
 *           .build();
 *     }
 *     return chain.proceed(chain.request());
 *   }
 * }
 * </pre>
 */
@FunctionalInterface
public interface Interceptor {
    @NonNull
    ClientResponse intercept(final @NonNull Chain chain);

    sealed interface Chain permits RealInterceptorChain {
        @NonNull
        ClientRequest request();

        @NonNull
        ClientResponse proceed(final @NonNull ClientRequest request);

        /**
         * @return the connection the request will be executed on. This is only available in the chains of network
         * interceptors. For application interceptors this is always null.
         */
        @Nullable
        Connection connection();

        /**
         * @return the call to which this chain belongs.
         */
        @NonNull
        Call call();

        /**
         * @return the Jayo HTTP this chain was started from.
         */
        @NonNull
        JayoHttpClient client();
    }
}
