/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package jayo.http.internal.http;

import jayo.Jayo;
import jayo.http.ClientResponse;
import jayo.http.Cookie;
import jayo.http.Interceptor;
import jayo.http.internal.StandardClientResponseBodies;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static jayo.http.internal.UrlUtils.toHostHeader;
import static jayo.http.internal.Utils.USER_AGENT;
import static jayo.http.internal.http.HttpHeaders.receiveHeaders;
import static jayo.http.tools.JayoHttpUtils.promisesBody;

/**
 * Bridges from application code to network code.
 * <ul>
 * <li>First, it builds a network request from a user request.
 * <li>Then it proceeds to call the network.
 * <li>Finally, it builds a user response from the network response.
 * </ul>
 */
public enum BridgeInterceptor implements Interceptor {
    INSTANCE;

    @Override
    public @NonNull ClientResponse intercept(final @NonNull Chain chain) {
        assert chain != null;

        final var userRequest = chain.request();
        final var requestBuilder = userRequest.newBuilder();

        final var body = userRequest.getBody();
        if (body != null) {
            final var contentType = body.contentType();
            if (contentType != null) {
                requestBuilder.header("Content-Type", contentType.toString());
            }

            final var contentByteSize = body.contentByteSize();
            if (contentByteSize != -1L) {
                requestBuilder.header("Content-Length", Long.toString(contentByteSize));
                requestBuilder.removeHeader("Transfer-Encoding");
            } else {
                requestBuilder.header("Transfer-Encoding", "chunked");
                requestBuilder.removeHeader("Content-Length");
            }
        }

        if (userRequest.header("Host") == null) {
            requestBuilder.header("Host", toHostHeader(userRequest.getUrl(), false));
        }

        if (userRequest.header("Connection") == null) {
            requestBuilder.header("Connection", "Keep-Alive");
        }

        // If we add an "Accept-Encoding: gzip" header field, we're responsible for also decompressing the transfer
        // stream.
        var transparentGzip = false;
        if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
            transparentGzip = true;
            requestBuilder.header("Accept-Encoding", "gzip");
        }

        final var cookieJar = chain.client().getCookieJar();
        final var cookies = cookieJar.loadForRequest(userRequest.getUrl());
        if (!cookies.isEmpty()) {
            requestBuilder.header("Cookie", cookieHeader(cookies));
        }

        if (userRequest.header("User-Agent") == null) {
            requestBuilder.header("User-Agent", USER_AGENT);
        }

        final var networkRequest = requestBuilder.build();
        final var networkResponse = chain.proceed(networkRequest);

        receiveHeaders(cookieJar, networkRequest.getUrl(), networkResponse.getHeaders());

        final var responseBuilder = networkResponse.newBuilder()
                .request(networkRequest);

        if (transparentGzip &&
                "gzip".equalsIgnoreCase(networkResponse.header("Content-Encoding")) &&
                promisesBody(networkResponse)) {
            final var responseBody = networkResponse.getBody();
            final var gzipSource = Jayo.gzip(responseBody.reader());
            final var strippedHeaders = networkResponse.getHeaders().newBuilder()
                    .removeAll("Content-Encoding")
                    .removeAll("Content-Length")
                    .build();
            responseBuilder.headers(strippedHeaders);

            final var contentType = networkResponse.header("Content-Type");
            responseBuilder.body(
                    StandardClientResponseBodies.create(Jayo.buffer(gzipSource), contentType, -1L));
        }

        return responseBuilder.build();
    }

    /**
     * Returns a 'Cookie' HTTP request header with all cookies, like {@code a=b; c=d}.
     */
    private @NonNull String cookieHeader(final @NonNull List<@NonNull Cookie> cookies) {
        assert cookies != null;

        final var result = new StringBuilder();
        for (var index = 0; index < cookies.size(); index++) {
            if (index > 0) {
                result.append("; ");
            }
            final var cookie = cookies.get(index);
            result.append(cookie.getName()).append('=').append(cookie.getValue());
        }
        return result.toString();
    }
}
