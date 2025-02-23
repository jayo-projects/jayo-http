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

package jayo.http.internal;

import jayo.http.Cookie;
import jayo.http.Headers;
import jayo.http.HttpUrl;
import jayo.http.internal.publicsuffix.PublicSuffixDatabase;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import static jayo.http.internal.DateFormatting.toHttpInstantString;
import static jayo.http.internal.HostnameUtils.domainMatch;
import static jayo.http.internal.HostnameUtils.toCanonicalHost;
import static jayo.http.internal.Utils.delimiterOffset;
import static jayo.http.internal.Utils.trimSubstring;

public record RealCookie(
        @NonNull String name,
        @NonNull String value,
        @NonNull Instant expiresAt,
        @NonNull String domain,
        @NonNull String path,
        boolean secure,
        boolean httpOnly,
        boolean persistent,
        boolean hostOnly,
        @Nullable String sameSite) implements Cookie {
    @Override
    public boolean matches(final @NonNull HttpUrl url) {
        Objects.requireNonNull(url);

        boolean domainMatch = hostOnly ? url.getHost().equals(domain) : domainMatch(url.getHost(), domain);
        if (!domainMatch) {
            return false;
        }

        if (!pathMatch(url, path)) {
            return false;
        }

        return !secure || url.isHttps();
    }

    private static boolean pathMatch(final @NonNull HttpUrl url, final @NonNull String path) {
        assert url != null;
        assert path != null;

        final var urlPath = url.getEncodedPath();

        if (urlPath.equals(path)) { // As in '/foo' matching '/foo'.
            return true;
        }

        if (urlPath.startsWith(path)) {
            if (path.endsWith("/")) { // As in '/' matching '/foo'.
                return true;
            }
            if (urlPath.charAt(path.length()) == '/') { // As in '/foo' matching '/foo/bar'.
                return true;
            }
        }

        return false;
    }

    @Override
    public @NonNull String toString() {
        return toString(false);
    }

    /**
     * @param forObsoleteRfc2965 true to include a leading {@code .} on the domain pattern. This is necessary for
     *                           {@code example.com} to match {@code www.example.com} under RFC 2965. This extra dot is
     *                           ignored by more recent specifications.
     */
    String toString(final boolean forObsoleteRfc2965) {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        sb.append('=');
        sb.append(value);

        if (persistent) {
            if (expiresAt.equals(Instant.MIN)) {
                sb.append("; max-age=0");
            } else {
                sb.append("; expires=").append(toHttpInstantString(expiresAt));
            }
        }

        if (!hostOnly) {
            sb.append("; domain=");
            if (forObsoleteRfc2965) {
                sb.append(".");
            }
            sb.append(domain);
        }

        sb.append("; path=").append(path);

        if (secure) {
            sb.append("; secure");
        }

        if (httpOnly) {
            sb.append("; httponly");
        }

        if (sameSite != null) {
            sb.append("; samesite=").append(sameSite);
        }

        return sb.toString();
    }

    @Override
    public @NonNull String getName() {
        return name;
    }

    @Override
    public @NonNull String getValue() {
        return value;
    }

    @Override
    public @NonNull Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public @NonNull String getDomain() {
        return domain;
    }

    @Override
    public @NonNull String getPath() {
        return path;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public boolean isHttpOnly() {
        return httpOnly;
    }

    @Override
    public boolean isPersistent() {
        return persistent;
    }

    @Override
    public boolean isHostOnly() {
        return hostOnly;
    }

    @Override
    public @Nullable String getSameSite() {
        return sameSite;
    }

    @Override
    public @NonNull Builder newBuilder() {
        return new Builder(this);
    }

    /**
     * The last four-digit year: "Fri Dec 31 9999 23:59:59.999 UTC".
     */
    private static final long MAX_DATE_MILLI = 253402300799999L;
    static final @NonNull Instant MAX_DATE = Instant.ofEpochMilli(MAX_DATE_MILLI);

    public static final class Builder implements Cookie.Builder {
        private @Nullable String name = null;
        private @Nullable String value = null;
        private @NonNull Instant expiresAt = MAX_DATE;
        private @Nullable String domain = null;
        private @NonNull String path = "/";
        private boolean secure = false;
        private boolean httpOnly = false;
        private boolean persistent = false;
        private boolean hostOnly = false;
        private @Nullable String sameSite = null;

        public Builder() {
        }

        private Builder(final @NonNull RealCookie cookie) {
            assert cookie != null;

            this.name = cookie.name;
            this.value = cookie.value;
            this.expiresAt = cookie.expiresAt;
            this.domain = cookie.domain;
            this.path = cookie.path;
            this.secure = cookie.secure;
            this.httpOnly = cookie.httpOnly;
            this.persistent = cookie.persistent;
            this.hostOnly = cookie.hostOnly;
            this.sameSite = cookie.sameSite;
        }

        @Override
        public @NonNull Builder name(final @NonNull String name) {
            Objects.requireNonNull(name);

            if (!name.strip().equals(name)) {
                throw new IllegalArgumentException("name is not trimmed");
            }
            this.name = name;
            return this;
        }

        @Override
        public @NonNull Builder value(final @NonNull String value) {
            Objects.requireNonNull(value);

            if (!value.strip().equals(value)) {
                throw new IllegalArgumentException("value is not trimmed");
            }
            this.value = value;
            return this;
        }

        @Override
        public @NonNull Builder expiresAt(final @NonNull Instant expiresAt) {
            Objects.requireNonNull(expiresAt);
            final Instant _expiresAt;
            if (expiresAt.compareTo(Instant.EPOCH) <= 0) {
                _expiresAt = Instant.MIN;
            } else if (expiresAt.isAfter(MAX_DATE)) {
                _expiresAt = MAX_DATE;
            } else {
                _expiresAt = expiresAt;
            }

            this.expiresAt = _expiresAt;
            this.persistent = true;
            return this;
        }

        @Override
        public @NonNull Builder expiresAt(final long expiresAt) {
            final Instant _expiresAt;
            if (expiresAt <= 0L) {
                _expiresAt = Instant.MIN;
            } else if (expiresAt > MAX_DATE_MILLI) {
                _expiresAt = MAX_DATE;
            } else {
                _expiresAt = Instant.ofEpochMilli(expiresAt);
            }

            this.expiresAt = _expiresAt;
            this.persistent = true;
            return this;
        }

        @Override
        public @NonNull Builder domain(final @NonNull String domain) {
            Objects.requireNonNull(domain);
            return domain(domain, false);
        }

        @Override
        public @NonNull Builder hostOnlyDomain(final @NonNull String domain) {
            Objects.requireNonNull(domain);
            return domain(domain, true);
        }

        private @NonNull Builder domain(final @NonNull String domain, final boolean hostOnly) {
            assert domain != null;

            final var canonicalDomain = toCanonicalHost(domain);
            if (canonicalDomain == null) {
                throw new IllegalArgumentException("unexpected domain: " + domain);
            }
            this.domain = canonicalDomain;
            this.hostOnly = hostOnly;
            return this;
        }

        @Override
        public @NonNull Builder path(final @NonNull String path) {
            Objects.requireNonNull(path);

            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("path must start with '/'");
            }
            this.path = path;
            return this;
        }

        @Override
        public @NonNull Builder secure(final boolean isSecure) {
            this.secure = isSecure;
            return this;
        }

        @Override
        public @NonNull Builder httpOnly(final boolean isHttpOnly) {
            this.httpOnly = isHttpOnly;
            return this;
        }

        @Override
        public @NonNull Builder sameSite(final @NonNull String sameSite) {
            Objects.requireNonNull(sameSite);

            if (!sameSite.strip().equals(sameSite)) {
                throw new IllegalArgumentException("sameSite is not trimmed");
            }
            this.sameSite = sameSite;
            return this;
        }

        @Override
        public @NonNull Cookie build() {
            if (name == null) {
                throw new NullPointerException("builder.name == null");
            }
            if (value == null) {
                throw new NullPointerException("builder.value == null");
            }
            if (domain == null) {
                throw new NullPointerException("builder.domain == null");
            }
            return new RealCookie(
                    name,
                    value,
                    expiresAt,
                    domain,
                    path,
                    secure,
                    httpOnly,
                    persistent,
                    hostOnly,
                    sameSite);
        }
    }

    public static @Nullable RealCookie parse(final long currentTimeMillis,
                                             final @NonNull HttpUrl url,
                                             final @NonNull String setCookie) {
        assert url != null;
        assert setCookie != null;

        final var cookiePairEnd = delimiterOffset(setCookie, ';', 0, setCookie.length());
        final var pairEqualsSign = delimiterOffset(setCookie, '=', 0, cookiePairEnd);
        if (pairEqualsSign == cookiePairEnd) {
            return null;
        }

        final var cookieName = trimSubstring(setCookie, 0, pairEqualsSign);
        if (cookieName.isEmpty() || indexOfControlOrNonAscii(cookieName) != -1) {
            return null;
        }

        final var cookieValue = trimSubstring(setCookie, pairEqualsSign + 1, cookiePairEnd);
        if (indexOfControlOrNonAscii(cookieValue) != -1) {
            return null;
        }

        var expiresAt = MAX_DATE;
        var deltaSeconds = -1L;
        String domain = null;
        String path = null;
        var secureOnly = false;
        var httpOnly = false;
        var hostOnly = true;
        var persistent = false;
        String sameSite = null;

        var pos = cookiePairEnd + 1;
        final var limit = setCookie.length();
        while (pos < limit) {
            final var attributePairEnd = delimiterOffset(setCookie, ';', pos, limit);
            final var attributeEqualsSign = delimiterOffset(setCookie, '=', pos, attributePairEnd);
            final var attributeName = trimSubstring(setCookie, pos, attributeEqualsSign);
            final var attributeValue = (attributeEqualsSign < attributePairEnd)
                    ? trimSubstring(setCookie, attributeEqualsSign + 1, attributePairEnd)
                    : "";

            switch (attributeName.toLowerCase()) {
                case "expires":
                    try {
                        expiresAt = parseExpires(attributeValue);
                        persistent = true;
                    } catch (IllegalArgumentException ignored) {
                        // Ignore this attribute, it isn't recognizable as a date.
                    }
                    break;
                case "max-age":
                    try {
                        deltaSeconds = parseMaxAge(attributeValue);
                        persistent = true;
                    } catch (NumberFormatException ignored) {
                        // Ignore this attribute, it isn't recognizable as a max age.
                    }
                    break;
                case "domain":
                    try {
                        domain = parseDomain(attributeValue);
                        hostOnly = false;
                    } catch (IllegalArgumentException ignored) {
                        // Ignore this attribute, it isn't recognizable as a domain.
                    }
                    break;
                case "path":
                    path = attributeValue;
                    break;
                case "secure":
                    secureOnly = true;
                    break;
                case "httponly":
                    httpOnly = true;
                    break;
                case "samesite":
                    sameSite = attributeValue;
                    break;
            }

            pos = attributePairEnd + 1;
        }

        // If 'Max-Age' is present, it takes precedence over 'Expires', regardless of the order the two attributes are
        // declared in the cookie string.
        if (deltaSeconds == Long.MIN_VALUE) {
            expiresAt = Instant.MIN;
        } else if (deltaSeconds != -1L) {
            final var deltaMillis = (deltaSeconds <= Long.MAX_VALUE / 1000)
                    ? deltaSeconds * 1000
                    : Long.MAX_VALUE;
            final var expiresAtMillis = currentTimeMillis + deltaMillis;
            if (expiresAtMillis < currentTimeMillis || expiresAtMillis > MAX_DATE_MILLI) {
                expiresAt = MAX_DATE; // Handle overflow and limit the date range.
            } else {
                expiresAt = Instant.ofEpochMilli(expiresAtMillis);
            }
        }

        // If the domain is present, it must domain match. Otherwise, we have a host-only cookie.
        final var urlHost = url.getHost();
        if (domain == null) {
            domain = urlHost;
        } else if (!domainMatch(urlHost, domain)) {
            return null; // No domain match? This is either incompetence or malice!
        }

        // If the domain is a suffix of the url host, it must not be a public suffix.
        if (urlHost.length() != domain.length() &&
                PublicSuffixDatabase.getInstance().getEffectiveTldPlusOne(domain) == null) {
            return null;
        }

        // If the path is absent or didn't start with '/', use the default path. It's a string like '/foo/bar' for a URL
        // like 'http://example.com/foo/bar/baz'. It always starts with '/'.
        if (path == null || !path.startsWith("/")) {
            final var encodedPath = url.getEncodedPath();
            final var lastSlash = encodedPath.lastIndexOf('/');
            path = (lastSlash != 0) ? encodedPath.substring(0, lastSlash) : "/";
        }

        return new RealCookie(
                cookieName,
                cookieValue,
                expiresAt,
                domain,
                path,
                secureOnly,
                httpOnly,
                persistent,
                hostOnly,
                sameSite
        );
    }

    /**
     * Returns the index of the first character in this string that is either a control character (like {@code \u0000}
     * or {@code \n}) or a non-ASCII character. Returns -1 if this string has no such characters.
     */
    private static int indexOfControlOrNonAscii(final @NonNull String string) {
        assert string != null;

        for (var i = 0; i < string.length(); i++) {
            final var c = string.charAt(i);
            if (c <= '\u001f' || c >= '\u007f') {
                return i;
            }
        }
        return -1;
    }

    private static final @NonNull Pattern YEAR_PATTERN = Pattern.compile("(\\d{2,4})[^\\d]*");
    private static final @NonNull Pattern MONTH_PATTERN =
            Pattern.compile("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*");
    private static final @NonNull Pattern DAY_OF_MONTH_PATTERN = Pattern.compile("(\\d{1,2})[^\\d]*");
    private static final @NonNull Pattern TIME_PATTERN =
            Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[^\\d]*");

    /**
     * Parse a date as specified in RFC 6265, section 5.1.1.
     */
    private static @NonNull Instant parseExpires(final @NonNull String string) {
        assert string != null;

        final var limit = string.length();
        var pos = dateCharacterOffset(string, 0, limit, false);

        var hour = -1;
        var minute = -1;
        var second = -1;
        var dayOfMonth = -1;
        var month = -1;
        var year = -1;
        final var matcher = TIME_PATTERN.matcher(string);

        while (pos < limit) {
            final var end = dateCharacterOffset(string, pos + 1, limit, true);
            matcher.region(pos, end);

            if (hour == -1 && matcher.usePattern(TIME_PATTERN).matches()) {
                hour = Integer.parseInt(matcher.group(1));
                minute = Integer.parseInt(matcher.group(2));
                second = Integer.parseInt(matcher.group(3));
            } else if (dayOfMonth == -1 && matcher.usePattern(DAY_OF_MONTH_PATTERN).matches()) {
                dayOfMonth = Integer.parseInt(matcher.group(1));
            } else if (month == -1 && matcher.usePattern(MONTH_PATTERN).matches()) {
                final var monthString = matcher.group(1).toLowerCase(Locale.US);
                month = MONTH_PATTERN.pattern().indexOf(monthString) / 4; // Sneaky! jan=1, dec=12.
            } else if (year == -1 && matcher.usePattern(YEAR_PATTERN).matches()) {
                year = Integer.parseInt(matcher.group(1));
            }

            pos = dateCharacterOffset(string, end + 1, limit, false);
        }

        // Convert two-digit years into four-digit years. 99 becomes 1999, 15 becomes 2015.
        if (year >= 70 && year <= 99) {
            year += 1900;
        }
        if (year >= 0 && year <= 69) {
            year += 2000;
        }

        // If any partial is omitted or out of range, throw an IllegalArgumentException. The date is impossible.
        // Note that this syntax does not support leap seconds.
        if (year < 1601 ||
                month == -1 ||
                dayOfMonth < 1 || dayOfMonth > 31 ||
                hour < 0 || hour > 23 ||
                minute < 0 || minute > 59 ||
                second < 0 || second > 59) {
            throw new IllegalArgumentException();
        }

        final var zdt = ZonedDateTime.of(
                year,
                month,
                dayOfMonth,
                hour,
                minute,
                second,
                0,
                ZoneOffset.UTC);
        return zdt.toInstant();
    }

    /**
     * @return the index of the next date character in {@code input}, or if {@code invert} the index of the next
     * non-date character in {@code input}.
     */
    private static int dateCharacterOffset(final @NonNull String input,
                                           final int pos,
                                           final int limit,
                                           final boolean invert) {
        assert input != null;

        for (var i = pos; i < limit; i++) {
            final var c = (int) input.charAt(i);
            final var isDateCharacter =
                    (c < ((int) ' ') && c != ((int) '\t')) ||
                            (c >= ((int) '\u007f')) ||
                            (c >= ((int) '0') && c <= ((int) '9')) ||
                            (c >= ((int) 'a') && c <= ((int) 'z')) ||
                            (c >= ((int) 'A') && c <= ((int) 'Z')) ||
                            (c == ((int) ':'));
            if (isDateCharacter != invert) {
                return i;
            }
        }
        return limit;
    }

    /**
     * @return the positive value if {@code string} is positive, or {@code Long.MIN_VALUE} if it is either 0 or
     * negative. If the value is positive but out of range, this returns {@code Long.MAX_VALUE}.
     * @throws NumberFormatException if {@code string} is not a long of any precision.
     */
    private static long parseMaxAge(final @NonNull String string) {
        assert string != null;

        try {
            final var parsed = Long.parseLong(string);
            return (parsed <= 0L) ? Long.MIN_VALUE : parsed;
        } catch (NumberFormatException e) {
            // Check if the value is a number (positive or negative) that's too big for a long.
            if (string.matches("-?\\d+")) {
                return string.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            throw e;
        }
    }

    /**
     * @return a domain string like {@code example.com} for an input domain like {@code EXAMPLE.COM} or
     * {@code .example.com}.
     */
    private static String parseDomain(final @NonNull String string) {
        assert string != null;

        if (string.endsWith(".")) {
            throw new IllegalArgumentException();
        }
        final var canonicalHost = toCanonicalHost(string.startsWith(".") ? string.substring(1) : string);
        if (canonicalHost == null) {
            throw new IllegalArgumentException();
        }
        return canonicalHost;
    }

    public static @NonNull List<@NonNull Cookie> parseAll(final long currentTimeMillis,
                                                          final @NonNull HttpUrl url,
                                                          final @NonNull Headers headers) {
        assert url != null;
        assert headers != null;

        return headers.values("Set-Cookie").stream()
                .map(cookieString -> (Cookie) parse(currentTimeMillis, url, cookieString))
                .filter(Objects::nonNull)
                .toList();
    }
}
