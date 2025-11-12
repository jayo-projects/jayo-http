/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2013 Square, Inc.
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jayo.http.internal.connection;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;

/**
 * An immutable collection of key-value pairs implemented as a singly linked list.
 * <p>
 * Build up a collection by starting with {@linkplain EmptyTags EmptyTags} and repeatedly calling
 * {@link #plus(Class, Object)} . Each such call returns a new instance.
 * <p>
 * This collection is optimized for safe concurrent access over a very small number of elements.
 * <p>
 * This collection and is expected to hold fewer than 10 elements. Each operation is <i>O(N)</i>, and so building an
 * instance with <i>N</i> elements is <i>O(N**2)</i>.
 */
sealed interface Tags {
    /**
     * @return a {@link Tags} instance that maps {@code key} to {@code value}. If {@code value} is null, this returns a
     * {@link Tags} instance that does not have any mapping for {@code key}.
     */
    <T> Tags plus(final Class<@NonNull T> key, final @Nullable T value);

    <T> T get(final Class<@NonNull T> key);

    /**
     * An empty tags. This is always the tail of a {@link LinkedTags} chain.
     */
    enum EmptyTags implements Tags {
        INSTANCE;

        @Override
        public <T> Tags plus(final Class<@NonNull T> key, final @Nullable T value) {
            assert key != null;

            return (value != null)
                    ? new LinkedTags<>(key, value, this)
                    : this;
        }

        @Override
        public <T> T get(final Class<@NonNull T> key) {
            assert key != null;
            return null;
        }

        @Override
        public String toString() {
            return "{}";
        }
    }

    /**
     * An invariant of this implementation is that {@link #next} must not contain a mapping for {@link #key}. Otherwise,
     * we would have two values for the same key.
     */
    final class LinkedTags<K> implements Tags {
        private final @NonNull Class<@NonNull K> key;
        private final @NonNull K value;
        private final @NonNull Tags next;

        private LinkedTags(final @NonNull Class<@NonNull K> key,
                           final @NonNull K value,
                           final @NonNull Tags next) {
            assert key != null;
            assert value != null;
            assert next != null;

            this.key = key;
            this.value = value;
            this.next = next;
        }

        @Override
        public <T> Tags plus(final Class<@NonNull T> key, final @Nullable T value) {
            assert key != null;

            // Create a copy of this `LinkedTags` that doesn't have a mapping for `key`.
            final Tags thisMinusKey;
            if (key.equals(this.key)) {
                thisMinusKey = next; // Subtract this!
            } else {
                final var nextMinusKey = next.plus(key, null);
                if (nextMinusKey == next) {
                    thisMinusKey = this; // Same as the following line, but with fewer allocations.
                } else {
                    thisMinusKey = new LinkedTags<>(this.key, this.value, nextMinusKey);
                }
            }

            // Return a new `Tags` that maps `key` to `value`.
            if (value != null) {
                return new LinkedTags<>(key, value, thisMinusKey);
            } else {
                return thisMinusKey;
            }
        }

        @Override
        public <T> T get(final Class<@NonNull T> key) {
            assert key != null;

            if (key.equals(this.key)) {
                return key.cast(value);
            }
            return next.get(key);
        }

        /**
         * Returns a {@code toString()} consistent with {@link java.util.Map}, with elements in insertion order.
         */
        @Override
        public @NonNull String toString() {
            final var tags = new ArrayList<LinkedTags<?>>();
            Tags nextTag = this;
            while (nextTag instanceof LinkedTags<?> nextLinkedTags) {
                tags.add(nextLinkedTags);
                nextTag = nextLinkedTags.next;
            }
            Collections.reverse(tags);

            final var sb = new StringBuilder("{");
            for (var i = 0; i < tags.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                final var tag = tags.get(i);
                sb.append(tag.key).append('=').append(tag.value);
            }
            sb.append('}');
            return sb.toString();
        }
    }
}
