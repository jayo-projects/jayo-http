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

package jayo.http

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLSession

/** An [SSLEngine] that delegates all calls.  */
abstract class DelegatingSSLEngine(protected val delegate: SSLEngine?) : SSLEngine() {
    override fun wrap(srcs: Array<out ByteBuffer>?, offset: Int, length: Int, dst: ByteBuffer?): SSLEngineResult {
        return delegate!!.wrap(srcs, offset, length, dst)
    }

    override fun unwrap(src: ByteBuffer?, dsts: Array<out ByteBuffer>?, offset: Int, length: Int): SSLEngineResult {
        return delegate!!.unwrap(src, dsts, offset, length)
    }

    override fun getDelegatedTask(): Runnable {
        return delegate!!.delegatedTask
    }

    override fun closeInbound() {
        delegate!!.closeInbound()
    }

    override fun isInboundDone(): Boolean {
        return delegate!!.isInboundDone
    }

    override fun closeOutbound() {
        delegate!!.closeOutbound()
    }

    override fun isOutboundDone(): Boolean {
        return delegate!!.isOutboundDone
    }

    override fun getSupportedCipherSuites(): Array<String> {
        return delegate!!.supportedCipherSuites
    }

    override fun getEnabledCipherSuites(): Array<String> {
        return delegate!!.enabledCipherSuites
    }

    override fun setEnabledCipherSuites(suites: Array<String>) {
        delegate!!.enabledCipherSuites = suites
    }

    override fun getSupportedProtocols(): Array<String> {
        return delegate!!.supportedProtocols
    }

    override fun getEnabledProtocols(): Array<String> {
        return delegate!!.enabledProtocols
    }

    override fun setEnabledProtocols(protocols: Array<String>) {
        delegate!!.enabledProtocols = protocols
    }

    override fun getSession(): SSLSession {
        return delegate!!.session
    }

    override fun beginHandshake() {
        delegate!!.beginHandshake()
    }

    override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus {
        return delegate!!.handshakeStatus
    }

    override fun setUseClientMode(mode: Boolean) {
        delegate!!.useClientMode = mode
    }

    override fun getUseClientMode(): Boolean {
        return delegate!!.useClientMode
    }

    override fun setNeedClientAuth(need: Boolean) {
        delegate!!.needClientAuth = need
    }

    override fun getNeedClientAuth(): Boolean {
        return delegate!!.needClientAuth
    }

    override fun setWantClientAuth(want: Boolean) {
        delegate!!.wantClientAuth = want
    }

    override fun getWantClientAuth(): Boolean {
        return delegate!!.wantClientAuth
    }

    override fun setEnableSessionCreation(flag: Boolean) {
        delegate!!.enableSessionCreation = flag
    }

    override fun getEnableSessionCreation(): Boolean {
        return delegate!!.enableSessionCreation
    }
}
