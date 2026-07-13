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
package io.github.dominikschlosser.k8store.loginfailure;

import io.github.dominikschlosser.k8store.crd.LoginFailureSpec;
import org.keycloak.models.UserLoginFailureModel;

/**
 * {@link UserLoginFailureModel} over a {@link LoginFailureSpec}. The brute-force protector
 * mutates several counters per failed login; each mutation re-persists the spec explicitly and
 * the transaction buffer collapses them into one apply.
 */
public class LoginFailureAdapter implements UserLoginFailureModel {

    private final LoginFailureSpec spec;

    LoginFailureAdapter(LoginFailureSpec spec) {
        this.spec = spec;
    }

    private void persist() {
        LoginFailureCrStore.save(spec);
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }

    @Override
    public String getId() {
        return spec.getUserId();
    }

    @Override
    public String getUserId() {
        return spec.getUserId();
    }

    @Override
    public int getFailedLoginNotBefore() {
        return intOf(spec.getFailedLoginNotBefore());
    }

    @Override
    public void setFailedLoginNotBefore(int notBefore) {
        spec.setFailedLoginNotBefore(notBefore);
        persist();
    }

    @Override
    public int getNumFailures() {
        return intOf(spec.getNumFailures());
    }

    @Override
    public void incrementFailures() {
        spec.setNumFailures(getNumFailures() + 1);
        persist();
    }

    @Override
    public int getNumTemporaryLockouts() {
        return intOf(spec.getNumTemporaryLockouts());
    }

    @Override
    public void incrementTemporaryLockouts() {
        spec.setNumTemporaryLockouts(getNumTemporaryLockouts() + 1);
        persist();
    }

    @Override
    public void clearFailures() {
        spec.setFailedLoginNotBefore(null);
        spec.setNumFailures(null);
        spec.setLastFailure(null);
        spec.setLastIpFailure(null);
        spec.setNumTemporaryLockouts(null);
        spec.setNumSecondaryAuthFailures(null);
        persist();
    }

    @Override
    public long getLastFailure() {
        return spec.getLastFailure() == null ? 0 : spec.getLastFailure();
    }

    @Override
    public void setLastFailure(long lastFailure) {
        spec.setLastFailure(lastFailure);
        persist();
    }

    @Override
    public String getLastIPFailure() {
        return spec.getLastIpFailure();
    }

    @Override
    public void setLastIPFailure(String ip) {
        spec.setLastIpFailure(ip);
        persist();
    }

    @Override
    public int getNumSecondaryAuthFailures() {
        return intOf(spec.getNumSecondaryAuthFailures());
    }

    @Override
    public void incrementSecondaryAuthFailures() {
        spec.setNumSecondaryAuthFailures(getNumSecondaryAuthFailures() + 1);
        persist();
    }

    @Override
    public void clearPrimaryAndSecondaryAuthFailures() {
        spec.setNumFailures(null);
        spec.setNumSecondaryAuthFailures(null);
        persist();
    }
}
