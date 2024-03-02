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

import jayo.http.HttpsUrl;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Set;

public final class RealHttpsUrl implements HttpsUrl {
    @Override
    public @NonNull String getEncodedUsername() {
        return null;
    }

    @Override
    public @NonNull String getEncodedPassword() {
        return null;
    }

    @Override
    public @NonNull String getEncodedPath() {
        return null;
    }

    @Override
    public @NonNull List<String> getEncodedPathSegments() {
        return null;
    }

    @Override
    public @Nullable String getEncodedQuery() {
        return null;
    }

    @Override
    public @NonNull String getUsername() {
        return null;
    }

    @Override
    public @NonNull String getPassword() {
        return null;
    }

    @Override
    public @NonNull String getHost() {
        return null;
    }

    @Override
    public int getPort() {
        return 0;
    }

    @Override
    public @NonNull List<@NonNull String> getPathSegments() {
        return null;
    }

    @Override
    public @Nullable String getFragment() {
        return null;
    }

    @Override
    public @NonNull URL toUrl() {
        return null;
    }

    @Override
    public @NonNull URI toUri() {
        return null;
    }

    @Override
    public int getPathSize() {
        return 0;
    }

    @Override
    public @Nullable String getQuery() {
        return null;
    }

    @Override
    public int getQuerySize() {
        return 0;
    }

    @Override
    public @Nullable String getQueryParameter(@NonNull String name) {
        return null;
    }

    @Override
    public @NonNull Set<@NonNull String> getQueryParameterNames() {
        return null;
    }

    @Override
    public @NonNull List<@Nullable String> getQueryParameterValues(@NonNull String name) {
        return null;
    }

    @Override
    public @NonNull String getQueryParameterName(int index) {
        return null;
    }

    @Override
    public @Nullable String getQueryParameterValue(int index) {
        return null;
    }

    @Override
    public @Nullable String getEncodedFragment() {
        return null;
    }

    @Override
    public @NonNull String redact() {
        return null;
    }

    @Override
    public @Nullable HttpsUrl resolve(@NonNull String link) {
        return null;
    }

    @Override
    public @Nullable String topPrivateDomain() {
        return null;
    }
}
