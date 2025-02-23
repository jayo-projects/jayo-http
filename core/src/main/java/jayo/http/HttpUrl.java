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

package jayo.http;

import jayo.http.internal.RealHttpUrl;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A uniform resource locator (URL) with a scheme of either {@code http} or {@code https}. Use this class to compose and
 * decompose Internet addresses. For example, this code will compose and print a URL for Google search:
 * <pre>
 * {@code
 * HttpsUrl url = HttpsUrl.create(config -> {
 *   config.setHost("google.com");
 *   config.addPathSegment("search");
 *   config.addQueryParameter("q", "polar bears");
 * });
 * System.out.println(url);
 * }
 * </pre>
 * which prints:
 * <pre>
 * {@code
 * https://google.com/search?q=polar%20bears
 * }
 * </pre>
 * As another example, this code prints the human-readable query parameters of a Twitter search:
 * <pre>
 * {@code
 * HttpsUrl url = HttpsUrl.parse("https://twitter.com/search?q=cute%20%23puppies&f=images");
 * for (int i = 0, size = url.querySize(); i < size; i++) {
 *   System.out.println(url.queryParameterName(i) + ": " + url.queryParameterValue(i));
 * }
 * }
 * </pre>
 * which prints:
 * <pre>
 * {@code
 * q: cute #puppies
 * f: images
 * }
 * </pre>
 * In addition to composing URLs from their component parts and decomposing URLs into their component parts, this class
 * implements relative URL resolution: what address you'd reach by clicking a relative link on a specified page.
 * For example:
 * <pre>
 * {@code
 * HttpsUrl base = HttpsUrl.parse("https://youtube.com/user/WatchTheDaily/videos");
 * HttpsUrl link = base.resolve("../../watch?v=cbP2N1BQdYc");
 * System.out.println(link);
 * }
 * </pre>
 * which prints:
 * <pre>
 * {@code
 * https://youtube.com/watch?v=cbP2N1BQdYc
 * }
 * </pre>
 * <h2>What's in a URL?</h2>
 * A URL has several components.
 * <h3>Scheme</h3>
 * Sometimes referred to as <b>protocol</b>, A URL's scheme describes what mechanism should be used to retrieve the
 * resource. Although URLs have many schemes ({@code mailto}, {@code file}, {@code ftp}), this class only supports
 * {@code https}. Use {@linkplain URI java.net.URI} for URLs with arbitrary schemes.
 * <h3>Username and Password</h3>
 * Username and password are either present, or the empty string {@code ""} if absent. This class offers no mechanism to
 * differentiate empty from absent. Neither of these components are popular in practice. Typically, HTTP applications
 * use other mechanisms for user identification and authentication.
 * <h3>Host</h3>
 * The host identifies the webserver that serves the URL's resource. It is either a hostname like {@code jayo.dev} or
 * {@code localhost}, an IPv4 address like {@code 192.168.0.1}, or an IPv6 address like {@code ::1}.
 * <p>
 * Usually a webserver is reachable with multiple identifiers: its IP addresses, registered domain names, and even
 * {@code localhost} when connecting from the server itself. Each of a web server's names is a distinct URL, and they
 * are not interchangeable. For example, even if {@code https://square.github.io/dagger} and
 * {@code https://google.github.io/dagger} are served by the same IP address, the two URLs identify different resources.
 * <h3>Port</h3>
 * The port used to connect to the web server. By default, this is 80 for HTTP and 443 for HTTPS. This class never
 * returns -1 for the port: if no port is explicitly specified in the URL then the scheme's default is used.
 * <h3>Path</h3>
 * The path identifies a specific resource on the host. Paths have a hierarchical structure like
 * "jayo-projects/jayo-http/issues/1" and decompose into a list of segments like {@code ["jayo-projects", "jayo-http",
 * "issues", "1"]}.
 * <p>
 * This class offers methods to compose and decompose paths by segment. It composes each path from a list of segments by
 * alternating between "/" and the encoded segment. For example the segments {@code ["a", "b"]} build "/a/b" and the
 * segments {@code ["a", "b", ""]} build "/a/b/".
 * <p>
 * If a path's last segment is the empty string then the path ends with "/". This class always builds non-empty paths:
 * if the path is omitted it defaults to "/". The default path's segment list is a single empty string: {@code [""]}.
 * <h3>Query</h3>
 * The query is optional: it can be null, empty, or non-empty. For many HTTP or HTTPS URLs the query string is subdivided into a
 * collection of name-value parameters. This class offers methods to set the query as the single string, or as
 * individual name-value parameters. With name-value parameters the values are optional and names may be repeated.
 * <h3>Fragment</h3>
 * The fragment is optional: it can be null, empty, or non-empty. Unlike host, port, path, and
 * query the fragment is not sent to the webserver: it's private to the client.
 * <h2>Encoding</h2>
 * Each component must be encoded before it is embedded in the complete URL. As we saw above, the string
 * {@code cute #puppies} is encoded as {@code cute%20%23puppies} when used as a query parameter value.
 * <h3>Percent encoding</h3>
 * Percent encoding replaces a character (like {@code \ud83c\udf69}) with its UTF-8 hex bytes (like {@code %F0%9F%8D%A9}
 * ). This approach works for whitespace characters, control characters, non-ASCII characters, and characters that
 * already have another meaning in a particular context.
 * <p>
 * Percent encoding is used in every URL component except for the hostname. But the set of characters that need to be
 * encoded is different for each component. For example, the path component must escape all of its {@code ?} characters,
 * otherwise it could be interpreted as the start of the URL's query. But within the query and fragment components, the
 * {@code ?} character doesn't delimit anything and doesn't need to be escaped.
 * <pre>
 * {@code
 * HttpsUrl url = HttpsUrl.parse("https://who-let-the-dogs.out").createNew(config -> {
 *   config.addPathSegment("_Who?_");
 *   config.setQuery("_Who?_");
 *   config.setFragment("_Who?_");
 * });
 * System.out.println(url);
 * }
 * </pre>
 * This prints:
 * <pre>
 * {@code
 * https://who-let-the-dogs.out/_Who%3F_?_Who?_#_Who?_
 * }
 * </pre>
 * When parsing URLs that lack percent encoding where it is required, this class will percent encode the offending
 * characters.
 * <h3>IDNA Mapping and Punycode encoding</h3>
 * Hostnames have different requirements and use a different encoding scheme. It consists of IDNA mapping and Punycode
 * encoding.
 * <p>
 * In order to avoid confusion and discourage phishing attacks,
 * <a href="https://unicode.org/reports/tr46/#ToASCII">IDNA Mapping</a> transforms names to avoid confusing characters.
 * This includes basic case folding: transforming shouting {@code JAYO.DEV} into cool and casual {@code jayo.dev}. It
 * also handles more exotic characters. For example, the Unicode trademark sign (™) could be confused for the letters
 * "TM" in {@code https://ho™ail.com}. To mitigate this, the single character (™) maps to the string (tm). There is
 * similar policy for all the 1.1 million Unicode code points. Note that some code points such as "\ud83c\udf69" are
 * not mapped and cannot be used in a hostname.
 * <p>
 * <a href="https://ietf.org/rfc/rfc3492.txt">Punycode</a> converts a Unicode string to an ASCII string to make
 * international domain names work everywhere. For example, "σ" encodes as "xn--4xa". The encoded string is not
 * human-readable, but can be used with classes like {@link InetAddress} to establish connections.
 * <h2>Why another URL model?</h2>
 * Java includes both {@link URL} and {@link URI}. We offer a new URL model to address problems that
 * the others don't.
 * <h3>Different URLs should be different</h3>
 * Although they have different content, {@code java.net.URL} considers the following two URLs equal, and the
 * {@linkplain Object#equals equals()} method between them returns true:
 * <ul>
 * <li>https://example.net/
 * <li>https://example.com/
 * </ul>
 * This is because those two hosts share the same IP address. This is an old, bad design decision that makes
 * {@code java.net.URL} unusable for many things. It shouldn't be used as a {@linkplain Map Map} key or in a
 * {@link Set}. Doing so is both inefficient because equality may require a DNS lookup, and incorrect because unequal
 * URLs may be equal because of how they are hosted.
 * <h3>Equal URLs should be equal</h3>
 * These two URLs are semantically identical, but {@code java.net.URI} disagrees:
 * <ul>
 * <li> https://host:443/
 * <li> https://host
 * </ul>
 * Both the unnecessary port specification (`:443`) and the absent trailing slash (`/`) cause URI to bucket the two URLs
 * separately. This harms URI's usefulness in collections. Any application that stores information-per-URL will need to
 * either canonicalize manually, or suffer unnecessary redundancy for such URLs.
 * <p>
 * Because they don't attempt canonical form, these classes are surprisingly difficult to use securely. Suppose you're
 * building a webservice that checks that incoming paths are prefixed "/static/images/" before serving the corresponding
 * assets from the filesystem.
 * <pre>
 * {@code
 * String attack = "https://example.com/static/images/../../../../../etc/passwd";
 * System.out.println(new URL(attack).getPath());
 * System.out.println(new URI(attack).getPath());
 * System.out.println(HttpUrl.parse(attack).getEncodedPath());
 * }
 * </pre>
 * By canonicalizing the input paths, they are complicit in directory traversal attacks. Code that checks only the path
 * prefix may suffer!
 * This prints:
 * <pre>
 * {@code
 * /static/images/../../../../../etc/passwd
 * /static/images/../../../../../etc/passwd
 * /etc/passwd
 * }
 * </pre>
 * <h3>If it works on the web, it should work in your application</h3>
 * The {@code java.net.URI} class is strict around what URLs accepts. It rejects URLs like
 * {@code https://example.com/abc|def} because the {@code |} character is unsupported. This class is more forgiving: it
 * will automatically percent-encode the {@code |} yielding {@code https://example.com/abc%7Cdef}.
 * This kind behavior is consistent with web browsers. {@code HttpsUrl} prefers consistency with major web browsers over
 * consistency with obsolete specifications.
 * <h3>Paths and Queries should decompose</h3>
 * Neither of the built-in URL models offer direct access to path segments or query parameters.
 * <p>
 * Manually using {@code StringBuilder} to assemble these components is cumbersome:
 * <ul>
 * <li>do '+' characters get silently replaced with spaces?
 * <li>If a query parameter contains a '&amp;', does that get escaped?
 * </ul>
 * By offering methods to read and write individual query parameters directly, application developers are saved from the
 * hassles of encoding and decoding.
 * <h3>Plus a modern API</h3>
 * The URL (JDK1.0) and URI (Java 1.4) classes predate builders and instead use telescoping constructors. For example,
 * there's no API to compose a URI with a custom port without also providing a query and fragment.
 * <p>
 * Instances of {@link HttpUrl} are well-formed and always have a 'https://' scheme, a host, and a path. With
 * {@code java.net.URL} it's possible to create an awkward URL like {@code https:/} with scheme and path but no
 * hostname. Building APIs that consume such malformed values is difficult!
 * <p>
 * This class has a modern API. It avoids punitive checked exceptions: {@link HttpUrl#get(String)} throws
 * {@link IllegalArgumentException} on invalid input. {@link HttpUrl#parse(String)} returns null if the input is an
 * invalid URL. You can even be explicit about whether each component has been encoded already.
 */
public sealed interface HttpUrl permits RealHttpUrl {
    static @NonNull Builder builder() {
        return new RealHttpUrl.Builder();
    }

    /**
     * @return a new {@link HttpUrl} representing {@code url}.
     * @throws IllegalArgumentException If this is not a well-formed HTTP or HTTPS URL.
     */
    static @NonNull HttpUrl get(final @NonNull String url) {
        Objects.requireNonNull(url);
        return new RealHttpUrl.Builder().parse(null, url).build();
    }

    /**
     * @return a new {@link HttpUrl} representing {@code url} if it is a well-formed HTTP or HTTPS URL, or null if it isn't.
     */
    static @Nullable HttpUrl parse(final @NonNull String url) {
        Objects.requireNonNull(url);
        try {
            return get(url);
        } catch (IllegalArgumentException _unused) {
            return null;
        }
    }

    /**
     * @return a new {@link HttpUrl} representing {@code url} if its protocol is HTTP or HTTPS, or null if it has any other
     * protocol.
     */
    static @Nullable HttpUrl parse(final @NonNull URL url) {
        Objects.requireNonNull(url);
        return parse(url.toString());
    }

    /**
     * @return a new {@link HttpUrl} representing {@code uri} if its protocol is HTTP or HTTPS, or null if it has any other
     * protocol.
     */
    static @Nullable HttpUrl parse(final @NonNull URI uri) {
        Objects.requireNonNull(uri);
        return parse(uri.toString());
    }

    /**
     * @return 443 if {@code getScheme().equals("https")}, 80 if {@code getScheme().equals("http")} and -1 otherwise.
     */
    static int defaultPort(final @NonNull String scheme) {
        Objects.requireNonNull(scheme);
        return switch (scheme) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }

    /**
     * Either "http" or "https".
     */
    @NonNull
    String getScheme();

    boolean isHttps();

    /**
     * @return the decoded username, or an empty string if none is present.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getUsername()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://username@host/}</td>
     *         <td>{@code "username"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://username:password@host/}</td>
     *         <td>{@code "username"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://a%20b:c%20d@host/}</td>
     *         <td>{@code "a b"}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    String getUsername();

    /**
     * @return the decoded password, or an empty string if none is present.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getPassword()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://username@host/}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://username:password@host/}</td>
     *         <td>{@code "password"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://a%20b:c%20d@host/}</td>
     *         <td>{@code "c d"}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    String getPassword();

    /**
     * @return the host address suitable for use with {@link InetAddress#getAllByName(String)}. It may be:
     * <ul>
     * <li>A regular host name, like {@code android.com}.</li>
     * <li>An IPv4 address, like {@code 127.0.0.1}.</li>
     * <li>An IPv6 address, like {@code ::1}. Note that there are no square braces.</li>
     * <li>An encoded IDN, like {@code xn--n3h.net}.</li>
     * </ul>
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getHost()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://android.com/}</td>
     *         <td>{@code "android.com"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://127.0.0.1/}</td>
     *         <td>{@code "127.0.0.1"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://[::1]/}</td>
     *         <td>{@code "::1"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://xn--n3h.net/}</td>
     *         <td>{@code "xn--n3h.net"}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    String getHost();

    /**
     * @return the explicitly specified port if one was provided, or the default port 443 for HTTPS.
     * For example, this returns 8443 for {@code https://jayo.dev:8443/} and 443 for {@code https://jayo.dev/}.
     * The result is in the {@code [1..65535]} range.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getPort()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code 443}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host:8000/}</td>
     *         <td>{@code 8000}</td>
     *     </tr>
     * </table>
     */
    int getPort();

    /**
     * @return a list of path segments like {@code ["a", "b", "c"]} for the URL {@code https://host/a/b/c}. This list is
     * never empty, though it may contain a single empty string.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getPathSegments()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code [""]}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/a/b/c}</td>
     *         <td>{@code ["a", "b", "c"]}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/a/b%20c/d}</td>
     *         <td>{@code ["a", "b c", "d"]}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    List<@NonNull String> getPathSegments();

    /**
     * @return this URL's fragment, like {@code "abc"} for {@code https://host/#abc}. This is null if the URL has no
     * fragment.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getFragment()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/#}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/#abc}</td>
     *         <td>{@code "abc"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/#abc|def}</td>
     *         <td>{@code "abc|def"}</td>
     *     </tr>
     * </table>
     */
    @Nullable
    String getFragment();

    /**
     * @return this URL as a {@linkplain URL java.net.URL}.
     */
    @NonNull
    URL toUrl();

    /**
     * @return this URL as a {@linkplain URI java.net.URI}. Because {@code URI} is stricter than this class, the
     * returned URI may be semantically different from this URL:
     * <ul>
     *     <li>Characters forbidden by URI like {@code [} and {@code |} will be escaped.</li>
     *     <li>Invalid percent-encoded sequences like {@code %xx} will be encoded like {@code %25xx}.</li>
     *     <li>Whitespace and control characters in the fragment will be stripped.</li>
     * </ul>
     * These differences may have a significant consequence when the URI is interpreted by a web server.
     * For this reason the {@linkplain URI URI class} and this method <b>should be avoided</b>.
     */
    @NonNull
    URI toUri();

    /**
     * @return the username, or an empty string if none is set.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getEncodedUsername()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://username@host/}</td>
     *         <td>{@code "username"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://username:password@host/}</td>
     *         <td>{@code "username"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://a%20b:c%20d@host/}</td>
     *         <td>{@code "a%20b"}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    String getEncodedUsername();

    /**
     * @return the password, or an empty string if none is set.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getEncodedPassword()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://username@host/}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://username:password@host/}</td>
     *         <td>{@code "password"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://a%20b:c%20d@host/}</td>
     *         <td>{@code "c%20d"}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    String getEncodedPassword();

    /**
     * @return the entire path of this URL encoded for use in HTTP resource resolution. The returned path will start
     * with {@code "/"}.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getEncodedPath()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code "/"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/a/b/c}</td>
     *         <td>{@code "/a/b/c"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/a/b%20c/d}</td>
     *         <td>{@code "/a/b%20c/d"}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    String getEncodedPath();

    /**
     * @return a list of encoded path segments like {@code ["a", "b", "c"]} for the URL {@code https://host/a/b/c}.
     * This list is never empty though it may contain a single empty string.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getEncodedPathSegments()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code [""]}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/a/b/c}</td>
     *         <td>{@code ["a", "b", "c"]}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/a/b%20c/d}</td>
     *         <td>{@code ["a", "b%20c", "d"]}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    List<String> getEncodedPathSegments();

    /**
     * @return the query of this URL, encoded for use in HTTP resource resolution. This string may be null (for URLs with
     * no query), empty (for URLs with an empty query) or non-empty (all other URLs).
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getEncodedQuery()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&k=key+lime}</td>
     *         <td>{@code "a=apple&k=key+lime"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&a=apricot}</td>
     *         <td>{@code "a=apple&a=apricot"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&b}</td>
     *         <td>{@code "a=apple&b"}</td>
     *     </tr>
     * </table>
     */
    @Nullable
    String getEncodedQuery();

    /**
     * @return the number of segments in this URL's path. This is also the number of slashes in this URL's path, like
     * '3' in {@code https://host/a/b/c}. This is always at least '1'.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getPathSize()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code 1}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/a/b/c}</td>
     *         <td>{@code 3}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/a/b/c/}</td>
     *         <td>{@code 4}</td>
     *     </tr>
     * </table>
     */
    int getPathSize();

    /**
     * @return this URL's query, like {@code "abc"} for {@code https://host/?abc}. Most callers should prefer
     * {@link #queryParameterName} and {@link #queryParameterValue} because these methods offer direct access to
     * individual query parameters.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getQuery()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&k=key+lime}</td>
     *         <td>{@code "a=apple&k=key lime"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&a=apricot}</td>
     *         <td>{@code "a=apple&a=apricot"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&b}</td>
     *         <td>{@code "a=apple&b"}</td>
     *     </tr>
     * </table>
     */
    @Nullable
    String getQuery();

    /**
     * @return the number of query parameters in this URL, like '2' for {@code https://host/?a=apple&b=banana}. If this
     * URL has no query, this is '0'. Otherwise, it is one more than the number of {@code &} separators in the query.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getQuerySize()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code 0}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?}</td>
     *         <td>{@code 1}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&k=key+lime}</td>
     *         <td>{@code 2}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&a=apricot}</td>
     *         <td>{@code 2}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&b}</td>
     *         <td>{@code 2}</td>
     *     </tr>
     * </table>
     */
    int getQuerySize();

    /**
     * @return the first query parameter named {@code name} decoded using UTF-8, or null if there is no such query
     * parameter.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getQueryParameter("a")}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&k=key+lime}</td>
     *         <td>{@code "apple"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&a=apricot}</td>
     *         <td>{@code "apple"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&b}</td>
     *         <td>{@code "apple"}</td>
     *     </tr>
     * </table>
     */
    @Nullable
    String queryParameter(final @NonNull String name);

    /**
     * @return the distinct query parameter names in this URL, like {@code ["a", "b"]} for
     * {@code https://host/?a=apple&b=banana}. If this URL has no query, this is the empty set.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getQueryParameterNames()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code []}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?}</td>
     *         <td>{@code [""]}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&k=key+lime}</td>
     *         <td>{@code ["a", "k"]}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&a=apricot}</td>
     *         <td>{@code ["a"]}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&b}</td>
     *         <td>{@code ["a", "b"]}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    Set<@NonNull String> getQueryParameterNames();

    /**
     * @return all values for the query parameter {@code name} ordered by their appearance in this URL. For example,
     * this returns {@code ["banana"]} for {@code getQueryParameterValues("b")} on
     * {@code https://host/?a=apple&b=banana}.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getQueryParameterValues("a")}</th>
     *         <th>{@code getQueryParameterValues("b")}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code []}</td>
     *         <td>{@code []}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?}</td>
     *         <td>{@code []}</td>
     *         <td>{@code []}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&k=key+lime}</td>
     *         <td>{@code ["apple"]}</td>
     *         <td>{@code []}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&a=apricot}</td>
     *         <td>{@code ["apple", "apricot"]}</td>
     *         <td>{@code []}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&b}</td>
     *         <td>{@code ["apple"]}</td>
     *         <td>{@code [null]}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    List<@Nullable String> queryParameterValues(final @NonNull String name);

    /**
     * @return the name of the query parameter at {@code index}. For example, this returns {@code "a"} for
     * {@code getQueryParameterName(0)} on {@code https://host/?a=apple&b=banana}. This throws if {@code index} is not
     * less than the {@linkplain #getQuerySize query size}.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getQueryParameterName(0)}</th>
     *         <th>{@code getQueryParameterName(1)}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code exception}</td>
     *         <td>{@code exception}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?}</td>
     *         <td>{@code ""}</td>
     *         <td>{@code exception}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&k=key+lime}</td>
     *         <td>{@code "a"}</td>
     *         <td>{@code "k"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&a=apricot}</td>
     *         <td>{@code "a"}</td>
     *         <td>{@code "a"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&b}</td>
     *         <td>{@code "a"}</td>
     *         <td>{@code "b"}</td>
     *     </tr>
     * </table>
     */
    @NonNull
    String queryParameterName(final int index);

    /**
     * @return the value of the query parameter at {@code index}. For example, this returns {@code "apple"} for
     * {@code getQueryParameterValue(0)} on {@code https://host/?a=apple&b=banana}. This throws if {@code index} is not
     * less than the {@linkplain #getQuerySize query size}.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getQueryParameterValue(0)}</th>
     *         <th>{@code getQueryParameterValue(1)}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code exception}</td>
     *         <td>{@code exception}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?}</td>
     *         <td>{@code null}</td>
     *         <td>{@code exception}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&k=key+lime}</td>
     *         <td>{@code "apple"}</td>
     *         <td>{@code "key lime"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&a=apricot}</td>
     *         <td>{@code "apple"}</td>
     *         <td>{@code "apricot"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/?a=apple&b}</td>
     *         <td>{@code "apple"}</td>
     *         <td>{@code null}</td>
     *     </tr>
     * </table>
     */
    @Nullable
    String queryParameterValue(final int index);

    /**
     * @return this URL's encoded fragment, like {@code "abc"} for {@code https://host/#abc}. This is null if the URL
     * has no fragment.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code getEncodedFragment()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/#}</td>
     *         <td>{@code ""}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/#abc}</td>
     *         <td>{@code "abc"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://host/#abc|def}</td>
     *         <td>{@code "abc|def"}</td>
     *     </tr>
     * </table>
     */
    @Nullable
    String getEncodedFragment();

    /**
     * @return a string containing this URL with its username, password, query, and fragment stripped, and its path
     * replaced with {@code /...}. For example, redacting {@code https://username:password@example.com/path} returns
     * {@code https://example.com/...}.
     */
    @NonNull
    String redact();

    /**
     * @return the URL that would be retrieved by following {@code link} from this URL, or null if the resulting URL is
     * not well-formed.
     */
    @Nullable
    HttpUrl resolve(final @NonNull String link);

    /**
     * @return a builder based on this URL.
     */
    @NonNull
    Builder newBuilder();

    /**
     * @return a builder for the URL that would be retrieved by following {@code link} from this URL, or null if the
     * resulting URL is not well-formed.
     */
    @Nullable
    Builder newBuilder(final @NonNull String link);

    /**
     * @return the domain name of this URL's {@code host} that is one level beneath the public suffix by
     * consulting the <a href="https://publicsuffix.org">public suffix list</a>. Returns null if this URL's
     * {@code host} is an IP address or is considered a public suffix by the public suffix list.
     * <p>
     * In general this method <b>should not</b> be used to test whether a domain is valid or routable.
     * Instead, DNS is the recommended source for that information.
     *
     * <table>
     *     <tr>
     *         <th>URL</th>
     *         <th>{@code topPrivateDomain()}</th>
     *     </tr>
     *     <tr>
     *         <td>{@code https://google.com}</td>
     *         <td>{@code "google.com"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://adwords.google.co.uk}</td>
     *         <td>{@code "google.co.uk"}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://jayo}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://co.uk}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://localhost}</td>
     *         <td>{@code null}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code https://127.0.0.1}</td>
     *         <td>{@code null}</td>
     *     </tr>
     * </table>
     */
    @Nullable
    String topPrivateDomain();

    /**
     * The builder used to create a {@link HttpUrl} instance.
     */
    sealed interface Builder permits RealHttpUrl.Builder {
        /**
         * @param scheme either "http" or "https".
         */
        @NonNull
        Builder scheme(final @NonNull String scheme);

        /**
         * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6 address.
         */
        @NonNull
        Builder host(final @NonNull String host);

        @NonNull
        Builder port(final int port);

        @NonNull
        Builder username(final @NonNull String username);

        @NonNull
        Builder encodedUsername(final @NonNull String encodedUsername);

        @NonNull
        Builder password(final @NonNull String password);

        @NonNull
        Builder encodedPassword(final @NonNull String encodedPassword);

        @NonNull
        Builder addPathSegment(final @NonNull String pathSegment);

        /**
         * Adds a set of path segments separated by a slash (either {@code \} or {@code /}). If {@code pathSegments}
         * starts with a slash, the resulting URL will have empty path segment.
         */
        @NonNull
        Builder addPathSegments(final @NonNull String pathSegments);

        @NonNull
        Builder addEncodedPathSegment(final @NonNull String encodedPathSegment);

        /**
         * Adds a set of encoded path segments separated by a slash (either {@code \} or {@code /}). If
         * {@code encodedPathSegments} starts with a slash, the resulting URL will have an empty path segment.
         */
        @NonNull
        Builder addEncodedPathSegments(final @NonNull String encodedPathSegments);

        @NonNull
        Builder setPathSegment(final int index, final @NonNull String pathSegment);

        @NonNull
        Builder setEncodedPathSegment(final int index, final @NonNull String encodedPathSegment);

        @NonNull
        Builder removePathSegment(final int index);

        @NonNull
        Builder encodedPath(final @NonNull String encodedPath);

        @NonNull
        Builder query(final @Nullable String query);

        @NonNull
        Builder encodedQuery(final @Nullable String encodedQuery);

        /**
         * Encodes the query parameter using UTF-8 and adds it to this URL's query string.
         */
        @NonNull
        Builder addQueryParameter(final @NonNull String name, final @Nullable String value);

        /**
         * Adds the pre-encoded query parameter to this URL's query string.
         */
        @NonNull
        Builder addEncodedQueryParameter(final @NonNull String encodedName, final @Nullable String encodedValue);

        @NonNull
        Builder setQueryParameter(final @NonNull String name, final @Nullable String value);

        @NonNull
        Builder setEncodedQueryParameter(final @NonNull String encodedName, final @Nullable String encodedValue);

        @NonNull
        Builder removeAllQueryParameters(final @NonNull String name);

        @NonNull
        Builder removeAllEncodedQueryParameters(final @NonNull String encodedName);

        @NonNull
        Builder removeAllCanonicalQueryParameters(final @NonNull String canonicalName);

        @NonNull
        Builder fragment(final @Nullable String fragment);

        @NonNull
        Builder encodedFragment(final @Nullable String encodedFragment);

        @NonNull
        HttpUrl build();
    }
}
