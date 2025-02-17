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

@file:JvmName("-HttpUrl") // A leading '-' hides this class from Java.

package jayo.http

import jayo.JayoDslMarker
import java.net.URI
import java.net.URL
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * @return a new [HttpUrl] representing this.
 *
 * @throws IllegalArgumentException If this is not a well-formed HTTP or HTTPS URL.
 */
public fun String.toHttpUrl(): HttpUrl = HttpUrl.get(this)

/**
 * @return a new [HttpUrl] representing `url` if it is a well-formed HTTP or HTTPS URL, or null if it isn't.
 */
public fun String.toHttpUrlOrNull(): HttpUrl? = HttpUrl.parse(this)

/**
 * @return an [HttpUrl] for this if its protocol is `http` or `https`, or null if it has any other protocol.
 */
public fun URL.toHttpUrlOrNull(): HttpUrl? = HttpUrl.parse(this)

public fun URI.toHttpUrlOrNull(): HttpUrl? = HttpUrl.parse(this)

public fun HttpUrl.Builder.build(config: HttpUrlBuilderDsl.() -> Unit): HttpUrl {
    contract { callsInPlace(config, InvocationKind.EXACTLY_ONCE) }

    config(HttpUrlBuilderDsl(this))
    return build()
}

@JayoDslMarker
@JvmInline
public value class HttpUrlBuilderDsl internal constructor(private val builder: HttpUrl.Builder) {
    /** Sets the scheme; either "http" or "https". */
    public var scheme: String
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.scheme(value)
        }

    /**
     * Sets the host; either a regular hostname, International Domain Name, IPv4 address, or IPv6 address. */
    public var host: String
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.host(value)
        }

    public var port: Int
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.port(value)
        }

    public var username: String
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.username(value)
        }

    public var encodedUsername: String
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.encodedUsername(value)
        }

    public var password: String
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.password(value)
        }

    public var encodedPassword: String
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.encodedPassword(value)
        }

    public fun addPathSegment(pathSegment: String) {
        builder.addPathSegment(pathSegment)
    }

    /**
     * Adds a set of path segments separated by a slash (either `\` or `/`). If [pathSegments] starts with a slash, the
     * resulting URL will have empty path segment.
     */
    public fun addPathSegments(pathSegments: String) {
        builder.addPathSegments(pathSegments)
    }

    public fun addEncodedPathSegment(encodedPathSegment: String) {
        builder.addEncodedPathSegment(encodedPathSegment)
    }

    /**
     * Adds a set of encoded path segments separated by a slash (either `\` or `/`). If [encodedPathSegments] starts
     * with a slash, the resulting URL will have empty path segment.
     */
    public fun addEncodedPathSegments(encodedPathSegments: String) {
        builder.addEncodedPathSegments(encodedPathSegments)
    }

    public fun setPathSegment(index: Int, pathSegment: String) {
        builder.setPathSegment(index, pathSegment)
    }

    public fun setEncodedPathSegment(index: Int, encodedPathSegment: String) {
        builder.setEncodedPathSegment(index, encodedPathSegment)
    }

    public fun removePathSegment(index: Int) {
        builder.removePathSegment(index)
    }

    public var encodedPath: String
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.encodedPath(value)
        }

    public var query: String?
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.query(value)
        }

    public var encodedQuery: String?
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.encodedQuery(value)
        }

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    public fun addQueryParameter(name: String, value: String?) {
        builder.addQueryParameter(name, value)
    }

    /** Adds the pre-encoded query parameter to this URL's query string. */
    public fun addEncodedQueryParameter(encodedName: String, encodedValue: String?) {
        builder.addEncodedQueryParameter(encodedName, encodedValue)
    }

    public fun setQueryParameter(name: String, value: String?) {
        builder.setQueryParameter(name, value)
    }

    public fun setEncodedQueryParameter(encodedName: String, encodedValue: String?) {
        builder.setEncodedQueryParameter(encodedName, encodedValue)
    }

    public fun removeAllQueryParameters(name: String) {
        builder.removeAllQueryParameters(name)
    }

    public fun removeAllEncodedQueryParameters(encodedName: String) {
        builder.removeAllEncodedQueryParameters(encodedName)
    }

    public fun removeAllCanonicalQueryParameters(canonicalName: String) {
        builder.removeAllCanonicalQueryParameters(canonicalName)
    }

    public var fragment: String?
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.fragment(value)
        }

    public var encodedFragment: String?
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.encodedFragment(value)
        }
}
