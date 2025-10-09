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

package jayo.http.logging;

import jayo.http.Call;
import jayo.http.EventListener;
import jayo.http.JayoHttpClient;
import jayo.http.logging.internal.RealLoggingEventListener;
import org.jspecify.annotations.NonNull;

/**
 * A Jayo HTTP EventListener, which logs call events. It must be applied as an
 * {@linkplain JayoHttpClient#getEventListenerFactory() event listener factory}.
 * <p>
 * The format of the logs created by this class should not be considered stable and may change slightly between
 * releases. If you need a stable logging format, use your own event listener.
 */
public sealed abstract class LoggingEventListener extends EventListener permits RealLoggingEventListener {
    public static final class Factory implements EventListener.Factory {
        private final HttpLoggingInterceptor.Logger logger;

        public Factory() {
            this(HttpLoggingInterceptor.Logger.DEFAULT);
        }

        public Factory(final HttpLoggingInterceptor.@NonNull Logger logger) {
            this.logger = logger;
        }

        @Override
        public @NonNull EventListener create(final @NonNull Call call) {
            assert call != null;
            return new RealLoggingEventListener(logger);
        }
    }
}
