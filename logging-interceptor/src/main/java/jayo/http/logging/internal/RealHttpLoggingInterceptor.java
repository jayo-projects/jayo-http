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

package jayo.http.logging.internal;

import jayo.Buffer;
import jayo.Jayo;
import jayo.Reader;
import jayo.http.ClientResponse;
import jayo.http.Headers;
import jayo.http.HttpUrl;
import jayo.http.logging.HttpLoggingInterceptor;
import jayo.tools.JayoUtils;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.*;

import static jayo.http.tools.JayoHttpUtils.*;

public final class RealHttpLoggingInterceptor implements HttpLoggingInterceptor {
    private final @NonNull Logger logger;
    private final @NonNull Level level;
    private final @NonNull Set<@NonNull String> headersToRedact;
    private final @NonNull Set<@NonNull String> queryParamsNameToRedact;

    private RealHttpLoggingInterceptor(
            final @NonNull Logger logger,
            final @NonNull Level level,
            final @NonNull Set<@NonNull String> headersToRedact,
            final @NonNull Set<@NonNull String> queryParamsNameToRedact
    ) {
        assert logger != null;
        assert level != null;
        assert headersToRedact != null;
        assert queryParamsNameToRedact != null;

        this.logger = logger;
        this.level = level;
        this.headersToRedact = headersToRedact;
        this.queryParamsNameToRedact = queryParamsNameToRedact;
    }

    @Override
    public @NonNull ClientResponse intercept(final @NonNull Chain chain) {
        assert chain != null;

        final var level = this.level;
        var request = chain.request();

        if (level == Level.NONE) {
            return chain.proceed(request);
        }

        final var logBody = level == Level.BODY;
        final var logHeaders = logBody || level == Level.HEADERS;

        final var connection = chain.connection();

        final var requestBody = request.getBody();
        var requestStartMessage = "--> " + request.getMethod() + " " + redactUrl(request.getUrl()) +
                ((connection != null) ? " " + connection.protocol() : "");
        if (!logHeaders && requestBody != null) {
            requestStartMessage += " (" + requestBody.contentByteSize() + "-byte body)";
        }
        logger.log(requestStartMessage);

        if (logHeaders) {
            final var headers = request.getHeaders();

            if (requestBody != null) {
                // Request body headers are only present when installed as a network interceptor. When not already
                // present, force them to be included (if available) so their values are known.
                final var contentType = requestBody.contentType();
                if (contentType != null) {
                    if (headers.get("Content-Type") == null) {
                        logger.log("Content-Type: " + contentType);
                    }
                }
                if (requestBody.contentByteSize() != -1L) {
                    if (headers.get("Content-Length") == null) {
                        logger.log("Content-Length: " + requestBody.contentByteSize());
                    }
                }
            }

            for (var i = 0; i < headers.size(); i++) {
                logHeader(headers, i);
            }

            if (!logBody || requestBody == null) {
                logger.log("--> END " + request.getMethod());
            } else if (bodyHasUnknownEncoding(headers)) {
                logger.log("--> END " + request.getMethod() + " (encoded body omitted)");
            } else if (requestBody.isDuplex()) {
                logger.log("--> END " + request.getMethod() + " (duplex request body omitted)");
            } else if (requestBody.isOneShot()) {
                logger.log("--> END " + request.getMethod() + " (one-shot body omitted)");
            } else {
                var buffer = Buffer.create();
                requestBody.writeTo(buffer);

                Long gzippedLength = null;
                if ("gzip".equalsIgnoreCase(headers.get("Content-Encoding"))) {
                    gzippedLength = buffer.bytesAvailable();
                    try (final var gzippedResponseBody = Jayo.gzip((Reader) buffer)) {
                        buffer = Buffer.create();
                        buffer.writeAllFrom(gzippedResponseBody);
                    }
                }

                final var charset = charsetOrUtf8(requestBody.contentType());

                logger.log("");
                if (!isProbablyUtf8(buffer, 16L)) {
                    logger.log(
                            "--> END " + request.getMethod() +
                                    " (binary " + requestBody.contentByteSize() + "-byte body omitted)"
                    );
                } else if (gzippedLength != null) {
                    logger.log("--> END " + request.getMethod() +
                            " (" + buffer.bytesAvailable() + "-byte, " + gzippedLength + "-gzipped-byte body)");
                } else {
                    logger.log(buffer.readString(charset));
                    logger.log("--> END " + request.getMethod() + " (" + requestBody.contentByteSize() + "-byte body)");
                }
            }
        }

        final var startNs = System.nanoTime();
        final ClientResponse response;
        try {
            response = chain.proceed(request);
        } catch (Exception e) {
            final var tookMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            logger.log("<-- HTTP FAILED: " + e + ". " + redactUrl(request.getUrl()) + " (" + tookMs + "ms)");
            throw e;
        }

        final var tookMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

        final var responseBody = response.getBody();
        final var contentByteSize = responseBody.contentByteSize();
        final var bodySize = (contentByteSize != -1L) ? contentByteSize + "-byte" : "unknown-length";
        logger.log("<-- " + response.getStatusCode() +
                (response.getStatusMessage().isEmpty() ? "" : " " + response.getStatusMessage()) +
                " " + redactUrl(response.getRequest().getUrl()) + " (" + tookMs + "ms" +
                (!logHeaders ? ", " + bodySize + " body" : "") +
                ")");

        if (logHeaders) {
            final var headers = response.getHeaders();
            for (var i = 0; i < headers.size(); i++) {
                logHeader(headers, i);
            }

            if (!logBody || !promisesBody(response)) {
                logger.log("<-- END HTTP");
            } else if (bodyHasUnknownEncoding(headers)) {
                logger.log("<-- END HTTP (encoded body omitted)");
            } else if (bodyIsStreaming(response)) {
                logger.log("<-- END HTTP (streaming)");
            } else {
                final var reader = responseBody.reader();
                reader.request(Long.MAX_VALUE); // Buffer the entire body.

                final var totalMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

                var buffer = JayoUtils.buffer(reader);

                Long gzippedLength = null;
                if ("gzip".equalsIgnoreCase(headers.get("Content-Encoding"))) {
                    gzippedLength = buffer.bytesAvailable();
                    try (final var gzippedResponseBody = Jayo.gzip((Reader) buffer.clone())) {
                        buffer = Buffer.create();
                        buffer.writeAllFrom(gzippedResponseBody);
                    }
                }

                final var charset = charsetOrUtf8(responseBody.contentType());

                if (!isProbablyUtf8(buffer, 16L)) {
                    logger.log("");
                    logger.log("<-- END HTTP (" + totalMs +
                            "ms, binary " + buffer.bytesAvailable() + "-byte body omitted)");
                    return response;
                }

                if (contentByteSize != 0L) {
                    logger.log("");
                    logger.log(buffer.clone().readString(charset));
                }

                logger.log(
                        "<-- END HTTP (" +
                                totalMs + "ms, " + buffer.bytesAvailable() + "-byte" +
                                ((gzippedLength != null) ? ", " + gzippedLength + "-gzipped-byte" : "") +
                                " body)"
                );
            }
        }

        return response;
    }

    private static boolean bodyHasUnknownEncoding(final @NonNull Headers headers) {
        assert headers != null;

        // Retrieve the Content-Encoding header or return false if null
        final var contentEncoding = headers.get("Content-Encoding");
        if (contentEncoding == null) {
            return false;
        }
        return !contentEncoding.equalsIgnoreCase("identity") &&
                !contentEncoding.equalsIgnoreCase("gzip");
    }

    private static boolean bodyIsStreaming(final @NonNull ClientResponse response) {
        assert response != null;

        final var contentType = response.getBody().contentType();
        return contentType != null &&
                "text".equals(contentType.getType()) &&
                "event-stream".equals(contentType.getSubtype());
    }

    private void logHeader(final @NonNull Headers headers, final int index) {
        assert headers != null;

        final var value = headersToRedact.contains(headers.name(index)) ? "██" : headers.value(index);
        logger.log(headers.name(index) + ": " + value);
    }

    @NonNull
    String redactUrl(final @NonNull HttpUrl url) {
        assert url != null;

        if (queryParamsNameToRedact.isEmpty() || url.getQuerySize() == 0) {
            return url.toString();
        }
        final var urlBuilder = url.newBuilder()
                .query(null);
        for (var i = 0; i < url.getQuerySize(); i++) {
            final var parameterName = url.queryParameterName(i);
            final var newValue = queryParamsNameToRedact.contains(parameterName)
                    ? "██"
                    : url.queryParameterValue(i);

            urlBuilder.addEncodedQueryParameter(parameterName, newValue);
        }
        return urlBuilder.toString();
    }

    @Override
    public @NonNull Level getLevel() {
        return level;
    }

    public static final class Builder implements HttpLoggingInterceptor.Builder {
        private @NonNull Logger logger = Logger.DEFAULT;
        private @NonNull Level level = Level.NONE;
        private final @NonNull Set<@NonNull String> headersToRedact = new HashSet<>();
        private final @NonNull Set<@NonNull String> queryParamsNameToRedact = new HashSet<>();

        @Override
        public @NonNull Builder logger(final @NonNull Logger logger) {
            this.logger = Objects.requireNonNull(logger);
            return this;
        }

        @Override
        public @NonNull Builder level(final @NonNull Level level) {
            this.level = Objects.requireNonNull(level);
            return this;
        }

        @Override
        public @NonNull Builder redactHeader(final @NonNull String name) {
            Objects.requireNonNull(name);

            headersToRedact.add(name);
            return this;
        }

        @Override
        public @NonNull Builder redactQueryParams(final @NonNull String @NonNull ... names) {
            Objects.requireNonNull(names);

            Collections.addAll(queryParamsNameToRedact, names);
            return this;
        }

        @Override
        public @NonNull HttpLoggingInterceptor build() {
            // build case-insensitive copies of current sets
            var _headersToRedact = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            _headersToRedact.addAll(this.headersToRedact);
            var _queryParamsNameToRedact = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            _queryParamsNameToRedact.addAll(this.queryParamsNameToRedact);

            return new RealHttpLoggingInterceptor(
                    logger,
                    level,
                    _headersToRedact,
                    _queryParamsNameToRedact
            );
        }
    }
}
