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

package jayo.http;

import jayo.http.internal.cache.RealCache;

import java.io.Closeable;
import java.io.Flushable;

/**
 * Caches HTTP and HTTPS responses to the filesystem so they may be reused, saving time and bandwidth.
 * <p>
 * The Cache instance must have exclusive access to the {@code directory}, since the internal data structures may cause
 * corruption or runtime errors if not. It may, however, be shared amongst multiple JayoHttpClient instances.
 * <h2>Cache Optimization</h2>
 * To measure cache effectiveness, this class tracks three statistics:
 * <ul>
 * <li>{@linkplain requestCount() Request Count:} the number of HTTP requests issued since this cache was created.
 * <li>{@linkplain networkCount() Network Count:} the number of those requests that required network use.
 * <li>{@linkplain hitCount() Hit Count:} the number of those requests whose responses were served by the cache.
 * </ul>
 * Sometimes a request will result in a conditional cache hit. If the cache contains a stale copy of the response, the
 * client will issue a conditional {@code GET}. The server will then send either the updated response if it has changed,
 * or a short 'not modified' response if the client's copy is still valid. Such responses increment both the network
 * count and hit count.
 * <p>
 * The best way to improve the cache hit rate is by configuring the web server to return cacheable responses. Although
 * this client honors all <a href="https://tools.ietf.org/html/rfc7234">HTTP/1.1 (RFC 7234)</a> cache headers, it
 * doesn't cache partial responses.
 * <h2>Force a Network Response</h2>
 * In some situations, such as after a user clicks a 'refresh' button, it may be necessary to skip the cache and fetch
 * data directly from the server. To force a full refresh, add the {@code no-cache} directive:
 * <pre>
 * {@code
 * ClientRequest request = ClientRequest.builder()
 *     .cacheControl(CacheControl.builder().noCache().build())
 *     .url("https://publicobject.com/helloworld.txt")
 *     .build();
 * }
 * </pre>
 * If it is only necessary to force a cached response to be validated by the server, use the more efficient
 * {@code max-age=0} directive instead:
 * <pre>
 * {@code
 * ClientRequest request = ClientRequest.builder()
 *     .cacheControl(CacheControl.builder()
 *         .maxAge(0, TimeUnit.SECONDS)
 *         .build())
 *     .url("https://publicobject.com/helloworld.txt")
 *     .build();
 * }
 * </pre>
 * <h2>Force a Cache Response</h2>
 * Sometimes you'll want to show resources if they are available immediately, but not otherwise. This can be used so
 * your application can show <i>something</i> while waiting for the latest data to be downloaded. To restrict a request
 * to locally cached resources, add the {@code only-if-cached} directive:
 * <pre>
 * {@code
 * ClientRequest request = ClientRequest.builder()
 *     .cacheControl(CacheControl.builder()
 *         .onlyIfCached()
 *         .build())
 *     .url("https://publicobject.com/helloworld.txt")
 *     .build();
 * ClientResponse forceCacheResponse = client.newCall(request).execute();
 * if (forceCacheResponse.code() != 504) {
 *   // The resource was cached! Show it.
 * } else {
 *   // The resource was not cached.
 * }
 * }
 * </pre>
 * This technique works even better in situations where a stale response is better than no response. To permit stale
 * cached responses, use the {@code max-stale} directive with the maximum staleness in seconds:
 * <pre>
 * {@code
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxStale(365, TimeUnit.DAYS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * }
 * </pre>
 * The {@link CacheControl} class can configure request caching directives and parse response caching directives. It
 * even offers convenient constants {@link CacheControl#FORCE_NETWORK} and {@link CacheControl#FORCE_CACHE} that address
 * the use cases above.
 */
public sealed interface Cache extends Closeable, Flushable permits RealCache {
    @Override
    void close();

    @Override
    void flush();
}
