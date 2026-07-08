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

import com.github.dominikschlosser.k8store.crd.IssuedVerifiableCredentialSpec;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * One OID4VC credential issuance, stored as a Kubernetes custom resource (experimental
 * {@code user} area + {@code oid4vc-vci} feature). Keycloak-managed runtime state that expires
 * ({@code spec.expiresAt}) - never authored by hand. The spec is the original
 * {@link IssuedVerifiableCredentialSpec}; {@code spec.id} is the store id.
 */
@Group(K8sCrd.GROUP)
@Version(value = K8sCrd.VERSION, served = true, storage = true)
@Kind("KeycloakIssuedVerifiableCredential")
@Singular("keycloakissuedverifiablecredential")
@Plural("keycloakissuedverifiablecredentials")
@ShortNames("kivc")
public class KeycloakIssuedVerifiableCredentialCr extends CustomResource<IssuedVerifiableCredentialSpec, Void>
        implements Namespaced {

    /** Explicit factories - the fabric8 default implementations use reflection. */
    @Override
    protected IssuedVerifiableCredentialSpec initSpec() {
        return new IssuedVerifiableCredentialSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }
}
