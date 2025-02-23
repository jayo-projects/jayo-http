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
import jayo.http.Dns;
import org.jspecify.annotations.NonNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A DNS that uses {@link InetAddress#getAllByName(String)} to ask the underlying operating system to lookup IP
 * addresses.
 */
public final class DnsSystem implements Dns {
    @Override
    public @NonNull List<@NonNull InetAddress> lookup(@NonNull final String hostname) {
        Objects.requireNonNull(hostname);

        try {
            try {
                return Arrays.stream(InetAddress.getAllByName(hostname))
                        .toList();
            } catch (NullPointerException e) {
                final var uhe = new UnknownHostException("Broken system behaviour for dns lookup of " + hostname);
                uhe.initCause(e);
                throw uhe;
            }
        } catch (UnknownHostException e) {
            throw JayoException.buildJayoException(e);
        }
    }
}
