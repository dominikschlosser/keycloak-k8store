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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads CustomResourceDefinition documents from YAML files into raw {@link JsonNode} trees,
 * keyed by {@code metadata.name}. Documents of any other kind are ignored.
 */
public final class CrdLoader {

    private final YAMLMapper mapper = new YAMLMapper();

    /**
     * Loads CRDs from a single YAML file or from every {@code *.yml}/{@code *.yaml} file in a directory.
     *
     * @throws IOException if the path does not exist, contains no CRD documents, or cannot be parsed
     */
    public Map<String, JsonNode> load(File fileOrDir) throws IOException {
        List<File> files = listYamlFiles(fileOrDir);
        Map<String, JsonNode> crds = new LinkedHashMap<>();
        for (File file : files) {
            for (JsonNode document : readDocuments(file)) {
                if (!"CustomResourceDefinition".equals(document.path("kind").asText())) {
                    continue;
                }
                String name = document.path("metadata").path("name").asText();
                if (name.isEmpty()) {
                    throw new IOException("CustomResourceDefinition without metadata.name in " + file);
                }
                crds.put(name, document);
            }
        }
        if (crds.isEmpty()) {
            throw new IOException("no CustomResourceDefinition documents found in " + fileOrDir);
        }
        return crds;
    }

    /** Parses all YAML documents (multi-document files supported) in the given file. */
    public List<JsonNode> readDocuments(File file) throws IOException {
        List<JsonNode> documents = new ArrayList<>();
        try (JsonParser parser = mapper.getFactory().createParser(file);
             MappingIterator<JsonNode> iterator = mapper.readValues(parser, JsonNode.class)) {
            while (iterator.hasNext()) {
                JsonNode document = iterator.next();
                if (document != null && document.isObject()) {
                    documents.add(document);
                }
            }
        }
        return documents;
    }

    private static List<File> listYamlFiles(File fileOrDir) throws IOException {
        if (!fileOrDir.exists()) {
            throw new IOException("path does not exist: " + fileOrDir);
        }
        if (fileOrDir.isFile()) {
            return List.of(fileOrDir);
        }
        File[] entries = fileOrDir.listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (entries == null || entries.length == 0) {
            throw new IOException("no *.yml/*.yaml files found in directory: " + fileOrDir);
        }
        List<File> files = new ArrayList<>(Arrays.asList(entries));
        files.sort(Comparator.comparing(File::getName));
        return files;
    }
}
