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

import jayo.http.internal.DnsSystem;
import org.jspecify.annotations.NonNull;

import java.net.InetAddress;
import java.util.List;

/**
 * A domain name service that resolves IP addresses for host names. Most applications will use the
 * {@linkplain #SYSTEM system DNS service}, which is the default. Some applications may provide their own implementation
 * to use a different DNS server, to prefer IPv6 addresses, to prefer IPv4 addresses, or to force a specific known IP
 * address.
 *
 * @implSpec Implementations of this interface must be safe for concurrent use.
 */
@FunctionalInterface
public interface Dns {
    /**
     * @return the IP addresses of {@code hostname}, in the order they will be attempted by Jayo HTTP. If a connection
     * to an address fails, Jayo HTTP will retry the connection with the next address until either a connection is made,
     * the set of IP addresses is exhausted, or a limit is exceeded.
     * @throws jayo.JayoUnknownHostException if {@code hostname} has no known IP address in this Dns.
     */
    @NonNull
    List<@NonNull InetAddress> lookup(final @NonNull String hostname);

    /**
     * A DNS that uses {@link InetAddress#getAllByName(String)} to ask the underlying operating system to lookup IP
     * addresses.
     * Most custom {@link Dns} implementations should delegate to this instance.
     */
    @NonNull
    Dns SYSTEM = new DnsSystem();
}
