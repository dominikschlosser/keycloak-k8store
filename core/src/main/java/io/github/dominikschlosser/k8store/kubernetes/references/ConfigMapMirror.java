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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Informer-backed mirror of the Kubernetes ConfigMaps in one namespace, feeding
 * {@link ValueReferenceResolver}'s {@code configMapKeyRef} lookups without an API-server round trip
 * on the read hot path. The counterpart of {@link SecretMirror} for non-secret configuration.
 *
 * <p>Only the configured write namespace is watched, even when the CRDs are watched cluster-wide. A
 * {@code configMapKeyRef} carries no namespace, so referenced ConfigMaps must live alongside the
 * datastore in its namespace.
 */
public final class ConfigMapMirror implements ValueReferenceResolver.KeyValueSource, AutoCloseable {

    private final KubernetesClient client;
    private final String namespace;

    /** configMapName -> (key -> value). */
    private final Map<String, Map<String, String>> byName = new ConcurrentHashMap<>();

    private SharedIndexInformer<ConfigMap> informer;

    public ConfigMapMirror(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /** Starts the ConfigMap informer and returns it so the caller can await its sync and close it. */
    public SharedIndexInformer<ConfigMap> start() {
        informer = client.configMaps().inNamespace(namespace).inform(new ResourceEventHandler<>() {
            @Override
            public void onAdd(ConfigMap configMap) {
                byName.put(configMap.getMetadata().getName(), decode(configMap));
            }

            @Override
            public void onUpdate(ConfigMap oldConfigMap, ConfigMap newConfigMap) {
                byName.put(newConfigMap.getMetadata().getName(), decode(newConfigMap));
            }

            @Override
            public void onDelete(ConfigMap configMap, boolean deletedFinalStateUnknown) {
                byName.remove(configMap.getMetadata().getName());
            }
        });
        return informer;
    }

    /** The {@code data} (plain) and {@code binaryData} (base64) maps of a ConfigMap. */
    private static Map<String, String> decode(ConfigMap configMap) {
        Map<String, String> decoded = new ConcurrentHashMap<>();
        Map<String, String> data = configMap.getData();
        if (data != null) {
            data.forEach((key, value) -> {
                if (value != null) {
                    decoded.put(key, value);
                }
            });
        }
        Map<String, String> binaryData = configMap.getBinaryData();
        if (binaryData != null) {
            binaryData.forEach((key, base64) -> {
                if (base64 != null) {
                    decoded.put(key, new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8));
                }
            });
        }
        return decoded;
    }

    @Override
    public String value(String name, String key) {
        Map<String, String> data = byName.get(name);
        return data == null ? null : data.get(key);
    }

    @Override
    public void close() {
        if (informer != null) {
            informer.close();
            informer = null;
        }
        byName.clear();
    }
}
