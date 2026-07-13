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
package com.github.dominikschlosser.k8store.kubernetes.references;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlaceholderResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlaceholderResolver resolver(Map<String, String> env, Map<String, Map<String, String>> secrets) {
        return new PlaceholderResolver(
                env::get, (name, key) -> secrets.getOrDefault(name, Map.of()).get(key));
    }

    @Test
    void resolvesEnvironmentVariable() {
        PlaceholderResolver resolver = resolver(Map.of("FOO", "bar"), Map.of());
        assertEquals("bar", resolver.resolve("${env:FOO}"));
        assertEquals("a-bar-b", resolver.resolve("a-${env:FOO}-b"));
    }

    @Test
    void usesDefaultWhenEnvUnsetOrEmpty() {
        PlaceholderResolver resolver = resolver(Map.of("EMPTY", ""), Map.of());
        assertEquals("fallback", resolver.resolve("${env:MISSING:-fallback}"));
        assertEquals("fallback", resolver.resolve("${env:EMPTY:-fallback}"));
        // an empty default is a valid (empty) resolution, not an unresolved reference
        assertEquals("", resolver.resolve("${env:MISSING:-}"));
    }

    @Test
    void leavesUnsetEnvWithoutDefaultVerbatim() {
        PlaceholderResolver resolver = resolver(Map.of(), Map.of());
        assertEquals("${env:MISSING}", resolver.resolve("${env:MISSING}"));
    }

    @Test
    void resolvesSecretKey() {
        PlaceholderResolver resolver = resolver(Map.of(), Map.of("db", Map.of("password", "s3cr3t")));
        assertEquals("s3cr3t", resolver.resolve("${secret:db:password}"));
        assertEquals("jdbc://h/d?p=s3cr3t", resolver.resolve("jdbc://h/d?p=${secret:db:password}"));
    }

    @Test
    void leavesMissingSecretVerbatim() {
        PlaceholderResolver resolver = resolver(Map.of(), Map.of("db", Map.of("password", "s3cr3t")));
        assertEquals("${secret:db:absent}", resolver.resolve("${secret:db:absent}"));
        assertEquals("${secret:other:password}", resolver.resolve("${secret:other:password}"));
    }

    @Test
    void leavesMalformedSecretVerbatim() {
        PlaceholderResolver resolver = resolver(Map.of(), Map.of());
        assertEquals("${secret:onlyname}", resolver.resolve("${secret:onlyname}"));
        assertEquals("${secret:name:}", resolver.resolve("${secret:name:}"));
    }

    @Test
    void escapesDoubleDollar() {
        PlaceholderResolver resolver = resolver(Map.of("FOO", "bar"), Map.of());
        assertEquals("${env:FOO}", resolver.resolve("$${env:FOO}"));
        assertEquals("$5", resolver.resolve("$$5"));
    }

    @Test
    void passesThroughUnknownPrefixes() {
        PlaceholderResolver resolver = resolver(Map.of(), Map.of());
        assertEquals("${something}", resolver.resolve("${something}"));
        assertEquals("${role.name}", resolver.resolve("${role.name}"));
        assertEquals("no placeholder here", resolver.resolve("no placeholder here"));
    }

    @Test
    void resolvesMultiplePlaceholdersInOneValue() {
        PlaceholderResolver resolver = resolver(Map.of("HOST", "db.example"), Map.of("db", Map.of("pw", "pass")));
        assertEquals("db.example:pass", resolver.resolve("${env:HOST}:${secret:db:pw}"));
    }

    @Test
    void attemptsLookupOnEveryResolveSoRotationIsPickedUp() {
        AtomicInteger secretLookups = new AtomicInteger();
        PlaceholderResolver resolver = new PlaceholderResolver(name -> null, (name, key) -> {
            secretLookups.incrementAndGet();
            return null;
        });
        // an unresolved reference is not negatively cached: the lookup is attempted on every read,
        // so a Secret that appears later resolves on the next read (each call still returns the
        // placeholder verbatim while the Secret is absent)
        assertEquals("${secret:db:pw}", resolver.resolve("${secret:db:pw}"));
        assertEquals("${secret:db:pw}", resolver.resolve("${secret:db:pw}"));
        assertEquals(2, secretLookups.get());
    }

    @Test
    void resolvesTreeRecursivelyThroughObjectsAndArrays() throws Exception {
        PlaceholderResolver resolver = resolver(
                Map.of("SMTP_HOST", "mail.example"),
                Map.of("kc-secrets", Map.of("client", "top-secret", "smtp", "mail-pass")));
        String json = "{"
                + "\"secret\":\"${secret:kc-secrets:client}\","
                + "\"smtpServer\":{\"host\":\"${env:SMTP_HOST}\",\"password\":\"${secret:kc-secrets:smtp}\"},"
                + "\"redirectUris\":[\"https://a\",\"${env:MISSING:-https://b}\"],"
                + "\"count\":3,"
                + "\"enabled\":true"
                + "}";
        JsonNode resolved = resolver.resolveTree(MAPPER.readTree(json));

        assertEquals("top-secret", resolved.get("secret").asText());
        assertEquals("mail.example", resolved.get("smtpServer").get("host").asText());
        assertEquals("mail-pass", resolved.get("smtpServer").get("password").asText());
        assertEquals("https://a", resolved.get("redirectUris").get(0).asText());
        assertEquals("https://b", resolved.get("redirectUris").get(1).asText());
        // non-textual nodes are untouched
        assertEquals(3, resolved.get("count").asInt());
        assertEquals(true, resolved.get("enabled").asBoolean());
    }
}
