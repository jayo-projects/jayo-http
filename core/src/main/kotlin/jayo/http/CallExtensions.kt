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

@file:JvmName("-Call") // A leading '-' hides this class from Java.

package jayo.http

import kotlin.reflect.KClass

/**
 * @return the tag attached with [T] as a key, or null if no tag is attached with that key.
 *
 * The tags on a call are seeded from the {@linkplain Factory#newCall(ClientRequest, Tag[]) call creation}. This set
 * will grow if new tags are computed.
 */
public inline fun <reified T : Any> Call.tag(): T? = tag(T::class.java)

/**
 * @return the tag attached with [T] as a key. If it is absent, then [computeIfAbsent] is called, and that value is both
 * inserted and returned.
 *
 * If multiple calls to this function are made concurrently with the same [type][T], multiple values may be computed.
 * But only one value will be inserted, and that inserted value will be returned to all callers.
 *
 * If computing multiple values is problematic, use an appropriate concurrency mechanism in your [computeIfAbsent]
 * implementation. No locks are held while calling this function.
 */
public inline fun <reified T : Any> Call.tag(noinline computeIfAbsent: () -> T): T =
    tag(T::class.java, computeIfAbsent)

/**
 * @return the tag attached with [type] as a key, or null if no tag is attached with that key.
 *
 * The tags on a call are seeded from the {@linkplain Factory#newCall(ClientRequest, Tag[]) call creation}. This set
 * will grow if new tags are computed.
 */
public fun <T : Any> Call.tag(type: KClass<T>): T? = tag(type.java)

/**
 * @return the tag attached with [type] as a key. If it is absent, then [computeIfAbsent] is called, and that value is
 * both inserted and returned.
 *
 * If multiple calls to this function are made concurrently with the same [type], multiple values may be computed. But
 * only one value will be inserted, and that inserted value will be returned to all callers.
 *
 * If computing multiple values is problematic, use an appropriate concurrency mechanism in your [computeIfAbsent]
 * implementation. No locks are held while calling this function.
 */
public fun <T : Any> Call.tag(type: KClass<T>, computeIfAbsent: () -> T): T = tag(type.java, computeIfAbsent)
