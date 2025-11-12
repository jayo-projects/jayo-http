/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http;

import org.jspecify.annotations.NonNull;

/**
 * A tag associated to a {@link Call} that uses {@code type} as a key. Tags can be read from a call using
 * {@link Call#tag(Class)}.
 */
public record Tag<T>(@NonNull Class<? super T> type, @NonNull T value) {
}
