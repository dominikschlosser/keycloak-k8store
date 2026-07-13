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
package com.github.dominikschlosser.k8store.kubernetes.references;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.jboss.logging.Logger;

/**
 * Resolves reference placeholders embedded in custom-resource string values on the read path, so a
 * CR (and the git repository backing it) only ever holds a reference while Keycloak sees the real
 * value.
 *
 * <p>Only two explicitly prefixed forms are recognized, which keeps ordinary values (JavaScript
 * policy code, regular expressions, {@code ${...}} tokens Keycloak itself interprets) untouched:
 *
 * <ul>
 *   <li>{@code ${env:NAME}} - the pod environment variable {@code NAME}.
 *   <li>{@code ${env:NAME:-default}} - {@code NAME}, or {@code default} when it is unset or empty.
 *   <li>{@code ${secret:secret-name:key}} - key {@code key} of the Kubernetes Secret
 *       {@code secret-name} in the watched namespace.
 * </ul>
 *
 * <p>{@code $$} escapes to a literal {@code $} (so a literal {@code ${} can be written
 * {@code $${}). Any other {@code ${...}} is passed through verbatim. A placeholder may appear
 * anywhere inside a larger string and any number of times.
 *
 * <p>A reference that cannot be resolved (missing Secret/key, or an unset environment variable with
 * no default) is left in place verbatim - the feature fails open, visibly - and reported once per
 * distinct placeholder through a bounded rate-limiter so hot-path reads do not spam the log.
 *
 * <p>This class is pure logic over its two lookup functions and holds no Kubernetes state.
 */
public final class PlaceholderResolver {

    private static final Logger LOG = Logger.getLogger(PlaceholderResolver.class);

    /** Upper bound on the set of already-warned placeholders, so an adversarial CR cannot grow it. */
    private static final int WARN_CACHE_LIMIT = 2048;

    /** Reads a key of a Kubernetes Secret; returns {@code null} when the Secret or key is absent. */
    @FunctionalInterface
    public interface SecretSource {
        String value(String secretName, String key);
    }

    private final UnaryOperator<String> env;
    private final SecretSource secrets;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * @param env     environment lookup ({@code System::getenv} in production; a fixed map in tests)
     * @param secrets Kubernetes Secret lookup
     */
    public PlaceholderResolver(UnaryOperator<String> env, SecretSource secrets) {
        this.env = env;
        this.secrets = secrets;
    }

    /**
     * Rewrites every textual node in {@code node} (recursively, through objects and arrays) by
     * resolving its placeholders. Mutates and returns the same tree.
     */
    public JsonNode resolveTree(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child instanceof TextNode) {
                    String resolved = resolve(child.asText());
                    if (!resolved.equals(child.asText())) {
                        object.set(name, TextNode.valueOf(resolved));
                    }
                } else if (child.isContainerNode()) {
                    resolveTree(child);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child instanceof TextNode) {
                    String resolved = resolve(child.asText());
                    if (!resolved.equals(child.asText())) {
                        array.set(i, TextNode.valueOf(resolved));
                    }
                } else if (child.isContainerNode()) {
                    resolveTree(child);
                }
            }
        }
        return node;
    }

    /** Resolves every recognized placeholder in {@code value}; a cheap no-op when it holds no {@code $}. */
    public String resolve(String value) {
        if (value == null || value.indexOf('$') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        int n = value.length();
        while (i < n) {
            char c = value.charAt(i);
            if (c != '$' || i + 1 >= n) {
                out.append(c);
                i++;
                continue;
            }
            char next = value.charAt(i + 1);
            if (next == '$') {
                out.append('$');
                i += 2;
                continue;
            }
            if (next != '{') {
                out.append(c);
                i++;
                continue;
            }
            int end = value.indexOf('}', i + 2);
            if (end < 0) {
                // an unterminated "${" - nothing more to interpret, copy the rest verbatim
                out.append(value, i, n);
                break;
            }
            String body = value.substring(i + 2, end);
            String whole = value.substring(i, end + 1);
            out.append(resolvePlaceholder(body, whole));
            i = end + 1;
        }
        return out.toString();
    }

    /**
     * Resolves a single {@code ${body}} occurrence. {@code whole} is the exact source text
     * (including the delimiters), returned verbatim for anything not recognized or not resolvable.
     */
    private String resolvePlaceholder(String body, String whole) {
        if (body.startsWith("env:")) {
            return resolveEnv(body.substring("env:".length()), whole);
        }
        if (body.startsWith("secret:")) {
            return resolveSecret(body.substring("secret:".length()), whole);
        }
        // not one of our prefixes: leave untouched (e.g. a Keycloak-interpreted token)
        return whole;
    }

    private String resolveEnv(String spec, String whole) {
        String name = spec;
        String defaultValue = null;
        int sep = spec.indexOf(":-");
        if (sep >= 0) {
            name = spec.substring(0, sep);
            defaultValue = spec.substring(sep + 2);
        }
        if (name.isEmpty()) {
            return unresolved(whole, "empty environment variable name");
        }
        String value = env.apply(name);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        return unresolved(whole, "environment variable '" + name + "' is not set");
    }

    private String resolveSecret(String spec, String whole) {
        int sep = spec.indexOf(':');
        if (sep < 0 || sep == 0 || sep == spec.length() - 1) {
            return unresolved(whole, "malformed secret reference, expected ${secret:name:key}");
        }
        String name = spec.substring(0, sep);
        String key = spec.substring(sep + 1);
        String value = secrets.value(name, key);
        if (value != null) {
            return value;
        }
        return unresolved(whole, "secret '" + name + "' has no key '" + key + "' in the watched namespace");
    }

    /** Leaves the placeholder verbatim and warns once per distinct placeholder. */
    private String unresolved(String whole, String reason) {
        if (warned.size() < WARN_CACHE_LIMIT && warned.add(whole)) {
            LOG.warnv(
                    "k8store: could not resolve reference {0} ({1}); leaving it unresolved in the served value",
                    whole, reason);
        }
        return whole;
    }
}
