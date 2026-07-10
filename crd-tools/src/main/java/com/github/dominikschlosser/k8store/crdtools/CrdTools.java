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
package com.github.dominikschlosser.k8store.crdtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dominikschlosser.k8store.crdtools.SchemaDiff.Change;
import com.github.dominikschlosser.k8store.crdtools.SchemaDiff.Severity;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.WritableOperation;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI for keycloak-k8store CRD lifecycle management across Keycloak upgrades:
 * diff regenerated CRD schemas against a previous state or a running cluster, classify
 * changes as breaking vs. compatible, and roll out CRD updates via server-side apply.
 */
public final class CrdTools {

    static final String FIELD_MANAGER = "keycloak-k8store-crd-tools";
    static final String DEFAULT_CRD_DIR = "crds";

    static final int EXIT_OK = 0;
    static final int EXIT_BREAKING = 1;
    static final int EXIT_ERROR = 2;

    private static final List<String> BREAKING_GUIDANCE = List.of(
            "Breaking CRD schema changes detected. To roll them out without downtime:",
            "  1. Do not change the existing version in place: bump the CRD version instead (e.g. v1alpha1 -> v1alpha2).",
            "  2. Serve both versions (served: true on old and new), keeping storage: true on the old version.",
            "  3. Migrate existing custom resources to the new version (read and re-write every CR).",
            "  4. Flip storage: true to the new version, set the old version to served: false, then drop it",
            "     from status.storedVersions once no stored objects remain at the old version.");

    private final PrintStream out;
    private final PrintStream err;
    private final SchemaDiff schemaDiff = new SchemaDiff();
    private final CrdLoader loader = new CrdLoader();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public CrdTools(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new CrdTools(System.out, System.err).run(args));
    }

    int run(String[] args) {
        List<String> positionals = new ArrayList<>();
        boolean json = false;
        boolean dryRun = false;
        boolean force = false;
        for (String arg : args) {
            switch (arg) {
                case "--json" -> json = true;
                case "--dry-run" -> dryRun = true;
                case "--force" -> force = true;
                case "-h", "--help" -> {
                    printUsage(out);
                    return EXIT_OK;
                }
                default -> {
                    if (arg.startsWith("--")) {
                        err.println("Unknown option: " + arg);
                        printUsage(err);
                        return EXIT_ERROR;
                    }
                    positionals.add(arg);
                }
            }
        }
        if (positionals.isEmpty()) {
            printUsage(err);
            return EXIT_ERROR;
        }
        String command = positionals.remove(0);
        try {
            return switch (command) {
                case "diff" -> diff(positionals, json);
                case "check-cluster" -> checkCluster(positionals, json);
                case "apply" -> apply(positionals, dryRun, force);
                default -> {
                    err.println("Unknown command: " + command);
                    printUsage(err);
                    yield EXIT_ERROR;
                }
            };
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_ERROR;
        } catch (RuntimeException e) {
            err.println("Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return EXIT_ERROR;
        }
    }

    private int diff(List<String> positionals, boolean json) throws IOException {
        if (positionals.size() != 2) {
            err.println("Usage: crd-tools diff <oldFileOrDir> <newFileOrDir> [--json]");
            return EXIT_ERROR;
        }
        Map<String, JsonNode> oldCrds = loader.load(new File(positionals.get(0)));
        Map<String, JsonNode> newCrds = loader.load(new File(positionals.get(1)));
        List<Change> changes = schemaDiff.diff(oldCrds, newCrds);
        return report(changes, json);
    }

    private int checkCluster(List<String> positionals, boolean json) throws IOException {
        if (positionals.size() > 1) {
            err.println("Usage: crd-tools check-cluster [<localDir>] [--json]");
            return EXIT_ERROR;
        }
        File localDir = new File(positionals.isEmpty() ? DEFAULT_CRD_DIR : positionals.get(0));
        Map<String, JsonNode> localCrds = loader.load(localDir);
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<Change> changes = diffAgainstCluster(client, localCrds);
            return report(changes, json);
        }
    }

    private int apply(List<String> positionals, boolean dryRun, boolean force) throws IOException {
        if (positionals.size() > 1) {
            err.println("Usage: crd-tools apply [<localDir>] [--dry-run] [--force]");
            return EXIT_ERROR;
        }
        File localDir = new File(positionals.isEmpty() ? DEFAULT_CRD_DIR : positionals.get(0));
        Map<String, JsonNode> localCrds = loader.load(localDir);
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<Change> changes = diffAgainstCluster(client, localCrds);
            printHumanReport(changes);
            boolean breaking = changes.stream().anyMatch(Change::isBreaking);
            if (breaking) {
                out.println();
                BREAKING_GUIDANCE.forEach(out::println);
                if (!force) {
                    out.println();
                    out.println("Refusing to apply. Re-run with --force to apply anyway.");
                    return EXIT_BREAKING;
                }
                out.println();
                out.println("--force given: applying despite breaking changes.");
            }
            for (JsonNode node : localCrds.values()) {
                CustomResourceDefinition crd =
                        client.getKubernetesSerialization().convertValue(node, CustomResourceDefinition.class);
                Resource<CustomResourceDefinition> resource = client.resource(crd);
                WritableOperation<CustomResourceDefinition> operation = dryRun ? resource.dryRun() : resource;
                CustomResourceDefinition applied =
                        operation.fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply();
                out.println("Applied " + applied.getMetadata().getName() + " (server-side apply"
                        + (dryRun ? ", dry-run" : "") + ")");
            }
            return EXIT_OK;
        }
    }

    /**
     * Diffs local CRDs against the state in the connected cluster (cluster = old, local = new).
     * CRDs missing in the cluster are reported as compatible "not installed" changes.
     */
    List<Change> diffAgainstCluster(KubernetesClient client, Map<String, JsonNode> localCrds) {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : localCrds.entrySet()) {
            String name = entry.getKey();
            CustomResourceDefinition clusterCrd = client.apiextensions()
                    .v1()
                    .customResourceDefinitions()
                    .withName(name)
                    .get();
            if (clusterCrd == null) {
                changes.add(new Change(Severity.COMPATIBLE, name, "", "not installed in cluster (will be created)"));
                continue;
            }
            JsonNode clusterNode = client.getKubernetesSerialization().convertValue(clusterCrd, JsonNode.class);
            changes.addAll(schemaDiff.diffCrd(name, clusterNode, entry.getValue()));
        }
        return changes;
    }

    private int report(List<Change> changes, boolean json) {
        boolean breaking = changes.stream().anyMatch(Change::isBreaking);
        if (json) {
            printJsonReport(changes, breaking);
        } else {
            printHumanReport(changes);
            if (breaking) {
                out.println();
                BREAKING_GUIDANCE.forEach(out::println);
            }
        }
        return breaking ? EXIT_BREAKING : EXIT_OK;
    }

    private void printHumanReport(List<Change> changes) {
        if (changes.isEmpty()) {
            out.println("No CRD schema changes detected.");
            return;
        }
        Map<String, List<Change>> byCrd = new LinkedHashMap<>();
        for (Change change : changes) {
            byCrd.computeIfAbsent(change.crd(), k -> new ArrayList<>()).add(change);
        }
        for (Map.Entry<String, List<Change>> entry : byCrd.entrySet()) {
            out.println("CRD " + entry.getKey());
            for (Change change : entry.getValue()) {
                String location = change.path().isEmpty() ? "" : change.path() + ": ";
                out.printf("  %-11s %s%s%n", change.severity(), location, change.message());
            }
        }
        long breakingCount = changes.stream().filter(Change::isBreaking).count();
        out.println();
        out.println("Summary: " + breakingCount + " breaking, " + (changes.size() - breakingCount)
                + " compatible change(s)");
    }

    private void printJsonReport(List<Change> changes, boolean breaking) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("breaking", breaking);
        root.put("breakingCount", changes.stream().filter(Change::isBreaking).count());
        root.put(
                "compatibleCount", changes.stream().filter(c -> !c.isBreaking()).count());
        ArrayNode changeArray = root.putArray("changes");
        for (Change change : changes) {
            ObjectNode node = changeArray.addObject();
            node.put("severity", change.severity().name());
            node.put("crd", change.crd());
            node.put("path", change.path());
            node.put("message", change.message());
        }
        if (breaking) {
            ArrayNode guidance = root.putArray("guidance");
            BREAKING_GUIDANCE.forEach(guidance::add);
        }
        out.println(root.toPrettyString());
    }

    private void printUsage(PrintStream stream) {
        stream.println("""
                keycloak-k8store CRD tools

                Usage:
                  crd-tools diff <oldFileOrDir> <newFileOrDir> [--json]
                      Diff two sets of CRD YAMLs (matched by metadata.name) and classify
                      each schema change as BREAKING or COMPATIBLE.

                  crd-tools check-cluster [<localDir>] [--json]
                      Diff the CRDs installed in the current kubeconfig cluster (old)
                      against the local CRD files (new). Default localDir: crds/

                  crd-tools apply [<localDir>] [--dry-run] [--force]
                      Server-side apply the local CRDs to the cluster (field manager
                      '%s'). Refuses on breaking changes unless --force.
                      --dry-run uses Kubernetes server-side dry-run. Default localDir: crds/

                Exit codes: 0 = no or only compatible changes, 1 = breaking changes, 2 = usage/IO error
                """.formatted(FIELD_MANAGER));
    }
}
