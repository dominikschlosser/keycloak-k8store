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
package io.github.dominikschlosser.k8store.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.github.dominikschlosser.k8store.crd.ClientSpec;
import io.github.dominikschlosser.k8store.crd.KeySelector;
import io.github.dominikschlosser.k8store.crd.ValueFrom;
import io.github.dominikschlosser.k8store.crd.ValueReference;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import io.github.dominikschlosser.k8store.tests.config.ReferenceResolutionServerConfig;
import io.github.dominikschlosser.k8store.tests.framework.Await;
import io.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import io.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.KindCluster;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;

/**
 * End-to-end reference resolution: the checked-in example CRs in {@code examples/references} are
 * applied to the cluster together with their Secret and ConfigMap, and Keycloak serves the client
 * with every {@code valuesFrom} reference resolved. The stored CR keeps the raw placeholders. These
 * are the same manifests the README points users at, so a green run keeps the documentation honest.
 */
@Order(2)
@KeycloakIntegrationTest(config = ReferenceResolutionServerConfig.class)
public class ReferenceResolutionStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectAdminClient(mode = InjectAdminClient.Mode.BOOTSTRAP)
    Keycloak adminClient;

    @Test
    public void secretConfigMapAndLiteralReferencesResolveOnRead() {
        applyExample("secret.yaml");
        applyExample("configmap.yaml");
        applyExample("keycloakclient.yaml");

        Await.await("the referencing client to appear in the master realm", () -> !adminClient
                .realm("master")
                .clients()
                .findByClientId("reference-example")
                .isEmpty());

        String clientId = adminClient
                .realm("master")
                .clients()
                .findByClientId("reference-example")
                .get(0)
                .getId();

        // the secret comes from the Secret, resolved on read
        Await.await("the client secret reference to resolve from the Secret", () -> "super-secret-value"
                .equals(adminClient
                        .realm("master")
                        .clients()
                        .get(clientId)
                        .getSecret()
                        .getValue()));

        ClientRepresentation client =
                adminClient.realm("master").clients().get(clientId).toRepresentation();
        // rootUrl comes from the ConfigMap
        assertEquals("https://app.example.com", client.getRootUrl(), "rootUrl must resolve from the ConfigMap");
        // the ConfigMap value is injected inside a larger string (the redirect URI)
        assertNotNull(client.getRedirectUris());
        assertTrue(
                client.getRedirectUris().contains("https://app.example.com/callback"),
                "the redirect URI must resolve the embedded ConfigMap reference: " + client.getRedirectUris());
        // the literal replaces the placeholder wherever it sits in the string
        assertEquals(
                "managed by k8store, revision 42",
                client.getDescription(),
                "the literal reference must be injected at the placeholder");

        // the stored CR still holds the raw placeholders: no secret is written into the CR in clear
        GenericKubernetesResource stored = kube.client()
                .genericKubernetesResources("k8store.dominikschlosser.github.io/v1alpha1", "KeycloakClient")
                .inNamespace(namespace.name())
                .withName("master.reference-example")
                .get();
        assertNotNull(stored, "the client CR must exist");
        Object secret = nested(stored, "spec", "secret");
        assertEquals("${client-secret}", secret, "the stored CR must keep the placeholder, not the resolved secret");
    }

    @Test
    public void missingSecretIsLeftVerbatimAndTheClientIsStillServed() {
        // the referenced Secret does not exist: resolution fails open, the placeholder is served
        // verbatim, and the client is not hidden
        ClientSpec spec = new ClientSpec();
        spec.setSecret("${absent}");
        spec.setValuesFrom(List.of(secretRef("secret", "no-such-secret", "absent")));
        createClient("unresolved-secret", spec);

        Await.await("the client with an unresolvable reference to appear", () -> clientExists("unresolved-secret"));

        assertEquals(
                "${absent}", secretValueOf("unresolved-secret"), "a missing Secret leaves the placeholder verbatim");
    }

    @Test
    public void undeclaredPlaceholderIsLeftVerbatim() {
        // a ${...} that no valuesFrom entry points at is a Keycloak token, not a reference: only the
        // declared secret placeholder resolves, the undeclared one is untouched
        createSecret("declared-secret", "key", "resolved-secret-value");

        ClientSpec spec = new ClientSpec();
        spec.setSecret("${key}");
        spec.setDescription("${role_admin}");
        spec.setValuesFrom(List.of(secretRef("secret", "declared-secret", "key")));
        createClient("undeclared-placeholder", spec);

        // wait until the declared reference has resolved, which proves the resolver ran on this CR
        Await.await(
                "the declared secret reference to resolve",
                () -> clientExists("undeclared-placeholder")
                        && "resolved-secret-value".equals(secretValueOf("undeclared-placeholder")));

        ClientRepresentation client = clientRepresentation("undeclared-placeholder");
        assertEquals("${role_admin}", client.getDescription(), "an undeclared placeholder must be served verbatim");
    }

    @Test
    public void targetPathWithoutThePlaceholderIsNotOverwritten() {
        // validate that targetPath matches: the Secret exists and resolves, but the string at the
        // targetPath carries no placeholder, so the field must be left exactly as authored
        createSecret("present-secret", "key", "should-not-appear");

        ClientSpec spec = new ClientSpec();
        spec.setSecret("kept-literal-secret");
        spec.setValuesFrom(List.of(secretRef("secret", "present-secret", "key")));
        createClient("target-path-mismatch", spec);

        Await.await("the client with a mismatched targetPath to appear", () -> clientExists("target-path-mismatch"));

        assertEquals(
                "kept-literal-secret",
                secretValueOf("target-path-mismatch"),
                "a targetPath whose string lacks the placeholder must not be overwritten");
    }

    private boolean clientExists(String clientId) {
        return !adminClient.realm("master").clients().findByClientId(clientId).isEmpty();
    }

    private String secretValueOf(String clientId) {
        String id = adminClient
                .realm("master")
                .clients()
                .findByClientId(clientId)
                .get(0)
                .getId();
        return adminClient.realm("master").clients().get(id).getSecret().getValue();
    }

    private ClientRepresentation clientRepresentation(String clientId) {
        String id = adminClient
                .realm("master")
                .clients()
                .findByClientId(clientId)
                .get(0)
                .getId();
        return adminClient.realm("master").clients().get(id).toRepresentation();
    }

    private void createClient(String clientId, ClientSpec spec) {
        spec.setClientId(clientId);
        spec.setRealm("master");
        spec.setEnabled(true);
        spec.setProtocol("openid-connect");
        spec.setPublicClient(false);
        spec.setClientAuthenticatorType("client-secret");
        KeycloakClientCr cr = new KeycloakClientCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("master." + clientId)
                .withNamespace(namespace.name())
                .build());
        cr.setSpec(spec);
        kube.client().resource(cr).inNamespace(namespace.name()).create();
    }

    private void createSecret(String name, String key, String value) {
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace.name())
                .endMetadata()
                .addToStringData(key, value)
                .build();
        kube.client().resource(secret).inNamespace(namespace.name()).create();
    }

    private static ValueReference secretRef(String targetPath, String name, String key) {
        KeySelector selector = new KeySelector();
        selector.setName(name);
        selector.setKey(key);
        ValueFrom valueFrom = new ValueFrom();
        valueFrom.setSecretKeyRef(selector);
        ValueReference reference = new ValueReference();
        reference.setTargetPath(targetPath);
        reference.setValueFrom(valueFrom);
        return reference;
    }

    private void applyExample(String fileName) {
        Path dir = Files.isDirectory(Path.of("../examples/references"))
                ? Path.of("../examples/references")
                : Path.of("examples/references");
        try (InputStream is = Files.newInputStream(dir.resolve(fileName))) {
            kube.client().load(is).inNamespace(namespace.name()).create();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot apply example manifest " + fileName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nested(GenericKubernetesResource resource, String... path) {
        Object current = resource.getAdditionalProperties();
        for (String key : path) {
            if (!(current instanceof java.util.Map<?, ?> map)) {
                return null;
            }
            current = ((java.util.Map<String, Object>) map).get(key);
        }
        return current;
    }
}
