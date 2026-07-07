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
package com.github.dominikschlosser.k8store.realm;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RealmProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Neutralizes Keycloak's built-in JPA realm provider factory while realms are served from custom
 * resources.
 *
 * <p>Why this is needed: even when another realm provider is selected, Keycloak's JPA realm
 * factory stays registered with the session factory as a provider-event listener. When this
 * extension publishes model removal events (e.g. {@code RealmModel.RealmRemovedEvent} after a
 * realm CR is deleted), that listener tries to cascade JPA deletions for models that never lived
 * in the database and fails. Registering this empty factory under the same id ({@code jpa}) with
 * a higher {@link #order()} shadows the built-in factory entirely, so it is never instantiated
 * and never listens. Only active when the realm area is CR-backed.
 */
@AutoService(RealmProviderFactory.class)
public class JpaRealmProviderFactory implements RealmProviderFactory<RealmProvider>, EnvironmentDependentProviderFactory {

    public static final String PROVIDER_ID = "jpa";

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public boolean isSupported(Config.Scope config) {
        // only shadow the built-in JPA realm factory when realms are actually served from CRs
        return K8sStoreConfig.isAreaEnabled(Area.REALM);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public RealmProvider create(KeycloakSession session) {
        return null;
    }

    @Override
    public void close() {}

    @Override
    public int order() {
        // above the built-in factory's order, so this one wins the id
        return 100;
    }
}
