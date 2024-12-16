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

@file:JvmName("-ClientResponseBody") // A leading '-' hides this class from Java.

package jayo.http

import jayo.ByteString
import jayo.Reader
import jayo.http.internal.StandardClientResponseBodies

/**
 * @return a new response body that transmits this string. If [contentType] is null or lacks a charset, it will use
 * UTF-8.
 */
public fun String.toResponseBody(contentType: MediaType? = null): ClientResponseBody =
    StandardClientResponseBodies.create(this, contentType)

/**
 * @return a new response body that transmits this ByteString. If [contentType] is null or lacks a charset, it will use
 * UTF-8.
 */
public fun ByteString.toResponseBody(contentType: MediaType? = null): ClientResponseBody =
    StandardClientResponseBodies.create(this, contentType)

/**
 * @return a new response body that transmits all bytes from this byte array. If [contentType] is null or lacks a
 * charset, it will use UTF-8.
 */
public fun ByteArray.toResponseBody(contentType: MediaType? = null): ClientResponseBody =
    StandardClientResponseBodies.create(this, contentType)

/**
 * @return a new response body that transmits [contentByteSize] (all if this value is not provided) from this reader. If
 * [contentType] is null or lacks a charset, it will use UTF-8.
 */
public fun Reader.asResponseBody(contentType: MediaType? = null, contentByteSize: Long = -1L): ClientResponseBody =
    StandardClientResponseBodies.create(this, contentType, contentByteSize)
