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

package jayo.http;

import jayo.http.internal.RealRoute;
import org.jspecify.annotations.NonNull;

import java.net.InetSocketAddress;

/**
 * The concrete route used by a connection to reach an abstract origin server. When creating a connection the client has
 * one option (proxy/proxy selector will be supported later) :
 * <ul>
 * <li><b>IP address:</b> whether connecting directly to an origin server or a proxy, opening a socket requires an IP
 * address. The DNS server may return multiple IP addresses to attempt.
 * <li><b>TODO: HTTP proxy:</b> a proxy server may be explicitly configured for the client. Otherwise, the
 * {@linkplain java.net.ProxySelector proxy selector} is used. It may return multiple proxies to attempt.
 * </ul>
 * Each route is a specific selection of this option.
 */
public sealed interface Route permits RealRoute {
    @NonNull
    Address getAddress();

    @NonNull
    InetSocketAddress getSocketAddress();

    // todo proxy + requiresTunnel
}
