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
package io.github.dominikschlosser.k8store.kubernetes.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValueReferenceResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ValueReferenceResolver resolver(
            Map<String, Map<String, String>> secrets, Map<String, Map<String, String>> configMaps) {
        return new ValueReferenceResolver(
                (name, key) -> secrets.getOrDefault(name, Map.of()).get(key),
                (name, key) -> configMaps.getOrDefault(name, Map.of()).get(key));
    }

    private JsonNode resolve(ValueReferenceResolver resolver, String json) throws Exception {
        return resolver.resolveTree(MAPPER.readTree(json));
    }

    private List<String> validate(ValueReferenceResolver resolver, String json) throws Exception {
        return resolver.validate(MAPPER.readTree(json));
    }

    @Test
    void resolvesSecretKeyRefAtSimplePath() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of("kc-secrets", Map.of("APP_SECRET", "s3cr3t")), Map.of());
        String json = "{"
                + "\"secret\":\"${APP_SECRET}\","
                + "\"valuesFrom\":[{\"targetPath\":\"secret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals("s3cr3t", resolved.get("secret").asText());
    }

    @Test
    void resolvesConfigMapKeyRefAtNestedPath() throws Exception {
        ValueReferenceResolver resolver =
                resolver(Map.of(), Map.of("kc-settings", Map.of("SMTP_HOST", "mail.example")));
        String json = "{"
                + "\"smtpServer\":{\"host\":\"${SMTP_HOST}\"},"
                + "\"valuesFrom\":[{\"targetPath\":\"smtpServer.host\","
                + "\"valueFrom\":{\"configMapKeyRef\":{\"name\":\"kc-settings\",\"key\":\"SMTP_HOST\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals("mail.example", resolved.get("smtpServer").get("host").asText());
    }

    @Test
    void resolvesLiteralValue() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of(), Map.of());
        String json = "{"
                + "\"host\":\"${anything}\","
                + "\"valuesFrom\":[{\"targetPath\":\"host\",\"valueFrom\":{\"value\":\"literal-host\"}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals("literal-host", resolved.get("host").asText());
    }

    @Test
    void injectsPlaceholderEmbeddedInLargerString() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of("kc-secrets", Map.of("TOKEN", "abc123")), Map.of());
        String json = "{"
                + "\"header\":\"Bearer ${TOKEN}\","
                + "\"valuesFrom\":[{\"targetPath\":\"header\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"TOKEN\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals("Bearer abc123", resolved.get("header").asText());
    }

    @Test
    void resolvesThroughArrayIndex() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of("broker", Map.of("CLIENT_SECRET", "idp-secret")), Map.of());
        String json = "{"
                + "\"identityProviders\":[{\"config\":{\"clientSecret\":\"${CLIENT_SECRET}\"}}],"
                + "\"valuesFrom\":[{\"targetPath\":\"identityProviders[0].config.clientSecret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"broker\",\"key\":\"CLIENT_SECRET\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals(
                "idp-secret",
                resolved.get("identityProviders")
                        .get(0)
                        .get("config")
                        .get("clientSecret")
                        .asText());
    }

    @Test
    void resolvesThroughBracketedMapKeyWithDots() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of("ldap", Map.of("BIND", "ldap-pw")), Map.of());
        String provider = "org.keycloak.storage.UserStorageProvider";
        String json = "{"
                + "\"components\":{\"" + provider + "\":[{\"config\":{\"bindCredential\":[\"${BIND}\"]}}]},"
                + "\"valuesFrom\":[{\"targetPath\":\"components[" + provider + "][0].config[bindCredential][0]\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"ldap\",\"key\":\"BIND\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals(
                "ldap-pw",
                resolved.get("components")
                        .get(provider)
                        .get(0)
                        .get("config")
                        .get("bindCredential")
                        .get(0)
                        .asText());
    }

    @Test
    void leavesUndeclaredPlaceholdersVerbatim() throws Exception {
        // a ${...} that no valuesFrom entry points at is a Keycloak token, not our reference
        ValueReferenceResolver resolver = resolver(Map.of("kc-secrets", Map.of("APP_SECRET", "s3cr3t")), Map.of());
        String json = "{"
                + "\"secret\":\"${APP_SECRET}\","
                + "\"displayName\":\"${role_admin}\","
                + "\"valuesFrom\":[{\"targetPath\":\"secret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals("s3cr3t", resolved.get("secret").asText());
        assertEquals("${role_admin}", resolved.get("displayName").asText());
    }

    @Test
    void leavesMissingSecretVerbatim() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of("kc-secrets", Map.of("APP_SECRET", "s3cr3t")), Map.of());
        String json = "{"
                + "\"secret\":\"${MISSING}\","
                + "\"valuesFrom\":[{\"targetPath\":\"secret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"MISSING\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals("${MISSING}", resolved.get("secret").asText());
    }

    @Test
    void leavesValueVerbatimWhenTargetPathLacksThePlaceholder() throws Exception {
        // validate that targetPath matches: the string there must carry the ${key} placeholder
        ValueReferenceResolver resolver = resolver(Map.of("kc-secrets", Map.of("APP_SECRET", "s3cr3t")), Map.of());
        String json = "{"
                + "\"secret\":\"not-a-placeholder\","
                + "\"valuesFrom\":[{\"targetPath\":\"secret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals("not-a-placeholder", resolved.get("secret").asText());
    }

    @Test
    void leavesTreeUntouchedWhenTargetPathIsMissing() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of("kc-secrets", Map.of("APP_SECRET", "s3cr3t")), Map.of());
        String json = "{"
                + "\"secret\":\"${APP_SECRET}\","
                + "\"valuesFrom\":[{\"targetPath\":\"absent.field\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}}]"
                + "}";
        JsonNode resolved = resolve(resolver, json);
        assertEquals("${APP_SECRET}", resolved.get("secret").asText());
    }

    @Test
    void ignoresSpecsWithoutValuesFrom() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of(), Map.of());
        JsonNode resolved = resolve(resolver, "{\"secret\":\"${APP_SECRET}\"}");
        assertEquals("${APP_SECRET}", resolved.get("secret").asText());
    }

    @Test
    void looksUpOnEveryResolveSoRotationIsPickedUp() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        ValueReferenceResolver resolver = new ValueReferenceResolver(
                (name, key) -> {
                    lookups.incrementAndGet();
                    return null;
                },
                (name, key) -> null);
        String json = "{"
                + "\"secret\":\"${APP_SECRET}\","
                + "\"valuesFrom\":[{\"targetPath\":\"secret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}}]"
                + "}";
        resolve(resolver, json);
        resolve(resolver, json);
        assertEquals(2, lookups.get());
    }

    @Test
    void validateReturnsEmptyWhenEveryReferenceResolves() throws Exception {
        ValueReferenceResolver resolver = resolver(
                Map.of("kc-secrets", Map.of("APP_SECRET", "s3cr3t")),
                Map.of("kc-settings", Map.of("SMTP_HOST", "mail.example")));
        String json = "{"
                + "\"secret\":\"${APP_SECRET}\","
                + "\"smtpServer\":{\"host\":\"${SMTP_HOST}\"},"
                + "\"valuesFrom\":["
                + "{\"targetPath\":\"secret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}},"
                + "{\"targetPath\":\"smtpServer.host\","
                + "\"valueFrom\":{\"configMapKeyRef\":{\"name\":\"kc-settings\",\"key\":\"SMTP_HOST\"}}}]"
                + "}";
        assertEquals(List.of(), validate(resolver, json));
    }

    @Test
    void validateReportsMissingSecret() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of(), Map.of());
        String json = "{"
                + "\"secret\":\"${APP_SECRET}\","
                + "\"valuesFrom\":[{\"targetPath\":\"secret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}}]"
                + "}";
        List<String> problems = validate(resolver, json);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("APP_SECRET"), problems.get(0));
    }

    @Test
    void validateReportsTargetPathThatDoesNotExist() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of("kc-secrets", Map.of("APP_SECRET", "s3cr3t")), Map.of());
        String json = "{"
                + "\"secret\":\"${APP_SECRET}\","
                + "\"valuesFrom\":[{\"targetPath\":\"absent.field\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}}]"
                + "}";
        List<String> problems = validate(resolver, json);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("does not exist"), problems.get(0));
    }

    @Test
    void validateReportsTargetPathWithoutThePlaceholder() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of("kc-secrets", Map.of("APP_SECRET", "s3cr3t")), Map.of());
        String json = "{"
                + "\"secret\":\"not-a-placeholder\","
                + "\"valuesFrom\":[{\"targetPath\":\"secret\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"kc-secrets\",\"key\":\"APP_SECRET\"}}}]"
                + "}";
        List<String> problems = validate(resolver, json);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("does not contain the placeholder"), problems.get(0));
    }

    @Test
    void validateReportsMalformedEntry() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of(), Map.of());
        String json =
                "{" + "\"secret\":\"${x}\"," + "\"valuesFrom\":[{\"targetPath\":\"secret\",\"valueFrom\":{}}]" + "}";
        List<String> problems = validate(resolver, json);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("secretKeyRef, configMapKeyRef or value"), problems.get(0));
    }

    @Test
    void validateIgnoresSpecsWithoutValuesFrom() throws Exception {
        ValueReferenceResolver resolver = resolver(Map.of(), Map.of());
        assertEquals(List.of(), validate(resolver, "{\"secret\":\"${APP_SECRET}\"}"));
    }

    @Test
    void parsesDottedAndBracketedPaths() {
        assertEquals(List.of("smtpServer", "password"), ValueReferenceResolver.parsePath("smtpServer.password"));
        assertEquals(
                List.of("identityProviders", 0, "config", "clientSecret"),
                ValueReferenceResolver.parsePath("identityProviders[0].config.clientSecret"));
        assertEquals(
                List.of("components", "org.keycloak.storage.UserStorageProvider", 0, "config", "bindCredential", 0),
                ValueReferenceResolver.parsePath(
                        "components[org.keycloak.storage.UserStorageProvider][0].config[bindCredential][0]"));
        assertNull(ValueReferenceResolver.parsePath("unterminated[0"));
    }
}
