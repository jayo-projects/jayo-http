/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("-JayoHttpClient") // Leading '-' hides this class from Java.

package jayo.http

/** @return a new [JayoHttpClient] with good defaults. */
public fun JayoHttpClient(): JayoHttpClient = JayoHttpClient.create()