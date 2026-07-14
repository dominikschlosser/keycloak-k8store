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
package io.github.dominikschlosser.k8store.crd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The source of a {@link ValueReference}: exactly one of a Secret key, a ConfigMap key or an
 * inline literal. Modeled on the {@code valueFrom} of a Kubernetes container env entry.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValueFrom {

    /** Key of a Kubernetes Secret in the watched namespace. */
    private KeySelector secretKeyRef;

    /** Key of a Kubernetes ConfigMap in the watched namespace. */
    private KeySelector configMapKeyRef;

    /** Inline literal value. Useful for templating a fixed value at a path. */
    private String value;

    public KeySelector getSecretKeyRef() {
        return secretKeyRef;
    }

    public void setSecretKeyRef(KeySelector secretKeyRef) {
        this.secretKeyRef = secretKeyRef;
    }

    public KeySelector getConfigMapKeyRef() {
        return configMapKeyRef;
    }

    public void setConfigMapKeyRef(KeySelector configMapKeyRef) {
        this.configMapKeyRef = configMapKeyRef;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
