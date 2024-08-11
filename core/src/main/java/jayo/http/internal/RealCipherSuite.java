/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
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

import jayo.http.CipherSuite;
import org.jspecify.annotations.NonNull;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class RealCipherSuite implements CipherSuite {
    /**
     * Compares cipher suites names like "TLS_RSA_WITH_NULL_MD5" and "SSL_RSA_WITH_NULL_MD5",
     * ignoring the "TLS_" or "SSL_" prefix which is not consistent across platforms. In particular
     * some IBM JVMs use the "SSL_" prefix everywhere whereas Oracle JVMs mix "TLS_" and "SSL_".
     */
    static final Comparator<String> ORDER_BY_NAME = (a, b) -> {
        assert a != null;
        assert b != null;
        var i = 4;
        final var limit = Math.min(a.length(), b.length());
        while (i < limit) {
            final var charA = a.charAt(i);
            final var charB = b.charAt(i);
            if (charA != charB) {
                return (charA < charB) ? -1 : 1;
            }
            i++;
        }
        final var lengthA = a.length();
        final var lengthB = b.length();
        if (lengthA != lengthB) {
            return (lengthA < lengthB) ? -1 : 1;
        }
        return 0;
    };

    /**
     * Holds interned instances. This needs to be above the init() calls below so that it's
     * initialized by the time those parts of `<clinit>()` run. Guarded by RealCipherSuite.class.
     */
    private static final @NonNull Map<String, CipherSuite> INSTANCES = new HashMap<>();

    private static final @NonNull Lock LOCK = new ReentrantLock();

    private final @NonNull String javaName;

    private RealCipherSuite(final @NonNull String javaName) {
        this.javaName = javaName;
    }

    @Override
    public @NonNull String getJavaName() {
        return javaName;
    }

    @Override
    public @NonNull String toString() {
        return javaName;
    }

    /**
     * @param javaName the name used by Java APIs for this cipher suite. Different from the IANA name for older cipher
     *                 suites because the prefix is {@code SSL_} instead of {@code TLS_}.
     */
    public static @NonNull CipherSuite fromJavaName(final @NonNull String javaName) {
        assert javaName != null;
        LOCK.lock();
        try {
            var result = INSTANCES.get(javaName);
            if (result == null) {
                result = INSTANCES.get(secondaryName(javaName));

                if (result == null) {
                    result = new RealCipherSuite(javaName);
                }

                // Add the new cipher suite, or a confirmed alias.
                INSTANCES.put(javaName, result);
            }
            return result;
        } finally {
            LOCK.unlock();
        }
    }

    private static @NonNull String secondaryName(final @NonNull String javaName) {
        if (javaName.startsWith("TLS_")) {
            return "SSL_" + javaName.substring(4);
        } else if (javaName.startsWith("SSL_")) {
            return "TLS_" + javaName.substring(4);
        }
        // else
        return javaName;
    }

    /**
     * @param javaName the name used by Java APIs for this cipher suite. Different than the IANA
     *                 name for older cipher suites because the prefix is `SSL_` instead of `TLS_`.
     * @param _value   the integer identifier for this cipher suite. (Documentation only.)
     */
    public static @NonNull CipherSuite init(final @NonNull String javaName, final int _value) {
        assert javaName != null;
        final var suite = new RealCipherSuite(javaName);
        INSTANCES.put(javaName, suite);
        return suite;
    }
}
