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
package com.github.dominikschlosser.k8store.crd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A client-initial-access token stored on the realm spec. Keycloak has no representation class
 * for these (they are created through a dedicated admin endpoint, never exported), so the spec
 * carries this small original shape mirroring {@code ClientInitialAccessModel}: creation
 * {@link #getTimestamp() timestamp} and {@link #getExpiration() expiration} are in seconds,
 * {@code expiration == 0} means the token never expires.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientInitialAccessSpec {

    private String id;
    private Integer timestamp;
    private Integer expiration;
    private Integer count;
    private Integer remainingCount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** Creation time in seconds since the epoch. */
    public Integer getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Integer timestamp) {
        this.timestamp = timestamp;
    }

    /** Validity duration in seconds from {@link #getTimestamp()}; {@code 0} = never expires. */
    public Integer getExpiration() {
        return expiration;
    }

    public void setExpiration(Integer expiration) {
        this.expiration = expiration;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public Integer getRemainingCount() {
        return remainingCount;
    }

    public void setRemainingCount(Integer remainingCount) {
        this.remainingCount = remainingCount;
    }
}
