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

@file:JvmName("-ClientRequest") // A leading '-' hides this class from Java.

package jayo.http

import jayo.JayoDslMarker
import java.net.URL
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

public fun ClientRequest.Builder.build(config: NewClientRequestBuilderDsl.() -> Unit): ClientRequest.Builder {
    contract { callsInPlace(config, InvocationKind.EXACTLY_ONCE) }

    config(NewClientRequestBuilderDsl(this))
    return this
}

public fun ClientRequest.FromClientRequestBuilder.build(config: ClientRequestBuilderDsl.() -> Unit): ClientRequest {
    contract { callsInPlace(config, InvocationKind.EXACTLY_ONCE) }

    config(ClientRequestBuilderDsl(this))
    return build()
}

@JayoDslMarker
public class NewClientRequestBuilderDsl internal constructor(
    private val builder: ClientRequest.Builder
) : ClientRequestBuilderDsl(builder) {
    /** Remove all headers on this builder and adds headers. */
    public var headers: Headers
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.headers(value)
        }
}

@JayoDslMarker
public open class ClientRequestBuilderDsl internal constructor(private val builder: ClientRequest.AbstractBuilder<*>) {
    /** Sets the URL target of this request. */
    public var url: HttpUrl
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.url(value)
        }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if [url] is not a valid HTTP or HTTPS URL. Avoid this exception by calling
     * [HttpUrl.parse]; it returns null for invalid URLs.
     */
    public fun url(url: String) {
        builder.url(url)
    }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if the scheme of `url` is not `http` or `https`.
     */
    public fun url(url: URL) {
        builder.url(url)
    }

    /**
     * Sets the header with the specified name and value. If this request already has any headers with that name,
     * they are all replaced.
     */
    public fun header(
        name: String,
        value: String,
    ) {
        builder.header(name, value)
    }

    /**
     * Adds a header with the specified name and value. Prefer this method for multiple-valued headers like `Cookie`
     *
     * Note that for some headers including `Content-Length` and `Content-Encoding`, Jayo HTTP may replace [value] with
     * a header derived from the request body.
     */
    public fun addHeader(
        name: String,
        value: String,
    ) {
        builder.addHeader(name, value)
    }

    /** Remove all values of a given header name. */
    public fun removeHeader(name: String) {
        builder.removeHeader(name)
    }

    /**
     * Sets this request's `Cache-Control` header, replacing any cache control headers already present. If
     * [cacheControl] doesn't define any directives, this clears this request's cache-control headers.
     */
    public var cacheControl: CacheControl
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.cacheControl(value)
        }

    /**
     * Override the [ClientRequest.url][ClientRequest.getUrl] for caching, if it is either polluted with transient query
     * params, or has a canonical URL possibly for a CDN.
     *
     * Note that POST requests will not be sent to the server if this URL is set and matches a cached response.
     */
    public var cacheUrlOverride: HttpUrl?
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.cacheUrlOverride(value)
        }

    /**
     * When set to `true`, configures this request's body to be compressed when it is transmitted. Default is false.
     * This also adds the `Content-Encoding: gzip` header. If a `Content-Encoding` header was already present, it is
     * discarded and replaced by `gzip` value.
     *
     * * Only use this method if you have prior knowledge that the receiving server supports gzip-compressed requests.
     * * This option is no-op if this request doesn't have a request body.
     */
    public var gzip: Boolean
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.gzip(value)
        }
}
