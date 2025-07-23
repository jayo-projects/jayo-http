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

package jayo.http.http2;

/**
 * <a href="https://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-7">HTTP2 error codes</a>
 */
public enum ErrorCode {
    /**
     * Not an error!
     */
    NO_ERROR(0),

    PROTOCOL_ERROR(1),

    INTERNAL_ERROR(2),

    FLOW_CONTROL_ERROR(3),

    SETTINGS_TIMEOUT(4),

    STREAM_CLOSED(5),

    FRAME_SIZE_ERROR(6),

    REFUSED_STREAM(7),

    CANCEL(8),

    COMPRESSION_ERROR(9),

    CONNECT_ERROR(0xa),

    ENHANCE_YOUR_CALM(0xb),

    INADEQUATE_SECURITY(0xc),

    HTTP_1_1_REQUIRED(0xd);

    private final int httpCode;

    ErrorCode(final int httpCode) {
        this.httpCode = httpCode;
    }

    public int getHttpCode() {
        return httpCode;
    }

    public static ErrorCode fromHttp2(final int code) {
        for (final var errorCode : values()) {
            if (errorCode.httpCode == code) {
                return errorCode;
            }
        }
        return null;
    }
}
