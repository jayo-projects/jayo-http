/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http;

import jayo.http.internal.RealHttpsUrl;

/**
 * A uniform resource locator (URL) with a required scheme of {@code https}.
 * @see HttpUrl for ull description.
 */
public sealed interface HttpsUrl extends HttpUrl permits RealHttpsUrl {
}
