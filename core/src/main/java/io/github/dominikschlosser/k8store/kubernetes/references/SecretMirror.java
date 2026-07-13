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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Informer-backed mirror of the Kubernetes Secrets in one namespace, feeding
 * {@link PlaceholderResolver}'s {@code ${secret:name:key}} lookups without an API-server round trip
 * on the read hot path. Secret rotation propagates through the watch like every other kind mirrored
 * by this datastore.
 *
 * <p>A single namespace is watched - the configured write namespace - even when the CRDs are
 * watched cluster-wide: a {@code ${secret:...}} reference carries no namespace, so referenced
 * Secrets must live alongside the datastore in its namespace.
 */
public final class SecretMirror implements PlaceholderResolver.SecretSource, AutoCloseable {

    private final KubernetesClient client;
    private final String namespace;

    /** secretName -> (key -> decoded value). */
    private final Map<String, Map<String, String>> byName = new ConcurrentHashMap<>();

    private SharedIndexInformer<Secret> informer;

    public SecretMirror(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /** Starts the Secret informer and returns it so the caller can await its sync and close it. */
    public SharedIndexInformer<Secret> start() {
        informer = client.secrets().inNamespace(namespace).inform(new ResourceEventHandler<>() {
            @Override
            public void onAdd(Secret secret) {
                byName.put(secret.getMetadata().getName(), decode(secret));
            }

            @Override
            public void onUpdate(Secret oldSecret, Secret newSecret) {
                byName.put(newSecret.getMetadata().getName(), decode(newSecret));
            }

            @Override
            public void onDelete(Secret secret, boolean deletedFinalStateUnknown) {
                byName.remove(secret.getMetadata().getName());
            }
        });
        return informer;
    }

    /** The {@code data} map of a Secret decoded from base64 to plain UTF-8 strings. */
    private static Map<String, String> decode(Secret secret) {
        Map<String, String> decoded = new ConcurrentHashMap<>();
        Map<String, String> data = secret.getData();
        if (data != null) {
            data.forEach((key, base64) -> {
                if (base64 != null) {
                    decoded.put(key, new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8));
                }
            });
        }
        // stringData is a write-only convenience the API server folds into data; tolerate it in
        // tests or externally-crafted objects that still carry it
        Map<String, String> stringData = secret.getStringData();
        if (stringData != null) {
            stringData.forEach((key, value) -> {
                if (value != null) {
                    decoded.put(key, value);
                }
            });
        }
        return decoded;
    }

    @Override
    public String value(String secretName, String key) {
        Map<String, String> data = byName.get(secretName);
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
