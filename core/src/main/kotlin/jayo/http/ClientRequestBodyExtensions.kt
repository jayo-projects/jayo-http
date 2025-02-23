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

@file:JvmName("-ClientRequestBody") // A leading '-' hides this class from Java.

package jayo.http

import jayo.bytestring.ByteString
import jayo.http.internal.StandardClientRequestBodies
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Path

/**
 * @return a new request body that transmits this string. If [contentType] is null or lacks a charset, it will use
 * UTF-8.
 */
public fun String.toRequestBody(contentType: MediaType? = null): ClientRequestBody =
    StandardClientRequestBodies.create(this, contentType)

/**
 * @return a new request body that transmits this ByteString. If [contentType] is null or lacks a charset, it will use
 * UTF-8.
 */
public fun ByteString.toRequestBody(contentType: MediaType? = null): ClientRequestBody =
    StandardClientRequestBodies.create(this, contentType)

/**
 * @return a new request body that transmits [byteCount] bytes from this byte array, starting at [offset]. If
 * [contentType] is null or lacks a charset, it will use UTF-8.
 * @throws IndexOutOfBoundsException if [offset] or [byteCount] is out of range of this array indices.
 */
public fun ByteArray.toRequestBody(
    contentType: MediaType? = null,
    offset: Int = 0,
    byteCount: Int = size,
): ClientRequestBody = StandardClientRequestBodies.create(this, contentType, offset, byteCount)

/**
 * @return a new request body that transmits the content of this file path. If [contentType] is null or lacks a charset,
 * it will use UTF-8.
 */
public fun Path.asRequestBody(contentType: MediaType? = null): ClientRequestBody =
    StandardClientRequestBodies.create(this, contentType)

/**
 * @return a new request body that transmits the content of this file. If [contentType] is null or lacks a charset, it
 * will use UTF-8.
 */
public fun File.asRequestBody(contentType: MediaType? = null): ClientRequestBody =
    StandardClientRequestBodies.create(this, contentType)

/**
 * @return a new request body that transmits the content of the file associated with this file descriptor. If
 * [contentType] is null or lacks a charset, it will use UTF-8. This file descriptor represents an existing connection
 * to an actual file in the file system.
 */
public fun FileDescriptor.asRequestBody(contentType: MediaType? = null): ClientRequestBody =
    StandardClientRequestBodies.create(this, contentType)
