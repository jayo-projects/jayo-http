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

import jayo.http.HttpUrl;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static java.lang.System.Logger.Level.TRACE;
import static java.lang.System.Logger.Level.WARNING;
import static jayo.http.internal.connection.RealJayoHttpClient.LOGGER;

final class LogCloseableUtils {
    // un-instantiable
    private LogCloseableUtils() {
    }

    static @Nullable Throwable getStackTraceForCloseable(final @NonNull String closer) {
        assert closer != null;

        return (LOGGER.isLoggable(TRACE))
                ? new Throwable(closer) // These are expensive to allocate.
                : null;
    }

    static void logCloseableLeak(final @NonNull HttpUrl url, final @Nullable Throwable stackTrace) {
        assert url != null;

        if (LOGGER.isLoggable(WARNING)) {
            var logMessage = "A connection to " + url + " was leaked. Did you forget to close a response body?";
            if (stackTrace == null) {
                logMessage += " To see where this was allocated, set the JayoHttpClient logger level to TRACE or FINE.";
            }
            LOGGER.log(WARNING, logMessage, stackTrace);
        }
    }
}
