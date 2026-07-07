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

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_AFTER_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_BEFORE_REMOVE;

import com.github.dominikschlosser.k8store.crd.ClientInitialAccessSpec;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;

/**
 * {@link RealmProvider} serving realms from {@code KeycloakRealm} custom resources. The realm
 * name is the id, so {@link #getRealmByName} is the same key lookup as {@link #getRealm}.
 */
public class RealmCrProvider implements RealmProvider {

    private static final Logger LOG = Logger.getLogger(RealmCrProvider.class);

    private final KeycloakSession session;

    public RealmCrProvider(KeycloakSession session) {
        this.session = session;
    }

    private RealmModel adapt(RealmSpec spec) {
        return new RealmAdapter(session, spec);
    }

    @Override
    public RealmModel createRealm(String name) {
        return createRealm(name, name);
    }

    @Override
    public RealmModel createRealm(String id, String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }
        if (id != null && !id.equals(name)) {
            // this store uses the name as the id — same convention as clients (clientId) and
            // client scopes (name)
            LOG.debugv("Ignoring explicit realm id {0}: this store keys realms by name ({1})", id, name);
        }
        if (getRealmByName(name) != null) {
            throw new ModelDuplicateException("Realm with given name exists: " + name);
        }
        RealmSpec spec = new RealmSpec();
        spec.setId(name);
        spec.setRealm(name);
        RealmCrStore.save(spec);
        return adapt(spec);
    }

    @Override
    public RealmModel getRealm(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        RealmSpec spec = RealmCrStore.read(id);
        return spec == null ? null : adapt(spec);
    }

    @Override
    public RealmModel getRealmByName(String name) {
        // the name is the id
        return getRealm(name);
    }

    @Override
    public Stream<RealmModel> getRealmsStream() {
        return RealmCrStore.readAll().stream()
                .map(this::adapt)
                .sorted(Comparator.comparing(RealmModel::getName));
    }

    @Override
    public Stream<RealmModel> getRealmsWithProviderTypeStream(Class<?> type) {
        return getRealmsStream()
                .filter(realm -> realm.getComponentsStream()
                        .anyMatch(component -> type.getName().equals(component.getProviderType())));
    }

    @Override
    public boolean removeRealm(String id) {
        RealmModel realm = getRealm(id);
        if (realm == null) {
            return false;
        }
        session.invalidate(REALM_BEFORE_REMOVE, realm);
        RealmCrStore.delete(realm.getId());
        session.invalidate(REALM_AFTER_REMOVE, realm);
        return true;
    }

    @Override
    public void removeExpiredClientInitialAccess() {
        if (K8sStoreConfig.get().isReadOnly()) {
            // periodic cleanup task: expired entries are ignored on use; in read-only mode the
            // CRs are managed out-of-band, so there is nothing this node may delete
            return;
        }
        int now = Time.currentTime();
        for (RealmSpec spec : RealmCrStore.readAll()) {
            List<ClientInitialAccessSpec> entries = spec.getClientInitialAccesses();
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            boolean changed = entries.removeIf(entry -> {
                Integer expiration = entry.getExpiration();
                Integer timestamp = entry.getTimestamp();
                return expiration != null && expiration > 0
                        && timestamp != null && timestamp + expiration < now;
            });
            if (changed) {
                RealmCrStore.save(spec);
            }
        }
    }

    // ------------------------------------------------------------------ localization

    @Override
    public void saveLocalizationText(RealmModel realm, String locale, String key, String text) {
        if (locale == null || key == null || text == null) {
            return;
        }
        realm.createOrUpdateRealmLocalizationTexts(locale, Map.of(key, text));
    }

    @Override
    public void saveLocalizationTexts(RealmModel realm, String locale, Map<String, String> localizationTexts) {
        if (locale == null || localizationTexts == null) {
            return;
        }
        realm.createOrUpdateRealmLocalizationTexts(locale, localizationTexts);
    }

    @Override
    public boolean updateLocalizationText(RealmModel realm, String locale, String key, String text) {
        if (locale == null || key == null || text == null
                || !realm.getRealmLocalizationTextsByLocale(locale).containsKey(key)) {
            return false;
        }
        saveLocalizationText(realm, locale, key, text);
        return true;
    }

    @Override
    public boolean deleteLocalizationTextsByLocale(RealmModel realm, String locale) {
        return realm.removeRealmLocalizationTexts(locale);
    }

    @Override
    public boolean deleteLocalizationText(RealmModel realm, String locale, String key) {
        if (locale == null || key == null
                || !realm.getRealmLocalizationTextsByLocale(locale).containsKey(key)) {
            return false;
        }
        Map<String, String> texts = new HashMap<>(realm.getRealmLocalizationTextsByLocale(locale));
        texts.remove(key);
        realm.removeRealmLocalizationTexts(locale);
        realm.createOrUpdateRealmLocalizationTexts(locale, texts);
        return true;
    }

    @Override
    public String getLocalizationTextsById(RealmModel realm, String locale, String key) {
        if (locale == null || key == null) {
            return null;
        }
        return realm.getRealmLocalizationTextsByLocale(locale).get(key);
    }

    @Override
    public void close() {
        // stateless facade over the informer-backed store
    }
}
