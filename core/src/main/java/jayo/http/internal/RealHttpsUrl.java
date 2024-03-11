/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http.internal;

import jayo.http.HttpsUrl;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

public final class RealHttpsUrl extends RealHttpUrl implements HttpsUrl {
    public RealHttpsUrl(
            final @NonNull String username,
            final @NonNull String password,
            final @NonNull String host,
            final int port,
            final @Nullable String fragment,
            final @NonNull List<@NonNull String> pathSegments,
            final @Nullable List<@Nullable String> queryNamesAndValues,
            final @NonNull String url
    ) {
        super("https", username, password, host, port, fragment, pathSegments, queryNamesAndValues, url);
    }

    @Override
    public @NonNull String getScheme() {
        return "https";
    }

    @Override
    public boolean isHttps() {
        return true;
    }
}
