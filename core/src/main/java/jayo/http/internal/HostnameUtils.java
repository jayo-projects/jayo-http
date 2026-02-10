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

package jayo.http.internal;

import jayo.Buffer;
import jayo.JayoException;
import jayo.http.internal.idn.Punycode;
import jayo.network.NetworkProtocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.Normalizer;
import java.util.Arrays;

import static java.text.Normalizer.Form.NFC;
import static jayo.http.internal.Utils.parseHexDigit;
import static jayo.http.internal.idn.IdnaMappingTable.IDNA_MAPPING_TABLE;
import static jayo.tools.HostnameUtils.canParseAsIpAddress;

@SuppressWarnings("resource")
public final class HostnameUtils {
    // un-instantiable
    private HostnameUtils() {
    }

    /**
     * If this is an IP address, this returns the IP address in canonical form.
     * <p>
     * Otherwise, this performs IDN ToASCII encoding and canonicalizes the result to lowercase. For example, this
     * converts {@code ☃.net} to {@code xn--n3h.net}, and {@code WwW.GoOgLe.cOm} to {@code www.google.com}.
     * {@code null} will be returned if the host cannot be ASCII encoded or if the result contains unsupported ASCII
     * characters.
     */
    public static @Nullable String toCanonicalHost(final @NonNull String host) {
        assert host != null;

        // If the input contains an ":", it’s an IPv6 address.
        if (host.indexOf(':') >= 0) {
            // If the input is encased in square braces "[...]", drop 'em.
            final byte[] inetAddressByteArray;
            if (host.startsWith("[") && host.endsWith("]")) {
                inetAddressByteArray = decodeIpv6(host, 1, host.length() - 1);
            } else {
                inetAddressByteArray = decodeIpv6(host, 0, host.length());
            }
            if (inetAddressByteArray == null) {
                return null;
            }

            final var address = canonicalizeInetAddress(inetAddressByteArray);
            if (address.length == 16) {
                return inet6AddressToAscii(address);
            }
            if (address.length == 4) {
                return inet4AddressToAscii(address); // An IPv4-mapped IPv6 address.
            }
            throw new AssertionError("Invalid IPv6 address: '" + host + "'");
        }

        final var result = idnToAscii(host);
        if (result == null
                || result.isEmpty()
                || containsInvalidHostnameAsciiCodes(result)
                || containsInvalidLabelLengths(result)) {
            return null;
        }

        return result;
    }

    /**
     * @return the canonical address for {@code address}. If {@code address} is an IPv6 address mapped to an IPv4
     * address, this returns the IPv4-mapped address. Otherwise, this returns {@code address} as is.
     * <p>
     * <a href="https://en.wikipedia.org/wiki/IPv6#IPv4-mapped_IPv6_addresses">IPv4-mapped_IPv6_addresses</a>
     */
    static byte @NonNull [] canonicalizeInetAddress(final byte @NonNull [] address) {
        assert address != null;

        if (isMappedIpv4Address(address)) {
            return Arrays.copyOfRange(address, 12, 16);
        }
        return address;
    }

    /**
     * @return true for IPv6 addresses like {@code 0000:0000:0000:0000:0000:ffff:XXXX:XXXX}.
     */
    private static boolean isMappedIpv4Address(final byte @NonNull [] address) {
        assert address != null;

        if (address.length != 16) {
            return false;
        }

        for (var i = 0; i < 10; i++) {
            if (address[i] != (byte) 0) {
                return false;
            }
        }

        if (address[10] != (byte) 255) {
            return false;
        }
        return address[11] == (byte) 255;
    }

    /**
     * Encodes an IPv6 address in canonical form according to RFC 5952.
     */
    private static @NonNull String inet6AddressToAscii(final byte @NonNull [] address) {
        assert address != null;

        // Go through the address looking for the longest run of 0s. Each group is 2-bytes.
        // A run must be longer than one group (section 4.2.2).
        // If there are multiple equal runs, the first one must be used (section 4.2.3).
        var longestRunOffset = -1;
        var longestRunLength = 0;
        var i = 0;
        while (i < address.length) {
            final var currentRunOffset = i;
            while ((i < 16) && ((int) address[i] == 0) && (((int) address[i + 1]) == 0)) {
                i += 2;
            }
            final var currentRunLength = i - currentRunOffset;
            if (currentRunLength > longestRunLength && currentRunLength >= 4) {
                longestRunOffset = currentRunOffset;
                longestRunLength = currentRunLength;
            }
            i += 2;
        }

        // Emit each 2-byte group in HEX format, separated by ':'. The longest run of zeroes is "::".
        final var buffer = Buffer.create();
        i = 0;
        while (i < address.length) {
            if (i == longestRunOffset) {
                buffer.writeByte((byte) ((int) ':'));
                i += longestRunLength;
                if (i == 16) {
                    buffer.writeByte((byte) ((int) ':'));
                }
            } else {
                if (i > 0) {
                    buffer.writeByte((byte) ((int) ':'));
                }
                final var group = ((address[i] & 0xff) << 8) | (address[i + 1] & 0xff);
                buffer.writeHexadecimalUnsignedLong(group);
                i += 2;
            }
        }
        return buffer.readString();
    }

    /**
     * Encodes an IPv4 address in canonical form according to RFC 4001.
     */
    static @NonNull String inet4AddressToAscii(final byte @NonNull [] address) {
        assert address != null;

        if (address.length != 4) {
            throw new IllegalArgumentException("IPv4 byte length must be 4, was " + address.length);
        }
        return Buffer.create()
                .writeDecimalLong((address[0] & 0xff))
                .writeByte((byte) ((int) '.'))
                .writeDecimalLong((address[1] & 0xff))
                .writeByte((byte) ((int) '.'))
                .writeDecimalLong((address[2] & 0xff))
                .writeByte((byte) ((int) '.'))
                .writeDecimalLong((address[3] & 0xff))
                .readString();
    }

    private static @Nullable String idnToAscii(final @NonNull String host) {
        assert host != null;

        final var bufferA = Buffer.create().write(host);
        final var bufferB = Buffer.create();

        // 1. Map, from bufferA to bufferB.
        while (!bufferA.exhausted()) {
            final var codePoint = bufferA.readUtf8CodePoint();
            if (!IDNA_MAPPING_TABLE.map(codePoint, bufferB)) {
                return null;
            }
        }

        // 2. Normalize, from bufferB to bufferA.
        final var normalized = Normalizer.normalize(bufferB.readString(), NFC);
        bufferA.write(normalized);

        // 3. For each label, convert/validate Punycode.
        final var decoded = Punycode.decode(bufferA.readString());
        if (decoded == null) {
            return null;
        }

        // 4.1 Validate.

        // Must be NFC.
        if (!decoded.equals(Normalizer.normalize(decoded, NFC))) {
            return null;
        }

        // TODO: Must not begin with a combining mark.
        // TODO: Each character must be 'valid' or 'deviation'. Not mapped.
        // TODO: CheckJoiners from IDNA 2008
        // TODO: CheckBidi from IDNA 2008, RFC 5893, Section 2.

        return Punycode.encode(decoded);
    }

    private static boolean containsInvalidHostnameAsciiCodes(final @NonNull String input) {
        assert input != null;

        for (var i = 0; i < input.length(); i++) {
            final var c = input.charAt(i);
            // The WHATWG Host parsing rules accept some character codes that are invalid by definition for Jayo HTTP's
            // host header checks (and the WHATWG Host syntax definition). Here we rule out characters that would cause
            // problems in host headers.
            if (c <= '\u001f' || c >= '\u007f') {
                return true;
            }
            // Check for the characters mentioned in the WHATWG Host parsing spec:
            // U+0000, U+0009, U+000A, U+000D, U+0020, "#", "%", "/", ":", "?", "@", "[", "\", and "]"
            // (excluding the characters covered above).
            if (" #%/:?@[\\]".indexOf(c) != -1) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if the length is not valid for DNS (empty or greater than 253 characters), or if any label is longer
     * than 63 characters. Trailing dots are okay.
     */
    private static boolean containsInvalidLabelLengths(final @NonNull String input) {
        assert input != null;

        final var length = input.length();
        if (length < 1 || length > 253) {
            return true;
        }

        var labelStart = 0;
        while (true) {
            final var dot = input.indexOf('.', labelStart);
            final int labelLength;
            if (dot == -1) {
                labelLength = length - labelStart;
            } else {
                labelLength = dot - labelStart;
            }
            if (labelLength < 1 || labelLength > 63) {
                return true;
            }
            if (dot == -1) {
                break;
            }
            if (dot == length - 1) {
                break; // Trailing '.' is allowed.
            }
            labelStart = dot + 1;
        }

        return false;
    }

    /**
     * Decodes an IPv6 address like {@code 1111:2222:3333:4444:5555:6666:7777:8888} or {@code ::1}.
     */
    static byte @Nullable [] decodeIpv6(final @NonNull String input,
                                        final int pos,
                                        final int limit) {
        assert input != null;

        final var address = new byte[16];
        var b = 0;
        var compress = -1;
        var groupOffset = -1;

        var i = pos;
        while (i < limit) {
            if (b == address.length) {
                return null; // Too many groups.
            }

            // Read a delimiter.
            if (i + 2 <= limit && input.startsWith("::", i)) {
                // Compression "::" delimiter, which is anywhere in the input, including its prefix.
                if (compress != -1) {
                    return null; // Multiple "::" delimiters.
                }
                i += 2;
                b += 2;
                compress = b;
                if (i == limit) {
                    break;
                }
            } else if (b != 0) {
                // Group separator ":" delimiter.
                if (input.startsWith(":", i)) {
                    i++;
                } else if (input.startsWith(".", i)) {
                    // If we see a '.', rewind to the beginning of the previous group and parse as IPv4.
                    if (!decodeIpv4Suffix(input, groupOffset, limit, address, b - 2)) {
                        return null;
                    }
                    b += 2; // We rewound two bytes and then added four.
                    break;
                } else {
                    return null; // Wrong delimiter.
                }
            }

            // Read a group, one to four hex digits.
            var value = 0;
            groupOffset = i;
            while (i < limit) {
                final var hexDigit = parseHexDigit(input.charAt(i));
                if (hexDigit == -1) {
                    break;
                }
                value = (value << 4) + hexDigit;
                i++;
            }
            final var groupLength = i - groupOffset;
            if (groupLength == 0 || groupLength > 4) {
                return null; // Group is the wrong size.
            }

            // We've successfully read a group. Assign its value to our byte array.
            address[b++] = (byte) ((value >>> 8) & 0xff);
            address[b++] = (byte) (value & 0xff);
        }

        // All done. If compression happened, we need to move bytes to the right place in the address. Here's a sample:
        //      input: "1111:2222:3333::7777:8888"
        //     before: { 11, 11, 22, 22, 33, 33, 00, 00, 77, 77, 88, 88, 00, 00, 00, 00  }
        //   compress: 6
        //          b: 10
        //      after: { 11, 11, 22, 22, 33, 33, 00, 00, 00, 00, 00, 00, 77, 77, 88, 88 }
        //
        if (b != address.length) {
            if (compress == -1) {
                return null; // Address didn't have compression or enough groups.
            }
            System.arraycopy(address, compress, address, address.length - (b - compress), b - compress);
            Arrays.fill(address, compress, compress + (address.length - b), (byte) 0);
        }

        return address;
    }

    /**
     * Decodes an IPv4 address suffix of an IPv6 address, like {@code 1111::5555:6666:192.168.0.1}.
     */
    private static boolean decodeIpv4Suffix(final @NonNull String input,
                                            final int pos,
                                            final int limit,
                                            final byte @NonNull [] address,
                                            final int addressOffset) {
        assert input != null;
        assert address != null;

        var b = addressOffset;

        var i = pos;
        while (i < limit) {
            if (b == address.length) {
                return false; // Too many groups.
            }

            // Read a delimiter.
            if (b != addressOffset) {
                if (input.charAt(i) != '.') {
                    return false; // Wrong delimiter.
                }
                i++;
            }

            // Read 1 or more decimal digits for a value in 0..255.
            var value = 0;
            final var groupOffset = i;
            while (i < limit) {
                final var c = input.charAt(i);
                if (c < '0' || c > '9') {
                    break;
                }
                if (value == 0 && groupOffset != i) {
                    return false; // Reject unnecessary leading '0's.
                }
                value = value * 10 + (int) c - (int) '0';
                if (value > 255) {
                    return false; // Value out of range.
                }
                i++;
            }
            final var groupLength = i - groupOffset;
            if (groupLength == 0) {
                return false; // No digits.
            }

            // We've successfully read a byte.
            address[b++] = (byte) value;
        }

        // Check for too few groups. We wanted exactly four.
        return b == addressOffset + 4;
    }

    static boolean domainMatch(final @NonNull String urlHost, final @NonNull String domain) {
        assert urlHost != null;
        assert domain != null;

        if (urlHost.equals(domain)) { // As in 'example.com' matching 'example.com'.
            return true;
        }

        return urlHost.endsWith(domain) &&
                urlHost.charAt(urlHost.length() - domain.length() - 1) == '.' &&
                !canParseAsIpAddress(urlHost);
    }

    static @NonNull InetAddress localHostAddress(final @Nullable NetworkProtocol networkProtocol) {
        try {
            if (networkProtocol != null) {
                return localHostAddressByProtocol(networkProtocol);
            } else {
                return InetAddress.getLocalHost();
            }
        } catch (UnknownHostException uhe) {
            throw JayoException.buildJayoException(uhe);
        }
    }

    private static @NonNull InetAddress localHostAddressByProtocol(final @NonNull NetworkProtocol networkProtocol)
            throws UnknownHostException {
        assert networkProtocol != null;

        final var inetAddresses = InetAddress.getAllByName("localhost");
        final var expectedClass = (networkProtocol == NetworkProtocol.IPv6) ? Inet6Address.class : Inet4Address.class;
        for (final var address : inetAddresses) {
            if (expectedClass.isInstance(address)) {
                return address;
            }
        }
        throw new UnknownHostException(networkProtocol.name() + " localhost");
    }
}
