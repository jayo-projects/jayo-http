/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("-CacheControl") // A leading '-' hides this class from Java.

package jayo.http

import jayo.JayoDslMarker
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.toJavaDuration

public fun CacheControl.Builder.build(config: CacheControlBuilderDsl.() -> Unit): CacheControl {
    contract { callsInPlace(config, InvocationKind.EXACTLY_ONCE) }

    config(CacheControlBuilderDsl(this))
    return build()
}

@JayoDslMarker
@JvmInline
public value class CacheControlBuilderDsl internal constructor(private val builder: CacheControl.Builder) {
    /** Don't accept an unvalidated cached response. */
    public fun noCache() {
        builder.noCache()
    }

    /** Don't store the server's response in any cache. */
    public fun noStore() {
        builder.noStore()
    }

    /**
     * Only accept the response if it is in the cache. If the response isn't cached, a `504 Unsatisfiable Request`
     * response will be returned.
     */
    public fun onlyIfCached() {
        builder.onlyIfCached()
    }

    /** Don't accept a transformed response. */
    public fun noTransform() {
        builder.noTransform()
    }

    public fun immutable() {
        builder.immutable()
    }

    /**
     * Sets the maximum age of a cached response. If the cache response's age exceeds `maxAge`, it will not be used and
     * a network request will be made. Must be a non-negative duration. This is stored and transmitted with
     * `DurationUnit.SECONDS` precision; finer precision will be lost.
     */
    public var maxAge: Duration
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.maxAge(value.toJavaDuration())
        }

    public var maxStale: Duration
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.maxStale(value.toJavaDuration())
        }

    public var minFresh: Duration
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.minFresh(value.toJavaDuration())
        }
}
