/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("-CacheControl") // A leading '-' hides this class from Java.

package jayo.http

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.toJavaDuration

public fun CacheControl.Builder.build(config: CacheControlBuilderDsl.() -> Unit): CacheControl {
    contract { callsInPlace(config, InvocationKind.EXACTLY_ONCE) }

    config(CacheControlBuilderDsl(this))
    return build()
}

@JayoHttpDslMarker
@JvmInline
public value class CacheControlBuilderDsl internal constructor(private val builder: CacheControl.Builder) {
    public fun noCache() {
        builder.noCache()
    }

    public fun noStore() {
        builder.noStore()
    }

    public fun onlyIfCached() {
        builder.onlyIfCached()
    }

    public fun noTransform() {
        builder.noTransform()
    }

    public fun immutable() {
        builder.immutable()
    }

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
