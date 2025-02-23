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

import jayo.http.Address;
import jayo.http.Route;
import jayo.network.Proxy;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;

import static jayo.http.internal.HostnameUtils.toCanonicalHost;

public final class RealRoute implements Route {
    private final @NonNull Address address;
    private final @NonNull InetSocketAddress socketAddress;

    RealRoute(final @NonNull Address address, final @NonNull InetSocketAddress socketAddress) {
        assert address != null;
        assert socketAddress != null;

        this.address = address;
        this.socketAddress = socketAddress;
    }

    @Override
    public @NonNull Address getAddress() {
        return address;
    }

    @Override
    public @NonNull InetSocketAddress getSocketAddress() {
        return socketAddress;
    }

    @Override
    public boolean requiresTunnel() {
        if (!(address.getProxy() instanceof Proxy.Http)) {
            return false;
        }
        return (address.getClientTlsEndpointBuilder() != null) ||
                (address.getProtocols().contains(Protocol.H2_PRIOR_KNOWLEDGE));
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof RealRoute that)) {
            return false;
        }

        return address.equals(that.address)
                && socketAddress.equals(that.socketAddress);
    }

    @Override
    public int hashCode() {
        var result = address.hashCode();
        result = 31 * result + socketAddress.hashCode();
        return result;
    }

    /**
     * @return a string with the URL hostname, socket IP address, and socket port, like one of these:
     * <ul>
     *  <li>{@code example.com:80 at 1.2.3.4:8888}
     *  <li>{@code example.com:443 via proxy [::1]:8888}
     * </ul>
     * This omits duplicate information when possible.
     */
    @Override
    public @NonNull String toString() {
        final var sb = new StringBuilder();

        final var addressHostname = address.getUrl().getHost();
        String socketHostname = null;
        if (socketAddress.getAddress() != null && socketAddress.getAddress().getHostAddress() != null) {
            socketHostname = toCanonicalHost(socketAddress.getAddress().getHostAddress());
        }

        if (addressHostname.contains(":")) {
            sb.append("[").append(addressHostname).append("]");
        } else {
            sb.append(addressHostname);
        }
        if ((address.getUrl().getPort() != socketAddress.getPort()) || (addressHostname.equals(socketHostname))) {
            sb.append(":");
            sb.append(address.getUrl().getPort());
        }

        if (!addressHostname.equals(socketHostname)) {
            if (address.getProxy() != null) {
                sb.append(" via proxy ");
            } else {
                sb.append(" at ");
            }
            if (socketHostname == null) {
                sb.append("<unresolved>");
            } else if (socketHostname.contains(":")) {
                sb.append("[").append(socketHostname).append("]");
            } else {
                sb.append(socketHostname);
            }

            sb.append(":");
            sb.append(socketAddress.getPort());
        }

        return sb.toString();
    }
}
