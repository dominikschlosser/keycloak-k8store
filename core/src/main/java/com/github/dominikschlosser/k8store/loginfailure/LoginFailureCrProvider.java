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
package com.github.dominikschlosser.k8store.loginfailure;

import com.github.dominikschlosser.k8store.crd.LoginFailureSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.HashMap;
import java.util.Map;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.models.UserLoginFailureProvider;

/**
 * {@link UserLoginFailureProvider} serving brute-force-protection counters from
 * {@code KeycloakLoginFailure} custom resources - one CR per (realm, user), keyed by user id.
 */
public class LoginFailureCrProvider implements UserLoginFailureProvider {

    /** Adapter per user id, so the protector's repeated lookups mutate one instance. */
    private final Map<String, LoginFailureAdapter> knownAdapters = new HashMap<>();

    private LoginFailureAdapter adapt(LoginFailureSpec spec) {
        return knownAdapters.computeIfAbsent(
                K8sStorageBackend.key(spec.getRealm(), spec.getUserId()), key -> new LoginFailureAdapter(spec));
    }

    @Override
    public UserLoginFailureModel getUserLoginFailure(RealmModel realm, String userId) {
        if (userId == null) {
            return null;
        }
        LoginFailureSpec spec = LoginFailureCrStore.read(realm.getId(), userId);
        return spec == null ? null : adapt(spec);
    }

    @Override
    public UserLoginFailureModel addUserLoginFailure(RealmModel realm, String userId) {
        UserLoginFailureModel existing = getUserLoginFailure(realm, userId);
        if (existing != null) {
            return existing;
        }
        LoginFailureSpec spec = new LoginFailureSpec();
        spec.setRealm(realm.getId());
        spec.setUserId(userId);
        LoginFailureCrStore.save(spec);
        return adapt(spec);
    }

    @Override
    public void removeUserLoginFailure(RealmModel realm, String userId) {
        knownAdapters.remove(K8sStorageBackend.key(realm.getId(), userId));
        LoginFailureCrStore.delete(realm.getId(), userId);
    }

    @Override
    public void removeAllUserLoginFailures(RealmModel realm) {
        LoginFailureCrStore.allInRealm(realm.getId()).forEach(spec -> removeUserLoginFailure(realm, spec.getUserId()));
    }

    @Override
    public void close() {
        knownAdapters.clear();
    }
}
