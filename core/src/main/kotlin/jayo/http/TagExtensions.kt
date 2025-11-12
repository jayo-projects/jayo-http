/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("-Tag") // A leading '-' hides this class from Java.

package jayo.http

import kotlin.reflect.KClass

/**
 * A tag associated to a [Call] that uses [T] as a key. Tags can be read from a call using [Call.tag].
 */
public inline fun <reified T : Any> Tag(value: T): Tag<T> = Tag(T::class.java, value)

/**
 * A tag associated to a [Call] that uses [type] as a key. Tags can be read from a call using [Call.tag].
 */
public fun <T : Any> Tag(type: KClass<T>, value: T): Tag<T> = Tag(type.java, value)
