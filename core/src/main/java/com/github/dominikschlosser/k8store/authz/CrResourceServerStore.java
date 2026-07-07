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
package com.github.dominikschlosser.k8store.authz;

import com.github.dominikschlosser.k8store.crd.ResourceServerSpec;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.store.ResourceServerStore;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ModelException;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.PolicyEnforcementMode;
import org.keycloak.storage.StorageId;

/**
 * {@link ResourceServerStore} over {@code KeycloakResourceServer} custom resources. The
 * resource-server id is the owning client's id (upstream JPA convention) - in this store that
 * is the clientId, so {@code findById} without a client is scoped to the session's realm
 * (clientIds are only unique per realm), with a cross-realm scan as last resort.
 */
class CrResourceServerStore implements ResourceServerStore {

    private final CrStoreFactory factory;

    CrResourceServerStore(CrStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public ResourceServer create(ClientModel client) {
        if (!StorageId.isLocalStorage(client.getId())) {
            throw new ModelException("Creating resource server from federated ClientModel not supported");
        }
        ResourceServerSpec spec = new ResourceServerSpec();
        spec.setClientId(client.getId());
        spec.setRealm(client.getRealm().getId());
        spec.setAllowRemoteResourceManagement(false);
        spec.setPolicyEnforcementMode(PolicyEnforcementMode.ENFORCING);
        spec.setDecisionStrategy(DecisionStrategy.UNANIMOUS);
        return factory.wrap(AuthzCrStore.save(spec));
    }

    @Override
    public void delete(ClientModel client) {
        String realmId = client.getRealm().getId();
        if (AuthzCrStore.resourceServer(realmId, client.getId()) == null) {
            return;
        }
        factory.deleteResourceServerGraph(realmId, client.getId());
    }

    @Override
    public ResourceServer findById(String id) {
        return factory.resourceServerById(factory.contextRealmId(), id);
    }

    @Override
    public ResourceServer findByClient(ClientModel client) {
        return factory.wrap(AuthzCrStore.resourceServer(client.getRealm().getId(), client.getId()));
    }
}
