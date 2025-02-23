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

import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.Connection;
import jayo.http.Interceptor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application interceptors, the Jayo HTTP
 * core, all network interceptors, and finally the network caller.
 * <p>
 * If the chain is for an application interceptor, then {@link #exchange} must be null. Otherwise, it is for a network
 * interceptor and {@link #exchange} must be non-null.
 */
public final class RealInterceptorChain implements Interceptor.Chain {
    private final @NonNull RealCall call;
    private final @NonNull List<@NonNull Interceptor> interceptors;
    private final int index;
    private final @Nullable Exchange exchange;
    private final @NonNull ClientRequest request;

    private int calls = 0;

    public RealInterceptorChain(final @NonNull RealCall call,
                                final @NonNull List<@NonNull Interceptor> interceptors,
                                final int index,
                                final @Nullable Exchange exchange,
                                final @NonNull ClientRequest request) {
        assert call != null;
        assert interceptors != null;
        assert request != null;

        this.call = call;
        this.interceptors = interceptors;
        this.index = index;
        this.exchange = exchange;
        this.request = request;
    }

    RealInterceptorChain copy(final @Nullable Exchange exchange) {
        return copy(this.index, this.request, exchange);
    }

    private RealInterceptorChain copy(final int index,
                                      final @NonNull ClientRequest request,
                                      final @Nullable Exchange exchange) {
        assert request != null;
        return new RealInterceptorChain(
                this.call,
                this.interceptors,
                index,
                exchange,
                request
        );
    }

    @Override
    public @Nullable Connection connection() {
        return (exchange != null) ? exchange.connection() : null;
    }

    @Override
    public @NonNull RealCall call() {
        return call;
    }

    @Override
    public @NonNull ClientRequest request() {
        return request;
    }

    @Nullable
    Exchange exchange() {
        return exchange;
    }

    @Override
    public @NonNull ClientResponse proceed(final @NonNull ClientRequest request) {
        assert request != null;

        if (index >= interceptors.size()) {
            throw new IllegalStateException("no more interceptors");
        }

        calls++;

        if (exchange != null) {
            if (!exchange.finder.routePlanner().sameHostAndPort(request.getUrl())) {
                throw new IllegalStateException(
                        "network interceptor " + interceptors.get(index - 1) + " must retain the same host and port");
            }
            if (calls != 1) {
                throw new IllegalStateException(
                        "network interceptor " + interceptors.get(index - 1) + " must call proceed() exactly once");
            }
        }

        // Call the next interceptor in the chain.
        final var next = copy(index + 1, request, exchange);
        final var interceptor = interceptors.get(index);

        final var response = interceptor.intercept(next);
        if (response == null) {
            throw new NullPointerException("interceptor " + interceptor + " returned null");
        }

        if (exchange != null) {
            if (index + 1 < interceptors.size() && next.calls != 1) {
                throw new IllegalStateException("network interceptor " + interceptor + " must call proceed() exactly once");
            }
        }

        return response;
    }
}
