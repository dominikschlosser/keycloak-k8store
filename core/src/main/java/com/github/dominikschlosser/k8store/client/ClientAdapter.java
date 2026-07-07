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
package com.github.dominikschlosser.k8store.client;

import com.github.dominikschlosser.k8store.common.ProtocolMapperSupport;
import com.github.dominikschlosser.k8store.common.ScopeMappingSupport;
import com.github.dominikschlosser.k8store.crd.ClientSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

/**
 * {@link ClientModel} over a {@link ClientSpec}. The adapter owns a defensive copy of the CR
 * spec; every mutation is persisted explicitly and as a whole - there are no dirty flags and no
 * shared references to rely on. Setters persist only when the value actually changes.
 *
 * <p>Identity: the model id <em>is</em> the clientId (human-readable, GitOps-friendly). Renaming
 * the clientId moves the CR - the old resource is deleted and the spec is re-persisted under the
 * new id; name-based references from other stores keep the usual staleness caveat of this store.
 *
 * <p>Registered cluster nodes are runtime information and deliberately never persisted to the
 * custom resource; they live in a per-factory in-memory map.
 */
public class ClientAdapter implements ClientModel {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final ClientSpec spec;
    private final Map<String, Map<String, Integer>> registeredNodesStore;

    public ClientAdapter(KeycloakSession session, RealmModel realm, ClientSpec spec,
                         Map<String, Map<String, Integer>> registeredNodesStore) {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(spec, "spec");
        this.session = session;
        this.realm = realm;
        this.spec = spec;
        this.registeredNodesStore = registeredNodesStore;
    }

    private void persist() {
        ClientCrStore.save(spec);
    }

    /**
     * The live spec this adapter keeps persisting. Cross-cutting writes that happen while an
     * adapter is being populated (e.g. the login-protocol factories assigning the realm's
     * default client scopes from the {@code ClientProtocolUpdatedEvent} fired in the middle of
     * client creation) must mutate <em>this</em> instance - a freshly read copy would be
     * clobbered by the adapter's next persist.
     */
    ClientSpec spec() {
        return spec;
    }

    @Override
    public void updateClient() {
        session.getKeycloakSessionFactory().publish(new ClientModel.ClientUpdatedEvent() {
            @Override
            public ClientModel getUpdatedClient() {
                return ClientAdapter.this;
            }

            @Override
            public KeycloakSession getKeycloakSession() {
                return session;
            }
        });
    }

    // ------------------------------------------------------------------ identity

    @Override
    public String getId() {
        return spec.getClientId();
    }

    @Override
    public String getClientId() {
        return spec.getClientId();
    }

    @Override
    public void setClientId(String clientId) {
        String current = spec.getClientId();
        if (Objects.equals(current, clientId)) {
            return;
        }
        // the clientId is the store id: move the CR instead of mutating it in place
        ClientCrStore.delete(spec.getRealm(), current);
        spec.setClientId(clientId);
        spec.setId(clientId);
        persist();
    }

    @Override
    public String getName() {
        return spec.getName();
    }

    @Override
    public void setName(String name) {
        if (!Objects.equals(spec.getName(), name)) {
            spec.setName(name);
            persist();
        }
    }

    @Override
    public String getDescription() {
        return spec.getDescription();
    }

    @Override
    public void setDescription(String description) {
        if (!Objects.equals(spec.getDescription(), description)) {
            spec.setDescription(description);
            persist();
        }
    }

    @Override
    public RealmModel getRealm() {
        return realm;
    }

    // ------------------------------------------------------------------ flags

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(spec.isEnabled());
    }

    @Override
    public void setEnabled(boolean enabled) {
        setBoolean(spec.isEnabled(), enabled, spec::setEnabled);
    }

    @Override
    public boolean isAlwaysDisplayInConsole() {
        return Boolean.TRUE.equals(spec.isAlwaysDisplayInConsole());
    }

    @Override
    public void setAlwaysDisplayInConsole(boolean alwaysDisplayInConsole) {
        setBoolean(spec.isAlwaysDisplayInConsole(), alwaysDisplayInConsole, spec::setAlwaysDisplayInConsole);
    }

    @Override
    public boolean isSurrogateAuthRequired() {
        return Boolean.TRUE.equals(spec.isSurrogateAuthRequired());
    }

    @Override
    public void setSurrogateAuthRequired(boolean surrogateAuthRequired) {
        setBoolean(spec.isSurrogateAuthRequired(), surrogateAuthRequired, spec::setSurrogateAuthRequired);
    }

    @Override
    public boolean isBearerOnly() {
        return Boolean.TRUE.equals(spec.isBearerOnly());
    }

    @Override
    public void setBearerOnly(boolean bearerOnly) {
        setBoolean(spec.isBearerOnly(), bearerOnly, spec::setBearerOnly);
    }

    @Override
    public boolean isFrontchannelLogout() {
        return Boolean.TRUE.equals(spec.isFrontchannelLogout());
    }

    @Override
    public void setFrontchannelLogout(boolean frontchannelLogout) {
        setBoolean(spec.isFrontchannelLogout(), frontchannelLogout, spec::setFrontchannelLogout);
    }

    @Override
    public boolean isFullScopeAllowed() {
        return Boolean.TRUE.equals(spec.isFullScopeAllowed());
    }

    @Override
    public void setFullScopeAllowed(boolean fullScopeAllowed) {
        setBoolean(spec.isFullScopeAllowed(), fullScopeAllowed, spec::setFullScopeAllowed);
    }

    @Override
    public boolean isPublicClient() {
        return Boolean.TRUE.equals(spec.isPublicClient());
    }

    @Override
    public void setPublicClient(boolean publicClient) {
        setBoolean(spec.isPublicClient(), publicClient, spec::setPublicClient);
    }

    @Override
    public boolean isConsentRequired() {
        return Boolean.TRUE.equals(spec.isConsentRequired());
    }

    @Override
    public void setConsentRequired(boolean consentRequired) {
        setBoolean(spec.isConsentRequired(), consentRequired, spec::setConsentRequired);
    }

    @Override
    public boolean isStandardFlowEnabled() {
        return Boolean.TRUE.equals(spec.isStandardFlowEnabled());
    }

    @Override
    public void setStandardFlowEnabled(boolean standardFlowEnabled) {
        setBoolean(spec.isStandardFlowEnabled(), standardFlowEnabled, spec::setStandardFlowEnabled);
    }

    @Override
    public boolean isImplicitFlowEnabled() {
        return Boolean.TRUE.equals(spec.isImplicitFlowEnabled());
    }

    @Override
    public void setImplicitFlowEnabled(boolean implicitFlowEnabled) {
        setBoolean(spec.isImplicitFlowEnabled(), implicitFlowEnabled, spec::setImplicitFlowEnabled);
    }

    @Override
    public boolean isDirectAccessGrantsEnabled() {
        return Boolean.TRUE.equals(spec.isDirectAccessGrantsEnabled());
    }

    @Override
    public void setDirectAccessGrantsEnabled(boolean directAccessGrantsEnabled) {
        setBoolean(spec.isDirectAccessGrantsEnabled(), directAccessGrantsEnabled, spec::setDirectAccessGrantsEnabled);
    }

    @Override
    public boolean isServiceAccountsEnabled() {
        return Boolean.TRUE.equals(spec.isServiceAccountsEnabled());
    }

    @Override
    public void setServiceAccountsEnabled(boolean serviceAccountsEnabled) {
        setBoolean(spec.isServiceAccountsEnabled(), serviceAccountsEnabled, spec::setServiceAccountsEnabled);
    }

    private void setBoolean(Boolean current, boolean value, Consumer<Boolean> setter) {
        if (current == null || current != value) {
            setter.accept(value);
            persist();
        }
    }

    // ------------------------------------------------------------------ URIs and origins

    @Override
    public Set<String> getWebOrigins() {
        return spec.getWebOrigins() == null ? Set.of() : new HashSet<>(spec.getWebOrigins());
    }

    @Override
    public void setWebOrigins(Set<String> webOrigins) {
        spec.setWebOrigins(webOrigins == null ? null : new ArrayList<>(webOrigins));
        persist();
    }

    @Override
    public void addWebOrigin(String webOrigin) {
        List<String> origins = spec.getWebOrigins();
        if (origins == null) {
            origins = new ArrayList<>();
            spec.setWebOrigins(origins);
        }
        if (!origins.contains(webOrigin)) {
            origins.add(webOrigin);
            persist();
        }
    }

    @Override
    public void removeWebOrigin(String webOrigin) {
        if (spec.getWebOrigins() != null && spec.getWebOrigins().remove(webOrigin)) {
            persist();
        }
    }

    @Override
    public Set<String> getRedirectUris() {
        return spec.getRedirectUris() == null ? Set.of() : new HashSet<>(spec.getRedirectUris());
    }

    @Override
    public void setRedirectUris(Set<String> redirectUris) {
        spec.setRedirectUris(redirectUris == null ? null : new ArrayList<>(redirectUris));
        persist();
    }

    @Override
    public void addRedirectUri(String redirectUri) {
        List<String> uris = spec.getRedirectUris();
        if (uris == null) {
            uris = new ArrayList<>();
            spec.setRedirectUris(uris);
        }
        if (!uris.contains(redirectUri)) {
            uris.add(redirectUri);
            persist();
        }
    }

    @Override
    public void removeRedirectUri(String redirectUri) {
        if (spec.getRedirectUris() != null && spec.getRedirectUris().remove(redirectUri)) {
            persist();
        }
    }

    @Override
    public String getManagementUrl() {
        return spec.getAdminUrl();
    }

    @Override
    public void setManagementUrl(String url) {
        if (!Objects.equals(spec.getAdminUrl(), url)) {
            spec.setAdminUrl(url);
            persist();
        }
    }

    @Override
    public String getRootUrl() {
        return spec.getRootUrl();
    }

    @Override
    public void setRootUrl(String url) {
        if (!Objects.equals(spec.getRootUrl(), url)) {
            spec.setRootUrl(url);
            persist();
        }
    }

    @Override
    public String getBaseUrl() {
        return spec.getBaseUrl();
    }

    @Override
    public void setBaseUrl(String url) {
        if (!Objects.equals(spec.getBaseUrl(), url)) {
            spec.setBaseUrl(url);
            persist();
        }
    }

    // ------------------------------------------------------------------ authentication

    @Override
    public String getClientAuthenticatorType() {
        return spec.getClientAuthenticatorType();
    }

    @Override
    public void setClientAuthenticatorType(String clientAuthenticatorType) {
        if (!Objects.equals(spec.getClientAuthenticatorType(), clientAuthenticatorType)) {
            spec.setClientAuthenticatorType(clientAuthenticatorType);
            persist();
        }
    }

    @Override
    public boolean validateSecret(String secret) {
        if (secret == null || spec.getSecret() == null) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), spec.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getSecret() {
        return spec.getSecret();
    }

    @Override
    public void setSecret(String secret) {
        if (!Objects.equals(spec.getSecret(), secret)) {
            spec.setSecret(secret);
            persist();
        }
    }

    @Override
    public String getRegistrationToken() {
        return spec.getRegistrationAccessToken();
    }

    @Override
    public void setRegistrationToken(String registrationToken) {
        if (!Objects.equals(spec.getRegistrationAccessToken(), registrationToken)) {
            spec.setRegistrationAccessToken(registrationToken);
            persist();
        }
    }

    @Override
    public String getProtocol() {
        return spec.getProtocol();
    }

    @Override
    public void setProtocol(String protocol) {
        if (!Objects.equals(spec.getProtocol(), protocol)) {
            spec.setProtocol(protocol);
            persist();
            session.getKeycloakSessionFactory()
                    .publish((ClientModel.ClientProtocolUpdatedEvent) () -> ClientAdapter.this);
        }
    }

    @Override
    public int getNotBefore() {
        return spec.getNotBefore() == null ? 0 : spec.getNotBefore();
    }

    @Override
    public void setNotBefore(int notBefore) {
        if (getNotBefore() != notBefore) {
            spec.setNotBefore(notBefore);
            persist();
        }
    }

    // ------------------------------------------------------------------ attributes

    @Override
    public void setAttribute(String name, String value) {
        if (value == null || value.isBlank()) {
            removeAttribute(name);
            return;
        }
        Map<String, String> attributes = spec.getAttributes();
        if (attributes == null) {
            attributes = new HashMap<>();
            spec.setAttributes(attributes);
        }
        if (!Objects.equals(attributes.get(name), value)) {
            attributes.put(name, value);
            persist();
        }
    }

    @Override
    public void removeAttribute(String name) {
        Map<String, String> attributes = spec.getAttributes();
        if (attributes != null && attributes.remove(name) != null) {
            persist();
        }
    }

    @Override
    public String getAttribute(String name) {
        return spec.getAttributes() == null ? null : spec.getAttributes().get(name);
    }

    @Override
    public Map<String, String> getAttributes() {
        return spec.getAttributes() == null ? Map.of() : new HashMap<>(spec.getAttributes());
    }

    // ------------------------------------------------------------------ auth flow binding overrides

    @Override
    public String getAuthenticationFlowBindingOverride(String binding) {
        Map<String, String> overrides = spec.getAuthenticationFlowBindingOverrides();
        return overrides == null ? null : overrides.get(binding);
    }

    @Override
    public Map<String, String> getAuthenticationFlowBindingOverrides() {
        Map<String, String> overrides = spec.getAuthenticationFlowBindingOverrides();
        return overrides == null ? Map.of() : new HashMap<>(overrides);
    }

    @Override
    public void removeAuthenticationFlowBindingOverride(String binding) {
        Map<String, String> overrides = spec.getAuthenticationFlowBindingOverrides();
        if (overrides != null && overrides.remove(binding) != null) {
            persist();
        }
    }

    @Override
    public void setAuthenticationFlowBindingOverride(String binding, String flowId) {
        Map<String, String> overrides = spec.getAuthenticationFlowBindingOverrides();
        if (overrides == null) {
            overrides = new HashMap<>();
            spec.setAuthenticationFlowBindingOverrides(overrides);
        }
        if (!Objects.equals(overrides.get(binding), flowId)) {
            overrides.put(binding, flowId);
            persist();
        }
    }

    // ------------------------------------------------------------------ node registrations (runtime only)

    @Override
    public int getNodeReRegistrationTimeout() {
        return spec.getNodeReRegistrationTimeout() == null ? 0 : spec.getNodeReRegistrationTimeout();
    }

    @Override
    public void setNodeReRegistrationTimeout(int timeout) {
        if (getNodeReRegistrationTimeout() != timeout) {
            spec.setNodeReRegistrationTimeout(timeout);
            persist();
        }
    }

    @Override
    public Map<String, Integer> getRegisteredNodes() {
        return Map.copyOf(nodesForThisClient());
    }

    @Override
    public void registerNode(String nodeHost, int registrationTime) {
        nodesForThisClient().put(nodeHost, registrationTime);
    }

    @Override
    public void unregisterNode(String nodeHost) {
        nodesForThisClient().remove(nodeHost);
    }

    private Map<String, Integer> nodesForThisClient() {
        return registeredNodesStore.computeIfAbsent(getId(), k -> new ConcurrentHashMap<>());
    }

    // ------------------------------------------------------------------ client scopes

    @Override
    public void addClientScope(ClientScopeModel clientScope, boolean defaultScope) {
        addClientScopes(Set.of(clientScope), defaultScope);
    }

    @Override
    public void addClientScopes(Set<ClientScopeModel> clientScopes, boolean defaultScope) {
        session.clients().addClientScopes(realm, this, clientScopes, defaultScope);
    }

    @Override
    public void removeClientScope(ClientScopeModel clientScope) {
        session.clients().removeClientScope(realm, this, clientScope);
    }

    @Override
    public Map<String, ClientScopeModel> getClientScopes(boolean defaultScope) {
        return session.clients().getClientScopes(realm, this, defaultScope);
    }

    // ------------------------------------------------------------------ scope mappings

    @Override
    public Stream<RoleModel> getScopeMappingsStream() {
        return ScopeMappingSupport.stream(session, realm, spec);
    }

    @Override
    public Stream<RoleModel> getRealmScopeMappingsStream() {
        return ScopeMappingSupport.realmStream(session, realm, spec);
    }

    @Override
    public void addScopeMapping(RoleModel role) {
        ScopeMappingSupport.add(spec, role, this::persist);
    }

    @Override
    public void deleteScopeMapping(RoleModel role) {
        ScopeMappingSupport.delete(spec, role, this::persist);
    }

    @Override
    public boolean hasDirectScope(RoleModel role) {
        if (ScopeMappingSupport.containsDirectly(spec, role)) {
            return true;
        }
        return getRolesStream().anyMatch(r -> Objects.equals(r, role));
    }

    @Override
    public boolean hasScope(RoleModel role) {
        if (isFullScopeAllowed()) {
            return true;
        }
        if (ScopeMappingSupport.containsDirectly(spec, role)) {
            return true;
        }
        if (getScopeMappingsStream().anyMatch(r -> r.hasRole(role))) {
            return true;
        }
        return getRolesStream().anyMatch(r -> Objects.equals(r, role) || r.hasRole(role));
    }

    // ------------------------------------------------------------------ roles

    @Override
    public RoleModel getRole(String name) {
        return session.roles().getClientRole(this, name);
    }

    @Override
    public RoleModel addRole(String name) {
        return session.roles().addClientRole(this, name);
    }

    @Override
    public RoleModel addRole(String id, String name) {
        return session.roles().addClientRole(this, id, name);
    }

    @Override
    public boolean removeRole(RoleModel role) {
        return session.roles().removeRole(role);
    }

    @Override
    public Stream<RoleModel> getRolesStream() {
        return session.roles().getClientRolesStream(this, null, null);
    }

    @Override
    public Stream<RoleModel> getRolesStream(Integer firstResult, Integer maxResults) {
        return session.roles().getClientRolesStream(this, firstResult, maxResults);
    }

    @Override
    public Stream<RoleModel> searchForRolesStream(String search, Integer first, Integer max) {
        return session.roles().searchForClientRolesStream(this, search, first, max);
    }

    // ------------------------------------------------------------------ protocol mappers

    @Override
    public Stream<ProtocolMapperModel> getProtocolMappersStream() {
        return ProtocolMapperSupport.stream(spec);
    }

    @Override
    public ProtocolMapperModel addProtocolMapper(ProtocolMapperModel model) {
        return ProtocolMapperSupport.add(spec, model, this::persist);
    }

    @Override
    public void removeProtocolMapper(ProtocolMapperModel mapping) {
        ProtocolMapperSupport.remove(spec, mapping, this::persist);
    }

    @Override
    public void updateProtocolMapper(ProtocolMapperModel mapping) {
        ProtocolMapperSupport.update(spec, mapping, this::persist);
    }

    @Override
    public ProtocolMapperModel getProtocolMapperById(String id) {
        return ProtocolMapperSupport.getById(spec, id);
    }

    @Override
    public ProtocolMapperModel getProtocolMapperByName(String protocol, String name) {
        return ProtocolMapperSupport.getByName(spec, protocol, name);
    }

    // ------------------------------------------------------------------ identity semantics

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientModel other)) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    @Override
    public String toString() {
        return getClientId() + "@" + realm.getId();
    }
}
