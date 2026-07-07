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

import com.github.dominikschlosser.k8store.crd.OrganizationInvitationSpec;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * A pending (or expired) organization invitation as a Kubernetes custom resource
 * ({@code organization} area, runtime data - writable in read-only mode). These CRs are
 * Keycloak-managed by the invitation flows and never authored by hand.
 */
@Group(KeycloakOrganizationInvitationCr.GROUP)
@Version(value = KeycloakOrganizationInvitationCr.VERSION, served = true, storage = true)
@Kind("KeycloakOrganizationInvitation")
@Singular("keycloakorganizationinvitation")
@Plural("keycloakorganizationinvitations")
@ShortNames("korginv")
public class KeycloakOrganizationInvitationCr extends CustomResource<OrganizationInvitationSpec, Void>
        implements Namespaced {

    /** Explicit factories - the fabric8 default implementations use reflection. */
    @Override
    protected OrganizationInvitationSpec initSpec() {
        return new OrganizationInvitationSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }

    public static final String GROUP = "k8store.dominikschlosser.github.io";
    public static final String VERSION = "v1alpha1";
}
