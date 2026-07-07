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

/**
 * Why does a test module have a {@code src/main} at all? The classes here are test-only
 * providers that must run <em>inside</em> the embedded Keycloak server under test - the test
 * framework deploys this module's {@code target/classes} into the server, and test-scope
 * classes are never deployed. They are not part of the k8store extension (which lives in the
 * {@code core} module); this module's jar is never shipped.
 *
 * <p>{@code TestFederationUserStorage} is a minimal import-style user-federation provider used
 * by {@code UserFederationStorageTest} to prove that LDAP-style federation works against
 * CR-backed users without running a real directory server.
 */
package com.github.dominikschlosser.k8store.tests.federation;
