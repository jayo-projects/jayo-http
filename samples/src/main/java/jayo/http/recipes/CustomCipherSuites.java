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
import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.ConnectionSpec;
import jayo.http.JayoHttpClient;
import jayo.tls.CipherSuite;
import jayo.tls.ClientHandshakeCertificates;
import jayo.tls.ClientTlsSocket;

import java.util.List;

public final class CustomCipherSuites {
    private final JayoHttpClient client;

    public CustomCipherSuites() {
        // Configure cipher suites to demonstrate how to customize which cipher suites will be used for a Jayo HTTP
        // request. To be selected, a cipher suite must be included in Jayo HTTP's connection spec. Most applications
        // should not customize the cipher suites list.
        List<CipherSuite> customCipherSuites = List.of(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384);
        final ConnectionSpec spec = ConnectionSpec.builder(ConnectionSpec.MODERN_TLS)
                .cipherSuites(customCipherSuites)
                .build();

        client = JayoHttpClient.builder()
                .connectionSpecs(List.of(spec))
                .tlsConfig(ClientTlsSocket.builder(ClientHandshakeCertificates.create()))
                .build();
    }

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/main/samples/src/main/resources/jayo-http.txt")
                .get();

        try (ClientResponse response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new JayoException("Unexpected code " + response.getStatusCode());
            }

            System.out.println(response.getHandshake().getCipherSuite());
            System.out.println(response.getBody().string());
        }
    }

    public static void main(String... args) {
        new CustomCipherSuites().run();
    }
}
