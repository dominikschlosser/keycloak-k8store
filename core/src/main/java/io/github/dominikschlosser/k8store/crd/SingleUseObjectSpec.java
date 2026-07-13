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
package io.github.dominikschlosser.k8store.crd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Spec of a {@code KeycloakSingleUseObject} custom resource: one short-lived single-use entry
 * (action token, nonce, code replay guard, ...), keyed by Keycloak's opaque object
 * {@link #getKey() key}. Single-use objects are not realm-scoped - the backend indexes them
 * under a constant pseudo-realm. The single-use guarantee maps onto Kubernetes DELETE semantics:
 * whichever node's delete succeeds owns the consumption. Written by Keycloak at runtime - never
 * author these by hand.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SingleUseObjectSpec {

    /** Keycloak's single-use object key, the store id. */
    private String key;

    private Map<String, String> notes;

    /** Absolute expiration, epoch millis. */
    private Long expiresAt;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Map<String, String> getNotes() {
        return notes;
    }

    public void setNotes(Map<String, String> notes) {
        this.notes = notes;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
