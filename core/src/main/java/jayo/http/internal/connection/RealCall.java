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

import jayo.*;
import jayo.http.*;
import jayo.http.internal.Utils;
import jayo.http.internal.cache.CacheInterceptor;
import jayo.http.internal.http.BridgeInterceptor;
import jayo.http.tools.JayoHttpUtils;
import jayo.scheduler.TaskRunner;
import jayo.tools.AsyncTimeout;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.INFO;
import static jayo.http.internal.connection.RealJayoHttpClient.LOGGER;
import static jayo.http.internal.connection.Tags.EmptyTags;

/**
 * The bridge between Jayo HTTP's application and network layers. This class exposes high-level application layer
 * primitives: connections, requests, responses, and streams.
 * <p>
 * This class supports {@linkplain #cancel() asynchronous canceling}. This is intended to have the smallest blast radius
 * possible. If an HTTP/2 stream is active, canceling will cancel that stream but not the other streams sharing its
 * connection. But if the TLS handshake is still in progress, then canceling may break the entire connection.
 */
public final class RealCall implements Call {
    final @NonNull RealJayoHttpClient client;
    /**
     * The application's original request unaltered by redirects or auth headers.
     */
    private final @NonNull ClientRequest originalRequest;
    final boolean forWebSocket;
    private final @NonNull RealConnectionPool connectionPool;
    volatile @NonNull EventListener eventListener;

    private final @NonNull AsyncTimeout timeout;
    private volatile AsyncTimeout.Node timeoutNode = null;

    private final @NonNull Lock lock = new ReentrantLock();

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int state = STATE_NOT_STARTED;

    private static final int STATE_NOT_STARTED = 0; // 00
    private static final int STATE_CANCELED_BEFORE_START = 1; // 01
    private static final int STATE_EXECUTING = 2; // 10
    private static final int STATE_CANCELED = 3; // 11


    // These properties are only accessed by the thread executing the call.

    /**
     * Initialized in {@link #callStart()}.
     */
    private @Nullable Throwable callStackTrace = null;

    /**
     * Finds an exchange to send the next request and receive the next response.
     */
    private @Nullable ExchangeFinder exchangeFinder = null;

    @Nullable
    RealConnection connection = null;

    private boolean timeoutEarlyExit = false;

    /**
     * This is the same value as {@link #exchange}, but scoped to the execution of the network interceptors. The
     * {@link #exchange} field is assigned to null when its streams end, which may be before or after the network
     * interceptors return.
     */
    private @Nullable Exchange interceptorScopedExchange = null;

    // These properties are guarded by `lock`. They are typically only accessed by the thread executing the call, but
    // they may be accessed by other threads for duplex requests.

    private boolean requestBodyOpen = false;
    private boolean responseBodyOpen = false;
    private boolean socketWriterOpen = false;
    private boolean socketReaderOpen = false;

    /**
     * True if there are more exchanges expected for this call.
     */
    private boolean expectMoreExchanges = true;

    // These properties are accessed by cancelling threads. Any thread can cancel a call, and once it's canceled, it's
    // canceled forever.

    private volatile @Nullable Exchange exchange = null;

    final @NonNull Collection<RoutePlanner.@NonNull Plan> plansToCancel = new CopyOnWriteArrayList<>();

    private final @NonNull Tags initialTags;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile @NonNull Tags tags;

    // VarHandle mechanics
    private static final @NonNull VarHandle STATE_HANDLE;
    private static final @NonNull VarHandle EVENT_LISTENER_HANDLE;
    private static final @NonNull VarHandle TAGS_HANDLE;

    static {
        try {
            STATE_HANDLE = MethodHandles.lookup().findVarHandle(RealCall.class, "state", int.class);
            EVENT_LISTENER_HANDLE = MethodHandles.lookup().findVarHandle(RealCall.class, "eventListener", EventListener.class);
            TAGS_HANDLE = MethodHandles.lookup().findVarHandle(RealCall.class, "tags", Tags.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public RealCall(final @NonNull RealJayoHttpClient client,
                    final @NonNull ClientRequest originalRequest,
                    final @NonNull Tag<?> @NonNull [] tags,
                    final boolean forWebSocket) {
        this(client, originalRequest, buildTags(tags), forWebSocket);
    }

    @SuppressWarnings({"unchecked", "RawUseOfParameterized"})
    private static Tags buildTags(final @NonNull Tag @NonNull [] tagList) {
        assert tagList != null;

        Tags tags = EmptyTags.INSTANCE;
        for (final var tag : tagList) {
            tags = tags.plus(tag.type(), tag.value());
        }
        return tags;
    }

    private RealCall(final @NonNull RealJayoHttpClient client,
                     final @NonNull ClientRequest originalRequest,
                     final @NonNull Tags tags,
                     final boolean forWebSocket) {
        assert client != null;
        assert originalRequest != null;
        assert tags != null;

        this.client = client;
        this.originalRequest = originalRequest;
        this.forWebSocket = forWebSocket;

        this.connectionPool = (RealConnectionPool) client.getConnectionPool();
        this.eventListener = client.getEventListenerFactory().create(this);
        this.timeout = AsyncTimeout.create(this::cancel);

        this.initialTags = tags;
        this.tags = tags;
    }

    /**
     * Immediately closes the socket connection if it's currently held. Use this to interrupt an in-flight request from
     * any thread. It's the caller's responsibility to close the request body and response body streams; otherwise
     * resources may be leaked.
     * <p>
     * This method is safe to be called concurrently but provides limited guarantees. If a transport layer connection
     * has been established (such as an HTTP/2 stream), that is terminated. Otherwise, if a socket connection is being
     * established, that is terminated.
     */
    @Override
    public void cancel() {
        var casSuccess = false;
        while (!casSuccess) {
            final var currentState = state;
            if ((currentState & 1) == 1) {
                return; // Already canceled.
            }
            final var updated = currentState | 1;
            casSuccess = STATE_HANDLE.compareAndSet(this, currentState, updated);
        }
        final var _exchange = exchange;
        if (_exchange != null) {
            _exchange.cancel();
        }
        for (final var plan : plansToCancel) {
            plan.cancel();
        }

        eventListener.canceled(this);
    }

    @Override
    public boolean isCanceled() {
        final var currentState = state;
        return (currentState & 1) == 1;
    }

    @Override
    public void addEventListener(final @NonNull EventListener eventListener) {
        Objects.requireNonNull(eventListener);

        // Atomically replace the current eventListener with a composite one.
        var previous = this.eventListener;
        while (!EVENT_LISTENER_HANDLE.compareAndSet(this, previous, EventListener.plus(previous, eventListener))) {
            previous = this.eventListener;
        }
    }

    @Override
    public <T> @Nullable T tag(final @NonNull Class<? extends T> type) {
        Objects.requireNonNull(type);
        return type.cast(tags.get(type));
    }

    @Override
    public <T> @NonNull T tag(final @NonNull Class<T> type, final @NonNull Supplier<@NonNull T> computeIfAbsent) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(computeIfAbsent);

        T computed = null;

        while (true) {
            final var currentTags = tags;

            // If the element is already present. Return it.
            final var existing = currentTags.get(type);
            if (existing != null) {
                return existing;
            }

            if (computed == null) {
                computed = computeIfAbsent.get();
            }

            // If we successfully add the computed element, we're done.
            final var newTags = currentTags.plus(type, computed);
            if (TAGS_HANDLE.compareAndSet(this, currentTags, newTags)) {
                return computed;
            }

            // We lost the race. Possibly to other code that was putting a *different* key. Try again!
        }
    }

    @Override
    public @NonNull ClientResponse execute() {
        if (!STATE_HANDLE.compareAndSet(this, STATE_NOT_STARTED, STATE_EXECUTING)) {
            throw new IllegalStateException("Already executed or canceled");
        }

        timeoutNode = timeout.enter(client.getCallTimeout().toNanos());
        callStart();
        try {
            client.dispatcher.executed(this);
            return getResponseWithInterceptorChain();
        } finally {
            client.dispatcher.finished(this);
        }
    }

    private @NonNull ClientResponse getResponseWithInterceptorChain() {
        // Build a full stack of interceptors.
        final var interceptors = new ArrayList<>(client.getInterceptors());
        interceptors.add(RetryAndFollowUpInterceptor.INSTANCE);
        interceptors.add(BridgeInterceptor.INSTANCE);
        interceptors.add(CacheInterceptor.INSTANCE);
        interceptors.add(ConnectInterceptor.INSTANCE);
        if (!forWebSocket) {
            interceptors.addAll(client.getNetworkInterceptors());
        }
        interceptors.add(CallServerInterceptor.INSTANCE);

        final var chain = new RealInterceptorChain(
                this,
                interceptors,
                0,
                null,
                originalRequest);

        var calledNoMoreExchanges = false;
        try {
            final var response = chain.proceed(originalRequest);
            if (isCanceled()) {
                JayoHttpUtils.closeQuietly(response);
                throw new JayoException("Canceled");
            }
            return response;
        } catch (JayoException e) {
            calledNoMoreExchanges = true;
            final var exception = noMoreExchanges(e);
            assert exception != null;
            throw exception;
        } finally {
            if (!calledNoMoreExchanges) {
                noMoreExchanges(null);
            }
        }
    }

    @Override
    public void enqueue(final @NonNull Callback responseCallback) {
        Objects.requireNonNull(responseCallback);
        enqueuePrivate(responseCallback,
                (client.getCallTimeout() != null) ? client.getCallTimeout().toNanos() : 0L);
    }

    @Override
    public void enqueueWithTimeout(final @NonNull Duration timeout, final @NonNull Callback responseCallback) {
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(responseCallback);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("The provided timeout must be strictly positive");
        }
        enqueuePrivate(responseCallback, timeout.toNanos());
    }

    private void enqueuePrivate(final @NonNull Callback responseCallback, final long timeoutNanos) {
        assert responseCallback != null;
        assert timeoutNanos >= 0L;
        if (!STATE_HANDLE.compareAndSet(this, STATE_NOT_STARTED, STATE_EXECUTING)) {
            throw new IllegalStateException("Already executed or canceled");
        }

        callStart();
        client.dispatcher.enqueue(new AsyncCall(responseCallback, timeoutNanos));
    }

    private void callStart() {
        this.callStackTrace = LogCloseableUtils.getStackTraceForCloseable("response.getBody().close()");
        eventListener.callStart(this);
    }

    /**
     * Prepare for a potential trip through all of this call's network interceptors. This prepares to find an exchange
     * to carry the request.
     * <p>
     * Note that an exchange will not be needed if the request is satisfied by the cache.
     *
     * @param newRoutePlanner true if this is not a retry and new routing can be performed.
     */
    void enterNetworkInterceptorExchange(final @NonNull ClientRequest request, final boolean newRoutePlanner) {
        assert request != null;

        if (interceptorScopedExchange != null) {
            throw new IllegalStateException();
        }

        lock.lock();
        try {
            if (responseBodyOpen) {
                throw new IllegalStateException("cannot make a new request because the previous response is still" +
                        " open: please call response.close()");
            }
            if (requestBodyOpen || socketReaderOpen || socketWriterOpen) {
                throw new IllegalStateException();
            }
        } finally {
            lock.unlock();
        }

        if (newRoutePlanner) {
            final var routePlanner = new RealRoutePlanner(
                    connectionPool,
                    client.networkSocketBuilder,
                    client.getPingInterval(),
                    client.retryOnConnectionFailure(),
                    client.fastFallback(),
                    client.address(request.getUrl()),
                    client.routeDatabase,
                    this,
                    request
            );
            this.exchangeFinder = client.fastFallback()
                    ? new FastFallbackExchangeFinder(routePlanner, Utils.defaultTaskRunner())
                    : new SequentialExchangeFinder(routePlanner);
        }
    }

    /**
     * Finds a new or pooled connection to carry a forthcoming request and response.
     */
    @NonNull
    Exchange initExchange() {
        lock.lock();
        try {
            if (!expectMoreExchanges) {
                throw new IllegalStateException("released");
            }
            if (responseBodyOpen || requestBodyOpen || socketReaderOpen || socketWriterOpen) {
                throw new IllegalStateException();
            }
        } finally {
            lock.unlock();
        }

        final var exchangeFinder = this.exchangeFinder;
        assert exchangeFinder != null;
        final var connection = exchangeFinder.find();
        final var codec = connection.newCodec(client);
        final var result = new Exchange(this, exchangeFinder, codec);
        this.interceptorScopedExchange = result;
        this.exchange = result;

        lock.lock();
        try {
            this.requestBodyOpen = true;
            this.responseBodyOpen = true;
        } finally {
            lock.unlock();
        }

        if (isCanceled()) {
            throw new JayoException("Canceled");
        }
        return result;
    }

    @Override
    public boolean isExecuted() {
        final var currentState = state;
        return (currentState & 2) == 2;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod") // We are a final type and this saves clearing state.
    @Override
    public @NonNull Call clone() {
        return new RealCall(client, originalRequest, initialTags, forWebSocket);
    }

    @Override
    public @NonNull String toString() {
        final var currentState = state;
        final var stateLabel = switch (currentState) {
            case STATE_NOT_STARTED -> "Call not started";
            case STATE_CANCELED_BEFORE_START -> "Call canceled before its execution";
            case STATE_EXECUTING -> "Call executing or executed";
            case STATE_CANCELED -> "Call canceled";
            default -> throw new IllegalStateException("Unknown state: " + currentState);
        };
        return "Call{" +
                "originalRequest=" + originalRequest +
                ", state=" + stateLabel +
                ", tags=" + tags +
                ", forWebSocket=" + forWebSocket +
                '}';
    }

    @Override
    public @NonNull ClientRequest request() {
        return originalRequest;
    }

    void acquireConnectionNoEvents(final @NonNull RealConnection connection) {
        assert connection != null;

        if (this.connection == null) {
            this.connection = connection;
            connection.calls.add(new CallReference(this, callStackTrace));
        } else {
            throw new IllegalStateException();
        }
    }

    /**
     * Releases resources held with the request or response of {@code exchange}. This should be called when the request
     * completes normally or when it fails due to an exception, in which case {@code e} should be non-null.
     * <p>
     * If the exchange was canceled or timed out, this will wrap {@code e} in an exception that provides that additional
     * context. Otherwise {@code e} is returned as-is.
     */
    <E extends JayoException> E messageDone(final @NonNull Exchange exchange,
                                            final boolean requestDone,
                                            final boolean responseDone,
                                            final boolean socketReaderDone,
                                            final boolean socketWriterDone,
                                            final @Nullable E e) {
        assert exchange != null;

        if (exchange != this.exchange) {
            return e; // This exchange was detached violently!
        }

        var allStreamsDone = false;
        var callDone = false;
        lock.lock();
        try {
            if (requestDone && requestBodyOpen ||
                    responseDone && responseBodyOpen ||
                    socketWriterDone && socketWriterOpen ||
                    socketReaderDone && socketReaderOpen
            ) {
                if (requestDone) {
                    requestBodyOpen = false;
                }
                if (responseDone) {
                    responseBodyOpen = false;
                }
                if (socketWriterDone) {
                    socketWriterOpen = false;
                }
                if (socketReaderDone) {
                    socketReaderOpen = false;
                }
                allStreamsDone = !requestBodyOpen &&
                        !responseBodyOpen &&
                        !socketWriterOpen &&
                        !socketReaderOpen;
                callDone = allStreamsDone && !expectMoreExchanges;
            }
        } finally {
            lock.unlock();
        }

        if (allStreamsDone) {
            this.exchange = null;
            if (this.connection != null) {
                this.connection.incrementSuccessCount();
            }
        }

        if (callDone) {
            return callDone(e);
        }

        return e;
    }

    <E extends JayoException> @Nullable E noMoreExchanges(final @Nullable E e) {
        var callDone = false;
        lock.lock();
        try {
            if (expectMoreExchanges) {
                expectMoreExchanges = false;
                callDone = !requestBodyOpen &&
                        !responseBodyOpen &&
                        !socketWriterOpen &&
                        !socketReaderOpen;
            }
        } finally {
            lock.unlock();
        }

        if (callDone) {
            return callDone(e);
        }

        return e;
    }

    /**
     * Complete this call. This should be called once these properties are all false: {@link #requestBodyOpen},
     * {@link #responseBodyOpen}, {@link #socketWriterOpen}, {@link #socketReaderOpen} and {@link #expectMoreExchanges}.
     * <p>
     * This will release the connection if it is still held.
     * <p>
     * It will also notify the listener that the call completed; either successfully or unsuccessfully.
     * <p>
     * If the call was canceled or timed out, this will wrap {@code e} in an exception that provides that additional
     * context. Otherwise {@code e} is returned as-is.
     */
    private <E extends JayoException> @Nullable E callDone(final @Nullable E e) {
        final var connection = this.connection;
        if (connection != null) {
            final RawSocket toClose;
            connection.lock.lock();
            try {
                // Sets this.connection to null.
                toClose = releaseConnectionNoEvents();
            } finally {
                connection.lock.unlock();
            }
            if (this.connection == null) {
                if (toClose != null) {
                    Jayo.closeQuietly(toClose);
                }
                eventListener.connectionReleased(this, connection);
//                connection.connectionListener.connectionReleased(connection, this);
//                if (toClose != null) {
//                    connection.connectionListener.connectionClosed(connection);
//                }
            } else if (toClose != null) {
                // If we still have a connection, we shouldn't be closing any sockets.
                throw new IllegalStateException();
            }
        }

        final var result = timeoutExit(e);
        if (e != null) {
            assert result != null;
            eventListener.callFailed(this, result);
        } else {
            eventListener.callEnd(this);
        }
        return result;
    }

    /**
     * Remove this call from the connection's list of allocations. Returns a socket that the caller should close.
     */
    @Nullable RawSocket releaseConnectionNoEvents() {
        final var _connection = this.connection;
        assert _connection != null;

        final var calls = _connection.calls;
        var index = -1;

        // Find the index of this call in the connection's calls
        for (var i = 0; i < calls.size(); i++) {
            if (calls.get(i).get() == this) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            throw new IllegalStateException("This call should be contained in the connection's calls");
        }

        calls.remove(index);
        this.connection = null;

        if (calls.isEmpty()) {
            _connection.idleAtNs = System.nanoTime();
            if (connectionPool.connectionBecameIdle(_connection)) {
                return _connection.socket();
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private <E extends JayoException> @Nullable E timeoutExit(final @Nullable E cause) {
        if (timeoutEarlyExit) {
            return cause;
        }
        final var _timeoutNode = timeoutNode;
        if (_timeoutNode == null || !_timeoutNode.exit()) {
            return cause;
        }

        final var e = new JayoTimeoutException("Call timeout");
        if (cause != null) {
            e.initCause(cause);
        }
        return (E) e; // E is either JayoException or JayoException?
    }

    /**
     * Stops applying the timeout before the call is entirely complete. This is used for WebSockets and duplex calls
     * where the timeout only applies to the initial setup.
     */
    void timeoutEarlyExit() {
        if (timeoutEarlyExit) {
            throw new IllegalStateException();
        }
        timeoutEarlyExit = true;
        timeoutNode.exit();
    }

    void upgradeToSocket() {
        timeoutEarlyExit();

        lock.lock();
        try {
            if (exchange == null || socketWriterOpen || socketReaderOpen || requestBodyOpen || !responseBodyOpen) {
                throw new IllegalStateException();
            }
            responseBodyOpen = false;
            socketWriterOpen = true;
            socketReaderOpen = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param closeExchange {@code true} if the current exchange should be closed because it will not be used. This is
     *                      usually due to either an exception or a retry.
     */
    void exitNetworkInterceptorExchange(boolean closeExchange) {
        lock.lock();
        try {
            if (!expectMoreExchanges) {
                throw new IllegalStateException("released");
            }
        } finally {
            lock.unlock();
        }

        if (closeExchange) {
            final var ex = exchange;
            if (ex != null) {
                ex.detachWithViolence();
            }
        }

        interceptorScopedExchange = null;
    }

    boolean retryAfterFailure() {
        final var ex = exchange;
        return ex != null && ex.hasFailure &&
                exchangeFinder != null && exchangeFinder.routePlanner().hasNext(ex.connection());
    }

    public @NonNull EventListener eventListener() {
        return eventListener;
    }

    @Nullable
    Exchange interceptorScopedExchange() {
        return interceptorScopedExchange;
    }

    final class AsyncCall implements Call.AsyncCall, Runnable {
        private final @NonNull Callback responseCallback;
        private final long timeoutNanos;

        volatile @NonNull AtomicInteger callsPerHost = new AtomicInteger(0);

        AsyncCall(final @NonNull Callback responseCallback, final long timeoutNanos) {
            assert responseCallback != null;
            assert timeoutNanos >= 0L;

            this.responseCallback = responseCallback;
            this.timeoutNanos = timeoutNanos;
        }

        void reuseCallsPerHostFrom(final @NonNull AsyncCall other) {
            assert other != null;
            this.callsPerHost = other.callsPerHost;
        }

        @NonNull
        String host() {
            return originalRequest.getUrl().getHost();
        }

        public @NonNull RealCall call() {
            return RealCall.this;
        }

        /**
         * Attempt to enqueue this async call on {@code taskRunner}. This will attempt to clean up if the executor
         * has been shut down by reporting the call as failed.
         */
        void executeOn(final @NonNull TaskRunner taskRunner) {
            assert taskRunner != null;

            var success = false;
            try {
                taskRunner.execute("JayoHttp " + redactedUrl(), false, this);
                success = true;
            } catch (RejectedExecutionException e) {
                failRejected(e);
            } finally {
                if (!success) {
                    client.dispatcher.finished(this); // This call is no longer running!
                }
            }
        }

        void failRejected(final @Nullable RejectedExecutionException e) {
            final var jayoException = new JayoInterruptedIOException("executor rejected");
            jayoException.getCause().initCause(e);
            noMoreExchanges(jayoException);
            responseCallback.onFailure(RealCall.this, jayoException);
        }

        @Override
        public void run() {
            var signalledCallback = false;
            timeoutNode = timeout.enter(timeoutNanos);
            try {
                final var response = getResponseWithInterceptorChain();
                signalledCallback = true;
                responseCallback.onResponse(RealCall.this, response);
            } catch (JayoException e) {
                if (signalledCallback) {
                    // Do not signal the callback twice!
                    if (LOGGER.isLoggable(INFO)) {
                        LOGGER.log(INFO, "Callback failure for " + toLoggableString(), e);
                    }
                } else {
                    responseCallback.onFailure(RealCall.this, e);
                }
            } catch (Throwable t) {
                cancel();
                if (!signalledCallback) {
                    final IOException cause;
                    if (t instanceof IOException ioException) {
                        cause = ioException;
                    } else {
                        cause = new IOException(t);
                    }
                    final var canceledException = new JayoException("canceled due to " + t, cause);
                    responseCallback.onFailure(RealCall.this, canceledException);
                }
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                } else {
                    throw t;
                }
            } finally {
                client.dispatcher.finished(this);
            }
        }
    }

    /**
     * @return a string that describes this call. Doesn't include a full URL as that might contain sensitive
     * information.
     */
    private String toLoggableString() {
        return (isCanceled() ? "canceled " : "") +
                (forWebSocket ? "web socket" : "call") +
                " to " + redactedUrl();
    }

    private @NonNull String redactedUrl() {
        return originalRequest.getUrl().redact();
    }

    static final class CallReference extends WeakReference<RealCall> {
        /**
         * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for identifying the
         * origin of connection leaks.
         */
        final @Nullable Throwable callStackTrace;

        public CallReference(final @NonNull RealCall referent, final @Nullable Throwable callStackTrace) {
            super(Objects.requireNonNull(referent));
            this.callStackTrace = callStackTrace;
        }
    }
}
