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

package jayo.http.internal.publicsuffix;

import jayo.ByteString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class PublicSuffixDatabase {
    private static PublicSuffixDatabase INSTANCE;

    private static final System.Logger LOGGER = System.getLogger("jayo.http.PublicSuffixDatabase");

    private final @NonNull PublicSuffixList publicSuffixList;

    private final ByteString WILDCARD_LABEL = ByteString.of((byte) ((int) '*'));
    private final List<String> PREVAILING_RULE = List.of("*");
    private final char EXCEPTION_MARKER = '!';

    PublicSuffixDatabase(final @NonNull PublicSuffixList publicSuffixList) {
        assert publicSuffixList != null;

        this.publicSuffixList = publicSuffixList;
    }

    public static PublicSuffixDatabase getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PublicSuffixDatabase(EmbeddedPublicSuffixList.getInstance());
        }

        return INSTANCE;
    }

    /**
     * @param domain A canonicalized domain. An International Domain Name (IDN) should be punycode encoded.
     * @return the effective top-level domain plus one (eTLD+1) by referencing the public suffix list. Returns null if
     * the domain is a public suffix or a private address.
     * <p>
     * Here are some examples:
     * <p>
     * ```java
     * assertEquals("google.com", getEffectiveTldPlusOne("google.com"));
     * assertEquals("google.com", getEffectiveTldPlusOne("www.google.com"));
     * assertNull(getEffectiveTldPlusOne("com"));
     * assertNull(getEffectiveTldPlusOne("localhost"));
     * assertNull(getEffectiveTldPlusOne("mymacbook"));
     * ```
     */
    public @Nullable String getEffectiveTldPlusOne(final @NonNull String domain) {
        // We use UTF-8 in the list, so we need to convert to Unicode.
        final var unicodeDomain = IDN.toUnicode(domain);
        final var domainLabels = splitDomain(unicodeDomain);

        final var rule = findMatchingRule(domainLabels);
        if (domainLabels.size() == rule.size() && rule.getFirst().charAt(0) != EXCEPTION_MARKER) {
            return null; // The domain is a public suffix.
        }

        final int firstLabelOffset;
        if (rule.getFirst().charAt(0) == EXCEPTION_MARKER) {
            // Exception rules hold the effective TLD plus one.
            firstLabelOffset = domainLabels.size() - rule.size();
        } else {
            // Otherwise the rule is for a public suffix, so we must take one more label.
            firstLabelOffset = domainLabels.size() - (rule.size() + 1);
        }

        return splitDomain(domain).stream()
                .skip(firstLabelOffset)
                .collect(Collectors.joining("."));
    }

    private @NonNull List<String> splitDomain(final @NonNull String domain) {
        final var domainLabels = Arrays.asList(domain.split("\\."));

        if (domainLabels.getLast().isEmpty()) {
            // allow for domain name trailing dot
            domainLabels.removeLast();
        }

        return domainLabels;
    }

    private @NonNull List<String> findMatchingRule(final @NonNull List<String> domainLabels) {
        publicSuffixList.ensureLoaded();

        // Break apart the domain into UTF-8 labels, i.e. foo.bar.com turns into [foo, bar, com].
        final var domainLabelsUtf8Bytes = new ByteString[domainLabels.size()];
        Arrays.setAll(domainLabelsUtf8Bytes, i -> ByteString.encode(domainLabels.get(i)));

        // Start by looking for exact matches. We start at the leftmost label. For example, foo.bar.com
        // will look like: [foo, bar, com], [bar, com], [com]. The longest matching rule wins.
        String exactMatch = null;
        for (var i = 0; i < domainLabelsUtf8Bytes.length; i++) {
            final var rule = binarySearch(publicSuffixList.bytes(), domainLabelsUtf8Bytes, i);
            if (rule != null) {
                exactMatch = rule;
                break;
            }
        }

        // In theory, wildcard rules are not restricted to having the wildcard in the leftmost position.
        // In practice, wildcards are always in the leftmost position. For now, this implementation
        // cheats and does not attempt every possible permutation. Instead, it only considers wildcards
        // in the leftmost position. We assert this fact when we generate the public suffix file. If
        // this assertion ever fails we'll need to refactor this implementation.
        String wildcardMatch = null;
        if (domainLabelsUtf8Bytes.length > 1) {
            final var labelsWithWildcard = domainLabelsUtf8Bytes.clone();
            for (var labelIndex = 0; labelIndex < labelsWithWildcard.length; labelIndex++) {
                labelsWithWildcard[labelIndex] = WILDCARD_LABEL;
                final var rule = binarySearch(publicSuffixList.bytes(), labelsWithWildcard, labelIndex);
                if (rule != null) {
                    wildcardMatch = rule;
                    break;
                }
            }
        }

        // Exception rules only apply to wildcard rules, so only try it if we matched a wildcard.
        String exception = null;
        if (wildcardMatch != null) {
            for (var labelIndex = 0; labelIndex < (domainLabelsUtf8Bytes.length - 1); labelIndex++) {
                final var rule = binarySearch(publicSuffixList.exceptionBytes(), domainLabelsUtf8Bytes, labelIndex);
                if (rule != null) {
                    exception = rule;
                    break;
                }
            }
        }

        if (exception != null) {
            // Signal we've identified an exception rule.
            exception = "!" + exception;
            return List.of(exception.split("\\."));
        } else if (exactMatch == null && wildcardMatch == null) {
            return PREVAILING_RULE;
        }

        final List<String> exactRuleLabels = (exactMatch != null) ? List.of(exactMatch.split("\\.")) : List.of();
        final List<String> wildcardRuleLabels = (wildcardMatch != null)
                ? List.of(wildcardMatch.split("\\."))
                : List.of();

        if (exactRuleLabels.size() > wildcardRuleLabels.size()) {
            return exactRuleLabels;
        }
        return wildcardRuleLabels;
    }

    private static @Nullable String binarySearch(
            ByteString bytes,
            ByteString[] labels,
            int labelIndex) {
        var low = 0;
        var high = bytes.byteSize();
        String match = null;
        while (low < high) {
            var mid = (low + high) / 2;
            // Search for a '\n' that marks the start of a value. Don't go back past the start of the
            // array.
            while (mid > -1 && bytes.getByte(mid) != ((byte) ((int) '\n'))) {
                mid--;
            }
            mid++;

            // Now look for the ending '\n'.
            var end = 1;
            while (bytes.getByte(mid + end) != ((byte) ((int) '\n'))) {
                end++;
            }
            final var publicSuffixLength = mid + end - mid;

            // Compare the bytes. Note that the file stores UTF-8 encoded bytes, so we must compare the
            // unsigned bytes.
            int compareResult;
            var currentLabelIndex = labelIndex;
            var currentLabelByteIndex = 0;
            var publicSuffixByteIndex = 0;

            var expectDot = false;
            while (true) {
                final int byte0;
                if (expectDot) {
                    byte0 = '.';
                    expectDot = false;
                } else {
                    byte0 = labels[currentLabelIndex].getByte(currentLabelByteIndex) & 0xff;
                }

                final var byte1 = bytes.getByte(mid + publicSuffixByteIndex) & 0xff;

                compareResult = byte0 - byte1;
                if (compareResult != 0) {
                    break;
                }

                publicSuffixByteIndex++;
                currentLabelByteIndex++;
                if (publicSuffixByteIndex == publicSuffixLength) {
                    break;
                }

                if (labels[currentLabelIndex].byteSize() == currentLabelByteIndex) {
                    // We've exhausted our current label. Either there are more labels to compare, in which
                    // case we expect a dot as the next character. Otherwise, we've checked all our labels.
                    if (currentLabelIndex == labels.length - 1) {
                        break;
                    } else {
                        currentLabelIndex++;
                        currentLabelByteIndex = -1;
                        expectDot = true;
                    }
                }
            }

            if (compareResult < 0) {
                high = mid - 1;
            } else if (compareResult > 0) {
                low = mid + end + 1;
            } else {
                // We found a match, but are the lengths equal?
                final var publicSuffixBytesLeft = publicSuffixLength - publicSuffixByteIndex;
                var labelBytesLeft = labels[currentLabelIndex].byteSize() - currentLabelByteIndex;
                for (var i = currentLabelIndex + 1; i < labels.length; i++) {
                    labelBytesLeft += labels[i].byteSize();
                }

                if (labelBytesLeft < publicSuffixBytesLeft) {
                    high = mid - 1;
                } else if (labelBytesLeft > publicSuffixBytesLeft) {
                    low = mid + end + 1;
                } else {
                    // Found a match.
                    match = bytes.substring(mid, mid + publicSuffixLength).decodeToString(StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        return match;
    }
}
