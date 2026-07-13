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

import java.util.List;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

/**
 * A spec that carries protocol mappers the standard representation way. Both
 * {@link ClientSpec} and {@link ClientScopeSpec} satisfy this interface through the accessors
 * inherited from their representation superclasses; it exists so the protocol-mapper model logic
 * can be shared between the client and client-scope adapters.
 */
public interface ProtocolMapperCarrier {

    List<ProtocolMapperRepresentation> getProtocolMappers();

    void setProtocolMappers(List<ProtocolMapperRepresentation> protocolMappers);

    /** The container's login protocol; mappers without an own protocol inherit it. */
    String getProtocol();
}
