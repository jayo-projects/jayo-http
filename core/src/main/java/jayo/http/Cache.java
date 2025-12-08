/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2010 The Android Open Source Project
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
import jayo.http.internal.connection.RealJayoHttpClient;
import org.jspecify.annotations.NonNull;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;

import static jayo.http.internal.cache.RealCache.varyFields;

/**
 * Caches HTTP and HTTPS responses to the filesystem so they may be reused, saving time and bandwidth.
 * <p>
 * The Cache instance must have exclusive access to the {@code directory}, since the internal data structures may cause
 * corruption or runtime errors if not. It may, however, be shared amongst multiple JayoHttpClient instances.
 * <h2>Cache Optimization</h2>
 * To measure cache effectiveness, this class tracks three statistics:
 * <ul>
 * <li>{@linkplain #requestCount() Request Count:} the number of HTTP requests issued since this cache was created.
 * <li>{@linkplain #networkCount() Network Count:} the number of those requests that required network use.
 * <li>{@linkplain #hitCount() Hit Count:} the number of those requests whose responses were served by the cache.
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
 * ClientRequest request = ClientRequest.builder()
 *     .cacheControl(CacheControl.builder()
 *         .maxStale(Duration.ofDays(365))
 *         .build())
 *     .url("https://publicobject.com/helloworld.txt")
 *     .build();
 * }
 * </pre>
 * The {@link CacheControl} class can configure request caching directives and parse response caching directives. It
 * even offers convenient constants {@link CacheControl#FORCE_NETWORK} and {@link CacheControl#FORCE_CACHE} that address
 * the use cases above.
 */
public sealed interface Cache extends AutoCloseable permits RealCache {
    /**
     * Create a cache of at most {@code maxSize} bytes in {@code directory}.
     */
    static @NonNull Cache create(final @NonNull Path directory, final long maxSize) {
        Objects.requireNonNull(directory);
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0: " + maxSize);
        }

        return new RealCache(directory, maxSize, RealJayoHttpClient.DEFAULT_TASK_RUNNER);
    }

    /**
     * Initialize the cache. This will include reading the journal files from the storage and building up the necessary
     * in-memory cache information.
     * <p>
     * The initialization time may vary depending on the journal file size and the current actual cache size. The
     * application needs to be aware of calling this function during the initialization phase and preferably in a
     * background worker thread.
     * <p>
     * Note that if the application chooses to not call this method to initialize the cache. By default, Jayo HTTP will
     * perform lazy initialization upon the first usage of the cache.
     */
    void initialize();

    /**
     * Closes the cache and deletes all of its stored values. This will delete all files in the cache directory,
     * including files that weren't created by the cache.
     */
    void delete();

    /**
     * Deletes all values stored in the cache. In-flight writes to the cache will complete normally, but the
     * corresponding responses will not be stored.
     */
    void evictAll();

    /**
     * @return an iterator over the URLs in this cache. This iterator doesn't throw
     * {@code ConcurrentModificationException}, but if new responses are added while iterating, their URLs will not be
     * returned. If existing responses are evicted during iteration, they will be absent (unless they were already
     * returned).
     * <p>
     * The iterator supports {@link Iterator#remove()}. Removing a URL from the iterator evicts the corresponding
     * response from the cache. Use this to evict selected responses.
     */
    @NonNull Iterator<@NonNull String> urls();

    int writeAbortCount();

    int writeSuccessCount();

    int networkCount();

    int hitCount();

    int requestCount();

    /**
     * @return the number of bytes currently being used to store the values in this cache. This may be greater than the
     * max size if a background deletion is pending.
     */
    long byteSize();

    /**
     * Max size of the cache (in bytes).
     */
    long maxByteSize();

    boolean isClosed();

    /**
     * Closes this cache. Stored values will remain in the filesystem.
     */
    @Override
    void close();

    /**
     * Force buffered operations to the filesystem.
     */
    void flush();

    /**
     * @return the directory where this cache stores its data.
     */
    @NonNull Path getDirectory();

    /**
     * @return true if none of the Vary headers have changed between {@code cachedRequest} and {@code newRequest}.
     */
    static boolean varyMatches(final @NonNull ClientResponse cachedResponse,
                               final @NonNull Headers cachedRequest,
                               final @NonNull ClientRequest newRequest) {
        Objects.requireNonNull(cachedResponse);
        Objects.requireNonNull(cachedRequest);
        Objects.requireNonNull(newRequest);

        return varyFields(cachedResponse.getHeaders()).stream().allMatch(varyField ->
                cachedRequest.values(varyField).equals(newRequest.headers(varyField)));
    }

    /**
     * @return true if a Vary header contains an asterisk. Such responses cannot be cached.
     */
    static boolean hasVaryAll(final @NonNull ClientResponse response) {
        Objects.requireNonNull(response);
        return varyFields(response.getHeaders()).contains("*");
    }
}
