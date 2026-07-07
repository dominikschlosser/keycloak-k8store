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
package com.github.dominikschlosser.k8store.usersession;

import com.github.dominikschlosser.k8store.crd.ClientSessionSpec;
import com.github.dominikschlosser.k8store.crd.UserSessionSpec;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.SessionExpirationUtils;

/**
 * Session expiration math, delegated to Keycloak's own {@link SessionExpirationUtils} so the
 * realm/client settings interplay (SSO max lifespan and idle timeout, their remember-me
 * variants, offline variants, per-client OIDC overrides) matches the built-in stores exactly.
 * The store recomputes {@code expiresAt} whenever a session's timestamp moves; expiry itself is
 * then enforced by the storage backend (read filtering + reaper). Per-session note-based
 * lifespan overrides are not applied (documented limitation of the dynamic areas).
 */
final class Expirations {

    private Expirations() {}

    /** Recomputes and stamps the absolute expiration of a user session, epoch millis. */
    static void stampUserSession(RealmModel realm, UserSessionSpec spec) {
        boolean offline = Boolean.TRUE.equals(spec.getOffline());
        boolean rememberMe = Boolean.TRUE.equals(spec.getRememberMe());
        long started = spec.getStarted() == null ? 0 : spec.getStarted();
        long lastRefresh = spec.getLastSessionRefresh() == null ? started : spec.getLastSessionRefresh();
        long lifespan = SessionExpirationUtils.calculateUserSessionMaxLifespanTimestamp(
                offline, rememberMe, started, realm);
        long idle = SessionExpirationUtils.calculateUserSessionIdleTimestamp(
                offline, rememberMe, lastRefresh, realm);
        spec.setExpiresAt(combine(lifespan, idle));
    }

    /** Recomputes and stamps the absolute expiration of an embedded client session. */
    static void stampClientSession(RealmModel realm, ClientModel client,
                                   UserSessionSpec parent, ClientSessionSpec spec) {
        boolean offline = Boolean.TRUE.equals(parent.getOffline());
        boolean rememberMe = Boolean.TRUE.equals(parent.getRememberMe());
        long userSessionStarted = parent.getStarted() == null ? 0 : parent.getStarted();
        long timestamp = spec.getTimestamp() == null ? userSessionStarted : spec.getTimestamp();
        long lifespan = SessionExpirationUtils.calculateClientSessionMaxLifespanTimestamp(
                offline, rememberMe, timestamp, userSessionStarted, realm, client);
        long idle = SessionExpirationUtils.calculateClientSessionIdleTimestamp(
                offline, rememberMe, timestamp, realm, client);
        spec.setExpiresAt(combine(lifespan, idle));
    }

    /** True when an embedded client session carries an expiration that has passed. */
    static boolean isClientSessionExpired(ClientSessionSpec spec, long nowMillis) {
        Long expiresAt = spec.getExpiresAt();
        return expiresAt != null && expiresAt > 0 && expiresAt <= nowMillis;
    }

    /** Earliest of the two deadlines; a non-positive deadline means "no limit" (0 = never). */
    private static long combine(long lifespan, long idle) {
        if (lifespan > 0 && idle > 0) {
            return Math.min(lifespan, idle);
        }
        return lifespan > 0 ? lifespan : Math.max(idle, 0);
    }
}
