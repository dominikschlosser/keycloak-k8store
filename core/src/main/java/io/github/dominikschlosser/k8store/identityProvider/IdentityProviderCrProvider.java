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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.keycloak.broker.provider.util.IdentityProviderTypeUtil;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.IdentityProviderQuery;
import org.keycloak.models.IdentityProviderStorageProvider;
import org.keycloak.models.IdentityProviderType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.utils.StringUtil;

/**
 * {@link IdentityProviderStorageProvider} serving identity providers and their mappers from the
 * realm's custom resource: they live in the realm spec's standard {@code identityProviders} and
 * {@code identityProviderMappers} representation lists, and all reads and writes go through the
 * realm model of the session's realm (this area therefore requires the realm area to be
 * CR-backed, which is validated at configuration time).
 */
public class IdentityProviderCrProvider implements IdentityProviderStorageProvider {

    private final KeycloakSession session;

    public IdentityProviderCrProvider(KeycloakSession session) {
        this.session = session;
    }

    private RealmModel realm() {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            throw new IllegalStateException("Session not bound to a realm");
        }
        return realm;
    }

    // ------------------------------------------------------------------ identity providers

    @Override
    public IdentityProviderModel create(IdentityProviderModel model) {
        realm().addIdentityProvider(model);
        return realm().getIdentityProviderByAlias(model.getAlias());
    }

    @Override
    public void update(IdentityProviderModel model) {
        realm().updateIdentityProvider(model);
    }

    @Override
    public boolean remove(String providerAlias) {
        // the realm model cascades the provider's mappers out with it
        realm().removeIdentityProviderByAlias(providerAlias);
        return true;
    }

    @Override
    public void removeAll() {
        realm().getIdentityProvidersStream()
                .map(IdentityProviderModel::getAlias)
                .toList()
                .forEach(alias -> realm().removeIdentityProviderByAlias(alias));
    }

    @Override
    public IdentityProviderModel getById(String internalId) {
        if (internalId == null) {
            return null;
        }
        return realm().getIdentityProvidersStream()
                .filter(idp -> internalId.equals(idp.getInternalId()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public IdentityProviderModel getByAlias(String alias) {
        return realm().getIdentityProviderByAlias(alias);
    }

    @Override
    public Stream<IdentityProviderModel> getAllStream(
            IdentityProviderQuery query, Integer firstResult, Integer maxResults) {
        int first = firstResult == null || firstResult < 0 ? 0 : firstResult;
        long limit = maxResults == null || maxResults < 0 ? Long.MAX_VALUE : maxResults;

        List<String> factoryFilter = null;
        if (query.getType() != null && query.getType() != IdentityProviderType.ANY) {
            factoryFilter = IdentityProviderTypeUtil.listFactoriesByType(session, query.getType());
        } else if (query.getCapability() != null) {
            factoryFilter = IdentityProviderTypeUtil.listFactoriesByCapability(session, query.getCapability());
        }
        // no factories registered for the type/capability: do not filter at all
        if (factoryFilter != null && factoryFilter.isEmpty()) {
            factoryFilter = null;
        }
        List<String> providerIds = factoryFilter;
        Map<String, String> options = query.getOptions();

        return realm().getIdentityProvidersStream()
                .filter(idp -> providerIds == null || providerIds.contains(idp.getProviderId()))
                .filter(idp -> matches(idp, options))
                .sorted(Comparator.comparing(IdentityProviderModel::getAlias))
                .skip(first)
                .limit(limit);
    }

    private static boolean matches(IdentityProviderModel idp, Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return true;
        }
        if (options.containsKey(IdentityProviderModel.ORGANIZATION_ID)
                && !Objects.equals(idp.getOrganizationId(), options.get(IdentityProviderModel.ORGANIZATION_ID))) {
            return false;
        }
        if (options.containsKey(IdentityProviderModel.ORGANIZATION_ID_NOT_NULL) && idp.getOrganizationId() == null) {
            return false;
        }
        if (options.containsKey(IdentityProviderModel.ENABLED)
                && idp.isEnabled() != Boolean.parseBoolean(options.get(IdentityProviderModel.ENABLED))) {
            return false;
        }
        if (options.containsKey(IdentityProviderModel.HIDE_ON_LOGIN)
                && Boolean.TRUE.equals(idp.isHideOnLogin())
                        != Boolean.parseBoolean(options.get(IdentityProviderModel.HIDE_ON_LOGIN))) {
            return false;
        }
        if (options.containsKey(IdentityProviderModel.LINK_ONLY)
                && Boolean.TRUE.equals(idp.isLinkOnly())
                        != Boolean.parseBoolean(options.get(IdentityProviderModel.LINK_ONLY))) {
            return false;
        }
        if (options.containsKey(IdentityProviderModel.ALIAS)
                && !Objects.equals(idp.getAlias(), options.get(IdentityProviderModel.ALIAS))) {
            return false;
        }
        if (options.containsKey(IdentityProviderModel.ALIAS_NOT_IN)
                && Arrays.asList(options.get(IdentityProviderModel.ALIAS_NOT_IN).split(","))
                        .contains(idp.getAlias())) {
            return false;
        }
        if (options.containsKey(IdentityProviderModel.SEARCH)
                && !aliasMatchesSearch(idp.getAlias(), options.get(IdentityProviderModel.SEARCH))) {
            return false;
        }
        return true;
    }

    /**
     * Matches an alias against a Keycloak search keyword. The admin API searches identity
     * providers by infix match (SQL {@code LIKE %keyword%}); asterisks are treated as wildcards
     * and ignored, so a keyword with or without them matches on the remaining literal text. A
     * blank keyword matches everything.
     */
    static boolean aliasMatchesSearch(String alias, String search) {
        if (StringUtil.isNullOrEmpty(search)) {
            return true;
        }
        return alias.contains(search.trim().replace("*", ""));
    }

    @Override
    public Stream<String> getByFlow(String flowId, String search, Integer firstResult, Integer maxResults) {
        int first = firstResult == null || firstResult < 0 ? 0 : firstResult;
        long limit = maxResults == null || maxResults < 0 ? Long.MAX_VALUE : maxResults;
        return realm().getIdentityProvidersStream()
                .filter(idp -> aliasMatchesSearch(idp.getAlias(), search))
                .filter(idp -> Objects.equals(idp.getFirstBrokerLoginFlowId(), flowId)
                        || Objects.equals(idp.getPostBrokerLoginFlowId(), flowId))
                .sorted(Comparator.comparing(IdentityProviderModel::getAlias))
                .skip(first)
                .limit(limit)
                .map(IdentityProviderModel::getAlias);
    }

    @Override
    public long count() {
        return realm().getIdentityProvidersStream().count();
    }

    // ------------------------------------------------------------------ mappers

    @Override
    public IdentityProviderMapperModel createMapper(IdentityProviderMapperModel model) {
        return realm().addIdentityProviderMapper(model);
    }

    @Override
    public void updateMapper(IdentityProviderMapperModel model) {
        realm().updateIdentityProviderMapper(model);
    }

    @Override
    public boolean removeMapper(IdentityProviderMapperModel model) {
        realm().removeIdentityProviderMapper(model);
        return true;
    }

    @Override
    public void removeAllMappers() {
        realm().getIdentityProviderMappersStream().toList().forEach(realm()::removeIdentityProviderMapper);
    }

    @Override
    public IdentityProviderMapperModel getMapperById(String id) {
        return realm().getIdentityProviderMapperById(id);
    }

    @Override
    public IdentityProviderMapperModel getMapperByName(String identityProviderAlias, String name) {
        return realm().getIdentityProviderMapperByName(identityProviderAlias, name);
    }

    @Override
    public Stream<IdentityProviderMapperModel> getMappersStream(
            Map<String, String> options, Integer firstResult, Integer maxResults) {
        int first = firstResult == null || firstResult < 0 ? 0 : firstResult;
        long limit = maxResults == null || maxResults < 0 ? Long.MAX_VALUE : maxResults;
        return realm().getIdentityProviderMappersStream()
                .filter(mapper -> options == null
                        || options.isEmpty()
                        || mapper.getConfig().entrySet().containsAll(options.entrySet()))
                .sorted(Comparator.comparing(IdentityProviderMapperModel::getName))
                .skip(first)
                .limit(limit);
    }

    @Override
    public Stream<IdentityProviderMapperModel> getMappersByAliasStream(String identityProviderAlias) {
        return realm().getIdentityProviderMappersStream()
                .filter(mapper -> Objects.equals(mapper.getIdentityProviderAlias(), identityProviderAlias))
                .sorted(Comparator.comparing(IdentityProviderMapperModel::getName));
    }

    @Override
    public void close() {
        // stateless facade over the realm model
    }
}
