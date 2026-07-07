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

import com.github.dominikschlosser.k8store.crd.OrganizationSpec;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * A Keycloak organization stored as a Kubernetes custom resource ({@code organization} area).
 * The spec is {@link OrganizationSpec} — Keycloak's organization representation plus the realm
 * and backing-group reference; {@code spec.id} defaults to {@code metadata.name}. Members,
 * organization groups and identity-provider linkage live in their own storage (users, {@code
 * KeycloakGroup} CRs with {@code type: organization}, the realm CR's identity providers).
 */
@Group(KeycloakOrganizationCr.GROUP)
@Version(value = KeycloakOrganizationCr.VERSION, served = true, storage = true)
@Kind("KeycloakOrganization")
@Singular("keycloakorganization")
@Plural("keycloakorganizations")
@ShortNames("korg")
public class KeycloakOrganizationCr extends CustomResource<OrganizationSpec, Void> implements Namespaced {

    /** Explicit factories — the fabric8 default implementations use reflection. */
    @Override
    protected OrganizationSpec initSpec() {
        return new OrganizationSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }

    public static final String GROUP = "k8store.dominikschlosser.github.io";
    public static final String VERSION = "v1alpha1";
}
