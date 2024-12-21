/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http;

import jayo.external.NonNegative;
import org.jspecify.annotations.NonNull;

/**
 * Contains data of an HTTP response status.
 *
 * @param code    the HTTP status code.
 * @param message the HTTP status message.
 */
public record ResponseStatus(@NonNegative int code, @NonNull String message) {
}
