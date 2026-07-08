/*
 * Copyright 2026 Dominik Schlosser
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.dominikschlosser.k8store.common;

import java.util.regex.Pattern;

/**
 * SQL-LIKE matching for in-memory searches. Keycloak's admin/search APIs hand providers LIKE
 * patterns ({@code %} wildcards, e.g. {@code %foo%}); the default stores match them in SQL, an
 * in-memory store has to do it itself. Case-insensitive and case-sensitive variants exist
 * because the upstream queries mix both (e.g. {@code lower(name) like ...} for names vs a plain
 * {@code like} on policy config values).
 */
public final class LikePatterns {

    private LikePatterns() {}

    public static boolean insensitiveLike(String value, String likePattern) {
        return matches(value, likePattern, '%', Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /** Case-sensitive SQL-LIKE ({@code %} wildcards) - plain JPA {@code like} semantics. */
    public static boolean like(String value, String likePattern) {
        return matches(value, likePattern, '%', Pattern.DOTALL);
    }

    /**
     * "Value contains the search term" matching of the authorization stores' filter options:
     * upstream JPA escapes the term's LIKE metacharacters, converts {@code *} to {@code %} and
     * wraps the result in {@code %...%} - i.e. the term's {@code *} are wildcards, everything
     * else is literal, and the term may match anywhere in the value.
     */
    public static boolean containsTerm(String value, String term, boolean caseSensitive) {
        if (value == null || term == null) {
            return false;
        }
        return matches(
                value,
                "*" + term + "*",
                '*',
                caseSensitive ? Pattern.DOTALL : Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static boolean matches(String value, String pattern, char wildcard, int flags) {
        if (value == null || pattern == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == wildcard) {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), flags).matcher(value).matches();
    }
}
