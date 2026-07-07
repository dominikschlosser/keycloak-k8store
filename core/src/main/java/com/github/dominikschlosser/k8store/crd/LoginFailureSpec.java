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
package com.github.dominikschlosser.k8store.crd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Spec of a {@code KeycloakLoginFailure} custom resource: the brute-force-protection counters of
 * one (realm, user) pair — the user id is the store id, so there is at most one CR per user and
 * realm. Written by Keycloak on failed logins; has no expiration ({@code clearFailures} deletes
 * the CR). {@link #getLastFailure() lastFailure} is epoch millis;
 * {@link #getFailedLoginNotBefore() failedLoginNotBefore} is epoch <em>seconds</em>, matching
 * Keycloak's model contract for that value.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginFailureSpec {

    /** Id of the user the failures belong to, the store id; defaults to {@code metadata.name}. */
    private String userId;

    /** Name of the realm. */
    private String realm;

    /** Epoch seconds before which login attempts are rejected (temporary lockout). */
    private Integer failedLoginNotBefore;

    private Integer numFailures;

    /** Time of the last failure, epoch millis. */
    private Long lastFailure;

    private String lastIpFailure;
    private Integer numTemporaryLockouts;
    private Integer numSecondaryAuthFailures;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public Integer getFailedLoginNotBefore() {
        return failedLoginNotBefore;
    }

    public void setFailedLoginNotBefore(Integer failedLoginNotBefore) {
        this.failedLoginNotBefore = failedLoginNotBefore;
    }

    public Integer getNumFailures() {
        return numFailures;
    }

    public void setNumFailures(Integer numFailures) {
        this.numFailures = numFailures;
    }

    public Long getLastFailure() {
        return lastFailure;
    }

    public void setLastFailure(Long lastFailure) {
        this.lastFailure = lastFailure;
    }

    public String getLastIpFailure() {
        return lastIpFailure;
    }

    public void setLastIpFailure(String lastIpFailure) {
        this.lastIpFailure = lastIpFailure;
    }

    public Integer getNumTemporaryLockouts() {
        return numTemporaryLockouts;
    }

    public void setNumTemporaryLockouts(Integer numTemporaryLockouts) {
        this.numTemporaryLockouts = numTemporaryLockouts;
    }

    public Integer getNumSecondaryAuthFailures() {
        return numSecondaryAuthFailures;
    }

    public void setNumSecondaryAuthFailures(Integer numSecondaryAuthFailures) {
        this.numSecondaryAuthFailures = numSecondaryAuthFailures;
    }
}
