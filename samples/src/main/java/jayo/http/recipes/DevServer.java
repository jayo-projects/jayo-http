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

import jayo.http.*;
import jayo.tls.ClientHandshakeCertificates;
import jayo.tls.ClientTlsSocket;
import jayo.tls.HeldCertificate;
import jayo.tls.ServerHandshakeCertificates;
import jayo.tools.JayoTlsUtils;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Headers;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;

/**
 * Create an HTTPS server with a self-signed certificate that Jayo HTTP trusts.
 */
public class DevServer {
    public void run() throws IOException {
        HeldCertificate localhostCertificate = HeldCertificate.builder()
                .addSubjectAlternativeName("localhost")
                .build();

        ServerHandshakeCertificates serverCertificates = ServerHandshakeCertificates.builder(localhostCertificate)
                .build();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            SSLSocketFactory sslSocketFactory = JayoTlsUtils.handshakeCertSSLContext(serverCertificates).getSocketFactory();
            server.useHttps(sslSocketFactory);
            server.enqueue(new MockResponse(200, Headers.of(), ""));

            ClientHandshakeCertificates clientCertificates = ClientHandshakeCertificates.builder()
                    .addTrustedCertificate(localhostCertificate.getCertificate())
                    .build();
            JayoHttpClient client = JayoHttpClient.builder()
                    .tlsConfig(ClientTlsSocket.builder(clientCertificates))
                    .build();

            Call call = client.newCall(ClientRequest.builder()
                    .url(HttpUrl.get(server.url("/").toString()))
                    .get());
            ClientResponse response = call.execute();
            System.out.println(response.getHandshake().getTlsVersion());
        }
    }

    public static void main(String... args) throws IOException {
        new DevServer().run();
    }
}
