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
package io.github.dominikschlosser.k8store.identityProvider;

import com.google.auto.service.AutoService;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import io.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import org.keycloak.Config;
import org.keycloak.models.IdentityProviderStorageProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Registers the CR-backed identity provider store under provider id
 * {@link AbstractCrProviderFactory#PROVIDER_ID}. Identity providers live inside the realm custom
 * resource, so this area requires the realm area (validated by {@link K8sStoreConfig}).
 */
@AutoService(IdentityProviderStorageProviderFactory.class)
public class IdentityProviderCrProviderFactory
        implements IdentityProviderStorageProviderFactory<IdentityProviderCrProvider>,
                EnvironmentDependentProviderFactory {

    @Override
    public IdentityProviderCrProvider create(KeycloakSession session) {
        return new IdentityProviderCrProvider(session);
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}

    @Override
    public boolean isSupported(Config.Scope config) {
        return K8sStoreConfig.isAreaEnabled(K8sStoreConfig.Area.IDENTITY_PROVIDER);
    }

    @Override
    public String getId() {
        return AbstractCrProviderFactory.PROVIDER_ID;
    }

    @Override
    public int order() {
        // above the built-in provider's order so the k8store provider wins when enabled
        return 100;
    }
}
