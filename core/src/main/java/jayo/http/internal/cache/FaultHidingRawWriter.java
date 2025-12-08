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

package jayo.http.internal.cache;

import jayo.*;
import org.jspecify.annotations.NonNull;

import java.util.function.Consumer;

/**
 * A RawWriter that never throws JayoExceptions, even if the underlying writer does.
 */
final class FaultHidingRawWriter implements RawWriter {
    private final @NonNull RawWriter delegate;
    private final @NonNull Consumer<@NonNull JayoException> onException;
    private boolean hasErrors = false;

    FaultHidingRawWriter(final @NonNull RawWriter delegate,
                         final @NonNull Consumer<@NonNull JayoException> onException) {
        assert delegate != null;
        assert onException != null;

        this.delegate = delegate;
        this.onException = onException;
    }

    @Override
    public void writeFrom(final @NonNull Buffer source, final long byteCount) {
        assert source != null;

        if (hasErrors) {
            source.skip(byteCount);
            return;
        }
        try {
            delegate.writeFrom(source, byteCount);
        } catch (JayoException e) {
            if (e instanceof JayoInterruptedIOException) {
                throw e;
            }
            hasErrors = true;
            onException.accept(e);
        }
    }

    @Override
    public void flush() {
        if (hasErrors) {
            return;
        }
        try {
            delegate.flush();
        } catch (JayoException e) {
            hasErrors = true;
            onException.accept(e);
        }
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } catch (JayoException e) {
            hasErrors = true;
            onException.accept(e);
        }
    }
}
