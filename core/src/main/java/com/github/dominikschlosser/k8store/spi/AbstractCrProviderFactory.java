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
package com.github.dominikschlosser.k8store.spi;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import org.keycloak.Config.Scope;
import org.keycloak.component.AmphibianProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.Provider;

/**
 * Base of the k8store per-area provider factories. Every factory registers under provider id
 * {@link #PROVIDER_ID} and only when its {@link K8sStoreConfig.Area area} is configured to be
 * served from custom resources - otherwise Keycloak's default provider for the area stays in
 * place. Providers are stateless facades over the informer-backed store, so one instance per
 * session is enough; it is memoized in the session attributes.
 */
public abstract class AbstractCrProviderFactory<T extends Provider>
        implements AmphibianProviderFactory<T>, EnvironmentDependentProviderFactory {

    public static final String PROVIDER_ID = "k8store";

    private final Class<T> providerClass;
    private final K8sStoreConfig.Area area;

    protected AbstractCrProviderFactory(Class<T> providerClass, K8sStoreConfig.Area area) {
        this.providerClass = providerClass;
        this.area = area;
    }

    /** Creates the provider instance; called at most once per session. */
    protected abstract T createNew(KeycloakSession session);

    @Override
    public T create(KeycloakSession session) {
        String key = providerClass.getName();
        T provider = session.getAttribute(key, providerClass);
        if (provider == null) {
            provider = createNew(session);
            session.setAttribute(key, provider);
        }
        return provider;
    }

    @Override
    public boolean isSupported(Scope config) {
        return K8sStoreConfig.isAreaEnabled(area);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}
}
