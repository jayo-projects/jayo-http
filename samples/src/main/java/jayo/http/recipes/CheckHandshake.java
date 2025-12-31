/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
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

package jayo.http.recipes;

import jayo.JayoException;
import jayo.http.*;

import java.security.cert.Certificate;
import java.util.Set;

public final class CheckHandshake {
    /**
     * Rejects otherwise-trusted certificates.
     */
    private static final Interceptor CHECK_HANDSHAKE_INTERCEPTOR = new Interceptor() {
        final Set<String> denylist = Set.of("sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=");

        @Override
        public ClientResponse intercept(Chain chain) {
            for (Certificate certificate : chain.connection().handshake().getPeerCertificates()) {
                String pin = CertificatePinner.pin(certificate);
                if (denylist.contains(pin)) {
                    throw new JayoException("Denylisted peer certificate: " + pin);
                }
            }
            return chain.proceed(chain.request());
        }
    };

    private final JayoHttpClient client = JayoHttpClient.builder()
            .addNetworkInterceptor(CHECK_HANDSHAKE_INTERCEPTOR)
            .build();

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/initial/samples/src/main/resources/jayo-http.txt")
                .get();

        try (ClientResponse response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new JayoException("Unexpected code " + response.getStatusCode());
            }

            System.out.println(response.getBody().string());
        }
    }

    public static void main(String... args) {
        new CheckHandshake().run();
    }
}
