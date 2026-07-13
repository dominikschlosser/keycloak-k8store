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
package com.github.dominikschlosser.k8store.config;

import com.google.auto.service.AutoService;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Once a deployment opts into the k8store datastore ({@code --spi-datastore--provider=k8store}), this
 * disables the SPI providers that would otherwise shadow the CR-backed stores - so those disables no
 * longer have to be listed by hand. Contributed values are defaults: anything set explicitly (CLI
 * args, {@code KC_*} env, {@code keycloak.conf}) wins.
 *
 * <p>Keycloak discovers this through the standard SmallRye {@link ConfigSourceFactory} ServiceLoader
 * hook (the same discovery that finds Keycloak's own config sources), at both {@code kc.sh build}
 * and a re-augmenting {@code start}. Using a factory rather than a plain {@code ConfigSource} lets us
 * read the already-resolved configuration through the {@link ConfigSourceContext}, which buys two
 * things:
 *
 * <ul>
 *   <li><b>The datastore selection is the opt-in.</b> We contribute only when k8store is the
 *       explicitly selected datastore. Merely having the extension on the classpath, or choosing a
 *       different datastore, contributes nothing - so this never disables another store's caches.
 *   <li><b>No ordinal race.</b> Each key is contributed only when the context reports it unset, so
 *       correctness does not depend on out-ranking another source. {@link #PRIORITY} just has to sit
 *       below Keycloak's sources so the context can observe them.
 * </ul>
 */
@AutoService(ConfigSourceFactory.class)
public class K8sConfigDefaultsSourceFactory implements ConfigSourceFactory {

    private static final String DATASTORE_PROVIDER = "kc.spi-datastore--provider";
    private static final String K8STORE = "k8store";

    /**
     * The SPI providers that would otherwise shadow the CR-backed stores: the built-in JPA realm
     * provider (a stray model-event listener) and the realm / authorization / organization
     * infinispan caches (which never observe out-of-band CR edits, and whose organization variant
     * delegates to the empty JPA store). Each is defaulted to disabled only when unset.
     */
    private static final List<String> PROVIDER_DISABLES = List.of(
            "kc.spi-realm--jpa--enabled",
            "kc.spi-realm-cache--default--enabled",
            "kc.spi-authorization-cache--default--enabled",
            "kc.spi-organization--infinispan--enabled");

    /**
     * Below every Keycloak config source (persisted build values = 200, {@code keycloak.conf} = 299,
     * {@code KC_*} env = 500, CLI args = 600), so {@link #getConfigSources} observes whatever a
     * deployment set and fills in only the gaps. Any value strictly below 200 is equivalent here.
     */
    static final int PRIORITY = 100;

    @Override
    public OptionalInt getPriority() {
        return OptionalInt.of(PRIORITY);
    }

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        if (!K8STORE.equals(value(context, DATASTORE_PROVIDER))) {
            return List.of();
        }
        Map<String, String> defaults = new HashMap<>();
        for (String key : PROVIDER_DISABLES) {
            if (value(context, key) == null) {
                defaults.put(key, "false");
            }
        }
        return defaults.isEmpty() ? List.of() : List.of(new MapConfigSource(defaults));
    }

    private static String value(ConfigSourceContext context, String key) {
        ConfigValue configValue = context.getValue(key);
        return configValue == null ? null : configValue.getValue();
    }

    private static final class MapConfigSource implements ConfigSource {

        private final Map<String, String> properties;

        private MapConfigSource(Map<String, String> properties) {
            this.properties = properties;
        }

        @Override
        public Map<String, String> getProperties() {
            return properties;
        }

        @Override
        public Set<String> getPropertyNames() {
            return properties.keySet();
        }

        @Override
        public String getValue(String propertyName) {
            return properties.get(propertyName);
        }

        @Override
        public String getName() {
            return "k8store-defaults";
        }

        @Override
        public int getOrdinal() {
            return PRIORITY;
        }
    }
}
