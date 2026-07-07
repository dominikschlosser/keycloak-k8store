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
package com.github.dominikschlosser.k8store.organization;

import com.github.dominikschlosser.k8store.common.CrStore;
import com.github.dominikschlosser.k8store.crd.OrganizationInvitationSpec;
import java.util.List;

/**
 * Access to {@code KeycloakOrganizationInvitation} custom resources, keyed by
 * {@code (realm, id)}. Runtime data written by the invitation flows - the kind is always
 * writable, including in read-only mode.
 */
public final class OrganizationInvitationCrStore {

    private static final CrStore<OrganizationInvitationSpec> STORE = new CrStore<>(
            OrganizationInvitationSpec.class, OrganizationInvitationSpec::getRealm, OrganizationInvitationSpec::getId);

    private OrganizationInvitationCrStore() {}

    public static OrganizationInvitationSpec read(String realmId, String id) {
        return STORE.read(realmId, id);
    }

    public static List<OrganizationInvitationSpec> allInRealm(String realmId) {
        return STORE.allInRealm(realmId);
    }

    public static OrganizationInvitationSpec save(OrganizationInvitationSpec spec) {
        return STORE.save(spec);
    }

    public static void delete(String realmId, String id) {
        STORE.delete(realmId, id);
    }
}
