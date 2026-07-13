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
package io.github.dominikschlosser.k8store.usersession;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExpirationsTest {

    @Test
    void bothPositiveTakesTheMinimum() {
        assertEquals(50L, Expirations.combine(100L, 50L));
        assertEquals(50L, Expirations.combine(50L, 100L));
    }

    @Test
    void oneZeroYieldsThePositiveBound() {
        assertEquals(100L, Expirations.combine(100L, 0L));
        assertEquals(100L, Expirations.combine(0L, 100L));
    }

    @Test
    void bothZeroMeansNoBound() {
        assertEquals(0L, Expirations.combine(0L, 0L));
    }

    @Test
    void aNonPositiveCandidateNeverWinsTheMinimum() {
        // a disabled timeout (0) must not shadow the real, larger deadline
        assertEquals(100L, Expirations.combine(100L, 0L));
        // negative candidates are treated as unbounded, like zero
        assertEquals(100L, Expirations.combine(100L, -5L));
        assertEquals(0L, Expirations.combine(-1L, -1L));
    }
}
