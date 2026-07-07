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
package com.github.dominikschlosser.k8store.group;

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.GROUP_AFTER_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.GROUP_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_RENAMED;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import com.google.auto.service.AutoService;
import java.util.List;
import java.util.stream.Collectors;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.provider.InvalidationHandler;

@AutoService(GroupProviderFactory.class)
public class GroupCrProviderFactory extends AbstractCrProviderFactory<GroupCrProvider>
        implements GroupProviderFactory<GroupCrProvider>, InvalidationHandler {

    public GroupCrProviderFactory() {
        super(GroupCrProvider.class, K8sStoreConfig.Area.GROUP);
    }

    @Override
    protected GroupCrProvider createNew(KeycloakSession session) {
        return new GroupCrProvider(session);
    }

    @Override
    public String getHelpText() {
        return "Group provider backed by KeycloakGroup custom resources";
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == REALM_BEFORE_REMOVE) {
            create(session).preRemove((RealmModel) params[0]);
        } else if (type == ROLE_BEFORE_REMOVE) {
            create(session).roleRemoved((RealmModel) params[0], (RoleModel) params[1]);
        } else if (type == ROLE_RENAMED) {
            create(session).roleRenamed((RealmModel) params[0], (RoleModel) params[1], (String) params[2]);
        } else if (type == GROUP_BEFORE_REMOVE) {
            RealmModel realm = (RealmModel) params[0];
            GroupModel group = (GroupModel) params[1];
            realm.removeDefaultGroup(group);
            List<GroupModel> subGroups = group.getSubGroupsStream().collect(Collectors.toList());
            subGroups.forEach(subGroup -> create(session).removeGroup(realm, subGroup));
        } else if (type == GROUP_AFTER_REMOVE) {
            session.getKeycloakSessionFactory().publish(new GroupModel.GroupRemovedEvent() {
                @Override
                public RealmModel getRealm() {
                    return (RealmModel) params[0];
                }

                @Override
                public GroupModel getGroup() {
                    return (GroupModel) params[1];
                }

                @Override
                public KeycloakSession getKeycloakSession() {
                    return session;
                }
            });
        }
    }
}
