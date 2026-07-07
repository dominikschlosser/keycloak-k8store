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

import java.util.List;

/** Small in-place list edits shared by the rename cascades. */
public final class ListRewrites {

    private ListRewrites() {}

    /**
     * Replaces the first occurrence of {@code oldValue} with {@code newValue}, keeping its
     * position (the cross-references these lists hold - composite roles, scope mappings,
     * default-scope lists - are order-sensitive). Returns whether a replacement happened; a null
     * list or an absent value is a no-op.
     */
    public static boolean replaceInList(List<String> values, String oldValue, String newValue) {
        if (values == null) {
            return false;
        }
        int index = values.indexOf(oldValue);
        if (index < 0) {
            return false;
        }
        values.set(index, newValue);
        return true;
    }
}
