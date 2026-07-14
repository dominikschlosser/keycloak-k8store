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
package io.github.dominikschlosser.k8store.kubernetes.references;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Resolves the {@code valuesFrom} references of a custom-resource spec on the read path, so a CR
 * (and the git repository backing it) only ever holds a placeholder while Keycloak sees the real
 * value.
 *
 * <p>Each {@code valuesFrom} entry declares a {@code targetPath} and a {@code valueFrom} source (a
 * Secret key, a ConfigMap key or an inline literal). The value is injected where a {@code ${...}}
 * placeholder sits inside the string at {@code targetPath}. Nothing else is touched: a
 * {@code ${...}} that no entry points at (Keycloak's own localization keys, policy expressions) is
 * left verbatim, and a Secret or ConfigMap the CR did not declare cannot be pulled in. This is the
 * same shape the Grafana operator uses for its datasources.
 *
 * <p>The placeholder name that a Secret/ConfigMap value replaces is the referenced {@code key}, so
 * {@code ${DB_PASSWORD}} pairs with {@code secretKeyRef.key: DB_PASSWORD}. A literal value replaces
 * the single {@code ${...}} placeholder found at its {@code targetPath}.
 *
 * <p>{@link #resolveTree} fails open and visibly: a reference that cannot be resolved (missing
 * Secret/ConfigMap/key, a {@code targetPath} that does not point at a matching placeholder string)
 * is left in place and reported once per distinct problem through a bounded rate-limiter.
 * {@link #validate} runs the same checks without mutating and returns the problems, so a caller can
 * fail the boot instead of serving an unresolved reference.
 *
 * <p>This class is pure logic over its two lookup functions and holds no Kubernetes state.
 */
public final class ValueReferenceResolver {

    private static final Logger LOG = Logger.getLogger(ValueReferenceResolver.class);

    /** Upper bound on the set of already-warned problems, so an adversarial CR cannot grow it. */
    private static final int WARN_CACHE_LIMIT = 2048;

    /** Reads a key of a Kubernetes Secret or ConfigMap; {@code null} when the object or key is absent. */
    @FunctionalInterface
    public interface KeyValueSource {
        String value(String name, String key);
    }

    private final KeyValueSource secrets;
    private final KeyValueSource configMaps;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ValueReferenceResolver(KeyValueSource secrets, KeyValueSource configMaps) {
        this.secrets = secrets;
        this.configMaps = configMaps;
    }

    /**
     * Applies every {@code valuesFrom} entry of {@code node} to the tree it belongs to. Mutates and
     * returns the same tree. A non-object node, or one without a {@code valuesFrom} array, is
     * returned untouched. Unresolvable entries are left verbatim and warned.
     */
    public JsonNode resolveTree(JsonNode node) {
        if (!(node instanceof ObjectNode root)) {
            return node;
        }
        JsonNode valuesFrom = root.get("valuesFrom");
        if (!(valuesFrom instanceof ArrayNode entries)) {
            return node;
        }
        for (JsonNode entry : entries) {
            try {
                applyEntry(root, entry, true);
            } catch (ReferenceProblem problem) {
                warn(problem.getMessage());
            }
        }
        return node;
    }

    /**
     * Runs the same checks as {@link #resolveTree} without mutating and returns one message per
     * invalid entry. An empty list means every reference resolves.
     */
    public List<String> validate(JsonNode node) {
        List<String> problems = new ArrayList<>();
        if (!(node instanceof ObjectNode root)) {
            return problems;
        }
        JsonNode valuesFrom = root.get("valuesFrom");
        if (!(valuesFrom instanceof ArrayNode entries)) {
            return problems;
        }
        for (JsonNode entry : entries) {
            try {
                applyEntry(root, entry, false);
            } catch (ReferenceProblem problem) {
                problems.add(problem.getMessage());
            }
        }
        return problems;
    }

    /**
     * Checks one {@code valuesFrom} entry against {@code root} and, when {@code apply} is true,
     * writes the resolved value into the tree. Throws {@link ReferenceProblem} on any issue.
     */
    private void applyEntry(ObjectNode root, JsonNode entry, boolean apply) throws ReferenceProblem {
        String targetPath = text(entry, "targetPath");
        JsonNode valueFrom = entry.get("valueFrom");
        if (targetPath == null || targetPath.isBlank()) {
            throw new ReferenceProblem("a valuesFrom entry is missing targetPath");
        }
        if (valueFrom == null) {
            throw new ReferenceProblem("valuesFrom targetPath '" + targetPath + "' is missing valueFrom");
        }

        Resolved resolved = resolveSource(targetPath, valueFrom);

        List<Object> path = parsePath(targetPath);
        if (path == null || path.isEmpty()) {
            throw new ReferenceProblem("valuesFrom targetPath '" + targetPath + "' is malformed");
        }
        JsonNode container = root;
        for (int i = 0; i < path.size() - 1; i++) {
            container = child(container, path.get(i));
            if (container == null) {
                throw new ReferenceProblem("valuesFrom targetPath '" + targetPath + "' does not exist in the resource");
            }
        }
        Object last = path.get(path.size() - 1);
        JsonNode leaf = child(container, last);
        if (!(leaf instanceof TextNode)) {
            throw new ReferenceProblem("valuesFrom targetPath '" + targetPath + "' does not point at a string value");
        }

        String replaced = inject(leaf.asText(), resolved, targetPath);
        if (apply) {
            setChild(container, last, TextNode.valueOf(replaced));
        }
    }

    /** Resolves the {@code valueFrom} source to a value plus the placeholder name it replaces. */
    private Resolved resolveSource(String targetPath, JsonNode valueFrom) throws ReferenceProblem {
        JsonNode secretRef = valueFrom.get("secretKeyRef");
        if (secretRef != null) {
            return fromKeyRef(targetPath, secretRef, secrets, "secret");
        }
        JsonNode configMapRef = valueFrom.get("configMapKeyRef");
        if (configMapRef != null) {
            return fromKeyRef(targetPath, configMapRef, configMaps, "configmap");
        }
        JsonNode literal = valueFrom.get("value");
        if (literal != null && literal.isTextual()) {
            return new Resolved(literal.asText(), null);
        }
        throw new ReferenceProblem("valuesFrom targetPath '" + targetPath
                + "' needs a secretKeyRef, configMapKeyRef or value in valueFrom");
    }

    private Resolved fromKeyRef(String targetPath, JsonNode ref, KeyValueSource source, String kind)
            throws ReferenceProblem {
        String name = text(ref, "name");
        String key = text(ref, "key");
        if (name == null || key == null) {
            throw new ReferenceProblem(
                    "valuesFrom targetPath '" + targetPath + "' " + kind + " reference needs name and key");
        }
        String value = source.value(name, key);
        if (value == null) {
            throw new ReferenceProblem("valuesFrom targetPath '" + targetPath + "' references " + kind + " '" + name
                    + "' key '" + key + "', which is absent in the watched namespace");
        }
        return new Resolved(value, key);
    }

    /**
     * Substitutes the placeholder inside {@code current}. A Secret or ConfigMap source uses the
     * placeholder {@code ${key}}. A literal uses the single {@code ${...}} at the path. Throws when
     * the expected placeholder is not present.
     */
    private String inject(String current, Resolved resolved, String targetPath) throws ReferenceProblem {
        if (resolved.placeholderName != null) {
            String token = "${" + resolved.placeholderName + "}";
            if (!current.contains(token)) {
                throw new ReferenceProblem(
                        "valuesFrom targetPath '" + targetPath + "' string does not contain the placeholder " + token);
            }
            return current.replace(token, resolved.value);
        }
        int start = current.indexOf("${");
        int end = start < 0 ? -1 : current.indexOf('}', start + 2);
        if (end < 0) {
            throw new ReferenceProblem("valuesFrom targetPath '" + targetPath
                    + "' string does not contain a ${...} placeholder for the literal value");
        }
        return current.substring(0, start) + resolved.value + current.substring(end + 1);
    }

    /**
     * Splits a targetPath into segments. Dots separate fields. {@code [n]} indexes an array.
     * {@code [key]} indexes an object field whose name may contain dots. Returns {@code null} on an
     * unterminated bracket.
     */
    static List<Object> parsePath(String path) {
        List<Object> segments = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int i = 0;
        int n = path.length();
        while (i < n) {
            char c = path.charAt(i);
            if (c == '.') {
                flush(field, segments);
                i++;
            } else if (c == '[') {
                flush(field, segments);
                int end = path.indexOf(']', i + 1);
                if (end < 0) {
                    return null;
                }
                String inner = path.substring(i + 1, end);
                if (!inner.isEmpty() && inner.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                    segments.add(Integer.parseInt(inner));
                } else {
                    segments.add(inner);
                }
                i = end + 1;
            } else {
                field.append(c);
                i++;
            }
        }
        flush(field, segments);
        return segments;
    }

    private static void flush(StringBuilder field, List<Object> segments) {
        if (field.length() > 0) {
            segments.add(field.toString());
            field.setLength(0);
        }
    }

    private static JsonNode child(JsonNode container, Object segment) {
        if (segment instanceof Integer index && container instanceof ArrayNode array) {
            return index >= 0 && index < array.size() ? array.get(index) : null;
        }
        if (segment instanceof String field && container instanceof ObjectNode object) {
            return object.get(field);
        }
        return null;
    }

    private static void setChild(JsonNode container, Object segment, JsonNode value) {
        if (segment instanceof Integer index && container instanceof ArrayNode array) {
            array.set(index, value);
        } else if (segment instanceof String field && container instanceof ObjectNode object) {
            object.set(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** Warns once per distinct message. */
    private void warn(String message) {
        if (warned.size() < WARN_CACHE_LIMIT && warned.add(message)) {
            LOG.warnv("k8store: {0}; leaving the reference unresolved in the served value", message);
        }
    }

    private record Resolved(String value, String placeholderName) {}

    /** An invalid or unresolvable {@code valuesFrom} entry. Carries a human-readable message. */
    private static final class ReferenceProblem extends Exception {
        ReferenceProblem(String message) {
            super(message);
        }
    }
}
