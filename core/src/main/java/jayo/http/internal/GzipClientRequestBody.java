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

import jayo.Jayo;
import jayo.Writer;
import jayo.http.ClientRequestBody;
import jayo.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

final class GzipClientRequestBody extends ClientRequestBody {
    private final ClientRequestBody delegate;

    GzipClientRequestBody(final @NonNull ClientRequestBody body) {
        assert body != null;
        this.delegate = body;
    }

    @Override
    public @Nullable MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public void writeTo(final @NonNull Writer destination) {
        Objects.requireNonNull(destination);

        try (final var gzipedDst = Jayo.buffer(Jayo.gzip(destination))) {
            delegate.writeTo(gzipedDst);
        }
    }

    @Override
    public boolean isOneShot() {
        return delegate.isOneShot();
    }
}
