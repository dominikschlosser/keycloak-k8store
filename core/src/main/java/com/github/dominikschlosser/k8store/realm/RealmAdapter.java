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

import com.github.dominikschlosser.k8store.common.ListRewrites;
import com.github.dominikschlosser.k8store.crd.ClientInitialAccessSpec;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.broker.provider.IdentityProvider;
import org.keycloak.broker.provider.IdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.common.enums.SslRequired;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.common.util.Time;
import org.keycloak.component.ComponentFactory;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.CibaConfig;
import org.keycloak.models.ClientInitialAccessModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.Constants;
import org.keycloak.models.GroupModel;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.OAuth2DeviceConfig;
import org.keycloak.models.OTPPolicy;
import org.keycloak.models.ParConfig;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionConfigModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.RequiredCredentialModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.StorageProviderRealmModel;
import org.keycloak.models.WebAuthnPolicy;
import org.keycloak.models.utils.ComponentUtil;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.AuthenticationExecutionExportRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation;
import org.keycloak.representations.idm.ComponentExportRepresentation;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * {@link RealmModel} over a {@link RealmSpec}. The adapter owns a defensive copy of the CR spec;
 * <b>every mutation persists the whole spec explicitly</b> - there are no dirty flags, no
 * write-through setters and no shared references to rely on, so nested updates (component
 * configs, flow executions, ...) can never be lost between the model and the custom resource.
 *
 * <p>Identity: the realm name is the id ({@code spec.realm}). Renaming a realm therefore moves
 * the CR to the new key; CRs of other kinds referencing the old realm name are not rewritten
 * (the same reference-staleness class as every name-based reference in this store).
 *
 * <p>Representation conventions served by this adapter:
 *
 * <ul>
 *   <li>Flow bindings ({@code browserFlow}, ...) and the {@code flowAlias}/{@code
 *       authenticatorConfig} references inside flow executions are stored by <em>alias</em>, as
 *       in realm exports; renaming a flow or config rewrites the references.
 *   <li>Flow executions live nested in their flow (standard export shape) and carry no persisted
 *       id; execution ids are derived deterministically from {@code (flow id, position)}, so
 *       every node serves the same ids for the same CR content.
 *   <li>The default role and the default client-scope lists are stored by <em>name</em>, default
 *       groups by <em>path</em>; all resolve through the session providers, so they work when the
 *       referenced area is served from the database instead of CRs.
 *   <li>Components are stored as the export tree ({@code providerType -> [component]}, children
 *       in {@code subComponents}); {@code parentId} is implied by nesting.
 *   <li>Brute-force, OTP and WebAuthn settings are first-class representation fields; settings
 *       without representation fields (OAuth 2.0 device flow, CIBA, PAR, ...) go through the
 *       generic attribute API into {@code spec.attributes}.
 * </ul>
 */
public class RealmAdapter implements StorageProviderRealmModel {

    private static final Logger LOG = Logger.getLogger(RealmAdapter.class);

    private static final String ACTION_TOKEN_LIFESPAN_PREFIX = "actionTokenGeneratedByUserLifespan.";
    private static final String ADMIN_PERMISSIONS_CLIENT_ID = "adminPermissionsClientId";
    private static final String SCIM_API_ENABLED = "scimApiEnabled";
    private static final String COMPONENT_PROVIDER_EXISTS_DISABLED = "component.provider.exists.disabled";

    protected final KeycloakSession session;
    protected final RealmSpec spec;

    private PasswordPolicy passwordPolicy;

    public RealmAdapter(KeycloakSession session, RealmSpec spec) {
        Objects.requireNonNull(spec, "spec");
        this.session = session;
        this.spec = spec;
    }

    private void persist() {
        RealmCrStore.save(spec);
    }

    // ------------------------------------------------------------------ identity

    @Override
    public String getId() {
        return spec.getRealm();
    }

    @Override
    public String getName() {
        return spec.getRealm();
    }

    @Override
    public void setName(String name) {
        String current = spec.getRealm();
        if (Objects.equals(current, name)) {
            return;
        }
        // the name is the store id: move the CR instead of mutating it in place
        LOG.warnv("Renaming realm {0} to {1}: custom resources of other kinds referencing the old"
                + " realm name are not rewritten", current, name);
        RealmCrStore.delete(current);
        spec.setRealm(name);
        spec.setId(name);
        persist();
    }

    @Override
    public String getDisplayName() {
        return spec.getDisplayName();
    }

    @Override
    public void setDisplayName(String displayName) {
        spec.setDisplayName(displayName);
        persist();
    }

    @Override
    public String getDisplayNameHtml() {
        return spec.getDisplayNameHtml();
    }

    @Override
    public void setDisplayNameHtml(String displayNameHtml) {
        spec.setDisplayNameHtml(displayNameHtml);
        persist();
    }

    // ------------------------------------------------------------------ flags

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(spec.isEnabled());
    }

    @Override
    public void setEnabled(boolean enabled) {
        spec.setEnabled(enabled);
        persist();
    }

    @Override
    public SslRequired getSslRequired() {
        String sslRequired = spec.getSslRequired();
        return sslRequired == null ? null : SslRequired.valueOf(sslRequired.toUpperCase(Locale.ROOT));
    }

    @Override
    public void setSslRequired(SslRequired sslRequired) {
        // exports carry the value lowercase
        spec.setSslRequired(sslRequired == null ? null : sslRequired.name().toLowerCase(Locale.ROOT));
        persist();
    }

    @Override
    public boolean isRegistrationAllowed() {
        return Boolean.TRUE.equals(spec.isRegistrationAllowed());
    }

    @Override
    public void setRegistrationAllowed(boolean registrationAllowed) {
        spec.setRegistrationAllowed(registrationAllowed);
        persist();
    }

    @Override
    public boolean isRegistrationEmailAsUsername() {
        return Boolean.TRUE.equals(spec.isRegistrationEmailAsUsername());
    }

    @Override
    public void setRegistrationEmailAsUsername(boolean registrationEmailAsUsername) {
        spec.setRegistrationEmailAsUsername(registrationEmailAsUsername);
        persist();
    }

    @Override
    public boolean isRememberMe() {
        return Boolean.TRUE.equals(spec.isRememberMe());
    }

    @Override
    public void setRememberMe(boolean rememberMe) {
        spec.setRememberMe(rememberMe);
        persist();
    }

    @Override
    public boolean isEditUsernameAllowed() {
        return Boolean.TRUE.equals(spec.isEditUsernameAllowed());
    }

    @Override
    public void setEditUsernameAllowed(boolean editUsernameAllowed) {
        spec.setEditUsernameAllowed(editUsernameAllowed);
        persist();
    }

    @Override
    public boolean isUserManagedAccessAllowed() {
        return Boolean.TRUE.equals(spec.isUserManagedAccessAllowed());
    }

    @Override
    public void setUserManagedAccessAllowed(boolean userManagedAccessAllowed) {
        spec.setUserManagedAccessAllowed(userManagedAccessAllowed);
        persist();
    }

    @Override
    public boolean isOrganizationsEnabled() {
        return Boolean.TRUE.equals(spec.isOrganizationsEnabled());
    }

    @Override
    public void setOrganizationsEnabled(boolean organizationsEnabled) {
        spec.setOrganizationsEnabled(organizationsEnabled);
        persist();
    }

    @Override
    public boolean isAdminPermissionsEnabled() {
        return Boolean.TRUE.equals(spec.isAdminPermissionsEnabled());
    }

    @Override
    public void setAdminPermissionsEnabled(boolean adminPermissionsEnabled) {
        spec.setAdminPermissionsEnabled(adminPermissionsEnabled);
        persist();
    }

    @Override
    public boolean isVerifiableCredentialsEnabled() {
        return Boolean.TRUE.equals(spec.isVerifiableCredentialsEnabled());
    }

    @Override
    public void setVerifiableCredentialsEnabled(boolean verifiableCredentialsEnabled) {
        spec.setVerifiableCredentialsEnabled(verifiableCredentialsEnabled);
        persist();
    }

    @Override
    public boolean isScimApiEnabled() {
        return getAttribute(SCIM_API_ENABLED, Boolean.FALSE);
    }

    @Override
    public void setScimApiEnabled(boolean enabled) {
        setAttribute(SCIM_API_ENABLED, enabled);
    }

    @Override
    public boolean isVerifyEmail() {
        return Boolean.TRUE.equals(spec.isVerifyEmail());
    }

    @Override
    public void setVerifyEmail(boolean verifyEmail) {
        spec.setVerifyEmail(verifyEmail);
        persist();
    }

    @Override
    public boolean isLoginWithEmailAllowed() {
        return Boolean.TRUE.equals(spec.isLoginWithEmailAllowed());
    }

    @Override
    public void setLoginWithEmailAllowed(boolean loginWithEmailAllowed) {
        spec.setLoginWithEmailAllowed(loginWithEmailAllowed);
        persist();
    }

    @Override
    public boolean isDuplicateEmailsAllowed() {
        return Boolean.TRUE.equals(spec.isDuplicateEmailsAllowed());
    }

    @Override
    public void setDuplicateEmailsAllowed(boolean duplicateEmailsAllowed) {
        spec.setDuplicateEmailsAllowed(duplicateEmailsAllowed);
        persist();
    }

    @Override
    public boolean isResetPasswordAllowed() {
        return Boolean.TRUE.equals(spec.isResetPasswordAllowed());
    }

    @Override
    public void setResetPasswordAllowed(boolean resetPasswordAllowed) {
        spec.setResetPasswordAllowed(resetPasswordAllowed);
        persist();
    }

    @Override
    public boolean isRevokeRefreshToken() {
        return Boolean.TRUE.equals(spec.getRevokeRefreshToken());
    }

    @Override
    public void setRevokeRefreshToken(boolean revokeRefreshToken) {
        spec.setRevokeRefreshToken(revokeRefreshToken);
        persist();
    }

    // ------------------------------------------------------------------ attributes

    private Map<String, String> attributes() {
        if (spec.getAttributes() == null) {
            spec.setAttributes(new HashMap<>());
        }
        return spec.getAttributes();
    }

    @Override
    public void setAttribute(String name, String value) {
        attributes().put(name, value);
        persist();
    }

    @Override
    public void removeAttribute(String name) {
        if (spec.getAttributes() != null && spec.getAttributes().remove(name) != null) {
            persist();
        }
    }

    @Override
    public String getAttribute(String name) {
        return spec.getAttributes() == null ? null : spec.getAttributes().get(name);
    }

    @Override
    public Map<String, String> getAttributes() {
        return spec.getAttributes() == null ? Map.of() : Map.copyOf(spec.getAttributes());
    }

    // ------------------------------------------------------------------ brute force

    @Override
    public boolean isBruteForceProtected() {
        return Boolean.TRUE.equals(spec.isBruteForceProtected());
    }

    @Override
    public void setBruteForceProtected(boolean value) {
        spec.setBruteForceProtected(value);
        persist();
    }

    @Override
    public boolean isPermanentLockout() {
        return Boolean.TRUE.equals(spec.isPermanentLockout());
    }

    @Override
    public void setPermanentLockout(boolean value) {
        spec.setPermanentLockout(value);
        persist();
    }

    @Override
    public int getMaxTemporaryLockouts() {
        return orZero(spec.getMaxTemporaryLockouts());
    }

    @Override
    public void setMaxTemporaryLockouts(int value) {
        spec.setMaxTemporaryLockouts(value);
        persist();
    }

    @Override
    public RealmRepresentation.BruteForceStrategy getBruteForceStrategy() {
        return spec.getBruteForceStrategy() == null
                ? RealmRepresentation.BruteForceStrategy.MULTIPLE
                : spec.getBruteForceStrategy();
    }

    @Override
    public void setBruteForceStrategy(RealmRepresentation.BruteForceStrategy strategy) {
        spec.setBruteForceStrategy(strategy);
        persist();
    }

    @Override
    public int getMaxFailureWaitSeconds() {
        return orZero(spec.getMaxFailureWaitSeconds());
    }

    @Override
    public void setMaxFailureWaitSeconds(int value) {
        spec.setMaxFailureWaitSeconds(value);
        persist();
    }

    @Override
    public int getWaitIncrementSeconds() {
        return orZero(spec.getWaitIncrementSeconds());
    }

    @Override
    public void setWaitIncrementSeconds(int value) {
        spec.setWaitIncrementSeconds(value);
        persist();
    }

    @Override
    public int getMinimumQuickLoginWaitSeconds() {
        return orZero(spec.getMinimumQuickLoginWaitSeconds());
    }

    @Override
    public void setMinimumQuickLoginWaitSeconds(int value) {
        spec.setMinimumQuickLoginWaitSeconds(value);
        persist();
    }

    @Override
    public long getQuickLoginCheckMilliSeconds() {
        Long value = spec.getQuickLoginCheckMilliSeconds();
        return value == null ? 0 : value;
    }

    @Override
    public void setQuickLoginCheckMilliSeconds(long value) {
        spec.setQuickLoginCheckMilliSeconds(value);
        persist();
    }

    @Override
    public int getMaxDeltaTimeSeconds() {
        return orZero(spec.getMaxDeltaTimeSeconds());
    }

    @Override
    public void setMaxDeltaTimeSeconds(int value) {
        spec.setMaxDeltaTimeSeconds(value);
        persist();
    }

    @Override
    public int getFailureFactor() {
        return orZero(spec.getFailureFactor());
    }

    @Override
    public void setFailureFactor(int failureFactor) {
        spec.setFailureFactor(failureFactor);
        persist();
    }

    @Override
    public int getMaxSecondaryAuthFailures() {
        return orZero(spec.getMaxSecondaryAuthFailures());
    }

    @Override
    public void setMaxSecondaryAuthFailures(int value) {
        spec.setMaxSecondaryAuthFailures(value);
        persist();
    }

    // ------------------------------------------------------------------ token / session lifespans

    @Override
    public String getDefaultSignatureAlgorithm() {
        return spec.getDefaultSignatureAlgorithm();
    }

    @Override
    public void setDefaultSignatureAlgorithm(String defaultSignatureAlgorithm) {
        spec.setDefaultSignatureAlgorithm(defaultSignatureAlgorithm);
        persist();
    }

    @Override
    public int getRefreshTokenMaxReuse() {
        return orZero(spec.getRefreshTokenMaxReuse());
    }

    @Override
    public void setRefreshTokenMaxReuse(int count) {
        spec.setRefreshTokenMaxReuse(count);
        persist();
    }

    @Override
    public int getSsoSessionIdleTimeout() {
        return orZero(spec.getSsoSessionIdleTimeout());
    }

    @Override
    public void setSsoSessionIdleTimeout(int seconds) {
        spec.setSsoSessionIdleTimeout(seconds);
        persist();
    }

    @Override
    public int getSsoSessionMaxLifespan() {
        return orZero(spec.getSsoSessionMaxLifespan());
    }

    @Override
    public void setSsoSessionMaxLifespan(int seconds) {
        spec.setSsoSessionMaxLifespan(seconds);
        persist();
    }

    @Override
    public int getSsoSessionIdleTimeoutRememberMe() {
        return orZero(spec.getSsoSessionIdleTimeoutRememberMe());
    }

    @Override
    public void setSsoSessionIdleTimeoutRememberMe(int seconds) {
        spec.setSsoSessionIdleTimeoutRememberMe(seconds);
        persist();
    }

    @Override
    public int getSsoSessionMaxLifespanRememberMe() {
        return orZero(spec.getSsoSessionMaxLifespanRememberMe());
    }

    @Override
    public void setSsoSessionMaxLifespanRememberMe(int seconds) {
        spec.setSsoSessionMaxLifespanRememberMe(seconds);
        persist();
    }

    @Override
    public int getOfflineSessionIdleTimeout() {
        return orZero(spec.getOfflineSessionIdleTimeout());
    }

    @Override
    public void setOfflineSessionIdleTimeout(int seconds) {
        spec.setOfflineSessionIdleTimeout(seconds);
        persist();
    }

    @Override
    public boolean isOfflineSessionMaxLifespanEnabled() {
        return Boolean.TRUE.equals(spec.getOfflineSessionMaxLifespanEnabled());
    }

    @Override
    public void setOfflineSessionMaxLifespanEnabled(boolean enabled) {
        spec.setOfflineSessionMaxLifespanEnabled(enabled);
        persist();
    }

    @Override
    public int getOfflineSessionMaxLifespan() {
        return orZero(spec.getOfflineSessionMaxLifespan());
    }

    @Override
    public void setOfflineSessionMaxLifespan(int seconds) {
        spec.setOfflineSessionMaxLifespan(seconds);
        persist();
    }

    @Override
    public int getClientSessionIdleTimeout() {
        return orZero(spec.getClientSessionIdleTimeout());
    }

    @Override
    public void setClientSessionIdleTimeout(int seconds) {
        spec.setClientSessionIdleTimeout(seconds);
        persist();
    }

    @Override
    public int getClientSessionMaxLifespan() {
        return orZero(spec.getClientSessionMaxLifespan());
    }

    @Override
    public void setClientSessionMaxLifespan(int seconds) {
        spec.setClientSessionMaxLifespan(seconds);
        persist();
    }

    @Override
    public int getClientOfflineSessionIdleTimeout() {
        return orZero(spec.getClientOfflineSessionIdleTimeout());
    }

    @Override
    public void setClientOfflineSessionIdleTimeout(int seconds) {
        spec.setClientOfflineSessionIdleTimeout(seconds);
        persist();
    }

    @Override
    public int getClientOfflineSessionMaxLifespan() {
        return orZero(spec.getClientOfflineSessionMaxLifespan());
    }

    @Override
    public void setClientOfflineSessionMaxLifespan(int seconds) {
        spec.setClientOfflineSessionMaxLifespan(seconds);
        persist();
    }

    @Override
    public int getAccessTokenLifespan() {
        return orZero(spec.getAccessTokenLifespan());
    }

    @Override
    public void setAccessTokenLifespan(int seconds) {
        spec.setAccessTokenLifespan(seconds);
        persist();
    }

    @Override
    public int getAccessTokenLifespanForImplicitFlow() {
        return orZero(spec.getAccessTokenLifespanForImplicitFlow());
    }

    @Override
    public void setAccessTokenLifespanForImplicitFlow(int seconds) {
        spec.setAccessTokenLifespanForImplicitFlow(seconds);
        persist();
    }

    @Override
    public int getAccessCodeLifespan() {
        return orZero(spec.getAccessCodeLifespan());
    }

    @Override
    public void setAccessCodeLifespan(int seconds) {
        spec.setAccessCodeLifespan(seconds);
        persist();
    }

    @Override
    public int getAccessCodeLifespanUserAction() {
        return orZero(spec.getAccessCodeLifespanUserAction());
    }

    @Override
    public void setAccessCodeLifespanUserAction(int seconds) {
        spec.setAccessCodeLifespanUserAction(seconds);
        persist();
    }

    @Override
    public int getAccessCodeLifespanLogin() {
        return orZero(spec.getAccessCodeLifespanLogin());
    }

    @Override
    public void setAccessCodeLifespanLogin(int seconds) {
        spec.setAccessCodeLifespanLogin(seconds);
        persist();
    }

    @Override
    public int getActionTokenGeneratedByAdminLifespan() {
        return orZero(spec.getActionTokenGeneratedByAdminLifespan());
    }

    @Override
    public void setActionTokenGeneratedByAdminLifespan(int seconds) {
        spec.setActionTokenGeneratedByAdminLifespan(seconds);
        persist();
    }

    @Override
    public int getActionTokenGeneratedByUserLifespan() {
        Integer value = spec.getActionTokenGeneratedByUserLifespan();
        return value == null ? getAccessCodeLifespanUserAction() : value;
    }

    @Override
    public void setActionTokenGeneratedByUserLifespan(int seconds) {
        spec.setActionTokenGeneratedByUserLifespan(seconds);
        persist();
    }

    @Override
    public int getActionTokenGeneratedByUserLifespan(String actionTokenType) {
        if (actionTokenType == null) {
            return getActionTokenGeneratedByUserLifespan();
        }
        Integer value = getAttribute(ACTION_TOKEN_LIFESPAN_PREFIX + actionTokenType, (Integer) null);
        return value == null ? getActionTokenGeneratedByUserLifespan() : value;
    }

    @Override
    public void setActionTokenGeneratedByUserLifespan(String actionTokenType, Integer seconds) {
        if (actionTokenType != null && !actionTokenType.isEmpty() && seconds != null) {
            setAttribute(ACTION_TOKEN_LIFESPAN_PREFIX + actionTokenType, seconds);
        }
    }

    @Override
    public Map<String, Integer> getUserActionTokenLifespans() {
        if (spec.getAttributes() == null) {
            return Map.of();
        }
        return spec.getAttributes().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(ACTION_TOKEN_LIFESPAN_PREFIX) && entry.getValue() != null)
                .collect(Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().substring(ACTION_TOKEN_LIFESPAN_PREFIX.length()),
                        entry -> Integer.valueOf(entry.getValue())));
    }

    // ------------------------------------------------------------------ policies

    @Override
    public Stream<RequiredCredentialModel> getRequiredCredentialsStream() {
        Set<String> types = spec.getRequiredCredentials();
        return types == null
                ? Stream.empty()
                : types.stream().map(RequiredCredentialModel.BUILT_IN::get).filter(Objects::nonNull);
    }

    @Override
    public void addRequiredCredential(String cred) {
        if (!RequiredCredentialModel.BUILT_IN.containsKey(cred)) {
            throw new IllegalArgumentException("Unknown credential type " + cred);
        }
        Set<String> types = spec.getRequiredCredentials();
        if (types != null && types.contains(cred)) {
            throw new ModelDuplicateException("A Required Credential with given type already exists.");
        }
        if (types == null) {
            types = new LinkedHashSet<>();
            spec.setRequiredCredentials(types);
        }
        types.add(cred);
        persist();
    }

    @Override
    public void updateRequiredCredentials(Set<String> credentials) {
        Set<String> types = spec.getRequiredCredentials();
        if (types == null) {
            types = new LinkedHashSet<>();
            spec.setRequiredCredentials(types);
        }
        for (String cred : credentials) {
            if (!RequiredCredentialModel.BUILT_IN.containsKey(cred)) {
                throw new IllegalArgumentException("Unknown credential type " + cred);
            }
            types.add(cred);
        }
        persist();
    }

    @Override
    public PasswordPolicy getPasswordPolicy() {
        if (passwordPolicy == null) {
            passwordPolicy = PasswordPolicy.parse(session, spec.getPasswordPolicy());
        }
        return passwordPolicy;
    }

    @Override
    public void setPasswordPolicy(PasswordPolicy policy) {
        this.passwordPolicy = policy;
        spec.setPasswordPolicy(policy.toString());
        persist();
    }

    @Override
    public OTPPolicy getOTPPolicy() {
        if (spec.getOtpPolicyType() == null) {
            return OTPPolicy.DEFAULT_POLICY;
        }
        OTPPolicy policy = new OTPPolicy();
        policy.setType(spec.getOtpPolicyType());
        policy.setAlgorithm(spec.getOtpPolicyAlgorithm());
        policy.setInitialCounter(orZero(spec.getOtpPolicyInitialCounter()));
        policy.setDigits(orZero(spec.getOtpPolicyDigits()));
        policy.setLookAheadWindow(orZero(spec.getOtpPolicyLookAheadWindow()));
        policy.setPeriod(orZero(spec.getOtpPolicyPeriod()));
        policy.setCodeReusable(spec.isOtpPolicyCodeReusable() == null
                ? OTPPolicy.DEFAULT_IS_REUSABLE
                : spec.isOtpPolicyCodeReusable());
        return policy;
    }

    @Override
    public void setOTPPolicy(OTPPolicy policy) {
        spec.setOtpPolicyType(policy.getType());
        spec.setOtpPolicyAlgorithm(policy.getAlgorithm());
        spec.setOtpPolicyInitialCounter(policy.getInitialCounter());
        spec.setOtpPolicyDigits(policy.getDigits());
        spec.setOtpPolicyLookAheadWindow(policy.getLookAheadWindow());
        spec.setOtpPolicyPeriod(policy.getPeriod());
        spec.setOtpPolicyCodeReusable(policy.isCodeReusable());
        persist();
    }

    @Override
    public WebAuthnPolicy getWebAuthnPolicy() {
        WebAuthnPolicy policy = new WebAuthnPolicy();
        policy.setRpEntityName(orDefault(
                spec.getWebAuthnPolicyRpEntityName(), Constants.DEFAULT_WEBAUTHN_POLICY_RP_ENTITY_NAME));
        List<String> algorithms = spec.getWebAuthnPolicySignatureAlgorithms();
        policy.setSignatureAlgorithm(algorithms == null || algorithms.isEmpty()
                ? List.of(Constants.DEFAULT_WEBAUTHN_POLICY_SIGNATURE_ALGORITHMS.split(","))
                : algorithms);
        policy.setRpId(orDefault(spec.getWebAuthnPolicyRpId(), ""));
        policy.setAttestationConveyancePreference(orDefault(
                spec.getWebAuthnPolicyAttestationConveyancePreference(),
                Constants.DEFAULT_WEBAUTHN_POLICY_NOT_SPECIFIED));
        policy.setAuthenticatorAttachment(orDefault(
                spec.getWebAuthnPolicyAuthenticatorAttachment(), Constants.DEFAULT_WEBAUTHN_POLICY_NOT_SPECIFIED));
        policy.setRequireResidentKey(orDefault(
                spec.getWebAuthnPolicyRequireResidentKey(), Constants.DEFAULT_WEBAUTHN_POLICY_NOT_SPECIFIED));
        policy.setUserVerificationRequirement(orDefault(
                spec.getWebAuthnPolicyUserVerificationRequirement(), Constants.DEFAULT_WEBAUTHN_POLICY_NOT_SPECIFIED));
        policy.setCreateTimeout(orZero(spec.getWebAuthnPolicyCreateTimeout()));
        policy.setAvoidSameAuthenticatorRegister(
                Boolean.TRUE.equals(spec.isWebAuthnPolicyAvoidSameAuthenticatorRegister()));
        List<String> aaguids = spec.getWebAuthnPolicyAcceptableAaguids();
        policy.setAcceptableAaguids(aaguids == null ? new ArrayList<>() : aaguids);
        List<String> origins = spec.getWebAuthnPolicyExtraOrigins();
        policy.setExtraOrigins(origins == null ? new ArrayList<>() : origins);
        return policy;
    }

    @Override
    public void setWebAuthnPolicy(WebAuthnPolicy policy) {
        spec.setWebAuthnPolicyRpEntityName(policy.getRpEntityName());
        spec.setWebAuthnPolicySignatureAlgorithms(policy.getSignatureAlgorithm());
        spec.setWebAuthnPolicyRpId(policy.getRpId());
        spec.setWebAuthnPolicyAttestationConveyancePreference(policy.getAttestationConveyancePreference());
        spec.setWebAuthnPolicyAuthenticatorAttachment(policy.getAuthenticatorAttachment());
        spec.setWebAuthnPolicyRequireResidentKey(policy.getRequireResidentKey());
        spec.setWebAuthnPolicyUserVerificationRequirement(policy.getUserVerificationRequirement());
        spec.setWebAuthnPolicyCreateTimeout(policy.getCreateTimeout());
        spec.setWebAuthnPolicyAvoidSameAuthenticatorRegister(policy.isAvoidSameAuthenticatorRegister());
        spec.setWebAuthnPolicyAcceptableAaguids(policy.getAcceptableAaguids());
        spec.setWebAuthnPolicyExtraOrigins(policy.getExtraOrigins());
        persist();
    }

    @Override
    public WebAuthnPolicy getWebAuthnPolicyPasswordless() {
        WebAuthnPolicy policy = new WebAuthnPolicy();
        policy.setRpEntityName(orDefault(
                spec.getWebAuthnPolicyPasswordlessRpEntityName(), Constants.DEFAULT_WEBAUTHN_POLICY_RP_ENTITY_NAME));
        List<String> algorithms = spec.getWebAuthnPolicyPasswordlessSignatureAlgorithms();
        policy.setSignatureAlgorithm(algorithms == null || algorithms.isEmpty()
                ? List.of(Constants.DEFAULT_WEBAUTHN_POLICY_SIGNATURE_ALGORITHMS.split(","))
                : algorithms);
        policy.setRpId(orDefault(spec.getWebAuthnPolicyPasswordlessRpId(), ""));
        policy.setAttestationConveyancePreference(orDefault(
                spec.getWebAuthnPolicyPasswordlessAttestationConveyancePreference(),
                Constants.DEFAULT_WEBAUTHN_POLICY_NOT_SPECIFIED));
        policy.setAuthenticatorAttachment(orDefault(
                spec.getWebAuthnPolicyPasswordlessAuthenticatorAttachment(),
                Constants.DEFAULT_WEBAUTHN_POLICY_NOT_SPECIFIED));
        policy.setRequireResidentKey(orDefault(
                spec.getWebAuthnPolicyPasswordlessRequireResidentKey(),
                Constants.DEFAULT_WEBAUTHN_POLICY_NOT_SPECIFIED));
        policy.setUserVerificationRequirement(orDefault(
                spec.getWebAuthnPolicyPasswordlessUserVerificationRequirement(),
                Constants.DEFAULT_WEBAUTHN_POLICY_NOT_SPECIFIED));
        policy.setCreateTimeout(orZero(spec.getWebAuthnPolicyPasswordlessCreateTimeout()));
        policy.setAvoidSameAuthenticatorRegister(
                Boolean.TRUE.equals(spec.isWebAuthnPolicyPasswordlessAvoidSameAuthenticatorRegister()));
        List<String> aaguids = spec.getWebAuthnPolicyPasswordlessAcceptableAaguids();
        policy.setAcceptableAaguids(aaguids == null ? new ArrayList<>() : aaguids);
        List<String> origins = spec.getWebAuthnPolicyPasswordlessExtraOrigins();
        policy.setExtraOrigins(origins == null ? new ArrayList<>() : origins);
        policy.setPasskeysEnabled(spec.getWebAuthnPolicyPasswordlessPasskeysEnabled());
        return policy;
    }

    @Override
    public void setWebAuthnPolicyPasswordless(WebAuthnPolicy policy) {
        spec.setWebAuthnPolicyPasswordlessRpEntityName(policy.getRpEntityName());
        spec.setWebAuthnPolicyPasswordlessSignatureAlgorithms(policy.getSignatureAlgorithm());
        spec.setWebAuthnPolicyPasswordlessRpId(policy.getRpId());
        spec.setWebAuthnPolicyPasswordlessAttestationConveyancePreference(
                policy.getAttestationConveyancePreference());
        spec.setWebAuthnPolicyPasswordlessAuthenticatorAttachment(policy.getAuthenticatorAttachment());
        spec.setWebAuthnPolicyPasswordlessRequireResidentKey(policy.getRequireResidentKey());
        spec.setWebAuthnPolicyPasswordlessUserVerificationRequirement(policy.getUserVerificationRequirement());
        spec.setWebAuthnPolicyPasswordlessCreateTimeout(policy.getCreateTimeout());
        spec.setWebAuthnPolicyPasswordlessAvoidSameAuthenticatorRegister(policy.isAvoidSameAuthenticatorRegister());
        spec.setWebAuthnPolicyPasswordlessAcceptableAaguids(policy.getAcceptableAaguids());
        spec.setWebAuthnPolicyPasswordlessExtraOrigins(policy.getExtraOrigins());
        spec.setWebAuthnPolicyPasswordlessPasskeysEnabled(policy.isPasskeysEnabled());
        persist();
    }

    @Override
    public OAuth2DeviceConfig getOAuth2DeviceConfig() {
        return new OAuth2DeviceConfig(this);
    }

    @Override
    public CibaConfig getCibaPolicy() {
        return new CibaConfig(this);
    }

    @Override
    public ParConfig getParPolicy() {
        return new ParConfig(this);
    }

    // ------------------------------------------------------------------ delegating: roles / clients / groups / scopes

    @Override
    public RoleModel getRoleById(String id) {
        return session.roles().getRoleById(this, id);
    }

    @Override
    public RoleModel getRole(String name) {
        return session.roles().getRealmRole(this, name);
    }

    @Override
    public RoleModel addRole(String name) {
        return session.roles().addRealmRole(this, name);
    }

    @Override
    public RoleModel addRole(String id, String name) {
        return session.roles().addRealmRole(this, id, name);
    }

    @Override
    public boolean removeRole(RoleModel role) {
        return session.roles().removeRole(role);
    }

    @Override
    public Stream<RoleModel> getRolesStream() {
        return session.roles().getRealmRolesStream(this);
    }

    @Override
    public Stream<RoleModel> getRolesStream(Integer firstResult, Integer maxResults) {
        return session.roles().getRealmRolesStream(this, firstResult, maxResults);
    }

    @Override
    public Stream<RoleModel> searchForRolesStream(String search, Integer first, Integer max) {
        return session.roles().searchForRolesStream(this, search, first, max);
    }

    @Override
    public Stream<ClientModel> getClientsStream() {
        return session.clients().getClientsStream(this);
    }

    @Override
    public Stream<ClientModel> getClientsStream(Integer firstResult, Integer maxResults) {
        return session.clients().getClientsStream(this, firstResult, maxResults);
    }

    @Override
    public Long getClientsCount() {
        return session.clients().getClientsCount(this);
    }

    @Override
    public Stream<ClientModel> getAlwaysDisplayInConsoleClientsStream() {
        return session.clients().getAlwaysDisplayInConsoleClientsStream(this);
    }

    @Override
    public ClientModel addClient(String name) {
        return session.clients().addClient(this, name);
    }

    @Override
    public ClientModel addClient(String id, String clientId) {
        return session.clients().addClient(this, id, clientId);
    }

    @Override
    public boolean removeClient(String id) {
        return session.clients().removeClient(this, id);
    }

    @Override
    public ClientModel getClientById(String id) {
        return session.clients().getClientById(this, id);
    }

    @Override
    public ClientModel getClientByClientId(String clientId) {
        return session.clients().getClientByClientId(this, clientId);
    }

    @Override
    public Stream<ClientModel> searchClientByClientIdStream(String clientId, Integer firstResult, Integer maxResults) {
        return session.clients().searchClientsByClientIdStream(this, clientId, firstResult, maxResults);
    }

    @Override
    public Stream<ClientModel> searchClientByAttributes(
            Map<String, String> attributes, Integer firstResult, Integer maxResults) {
        return session.clients().searchClientsByAttributes(this, attributes, firstResult, maxResults);
    }

    @Override
    public Stream<ClientModel> searchClientByAuthenticationFlowBindingOverrides(
            Map<String, String> overrides, Integer firstResult, Integer maxResults) {
        return session.clients()
                .searchClientsByAuthenticationFlowBindingOverrides(this, overrides, firstResult, maxResults);
    }

    @Override
    public GroupModel createGroup(String id, String name, GroupModel toParent) {
        return session.groups().createGroup(this, id, name, toParent);
    }

    @Override
    public GroupModel getGroupById(String id) {
        return session.groups().getGroupById(this, id);
    }

    @Override
    public Stream<GroupModel> getGroupsStream() {
        return session.groups().getGroupsStream(this);
    }

    @Override
    public Long getGroupsCount(Boolean onlyTopGroups) {
        return session.groups().getGroupsCount(this, onlyTopGroups);
    }

    @Override
    public Long getGroupsCountByNameContaining(String search) {
        return session.groups().getGroupsCountByNameContaining(this, search);
    }

    @Override
    public Stream<GroupModel> getTopLevelGroupsStream() {
        return session.groups().getTopLevelGroupsStream(this);
    }

    @Override
    public Stream<GroupModel> getTopLevelGroupsStream(Integer first, Integer max) {
        return session.groups().getTopLevelGroupsStream(this, first, max);
    }

    @Override
    public boolean removeGroup(GroupModel group) {
        return session.groups().removeGroup(this, group);
    }

    @Override
    public void moveGroup(GroupModel group, GroupModel toParent) {
        session.groups().moveGroup(this, group, toParent);
    }

    @Override
    public Stream<ClientScopeModel> getClientScopesStream() {
        return session.clientScopes().getClientScopesStream(this);
    }

    @Override
    public ClientScopeModel addClientScope(String name) {
        return session.clientScopes().addClientScope(this, name);
    }

    @Override
    public ClientScopeModel addClientScope(String id, String name) {
        return session.clientScopes().addClientScope(this, id, name);
    }

    @Override
    public boolean removeClientScope(String id) {
        return session.clientScopes().removeClientScope(this, id);
    }

    @Override
    public ClientScopeModel getClientScopeById(String id) {
        return session.clientScopes().getClientScopeById(this, id);
    }

    // ------------------------------------------------------------------ default role / groups / client scopes

    @Override
    public RoleModel getDefaultRole() {
        RoleRepresentation defaultRole = spec.getDefaultRole();
        return defaultRole == null ? null : session.roles().getRealmRole(this, defaultRole.getName());
    }

    @Override
    public void setDefaultRole(RoleModel role) {
        // stored by name; per-kind role CRs are the storage, so only the reference lives here
        RoleRepresentation reference = new RoleRepresentation();
        reference.setId(role.getId());
        reference.setName(role.getName());
        spec.setDefaultRole(reference);
        persist();
    }

    /**
     * Role rename cascade: rewrite the realm default-role reference when it points at the renamed
     * role. Realm roles are stored by name and their id equals the name, so both fields move to
     * the new name.
     */
    public void renameDefaultRole(String oldName, String newName) {
        RoleRepresentation defaultRole = spec.getDefaultRole();
        if (defaultRole == null || !oldName.equals(defaultRole.getName())) {
            return;
        }
        defaultRole.setId(newName);
        defaultRole.setName(newName);
        persist();
    }

    @Override
    public Stream<GroupModel> getDefaultGroupsStream() {
        List<String> paths = spec.getDefaultGroups();
        return paths == null
                ? Stream.empty()
                : List.copyOf(paths).stream()
                        .map(path -> KeycloakModelUtils.findGroupByPath(session, this, path))
                        .filter(Objects::nonNull);
    }

    @Override
    public void addDefaultGroup(GroupModel group) {
        String path = KeycloakModelUtils.buildGroupPath(group);
        List<String> paths = spec.getDefaultGroups();
        if (paths == null) {
            paths = new ArrayList<>();
            spec.setDefaultGroups(paths);
        }
        if (!paths.contains(path)) {
            paths.add(path);
            persist();
        }
    }

    @Override
    public void removeDefaultGroup(GroupModel group) {
        List<String> paths = spec.getDefaultGroups();
        if (paths != null && paths.remove(KeycloakModelUtils.buildGroupPath(group))) {
            persist();
        }
    }

    @Override
    public void addDefaultClientScope(ClientScopeModel clientScope, boolean defaultScope) {
        List<String> names = defaultScope ? spec.getDefaultDefaultClientScopes() : spec.getDefaultOptionalClientScopes();
        if (names == null) {
            names = new ArrayList<>();
            if (defaultScope) {
                spec.setDefaultDefaultClientScopes(names);
            } else {
                spec.setDefaultOptionalClientScopes(names);
            }
        }
        if (!names.contains(clientScope.getName())) {
            names.add(clientScope.getName());
            persist();
        }
    }

    @Override
    public void removeDefaultClientScope(ClientScopeModel clientScope) {
        boolean changed = spec.getDefaultDefaultClientScopes() != null
                && spec.getDefaultDefaultClientScopes().remove(clientScope.getName());
        changed |= spec.getDefaultOptionalClientScopes() != null
                && spec.getDefaultOptionalClientScopes().remove(clientScope.getName());
        if (changed) {
            persist();
        }
    }

    /**
     * Client-scope rename cascade: swap the old scope name for the new one in both realm-default
     * lists, preserving list position so the default-scope ordering stays stable.
     */
    public void renameDefaultClientScope(String oldName, String newName) {
        boolean changed = ListRewrites.replaceInList(spec.getDefaultDefaultClientScopes(), oldName, newName);
        changed |= ListRewrites.replaceInList(spec.getDefaultOptionalClientScopes(), oldName, newName);
        if (changed) {
            persist();
        }
    }

    @Override
    public Stream<ClientScopeModel> getDefaultClientScopesStream(boolean defaultScope) {
        List<String> names = defaultScope ? spec.getDefaultDefaultClientScopes() : spec.getDefaultOptionalClientScopes();
        if (names == null || names.isEmpty()) {
            return Stream.empty();
        }
        // resolve by name so this works when the client-scope area is database-backed
        Set<String> wanted = Set.copyOf(names);
        return session.clientScopes().getClientScopesStream(this)
                .filter(scope -> wanted.contains(scope.getName()));
    }

    // ------------------------------------------------------------------ smtp / themes / headers

    @Override
    public Map<String, String> getSmtpConfig() {
        return spec.getSmtpServer() == null ? Map.of() : Map.copyOf(spec.getSmtpServer());
    }

    @Override
    public void setSmtpConfig(Map<String, String> smtpConfig) {
        spec.setSmtpServer(smtpConfig == null ? null : new HashMap<>(smtpConfig));
        persist();
    }

    @Override
    public Map<String, String> getBrowserSecurityHeaders() {
        return spec.getBrowserSecurityHeaders() == null ? Map.of() : Map.copyOf(spec.getBrowserSecurityHeaders());
    }

    @Override
    public void setBrowserSecurityHeaders(Map<String, String> headers) {
        spec.setBrowserSecurityHeaders(headers == null ? null : new HashMap<>(headers));
        persist();
    }

    @Override
    public String getLoginTheme() {
        return spec.getLoginTheme();
    }

    @Override
    public void setLoginTheme(String name) {
        spec.setLoginTheme(name);
        persist();
    }

    @Override
    public String getAccountTheme() {
        return spec.getAccountTheme();
    }

    @Override
    public void setAccountTheme(String name) {
        spec.setAccountTheme(name);
        persist();
    }

    @Override
    public String getAdminTheme() {
        return spec.getAdminTheme();
    }

    @Override
    public void setAdminTheme(String name) {
        spec.setAdminTheme(name);
        persist();
    }

    @Override
    public String getEmailTheme() {
        return spec.getEmailTheme();
    }

    @Override
    public void setEmailTheme(String name) {
        spec.setEmailTheme(name);
        persist();
    }

    @Override
    public int getNotBefore() {
        return orZero(spec.getNotBefore());
    }

    @Override
    public void setNotBefore(int notBefore) {
        spec.setNotBefore(notBefore);
        persist();
    }

    // ------------------------------------------------------------------ events

    @Override
    public boolean isEventsEnabled() {
        return Boolean.TRUE.equals(spec.isEventsEnabled());
    }

    @Override
    public void setEventsEnabled(boolean enabled) {
        spec.setEventsEnabled(enabled);
        persist();
    }

    @Override
    public long getEventsExpiration() {
        Long expiration = spec.getEventsExpiration();
        return expiration == null ? 0 : expiration;
    }

    @Override
    public void setEventsExpiration(long expiration) {
        spec.setEventsExpiration(expiration);
        persist();
    }

    @Override
    public Stream<String> getEventsListenersStream() {
        List<String> listeners = spec.getEventsListeners();
        return listeners == null ? Stream.empty() : List.copyOf(listeners).stream();
    }

    @Override
    public void setEventsListeners(Set<String> listeners) {
        spec.setEventsListeners(listeners == null ? null : new ArrayList<>(listeners));
        persist();
    }

    @Override
    public Stream<String> getEnabledEventTypesStream() {
        List<String> types = spec.getEnabledEventTypes();
        return types == null ? Stream.empty() : List.copyOf(types).stream();
    }

    @Override
    public void setEnabledEventTypes(Set<String> enabledEventTypes) {
        spec.setEnabledEventTypes(enabledEventTypes == null ? null : new ArrayList<>(enabledEventTypes));
        persist();
    }

    @Override
    public boolean isAdminEventsEnabled() {
        return Boolean.TRUE.equals(spec.isAdminEventsEnabled());
    }

    @Override
    public void setAdminEventsEnabled(boolean enabled) {
        spec.setAdminEventsEnabled(enabled);
        persist();
    }

    @Override
    public boolean isAdminEventsDetailsEnabled() {
        return Boolean.TRUE.equals(spec.isAdminEventsDetailsEnabled());
    }

    @Override
    public void setAdminEventsDetailsEnabled(boolean enabled) {
        spec.setAdminEventsDetailsEnabled(enabled);
        persist();
    }

    // ------------------------------------------------------------------ admin clients

    @Override
    public ClientModel getMasterAdminClient() {
        String masterAdminClientId = spec.getMasterAdminClient();
        if (masterAdminClientId == null) {
            return null;
        }
        RealmModel masterRealm = getName().equals(Config.getAdminRealm())
                ? this
                : session.realms().getRealmByName(Config.getAdminRealm());
        return masterRealm == null ? null : session.clients().getClientById(masterRealm, masterAdminClientId);
    }

    @Override
    public void setMasterAdminClient(ClientModel client) {
        spec.setMasterAdminClient(client == null ? null : client.getId());
        persist();
    }

    @Override
    public ClientModel getAdminPermissionsClient() {
        String id = getAttribute(ADMIN_PERMISSIONS_CLIENT_ID);
        return id == null ? null : session.clients().getClientById(this, id);
    }

    @Override
    public void setAdminPermissionsClient(ClientModel client) {
        setAttribute(ADMIN_PERMISSIONS_CLIENT_ID, client.getId());
    }

    // ------------------------------------------------------------------ localization

    @Override
    public boolean isInternationalizationEnabled() {
        return Boolean.TRUE.equals(spec.isInternationalizationEnabled());
    }

    @Override
    public void setInternationalizationEnabled(boolean enabled) {
        spec.setInternationalizationEnabled(enabled);
        persist();
    }

    @Override
    public Stream<String> getSupportedLocalesStream() {
        Set<String> locales = spec.getSupportedLocales();
        return locales == null ? Stream.empty() : Set.copyOf(locales).stream();
    }

    @Override
    public void setSupportedLocales(Set<String> locales) {
        spec.setSupportedLocales(locales == null ? null : new LinkedHashSet<>(locales));
        persist();
    }

    @Override
    public String getDefaultLocale() {
        return spec.getDefaultLocale();
    }

    @Override
    public void setDefaultLocale(String locale) {
        spec.setDefaultLocale(locale);
        persist();
    }

    private Map<String, Map<String, String>> localizationTexts() {
        if (spec.getLocalizationTexts() == null) {
            spec.setLocalizationTexts(new HashMap<>());
        }
        return spec.getLocalizationTexts();
    }

    @Override
    public void createOrUpdateRealmLocalizationTexts(String locale, Map<String, String> texts) {
        Map<String, String> merged = new HashMap<>(localizationTexts().getOrDefault(locale, Map.of()));
        merged.putAll(texts);
        localizationTexts().put(locale, merged);
        persist();
    }

    @Override
    public boolean removeRealmLocalizationTexts(String locale) {
        if (locale == null || spec.getLocalizationTexts() == null) {
            return false;
        }
        boolean removed = spec.getLocalizationTexts().remove(locale) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    @Override
    public Map<String, Map<String, String>> getRealmLocalizationTexts() {
        return spec.getLocalizationTexts() == null ? Map.of() : spec.getLocalizationTexts();
    }

    @Override
    public Map<String, String> getRealmLocalizationTextsByLocale(String locale) {
        Map<String, String> texts = spec.getLocalizationTexts() == null ? null : spec.getLocalizationTexts().get(locale);
        return texts == null ? Map.of() : texts;
    }

    // ------------------------------------------------------------------ authentication flows

    private List<AuthenticationFlowRepresentation> flowReps() {
        return spec.getAuthenticationFlows() == null ? List.of() : spec.getAuthenticationFlows();
    }

    private AuthenticationFlowRepresentation flowRepById(String flowId) {
        if (flowId == null) {
            return null;
        }
        return flowReps().stream().filter(flow -> flowId.equals(flow.getId())).findFirst().orElse(null);
    }

    private AuthenticationFlowRepresentation flowRepByAlias(String alias) {
        if (alias == null) {
            return null;
        }
        return flowReps().stream().filter(flow -> alias.equals(flow.getAlias())).findFirst().orElse(null);
    }

    private static AuthenticationFlowModel toFlowModel(AuthenticationFlowRepresentation rep) {
        AuthenticationFlowModel model = new AuthenticationFlowModel();
        model.setId(rep.getId());
        model.setAlias(rep.getAlias());
        model.setDescription(rep.getDescription());
        model.setProviderId(rep.getProviderId());
        model.setTopLevel(rep.isTopLevel());
        model.setBuiltIn(rep.isBuiltIn());
        return model;
    }

    @Override
    public Stream<AuthenticationFlowModel> getAuthenticationFlowsStream() {
        return List.copyOf(flowReps()).stream().map(RealmAdapter::toFlowModel);
    }

    @Override
    public AuthenticationFlowModel getFlowByAlias(String alias) {
        AuthenticationFlowRepresentation rep = flowRepByAlias(alias);
        return rep == null ? null : toFlowModel(rep);
    }

    @Override
    public AuthenticationFlowModel getAuthenticationFlowById(String flowId) {
        AuthenticationFlowRepresentation rep = flowRepById(flowId);
        return rep == null ? null : toFlowModel(rep);
    }

    @Override
    public AuthenticationFlowModel addAuthenticationFlow(AuthenticationFlowModel model) {
        if (model.getId() != null && flowRepById(model.getId()) != null) {
            throw new ModelDuplicateException("An AuthenticationFlow with given id already exists");
        }
        if (model.getId() == null) {
            model.setId(KeycloakModelUtils.generateId());
        }
        AuthenticationFlowRepresentation rep = new AuthenticationFlowRepresentation();
        rep.setId(model.getId());
        rep.setAlias(model.getAlias());
        rep.setDescription(model.getDescription());
        rep.setProviderId(model.getProviderId());
        rep.setTopLevel(model.isTopLevel());
        rep.setBuiltIn(model.isBuiltIn());
        rep.setAuthenticationExecutions(new ArrayList<>());
        if (spec.getAuthenticationFlows() == null) {
            spec.setAuthenticationFlows(new ArrayList<>());
        }
        spec.getAuthenticationFlows().add(rep);
        persist();
        return model;
    }

    @Override
    public void updateAuthenticationFlow(AuthenticationFlowModel model) {
        AuthenticationFlowRepresentation rep = flowRepById(model.getId());
        if (rep == null) {
            return;
        }
        String oldAlias = rep.getAlias();
        rep.setAlias(model.getAlias());
        rep.setDescription(model.getDescription());
        rep.setProviderId(model.getProviderId());
        rep.setTopLevel(model.isTopLevel());
        rep.setBuiltIn(model.isBuiltIn());
        if (oldAlias != null && !oldAlias.equals(model.getAlias())) {
            renameFlowReferences(oldAlias, model.getAlias());
        }
        persist();
    }

    /** Flow bindings, sub-flow references and IdP flow references are alias-based: follow a rename. */
    private void renameFlowReferences(String oldAlias, String newAlias) {
        if (oldAlias.equals(spec.getBrowserFlow())) {
            spec.setBrowserFlow(newAlias);
        }
        if (oldAlias.equals(spec.getRegistrationFlow())) {
            spec.setRegistrationFlow(newAlias);
        }
        if (oldAlias.equals(spec.getDirectGrantFlow())) {
            spec.setDirectGrantFlow(newAlias);
        }
        if (oldAlias.equals(spec.getResetCredentialsFlow())) {
            spec.setResetCredentialsFlow(newAlias);
        }
        if (oldAlias.equals(spec.getClientAuthenticationFlow())) {
            spec.setClientAuthenticationFlow(newAlias);
        }
        if (oldAlias.equals(spec.getDockerAuthenticationFlow())) {
            spec.setDockerAuthenticationFlow(newAlias);
        }
        if (oldAlias.equals(spec.getFirstBrokerLoginFlow())) {
            spec.setFirstBrokerLoginFlow(newAlias);
        }
        for (AuthenticationFlowRepresentation flow : flowReps()) {
            if (flow.getAuthenticationExecutions() == null) {
                continue;
            }
            for (AuthenticationExecutionExportRepresentation execution : flow.getAuthenticationExecutions()) {
                if (oldAlias.equals(execution.getFlowAlias())) {
                    execution.setFlowAlias(newAlias);
                }
            }
        }
        for (IdentityProviderRepresentation idp : idpReps()) {
            if (oldAlias.equals(idp.getFirstBrokerLoginFlowAlias())) {
                idp.setFirstBrokerLoginFlowAlias(newAlias);
            }
            if (oldAlias.equals(idp.getPostBrokerLoginFlowAlias())) {
                idp.setPostBrokerLoginFlowAlias(newAlias);
            }
        }
    }

    @Override
    public void removeAuthenticationFlow(AuthenticationFlowModel model) {
        if (spec.getAuthenticationFlows() != null
                && spec.getAuthenticationFlows().removeIf(flow -> Objects.equals(flow.getId(), model.getId()))) {
            persist();
        }
    }

    // ------------------------------------------------------------------ flow bindings

    @Override
    public AuthenticationFlowModel getBrowserFlow() {
        return getFlowByAlias(spec.getBrowserFlow());
    }

    @Override
    public void setBrowserFlow(AuthenticationFlowModel flow) {
        spec.setBrowserFlow(flow.getAlias());
        persist();
    }

    @Override
    public AuthenticationFlowModel getRegistrationFlow() {
        return getFlowByAlias(spec.getRegistrationFlow());
    }

    @Override
    public void setRegistrationFlow(AuthenticationFlowModel flow) {
        spec.setRegistrationFlow(flow.getAlias());
        persist();
    }

    @Override
    public AuthenticationFlowModel getDirectGrantFlow() {
        return getFlowByAlias(spec.getDirectGrantFlow());
    }

    @Override
    public void setDirectGrantFlow(AuthenticationFlowModel flow) {
        spec.setDirectGrantFlow(flow.getAlias());
        persist();
    }

    @Override
    public AuthenticationFlowModel getResetCredentialsFlow() {
        return getFlowByAlias(spec.getResetCredentialsFlow());
    }

    @Override
    public void setResetCredentialsFlow(AuthenticationFlowModel flow) {
        spec.setResetCredentialsFlow(flow.getAlias());
        persist();
    }

    @Override
    public AuthenticationFlowModel getClientAuthenticationFlow() {
        return getFlowByAlias(spec.getClientAuthenticationFlow());
    }

    @Override
    public void setClientAuthenticationFlow(AuthenticationFlowModel flow) {
        spec.setClientAuthenticationFlow(flow.getAlias());
        persist();
    }

    @Override
    public AuthenticationFlowModel getDockerAuthenticationFlow() {
        return getFlowByAlias(spec.getDockerAuthenticationFlow());
    }

    @Override
    public void setDockerAuthenticationFlow(AuthenticationFlowModel flow) {
        spec.setDockerAuthenticationFlow(flow.getAlias());
        persist();
    }

    @Override
    public AuthenticationFlowModel getFirstBrokerLoginFlow() {
        return getFlowByAlias(spec.getFirstBrokerLoginFlow());
    }

    @Override
    public void setFirstBrokerLoginFlow(AuthenticationFlowModel flow) {
        spec.setFirstBrokerLoginFlow(flow.getAlias());
        persist();
    }

    // ------------------------------------------------------------------ flow executions

    /**
     * Executions are stored inside their flow in the standard export shape, which carries no id.
     * Ids are derived deterministically from the flow id and the execution's position, so every
     * node computes the same id for the same CR content. Positions are stable under updates and
     * appends; removing an execution shifts the ids of the ones after it - acceptable, since the
     * admin API re-reads the flow after each mutation.
     */
    private static String executionId(String flowId, int index) {
        return UUID.nameUUIDFromBytes(
                        ("k8store-execution" + K8sStorageBackend.KEY_SEPARATOR + flowId + K8sStorageBackend.KEY_SEPARATOR + index).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private List<AuthenticationExecutionExportRepresentation> executionsOf(AuthenticationFlowRepresentation flow) {
        if (flow.getAuthenticationExecutions() == null) {
            flow.setAuthenticationExecutions(new ArrayList<>());
        }
        return flow.getAuthenticationExecutions();
    }

    private AuthenticationExecutionModel toExecutionModel(
            AuthenticationFlowRepresentation flow, int index, AuthenticationExecutionExportRepresentation rep) {
        AuthenticationExecutionModel model = new AuthenticationExecutionModel();
        model.setId(executionId(flow.getId(), index));
        model.setParentFlow(flow.getId());
        model.setAuthenticator(rep.getAuthenticator());
        model.setRequirement(rep.getRequirement() == null
                ? null
                : AuthenticationExecutionModel.Requirement.valueOf(rep.getRequirement()));
        model.setPriority(rep.getPriority() == null ? 0 : rep.getPriority());
        model.setAuthenticatorFlow(rep.isAuthenticatorFlow());
        if (rep.getFlowAlias() != null) {
            AuthenticationFlowRepresentation subFlow = flowRepByAlias(rep.getFlowAlias());
            model.setFlowId(subFlow == null ? null : subFlow.getId());
        }
        if (rep.getAuthenticatorConfig() != null) {
            AuthenticatorConfigRepresentation config = configRepByAlias(rep.getAuthenticatorConfig());
            model.setAuthenticatorConfig(config == null ? rep.getAuthenticatorConfig() : config.getId());
        }
        return model;
    }

    private AuthenticationExecutionExportRepresentation toExecutionRep(AuthenticationExecutionModel model) {
        AuthenticationExecutionExportRepresentation rep = new AuthenticationExecutionExportRepresentation();
        rep.setAuthenticator(model.getAuthenticator());
        rep.setRequirement(model.getRequirement() == null ? null : model.getRequirement().name());
        rep.setPriority(model.getPriority());
        rep.setAuthenticatorFlow(model.isAuthenticatorFlow());
        if (model.getFlowId() != null) {
            AuthenticationFlowRepresentation subFlow = flowRepById(model.getFlowId());
            rep.setFlowAlias(subFlow == null ? null : subFlow.getAlias());
        }
        if (model.getAuthenticatorConfig() != null) {
            AuthenticatorConfigRepresentation config = configRepById(model.getAuthenticatorConfig());
            rep.setAuthenticatorConfig(config == null ? model.getAuthenticatorConfig() : config.getAlias());
        }
        return rep;
    }

    @Override
    public Stream<AuthenticationExecutionModel> getAuthenticationExecutionsStream(String flowId) {
        AuthenticationFlowRepresentation flow = flowRepById(flowId);
        if (flow == null || flow.getAuthenticationExecutions() == null) {
            return Stream.empty();
        }
        List<AuthenticationExecutionExportRepresentation> executions =
                List.copyOf(flow.getAuthenticationExecutions());
        List<AuthenticationExecutionModel> models = new ArrayList<>(executions.size());
        for (int i = 0; i < executions.size(); i++) {
            models.add(toExecutionModel(flow, i, executions.get(i)));
        }
        return models.stream().sorted(AuthenticationExecutionModel.ExecutionComparator.SINGLETON);
    }

    @Override
    public AuthenticationExecutionModel getAuthenticationExecutionById(String id) {
        if (id == null) {
            return null;
        }
        for (AuthenticationFlowRepresentation flow : flowReps()) {
            List<AuthenticationExecutionExportRepresentation> executions = flow.getAuthenticationExecutions();
            if (executions == null) {
                continue;
            }
            for (int i = 0; i < executions.size(); i++) {
                if (id.equals(executionId(flow.getId(), i))) {
                    return toExecutionModel(flow, i, executions.get(i));
                }
            }
        }
        return null;
    }

    @Override
    public AuthenticationExecutionModel getAuthenticationExecutionByFlowId(String flowId) {
        if (flowId == null) {
            return null;
        }
        for (AuthenticationFlowRepresentation flow : flowReps()) {
            List<AuthenticationExecutionExportRepresentation> executions = flow.getAuthenticationExecutions();
            if (executions == null) {
                continue;
            }
            for (int i = 0; i < executions.size(); i++) {
                AuthenticationExecutionModel model = toExecutionModel(flow, i, executions.get(i));
                if (flowId.equals(model.getFlowId())) {
                    return model;
                }
            }
        }
        return null;
    }

    @Override
    public AuthenticationExecutionModel addAuthenticatorExecution(AuthenticationExecutionModel model) {
        AuthenticationFlowRepresentation flow = flowRepById(model.getParentFlow());
        if (flow == null) {
            throw new IllegalArgumentException("Parent flow not found: " + model.getParentFlow());
        }
        List<AuthenticationExecutionExportRepresentation> executions = executionsOf(flow);
        executions.add(toExecutionRep(model));
        model.setId(executionId(flow.getId(), executions.size() - 1));
        persist();
        return model;
    }

    @Override
    public void updateAuthenticatorExecution(AuthenticationExecutionModel model) {
        for (AuthenticationFlowRepresentation flow : flowReps()) {
            List<AuthenticationExecutionExportRepresentation> executions = flow.getAuthenticationExecutions();
            if (executions == null) {
                continue;
            }
            for (int i = 0; i < executions.size(); i++) {
                if (Objects.equals(model.getId(), executionId(flow.getId(), i))) {
                    executions.set(i, toExecutionRep(model));
                    persist();
                    return;
                }
            }
        }
    }

    @Override
    public void removeAuthenticatorExecution(AuthenticationExecutionModel model) {
        for (AuthenticationFlowRepresentation flow : flowReps()) {
            List<AuthenticationExecutionExportRepresentation> executions = flow.getAuthenticationExecutions();
            if (executions == null) {
                continue;
            }
            for (int i = 0; i < executions.size(); i++) {
                if (Objects.equals(model.getId(), executionId(flow.getId(), i))) {
                    executions.remove(i);
                    persist();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ authenticator configs

    private List<AuthenticatorConfigRepresentation> configReps() {
        return spec.getAuthenticatorConfig() == null ? List.of() : spec.getAuthenticatorConfig();
    }

    private AuthenticatorConfigRepresentation configRepById(String id) {
        if (id == null) {
            return null;
        }
        return configReps().stream().filter(config -> id.equals(config.getId())).findFirst().orElse(null);
    }

    private AuthenticatorConfigRepresentation configRepByAlias(String alias) {
        if (alias == null) {
            return null;
        }
        return configReps().stream().filter(config -> alias.equals(config.getAlias())).findFirst().orElse(null);
    }

    private static AuthenticatorConfigModel toConfigModel(AuthenticatorConfigRepresentation rep) {
        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setId(rep.getId());
        model.setAlias(rep.getAlias());
        model.setConfig(rep.getConfig() == null ? new HashMap<>() : new HashMap<>(rep.getConfig()));
        return model;
    }

    @Override
    public Stream<AuthenticatorConfigModel> getAuthenticatorConfigsStream() {
        return List.copyOf(configReps()).stream().map(RealmAdapter::toConfigModel);
    }

    @Override
    public AuthenticatorConfigModel addAuthenticatorConfig(AuthenticatorConfigModel model) {
        if (model.getId() != null && configRepById(model.getId()) != null) {
            throw new ModelDuplicateException("An Authenticator Config with given id already exists.");
        }
        if (model.getId() == null) {
            model.setId(KeycloakModelUtils.generateId());
        }
        AuthenticatorConfigRepresentation rep = new AuthenticatorConfigRepresentation();
        rep.setId(model.getId());
        rep.setAlias(model.getAlias());
        rep.setConfig(model.getConfig() == null ? null : new HashMap<>(model.getConfig()));
        if (spec.getAuthenticatorConfig() == null) {
            spec.setAuthenticatorConfig(new ArrayList<>());
        }
        spec.getAuthenticatorConfig().add(rep);
        persist();
        return model;
    }

    @Override
    public void updateAuthenticatorConfig(AuthenticatorConfigModel model) {
        AuthenticatorConfigRepresentation rep = configRepById(model.getId());
        if (rep == null) {
            return;
        }
        String oldAlias = rep.getAlias();
        rep.setAlias(model.getAlias());
        rep.setConfig(model.getConfig() == null ? null : new HashMap<>(model.getConfig()));
        if (oldAlias != null && !oldAlias.equals(model.getAlias())) {
            // execution references are alias-based: follow the rename
            for (AuthenticationFlowRepresentation flow : flowReps()) {
                if (flow.getAuthenticationExecutions() == null) {
                    continue;
                }
                for (AuthenticationExecutionExportRepresentation execution : flow.getAuthenticationExecutions()) {
                    if (oldAlias.equals(execution.getAuthenticatorConfig())) {
                        execution.setAuthenticatorConfig(model.getAlias());
                    }
                }
            }
        }
        persist();
    }

    @Override
    public void removeAuthenticatorConfig(AuthenticatorConfigModel model) {
        if (spec.getAuthenticatorConfig() != null
                && spec.getAuthenticatorConfig().removeIf(config -> Objects.equals(config.getId(), model.getId()))) {
            persist();
        }
    }

    @Override
    public AuthenticatorConfigModel getAuthenticatorConfigById(String id) {
        AuthenticatorConfigRepresentation rep = configRepById(id);
        return rep == null ? null : toConfigModel(rep);
    }

    @Override
    public AuthenticatorConfigModel getAuthenticatorConfigByAlias(String alias) {
        AuthenticatorConfigRepresentation rep = configRepByAlias(alias);
        return rep == null ? null : toConfigModel(rep);
    }

    // ------------------------------------------------------------------ required actions

    private List<RequiredActionProviderRepresentation> requiredActionReps() {
        return spec.getRequiredActions() == null ? List.of() : spec.getRequiredActions();
    }

    /** Required actions are keyed by alias (unique per realm); the model id is the alias. */
    private RequiredActionProviderRepresentation requiredActionRep(String aliasOrId) {
        if (aliasOrId == null) {
            return null;
        }
        return requiredActionReps().stream()
                .filter(action -> aliasOrId.equals(action.getAlias()))
                .findFirst()
                .orElse(null);
    }

    private static RequiredActionProviderModel toRequiredActionModel(RequiredActionProviderRepresentation rep) {
        RequiredActionProviderModel model = new RequiredActionProviderModel();
        model.setId(rep.getAlias());
        model.setAlias(rep.getAlias());
        model.setName(rep.getName());
        model.setProviderId(rep.getProviderId());
        model.setEnabled(rep.isEnabled());
        model.setDefaultAction(rep.isDefaultAction());
        model.setPriority(rep.getPriority());
        model.setConfig(rep.getConfig() == null ? new HashMap<>() : new HashMap<>(rep.getConfig()));
        return model;
    }

    @Override
    public Stream<RequiredActionProviderModel> getRequiredActionProvidersStream() {
        return List.copyOf(requiredActionReps()).stream()
                .map(RealmAdapter::toRequiredActionModel)
                .sorted(RequiredActionProviderModel.RequiredActionComparator.SINGLETON);
    }

    @Override
    public RequiredActionProviderModel addRequiredActionProvider(RequiredActionProviderModel model) {
        if (requiredActionRep(model.getAlias()) != null) {
            throw new ModelDuplicateException("A Required Action Provider with given alias already exists.");
        }
        RequiredActionProviderRepresentation rep = new RequiredActionProviderRepresentation();
        rep.setAlias(model.getAlias());
        rep.setName(model.getName());
        rep.setProviderId(model.getProviderId());
        rep.setEnabled(model.isEnabled());
        rep.setDefaultAction(model.isDefaultAction());
        rep.setPriority(model.getPriority());
        rep.setConfig(model.getConfig() == null || model.getConfig().isEmpty()
                ? null
                : new HashMap<>(model.getConfig()));
        if (spec.getRequiredActions() == null) {
            spec.setRequiredActions(new ArrayList<>());
        }
        spec.getRequiredActions().add(rep);
        persist();
        model.setId(model.getAlias());
        return model;
    }

    @Override
    public void updateRequiredActionProvider(RequiredActionProviderModel model) {
        RequiredActionProviderRepresentation rep = requiredActionRep(model.getId());
        if (rep == null) {
            return;
        }
        rep.setAlias(model.getAlias());
        rep.setName(model.getName());
        rep.setProviderId(model.getProviderId());
        rep.setEnabled(model.isEnabled());
        rep.setDefaultAction(model.isDefaultAction());
        rep.setPriority(model.getPriority());
        rep.setConfig(model.getConfig() == null || model.getConfig().isEmpty()
                ? null
                : new HashMap<>(model.getConfig()));
        persist();
    }

    @Override
    public void removeRequiredActionProvider(RequiredActionProviderModel model) {
        if (spec.getRequiredActions() != null
                && spec.getRequiredActions().removeIf(action -> Objects.equals(action.getAlias(), model.getId()))) {
            persist();
        }
    }

    @Override
    public RequiredActionProviderModel getRequiredActionProviderById(String id) {
        RequiredActionProviderRepresentation rep = requiredActionRep(id);
        return rep == null ? null : toRequiredActionModel(rep);
    }

    @Override
    public RequiredActionProviderModel getRequiredActionProviderByAlias(String alias) {
        RequiredActionProviderRepresentation rep = requiredActionRep(alias);
        return rep == null ? null : toRequiredActionModel(rep);
    }

    /** The config of a required action is stored on the action itself; the config id is the alias. */
    private static RequiredActionConfigModel toRequiredActionConfigModel(RequiredActionProviderRepresentation rep) {
        RequiredActionConfigModel model = new RequiredActionConfigModel();
        model.setId(rep.getAlias());
        model.setAlias(rep.getAlias());
        model.setProviderId(rep.getProviderId());
        model.setConfig(rep.getConfig() == null ? new HashMap<>() : new HashMap<>(rep.getConfig()));
        return model;
    }

    @Override
    public RequiredActionConfigModel getRequiredActionConfigById(String id) {
        RequiredActionProviderRepresentation rep = requiredActionRep(id);
        return rep == null ? null : toRequiredActionConfigModel(rep);
    }

    @Override
    public RequiredActionConfigModel getRequiredActionConfigByAlias(String alias) {
        RequiredActionProviderRepresentation rep = requiredActionRep(alias);
        return rep == null ? null : toRequiredActionConfigModel(rep);
    }

    @Override
    public void removeRequiredActionProviderConfig(RequiredActionConfigModel model) {
        RequiredActionProviderRepresentation rep = requiredActionRep(model.getId());
        if (rep != null && rep.getConfig() != null) {
            rep.setConfig(null);
            persist();
        }
    }

    @Override
    public void updateRequiredActionConfig(RequiredActionConfigModel model) {
        RequiredActionProviderRepresentation rep = requiredActionRep(model.getId());
        if (rep == null) {
            return;
        }
        rep.setConfig(model.getConfig() == null || model.getConfig().isEmpty()
                ? null
                : new HashMap<>(model.getConfig()));
        persist();
    }

    @Override
    public Stream<RequiredActionConfigModel> getRequiredActionConfigsStream() {
        return List.copyOf(requiredActionReps()).stream()
                .filter(rep -> rep.getConfig() != null && !rep.getConfig().isEmpty())
                .map(RealmAdapter::toRequiredActionConfigModel);
    }

    // ------------------------------------------------------------------ identity providers

    private List<IdentityProviderRepresentation> idpReps() {
        return spec.getIdentityProviders() == null ? List.of() : spec.getIdentityProviders();
    }

    /**
     * Brokers subclass {@code IdentityProviderModel} with typed accessors over the config map
     * (e.g. the OIDC config); the factory of the stored {@code providerId} knows the right
     * class. Falls back to the plain model for unknown providers.
     */
    private IdentityProviderModel emptyModelFor(String providerId) {
        IdentityProviderFactory<?> factory = Stream.concat(
                        session.getKeycloakSessionFactory().getProviderFactoriesStream(IdentityProvider.class),
                        session.getKeycloakSessionFactory().getProviderFactoriesStream(SocialIdentityProvider.class))
                .filter(candidate -> Objects.equals(candidate.getId(), providerId))
                .map(IdentityProviderFactory.class::cast)
                .findFirst()
                .orElse(null);
        if (factory == null) {
            LOG.debugv("No identity provider factory for provider id {0}; using the generic model", providerId);
            return new IdentityProviderModel();
        }
        return factory.createConfig();
    }

    private IdentityProviderModel toIdpModel(IdentityProviderRepresentation rep) {
        IdentityProviderModel model = emptyModelFor(rep.getProviderId());
        model.setInternalId(rep.getInternalId());
        model.setAlias(rep.getAlias());
        model.setDisplayName(rep.getDisplayName());
        model.setProviderId(rep.getProviderId());
        model.setEnabled(rep.isEnabled());
        model.setTrustEmail(rep.isTrustEmail());
        model.setStoreToken(rep.isStoreToken());
        model.setLinkOnly(rep.isLinkOnly());
        model.setHideOnLogin(rep.isHideOnLogin());
        model.setAddReadTokenRoleOnCreate(rep.isAddReadTokenRoleOnCreate());
        model.setAuthenticateByDefault(rep.isAuthenticateByDefault());
        model.setOrganizationId(rep.getOrganizationId());
        model.setConfig(rep.getConfig() == null ? new HashMap<>() : new HashMap<>(rep.getConfig()));
        AuthenticationFlowRepresentation firstBroker = flowRepByAlias(rep.getFirstBrokerLoginFlowAlias());
        model.setFirstBrokerLoginFlowId(firstBroker == null ? null : firstBroker.getId());
        AuthenticationFlowRepresentation postBroker = flowRepByAlias(rep.getPostBrokerLoginFlowAlias());
        model.setPostBrokerLoginFlowId(postBroker == null ? null : postBroker.getId());
        return model;
    }

    private IdentityProviderRepresentation toIdpRep(IdentityProviderModel model) {
        IdentityProviderRepresentation rep = new IdentityProviderRepresentation();
        rep.setInternalId(model.getInternalId());
        rep.setAlias(model.getAlias());
        rep.setDisplayName(model.getDisplayName());
        rep.setProviderId(model.getProviderId());
        rep.setEnabled(model.isEnabled());
        rep.setTrustEmail(model.isTrustEmail());
        rep.setStoreToken(model.isStoreToken());
        rep.setLinkOnly(model.isLinkOnly());
        rep.setHideOnLogin(model.isHideOnLogin());
        rep.setAddReadTokenRoleOnCreate(model.isAddReadTokenRoleOnCreate());
        rep.setAuthenticateByDefault(model.isAuthenticateByDefault());
        rep.setOrganizationId(model.getOrganizationId());
        rep.setConfig(model.getConfig() == null ? null : new HashMap<>(model.getConfig()));
        AuthenticationFlowRepresentation firstBroker = flowRepById(model.getFirstBrokerLoginFlowId());
        rep.setFirstBrokerLoginFlowAlias(firstBroker == null ? null : firstBroker.getAlias());
        AuthenticationFlowRepresentation postBroker = flowRepById(model.getPostBrokerLoginFlowId());
        rep.setPostBrokerLoginFlowAlias(postBroker == null ? null : postBroker.getAlias());
        return rep;
    }

    @Override
    public boolean isIdentityFederationEnabled() {
        return !idpReps().isEmpty();
    }

    @Override
    public Stream<IdentityProviderModel> getIdentityProvidersStream() {
        return List.copyOf(idpReps()).stream().map(this::toIdpModel);
    }

    @Override
    public IdentityProviderModel getIdentityProviderByAlias(String alias) {
        return idpReps().stream()
                .filter(rep -> Objects.equals(rep.getAlias(), alias))
                .findFirst()
                .map(this::toIdpModel)
                .orElse(null);
    }

    @Override
    public void addIdentityProvider(IdentityProviderModel model) {
        if (getIdentityProviderByAlias(model.getAlias()) != null) {
            throw new ModelDuplicateException("An Identity Provider with given alias already exists.");
        }
        if (model.getInternalId() == null) {
            model.setInternalId(KeycloakModelUtils.generateId());
        }
        if (spec.getIdentityProviders() == null) {
            spec.setIdentityProviders(new ArrayList<>());
        }
        spec.getIdentityProviders().add(toIdpRep(model));
        persist();
    }

    @Override
    public void removeIdentityProviderByAlias(String alias) {
        IdentityProviderModel model = getIdentityProviderByAlias(alias);
        if (model == null) {
            return;
        }
        spec.getIdentityProviders().removeIf(rep -> Objects.equals(rep.getAlias(), alias));
        // mappers of a removed identity provider are cascaded out with it
        if (spec.getIdentityProviderMappers() != null) {
            spec.getIdentityProviderMappers().removeIf(mapper -> Objects.equals(mapper.getIdentityProviderAlias(), alias));
        }
        persist();
        session.getKeycloakSessionFactory().publish(new RealmModel.IdentityProviderRemovedEvent() {
            @Override
            public RealmModel getRealm() {
                return RealmAdapter.this;
            }

            @Override
            public IdentityProviderModel getRemovedIdentityProvider() {
                return model;
            }

            @Override
            public KeycloakSession getKeycloakSession() {
                return session;
            }
        });
    }

    @Override
    public void updateIdentityProvider(IdentityProviderModel model) {
        List<IdentityProviderRepresentation> reps = spec.getIdentityProviders();
        if (reps == null) {
            return;
        }
        for (int i = 0; i < reps.size(); i++) {
            if (Objects.equals(reps.get(i).getInternalId(), model.getInternalId())) {
                reps.set(i, toIdpRep(model));
                persist();
                break;
            }
        }
        session.getKeycloakSessionFactory().publish(new RealmModel.IdentityProviderUpdatedEvent() {
            @Override
            public RealmModel getRealm() {
                return RealmAdapter.this;
            }

            @Override
            public IdentityProviderModel getUpdatedIdentityProvider() {
                return model;
            }

            @Override
            public KeycloakSession getKeycloakSession() {
                return session;
            }
        });
    }

    // ------------------------------------------------------------------ identity provider mappers

    private List<IdentityProviderMapperRepresentation> idpMapperReps() {
        return spec.getIdentityProviderMappers() == null ? List.of() : spec.getIdentityProviderMappers();
    }

    private static IdentityProviderMapperModel toIdpMapperModel(IdentityProviderMapperRepresentation rep) {
        IdentityProviderMapperModel model = new IdentityProviderMapperModel();
        model.setId(rep.getId());
        model.setName(rep.getName());
        model.setIdentityProviderAlias(rep.getIdentityProviderAlias());
        model.setIdentityProviderMapper(rep.getIdentityProviderMapper());
        model.setConfig(rep.getConfig() == null ? new HashMap<>() : new HashMap<>(rep.getConfig()));
        return model;
    }

    private static IdentityProviderMapperRepresentation toIdpMapperRep(IdentityProviderMapperModel model) {
        IdentityProviderMapperRepresentation rep = new IdentityProviderMapperRepresentation();
        rep.setId(model.getId());
        rep.setName(model.getName());
        rep.setIdentityProviderAlias(model.getIdentityProviderAlias());
        rep.setIdentityProviderMapper(model.getIdentityProviderMapper());
        rep.setConfig(model.getConfig() == null ? null : new HashMap<>(model.getConfig()));
        return rep;
    }

    @Override
    public Stream<IdentityProviderMapperModel> getIdentityProviderMappersStream() {
        return List.copyOf(idpMapperReps()).stream().map(RealmAdapter::toIdpMapperModel);
    }

    @Override
    public Stream<IdentityProviderMapperModel> getIdentityProviderMappersByAliasStream(String brokerAlias) {
        return List.copyOf(idpMapperReps()).stream()
                .filter(rep -> Objects.equals(rep.getIdentityProviderAlias(), brokerAlias))
                .map(RealmAdapter::toIdpMapperModel);
    }

    @Override
    public IdentityProviderMapperModel addIdentityProviderMapper(IdentityProviderMapperModel model) {
        if (model.getId() != null && getIdentityProviderMapperById(model.getId()) != null) {
            throw new ModelDuplicateException("An IdentityProviderMapper with given id already exists");
        }
        if (model.getId() == null) {
            model.setId(KeycloakModelUtils.generateId());
        }
        if (spec.getIdentityProviderMappers() == null) {
            spec.setIdentityProviderMappers(new ArrayList<>());
        }
        spec.getIdentityProviderMappers().add(toIdpMapperRep(model));
        persist();
        return model;
    }

    @Override
    public void removeIdentityProviderMapper(IdentityProviderMapperModel model) {
        if (spec.getIdentityProviderMappers() != null
                && spec.getIdentityProviderMappers().removeIf(rep -> Objects.equals(rep.getId(), model.getId()))) {
            persist();
        }
    }

    @Override
    public void updateIdentityProviderMapper(IdentityProviderMapperModel model) {
        List<IdentityProviderMapperRepresentation> reps = spec.getIdentityProviderMappers();
        if (reps == null) {
            return;
        }
        for (int i = 0; i < reps.size(); i++) {
            if (Objects.equals(reps.get(i).getId(), model.getId())) {
                reps.set(i, toIdpMapperRep(model));
                persist();
                return;
            }
        }
    }

    @Override
    public IdentityProviderMapperModel getIdentityProviderMapperById(String id) {
        if (id == null) {
            return null;
        }
        return idpMapperReps().stream()
                .filter(rep -> id.equals(rep.getId()))
                .findFirst()
                .map(RealmAdapter::toIdpMapperModel)
                .orElse(null);
    }

    @Override
    public IdentityProviderMapperModel getIdentityProviderMapperByName(String brokerAlias, String name) {
        return idpMapperReps().stream()
                .filter(rep -> Objects.equals(rep.getIdentityProviderAlias(), brokerAlias)
                        && Objects.equals(rep.getName(), name))
                .findFirst()
                .map(RealmAdapter::toIdpMapperModel)
                .orElse(null);
    }

    // ------------------------------------------------------------------ components

    /**
     * Components are stored as the export tree: {@code providerType -> [component]} at the top
     * level, children nested in {@code subComponents} keyed by their provider type. The flat
     * model view (with {@code parentId}) is derived from the nesting on read and rebuilt on
     * every mutation.
     */
    private List<ComponentModel> componentModels() {
        List<ComponentModel> result = new ArrayList<>();
        if (spec.getComponents() != null) {
            collectComponents(spec.getComponents(), getId(), result);
        }
        return result;
    }

    private void collectComponents(
            MultivaluedHashMap<String, ComponentExportRepresentation> components,
            String parentId,
            List<ComponentModel> out) {
        for (Map.Entry<String, List<ComponentExportRepresentation>> entry : components.entrySet()) {
            for (ComponentExportRepresentation rep : entry.getValue()) {
                ComponentModel model = new ComponentModel();
                model.setId(rep.getId());
                model.setName(rep.getName());
                model.setProviderId(rep.getProviderId());
                model.setProviderType(entry.getKey());
                model.setSubType(rep.getSubType());
                model.setParentId(parentId);
                model.setConfig(rep.getConfig() == null
                        ? new MultivaluedHashMap<>()
                        : new MultivaluedHashMap<>(rep.getConfig()));
                out.add(model);
                if (rep.getSubComponents() != null && !rep.getSubComponents().isEmpty()) {
                    collectComponents(rep.getSubComponents(), rep.getId(), out);
                }
            }
        }
    }

    private void persistComponents(List<ComponentModel> all) {
        Map<String, ComponentExportRepresentation> byId = new LinkedHashMap<>();
        for (ComponentModel model : all) {
            ComponentExportRepresentation rep = new ComponentExportRepresentation();
            rep.setId(model.getId());
            rep.setName(model.getName());
            rep.setProviderId(model.getProviderId());
            rep.setSubType(model.getSubType());
            rep.setConfig(model.getConfig() == null
                    ? null
                    : new MultivaluedHashMap<>(model.getConfig()));
            byId.put(model.getId(), rep);
        }
        MultivaluedHashMap<String, ComponentExportRepresentation> root = new MultivaluedHashMap<>();
        for (ComponentModel model : all) {
            ComponentExportRepresentation rep = byId.get(model.getId());
            String parentId = model.getParentId();
            if (parentId != null && !parentId.equals(getId()) && byId.containsKey(parentId)) {
                ComponentExportRepresentation parent = byId.get(parentId);
                if (parent.getSubComponents() == null) {
                    parent.setSubComponents(new MultivaluedHashMap<>());
                }
                parent.getSubComponents().add(model.getProviderType(), rep);
            } else {
                if (parentId != null && !parentId.equals(getId())) {
                    LOG.debugv("Component {0} has unknown parent {1}; storing it at the top level",
                            model.getId(), parentId);
                }
                root.add(model.getProviderType(), rep);
            }
        }
        spec.setComponents(root);
        persist();
    }

    @Override
    public ComponentModel addComponentModel(ComponentModel model) {
        model = importComponentModel(model);
        ComponentUtil.notifyCreated(session, this, model);
        return model;
    }

    @Override
    public ComponentModel importComponentModel(ComponentModel model) {
        try {
            ComponentFactory<?, ?> componentFactory = ComponentUtil.getComponentFactory(session, model);
            if (componentFactory == null) {
                throw new IllegalArgumentException("Invalid component type");
            }
            componentFactory.validateConfiguration(session, this, model);
        } catch (IllegalArgumentException | ComponentValidationException e) {
            if (System.getProperty(COMPONENT_PROVIDER_EXISTS_DISABLED) == null) {
                throw e;
            }
        }
        List<ComponentModel> all = componentModels();
        if (model.getId() == null) {
            model.setId(KeycloakModelUtils.generateId());
        } else if (all.removeIf(existing -> model.getId().equals(existing.getId()))) {
            LOG.warnv("Replacing existing component with id {0}", model.getId());
        }
        if (model.getParentId() == null) {
            model.setParentId(getId());
        }
        all.add(model);
        persistComponents(all);
        return model;
    }

    @Override
    public void updateComponent(ComponentModel component) {
        ComponentUtil.getComponentFactory(session, component).validateConfiguration(session, this, component);
        List<ComponentModel> all = componentModels();
        for (int i = 0; i < all.size(); i++) {
            if (Objects.equals(all.get(i).getId(), component.getId())) {
                ComponentModel old = all.get(i);
                all.set(i, component);
                // rebuild + persist the spec before notifying: listeners may re-read the realm
                persistComponents(all);
                ComponentUtil.notifyUpdated(session, this, old, component);
                return;
            }
        }
    }

    @Override
    public void removeComponent(ComponentModel component) {
        List<ComponentModel> all = componentModels();
        if (all.stream().noneMatch(existing -> Objects.equals(existing.getId(), component.getId()))) {
            return;
        }
        session.users().preRemove(this, component);
        ComponentUtil.notifyPreRemove(session, this, component);
        removeComponents(component.getId());
        all = componentModels();
        all.removeIf(existing -> Objects.equals(existing.getId(), component.getId()));
        persistComponents(all);
    }

    @Override
    public void removeComponents(String parentId) {
        List<ComponentModel> all = componentModels();
        List<ComponentModel> children = all.stream()
                .filter(component -> Objects.equals(parentId, component.getParentId()))
                .collect(Collectors.toList());
        if (children.isEmpty()) {
            return;
        }
        for (ComponentModel child : children) {
            session.users().preRemove(this, child);
            ComponentUtil.notifyPreRemove(session, this, child);
        }
        Set<String> removedIds = children.stream().map(ComponentModel::getId).collect(Collectors.toSet());
        all.removeIf(component -> removedIds.contains(component.getId()));
        persistComponents(all);
    }

    @Override
    public Stream<ComponentModel> getComponentsStream() {
        return componentModels().stream();
    }

    @Override
    public Stream<ComponentModel> getComponentsStream(String parentId) {
        return componentModels().stream().filter(component -> Objects.equals(parentId, component.getParentId()));
    }

    @Override
    public Stream<ComponentModel> getComponentsStream(String parentId, String providerType) {
        return componentModels().stream()
                .filter(component -> Objects.equals(parentId, component.getParentId()))
                .filter(component -> Objects.equals(providerType, component.getProviderType()));
    }

    @Override
    public ComponentModel getComponent(String id) {
        return componentModels().stream()
                .filter(component -> Objects.equals(id, component.getId()))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------ client initial access

    private List<ClientInitialAccessSpec> clientInitialAccesses() {
        if (spec.getClientInitialAccesses() == null) {
            spec.setClientInitialAccesses(new ArrayList<>());
        }
        return spec.getClientInitialAccesses();
    }

    private static ClientInitialAccessModel toClientInitialAccessModel(ClientInitialAccessSpec entry) {
        ClientInitialAccessModel model = new ClientInitialAccessModel();
        model.setId(entry.getId());
        model.setTimestamp(entry.getTimestamp() == null ? 0 : entry.getTimestamp());
        model.setExpiration(entry.getExpiration() == null ? 0 : entry.getExpiration());
        model.setCount(entry.getCount() == null ? 0 : entry.getCount());
        model.setRemainingCount(entry.getRemainingCount() == null ? 0 : entry.getRemainingCount());
        return model;
    }

    @Override
    public ClientInitialAccessModel createClientInitialAccessModel(int expiration, int count) {
        ClientInitialAccessSpec entry = new ClientInitialAccessSpec();
        entry.setId(KeycloakModelUtils.generateId());
        entry.setTimestamp(Time.currentTime());
        entry.setExpiration(expiration);
        entry.setCount(count);
        entry.setRemainingCount(count);
        clientInitialAccesses().add(entry);
        persist();
        return toClientInitialAccessModel(entry);
    }

    @Override
    public ClientInitialAccessModel getClientInitialAccessModel(String id) {
        if (spec.getClientInitialAccesses() == null) {
            return null;
        }
        return spec.getClientInitialAccesses().stream()
                .filter(entry -> Objects.equals(entry.getId(), id))
                .findFirst()
                .map(RealmAdapter::toClientInitialAccessModel)
                .orElse(null);
    }

    @Override
    public void removeClientInitialAccessModel(String id) {
        if (spec.getClientInitialAccesses() != null
                && spec.getClientInitialAccesses().removeIf(entry -> Objects.equals(entry.getId(), id))) {
            persist();
        }
    }

    @Override
    public Stream<ClientInitialAccessModel> getClientInitialAccesses() {
        return spec.getClientInitialAccesses() == null
                ? Stream.empty()
                : List.copyOf(spec.getClientInitialAccesses()).stream().map(RealmAdapter::toClientInitialAccessModel);
    }

    @Override
    public void decreaseRemainingCount(ClientInitialAccessModel model) {
        if (spec.getClientInitialAccesses() == null) {
            return;
        }
        spec.getClientInitialAccesses().stream()
                .filter(entry -> Objects.equals(entry.getId(), model.getId()))
                .findFirst()
                .ifPresent(entry -> {
                    entry.setRemainingCount(model.getRemainingCount() - 1);
                    persist();
                });
    }

    // ------------------------------------------------------------------ object identity

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RealmModel that)) {
            return false;
        }
        return Objects.equals(that.getId(), getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    @Override
    public String toString() {
        return String.format("%s@%08x", getId(), System.identityHashCode(this));
    }

    // ------------------------------------------------------------------ helpers

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
