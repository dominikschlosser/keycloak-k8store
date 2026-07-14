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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.keycloak.representations.idm.GroupRepresentation;

/**
 * Spec of a {@code KeycloakGroup} custom resource: Keycloak's own group representation plus the
 * {@link #getRealm() realm} that scopes it, so the CR body reads exactly like standard Keycloak
 * group JSON.
 *
 * <p>Identity and hierarchy: {@code spec.id} is the store id and defaults to
 * {@code metadata.name} when omitted; Keycloak writes name-based ids. The hierarchy is stored
 * <em>flat</em> - one CR per group, linked through {@code spec.parentId}. The recursive
 * {@code subGroups} list of the representation is intentionally not part of the CRD schema and
 * its content is ignored at read time; author one CR per subgroup instead. Role grants are
 * stored the standard representation way ({@code realmRoles} role names, {@code clientRoles}
 * keyed by client).
 *
 * <p>Serialization rules: {@code null} properties and {@code null} map values are dropped - a
 * real API server rejects explicit nulls in {@code map<string,string>} schema fields with 422.
 * Unknown properties are ignored on read.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GroupSpec extends GroupRepresentation implements ValueReferenceCarrier {

    /**
     * Secret, ConfigMap and literal references this resource may pull into its {@code ${...}}
     * placeholders. Resolved on read only. See {@link ValueReferenceCarrier}.
     */
    private List<ValueReference> valuesFrom;

    @Override
    public List<ValueReference> getValuesFrom() {
        return valuesFrom;
    }

    @Override
    public void setValuesFrom(List<ValueReference> valuesFrom) {
        this.valuesFrom = valuesFrom;
    }

    /**
     * Name of the realm this group belongs to. Required in hand-authored CRs (alternatively via
     * the realm label); always set on CRs written by Keycloak.
     */
    private String realm;

    /**
     * Group type: {@code organization} for groups owned by a Keycloak organization (the
     * organization's backing group and its organization-scoped subgroups), absent/null for
     * regular realm groups. Organization groups are invisible to the normal group queries,
     * exactly like upstream's {@code KEYCLOAK_GROUP.TYPE} column.
     */
    private String type;

    /**
     * Id of the organization owning this group (backing group and organization-scoped
     * subgroups) - the CR shape of upstream's group-to-organization foreign key. Null for
     * realm groups.
     */
    private String organizationId;

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    /**
     * Excluded from (de)serialization and thus from the CRD schema: the hierarchy is stored
     * flat via {@code parentId}, and the superclass getter lazily initializes the list - an
     * always-serialized empty {@code subGroups} array would be rejected by server-side apply
     * against a schema without the field.
     */
    @JsonIgnore
    @Override
    public List<GroupRepresentation> getSubGroups() {
        return super.getSubGroups();
    }
}
