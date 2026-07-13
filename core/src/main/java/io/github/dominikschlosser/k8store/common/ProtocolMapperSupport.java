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
package io.github.dominikschlosser.k8store.common;

import io.github.dominikschlosser.k8store.crd.ProtocolMapperCarrier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

/**
 * Shared {@link org.keycloak.models.ProtocolMapperContainerModel} logic of the client and
 * client-scope adapters, operating on the standard {@link ProtocolMapperRepresentation} lists of
 * a {@link ProtocolMapperCarrier} spec. Every mutation runs the supplied {@code persist}
 * callback - specs carry no write-through machinery.
 *
 * <p>Mapper ids are generated with {@link KeycloakModelUtils#generateId()} at creation and
 * preserved on read; a mapper without an own protocol inherits the container's (defaulting to
 * {@code openid-connect}, like the container itself).
 */
public final class ProtocolMapperSupport {

    private static final String DEFAULT_PROTOCOL = "openid-connect";

    private ProtocolMapperSupport() {}

    /** The container's login protocol, defaulting to {@code openid-connect}. */
    public static String effectiveProtocol(ProtocolMapperCarrier spec) {
        return spec.getProtocol() == null ? DEFAULT_PROTOCOL : spec.getProtocol();
    }

    public static Stream<ProtocolMapperModel> stream(ProtocolMapperCarrier spec) {
        List<ProtocolMapperRepresentation> reps = spec.getProtocolMappers();
        if (reps == null) {
            return Stream.empty();
        }
        String fallbackProtocol = effectiveProtocol(spec);
        return reps.stream().map(rep -> toModel(rep, fallbackProtocol));
    }

    public static ProtocolMapperModel add(ProtocolMapperCarrier spec, ProtocolMapperModel model, Runnable persist) {
        if (model == null) {
            return null;
        }
        ProtocolMapperRepresentation rep = toRepresentation(model);
        if (rep.getId() == null) {
            rep.setId(KeycloakModelUtils.generateId());
        }
        if (rep.getConfig() == null) {
            rep.setConfig(new HashMap<>());
        }
        List<ProtocolMapperRepresentation> reps = spec.getProtocolMappers();
        if (reps == null) {
            reps = new ArrayList<>();
            spec.setProtocolMappers(reps);
        }
        reps.add(rep);
        persist.run();
        return toModel(rep, effectiveProtocol(spec));
    }

    public static void remove(ProtocolMapperCarrier spec, ProtocolMapperModel mapping, Runnable persist) {
        String id = mapping == null ? null : mapping.getId();
        List<ProtocolMapperRepresentation> reps = spec.getProtocolMappers();
        if (id != null && reps != null && reps.removeIf(rep -> id.equals(rep.getId()))) {
            persist.run();
        }
    }

    /** Replaces the mapper with {@code mapping}'s id in place; a no-op for unknown ids. */
    public static void update(ProtocolMapperCarrier spec, ProtocolMapperModel mapping, Runnable persist) {
        String id = mapping == null ? null : mapping.getId();
        List<ProtocolMapperRepresentation> reps = spec.getProtocolMappers();
        if (id == null || reps == null) {
            return;
        }
        for (int i = 0; i < reps.size(); i++) {
            if (id.equals(reps.get(i).getId())) {
                ProtocolMapperRepresentation rep = toRepresentation(mapping);
                if (rep.getConfig() == null) {
                    rep.setConfig(new HashMap<>());
                }
                reps.set(i, rep);
                persist.run();
                return;
            }
        }
    }

    public static ProtocolMapperModel getById(ProtocolMapperCarrier spec, String id) {
        List<ProtocolMapperRepresentation> reps = spec.getProtocolMappers();
        if (id == null || reps == null) {
            return null;
        }
        return reps.stream()
                .filter(rep -> id.equals(rep.getId()))
                .findFirst()
                .map(rep -> toModel(rep, effectiveProtocol(spec)))
                .orElse(null);
    }

    public static ProtocolMapperModel getByName(ProtocolMapperCarrier spec, String protocol, String name) {
        if (!Objects.equals(protocol, effectiveProtocol(spec))) {
            return null;
        }
        List<ProtocolMapperRepresentation> reps = spec.getProtocolMappers();
        if (reps == null) {
            return null;
        }
        return reps.stream()
                .filter(rep -> Objects.equals(rep.getName(), name))
                .findFirst()
                .map(rep -> toModel(rep, effectiveProtocol(spec)))
                .orElse(null);
    }

    public static ProtocolMapperModel toModel(ProtocolMapperRepresentation rep, String fallbackProtocol) {
        ProtocolMapperModel model = new ProtocolMapperModel();
        model.setId(rep.getId());
        model.setName(rep.getName());
        model.setProtocol(rep.getProtocol() == null ? fallbackProtocol : rep.getProtocol());
        model.setProtocolMapper(rep.getProtocolMapper());
        model.setConfig(rep.getConfig() == null ? new HashMap<>() : new HashMap<>(rep.getConfig()));
        return model;
    }

    public static ProtocolMapperRepresentation toRepresentation(ProtocolMapperModel model) {
        ProtocolMapperRepresentation rep = new ProtocolMapperRepresentation();
        rep.setId(model.getId());
        rep.setName(model.getName());
        rep.setProtocol(model.getProtocol());
        rep.setProtocolMapper(model.getProtocolMapper());
        rep.setConfig(model.getConfig() == null ? new HashMap<>() : new HashMap<>(model.getConfig()));
        return rep;
    }
}
