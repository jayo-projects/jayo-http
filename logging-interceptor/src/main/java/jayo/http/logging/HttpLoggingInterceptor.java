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

import jayo.http.ClientResponse;
import jayo.http.Interceptor;
import jayo.http.logging.internal.RealHttpLoggingInterceptor;
import org.jspecify.annotations.NonNull;

import static java.lang.System.Logger.Level.INFO;

/**
 * A Jayo HTTP interceptor which logs request and response information. Can be applied as an
 * {@linkplain jayo.http.JayoHttpClient#getInterceptors() application interceptor} or as a
 * {@linkplain jayo.http.JayoHttpClient#getNetworkInterceptors() network interceptor}.
 * <p>
 * The format of the logs created by this class should not be considered stable and may change slightly between
 * releases. If you need a stable logging format, use your own interceptor.
 */
public sealed interface HttpLoggingInterceptor extends Interceptor permits RealHttpLoggingInterceptor {
    static @NonNull Builder builder() {
        return new RealHttpLoggingInterceptor.Builder();
    }

    @NonNull
    Level getLevel();

    enum Level {
        /**
         * No logs.
         */
        NONE,

        /**
         * Logs request and response lines.
         * <p>
         * Example:
         * ```
         * --> POST /greeting http/1.1 (3-byte body)
         * <p>
         * <-- 200 OK (22ms, 6-byte body)
         * ```
         */
        BASIC,

        /**
         * Logs request and response lines and their respective headers.
         * <p>
         * Example:
         * ```
         * --> POST /greeting http/1.1
         * Host: example.com
         * Content-Type: plain/text
         * Content-Length: 3
         * --> END POST
         * <p>
         * <-- 200 OK (22ms)
         * Content-Type: plain/text
         * Content-Length: 6
         * <-- END HTTP
         * ```
         */
        HEADERS,

        /**
         * Logs request and response lines and their respective headers and bodies (if present).
         * <p>
         * Example:
         * ```
         * --> POST /greeting http/1.1
         * Host: example.com
         * Content-Type: plain/text
         * Content-Length: 3
         * <p>
         * Hi?
         * --> END POST
         * <p>
         * <-- 200 OK (22ms)
         * Content-Type: plain/text
         * Content-Length: 6
         * <p>
         * Hello!
         * <-- END HTTP
         * ```
         */
        BODY,
    }

    interface Logger {
        void log(String message);

        /**
         * The default logger used by this interceptor, relying on {@link System.Logger}.
         */
        @NonNull
        Logger DEFAULT = new SystemLogger();

        final class SystemLogger implements Logger {
            private static final System.Logger LOGGER =
                    System.getLogger("jayo.http.logging.HttpLoggingInterceptor");

            @Override
            public void log(String message) {
                LOGGER.log(INFO, message);
            }
        }
    }

    /**
     * The builder used to create a {@link ClientResponse} instance.
     */
    sealed interface Builder permits RealHttpLoggingInterceptor.Builder {
        @NonNull
        Builder logger(final @NonNull Logger logger);

        @NonNull
        Builder level(final @NonNull Level level);

        @NonNull
        Builder redactHeader(final @NonNull String name);

        @NonNull
        Builder redactQueryParams(final @NonNull String @NonNull ... names);

        @NonNull
        HttpLoggingInterceptor build();
    }
}
