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
 * One entry of a spec's {@code valuesFrom} list: it declares that the string at {@link #getTargetPath()}
 * may pull a value from {@link #getValueFrom()}, and nothing else may. This is the same shape the
 * Grafana operator uses for its datasources.
 *
 * <p>The value lands where a {@code ${...}} placeholder sits inside the string at {@code targetPath}.
 * Resolution happens on the read path only, so the stored custom resource keeps the placeholder and
 * never holds the resolved secret in clear. A {@code ${...}} that no {@code valuesFrom} entry points
 * at is left untouched, which is what keeps Keycloak's own {@code ${...}} tokens (localization keys,
 * policy expressions) intact.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValueReference {

    /**
     * Path to the string this reference feeds, relative to the spec root. Dot notation with array
     * indexing, e.g. {@code secret}, {@code smtpServer.password} or
     * {@code identityProviders[0].config.clientSecret}. Map keys that contain dots use brackets:
     * {@code components[org.keycloak.storage.UserStorageProvider][0].config[bindCredential][0]}.
     */
    private String targetPath;

    /** Where the value comes from: a Secret key, a ConfigMap key or an inline literal. */
    private ValueFrom valueFrom;

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public ValueFrom getValueFrom() {
        return valueFrom;
    }

    public void setValueFrom(ValueFrom valueFrom) {
        this.valueFrom = valueFrom;
    }
}
