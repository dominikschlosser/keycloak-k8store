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
package io.github.dominikschlosser.k8store.kubernetes.crd;

/**
 * The CRD API group and version shared by every {@code Keycloak*Cr} custom resource. Kept in one
 * place so a group rename or a version bump is a single edit; referenced from each CR's
 * {@code @Group}/{@code @Version} (both are compile-time constants, so they are annotation-legal).
 */
public final class K8sCrd {

    private K8sCrd() {}

    public static final String GROUP = "k8store.dominikschlosser.github.io";
    public static final String VERSION = "v1alpha1";
}
