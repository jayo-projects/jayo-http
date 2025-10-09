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

import jayo.http.Call;
import jayo.http.Dispatcher;
import jayo.http.internal.connection.RealCall.AsyncCall;
import jayo.tools.JayoUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static jayo.http.internal.Utils.JAYO_HTTP_NAME;

public final class RealDispatcher implements Dispatcher {
    private final int maxRequests;
    private final int maxRequestsPerHost;
    private @Nullable ExecutorService executorServiceOrNull;
    private final @Nullable Runnable idleCallback;

    private final @NonNull Lock lock = new ReentrantLock();

    /**
     * Ready async calls in the order they'll be run.
     */
    private final Collection<AsyncCall> readyAsyncCalls = new ArrayDeque<>();

    /**
     * Running asynchronous calls. Includes canceled calls that haven't finished yet.
     */
    private final Collection<AsyncCall> runningAsyncCalls = new ArrayDeque<>();

    /**
     * Running synchronous calls. Includes canceled calls that haven't finished yet.
     */
    private final Collection<RealCall> runningSyncCalls = new ArrayDeque<>();

    public RealDispatcher(final int maxRequests,
                          final int maxRequestsPerHost,
                          final @Nullable ExecutorService executorServiceOrNull,
                          final @Nullable Runnable idleCallback) {
        this.maxRequests = maxRequests;
        this.maxRequestsPerHost = maxRequestsPerHost;
        this.executorServiceOrNull = executorServiceOrNull;
        this.idleCallback = idleCallback;
    }

    @Override
    public int getMaxRequests() {
        return maxRequests;
    }

    @Override
    public int getMaxRequestsPerHost() {
        return maxRequestsPerHost;
    }

    @Override
    public @NonNull ExecutorService getExecutorService() {
        lock.lock();
        try {
            if (executorServiceOrNull == null) {
                executorServiceOrNull = JayoUtils.executorService(JAYO_HTTP_NAME + " Dispatcher", false);
            }
            return executorServiceOrNull;
        } finally {
            lock.unlock();
        }
    }

    void enqueue(final @NonNull AsyncCall call) {
        assert call != null;
        promoteAndExecute(call, null, null);
    }

    private @Nullable AsyncCall findExistingCallWithHost(final @NonNull String host) {
        for (final var existingCall : runningAsyncCalls) {
            if (existingCall.host().equals(host)) {
                return existingCall;
            }
        }
        for (final var existingCall : readyAsyncCalls) {
            if (existingCall.host().equals(host)) {
                return existingCall;
            }
        }
        return null;
    }

    @Override
    public void cancelAll() {
        lock.lock();
        try {
            for (final var call : readyAsyncCalls) {
                call.call().cancel();
            }
            for (final var call : runningAsyncCalls) {
                call.call().cancel();
            }
            for (final var call : runningSyncCalls) {
                call.cancel();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Promotes eligible calls from {@link #readyAsyncCalls} to {@link #runningAsyncCalls} and runs them on the executor
     * service. Must not be called with synchronization because executing calls can call into user code.
     *
     * @param enqueuedCall      a call to enqueue in the synchronized block
     * @param finishedCall      a call to finish in the synchronized block
     * @param finishedAsyncCall an async call to finish in the synchronized block
     */
    private void promoteAndExecute(final @Nullable AsyncCall enqueuedCall,
                                   final @Nullable RealCall finishedCall,
                                   final @Nullable AsyncCall finishedAsyncCall) {
        final var executorIsShutdown = getExecutorService().isShutdown();

        final Effects effects;
        lock.lock();
        try {
            effects = computeEffects(enqueuedCall, finishedCall, finishedAsyncCall, executorIsShutdown);
        } finally {
            lock.unlock();
        }

        var callDispatcherQueueStart = true;

        for (final var call : effects.callsToExecute) {
            // If the newly enqueued call is already out, skip its dispatcher queue events. We only publish those events
            // for calls that have to wait.
            if (call == enqueuedCall) {
                callDispatcherQueueStart = false;
            } else {
                call.call().eventListener().dispatcherQueueEnd(call.call(), this);
            }

            if (executorIsShutdown) {
                call.failRejected(null);
            } else {
                call.executeOn(getExecutorService());
            }
        }

        if (callDispatcherQueueStart && enqueuedCall != null) {
            enqueuedCall.call().eventListener().dispatcherQueueStart(enqueuedCall.call(), this);
        }

        if (effects.idleCallbackToRun != null) {
            effects.idleCallbackToRun.run();
        }
    }

    private @NonNull Effects computeEffects(final @Nullable AsyncCall enqueuedCall,
                                            final @Nullable RealCall finishedCall,
                                            final @Nullable AsyncCall finishedAsyncCall,
                                            final boolean executorIsShutdown) {
        if (finishedCall != null) {
            if (!runningSyncCalls.remove(finishedCall)) {
                throw new IllegalStateException("Call wasn't in-flight!");
            }
        }

        if (finishedAsyncCall != null) {
            finishedAsyncCall.callsPerHost.decrementAndGet();
            if (!runningAsyncCalls.remove(finishedAsyncCall)) {
                throw new IllegalStateException("Call wasn't in-flight!");
            }
        }

        if (enqueuedCall != null) {
            readyAsyncCalls.add(enqueuedCall);

            // Mutate the AsyncCall so that it shares the AtomicInteger of an existing running call to the same host
            if (!enqueuedCall.call().forWebSocket) {
                final var existingCall = findExistingCallWithHost(enqueuedCall.host());
                if (existingCall != null) {
                    enqueuedCall.reuseCallsPerHostFrom(existingCall);
                }
            }
        }

        final var becameIdle =
                (finishedCall != null || finishedAsyncCall != null) &&
                        (executorIsShutdown || runningAsyncCalls.isEmpty()) &&
                        runningSyncCalls.isEmpty();
        final var idleCallbackToRun = becameIdle ? idleCallback : null;

        if (executorIsShutdown) {
            final var callsToExecute = List.copyOf(readyAsyncCalls);
            readyAsyncCalls.clear();
            return new Effects(callsToExecute, idleCallbackToRun);
        }

        final var callsToExecute = new ArrayList<AsyncCall>();
        final var i = readyAsyncCalls.iterator();
        while (i.hasNext()) {
            final var asyncCall = i.next();

            if (runningAsyncCalls.size() >= this.maxRequests) {
                break; // Max capacity.
            }
            if (asyncCall.callsPerHost.get() >= this.maxRequestsPerHost) {
                continue; // Host max capacity.
            }

            i.remove();

            asyncCall.callsPerHost.incrementAndGet();
            callsToExecute.add(asyncCall);
            runningAsyncCalls.add(asyncCall);
        }

        return new Effects(callsToExecute, idleCallbackToRun);
    }

    void executed(final @NonNull RealCall call) {
        assert call != null;

        lock.lock();
        try {
            runningSyncCalls.add(call);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Used by {@link RealCall#execute()} to signal completion.
     */
    void finished(final @NonNull RealCall call) {
        assert call != null;
        promoteAndExecute(null, call, null);
    }

    /**
     * Used by {@link AsyncCall#run()} to signal completion.
     */
    void finished(final @NonNull AsyncCall asyncCall) {
        assert asyncCall != null;
        promoteAndExecute(null, null, asyncCall);
    }

    /**
     * @return a snapshot of the calls currently awaiting execution.
     */
    List<? extends Call> queuedCalls() {
        lock.lock();
        try {
            return readyAsyncCalls.stream()
                    .map(AsyncCall::call)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return a snapshot of the calls currently being executed.
     */
    List<? extends Call> runningCalls() {
        lock.lock();
        try {
            return Stream.concat(
                    runningSyncCalls.stream(),
                    runningAsyncCalls.stream()
                            .map(AsyncCall::call)
            ).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int queuedCallsCount() {
        lock.lock();
        try {
            return readyAsyncCalls.size();
        } finally {
            lock.unlock();
        }
    }

    int runningCallsCount() {
        lock.lock();
        try {
            return runningAsyncCalls.size() + runningSyncCalls.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Actions to take outside the synchronized block.
     */
    private static final class Effects {
        private final @NonNull List<@NonNull AsyncCall> callsToExecute;
        private final @Nullable Runnable idleCallbackToRun;

        private Effects(final @NonNull List<@NonNull AsyncCall> callsToExecute,
                        final @Nullable Runnable idleCallbackToRun) {
            assert callsToExecute != null;

            this.callsToExecute = callsToExecute;
            this.idleCallbackToRun = idleCallbackToRun;
        }
    }

    public static final class Builder implements Dispatcher.Builder {
        private int maxRequests = 64;
        private int maxRequestsPerHost = 5;
        private @Nullable ExecutorService executorServiceOrNull = null;
        private @Nullable Runnable idleCallback = null;

        @Override
        public @NonNull Builder maxRequests(final int maxRequests) {
            if (maxRequests < 1) {
                throw new IllegalArgumentException("max < 1: " + maxRequests);
            }
            this.maxRequests = maxRequests;
            return this;
        }

        @Override
        public @NonNull Builder maxRequestsPerHost(final int maxRequestsPerHost) {
            if (maxRequestsPerHost < 1) {
                throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
            }
            this.maxRequestsPerHost = maxRequestsPerHost;
            return this;
        }

        @Override
        public @NonNull Builder executorService(final @NonNull ExecutorService executorService) {
            this.executorServiceOrNull = Objects.requireNonNull(executorService);
            return this;
        }

        @Override
        public @NonNull Builder idleCallback(final @NonNull Runnable idleCallback) {
            this.idleCallback = Objects.requireNonNull(idleCallback);
            return this;
        }

        @Override
        public @NonNull RealDispatcher build() {
            return new RealDispatcher(maxRequests, maxRequestsPerHost, executorServiceOrNull, idleCallback);
        }
    }
}
