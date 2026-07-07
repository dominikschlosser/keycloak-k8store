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

import com.github.dominikschlosser.k8store.crd.RealmSpec;
import io.fabric8.crd.generator.annotation.SchemaSwap;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;
import org.keycloak.representations.idm.ComponentExportRepresentation;

/**
 * A Keycloak realm, stored as a Kubernetes custom resource. The spec is Keycloak's realm
 * representation ({@link RealmSpec}); {@code spec.realm} — the realm name, doubling as the realm
 * id — defaults to {@code metadata.name} when omitted.
 *
 * <p>{@code components.*.subComponents} recurses (components nest their child components, e.g.
 * user-storage mappers); a CRD structural schema cannot express recursion, so the schema is
 * unrolled to a fixed depth — 3 levels, matching what the upstream keycloak-operator uses for
 * the same representation graph.
 */
@Group(KeycloakRealmCr.GROUP)
@Version(value = KeycloakRealmCr.VERSION, served = true, storage = true)
@Kind("KeycloakRealm")
@Singular("keycloakrealm")
@Plural("keycloakrealms")
@ShortNames("kr")
@SchemaSwap(originalType = ComponentExportRepresentation.class, fieldName = "subComponents", depth = 3)
public class KeycloakRealmCr extends CustomResource<RealmSpec, Void> implements Namespaced {

    /** Explicit factories — the fabric8 default implementations use reflection. */
    @Override
    protected RealmSpec initSpec() {
        return new RealmSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }

    public static final String GROUP = "k8store.dominikschlosser.github.io";
    public static final String VERSION = "v1alpha1";
}
