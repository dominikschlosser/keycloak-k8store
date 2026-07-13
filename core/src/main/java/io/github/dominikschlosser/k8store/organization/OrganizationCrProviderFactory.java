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
package io.github.dominikschlosser.k8store.organization;

import static io.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_BEFORE_REMOVE;

import com.google.auto.service.AutoService;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import io.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import org.keycloak.Config.Scope;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.organization.OrganizationProviderFactory;
import org.keycloak.provider.InvalidationHandler;
import org.keycloak.userprofile.DeclarativeUserProfileProviderFactory;

/**
 * Registers the organization provider ({@code organization} SPI, provider id {@code k8store})
 * when the {@code organization} area AND the {@code organizations} feature are enabled - the
 * SPI itself vanishes with the feature, and the {@code OrganizationProviderFactory} default
 * {@code isSupported} carries the feature check. Keycloak resolves the SPI's default provider by
 * the highest {@code order()}, so this factory outranks the built-in {@code jpa} store
 * (order 0); with the area disabled, organizations would fall through to JPA - a combination
 * that cannot work with CR-backed groups and is therefore rejected at boot by
 * {@link K8sStoreConfig}.
 *
 * <p>The built-in JPA factory stays registered (both are supported when the feature is on) and
 * its {@code postInit} group-event guard keeps protecting organization groups from mutations
 * outside an organization context - it resolves the session-default organization provider, i.e.
 * this one.
 *
 * <p>Cleanup: {@code REALM_BEFORE_REMOVE} bulk-deletes the realm's organization and invitation
 * CRs (backing groups die in the group provider's own realm cascade). No user-removal listener
 * is needed: membership and the managed-member marker live on the user and disappear with it.
 */
@AutoService(OrganizationProviderFactory.class)
public class OrganizationCrProviderFactory extends AbstractCrProviderFactory<OrganizationProvider>
        implements OrganizationProviderFactory, InvalidationHandler {

    public OrganizationCrProviderFactory() {
        super(OrganizationProvider.class, K8sStoreConfig.Area.ORGANIZATION);
    }

    @Override
    protected OrganizationProvider createNew(KeycloakSession session) {
        return new OrganizationCrProvider(session);
    }

    @Override
    public String getHelpText() {
        return "Organization provider backed by KeycloakOrganization custom resources";
    }

    /** Area gate (base class) plus the SPI's feature gate. */
    @Override
    public boolean isSupported(Scope config) {
        return super.isSupported(config) && OrganizationProviderFactory.super.isSupported(config);
    }

    /** Beats the built-in jpa organization provider (order 0) in default-provider resolution. */
    @Override
    public int order() {
        return DeclarativeUserProfileProviderFactory.PROVIDER_PRIORITY + 1;
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == REALM_BEFORE_REMOVE) {
            ((OrganizationCrProvider) create(session)).realmRemoved((RealmModel) params[0]);
        }
    }
}
