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
package io.github.dominikschlosser.k8store.user;

import io.github.dominikschlosser.k8store.common.LikePatterns;
import io.github.dominikschlosser.k8store.crd.UserSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.keycloak.models.UserModel;

/**
 * In-memory equivalent of the JPA user search, over {@link UserSpec} mirror entries: every
 * whitespace-separated term of a {@code SEARCH} value must match username, email, first or last
 * name - prefix matching by default, {@code *} wildcards, {@code "quoted"} terms exactly; the
 * field-specific parameters match infix (or exactly with {@code EXACT}); service accounts are
 * excluded unless {@code INCLUDE_SERVICE_ACCOUNT} says otherwise; unknown keys are custom
 * attribute equality filters (upstream parity - e.g. organization membership queries). All
 * matching is case-insensitive.
 */
final class UserSearch {

    private UserSearch() {}

    static Predicate<UserSpec> predicate(Map<String, String> params) {
        List<Predicate<UserSpec>> predicates = new ArrayList<>();
        boolean exact = Boolean.parseBoolean(params.get(UserModel.EXACT));
        if (!Boolean.parseBoolean(params.get(UserModel.INCLUDE_SERVICE_ACCOUNT))) {
            predicates.add(spec -> spec.getServiceAccountClientId() == null);
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            switch (entry.getKey()) {
                case UserModel.SEARCH -> {
                    for (String term : value.trim().split("\\s+")) {
                        predicates.add(termPredicate(term));
                    }
                }
                case UserModel.USERNAME -> predicates.add(fieldPredicate(UserSpec::getUsername, value, exact));
                case UserModel.FIRST_NAME -> predicates.add(fieldPredicate(UserSpec::getFirstName, value, exact));
                case UserModel.LAST_NAME -> predicates.add(fieldPredicate(UserSpec::getLastName, value, exact));
                case UserModel.EMAIL -> predicates.add(fieldPredicate(UserSpec::getEmail, value, exact));
                case UserModel.ENABLED ->
                    predicates.add(spec -> Boolean.TRUE.equals(spec.isEnabled()) == Boolean.parseBoolean(value));
                case UserModel.EMAIL_VERIFIED ->
                    predicates.add(spec -> Boolean.TRUE.equals(spec.isEmailVerified()) == Boolean.parseBoolean(value));
                case UserModel.IDP_ALIAS ->
                    predicates.add(spec -> spec.getFederatedIdentities() != null
                            && spec.getFederatedIdentities().stream()
                                    .anyMatch(fi -> value.equals(fi.getIdentityProvider())));
                case UserModel.IDP_USER_ID ->
                    predicates.add(spec -> spec.getFederatedIdentities() != null
                            && spec.getFederatedIdentities().stream().anyMatch(fi -> value.equals(fi.getUserId())));
                case UserModel.CREATED_AFTER ->
                    predicates.add(spec ->
                            spec.getCreatedTimestamp() != null && spec.getCreatedTimestamp() >= Long.parseLong(value));
                case UserModel.CREATED_BEFORE ->
                    predicates.add(spec ->
                            spec.getCreatedTimestamp() != null && spec.getCreatedTimestamp() <= Long.parseLong(value));
                case UserModel.EXACT, UserModel.INCLUDE_SERVICE_ACCOUNT -> {
                    // handled above
                }
                default ->
                    predicates.add(spec -> spec.getAttributes() != null
                            && spec.getAttributes()
                                    .getOrDefault(entry.getKey(), List.of())
                                    .contains(value));
            }
        }
        return spec -> predicates.stream().allMatch(p -> p.test(spec));
    }

    /** One term of a full-text search: prefix by default, {@code *} wildcards, quotes = exact. */
    private static Predicate<UserSpec> termPredicate(String term) {
        if (term.length() >= 2 && term.startsWith("\"") && term.endsWith("\"")) {
            String literal = term.substring(1, term.length() - 1);
            return spec -> anySearchField(spec, literal::equalsIgnoreCase);
        }
        String pattern = term.replace('*', '%');
        String prefixed = pattern.endsWith("%") ? pattern : pattern + "%";
        return spec -> anySearchField(spec, field -> LikePatterns.insensitiveLike(field, prefixed));
    }

    static boolean anySearchField(UserSpec spec, Predicate<String> match) {
        return Stream.of(spec.getUsername(), spec.getEmail(), spec.getFirstName(), spec.getLastName())
                .filter(Objects::nonNull)
                .anyMatch(match);
    }

    private static Predicate<UserSpec> fieldPredicate(Function<UserSpec, String> field, String value, boolean exact) {
        if (exact) {
            return spec -> value.equalsIgnoreCase(field.apply(spec));
        }
        String pattern = "%" + value + "%";
        return spec -> LikePatterns.insensitiveLike(field.apply(spec), pattern);
    }
}
