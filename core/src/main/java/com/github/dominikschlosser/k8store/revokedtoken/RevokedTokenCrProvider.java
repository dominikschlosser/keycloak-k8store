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
package com.github.dominikschlosser.k8store.revokedtoken;

import com.github.dominikschlosser.k8store.crd.RevokedTokenSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import org.keycloak.common.util.Time;
import org.keycloak.models.RevokedTokenProvider;

/**
 * {@link RevokedTokenProvider} over {@code KeycloakRevokedToken} custom resources, indexed under
 * the {@linkplain K8sStorageBackend#GLOBAL_PSEUDO_REALM global pseudo-realm} (revoked tokens are
 * not realm-scoped). Semantics match the stateless JPA provider: {@link #put} inserts only if
 * absent (expiry = now + lifespan) and reports whether it inserted; {@link #contains} is
 * expiry-filtered. Writes go straight to the API server - a revocation must hold on every node
 * as fast as the watches deliver it - while {@link #contains} reads the mirror only (hot path).
 */
public class RevokedTokenCrProvider implements RevokedTokenProvider {

    @Override
    public boolean put(String tokenId, long lifespanSeconds) {
        RevokedTokenSpec spec = new RevokedTokenSpec();
        spec.setTokenId(tokenId);
        spec.setExpiresAt(Time.currentTimeMillis() + lifespanSeconds * 1000L);
        return K8sStorageBackend.putIfAbsentNow(
                RevokedTokenSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, tokenId, spec);
    }

    @Override
    public boolean contains(String tokenId) {
        // mirror-only on purpose: contains() runs on every token validation and "not revoked"
        // is the common case - an API-server round trip per miss would bound request
        // throughput. A revocation from another node holds within watch latency (milliseconds).
        return K8sStorageBackend.get().read(RevokedTokenSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, tokenId)
                != null;
    }

    @Override
    public void close() {
        // stateless facade over the informer-backed store
    }
}
