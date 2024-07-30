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

import java.net.URL
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.reflect.KClass

/** Returns the tag attached with [T] as a key, or null if no tag is attached with that key. */
public inline fun <reified T : Any> ClientRequest.tag(): T? = tag(T::class.java)

/** Returns the tag attached with [type] as a key, or null if no tag is attached with that key. */
public fun <T : Any> ClientRequest.tag(type: KClass<T>): T? = tag(type.java)

/**
 * Attaches [tag] to the request using [T] as a key. Tags can be read from a request using [ClientRequest.tag]. Use null
 * to remove any existing tag assigned for [T].
 *
 * Use this API to attach timing, debugging, or other application data to a request so that you may read it in
 * interceptors, event listeners, or callbacks.
 */
public inline fun <reified T : Any> ClientRequest.Builder.tag(tag: T?) = tag(T::class.java, tag)

public fun ClientRequest.Builder.builder(config: ClientRequestBuilderDsl.() -> Unit): ClientRequest.Builder {
    contract { callsInPlace(config, InvocationKind.EXACTLY_ONCE) }

    config(ClientRequestBuilderDsl(this))
    return this
}

@JayoHttpDslMarker
@JvmInline
public value class ClientRequestBuilderDsl internal constructor(@PublishedApi internal val builder: ClientRequest.Builder) {
    public var url: HttpUrl
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.url(value)
        }

    public fun url(url: String) {
        builder.url(url)
    }

    public fun url(url: URL) {
        builder.url(url)
    }

    public fun header(
        name: String,
        value: String,
    ) {
        builder.header(name, value)
    }

    public fun addHeader(
        name: String,
        value: String,
    ) {
        builder.addHeader(name, value)
    }

    public fun removeHeader(name: String) {
        builder.removeHeader(name)
    }

    public var headers: Headers
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.headers(value)
        }

    public var cacheControl: CacheControl
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.cacheControl(value)
        }

    public inline fun <reified T : Any> tag(tag: T?) {
        builder.tag(T::class.java, tag)
    }

    public fun <T : Any> tag(
        type: KClass<T>,
        tag: T?,
    ) {
        builder.tag(type.java, tag)
    }

    public var cacheUrlOverride: HttpUrl?
        @Deprecated("Getter is unsupported.", level = DeprecationLevel.ERROR)
        get() = error("unsupported")
        set(value) {
            builder.cacheUrlOverride(value)
        }
}
