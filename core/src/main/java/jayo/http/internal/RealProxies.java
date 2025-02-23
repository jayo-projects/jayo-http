/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http.internal;

import jayo.http.HttpUrl;
import jayo.http.Proxies;
import jayo.network.Proxy;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static jayo.http.internal.HostnameUtils.domainMatch;
import static jayo.http.internal.HostnameUtils.toCanonicalHost;
import static jayo.http.internal.UrlUtils.percentDecode;

public final class RealProxies implements Proxies {
    private final @Nullable Proxy defaultProxy;
    private final @NonNull Map<@NonNull String, @Nullable Proxy> proxiesByHost;

    private RealProxies(final @Nullable Proxy defaultProxy,
                        final @NonNull Map<@NonNull String, @Nullable Proxy> proxiesByHost) {
        assert proxiesByHost != null;

        this.defaultProxy = defaultProxy;
        this.proxiesByHost = proxiesByHost;
    }

    @Override
    public @Nullable Proxy select(final @NonNull HttpUrl url) {
        Objects.requireNonNull(url);

        final var host = url.getHost();

        for (final var proxyByHost : proxiesByHost.entrySet()) {
            if (domainMatch(host, proxyByHost.getKey())) {
                return proxyByHost.getValue();
            }
        }

        return defaultProxy;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof RealProxies that)) {
            return false;
        }

        return Objects.equals(defaultProxy, that.defaultProxy) &&
                proxiesByHost.equals(that.proxiesByHost);
    }

    @Override
    public int hashCode() {
        var result = Objects.hashCode(defaultProxy);
        result = 31 * result + proxiesByHost.hashCode();
        return result;
    }

    public static final class Builder implements Proxies.Builder {
        private @Nullable Proxy defaultProxy = null;
        private final @NonNull Map<@NonNull String, @Nullable Proxy> proxiesByHost = new HashMap<>();

        @Override
        public @NonNull Builder defaultProxy(final @NonNull Proxy proxy) {
            this.defaultProxy = Objects.requireNonNull(proxy);
            return this;
        }

        @Override
        public @NonNull Builder setProxy(final @NonNull String host, final @Nullable Proxy proxy) {
            Objects.requireNonNull(host);

            final var cleanedHost = percentDecode(host.startsWith(".") ? host.substring(1) : host);
            final var encoded = toCanonicalHost(cleanedHost);
            if (encoded == null) {
                throw new IllegalArgumentException("unexpected host: " + host);
            }

            this.proxiesByHost.put(encoded, proxy);
            return this;
        }

        @Override
        public @NonNull Proxies build() {
            return new RealProxies(defaultProxy, proxiesByHost);
        }
    }
}
