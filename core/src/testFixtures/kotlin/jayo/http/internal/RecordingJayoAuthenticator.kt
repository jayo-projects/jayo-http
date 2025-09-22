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

package jayo.http.internal

import jayo.http.Authenticator
import jayo.http.ClientRequest
import jayo.http.ClientResponse
import jayo.http.Route
import java.io.IOException

class RecordingJayoAuthenticator(
    val credential: String?,
    val scheme: String?,
) : Authenticator {
    val responses = mutableListOf<ClientResponse>()
    val routes = mutableListOf<Route>()

    fun onlyResponse() = responses.single()

    fun onlyRoute() = routes.single()

    @Throws(IOException::class)
    override fun authenticate(
        route: Route?,
        response: ClientResponse,
    ): ClientRequest? {
        if (route == null) {
            throw NullPointerException("route == null")
        }
        responses += response
        routes += route
        if (!schemeMatches(response) || credential == null) return null
        val header =
            when (response.statusCode) {
                407 -> "Proxy-Authorization"
                else -> "Authorization"
            }
        return response.request
            .newBuilder()
            .addHeader(header, credential)
            .build()
    }

    private fun schemeMatches(response: ClientResponse): Boolean {
        if (scheme == null) {
            return true
        }
        return response.challenges().any { it.scheme.equals(scheme, ignoreCase = true) }
    }
}