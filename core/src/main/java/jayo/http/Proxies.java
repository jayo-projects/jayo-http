/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http;

import jayo.http.internal.RealProxies;
import jayo.network.Proxy;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A set of user-defined proxies that will be used to {@linkplain #select(HttpUrl) select} the applicable proxy for HTTP
 * urls, if any.
 * <p>
 * Instances of this class are immutable.
 */
public sealed interface Proxies permits RealProxies {
    /**
     * Creates a {@link Proxies} with a single {@link Proxy} that will be used for all {@linkplain HttpUrl HTTP urls}.
     */
    static @NonNull Proxies create(final @NonNull Proxy proxy) {
        Objects.requireNonNull(proxy);
        return builder().defaultProxy(proxy).build();
    }

    static @NonNull Builder builder() {
        return new RealProxies.Builder();
    }

    /**
     * Empty proxies.
     */
    @NonNull
    Proxies EMPTY = builder().build();

    /**
     * @return the applicable proxy for {@code url}, or null. If no proxy is applicable, Jayo HTTP will use a direct
     * connection to connect to this url.
     */
    @Nullable
    Proxy select(final @NonNull HttpUrl url);

    /**
     * The builder used to create a {@link Proxies} instance.
     */
    sealed interface Builder permits RealProxies.Builder {
        /**
         * Sets the default proxy that will be used for any {@linkplain HttpUrl http url} if no url-specific proxy was
         * associated with this url.
         */
        @NonNull
        Builder defaultProxy(final @NonNull Proxy proxy);

        /**
         * Associates a proxy to a given host. This specific proxy will be {@linkplain #select(HttpUrl) selected} when
         * connecting to a url that matches this host.
         * <p>
         * Even if a {@linkplain #defaultProxy(Proxy) default proxy} is set, it will never be used for all urls that
         * match this host.
         *
         * @param host  can be
         *              <ul>
         *              <li>A full host like {@code test-api.example.org}.
         *              <li>A domain suffix, like {@code example.org} or {@code .example.com}.
         *              <li>An IP address like {@code test-api.example.org}
         *              <li>An IPv4 address, like {@code 127.0.0.1}.</li>
         *              <li>An IPv6 address, like {@code ::1}.
         *              </ul>
         * @param proxy the proxy associated with {@code host}. It may be {@code null} to force not using the
         *              {@linkplain #defaultProxy(Proxy) default proxy} for this host.
         */
        @NonNull
        Builder setProxy(final @NonNull String host, final @Nullable Proxy proxy);

        @NonNull
        Proxies build();
    }
}
