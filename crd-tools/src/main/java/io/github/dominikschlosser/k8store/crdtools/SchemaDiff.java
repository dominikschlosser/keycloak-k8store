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
package io.github.dominikschlosser.k8store.crdtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structural diff of CustomResourceDefinition OpenAPI v3 schemas, classifying every change as
 * {@link Severity#BREAKING} or {@link Severity#COMPATIBLE} with respect to already-stored custom resources.
 *
 * <p>Operates on raw Jackson {@link JsonNode} trees (not fabric8 POJOs) so that vendor extension fields
 * such as {@code x-kubernetes-preserve-unknown-fields} are never lost.
 */
public final class SchemaDiff {

    public enum Severity {
        BREAKING,
        COMPATIBLE
    }

    /**
     * A single detected schema change.
     *
     * @param severity whether the change can break existing stored resources
     * @param crd      metadata.name of the affected CustomResourceDefinition
     * @param path     JSON-path-ish location of the change inside the CRD document
     * @param message  human-readable description of the change
     */
    public record Change(Severity severity, String crd, String path, String message) {

        public boolean isBreaking() {
            return severity == Severity.BREAKING;
        }

        @Override
        public String toString() {
            return severity + " " + crd + " " + path + ": " + message;
        }
    }

    /** Validation keywords whose addition or tightening rejects previously valid documents. */
    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "pattern",
            "format",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minLength",
            "maxLength",
            "minItems",
            "maxItems",
            "uniqueItems",
            "minProperties",
            "maxProperties");

    /**
     * Diffs two sets of CRDs matched by {@code metadata.name}.
     *
     * @param oldCrds CRD documents keyed by metadata.name (the currently deployed / previous state)
     * @param newCrds CRD documents keyed by metadata.name (the desired / regenerated state)
     */
    public List<Change> diff(Map<String, JsonNode> oldCrds, Map<String, JsonNode> newCrds) {
        List<Change> changes = new ArrayList<>();
        Set<String> allNames = new TreeSet<>();
        allNames.addAll(oldCrds.keySet());
        allNames.addAll(newCrds.keySet());
        for (String name : allNames) {
            JsonNode oldCrd = oldCrds.get(name);
            JsonNode newCrd = newCrds.get(name);
            if (oldCrd == null) {
                changes.add(new Change(Severity.COMPATIBLE, name, "", "new CRD added"));
            } else if (newCrd == null) {
                changes.add(new Change(Severity.BREAKING, name, "", "CRD removed"));
            } else {
                changes.addAll(diffCrd(name, oldCrd, newCrd));
            }
        }
        return changes;
    }

    /** Diffs a single CRD document pair, comparing all versions and their schemas. */
    public List<Change> diffCrd(String name, JsonNode oldCrd, JsonNode newCrd) {
        List<Change> changes = new ArrayList<>();
        Map<String, JsonNode> oldVersions = versionsByName(oldCrd);
        Map<String, JsonNode> newVersions = versionsByName(newCrd);

        Set<String> allVersions = new LinkedHashSet<>(oldVersions.keySet());
        allVersions.addAll(newVersions.keySet());
        for (String version : allVersions) {
            JsonNode oldVersion = oldVersions.get(version);
            JsonNode newVersion = newVersions.get(version);
            String versionPath = "spec.versions[" + version + "]";
            if (oldVersion == null) {
                changes.add(new Change(Severity.COMPATIBLE, name, versionPath, "new version added"));
                continue;
            }
            if (newVersion == null) {
                boolean served = oldVersion.path("served").asBoolean(false);
                changes.add(new Change(
                        served ? Severity.BREAKING : Severity.COMPATIBLE,
                        name,
                        versionPath,
                        served ? "served version removed" : "unserved version removed"));
                continue;
            }
            boolean oldServed = oldVersion.path("served").asBoolean(false);
            boolean newServed = newVersion.path("served").asBoolean(false);
            if (oldServed && !newServed) {
                changes.add(
                        new Change(Severity.BREAKING, name, versionPath + ".served", "version is no longer served"));
            } else if (!oldServed && newServed) {
                changes.add(new Change(Severity.COMPATIBLE, name, versionPath + ".served", "version is now served"));
            }
            boolean oldStorage = oldVersion.path("storage").asBoolean(false);
            boolean newStorage = newVersion.path("storage").asBoolean(false);
            if (oldStorage != newStorage) {
                changes.add(new Change(
                        Severity.COMPATIBLE,
                        name,
                        versionPath + ".storage",
                        "storage flag changed: " + oldStorage + " -> " + newStorage));
            }
            JsonNode oldSchema = oldVersion.path("schema").path("openAPIV3Schema");
            JsonNode newSchema = newVersion.path("schema").path("openAPIV3Schema");
            diffSchema(name, versionPath + ".schema.openAPIV3Schema", oldSchema, newSchema, changes);
        }
        return changes;
    }

    /**
     * Recursively diffs two OpenAPI v3 schema nodes, appending detected changes to {@code out}.
     * Exposed for direct unit testing.
     */
    public void diffSchema(String crd, String path, JsonNode oldSchema, JsonNode newSchema, List<Change> out) {
        if (isAbsent(oldSchema) && isAbsent(newSchema)) {
            return;
        }
        JsonNode oldNode = isAbsent(oldSchema) ? JsonNodeFactory.instance.objectNode() : oldSchema;
        JsonNode newNode = isAbsent(newSchema) ? JsonNodeFactory.instance.objectNode() : newSchema;

        boolean relaxedToPreserveUnknown = !isPreserveUnknown(oldNode) && isPreserveUnknown(newNode);

        for (String field : fieldUnion(oldNode, newNode)) {
            JsonNode oldValue = oldNode.get(field);
            JsonNode newValue = newNode.get(field);
            switch (field) {
                case "description" -> {
                    if (!equalNodes(oldValue, newValue)) {
                        out.add(new Change(Severity.COMPATIBLE, crd, path + ".description", "description changed"));
                    }
                }
                case "type" -> diffType(crd, path, oldValue, newValue, relaxedToPreserveUnknown, out);
                case "properties" -> diffProperties(crd, path, oldValue, newValue, relaxedToPreserveUnknown, out);
                case "required" -> diffRequired(crd, path, oldValue, newValue, out);
                case "enum" -> diffEnum(crd, path, oldValue, newValue, out);
                case "items" -> diffItems(crd, path, oldValue, newValue, out);
                case "additionalProperties" -> diffAdditionalProperties(crd, path, oldValue, newValue, out);
                case "nullable" -> diffNullable(crd, path, oldValue, newValue, out);
                case "x-kubernetes-preserve-unknown-fields" ->
                    diffPreserveUnknownFields(crd, path, oldValue, newValue, out);
                default -> diffOtherKeyword(crd, path, field, oldValue, newValue, out);
            }
        }
    }

    private void diffType(
            String crd,
            String path,
            JsonNode oldValue,
            JsonNode newValue,
            boolean relaxedToPreserveUnknown,
            List<Change> out) {
        if (equalNodes(oldValue, newValue)) {
            return;
        }
        if (oldValue == null) {
            out.add(new Change(
                    Severity.BREAKING, crd, path + ".type", "type added (tightens validation): " + newValue));
        } else if (newValue == null) {
            out.add(new Change(
                    relaxedToPreserveUnknown ? Severity.COMPATIBLE : Severity.BREAKING,
                    crd,
                    path + ".type",
                    relaxedToPreserveUnknown
                            ? "type " + oldValue + " removed, schema relaxed to x-kubernetes-preserve-unknown-fields"
                            : "type removed: " + oldValue));
        } else {
            out.add(new Change(Severity.BREAKING, crd, path + ".type", oldValue + " -> " + newValue));
        }
    }

    private void diffProperties(
            String crd,
            String path,
            JsonNode oldValue,
            JsonNode newValue,
            boolean relaxedToPreserveUnknown,
            List<Change> out) {
        JsonNode oldProps = oldValue == null ? JsonNodeFactory.instance.objectNode() : oldValue;
        JsonNode newProps = newValue == null ? JsonNodeFactory.instance.objectNode() : newValue;
        for (String prop : fieldUnion(oldProps, newProps)) {
            String propPath = path + ".properties." + prop;
            JsonNode oldProp = oldProps.get(prop);
            JsonNode newProp = newProps.get(prop);
            if (oldProp == null) {
                out.add(new Change(Severity.COMPATIBLE, crd, propPath, "property added"));
            } else if (newProp == null) {
                out.add(new Change(
                        relaxedToPreserveUnknown ? Severity.COMPATIBLE : Severity.BREAKING,
                        crd,
                        propPath,
                        relaxedToPreserveUnknown
                                ? "property removed, schema relaxed to x-kubernetes-preserve-unknown-fields"
                                : "property removed"));
            } else {
                diffSchema(crd, propPath, oldProp, newProp, out);
            }
        }
    }

    private void diffRequired(String crd, String path, JsonNode oldValue, JsonNode newValue, List<Change> out) {
        Set<String> oldRequired = stringSet(oldValue);
        Set<String> newRequired = stringSet(newValue);
        for (String entry : newRequired) {
            if (!oldRequired.contains(entry)) {
                out.add(new Change(Severity.BREAKING, crd, path + ".required", "\"" + entry + "\" is newly required"));
            }
        }
        for (String entry : oldRequired) {
            if (!newRequired.contains(entry)) {
                out.add(new Change(
                        Severity.COMPATIBLE, crd, path + ".required", "\"" + entry + "\" is no longer required"));
            }
        }
    }

    private void diffEnum(String crd, String path, JsonNode oldValue, JsonNode newValue, List<Change> out) {
        Set<String> oldEnum = valueSet(oldValue);
        Set<String> newEnum = valueSet(newValue);
        for (String value : oldEnum) {
            if (!newEnum.contains(value)) {
                out.add(new Change(Severity.BREAKING, crd, path + ".enum", "enum value " + value + " removed"));
            }
        }
        for (String value : newEnum) {
            if (!oldEnum.contains(value)) {
                out.add(new Change(Severity.COMPATIBLE, crd, path + ".enum", "enum value " + value + " added"));
            }
        }
    }

    private void diffItems(String crd, String path, JsonNode oldValue, JsonNode newValue, List<Change> out) {
        if (oldValue == null) {
            out.add(new Change(Severity.BREAKING, crd, path + ".items", "items schema added (tightens validation)"));
        } else if (newValue == null) {
            out.add(new Change(Severity.COMPATIBLE, crd, path + ".items", "items schema removed (relaxes validation)"));
        } else {
            diffSchema(crd, path + ".items", oldValue, newValue, out);
        }
    }

    private void diffAdditionalProperties(
            String crd, String path, JsonNode oldValue, JsonNode newValue, List<Change> out) {
        if (equalNodes(oldValue, newValue)) {
            return;
        }
        String location = path + ".additionalProperties";
        if (oldValue == null) {
            out.add(new Change(Severity.BREAKING, crd, location, "additionalProperties added"));
        } else if (newValue == null) {
            out.add(new Change(Severity.BREAKING, crd, location, "additionalProperties removed"));
        } else {
            out.add(new Change(Severity.BREAKING, crd, location, "additionalProperties shape changed"));
        }
    }

    private void diffNullable(String crd, String path, JsonNode oldValue, JsonNode newValue, List<Change> out) {
        boolean oldNullable = oldValue != null && oldValue.asBoolean(false);
        boolean newNullable = newValue != null && newValue.asBoolean(false);
        if (oldNullable == newNullable) {
            return;
        }
        out.add(new Change(
                newNullable ? Severity.COMPATIBLE : Severity.BREAKING,
                crd,
                path + ".nullable",
                newNullable ? "nullable added (relaxes validation)" : "nullable removed (tightens validation)"));
    }

    private void diffPreserveUnknownFields(
            String crd, String path, JsonNode oldValue, JsonNode newValue, List<Change> out) {
        boolean oldPreserve = oldValue != null && oldValue.asBoolean(false);
        boolean newPreserve = newValue != null && newValue.asBoolean(false);
        if (oldPreserve == newPreserve) {
            return;
        }
        String location = path + ".x-kubernetes-preserve-unknown-fields";
        if (newPreserve) {
            out.add(new Change(
                    Severity.COMPATIBLE,
                    crd,
                    location,
                    "x-kubernetes-preserve-unknown-fields added (relaxes validation)"));
        } else {
            out.add(new Change(
                    Severity.BREAKING,
                    crd,
                    location,
                    "x-kubernetes-preserve-unknown-fields removed (unknown fields will be pruned/rejected)"));
        }
    }

    private void diffOtherKeyword(
            String crd, String path, String field, JsonNode oldValue, JsonNode newValue, List<Change> out) {
        if (equalNodes(oldValue, newValue)) {
            return;
        }
        String location = path + "." + field;
        if (field.startsWith("x-kubernetes-")) {
            out.add(new Change(Severity.COMPATIBLE, crd, location, describeChange(field, oldValue, newValue)));
            return;
        }
        if (CONSTRAINT_KEYWORDS.contains(field)) {
            if (newValue == null) {
                out.add(new Change(Severity.COMPATIBLE, crd, location, "constraint removed (relaxes validation)"));
            } else {
                out.add(new Change(
                        Severity.BREAKING,
                        crd,
                        location,
                        oldValue == null
                                ? "constraint added (tightens validation): " + newValue
                                : "constraint changed: " + oldValue + " -> " + newValue));
            }
            return;
        }
        out.add(new Change(Severity.COMPATIBLE, crd, location, describeChange(field, oldValue, newValue)));
    }

    private static String describeChange(String field, JsonNode oldValue, JsonNode newValue) {
        if (oldValue == null) {
            return field + " added: " + newValue;
        }
        if (newValue == null) {
            return field + " removed (was " + oldValue + ")";
        }
        return field + " changed: " + oldValue + " -> " + newValue;
    }

    private static Map<String, JsonNode> versionsByName(JsonNode crd) {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode version : crd.path("spec").path("versions")) {
            String name = version.path("name").asText();
            if (!name.isEmpty()) {
                byName.put(name, version);
            }
        }
        return byName;
    }

    private static boolean isPreserveUnknown(JsonNode schema) {
        return schema.path("x-kubernetes-preserve-unknown-fields").asBoolean(false);
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static boolean equalNodes(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(b);
    }

    private static Set<String> fieldUnion(JsonNode a, JsonNode b) {
        Set<String> fields = new LinkedHashSet<>();
        for (Iterator<String> it = a.fieldNames(); it.hasNext(); ) {
            fields.add(it.next());
        }
        for (Iterator<String> it = b.fieldNames(); it.hasNext(); ) {
            fields.add(it.next());
        }
        return fields;
    }

    private static Set<String> stringSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        if (array != null && array.isArray()) {
            for (JsonNode entry : array) {
                values.add(entry.asText());
            }
        }
        return values;
    }

    private static Set<String> valueSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        if (array != null && array.isArray()) {
            for (JsonNode entry : array) {
                values.add(entry.toString());
            }
        }
        return values;
    }
}
