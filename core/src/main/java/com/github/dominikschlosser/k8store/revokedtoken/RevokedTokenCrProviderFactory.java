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

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import com.google.auto.service.AutoService;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RevokedTokenProviderFactory;
import org.keycloak.userprofile.DeclarativeUserProfileProviderFactory;

@AutoService(RevokedTokenProviderFactory.class)
public class RevokedTokenCrProviderFactory extends AbstractCrProviderFactory<RevokedTokenCrProvider>
        implements RevokedTokenProviderFactory<RevokedTokenCrProvider> {

    public RevokedTokenCrProviderFactory() {
        super(RevokedTokenCrProvider.class, K8sStoreConfig.Area.REVOKED_TOKEN);
    }

    @Override
    protected RevokedTokenCrProvider createNew(KeycloakSession session) {
        return new RevokedTokenCrProvider();
    }

    @Override
    public String getHelpText() {
        return "Revoked token provider backed by KeycloakRevokedToken custom resources (experimental)";
    }

    /** Beats the cacheless JPA default in {@code getProvider(Class)} resolution. */
    @Override
    public int order() {
        return DeclarativeUserProfileProviderFactory.PROVIDER_PRIORITY + 1;
    }
}
