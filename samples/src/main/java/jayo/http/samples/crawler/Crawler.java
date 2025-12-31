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

package jayo.http.samples.crawler;

import jayo.JayoException;
import jayo.http.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetches HTML from a requested URL, follows the links, and repeats.
 * <p>
 * The main function requires 2 arguments: the first is the cache dir, the second is the root URL to crawl.
 * These arguments from a Linux desktop could be: {@code /tmp/jayo-cache https://jayo.dev/}.
 */
public final class Crawler {
    private final JayoHttpClient client;
    private final Set<HttpUrl> fetchedUrls = Collections.synchronizedSet(new LinkedHashSet<>());
    private final BlockingQueue<HttpUrl> queue;
    private final ConcurrentHashMap<String, AtomicInteger> hostnames = new ConcurrentHashMap<>();
    private final int hostLimit;

    public Crawler(JayoHttpClient client, int queueLimit, int hostLimit) {
        this.client = client;
        this.queue = new LinkedBlockingQueue<>(queueLimit);
        this.hostLimit = hostLimit;
    }

    private void parallelDrainQueue(int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    drainQueue();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            });
        }
        executor.shutdown();
    }

    private void drainQueue() throws Exception {
        for (HttpUrl url; (url = queue.take()) != null; ) {
            if (!fetchedUrls.add(url)) {
                continue;
            }

            Thread currentThread = Thread.currentThread();
            String originalName = currentThread.getName();
            currentThread.setName("Crawler " + url);
            try {
                fetch(url);
            } catch (JayoException je) {
                System.out.printf("XXX: %s %s%n", url, je);
            } finally {
                currentThread.setName(originalName);
            }
        }
    }

    public void fetch(HttpUrl url) {
        // Skip hosts that we've visited many times.
        AtomicInteger hostnameCount = new AtomicInteger();
        AtomicInteger previous = hostnames.putIfAbsent(url.getHost(), hostnameCount);
        if (previous != null) {
            hostnameCount = previous;
        }
        if (hostnameCount.getAndIncrement() >= hostLimit) {
            return;
        }

        ClientRequest request = ClientRequest.builder()
                .url(url)
                .get();
        try (ClientResponse response = client.newCall(request).execute()) {
            String responseSource = (response.getNetworkResponse() != null)
                    ? ("(network: "
                    + response.getNetworkResponse().getStatusCode()
                    + " over "
                    + response.getProtocol()
                    + ")")
                    : "(cache)";
            int responseCode = response.getStatusCode();

            System.out.printf("%03d: %s %s%n", responseCode, url, responseSource);

            String contentType = response.header("Content-Type");
            if (responseCode != 200 || contentType == null) {
                return;
            }

            MediaType mediaType = MediaType.parse(contentType);
            if (mediaType == null || !mediaType.getSubtype().equalsIgnoreCase("html")) {
                return;
            }

            Document document = Jsoup.parse(response.getBody().string(), url.toString());
            for (Element element : document.select("a[href]")) {
                String href = element.attr("href");
                HttpUrl link = response.getRequest().getUrl().resolve(href);
                if (link == null) {
                    continue; // URL is either invalid or its scheme isn't http/https.
                }
                HttpUrl linkWithoutFragment = link.newBuilder().fragment(null).build();
                if (!queue.offer(linkWithoutFragment)) {
                    break; // Queue is full.
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: Crawler <cache dir> <root>");
            return;
        }

        int threadCount = 20;
        int queueLimit = 1000;
        int hostLimit = 25;
        long cacheByteCount = 1024L * 1024L * 100L;

        Cache cache = Cache.create(Path.of(args[0]), cacheByteCount);
        JayoHttpClient client = JayoHttpClient.builder()
                .cache(cache)
                .callTimeout(Duration.ofSeconds(5))
                .build();

        Crawler crawler = new Crawler(client, queueLimit, hostLimit);
        crawler.queue.add(HttpUrl.get(args[1]));
        crawler.parallelDrainQueue(threadCount);
    }
}
