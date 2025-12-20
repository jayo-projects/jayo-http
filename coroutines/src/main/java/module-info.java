/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

module jayo.http.coroutines {
    requires transitive jayo.http;
    requires transitive jayo;
    requires transitive kotlinx.coroutines.core;

    requires static org.jspecify;

    exports jayo.http.coroutines;
}
