/*
 * Copyright (c) 2024, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.charset;

import org.graalvm.collections.EconomicMap;

import com.oracle.truffle.regex.charset.UnicodeProperties.NameMatchingMode;

final class UnicodePropertyDataDiff extends UnicodePropertyData {

    static final class CodePointSetDiff {
        final CodePointSet add;
        final CodePointSet sub;

        private CodePointSetDiff(CodePointSet add, CodePointSet sub) {
            this.add = add;
            this.sub = sub;
        }

        static CodePointSetDiff create(CodePointSet add, CodePointSet sub) {
            return new CodePointSetDiff(add, sub);
        }
    }

    private final UnicodePropertyData parent;
    private final EconomicMap<String, CodePointSetDiff> properties;

    UnicodePropertyDataDiff(UnicodePropertyData parent,
                    EconomicMap<String, CodePointSetDiff> properties,
                    EconomicMap<String, ClassSetContents> emoji,
                    EconomicMap<String, String> propAliases,
                    EconomicMap<String, String> gcAliases,
                    EconomicMap<String, String> scAliases,
                    EconomicMap<String, String> blkAliases) {
        super(null, emoji, propAliases, gcAliases, scAliases, blkAliases);
        this.properties = properties;
        this.parent = parent;
    }

    @Override
    CodePointSet retrieveProperty(String propertySpec) {
        CodePointSet parentEntry = parent.retrieveProperty(propertySpec);
        CodePointSetDiff diff = properties.get(propertySpec);
        if (diff == null) {
            return parentEntry;
        }
        if (parentEntry == null) {
            assert diff.sub.isEmpty();
            return diff.add;
        } else {
            return parentEntry.union(diff.add).subtract(diff.sub);
        }
    }

    @Override
    ClassSetContents retrievePropertyOfStrings(String propertySpec) {
        CodePointSet prop = retrieveProperty(propertySpec);
        if (prop != null) {
            assert !emoji.containsKey(propertySpec) && parent.retrieveEmojiProperty(propertySpec) == null;
            return ClassSetContents.createCharacterClass(prop);
        }
        ClassSetContents parentEntry = parent.retrieveEmojiProperty(propertySpec);
        ClassSetContents diff = emoji.get(propertySpec);
        if (diff == null) {
            if (parentEntry != null) {
                return parentEntry;
            } else if ("RGI_Emoji".equals(propertySpec)) {
                return getRGIEmoji();
            } else {
                return null;
            }
        } else {
            if (parentEntry == null) {
                return diff;
            } else {
                return parentEntry.unionUnicodePropertyOfStrings(diff);
            }
        }
    }

    @Override
    String lookupPropertyAlias(String alias, NameMatchingMode nameMatchingMode) {
        String name = super.lookupPropertyAlias(alias, nameMatchingMode);
        if (name == null) {
            return parent.lookupPropertyAlias(alias, nameMatchingMode);
        }
        return name;
    }

    @Override
    String lookupGeneralCategoryAlias(String alias, NameMatchingMode nameMatchingMode) {
        String name = super.lookupGeneralCategoryAlias(alias, nameMatchingMode);
        if (name == null) {
            return parent.lookupGeneralCategoryAlias(alias, nameMatchingMode);
        }
        return name;
    }

    @Override
    String lookupScriptAlias(String alias, NameMatchingMode nameMatchingMode) {
        String name = super.lookupScriptAlias(alias, nameMatchingMode);
        if (name == null) {
            return parent.lookupScriptAlias(alias, nameMatchingMode);
        }
        return name;
    }

    @Override
    String lookupBlockAlias(String alias, NameMatchingMode nameMatchingMode) {
        String name = super.lookupBlockAlias(alias, nameMatchingMode);
        if (name == null) {
            return parent.lookupBlockAlias(alias, nameMatchingMode);
        }
        return name;
    }
}
