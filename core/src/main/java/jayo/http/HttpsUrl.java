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

import jayo.external.NonNegative;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A uniform resource locator (URL) with a scheme of either `http` or `https`. Use this class to
 * compose and decompose Internet addresses. For example, this code will compose and print a URL for
 * Google search:
 *
 * ```java
 * HttpUrl url = new HttpUrl.Builder()
 *     .scheme("https")
 *     .host("www.google.com")
 *     .addPathSegment("search")
 *     .addQueryParameter("q", "polar bears")
 *     .build();
 * System.out.println(url);
 * ```
 *
 * which prints:
 *
 * ```
 * https://www.google.com/search?q=polar%20bears
 * ```
 *
 * As another example, this code prints the human-readable query parameters of a Twitter search:
 *
 * ```java
 * HttpUrl url = HttpUrl.parse("https://twitter.com/search?q=cute%20%23puppies&f=images");
 * for (int i = 0, size = url.querySize(); i < size; i++) {
 *   System.out.println(url.queryParameterName(i) + ": " + url.queryParameterValue(i));
 * }
 * ```
 *
 * which prints:
 *
 * ```
 * q: cute #puppies
 * f: images
 * ```
 *
 * In addition to composing URLs from their component parts and decomposing URLs into their
 * component parts, this class implements relative URL resolution: what address you'd reach by
 * clicking a relative link on a specified page. For example:
 *
 * ```java
 * HttpUrl base = HttpUrl.parse("https://www.youtube.com/user/WatchTheDaily/videos");
 * HttpUrl link = base.resolve("../../watch?v=cbP2N1BQdYc");
 * System.out.println(link);
 * ```
 *
 * which prints:
 *
 * ```
 * https://www.youtube.com/watch?v=cbP2N1BQdYc
 * ```
 *
 * ## What's in a URL?
 *
 * A URL has several components.
 *
 * ### Scheme
 *
 * Sometimes referred to as *protocol*, A URL's scheme describes what mechanism should be used to
 * retrieve the resource. Although URLs have many schemes (`mailto`, `file`, `ftp`), this class only
 * supports `http` and `https`. Use [java.net.URI][URI] for URLs with arbitrary schemes.
 *
 * ### Username and Password
 *
 * Username and password are either present, or the empty string `""` if absent. This class offers
 * no mechanism to differentiate empty from absent. Neither of these components are popular in
 * practice. Typically HTTP applications use other mechanisms for user identification and
 * authentication.
 *
 * ### Host
 *
 * The host identifies the webserver that serves the URL's resource. It is either a hostname like
 * `square.com` or `localhost`, an IPv4 address like `192.168.0.1`, or an IPv6 address like `::1`.
 *
 * Usually a webserver is reachable with multiple identifiers: its IP addresses, registered
 * domain names, and even `localhost` when connecting from the server itself. Each of a web server's
 * names is a distinct URL and they are not interchangeable. For example, even if
 * `http://square.github.io/dagger` and `http://google.github.io/dagger` are served by the same IP
 * address, the two URLs identify different resources.
 *
 * ### Port
 *
 * The port used to connect to the web server. By default this is 80 for HTTP and 443 for HTTPS.
 * This class never returns -1 for the port: if no port is explicitly specified in the URL then the
 * scheme's default is used.
 *
 * ### Path
 *
 * The path identifies a specific resource on the host. Paths have a hierarchical structure like
 * "/square/okhttp/issues/1486" and decompose into a list of segments like `["square", "okhttp",
 * "issues", "1486"]`.
 *
 * This class offers methods to compose and decompose paths by segment. It composes each path
 * from a list of segments by alternating between "/" and the encoded segment. For example the
 * segments `["a", "b"]` build "/a/b" and the segments `["a", "b", ""]` build "/a/b/".
 *
 * If a path's last segment is the empty string then the path ends with "/". This class always
 * builds non-empty paths: if the path is omitted it defaults to "/". The default path's segment
 * list is a single empty string: `[""]`.
 *
 * ### Query
 *
 * The query is optional: it can be null, empty, or non-empty. For many HTTP URLs the query string
 * is subdivided into a collection of name-value parameters. This class offers methods to set the
 * query as the single string, or as individual name-value parameters. With name-value parameters
 * the values are optional and names may be repeated.
 *
 * ### Fragment
 *
 * The fragment is optional: it can be null, empty, or non-empty. Unlike host, port, path, and
 * query the fragment is not sent to the webserver: it's private to the client.
 *
 * ## Encoding
 *
 * Each component must be encoded before it is embedded in the complete URL. As we saw above, the
 * string `cute #puppies` is encoded as `cute%20%23puppies` when used as a query parameter value.
 *
 * ### Percent encoding
 *
 * Percent encoding replaces a character (like `\ud83c\udf69`) with its UTF-8 hex bytes (like
 * `%F0%9F%8D%A9`). This approach works for whitespace characters, control characters, non-ASCII
 * characters, and characters that already have another meaning in a particular context.
 *
 * Percent encoding is used in every URL component except for the hostname. But the set of
 * characters that need to be encoded is different for each component. For example, the path
 * component must escape all of its `?` characters, otherwise it could be interpreted as the
 * start of the URL's query. But within the query and fragment components, the `?` character
 * doesn't delimit anything and doesn't need to be escaped.
 *
 * ```java
 * HttpUrl url = HttpUrl.parse("http://who-let-the-dogs.out").newBuilder()
 *     .addPathSegment("_Who?_")
 *     .query("_Who?_")
 *     .fragment("_Who?_")
 *     .build();
 * System.out.println(url);
 * ```
 *
 * This prints:
 *
 * ```
 * http://who-let-the-dogs.out/_Who%3F_?_Who?_#_Who?_
 * ```
 *
 * When parsing URLs that lack percent encoding where it is required, this class will percent encode
 * the offending characters.
 *
 * ### IDNA Mapping and Punycode encoding
 *
 * Hostnames have different requirements and use a different encoding scheme. It consists of IDNA
 * mapping and Punycode encoding.
 *
 * In order to avoid confusion and discourage phishing attacks, [IDNA Mapping][idna] transforms
 * names to avoid confusing characters. This includes basic case folding: transforming shouting
 * `SQUARE.COM` into cool and casual `square.com`. It also handles more exotic characters. For
 * example, the Unicode trademark sign (™) could be confused for the letters "TM" in
 * `http://ho™ail.com`. To mitigate this, the single character (™) maps to the string (tm). There
 * is similar policy for all of the 1.1 million Unicode code points. Note that some code points such
 * as "\ud83c\udf69" are not mapped and cannot be used in a hostname.
 *
 * [Punycode](http://ietf.org/rfc/rfc3492.txt) converts a Unicode string to an ASCII string to make
 * international domain names work everywhere. For example, "σ" encodes as "xn--4xa". The encoded
 * string is not human readable, but can be used with classes like [InetAddress] to establish
 * connections.
 *
 * ## Why another URL model?
 *
 * Java includes both [java.net.URL][URL] and [java.net.URI][URI]. We offer a new URL
 * model to address problems that the others don't.
 *
 * ### Different URLs should be different
 *
 * Although they have different content, `java.net.URL` considers the following two URLs
 * equal, and the [equals()][Object.equals] method between them returns true:
 *
 *  * https://example.net/
 *
 *  * https://example.com/
 *
 * This is because those two hosts share the same IP address. This is an old, bad design decision
 * that makes `java.net.URL` unusable for many things. It shouldn't be used as a [Map] key or in a
 * [Set]. Doing so is both inefficient because equality may require a DNS lookup, and incorrect
 * because unequal URLs may be equal because of how they are hosted.
 *
 * ### Equal URLs should be equal
 *
 * These two URLs are semantically identical, but `java.net.URI` disagrees:
 *
 *  * http://host:80/
 *
 *  * http://host
 *
 * Both the unnecessary port specification (`:80`) and the absent trailing slash (`/`) cause URI to
 * bucket the two URLs separately. This harms URI's usefulness in collections. Any application that
 * stores information-per-URL will need to either canonicalize manually, or suffer unnecessary
 * redundancy for such URLs.
 *
 * Because they don't attempt canonical form, these classes are surprisingly difficult to use
 * securely. Suppose you're building a webservice that checks that incoming paths are prefixed
 * "/static/images/" before serving the corresponding assets from the filesystem.
 *
 * ```java
 * String attack = "http://example.com/static/images/../../../../../etc/passwd";
 * System.out.println(new URL(attack).getPath());
 * System.out.println(new URI(attack).getPath());
 * System.out.println(HttpUrl.parse(attack).encodedPath());
 * ```
 *
 * By canonicalizing the input paths, they are complicit in directory traversal attacks. Code that
 * checks only the path prefix may suffer!
 *
 * ```
 * /static/images/../../../../../etc/passwd
 * /static/images/../../../../../etc/passwd
 * /etc/passwd
 * ```
 *
 * ### If it works on the web, it should work in your application
 *
 * The `java.net.URI` class is strict around what URLs it accepts. It rejects URLs like
 * `http://example.com/abc|def` because the `|` character is unsupported. This class is more
 * forgiving: it will automatically percent-encode the `|'` yielding `http://example.com/abc%7Cdef`.
 * This kind behavior is consistent with web browsers. `HttpUrl` prefers consistency with major web
 * browsers over consistency with obsolete specifications.
 *
 * ### Paths and Queries should decompose
 *
 * Neither of the built-in URL models offer direct access to path segments or query parameters.
 * Manually using `StringBuilder` to assemble these components is cumbersome: do '+' characters get
 * silently replaced with spaces? If a query parameter contains a '&amp;', does that get escaped?
 * By offering methods to read and write individual query parameters directly, application
 * developers are saved from the hassles of encoding and decoding.
 *
 * ### Plus a modern API
 *
 * The URL (JDK1.0) and URI (Java 1.4) classes predate builders and instead use telescoping
 * constructors. For example, there's no API to compose a URI with a custom port without also
 * providing a query and fragment.
 *
 * Instances of [HttpUrl] are well-formed and always have a scheme, host, and path. With
 * `java.net.URL` it's possible to create an awkward URL like `http:/` with scheme and path but no
 * hostname. Building APIs that consume such malformed values is difficult!
 *
 * This class has a modern API. It avoids punitive checked exceptions: [toHttpUrl] throws
 * [IllegalArgumentException] on invalid input or [toHttpUrlOrNull] returns null if the input is an
 * invalid URL. You can even be explicit about whether each component has been encoded already.
 *
 * [idna]: http://www.unicode.org/reports/tr46/#ToASCII
 */
public interface HttpsUrl {
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
    @NonNull String getEncodedUsername();

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
    @NonNull String getEncodedPassword();

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
    @NonNull String getEncodedPath();

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
    @NonNull List<String> getEncodedPathSegments();

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
    @Nullable String getEncodedQuery();

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
    @NonNull String getUsername();

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
    @NonNull String getPassword();

    /**
     * @return the host address suitable for use with {@link java.net.InetAddress#getAllByName}. May be:
     * <ul>
     *     <li>A regular host name, like {@code android.com}.</li>
     *     <li>An IPv4 address, like {@code 127.0.0.1}.</li>
     *     <li>An IPv6 address, like {@code ::1}. Note that there are no square braces.</li>
     *     <li>An encoded IDN, like {@code xn--n3h.net}.</li>
     * </ul>
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
    @NonNull String getHost();

    /**
     * @return the explicitly-specified port if one was provided, or the default port 443 for HTTPS.
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
     * never empty though it may contain a single empty string.
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
    @NonNull List<@NonNull String> getPathSegments();

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
    @Nullable String getFragment();

    /**
     * @return this URL as a {@linkplain URL java.net.URL}
     */
    @NonNull URL toUrl();

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
    @NonNull URI toUri();

    /**
     * @return the number of segments in this URL's path. This is also the number of slashes in this URL's path, like 3
     * in {@code https://host/a/b/c}. This is always at least 1.
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
     * {@link #getQueryParameterName} and {@link #getQueryParameterValue} because these methods offer direct access to
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
    @Nullable String getQuery();

    /**
     * @return the number of query parameters in this URL, like 2 for {@code https://host/?a=apple&b=banana}. If this URL
     * has no query this is 0. Otherwise, it is one more than the number of {@code "&"} separators in the query.
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
    @NonNegative
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
    @Nullable String getQueryParameter(final @NonNull String name);

    /**
     * @return the distinct query parameter names in this URL, like {@code ["a", "b"]} for
     * {@code https://host/?a=apple&b=banana}. If this URL has no query this is the empty set.
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
    @NonNull Set<@NonNull String> getQueryParameterNames();

    /**
     * @return all values for the query parameter {@code name} ordered by their appearance in this URL. For example this
     * returns {@code ["banana"]} for {@code getQueryParameterValues("b")} on {@code https://host/?a=apple&b=banana}.
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
    @NonNull List<@Nullable String> getQueryParameterValues(final @NonNull String name);

    /**
     * @return the name of the query parameter at {@code index}. For example this returns {@code "a"} for
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
    @NonNull String getQueryParameterName(final @NonNegative int index);

    /**
     * @return the value of the query parameter at {@code index}. For example this returns {@code "apple"} for
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
    @Nullable String getQueryParameterValue(final @NonNegative int index);

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
    @Nullable String getEncodedFragment();

    /**
     * @return a string containing this URL with its username, password, query, and fragment stripped, and its path
     * replaced with {@code /...}. For example, redacting {@code https://username:password@example.com/path} returns
     * {@code https://example.com/...}.
     */
    @NonNull String redact();

    /**
     * @return the URL that would be retrieved by following {@code link} from this URL, or null if the resulting URL is
     * not well-formed.
     */
    @Nullable HttpsUrl resolve(final @NonNull String link);

    /**
     * Returns the domain name of this URL's {@code host} that is one level beneath the public suffix by
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
    @Nullable String topPrivateDomain();

    /**
     * @return a new {@link HttpsUrl} built with this {@code configurer}.
     */
    static @NonNull HttpsUrl create(final @NonNull Consumer<Config> configurer) {
        Objects.requireNonNull(configurer);
        final var builder = new RealHttpsUrl.Builder();
        configurer.accept(builder);
        return builder.build();
    }

    /**
     * The configuration used to create a {@link HttpsUrl} instance.
     */
    interface Config {
        void setHost(final @NonNull String host);

        void setPort(final int port);

        void setPassword(final @NonNull String password);

        void setEncodedPassword(final @NonNull String encodedPassword);

        void setUsername(final @NonNull String username);

        void setEncodedUsername(final @NonNull String encodedUsername);

        void setEncodedPath(final @NonNull String encodedPath);

        void setPathSegments(final @NonNull String rootPathSegment,
                             final @NonNull String @NonNull ... otherPathSegments);

        void setEncodedPathSegments(final @NonNull String rootEncodedPathSegment,
                                    final @NonNull String @NonNull ... otherEncodedPathSegments);

        void addPathSegment(final @NonNull String pathSegment);

        void addEncodedPathSegment(final @NonNull String encodedPathSegment);

        void setQuery(final @Nullable String query);

        void setEncodedQuery(final @Nullable String encodedQuery);

        void addQueryParameter(final @NonNull String name, final @Nullable String value);

        void addEncodedQueryParameter(final @NonNull String encodedName, final @Nullable String encodedValue);

        void setFragment(final @Nullable String fragment);

        void setEncodedFragment(final @Nullable String encodedFragment);
    }

    /**
     * @return a new {@link HttpsUrl} representing {@code url}.
     * @throws IllegalArgumentException If this is not a well-formed HTTPS URL.
     */
    static @NonNull HttpsUrl get(final @NonNull String url) {
        return create(config -> config.parse(null, url));
    }

    /**
     * @return a new {@link HttpsUrl} representing {@code url} if it is a well-formed HTTPS URL, or null if it isn't.
     */
    static @Nullable HttpsUrl parse(final @NonNull String url) {
        try {
            return get(url);
        } catch (IllegalArgumentException _unused) {
            return null;
        }
    }

    /**
     * @return a new {@link HttpsUrl} representing {@code url} if its protocol is HTTPS, or null if it has any other
     * protocol.
     */
    static @Nullable HttpsUrl parse(final @NonNull URL url) {
        return parse(url.toString());
    }

    /**
     * @return a new {@link HttpsUrl} representing {@code uri} if its protocol is HTTPS, or null if it has any other
     * protocol.
     */
    static @Nullable HttpsUrl parse(final @NonNull URI uri) {
        return parse(uri.toString());
    }
}
