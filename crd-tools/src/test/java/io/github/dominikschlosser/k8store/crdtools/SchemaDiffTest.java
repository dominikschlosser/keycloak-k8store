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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.dominikschlosser.k8store.crdtools.SchemaDiff.Change;
import io.github.dominikschlosser.k8store.crdtools.SchemaDiff.Severity;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaDiffTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    private final SchemaDiff diff = new SchemaDiff();

    private static JsonNode yaml(String content) {
        try {
            return YAML.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private List<Change> diffSchemas(String oldYaml, String newYaml) {
        List<Change> changes = new ArrayList<>();
        diff.diffSchema("test-crd", "spec", yaml(oldYaml), yaml(newYaml), changes);
        return changes;
    }

    @Test
    void identicalSchemasProduceNoChanges() {
        String schema = """
                type: object
                properties:
                  name:
                    type: string
                  attributes:
                    x-kubernetes-preserve-unknown-fields: true
                """;
        assertEquals(List.of(), diffSchemas(schema, schema));
    }

    @Test
    void addedPropertyIsCompatible() {
        List<Change> changes = diffSchemas("""
                type: object
                properties:
                  name:
                    type: string
                """, """
                type: object
                properties:
                  name:
                    type: string
                  description:
                    type: string
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.COMPATIBLE, changes.get(0).severity());
        assertEquals("spec.properties.description", changes.get(0).path());
        assertEquals("property added", changes.get(0).message());
    }

    @Test
    void removedPropertyIsBreaking() {
        List<Change> changes = diffSchemas("""
                type: object
                properties:
                  name:
                    type: string
                  legacy:
                    type: string
                """, """
                type: object
                properties:
                  name:
                    type: string
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.BREAKING, changes.get(0).severity());
        assertEquals("spec.properties.legacy", changes.get(0).path());
        assertEquals("property removed", changes.get(0).message());
    }

    @Test
    void typeChangeIsBreaking() {
        List<Change> changes = diffSchemas("""
                properties:
                  attributes:
                    type: object
                """, """
                properties:
                  attributes:
                    type: string
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.BREAKING, changes.get(0).severity());
        assertEquals("spec.properties.attributes.type", changes.get(0).path());
        assertEquals("\"object\" -> \"string\"", changes.get(0).message());
    }

    @Test
    void newRequiredEntryIsBreaking() {
        List<Change> changes = diffSchemas("""
                type: object
                required: [name]
                """, """
                type: object
                required: [name, realmId]
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.BREAKING, changes.get(0).severity());
        assertEquals("spec.required", changes.get(0).path());
        assertEquals("\"realmId\" is newly required", changes.get(0).message());
    }

    @Test
    void removedRequiredEntryIsCompatible() {
        List<Change> changes = diffSchemas("""
                type: object
                required: [name, realmId]
                """, """
                type: object
                required: [name]
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.COMPATIBLE, changes.get(0).severity());
    }

    @Test
    void addedEnumValueIsCompatible() {
        List<Change> changes = diffSchemas("""
                type: string
                enum: [a, b]
                """, """
                type: string
                enum: [a, b, c]
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.COMPATIBLE, changes.get(0).severity());
        assertEquals("spec.enum", changes.get(0).path());
    }

    @Test
    void removedEnumValueIsBreaking() {
        List<Change> changes = diffSchemas("""
                type: string
                enum: [a, b]
                """, """
                type: string
                enum: [a]
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.BREAKING, changes.get(0).severity());
        assertEquals("enum value \"b\" removed", changes.get(0).message());
    }

    @Test
    void descriptionOnlyChangeIsCompatible() {
        List<Change> changes = diffSchemas("""
                type: string
                description: old text
                """, """
                type: string
                description: new text
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.COMPATIBLE, changes.get(0).severity());
        assertEquals("spec.description", changes.get(0).path());
    }

    @Test
    void removedPreserveUnknownFieldsIsBreaking() {
        List<Change> changes = diffSchemas("""
                x-kubernetes-preserve-unknown-fields: true
                """, """
                type: object
                """);
        assertTrue(changes.stream()
                .anyMatch(c -> c.isBreaking() && c.path().equals("spec.x-kubernetes-preserve-unknown-fields")));
    }

    @Test
    void addedPreserveUnknownFieldsReplacingTypedSchemaIsCompatible() {
        List<Change> changes = diffSchemas("""
                type: object
                properties:
                  key:
                    type: string
                """, """
                x-kubernetes-preserve-unknown-fields: true
                """);
        assertTrue(
                changes.stream().noneMatch(Change::isBreaking),
                "relaxing a typed schema to preserve-unknown-fields must be compatible, got: " + changes);
    }

    @Test
    void changedAdditionalPropertiesShapeIsBreaking() {
        List<Change> changes = diffSchemas("""
                type: object
                additionalProperties:
                  type: string
                """, """
                type: object
                additionalProperties:
                  type: integer
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.BREAKING, changes.get(0).severity());
        assertEquals("spec.additionalProperties", changes.get(0).path());
    }

    @Test
    void addedConstraintIsBreakingAndRemovedConstraintIsCompatible() {
        List<Change> tightened = diffSchemas("type: string", """
                type: string
                pattern: "^[a-z]+$"
                """);
        assertEquals(1, tightened.size());
        assertEquals(Severity.BREAKING, tightened.get(0).severity());

        List<Change> relaxed = diffSchemas("""
                type: string
                maxLength: 10
                """, "type: string");
        assertEquals(1, relaxed.size());
        assertEquals(Severity.COMPATIBLE, relaxed.get(0).severity());
    }

    @Test
    void nestedItemsTypeChangeIsBreakingWithItemsPath() {
        List<Change> changes = diffSchemas("""
                type: array
                items:
                  type: string
                """, """
                type: array
                items:
                  type: integer
                """);
        assertEquals(1, changes.size());
        assertEquals(Severity.BREAKING, changes.get(0).severity());
        assertEquals("spec.items.type", changes.get(0).path());
    }

    private static JsonNode crdWithVersions(String versionsYaml) {
        return yaml("""
                apiVersion: apiextensions.k8s.io/v1
                kind: CustomResourceDefinition
                metadata:
                  name: keycloaktests.k8store.dominikschlosser.github.io
                spec:
                  group: k8store.dominikschlosser.github.io
                  versions:
                """ + versionsYaml.indent(4));
    }

    @Test
    void removedServedVersionIsBreaking() {
        JsonNode oldCrd = crdWithVersions("""
                - name: v1alpha1
                  served: true
                  storage: true
                  schema:
                    openAPIV3Schema:
                      type: object
                - name: v1beta1
                  served: true
                  storage: false
                  schema:
                    openAPIV3Schema:
                      type: object
                """);
        JsonNode newCrd = crdWithVersions("""
                - name: v1alpha1
                  served: true
                  storage: true
                  schema:
                    openAPIV3Schema:
                      type: object
                """);
        List<Change> changes = diff.diffCrd("keycloaktests.k8store.dominikschlosser.github.io", oldCrd, newCrd);
        assertEquals(1, changes.size());
        assertEquals(Severity.BREAKING, changes.get(0).severity());
        assertEquals("spec.versions[v1beta1]", changes.get(0).path());
        assertEquals("served version removed", changes.get(0).message());
    }

    @Test
    void addedVersionIsCompatible() {
        JsonNode oldCrd = crdWithVersions("""
                - name: v1alpha1
                  served: true
                  storage: true
                  schema:
                    openAPIV3Schema:
                      type: object
                """);
        JsonNode newCrd = crdWithVersions("""
                - name: v1alpha1
                  served: true
                  storage: true
                  schema:
                    openAPIV3Schema:
                      type: object
                - name: v1alpha2
                  served: true
                  storage: false
                  schema:
                    openAPIV3Schema:
                      type: object
                """);
        List<Change> changes = diff.diffCrd("keycloaktests.k8store.dominikschlosser.github.io", oldCrd, newCrd);
        assertEquals(1, changes.size());
        assertEquals(Severity.COMPATIBLE, changes.get(0).severity());
        assertEquals("spec.versions[v1alpha2]", changes.get(0).path());
        assertEquals("new version added", changes.get(0).message());
    }

    @Test
    void crdPresentOnlyInNewIsCompatibleAndOnlyInOldIsBreaking() {
        JsonNode crd = crdWithVersions("""
                - name: v1alpha1
                  served: true
                  storage: true
                  schema:
                    openAPIV3Schema:
                      type: object
                """);
        List<Change> added = diff.diff(Map.of(), Map.of("keycloaktests.k8store.dominikschlosser.github.io", crd));
        assertEquals(1, added.size());
        assertEquals(Severity.COMPATIBLE, added.get(0).severity());
        assertEquals("new CRD added", added.get(0).message());

        List<Change> removed = diff.diff(Map.of("keycloaktests.k8store.dominikschlosser.github.io", crd), Map.of());
        assertEquals(1, removed.size());
        assertEquals(Severity.BREAKING, removed.get(0).severity());
        assertEquals("CRD removed", removed.get(0).message());
    }

    @Test
    void realCrdsDirDiffedAgainstItselfHasNoChanges() throws IOException {
        File crdsDir = new File("../crds");
        assumeTrue(crdsDir.isDirectory(), "crds/ directory not present, skipping");
        CrdLoader loader = new CrdLoader();
        Map<String, JsonNode> crds = loader.load(crdsDir);
        assumeTrue(!crds.isEmpty(), "no CRDs found in crds/, skipping");
        List<Change> changes = diff.diff(crds, loader.load(crdsDir));
        assertEquals(List.of(), changes);
    }
}
