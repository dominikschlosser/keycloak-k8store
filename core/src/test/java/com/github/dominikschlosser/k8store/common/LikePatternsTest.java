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
package com.github.dominikschlosser.k8store.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LikePatternsTest {

    @Test
    void likeWithoutWildcardIsAnExactMatch() {
        assertTrue(LikePatterns.like("foo", "foo"));
        assertFalse(LikePatterns.like("foobar", "foo"));
    }

    @Test
    void percentWildcardsMatchLeadingTrailingAndInfix() {
        assertTrue(LikePatterns.like("foobar", "%bar"));
        assertTrue(LikePatterns.like("foobar", "foo%"));
        assertTrue(LikePatterns.like("foobar", "%oob%"));
        assertFalse(LikePatterns.like("foobar", "%baz%"));
    }

    @Test
    void insensitiveLikeIgnoresCaseWhilePlainLikeDoesNot() {
        assertTrue(LikePatterns.insensitiveLike("FooBar", "%foo%"));
        assertFalse(LikePatterns.like("FooBar", "%foo%"));
    }

    @Test
    void regexMetacharactersInThePatternAreTreatedLiterally() {
        // '.' must not act as the regex any-character wildcard
        assertTrue(LikePatterns.like("a.b", "a.b"));
        assertFalse(LikePatterns.like("axb", "a.b"));
        // '(' must not open a regex group and blow up
        assertTrue(LikePatterns.like("a(b)", "a(b)"));
    }

    @Test
    void containsTermMatchesAnywhereWithStarWildcards() {
        assertTrue(LikePatterns.containsTerm("abcdef", "cde", false));
        assertTrue(LikePatterns.containsTerm("abcdef", "a*f", false));
        assertFalse(LikePatterns.containsTerm("abcdef", "xyz", false));
    }

    @Test
    void containsTermCaseSensitivityIsHonored() {
        assertTrue(LikePatterns.containsTerm("HelloWorld", "world", false));
        assertFalse(LikePatterns.containsTerm("HelloWorld", "world", true));
    }

    @Test
    void containsTermTreatsRegexMetacharactersLiterally() {
        // a literal '.' in the term must match only a literal '.' in the value
        assertTrue(LikePatterns.containsTerm("hello.world", ".", false));
        assertFalse(LikePatterns.containsTerm("helloworld", ".", false));
    }
}
