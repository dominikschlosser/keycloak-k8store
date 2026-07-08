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
package com.github.dominikschlosser.k8store.kubernetes.crd;

import com.github.dominikschlosser.k8store.crd.UserSpec;
import io.fabric8.crd.generator.annotation.SchemaSwap;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;
import org.keycloak.representations.idm.CredentialRepresentation;

/**
 * A Keycloak user stored as a Kubernetes custom resource (experimental {@code user} area).
 * The spec is {@link UserSpec} - Keycloak's user representation plus the realm; {@code spec.id}
 * defaults to {@code metadata.name}.
 *
 * <p><strong>Security:</strong> these CRs carry credential hashes (and broker tokens when token
 * storage is on) - lock down RBAC read access to {@code keycloakusers}.
 */
@Group(K8sCrd.GROUP)
@Version(value = K8sCrd.VERSION, served = true, storage = true)
@Kind("KeycloakUser")
@Singular("keycloakuser")
@Plural("keycloakusers")
@ShortNames("ku")
// defense in depth: drop the representation's plaintext credential field from the schema, so a
// real API server prunes hand-authored plaintext values at admission - they never reach etcd.
// Keycloak itself only ever writes hashed secretData/credentialData.
@SchemaSwap(originalType = CredentialRepresentation.class, fieldName = "value")
public class KeycloakUserCr extends CustomResource<UserSpec, Void> implements Namespaced {

    /** Explicit factories - the fabric8 default implementations use reflection. */
    @Override
    protected UserSpec initSpec() {
        return new UserSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }
}
