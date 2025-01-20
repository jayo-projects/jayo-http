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

import jayo.http.internal.RealAddress;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import java.util.List;

/**
 * A specification for a connection to an origin server. For simple connections, this is the server's hostname and port.
 * For secure connections the address also includes the hostname verifier, and certificate pinner.
 * <p>
 * HTTP requests that share the same {@link Address} may also share the same {@link Connection}.
 * <p>
 * TODO: If an explicit proxy is requested (or {@linkplain java.net.Proxy#NO_PROXY no proxy} is explicitly requested),
 * this also includes that proxy information.
 */
public sealed interface Address permits RealAddress {
    /**
     * @return a URL with the hostname and port of the origin server. The path, query, and fragment of this URL are
     * always empty, since they are not significant for planning a route.
     */
    @NonNull
    HttpUrl getUrl();

    /**
     * The protocols the client supports. This method always returns a non-null list that contains minimally
     * {@link Protocol#HTTP_1_1}.
     */
    @NonNull
    List<Protocol> getProtocols();

    @NonNull
    List<ConnectionSpec> getConnectionSpecs();

    /**
     * @return the service that will be used to resolve IP addresses for hostnames.
     */
    @NonNull
    Dns getDns();

    /**
     * @return the hostname verifier, or null if this is not an HTTPS address.
     */
    @Nullable
    HostnameVerifier getHostnameVerifier();

    /**
     * @return this address's certificate pinner, or null if this is not an HTTPS address.
     */
    @Nullable
    CertificatePinner getCertificatePinner();

    // todo socketFactory: SocketFactory + sslSocketFactory: SSLSocketFactory? + proxyAuthenticator: Authenticator
    // todo proxy: Proxy? + proxySelector: ProxySelector
}
