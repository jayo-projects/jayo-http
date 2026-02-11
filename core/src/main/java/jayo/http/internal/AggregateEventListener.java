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

package jayo.http.internal;

import jayo.JayoException;
import jayo.http.*;
import jayo.network.Proxy;
import jayo.tls.Handshake;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

public final class AggregateEventListener implements EventListener {
    public static @NonNull EventListener create(final @NonNull EventListener leftEventListener,
                                                final @NonNull EventListener rightEventListener) {
        assert leftEventListener != null;
        assert rightEventListener != null;

        final EventListener[] left;
        if (leftEventListener == NONE) {
            return rightEventListener;
        } else if (leftEventListener instanceof AggregateEventListener aggregateEventListener) {
            left = aggregateEventListener.eventListeners;
        } else {
            left = new EventListener[]{leftEventListener};
        }

        final EventListener[] right;
        if (rightEventListener == NONE) {
            return leftEventListener;
        } else if (rightEventListener instanceof AggregateEventListener aggregateEventListener) {
            right = aggregateEventListener.eventListeners;
        } else {
            right = new EventListener[]{rightEventListener};
        }

        return new AggregateEventListener(Utils.concat(left, right));
    }

    private final @NonNull EventListener @NonNull [] eventListeners;

    public AggregateEventListener(final @NonNull EventListener @NonNull [] eventListeners) {
        assert eventListeners != null;
        this.eventListeners = eventListeners;
    }
    
    @Override
    public void callStart(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.callStart(call);
        }
    }

    @Override
    public void dispatcherQueueStart(final Call.@NonNull AsyncCall asyncCall, final @NonNull Dispatcher dispatcher) {
        for (final var delegate : eventListeners) {
            delegate.dispatcherQueueStart(asyncCall, dispatcher);
        }
    }

    @Override
    public void dispatcherQueueEnd(final Call.@NonNull AsyncCall asyncCall, final @NonNull Dispatcher dispatcher) {
        for (final var delegate : eventListeners) {
            delegate.dispatcherQueueEnd(asyncCall, dispatcher);
        }
    }

    @Override
    public void dispatcherExecution(final Call.@NonNull AsyncCall asyncCall, @NonNull Dispatcher dispatcher) {
        for (final var delegate : eventListeners) {
            delegate.dispatcherExecution(asyncCall, dispatcher);
        }
    }

    @Override
    public void proxySelected(final @NonNull Call call,
                              final @NonNull HttpUrl url,
                              final @Nullable Proxy proxy) {
        for (final var delegate : eventListeners) {
            delegate.proxySelected(call, url, proxy);
        }
    }

    @Override
    public void dnsStart(final @NonNull Call call, final @NonNull String domainName) {
        for (final var delegate : eventListeners) {
            delegate.dnsStart(call, domainName);
        }
    }

    @Override
    public void dnsEnd(final @NonNull Call call,
                       final @NonNull String domainName,
                       final @NonNull List<InetAddress> inetAddressList) {
        for (final var delegate : eventListeners) {
            delegate.dnsEnd(call, domainName, inetAddressList);
        }
    }

    @Override
    public void connectStart(final @NonNull Call call,
                             final @NonNull InetSocketAddress inetSocketAddress,
                             final @Nullable Proxy proxy) {
        for (final var delegate : eventListeners) {
            delegate.connectStart(call, inetSocketAddress, proxy);
        }
    }

    @Override
    public void secureConnectStart(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.secureConnectStart(call);
        }
    }

    @Override
    public void secureConnectEnd(final @NonNull Call call, final @Nullable Handshake handshake) {
        for (final var delegate : eventListeners) {
            delegate.secureConnectEnd(call, handshake);
        }
    }

    @Override
    public void connectEnd(final @NonNull Call call,
                           final @NonNull InetSocketAddress inetSocketAddress,
                           final @Nullable Proxy proxy,
                           final @Nullable Protocol protocol) {
        for (final var delegate : eventListeners) {
            delegate.connectEnd(call, inetSocketAddress, proxy, protocol);
        }
    }

    @Override
    public void connectFailed(final @NonNull Call call,
                              final @NonNull InetSocketAddress inetSocketAddress,
                              final @Nullable Proxy proxy,
                              final @Nullable Protocol protocol,
                              final @NonNull JayoException je) {
        for (final var delegate : eventListeners) {
            delegate.connectFailed(call, inetSocketAddress, proxy, protocol, je);
        }
    }

    @Override
    public void connectionAcquired(final @NonNull Call call, final @NonNull Connection connection) {
        for (final var delegate : eventListeners) {
            delegate.connectionAcquired(call, connection);
        }
    }

    @Override
    public void connectionReleased(final @NonNull Call call, final @NonNull Connection connection) {
        for (final var delegate : eventListeners) {
            delegate.connectionReleased(call, connection);
        }
    }

    @Override
    public void requestHeadersStart(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.requestHeadersStart(call);
        }
    }

    @Override
    public void requestHeadersEnd(final @NonNull Call call, final @NonNull ClientRequest request) {
        for (final var delegate : eventListeners) {
            delegate.requestHeadersEnd(call, request);
        }
    }

    @Override
    public void requestBodyStart(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.requestBodyStart(call);
        }
    }

    @Override
    public void requestBodyEnd(final @NonNull Call call, final long byteCount) {
        for (final var delegate : eventListeners) {
            delegate.requestBodyEnd(call, byteCount);
        }
    }

    @Override
    public void requestFailed(final @NonNull Call call, final @NonNull JayoException ioe) {
        for (final var delegate : eventListeners) {
            delegate.requestFailed(call, ioe);
        }
    }

    @Override
    public void responseHeadersStart(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.responseHeadersStart(call);
        }
    }

    @Override
    public void responseHeadersEnd(final @NonNull Call call, final @NonNull ClientResponse response) {
        for (final var delegate : eventListeners) {
            delegate.responseHeadersEnd(call, response);
        }
    }

    @Override
    public void responseBodyStart(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.responseBodyStart(call);
        }
    }

    @Override
    public void responseBodyEnd(final @NonNull Call call, final long byteCount) {
        for (final var delegate : eventListeners) {
            delegate.responseBodyEnd(call, byteCount);
        }
    }

    @Override
    public void responseFailed(final @NonNull Call call, final @NonNull JayoException ioe) {
        for (final var delegate : eventListeners) {
            delegate.responseFailed(call, ioe);
        }
    }

    @Override
    public void callEnd(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.callEnd(call);
        }
    }

    @Override
    public void callFailed(final @NonNull Call call, final @NonNull JayoException ioe) {
        for (final var delegate : eventListeners) {
            delegate.callFailed(call, ioe);
        }
    }

    @Override
    public void canceled(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.canceled(call);
        }
    }

    @Override
    public void satisfactionFailure(final @NonNull Call call, final @NonNull ClientResponse response) {
        for (final var delegate : eventListeners) {
            delegate.satisfactionFailure(call, response);
        }
    }

    @Override
    public void cacheHit(final @NonNull Call call, final @NonNull ClientResponse response) {
        for (final var delegate : eventListeners) {
            delegate.cacheHit(call, response);
        }
    }

    @Override
    public void cacheMiss(final @NonNull Call call) {
        for (final var delegate : eventListeners) {
            delegate.cacheMiss(call);
        }
    }

    @Override
    public void cacheConditionalHit(final @NonNull Call call, final @NonNull ClientResponse cachedResponse) {
        for (final var delegate : eventListeners) {
            delegate.cacheConditionalHit(call, cachedResponse);
        }
    }

    @Override
    public void retryDecision(final @NonNull Call call,
                              final @NonNull JayoException je,
                              final boolean retry) {
        for (final var delegate : eventListeners) {
            delegate.retryDecision(call, je, retry);
        }
    }

    @Override
    public void followUpDecision(final @NonNull Call call,
                                 final @NonNull ClientResponse networkResponse,
                                 final @Nullable ClientRequest nextRequest) {
        for (final var delegate : eventListeners) {
            delegate.followUpDecision(call, networkResponse, nextRequest);
        }
    }
}
