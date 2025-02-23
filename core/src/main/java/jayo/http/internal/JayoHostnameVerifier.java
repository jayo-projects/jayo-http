/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jayo.http.internal;

import jayo.Utf8Utils;
import org.jspecify.annotations.NonNull;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static jayo.http.internal.HostnameUtils.toCanonicalHost;
import static jayo.tools.HostnameUtils.canParseAsIpAddress;

/**
 * A HostnameVerifier consistent with <a href="https://www.ietf.org/rfc/rfc2818.txt">RFC 2818</a>.
 */
public enum JayoHostnameVerifier implements HostnameVerifier {
    INSTANCE;

    private static final int ALT_DNS_NAME = 2;
    private static final int ALT_IPA_NAME = 7;

    @Override
    public boolean verify(final @NonNull String host, final @NonNull SSLSession session) {
        Objects.requireNonNull(host);
        Objects.requireNonNull(session);

        if (!isAscii(host)) {
            return false;
        }

        try {
            return verify(host, (X509Certificate) session.getPeerCertificates()[0]);
        } catch (SSLException ignored) {
            return false;
        }
    }

    public static boolean verify(final @NonNull String host, final @NonNull X509Certificate certificate) {
        Objects.requireNonNull(host);
        Objects.requireNonNull(certificate);

        if (canParseAsIpAddress(host)) {
            return verifyIpAddress(host, certificate);
        }

        return verifyHostname(host, certificate);
    }

    /**
     * @return true if {@code certificate} matches {@code ipAddress}.
     */
    private static boolean verifyIpAddress(final @NonNull String ipAddress,
                                           final @NonNull X509Certificate certificate) {
        assert ipAddress != null;
        assert certificate != null;

        final var canonicalIpAddress = toCanonicalHost(ipAddress);

        return getSubjectAltNames(certificate, ALT_IPA_NAME).stream().anyMatch(altName ->
                Objects.equals(canonicalIpAddress, toCanonicalHost(altName))
        );
    }

    /**
     * @return true if {@code certificate} matches {@code hostname}.
     */
    private static boolean verifyHostname(final @NonNull String hostname,
                                          final @NonNull X509Certificate certificate) {
        assert hostname != null;
        assert certificate != null;

        final var adaptedHostname = asciiToLowercase(hostname);

        return getSubjectAltNames(certificate, ALT_DNS_NAME).stream().anyMatch(altName ->
                verifyHostname(adaptedHostname, altName)
        );
    }

    private static boolean isAscii(final @NonNull String host) {
        assert host != null;

        return host.length() == (int) Utf8Utils.utf8ByteSize(host);
    }

    /**
     * This is like {@link String#toLowerCase()} except that it does nothing if this contains any non-ASCII characters.
     * We want to avoid lower casing special chars like U+212A (Kelvin symbol) because they can return ASCII characters
     * that match real hostnames.
     */
    private static @NonNull String asciiToLowercase(final @NonNull String string) {
        assert string != null;

        return (isAscii(string))
                ? string.toLowerCase(Locale.US) // This is an ASCII string.
                : string;
    }

    /**
     * @param hostname lower-case host name.
     * @param pattern  a domain name pattern from a certificate. It may be a wildcard pattern such as
     *                 {@code *.android.com}.
     * @return true if {@code hostname} matches the domain name {@code pattern}.
     */
    private static boolean verifyHostname(final @NonNull String hostname, final @NonNull String pattern) {
        assert hostname != null;

        var _hostname = hostname;
        var _pattern = pattern;
        if (_hostname.isBlank() ||
                _hostname.startsWith(".") ||
                _hostname.endsWith("..")
        ) {
            // Invalid domain name.
            return false;
        }
        if (_pattern.isBlank() ||
                _pattern.startsWith(".") ||
                _pattern.endsWith("..")
        ) {
            // Invalid pattern.
            return false;
        }

        // Normalize hostname and pattern by turning them into absolute domain names if they are not yet absolute. This
        // is needed because server certificates do not normally contain absolute names or patterns, but they should be
        // treated as absolute. At the same time, any hostname presented to this method should also be treated as
        // absolute for the purposes of matching to the server certificate.
        //   www.android.com  matches www.android.com
        //   www.android.com  matches www.android.com.
        //   www.android.com. matches www.android.com.
        //   www.android.com. matches www.android.com
        if (!_hostname.endsWith(".")) {
            _hostname += ".";
        }
        if (!_pattern.endsWith(".")) {
            _pattern += ".";
        }
        // Hostname and pattern are now absolute domain names.

        _pattern = asciiToLowercase(_pattern);
        // Hostname and pattern are now in lower case -- domain names are case-insensitive.

        if (!_pattern.contains("*")) {
            // Not a wildcard pattern -- hostname and pattern must match exactly.
            return _hostname.equals(_pattern);
        }

        // Wildcard pattern

        // WILDCARD PATTERN RULES:
        // 1. Asterisk (*) is only permitted in the left-most domain name label and must be the only character in that
        //    label (i.e., must match the whole left-most label).
        //    For example, *.example.com is permitted, while *a.example.com, a*.example.com, a*b.example.com,
        //    a.*.example.com are not permitted.
        // 2. Asterisk (*) cannot match across domain name labels.
        //    For example, *.example.com matches test.example.com but does not match sub.test.example.com.
        // 3. Wildcard patterns for single-label domain names are not permitted.

        if (!_pattern.startsWith("*.") || _pattern.indexOf('*', 1) != -1) {
            // Asterisk (*) is only permitted in the left-most domain name label and must be the only character in that
            // label
            return false;
        }

        // Optimization: check whether the hostname is too short to match the pattern. hostName must be at least as long
        // as the pattern because asterisk must match the whole left-most label and hostname starts with a non-empty
        // label. Thus, an asterisk has to match one or more characters.
        if (_hostname.length() < _pattern.length()) {
            return false; // Hostname too short to match the pattern.
        }

        if ("*.".equals(_pattern)) {
            return false; // Wildcard pattern for single-label domain name -- not permitted.
        }

        // Hostname must end with the region of the pattern following the asterisk.
        final var suffix = _pattern.substring(1);
        if (!_hostname.endsWith(suffix)) {
            return false; // Hostname does not end with the suffix.
        }

        // Check that asterisk did not match across domain name labels.
        final var suffixStartIndexInHostname = _hostname.length() - suffix.length();
        //noinspection RedundantIfStatement
        if (suffixStartIndexInHostname > 0 &&
                _hostname.lastIndexOf('.', suffixStartIndexInHostname - 1) != -1
        ) {
            return false; // Asterisk is matching across domain name labels -- not permitted.
        }

        // Hostname matches the pattern.
        return true;
    }

    public @NonNull List<@NonNull String> allSubjectAltNames(final @NonNull X509Certificate certificate) {
        final var altIpaNames = getSubjectAltNames(certificate, ALT_IPA_NAME);
        final var altDnsNames = getSubjectAltNames(certificate, ALT_DNS_NAME);
        return concat(altIpaNames, altDnsNames);
    }

    private static @NonNull List<@NonNull String> getSubjectAltNames(
            final @NonNull X509Certificate certificate,
            final int type) {
        try {
            final var subjectAltNames = certificate.getSubjectAlternativeNames();
            if (subjectAltNames == null) {
                return List.of();
            }

            final var result = new ArrayList<String>();
            for (final var subjectAltName : subjectAltNames) {
                if (subjectAltName == null || subjectAltName.size() < 2) {
                    continue;
                }
                if (!subjectAltName.get(0).equals(type)) {
                    continue;
                }
                final var altName = subjectAltName.get(1);
                if (altName == null) {
                    continue;
                }
                result.add((String) altName);
            }
            return result;
        } catch (CertificateParsingException ignored) {
            return List.of();
        }
    }

    private static @NonNull List<@NonNull String> concat(final @NonNull List<@NonNull String> altIpaNames,
                                                         final @NonNull List<@NonNull String> altDnsNames) {
        final var result = new ArrayList<String>(altIpaNames.size() + altDnsNames.size());
        result.addAll(altIpaNames);
        result.addAll(altDnsNames);
        return result;
    }
}
