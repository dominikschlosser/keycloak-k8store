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
package com.github.dominikschlosser.k8store.spi;

import org.keycloak.provider.InvalidationHandler.InvalidableObjectType;

/**
 * Cross-provider invalidation events of the k8store model layer, sent through
 * {@link org.keycloak.models.KeycloakSession#invalidate}. A provider that removes an object
 * announces it here so that the other k8store providers can cascade - e.g. the role provider
 * dropping a deleted role from composites, the group provider dropping it from role grants -
 * and so that {@code *_AFTER_REMOVE} handlers can publish Keycloak's model removal events.
 *
 * <p>Event parameters follow one convention: {@code params[0]} is the {@code RealmModel} (except
 * for {@code CLIENT_AFTER_REMOVE} and {@code CLIENT_SCOPE_AFTER_REMOVE}, where {@code params[0]}
 * is the removed model itself) and {@code params[1]} is the affected model, where applicable.
 *
 * <p>The {@code *_RENAMED} events cascade a rename the same way the {@code *_BEFORE_REMOVE} events
 * cascade a removal, but they rewrite name-keyed references instead of dropping them. They are sent
 * before the renamed object's own CR is moved, so handlers still see the old references. For these
 * events {@code params[0]} is the {@code RealmModel}, {@code params[1]} is the affected model still
 * reporting its old name (or the old name itself), and the last parameter is the new name.
 */
public enum StoreInvalidation implements InvalidableObjectType {
    REALM_BEFORE_REMOVE,
    REALM_AFTER_REMOVE,
    CLIENT_BEFORE_REMOVE,
    CLIENT_AFTER_REMOVE,
    CLIENT_RENAMED,
    CLIENT_SCOPE_BEFORE_REMOVE,
    CLIENT_SCOPE_AFTER_REMOVE,
    CLIENT_SCOPE_RENAMED,
    ROLE_BEFORE_REMOVE,
    ROLE_AFTER_REMOVE,
    ROLE_RENAMED,
    GROUP_BEFORE_REMOVE,
    GROUP_AFTER_REMOVE
}
