/*
 * Copyright (c) 2018, 2025, Oracle and/or its affiliates. All rights reserved.
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
/* Copyright (c) 2018 Unicode, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.
 * For terms of use, see http://www.unicode.org/terms_of_use.html
 */

package com.oracle.truffle.regex.charset;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.MapCursor;

import com.oracle.truffle.regex.charset.UnicodeProperties.NameMatchingMode;
import com.oracle.truffle.regex.charset.UnicodePropertyDataDiff.CodePointSetDiff;

public class UnicodePropertyData {

    private record Aliases(
                    EconomicMap<String, String> prop,
                    EconomicMap<String, String> gc,
                    EconomicMap<String, String> sc,
                    EconomicMap<String, String> blk) {

        private Aliases transform(NameMatchingMode nameMatchingMode) {
            return new Aliases(
                            transformMap(prop, nameMatchingMode),
                            transformMap(gc, nameMatchingMode),
                            transformMap(sc, nameMatchingMode),
                            transformMap(blk, nameMatchingMode));
        }

        private static EconomicMap<String, String> transformMap(EconomicMap<String, String> source, NameMatchingMode nameMatchingMode) {
            EconomicMap<String, String> target = EconomicMap.create(source.size());
            MapCursor<String, String> cursor = source.getEntries();
            while (cursor.advance()) {
                String transformedKey = nameMatchingMode.normalize(cursor.getKey());
                assert !target.containsKey(transformedKey) || target.get(transformedKey).equals(cursor.getValue());
                target.put(transformedKey, cursor.getValue());
            }
            return target;
        }
    }

    private final EconomicMap<String, CodePointSet> properties;
    protected final EconomicMap<String, ClassSetContents> emoji;
    private ClassSetContents rgiEmoji;
    private final Aliases[] aliases = new Aliases[NameMatchingMode.values().length];

    UnicodePropertyData(
                    EconomicMap<String, CodePointSet> properties,
                    EconomicMap<String, ClassSetContents> emoji,
                    EconomicMap<String, String> propAliases,
                    EconomicMap<String, String> gcAliases,
                    EconomicMap<String, String> scAliases,
                    EconomicMap<String, String> blkAliases) {
        this.properties = properties;
        this.emoji = emoji;
        aliases[NameMatchingMode.exact.ordinal()] = new Aliases(propAliases, gcAliases, scAliases, blkAliases);
    }

    CodePointSet retrieveProperty(String propertySpec) {
        return properties.get(propertySpec);
    }

    ClassSetContents retrievePropertyOfStrings(String propertySpec) {
        CodePointSet prop = retrieveProperty(propertySpec);
        if (prop != null) {
            assert !emoji.containsKey(propertySpec);
            return ClassSetContents.createCharacterClass(prop);
        }
        ClassSetContents propEmoji = retrieveEmojiProperty(propertySpec);
        if (propEmoji != null) {
            return propEmoji;
        }
        if ("RGI_Emoji".equals(propertySpec)) {
            return getRGIEmoji();
        }
        return null;
    }

    protected ClassSetContents retrieveEmojiProperty(String propertySpec) {
        return emoji.get(propertySpec);
    }

    protected ClassSetContents getRGIEmoji() {
        if (rgiEmoji == null) {
            ClassSetContentsAccumulator acc = new ClassSetContentsAccumulator();
            acc.addAll(retrievePropertyOfStrings("Basic_Emoji"));
            acc.addAll(retrievePropertyOfStrings("Emoji_Keycap_Sequence"));
            acc.addAll(retrievePropertyOfStrings("RGI_Emoji_Flag_Sequence"));
            acc.addAll(retrievePropertyOfStrings("RGI_Emoji_Modifier_Sequence"));
            acc.addAll(retrievePropertyOfStrings("RGI_Emoji_Tag_Sequence"));
            acc.addAll(retrievePropertyOfStrings("RGI_Emoji_ZWJ_Sequence"));
            rgiEmoji = ClassSetContents.createClass(acc.getCodePointSet(), acc.getStrings(), acc.mayContainStrings());
        }
        return rgiEmoji;
    }

    Aliases getExactAliases() {
        return aliases[NameMatchingMode.exact.ordinal()];
    }

    Aliases getAliases(NameMatchingMode nameMatchingMode) {
        Aliases lookup = aliases[nameMatchingMode.ordinal()];
        if (lookup == null) {
            assert nameMatchingMode != NameMatchingMode.exact;
            lookup = getExactAliases().transform(nameMatchingMode);
            aliases[nameMatchingMode.ordinal()] = lookup;
        }
        return lookup;
    }

    String lookupPropertyAlias(String alias, NameMatchingMode nameMatchingMode) {
        String name = getExactAliases().prop.get(alias);
        if (name == null && nameMatchingMode != NameMatchingMode.exact) {
            return getAliases(nameMatchingMode).prop.get(nameMatchingMode.normalize(alias));
        }
        return name;
    }

    String lookupGeneralCategoryAlias(String alias, NameMatchingMode nameMatchingMode) {
        String name = getExactAliases().gc.get(alias);
        if (name == null && nameMatchingMode != NameMatchingMode.exact) {
            return getAliases(nameMatchingMode).gc.get(nameMatchingMode.normalize(alias));
        }
        return name;
    }

    String lookupScriptAlias(String alias, NameMatchingMode nameMatchingMode) {
        String name = getExactAliases().sc.get(alias);
        if (name == null && nameMatchingMode != NameMatchingMode.exact) {
            return getAliases(nameMatchingMode).sc.get(nameMatchingMode.normalize(alias));
        }
        return name;
    }

    String lookupBlockAlias(String alias, NameMatchingMode nameMatchingMode) {
        String name = getExactAliases().blk.get(alias);
        if (name == null && nameMatchingMode != NameMatchingMode.exact) {
            return getAliases(nameMatchingMode).blk.get(nameMatchingMode.normalize(alias));
        }
        return name;
    }

    private static EconomicSet<String> stringSet(String... strings) {
        EconomicSet<String> set = EconomicSet.create(strings.length);
        for (String s : strings) {
            set.add(s);
        }
        return set;
    }

    /* GENERATED CODE BEGIN - KEEP THIS MARKER FOR AUTOMATIC UPDATES */

    private static final EconomicMap<String, String> PROPERTY_ALIASES = EconomicMap.create(124);
    private static final EconomicMap<String, String> GENERAL_CATEGORY_ALIASES = EconomicMap.create(80);
    private static final EconomicMap<String, String> SCRIPT_ALIASES = EconomicMap.create(324);
    private static final EconomicMap<String, String> BLOCK_ALIASES = EconomicMap.create(469);
    private static final EconomicMap<String, CodePointSet> SET_ENCODINGS = EconomicMap.create(752);
    private static final EconomicMap<String, ClassSetContents> CLASS_SET_ENCODINGS = EconomicMap.create(7);
    static final UnicodePropertyData INSTANCE;

    static {
        PROPERTY_ALIASES.put("AHex", "AHex");
        PROPERTY_ALIASES.put("ASCII", "ASCII");
        PROPERTY_ALIASES.put("ASCII_Hex_Digit", "AHex");
        PROPERTY_ALIASES.put("Alpha", "Alpha");
        PROPERTY_ALIASES.put("Alphabetic", "Alpha");
        PROPERTY_ALIASES.put("Any", "Any");
        PROPERTY_ALIASES.put("Assigned", "Assigned");
        PROPERTY_ALIASES.put("Basic_Emoji", "Basic_Emoji");
        PROPERTY_ALIASES.put("Bidi_C", "Bidi_C");
        PROPERTY_ALIASES.put("Bidi_Control", "Bidi_C");
        PROPERTY_ALIASES.put("Bidi_M", "Bidi_M");
        PROPERTY_ALIASES.put("Bidi_Mirrored", "Bidi_M");
        PROPERTY_ALIASES.put("Block", "blk");
        PROPERTY_ALIASES.put("CI", "CI");
        PROPERTY_ALIASES.put("CWCF", "CWCF");
        PROPERTY_ALIASES.put("CWCM", "CWCM");
        PROPERTY_ALIASES.put("CWKCF", "CWKCF");
        PROPERTY_ALIASES.put("CWL", "CWL");
        PROPERTY_ALIASES.put("CWT", "CWT");
        PROPERTY_ALIASES.put("CWU", "CWU");
        PROPERTY_ALIASES.put("Case_Ignorable", "CI");
        PROPERTY_ALIASES.put("Cased", "Cased");
        PROPERTY_ALIASES.put("Changes_When_Casefolded", "CWCF");
        PROPERTY_ALIASES.put("Changes_When_Casemapped", "CWCM");
        PROPERTY_ALIASES.put("Changes_When_Lowercased", "CWL");
        PROPERTY_ALIASES.put("Changes_When_NFKC_Casefolded", "CWKCF");
        PROPERTY_ALIASES.put("Changes_When_Titlecased", "CWT");
        PROPERTY_ALIASES.put("Changes_When_Uppercased", "CWU");
        PROPERTY_ALIASES.put("DI", "DI");
        PROPERTY_ALIASES.put("Dash", "Dash");
        PROPERTY_ALIASES.put("Default_Ignorable_Code_Point", "DI");
        PROPERTY_ALIASES.put("Dep", "Dep");
        PROPERTY_ALIASES.put("Deprecated", "Dep");
        PROPERTY_ALIASES.put("Dia", "Dia");
        PROPERTY_ALIASES.put("Diacritic", "Dia");
        PROPERTY_ALIASES.put("EBase", "EBase");
        PROPERTY_ALIASES.put("EComp", "EComp");
        PROPERTY_ALIASES.put("EMod", "EMod");
        PROPERTY_ALIASES.put("EPres", "EPres");
        PROPERTY_ALIASES.put("Emoji", "Emoji");
        PROPERTY_ALIASES.put("Emoji_Component", "EComp");
        PROPERTY_ALIASES.put("Emoji_Keycap_Sequence", "Emoji_Keycap_Sequence");
        PROPERTY_ALIASES.put("Emoji_Modifier", "EMod");
        PROPERTY_ALIASES.put("Emoji_Modifier_Base", "EBase");
        PROPERTY_ALIASES.put("Emoji_Presentation", "EPres");
        PROPERTY_ALIASES.put("Ext", "Ext");
        PROPERTY_ALIASES.put("ExtPict", "ExtPict");
        PROPERTY_ALIASES.put("Extended_Pictographic", "ExtPict");
        PROPERTY_ALIASES.put("Extender", "Ext");
        PROPERTY_ALIASES.put("General_Category", "gc");
        PROPERTY_ALIASES.put("Gr_Base", "Gr_Base");
        PROPERTY_ALIASES.put("Gr_Ext", "Gr_Ext");
        PROPERTY_ALIASES.put("Grapheme_Base", "Gr_Base");
        PROPERTY_ALIASES.put("Grapheme_Extend", "Gr_Ext");
        PROPERTY_ALIASES.put("Hex", "Hex");
        PROPERTY_ALIASES.put("Hex_Digit", "Hex");
        PROPERTY_ALIASES.put("IDC", "IDC");
        PROPERTY_ALIASES.put("IDS", "IDS");
        PROPERTY_ALIASES.put("IDSB", "IDSB");
        PROPERTY_ALIASES.put("IDST", "IDST");
        PROPERTY_ALIASES.put("IDS_Binary_Operator", "IDSB");
        PROPERTY_ALIASES.put("IDS_Trinary_Operator", "IDST");
        PROPERTY_ALIASES.put("ID_Continue", "IDC");
        PROPERTY_ALIASES.put("ID_Start", "IDS");
        PROPERTY_ALIASES.put("Ideo", "Ideo");
        PROPERTY_ALIASES.put("Ideographic", "Ideo");
        PROPERTY_ALIASES.put("Join_C", "Join_C");
        PROPERTY_ALIASES.put("Join_Control", "Join_C");
        PROPERTY_ALIASES.put("LOE", "LOE");
        PROPERTY_ALIASES.put("Logical_Order_Exception", "LOE");
        PROPERTY_ALIASES.put("Lower", "Lower");
        PROPERTY_ALIASES.put("Lowercase", "Lower");
        PROPERTY_ALIASES.put("Math", "Math");
        PROPERTY_ALIASES.put("NChar", "NChar");
        PROPERTY_ALIASES.put("Noncharacter_Code_Point", "NChar");
        PROPERTY_ALIASES.put("OAlpha", "OAlpha");
        PROPERTY_ALIASES.put("OIDC", "OIDC");
        PROPERTY_ALIASES.put("OIDS", "OIDS");
        PROPERTY_ALIASES.put("OLower", "OLower");
        PROPERTY_ALIASES.put("OUpper", "OUpper");
        PROPERTY_ALIASES.put("Other_Alphabetic", "OAlpha");
        PROPERTY_ALIASES.put("Other_ID_Continue", "OIDC");
        PROPERTY_ALIASES.put("Other_ID_Start", "OIDS");
        PROPERTY_ALIASES.put("Other_Lowercase", "OLower");
        PROPERTY_ALIASES.put("Other_Uppercase", "OUpper");
        PROPERTY_ALIASES.put("Pat_Syn", "Pat_Syn");
        PROPERTY_ALIASES.put("Pat_WS", "Pat_WS");
        PROPERTY_ALIASES.put("Pattern_Syntax", "Pat_Syn");
        PROPERTY_ALIASES.put("Pattern_White_Space", "Pat_WS");
        PROPERTY_ALIASES.put("QMark", "QMark");
        PROPERTY_ALIASES.put("Quotation_Mark", "QMark");
        PROPERTY_ALIASES.put("RGI_Emoji", "RGI_Emoji");
        PROPERTY_ALIASES.put("RGI_Emoji_Flag_Sequence", "RGI_Emoji_Flag_Sequence");
        PROPERTY_ALIASES.put("RGI_Emoji_Modifier_Sequence", "RGI_Emoji_Modifier_Sequence");
        PROPERTY_ALIASES.put("RGI_Emoji_Tag_Sequence", "RGI_Emoji_Tag_Sequence");
        PROPERTY_ALIASES.put("RGI_Emoji_ZWJ_Sequence", "RGI_Emoji_ZWJ_Sequence");
        PROPERTY_ALIASES.put("RI", "RI");
        PROPERTY_ALIASES.put("Radical", "Radical");
        PROPERTY_ALIASES.put("Regional_Indicator", "RI");
        PROPERTY_ALIASES.put("SD", "SD");
        PROPERTY_ALIASES.put("STerm", "STerm");
        PROPERTY_ALIASES.put("Script", "sc");
        PROPERTY_ALIASES.put("Script_Extensions", "scx");
        PROPERTY_ALIASES.put("Sentence_Terminal", "STerm");
        PROPERTY_ALIASES.put("Soft_Dotted", "SD");
        PROPERTY_ALIASES.put("Term", "Term");
        PROPERTY_ALIASES.put("Terminal_Punctuation", "Term");
        PROPERTY_ALIASES.put("UIdeo", "UIdeo");
        PROPERTY_ALIASES.put("Unified_Ideograph", "UIdeo");
        PROPERTY_ALIASES.put("Upper", "Upper");
        PROPERTY_ALIASES.put("Uppercase", "Upper");
        PROPERTY_ALIASES.put("VS", "VS");
        PROPERTY_ALIASES.put("Variation_Selector", "VS");
        PROPERTY_ALIASES.put("WSpace", "WSpace");
        PROPERTY_ALIASES.put("White_Space", "WSpace");
        PROPERTY_ALIASES.put("XIDC", "XIDC");
        PROPERTY_ALIASES.put("XIDS", "XIDS");
        PROPERTY_ALIASES.put("XID_Continue", "XIDC");
        PROPERTY_ALIASES.put("XID_Start", "XIDS");
        PROPERTY_ALIASES.put("blk", "blk");
        PROPERTY_ALIASES.put("gc", "gc");
        PROPERTY_ALIASES.put("sc", "sc");
        PROPERTY_ALIASES.put("scx", "scx");
        PROPERTY_ALIASES.put("space", "WSpace");
        GENERAL_CATEGORY_ALIASES.put("C", "C");
        GENERAL_CATEGORY_ALIASES.put("Cased_Letter", "LC");
        GENERAL_CATEGORY_ALIASES.put("Cc", "Cc");
        GENERAL_CATEGORY_ALIASES.put("Cf", "Cf");
        GENERAL_CATEGORY_ALIASES.put("Close_Punctuation", "Pe");
        GENERAL_CATEGORY_ALIASES.put("Cn", "Cn");
        GENERAL_CATEGORY_ALIASES.put("Co", "Co");
        GENERAL_CATEGORY_ALIASES.put("Combining_Mark", "M");
        GENERAL_CATEGORY_ALIASES.put("Connector_Punctuation", "Pc");
        GENERAL_CATEGORY_ALIASES.put("Control", "Cc");
        GENERAL_CATEGORY_ALIASES.put("Cs", "Cs");
        GENERAL_CATEGORY_ALIASES.put("Currency_Symbol", "Sc");
        GENERAL_CATEGORY_ALIASES.put("Dash_Punctuation", "Pd");
        GENERAL_CATEGORY_ALIASES.put("Decimal_Number", "Nd");
        GENERAL_CATEGORY_ALIASES.put("Enclosing_Mark", "Me");
        GENERAL_CATEGORY_ALIASES.put("Final_Punctuation", "Pf");
        GENERAL_CATEGORY_ALIASES.put("Format", "Cf");
        GENERAL_CATEGORY_ALIASES.put("Initial_Punctuation", "Pi");
        GENERAL_CATEGORY_ALIASES.put("L", "L");
        GENERAL_CATEGORY_ALIASES.put("LC", "LC");
        GENERAL_CATEGORY_ALIASES.put("Letter", "L");
        GENERAL_CATEGORY_ALIASES.put("Letter_Number", "Nl");
        GENERAL_CATEGORY_ALIASES.put("Line_Separator", "Zl");
        GENERAL_CATEGORY_ALIASES.put("Ll", "Ll");
        GENERAL_CATEGORY_ALIASES.put("Lm", "Lm");
        GENERAL_CATEGORY_ALIASES.put("Lo", "Lo");
        GENERAL_CATEGORY_ALIASES.put("Lowercase_Letter", "Ll");
        GENERAL_CATEGORY_ALIASES.put("Lt", "Lt");
        GENERAL_CATEGORY_ALIASES.put("Lu", "Lu");
        GENERAL_CATEGORY_ALIASES.put("M", "M");
        GENERAL_CATEGORY_ALIASES.put("Mark", "M");
        GENERAL_CATEGORY_ALIASES.put("Math_Symbol", "Sm");
        GENERAL_CATEGORY_ALIASES.put("Mc", "Mc");
        GENERAL_CATEGORY_ALIASES.put("Me", "Me");
        GENERAL_CATEGORY_ALIASES.put("Mn", "Mn");
        GENERAL_CATEGORY_ALIASES.put("Modifier_Letter", "Lm");
        GENERAL_CATEGORY_ALIASES.put("Modifier_Symbol", "Sk");
        GENERAL_CATEGORY_ALIASES.put("N", "N");
        GENERAL_CATEGORY_ALIASES.put("Nd", "Nd");
        GENERAL_CATEGORY_ALIASES.put("Nl", "Nl");
        GENERAL_CATEGORY_ALIASES.put("No", "No");
        GENERAL_CATEGORY_ALIASES.put("Nonspacing_Mark", "Mn");
        GENERAL_CATEGORY_ALIASES.put("Number", "N");
        GENERAL_CATEGORY_ALIASES.put("Open_Punctuation", "Ps");
        GENERAL_CATEGORY_ALIASES.put("Other", "C");
        GENERAL_CATEGORY_ALIASES.put("Other_Letter", "Lo");
        GENERAL_CATEGORY_ALIASES.put("Other_Number", "No");
        GENERAL_CATEGORY_ALIASES.put("Other_Punctuation", "Po");
        GENERAL_CATEGORY_ALIASES.put("Other_Symbol", "So");
        GENERAL_CATEGORY_ALIASES.put("P", "P");
        GENERAL_CATEGORY_ALIASES.put("Paragraph_Separator", "Zp");
        GENERAL_CATEGORY_ALIASES.put("Pc", "Pc");
        GENERAL_CATEGORY_ALIASES.put("Pd", "Pd");
        GENERAL_CATEGORY_ALIASES.put("Pe", "Pe");
        GENERAL_CATEGORY_ALIASES.put("Pf", "Pf");
        GENERAL_CATEGORY_ALIASES.put("Pi", "Pi");
        GENERAL_CATEGORY_ALIASES.put("Po", "Po");
        GENERAL_CATEGORY_ALIASES.put("Private_Use", "Co");
        GENERAL_CATEGORY_ALIASES.put("Ps", "Ps");
        GENERAL_CATEGORY_ALIASES.put("Punctuation", "P");
        GENERAL_CATEGORY_ALIASES.put("S", "S");
        GENERAL_CATEGORY_ALIASES.put("Sc", "Sc");
        GENERAL_CATEGORY_ALIASES.put("Separator", "Z");
        GENERAL_CATEGORY_ALIASES.put("Sk", "Sk");
        GENERAL_CATEGORY_ALIASES.put("Sm", "Sm");
        GENERAL_CATEGORY_ALIASES.put("So", "So");
        GENERAL_CATEGORY_ALIASES.put("Space_Separator", "Zs");
        GENERAL_CATEGORY_ALIASES.put("Spacing_Mark", "Mc");
        GENERAL_CATEGORY_ALIASES.put("Surrogate", "Cs");
        GENERAL_CATEGORY_ALIASES.put("Symbol", "S");
        GENERAL_CATEGORY_ALIASES.put("Titlecase_Letter", "Lt");
        GENERAL_CATEGORY_ALIASES.put("Unassigned", "Cn");
        GENERAL_CATEGORY_ALIASES.put("Uppercase_Letter", "Lu");
        GENERAL_CATEGORY_ALIASES.put("Z", "Z");
        GENERAL_CATEGORY_ALIASES.put("Zl", "Zl");
        GENERAL_CATEGORY_ALIASES.put("Zp", "Zp");
        GENERAL_CATEGORY_ALIASES.put("Zs", "Zs");
        GENERAL_CATEGORY_ALIASES.put("cntrl", "Cc");
        GENERAL_CATEGORY_ALIASES.put("digit", "Nd");
        GENERAL_CATEGORY_ALIASES.put("punct", "P");
        SCRIPT_ALIASES.put("Adlam", "Adlm");
        SCRIPT_ALIASES.put("Adlm", "Adlm");
        SCRIPT_ALIASES.put("Aghb", "Aghb");
        SCRIPT_ALIASES.put("Ahom", "Ahom");
        SCRIPT_ALIASES.put("Anatolian_Hieroglyphs", "Hluw");
        SCRIPT_ALIASES.put("Arab", "Arab");
        SCRIPT_ALIASES.put("Arabic", "Arab");
        SCRIPT_ALIASES.put("Armenian", "Armn");
        SCRIPT_ALIASES.put("Armi", "Armi");
        SCRIPT_ALIASES.put("Armn", "Armn");
        SCRIPT_ALIASES.put("Avestan", "Avst");
        SCRIPT_ALIASES.put("Avst", "Avst");
        SCRIPT_ALIASES.put("Bali", "Bali");
        SCRIPT_ALIASES.put("Balinese", "Bali");
        SCRIPT_ALIASES.put("Bamu", "Bamu");
        SCRIPT_ALIASES.put("Bamum", "Bamu");
        SCRIPT_ALIASES.put("Bass", "Bass");
        SCRIPT_ALIASES.put("Bassa_Vah", "Bass");
        SCRIPT_ALIASES.put("Batak", "Batk");
        SCRIPT_ALIASES.put("Batk", "Batk");
        SCRIPT_ALIASES.put("Beng", "Beng");
        SCRIPT_ALIASES.put("Bengali", "Beng");
        SCRIPT_ALIASES.put("Bhaiksuki", "Bhks");
        SCRIPT_ALIASES.put("Bhks", "Bhks");
        SCRIPT_ALIASES.put("Bopo", "Bopo");
        SCRIPT_ALIASES.put("Bopomofo", "Bopo");
        SCRIPT_ALIASES.put("Brah", "Brah");
        SCRIPT_ALIASES.put("Brahmi", "Brah");
        SCRIPT_ALIASES.put("Brai", "Brai");
        SCRIPT_ALIASES.put("Braille", "Brai");
        SCRIPT_ALIASES.put("Bugi", "Bugi");
        SCRIPT_ALIASES.put("Buginese", "Bugi");
        SCRIPT_ALIASES.put("Buhd", "Buhd");
        SCRIPT_ALIASES.put("Buhid", "Buhd");
        SCRIPT_ALIASES.put("Cakm", "Cakm");
        SCRIPT_ALIASES.put("Canadian_Aboriginal", "Cans");
        SCRIPT_ALIASES.put("Cans", "Cans");
        SCRIPT_ALIASES.put("Cari", "Cari");
        SCRIPT_ALIASES.put("Carian", "Cari");
        SCRIPT_ALIASES.put("Caucasian_Albanian", "Aghb");
        SCRIPT_ALIASES.put("Chakma", "Cakm");
        SCRIPT_ALIASES.put("Cham", "Cham");
        SCRIPT_ALIASES.put("Cher", "Cher");
        SCRIPT_ALIASES.put("Cherokee", "Cher");
        SCRIPT_ALIASES.put("Chorasmian", "Chrs");
        SCRIPT_ALIASES.put("Chrs", "Chrs");
        SCRIPT_ALIASES.put("Common", "Zyyy");
        SCRIPT_ALIASES.put("Copt", "Copt");
        SCRIPT_ALIASES.put("Coptic", "Copt");
        SCRIPT_ALIASES.put("Cpmn", "Cpmn");
        SCRIPT_ALIASES.put("Cprt", "Cprt");
        SCRIPT_ALIASES.put("Cuneiform", "Xsux");
        SCRIPT_ALIASES.put("Cypriot", "Cprt");
        SCRIPT_ALIASES.put("Cypro_Minoan", "Cpmn");
        SCRIPT_ALIASES.put("Cyrillic", "Cyrl");
        SCRIPT_ALIASES.put("Cyrl", "Cyrl");
        SCRIPT_ALIASES.put("Deseret", "Dsrt");
        SCRIPT_ALIASES.put("Deva", "Deva");
        SCRIPT_ALIASES.put("Devanagari", "Deva");
        SCRIPT_ALIASES.put("Diak", "Diak");
        SCRIPT_ALIASES.put("Dives_Akuru", "Diak");
        SCRIPT_ALIASES.put("Dogr", "Dogr");
        SCRIPT_ALIASES.put("Dogra", "Dogr");
        SCRIPT_ALIASES.put("Dsrt", "Dsrt");
        SCRIPT_ALIASES.put("Dupl", "Dupl");
        SCRIPT_ALIASES.put("Duployan", "Dupl");
        SCRIPT_ALIASES.put("Egyp", "Egyp");
        SCRIPT_ALIASES.put("Egyptian_Hieroglyphs", "Egyp");
        SCRIPT_ALIASES.put("Elba", "Elba");
        SCRIPT_ALIASES.put("Elbasan", "Elba");
        SCRIPT_ALIASES.put("Elym", "Elym");
        SCRIPT_ALIASES.put("Elymaic", "Elym");
        SCRIPT_ALIASES.put("Ethi", "Ethi");
        SCRIPT_ALIASES.put("Ethiopic", "Ethi");
        SCRIPT_ALIASES.put("Geor", "Geor");
        SCRIPT_ALIASES.put("Georgian", "Geor");
        SCRIPT_ALIASES.put("Glag", "Glag");
        SCRIPT_ALIASES.put("Glagolitic", "Glag");
        SCRIPT_ALIASES.put("Gong", "Gong");
        SCRIPT_ALIASES.put("Gonm", "Gonm");
        SCRIPT_ALIASES.put("Goth", "Goth");
        SCRIPT_ALIASES.put("Gothic", "Goth");
        SCRIPT_ALIASES.put("Gran", "Gran");
        SCRIPT_ALIASES.put("Grantha", "Gran");
        SCRIPT_ALIASES.put("Greek", "Grek");
        SCRIPT_ALIASES.put("Grek", "Grek");
        SCRIPT_ALIASES.put("Gujarati", "Gujr");
        SCRIPT_ALIASES.put("Gujr", "Gujr");
        SCRIPT_ALIASES.put("Gunjala_Gondi", "Gong");
        SCRIPT_ALIASES.put("Gurmukhi", "Guru");
        SCRIPT_ALIASES.put("Guru", "Guru");
        SCRIPT_ALIASES.put("Han", "Hani");
        SCRIPT_ALIASES.put("Hang", "Hang");
        SCRIPT_ALIASES.put("Hangul", "Hang");
        SCRIPT_ALIASES.put("Hani", "Hani");
        SCRIPT_ALIASES.put("Hanifi_Rohingya", "Rohg");
        SCRIPT_ALIASES.put("Hano", "Hano");
        SCRIPT_ALIASES.put("Hanunoo", "Hano");
        SCRIPT_ALIASES.put("Hatr", "Hatr");
        SCRIPT_ALIASES.put("Hatran", "Hatr");
        SCRIPT_ALIASES.put("Hebr", "Hebr");
        SCRIPT_ALIASES.put("Hebrew", "Hebr");
        SCRIPT_ALIASES.put("Hira", "Hira");
        SCRIPT_ALIASES.put("Hiragana", "Hira");
        SCRIPT_ALIASES.put("Hluw", "Hluw");
        SCRIPT_ALIASES.put("Hmng", "Hmng");
        SCRIPT_ALIASES.put("Hmnp", "Hmnp");
        SCRIPT_ALIASES.put("Hrkt", "Hrkt");
        SCRIPT_ALIASES.put("Hung", "Hung");
        SCRIPT_ALIASES.put("Imperial_Aramaic", "Armi");
        SCRIPT_ALIASES.put("Inherited", "Zinh");
        SCRIPT_ALIASES.put("Inscriptional_Pahlavi", "Phli");
        SCRIPT_ALIASES.put("Inscriptional_Parthian", "Prti");
        SCRIPT_ALIASES.put("Ital", "Ital");
        SCRIPT_ALIASES.put("Java", "Java");
        SCRIPT_ALIASES.put("Javanese", "Java");
        SCRIPT_ALIASES.put("Kaithi", "Kthi");
        SCRIPT_ALIASES.put("Kali", "Kali");
        SCRIPT_ALIASES.put("Kana", "Kana");
        SCRIPT_ALIASES.put("Kannada", "Knda");
        SCRIPT_ALIASES.put("Katakana", "Kana");
        SCRIPT_ALIASES.put("Katakana_Or_Hiragana", "Hrkt");
        SCRIPT_ALIASES.put("Kawi", "Kawi");
        SCRIPT_ALIASES.put("Kayah_Li", "Kali");
        SCRIPT_ALIASES.put("Khar", "Khar");
        SCRIPT_ALIASES.put("Kharoshthi", "Khar");
        SCRIPT_ALIASES.put("Khitan_Small_Script", "Kits");
        SCRIPT_ALIASES.put("Khmer", "Khmr");
        SCRIPT_ALIASES.put("Khmr", "Khmr");
        SCRIPT_ALIASES.put("Khoj", "Khoj");
        SCRIPT_ALIASES.put("Khojki", "Khoj");
        SCRIPT_ALIASES.put("Khudawadi", "Sind");
        SCRIPT_ALIASES.put("Kits", "Kits");
        SCRIPT_ALIASES.put("Knda", "Knda");
        SCRIPT_ALIASES.put("Kthi", "Kthi");
        SCRIPT_ALIASES.put("Lana", "Lana");
        SCRIPT_ALIASES.put("Lao", "Laoo");
        SCRIPT_ALIASES.put("Laoo", "Laoo");
        SCRIPT_ALIASES.put("Latin", "Latn");
        SCRIPT_ALIASES.put("Latn", "Latn");
        SCRIPT_ALIASES.put("Lepc", "Lepc");
        SCRIPT_ALIASES.put("Lepcha", "Lepc");
        SCRIPT_ALIASES.put("Limb", "Limb");
        SCRIPT_ALIASES.put("Limbu", "Limb");
        SCRIPT_ALIASES.put("Lina", "Lina");
        SCRIPT_ALIASES.put("Linb", "Linb");
        SCRIPT_ALIASES.put("Linear_A", "Lina");
        SCRIPT_ALIASES.put("Linear_B", "Linb");
        SCRIPT_ALIASES.put("Lisu", "Lisu");
        SCRIPT_ALIASES.put("Lyci", "Lyci");
        SCRIPT_ALIASES.put("Lycian", "Lyci");
        SCRIPT_ALIASES.put("Lydi", "Lydi");
        SCRIPT_ALIASES.put("Lydian", "Lydi");
        SCRIPT_ALIASES.put("Mahajani", "Mahj");
        SCRIPT_ALIASES.put("Mahj", "Mahj");
        SCRIPT_ALIASES.put("Maka", "Maka");
        SCRIPT_ALIASES.put("Makasar", "Maka");
        SCRIPT_ALIASES.put("Malayalam", "Mlym");
        SCRIPT_ALIASES.put("Mand", "Mand");
        SCRIPT_ALIASES.put("Mandaic", "Mand");
        SCRIPT_ALIASES.put("Mani", "Mani");
        SCRIPT_ALIASES.put("Manichaean", "Mani");
        SCRIPT_ALIASES.put("Marc", "Marc");
        SCRIPT_ALIASES.put("Marchen", "Marc");
        SCRIPT_ALIASES.put("Masaram_Gondi", "Gonm");
        SCRIPT_ALIASES.put("Medefaidrin", "Medf");
        SCRIPT_ALIASES.put("Medf", "Medf");
        SCRIPT_ALIASES.put("Meetei_Mayek", "Mtei");
        SCRIPT_ALIASES.put("Mend", "Mend");
        SCRIPT_ALIASES.put("Mende_Kikakui", "Mend");
        SCRIPT_ALIASES.put("Merc", "Merc");
        SCRIPT_ALIASES.put("Mero", "Mero");
        SCRIPT_ALIASES.put("Meroitic_Cursive", "Merc");
        SCRIPT_ALIASES.put("Meroitic_Hieroglyphs", "Mero");
        SCRIPT_ALIASES.put("Miao", "Plrd");
        SCRIPT_ALIASES.put("Mlym", "Mlym");
        SCRIPT_ALIASES.put("Modi", "Modi");
        SCRIPT_ALIASES.put("Mong", "Mong");
        SCRIPT_ALIASES.put("Mongolian", "Mong");
        SCRIPT_ALIASES.put("Mro", "Mroo");
        SCRIPT_ALIASES.put("Mroo", "Mroo");
        SCRIPT_ALIASES.put("Mtei", "Mtei");
        SCRIPT_ALIASES.put("Mult", "Mult");
        SCRIPT_ALIASES.put("Multani", "Mult");
        SCRIPT_ALIASES.put("Myanmar", "Mymr");
        SCRIPT_ALIASES.put("Mymr", "Mymr");
        SCRIPT_ALIASES.put("Nabataean", "Nbat");
        SCRIPT_ALIASES.put("Nag_Mundari", "Nagm");
        SCRIPT_ALIASES.put("Nagm", "Nagm");
        SCRIPT_ALIASES.put("Nand", "Nand");
        SCRIPT_ALIASES.put("Nandinagari", "Nand");
        SCRIPT_ALIASES.put("Narb", "Narb");
        SCRIPT_ALIASES.put("Nbat", "Nbat");
        SCRIPT_ALIASES.put("New_Tai_Lue", "Talu");
        SCRIPT_ALIASES.put("Newa", "Newa");
        SCRIPT_ALIASES.put("Nko", "Nkoo");
        SCRIPT_ALIASES.put("Nkoo", "Nkoo");
        SCRIPT_ALIASES.put("Nshu", "Nshu");
        SCRIPT_ALIASES.put("Nushu", "Nshu");
        SCRIPT_ALIASES.put("Nyiakeng_Puachue_Hmong", "Hmnp");
        SCRIPT_ALIASES.put("Ogam", "Ogam");
        SCRIPT_ALIASES.put("Ogham", "Ogam");
        SCRIPT_ALIASES.put("Ol_Chiki", "Olck");
        SCRIPT_ALIASES.put("Olck", "Olck");
        SCRIPT_ALIASES.put("Old_Hungarian", "Hung");
        SCRIPT_ALIASES.put("Old_Italic", "Ital");
        SCRIPT_ALIASES.put("Old_North_Arabian", "Narb");
        SCRIPT_ALIASES.put("Old_Permic", "Perm");
        SCRIPT_ALIASES.put("Old_Persian", "Xpeo");
        SCRIPT_ALIASES.put("Old_Sogdian", "Sogo");
        SCRIPT_ALIASES.put("Old_South_Arabian", "Sarb");
        SCRIPT_ALIASES.put("Old_Turkic", "Orkh");
        SCRIPT_ALIASES.put("Old_Uyghur", "Ougr");
        SCRIPT_ALIASES.put("Oriya", "Orya");
        SCRIPT_ALIASES.put("Orkh", "Orkh");
        SCRIPT_ALIASES.put("Orya", "Orya");
        SCRIPT_ALIASES.put("Osage", "Osge");
        SCRIPT_ALIASES.put("Osge", "Osge");
        SCRIPT_ALIASES.put("Osma", "Osma");
        SCRIPT_ALIASES.put("Osmanya", "Osma");
        SCRIPT_ALIASES.put("Ougr", "Ougr");
        SCRIPT_ALIASES.put("Pahawh_Hmong", "Hmng");
        SCRIPT_ALIASES.put("Palm", "Palm");
        SCRIPT_ALIASES.put("Palmyrene", "Palm");
        SCRIPT_ALIASES.put("Pau_Cin_Hau", "Pauc");
        SCRIPT_ALIASES.put("Pauc", "Pauc");
        SCRIPT_ALIASES.put("Perm", "Perm");
        SCRIPT_ALIASES.put("Phag", "Phag");
        SCRIPT_ALIASES.put("Phags_Pa", "Phag");
        SCRIPT_ALIASES.put("Phli", "Phli");
        SCRIPT_ALIASES.put("Phlp", "Phlp");
        SCRIPT_ALIASES.put("Phnx", "Phnx");
        SCRIPT_ALIASES.put("Phoenician", "Phnx");
        SCRIPT_ALIASES.put("Plrd", "Plrd");
        SCRIPT_ALIASES.put("Prti", "Prti");
        SCRIPT_ALIASES.put("Psalter_Pahlavi", "Phlp");
        SCRIPT_ALIASES.put("Qaac", "Copt");
        SCRIPT_ALIASES.put("Qaai", "Zinh");
        SCRIPT_ALIASES.put("Rejang", "Rjng");
        SCRIPT_ALIASES.put("Rjng", "Rjng");
        SCRIPT_ALIASES.put("Rohg", "Rohg");
        SCRIPT_ALIASES.put("Runic", "Runr");
        SCRIPT_ALIASES.put("Runr", "Runr");
        SCRIPT_ALIASES.put("Samaritan", "Samr");
        SCRIPT_ALIASES.put("Samr", "Samr");
        SCRIPT_ALIASES.put("Sarb", "Sarb");
        SCRIPT_ALIASES.put("Saur", "Saur");
        SCRIPT_ALIASES.put("Saurashtra", "Saur");
        SCRIPT_ALIASES.put("Sgnw", "Sgnw");
        SCRIPT_ALIASES.put("Sharada", "Shrd");
        SCRIPT_ALIASES.put("Shavian", "Shaw");
        SCRIPT_ALIASES.put("Shaw", "Shaw");
        SCRIPT_ALIASES.put("Shrd", "Shrd");
        SCRIPT_ALIASES.put("Sidd", "Sidd");
        SCRIPT_ALIASES.put("Siddham", "Sidd");
        SCRIPT_ALIASES.put("SignWriting", "Sgnw");
        SCRIPT_ALIASES.put("Sind", "Sind");
        SCRIPT_ALIASES.put("Sinh", "Sinh");
        SCRIPT_ALIASES.put("Sinhala", "Sinh");
        SCRIPT_ALIASES.put("Sogd", "Sogd");
        SCRIPT_ALIASES.put("Sogdian", "Sogd");
        SCRIPT_ALIASES.put("Sogo", "Sogo");
        SCRIPT_ALIASES.put("Sora", "Sora");
        SCRIPT_ALIASES.put("Sora_Sompeng", "Sora");
        SCRIPT_ALIASES.put("Soyo", "Soyo");
        SCRIPT_ALIASES.put("Soyombo", "Soyo");
        SCRIPT_ALIASES.put("Sund", "Sund");
        SCRIPT_ALIASES.put("Sundanese", "Sund");
        SCRIPT_ALIASES.put("Sylo", "Sylo");
        SCRIPT_ALIASES.put("Syloti_Nagri", "Sylo");
        SCRIPT_ALIASES.put("Syrc", "Syrc");
        SCRIPT_ALIASES.put("Syriac", "Syrc");
        SCRIPT_ALIASES.put("Tagalog", "Tglg");
        SCRIPT_ALIASES.put("Tagb", "Tagb");
        SCRIPT_ALIASES.put("Tagbanwa", "Tagb");
        SCRIPT_ALIASES.put("Tai_Le", "Tale");
        SCRIPT_ALIASES.put("Tai_Tham", "Lana");
        SCRIPT_ALIASES.put("Tai_Viet", "Tavt");
        SCRIPT_ALIASES.put("Takr", "Takr");
        SCRIPT_ALIASES.put("Takri", "Takr");
        SCRIPT_ALIASES.put("Tale", "Tale");
        SCRIPT_ALIASES.put("Talu", "Talu");
        SCRIPT_ALIASES.put("Tamil", "Taml");
        SCRIPT_ALIASES.put("Taml", "Taml");
        SCRIPT_ALIASES.put("Tang", "Tang");
        SCRIPT_ALIASES.put("Tangsa", "Tnsa");
        SCRIPT_ALIASES.put("Tangut", "Tang");
        SCRIPT_ALIASES.put("Tavt", "Tavt");
        SCRIPT_ALIASES.put("Telu", "Telu");
        SCRIPT_ALIASES.put("Telugu", "Telu");
        SCRIPT_ALIASES.put("Tfng", "Tfng");
        SCRIPT_ALIASES.put("Tglg", "Tglg");
        SCRIPT_ALIASES.put("Thaa", "Thaa");
        SCRIPT_ALIASES.put("Thaana", "Thaa");
        SCRIPT_ALIASES.put("Thai", "Thai");
        SCRIPT_ALIASES.put("Tibetan", "Tibt");
        SCRIPT_ALIASES.put("Tibt", "Tibt");
        SCRIPT_ALIASES.put("Tifinagh", "Tfng");
        SCRIPT_ALIASES.put("Tirh", "Tirh");
        SCRIPT_ALIASES.put("Tirhuta", "Tirh");
        SCRIPT_ALIASES.put("Tnsa", "Tnsa");
        SCRIPT_ALIASES.put("Toto", "Toto");
        SCRIPT_ALIASES.put("Ugar", "Ugar");
        SCRIPT_ALIASES.put("Ugaritic", "Ugar");
        SCRIPT_ALIASES.put("Unknown", "Zzzz");
        SCRIPT_ALIASES.put("Vai", "Vaii");
        SCRIPT_ALIASES.put("Vaii", "Vaii");
        SCRIPT_ALIASES.put("Vith", "Vith");
        SCRIPT_ALIASES.put("Vithkuqi", "Vith");
        SCRIPT_ALIASES.put("Wancho", "Wcho");
        SCRIPT_ALIASES.put("Wara", "Wara");
        SCRIPT_ALIASES.put("Warang_Citi", "Wara");
        SCRIPT_ALIASES.put("Wcho", "Wcho");
        SCRIPT_ALIASES.put("Xpeo", "Xpeo");
        SCRIPT_ALIASES.put("Xsux", "Xsux");
        SCRIPT_ALIASES.put("Yezi", "Yezi");
        SCRIPT_ALIASES.put("Yezidi", "Yezi");
        SCRIPT_ALIASES.put("Yi", "Yiii");
        SCRIPT_ALIASES.put("Yiii", "Yiii");
        SCRIPT_ALIASES.put("Zanabazar_Square", "Zanb");
        SCRIPT_ALIASES.put("Zanb", "Zanb");
        SCRIPT_ALIASES.put("Zinh", "Zinh");
        SCRIPT_ALIASES.put("Zyyy", "Zyyy");
        SCRIPT_ALIASES.put("Zzzz", "Zzzz");
        BLOCK_ALIASES.put("ASCII", "ASCII");
        BLOCK_ALIASES.put("Adlam", "Adlam");
        BLOCK_ALIASES.put("Aegean_Numbers", "Aegean_Numbers");
        BLOCK_ALIASES.put("Ahom", "Ahom");
        BLOCK_ALIASES.put("Alchemical", "Alchemical");
        BLOCK_ALIASES.put("Alchemical_Symbols", "Alchemical");
        BLOCK_ALIASES.put("Alphabetic_PF", "Alphabetic_PF");
        BLOCK_ALIASES.put("Alphabetic_Presentation_Forms", "Alphabetic_PF");
        BLOCK_ALIASES.put("Anatolian_Hieroglyphs", "Anatolian_Hieroglyphs");
        BLOCK_ALIASES.put("Ancient_Greek_Music", "Ancient_Greek_Music");
        BLOCK_ALIASES.put("Ancient_Greek_Musical_Notation", "Ancient_Greek_Music");
        BLOCK_ALIASES.put("Ancient_Greek_Numbers", "Ancient_Greek_Numbers");
        BLOCK_ALIASES.put("Ancient_Symbols", "Ancient_Symbols");
        BLOCK_ALIASES.put("Arabic", "Arabic");
        BLOCK_ALIASES.put("Arabic_Ext_A", "Arabic_Ext_A");
        BLOCK_ALIASES.put("Arabic_Ext_B", "Arabic_Ext_B");
        BLOCK_ALIASES.put("Arabic_Ext_C", "Arabic_Ext_C");
        BLOCK_ALIASES.put("Arabic_Extended_A", "Arabic_Ext_A");
        BLOCK_ALIASES.put("Arabic_Extended_B", "Arabic_Ext_B");
        BLOCK_ALIASES.put("Arabic_Extended_C", "Arabic_Ext_C");
        BLOCK_ALIASES.put("Arabic_Math", "Arabic_Math");
        BLOCK_ALIASES.put("Arabic_Mathematical_Alphabetic_Symbols", "Arabic_Math");
        BLOCK_ALIASES.put("Arabic_PF_A", "Arabic_PF_A");
        BLOCK_ALIASES.put("Arabic_PF_B", "Arabic_PF_B");
        BLOCK_ALIASES.put("Arabic_Presentation_Forms-A", "Arabic_PF_A");
        BLOCK_ALIASES.put("Arabic_Presentation_Forms_A", "Arabic_PF_A");
        BLOCK_ALIASES.put("Arabic_Presentation_Forms_B", "Arabic_PF_B");
        BLOCK_ALIASES.put("Arabic_Sup", "Arabic_Sup");
        BLOCK_ALIASES.put("Arabic_Supplement", "Arabic_Sup");
        BLOCK_ALIASES.put("Armenian", "Armenian");
        BLOCK_ALIASES.put("Arrows", "Arrows");
        BLOCK_ALIASES.put("Avestan", "Avestan");
        BLOCK_ALIASES.put("Balinese", "Balinese");
        BLOCK_ALIASES.put("Bamum", "Bamum");
        BLOCK_ALIASES.put("Bamum_Sup", "Bamum_Sup");
        BLOCK_ALIASES.put("Bamum_Supplement", "Bamum_Sup");
        BLOCK_ALIASES.put("Basic_Latin", "ASCII");
        BLOCK_ALIASES.put("Bassa_Vah", "Bassa_Vah");
        BLOCK_ALIASES.put("Batak", "Batak");
        BLOCK_ALIASES.put("Bengali", "Bengali");
        BLOCK_ALIASES.put("Bhaiksuki", "Bhaiksuki");
        BLOCK_ALIASES.put("Block_Elements", "Block_Elements");
        BLOCK_ALIASES.put("Bopomofo", "Bopomofo");
        BLOCK_ALIASES.put("Bopomofo_Ext", "Bopomofo_Ext");
        BLOCK_ALIASES.put("Bopomofo_Extended", "Bopomofo_Ext");
        BLOCK_ALIASES.put("Box_Drawing", "Box_Drawing");
        BLOCK_ALIASES.put("Brahmi", "Brahmi");
        BLOCK_ALIASES.put("Braille", "Braille");
        BLOCK_ALIASES.put("Braille_Patterns", "Braille");
        BLOCK_ALIASES.put("Buginese", "Buginese");
        BLOCK_ALIASES.put("Buhid", "Buhid");
        BLOCK_ALIASES.put("Byzantine_Music", "Byzantine_Music");
        BLOCK_ALIASES.put("Byzantine_Musical_Symbols", "Byzantine_Music");
        BLOCK_ALIASES.put("CJK", "CJK");
        BLOCK_ALIASES.put("CJK_Compat", "CJK_Compat");
        BLOCK_ALIASES.put("CJK_Compat_Forms", "CJK_Compat_Forms");
        BLOCK_ALIASES.put("CJK_Compat_Ideographs", "CJK_Compat_Ideographs");
        BLOCK_ALIASES.put("CJK_Compat_Ideographs_Sup", "CJK_Compat_Ideographs_Sup");
        BLOCK_ALIASES.put("CJK_Compatibility", "CJK_Compat");
        BLOCK_ALIASES.put("CJK_Compatibility_Forms", "CJK_Compat_Forms");
        BLOCK_ALIASES.put("CJK_Compatibility_Ideographs", "CJK_Compat_Ideographs");
        BLOCK_ALIASES.put("CJK_Compatibility_Ideographs_Supplement", "CJK_Compat_Ideographs_Sup");
        BLOCK_ALIASES.put("CJK_Ext_A", "CJK_Ext_A");
        BLOCK_ALIASES.put("CJK_Ext_B", "CJK_Ext_B");
        BLOCK_ALIASES.put("CJK_Ext_C", "CJK_Ext_C");
        BLOCK_ALIASES.put("CJK_Ext_D", "CJK_Ext_D");
        BLOCK_ALIASES.put("CJK_Ext_E", "CJK_Ext_E");
        BLOCK_ALIASES.put("CJK_Ext_F", "CJK_Ext_F");
        BLOCK_ALIASES.put("CJK_Ext_G", "CJK_Ext_G");
        BLOCK_ALIASES.put("CJK_Ext_H", "CJK_Ext_H");
        BLOCK_ALIASES.put("CJK_Radicals_Sup", "CJK_Radicals_Sup");
        BLOCK_ALIASES.put("CJK_Radicals_Supplement", "CJK_Radicals_Sup");
        BLOCK_ALIASES.put("CJK_Strokes", "CJK_Strokes");
        BLOCK_ALIASES.put("CJK_Symbols", "CJK_Symbols");
        BLOCK_ALIASES.put("CJK_Symbols_And_Punctuation", "CJK_Symbols");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs", "CJK");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs_Extension_A", "CJK_Ext_A");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs_Extension_B", "CJK_Ext_B");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs_Extension_C", "CJK_Ext_C");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs_Extension_D", "CJK_Ext_D");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs_Extension_E", "CJK_Ext_E");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs_Extension_F", "CJK_Ext_F");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs_Extension_G", "CJK_Ext_G");
        BLOCK_ALIASES.put("CJK_Unified_Ideographs_Extension_H", "CJK_Ext_H");
        BLOCK_ALIASES.put("Canadian_Syllabics", "UCAS");
        BLOCK_ALIASES.put("Carian", "Carian");
        BLOCK_ALIASES.put("Caucasian_Albanian", "Caucasian_Albanian");
        BLOCK_ALIASES.put("Chakma", "Chakma");
        BLOCK_ALIASES.put("Cham", "Cham");
        BLOCK_ALIASES.put("Cherokee", "Cherokee");
        BLOCK_ALIASES.put("Cherokee_Sup", "Cherokee_Sup");
        BLOCK_ALIASES.put("Cherokee_Supplement", "Cherokee_Sup");
        BLOCK_ALIASES.put("Chess_Symbols", "Chess_Symbols");
        BLOCK_ALIASES.put("Chorasmian", "Chorasmian");
        BLOCK_ALIASES.put("Combining_Diacritical_Marks", "Diacriticals");
        BLOCK_ALIASES.put("Combining_Diacritical_Marks_Extended", "Diacriticals_Ext");
        BLOCK_ALIASES.put("Combining_Diacritical_Marks_For_Symbols", "Diacriticals_For_Symbols");
        BLOCK_ALIASES.put("Combining_Diacritical_Marks_Supplement", "Diacriticals_Sup");
        BLOCK_ALIASES.put("Combining_Half_Marks", "Half_Marks");
        BLOCK_ALIASES.put("Combining_Marks_For_Symbols", "Diacriticals_For_Symbols");
        BLOCK_ALIASES.put("Common_Indic_Number_Forms", "Indic_Number_Forms");
        BLOCK_ALIASES.put("Compat_Jamo", "Compat_Jamo");
        BLOCK_ALIASES.put("Control_Pictures", "Control_Pictures");
        BLOCK_ALIASES.put("Coptic", "Coptic");
        BLOCK_ALIASES.put("Coptic_Epact_Numbers", "Coptic_Epact_Numbers");
        BLOCK_ALIASES.put("Counting_Rod", "Counting_Rod");
        BLOCK_ALIASES.put("Counting_Rod_Numerals", "Counting_Rod");
        BLOCK_ALIASES.put("Cuneiform", "Cuneiform");
        BLOCK_ALIASES.put("Cuneiform_Numbers", "Cuneiform_Numbers");
        BLOCK_ALIASES.put("Cuneiform_Numbers_And_Punctuation", "Cuneiform_Numbers");
        BLOCK_ALIASES.put("Currency_Symbols", "Currency_Symbols");
        BLOCK_ALIASES.put("Cypriot_Syllabary", "Cypriot_Syllabary");
        BLOCK_ALIASES.put("Cypro_Minoan", "Cypro_Minoan");
        BLOCK_ALIASES.put("Cyrillic", "Cyrillic");
        BLOCK_ALIASES.put("Cyrillic_Ext_A", "Cyrillic_Ext_A");
        BLOCK_ALIASES.put("Cyrillic_Ext_B", "Cyrillic_Ext_B");
        BLOCK_ALIASES.put("Cyrillic_Ext_C", "Cyrillic_Ext_C");
        BLOCK_ALIASES.put("Cyrillic_Ext_D", "Cyrillic_Ext_D");
        BLOCK_ALIASES.put("Cyrillic_Extended_A", "Cyrillic_Ext_A");
        BLOCK_ALIASES.put("Cyrillic_Extended_B", "Cyrillic_Ext_B");
        BLOCK_ALIASES.put("Cyrillic_Extended_C", "Cyrillic_Ext_C");
        BLOCK_ALIASES.put("Cyrillic_Extended_D", "Cyrillic_Ext_D");
        BLOCK_ALIASES.put("Cyrillic_Sup", "Cyrillic_Sup");
        BLOCK_ALIASES.put("Cyrillic_Supplement", "Cyrillic_Sup");
        BLOCK_ALIASES.put("Cyrillic_Supplementary", "Cyrillic_Sup");
        BLOCK_ALIASES.put("Deseret", "Deseret");
        BLOCK_ALIASES.put("Devanagari", "Devanagari");
        BLOCK_ALIASES.put("Devanagari_Ext", "Devanagari_Ext");
        BLOCK_ALIASES.put("Devanagari_Ext_A", "Devanagari_Ext_A");
        BLOCK_ALIASES.put("Devanagari_Extended", "Devanagari_Ext");
        BLOCK_ALIASES.put("Devanagari_Extended_A", "Devanagari_Ext_A");
        BLOCK_ALIASES.put("Diacriticals", "Diacriticals");
        BLOCK_ALIASES.put("Diacriticals_Ext", "Diacriticals_Ext");
        BLOCK_ALIASES.put("Diacriticals_For_Symbols", "Diacriticals_For_Symbols");
        BLOCK_ALIASES.put("Diacriticals_Sup", "Diacriticals_Sup");
        BLOCK_ALIASES.put("Dingbats", "Dingbats");
        BLOCK_ALIASES.put("Dives_Akuru", "Dives_Akuru");
        BLOCK_ALIASES.put("Dogra", "Dogra");
        BLOCK_ALIASES.put("Domino", "Domino");
        BLOCK_ALIASES.put("Domino_Tiles", "Domino");
        BLOCK_ALIASES.put("Duployan", "Duployan");
        BLOCK_ALIASES.put("Early_Dynastic_Cuneiform", "Early_Dynastic_Cuneiform");
        BLOCK_ALIASES.put("Egyptian_Hieroglyph_Format_Controls", "Egyptian_Hieroglyph_Format_Controls");
        BLOCK_ALIASES.put("Egyptian_Hieroglyphs", "Egyptian_Hieroglyphs");
        BLOCK_ALIASES.put("Elbasan", "Elbasan");
        BLOCK_ALIASES.put("Elymaic", "Elymaic");
        BLOCK_ALIASES.put("Emoticons", "Emoticons");
        BLOCK_ALIASES.put("Enclosed_Alphanum", "Enclosed_Alphanum");
        BLOCK_ALIASES.put("Enclosed_Alphanum_Sup", "Enclosed_Alphanum_Sup");
        BLOCK_ALIASES.put("Enclosed_Alphanumeric_Supplement", "Enclosed_Alphanum_Sup");
        BLOCK_ALIASES.put("Enclosed_Alphanumerics", "Enclosed_Alphanum");
        BLOCK_ALIASES.put("Enclosed_CJK", "Enclosed_CJK");
        BLOCK_ALIASES.put("Enclosed_CJK_Letters_And_Months", "Enclosed_CJK");
        BLOCK_ALIASES.put("Enclosed_Ideographic_Sup", "Enclosed_Ideographic_Sup");
        BLOCK_ALIASES.put("Enclosed_Ideographic_Supplement", "Enclosed_Ideographic_Sup");
        BLOCK_ALIASES.put("Ethiopic", "Ethiopic");
        BLOCK_ALIASES.put("Ethiopic_Ext", "Ethiopic_Ext");
        BLOCK_ALIASES.put("Ethiopic_Ext_A", "Ethiopic_Ext_A");
        BLOCK_ALIASES.put("Ethiopic_Ext_B", "Ethiopic_Ext_B");
        BLOCK_ALIASES.put("Ethiopic_Extended", "Ethiopic_Ext");
        BLOCK_ALIASES.put("Ethiopic_Extended_A", "Ethiopic_Ext_A");
        BLOCK_ALIASES.put("Ethiopic_Extended_B", "Ethiopic_Ext_B");
        BLOCK_ALIASES.put("Ethiopic_Sup", "Ethiopic_Sup");
        BLOCK_ALIASES.put("Ethiopic_Supplement", "Ethiopic_Sup");
        BLOCK_ALIASES.put("General_Punctuation", "Punctuation");
        BLOCK_ALIASES.put("Geometric_Shapes", "Geometric_Shapes");
        BLOCK_ALIASES.put("Geometric_Shapes_Ext", "Geometric_Shapes_Ext");
        BLOCK_ALIASES.put("Geometric_Shapes_Extended", "Geometric_Shapes_Ext");
        BLOCK_ALIASES.put("Georgian", "Georgian");
        BLOCK_ALIASES.put("Georgian_Ext", "Georgian_Ext");
        BLOCK_ALIASES.put("Georgian_Extended", "Georgian_Ext");
        BLOCK_ALIASES.put("Georgian_Sup", "Georgian_Sup");
        BLOCK_ALIASES.put("Georgian_Supplement", "Georgian_Sup");
        BLOCK_ALIASES.put("Glagolitic", "Glagolitic");
        BLOCK_ALIASES.put("Glagolitic_Sup", "Glagolitic_Sup");
        BLOCK_ALIASES.put("Glagolitic_Supplement", "Glagolitic_Sup");
        BLOCK_ALIASES.put("Gothic", "Gothic");
        BLOCK_ALIASES.put("Grantha", "Grantha");
        BLOCK_ALIASES.put("Greek", "Greek");
        BLOCK_ALIASES.put("Greek_And_Coptic", "Greek");
        BLOCK_ALIASES.put("Greek_Ext", "Greek_Ext");
        BLOCK_ALIASES.put("Greek_Extended", "Greek_Ext");
        BLOCK_ALIASES.put("Gujarati", "Gujarati");
        BLOCK_ALIASES.put("Gunjala_Gondi", "Gunjala_Gondi");
        BLOCK_ALIASES.put("Gurmukhi", "Gurmukhi");
        BLOCK_ALIASES.put("Half_And_Full_Forms", "Half_And_Full_Forms");
        BLOCK_ALIASES.put("Half_Marks", "Half_Marks");
        BLOCK_ALIASES.put("Halfwidth_And_Fullwidth_Forms", "Half_And_Full_Forms");
        BLOCK_ALIASES.put("Hangul", "Hangul");
        BLOCK_ALIASES.put("Hangul_Compatibility_Jamo", "Compat_Jamo");
        BLOCK_ALIASES.put("Hangul_Jamo", "Jamo");
        BLOCK_ALIASES.put("Hangul_Jamo_Extended_A", "Jamo_Ext_A");
        BLOCK_ALIASES.put("Hangul_Jamo_Extended_B", "Jamo_Ext_B");
        BLOCK_ALIASES.put("Hangul_Syllables", "Hangul");
        BLOCK_ALIASES.put("Hanifi_Rohingya", "Hanifi_Rohingya");
        BLOCK_ALIASES.put("Hanunoo", "Hanunoo");
        BLOCK_ALIASES.put("Hatran", "Hatran");
        BLOCK_ALIASES.put("Hebrew", "Hebrew");
        BLOCK_ALIASES.put("High_PU_Surrogates", "High_PU_Surrogates");
        BLOCK_ALIASES.put("High_Private_Use_Surrogates", "High_PU_Surrogates");
        BLOCK_ALIASES.put("High_Surrogates", "High_Surrogates");
        BLOCK_ALIASES.put("Hiragana", "Hiragana");
        BLOCK_ALIASES.put("IDC", "IDC");
        BLOCK_ALIASES.put("IPA_Ext", "IPA_Ext");
        BLOCK_ALIASES.put("IPA_Extensions", "IPA_Ext");
        BLOCK_ALIASES.put("Ideographic_Description_Characters", "IDC");
        BLOCK_ALIASES.put("Ideographic_Symbols", "Ideographic_Symbols");
        BLOCK_ALIASES.put("Ideographic_Symbols_And_Punctuation", "Ideographic_Symbols");
        BLOCK_ALIASES.put("Imperial_Aramaic", "Imperial_Aramaic");
        BLOCK_ALIASES.put("Indic_Number_Forms", "Indic_Number_Forms");
        BLOCK_ALIASES.put("Indic_Siyaq_Numbers", "Indic_Siyaq_Numbers");
        BLOCK_ALIASES.put("Inscriptional_Pahlavi", "Inscriptional_Pahlavi");
        BLOCK_ALIASES.put("Inscriptional_Parthian", "Inscriptional_Parthian");
        BLOCK_ALIASES.put("Jamo", "Jamo");
        BLOCK_ALIASES.put("Jamo_Ext_A", "Jamo_Ext_A");
        BLOCK_ALIASES.put("Jamo_Ext_B", "Jamo_Ext_B");
        BLOCK_ALIASES.put("Javanese", "Javanese");
        BLOCK_ALIASES.put("Kaithi", "Kaithi");
        BLOCK_ALIASES.put("Kaktovik_Numerals", "Kaktovik_Numerals");
        BLOCK_ALIASES.put("Kana_Ext_A", "Kana_Ext_A");
        BLOCK_ALIASES.put("Kana_Ext_B", "Kana_Ext_B");
        BLOCK_ALIASES.put("Kana_Extended_A", "Kana_Ext_A");
        BLOCK_ALIASES.put("Kana_Extended_B", "Kana_Ext_B");
        BLOCK_ALIASES.put("Kana_Sup", "Kana_Sup");
        BLOCK_ALIASES.put("Kana_Supplement", "Kana_Sup");
        BLOCK_ALIASES.put("Kanbun", "Kanbun");
        BLOCK_ALIASES.put("Kangxi", "Kangxi");
        BLOCK_ALIASES.put("Kangxi_Radicals", "Kangxi");
        BLOCK_ALIASES.put("Kannada", "Kannada");
        BLOCK_ALIASES.put("Katakana", "Katakana");
        BLOCK_ALIASES.put("Katakana_Ext", "Katakana_Ext");
        BLOCK_ALIASES.put("Katakana_Phonetic_Extensions", "Katakana_Ext");
        BLOCK_ALIASES.put("Kawi", "Kawi");
        BLOCK_ALIASES.put("Kayah_Li", "Kayah_Li");
        BLOCK_ALIASES.put("Kharoshthi", "Kharoshthi");
        BLOCK_ALIASES.put("Khitan_Small_Script", "Khitan_Small_Script");
        BLOCK_ALIASES.put("Khmer", "Khmer");
        BLOCK_ALIASES.put("Khmer_Symbols", "Khmer_Symbols");
        BLOCK_ALIASES.put("Khojki", "Khojki");
        BLOCK_ALIASES.put("Khudawadi", "Khudawadi");
        BLOCK_ALIASES.put("Lao", "Lao");
        BLOCK_ALIASES.put("Latin_1", "Latin_1_Sup");
        BLOCK_ALIASES.put("Latin_1_Sup", "Latin_1_Sup");
        BLOCK_ALIASES.put("Latin_1_Supplement", "Latin_1_Sup");
        BLOCK_ALIASES.put("Latin_Ext_A", "Latin_Ext_A");
        BLOCK_ALIASES.put("Latin_Ext_Additional", "Latin_Ext_Additional");
        BLOCK_ALIASES.put("Latin_Ext_B", "Latin_Ext_B");
        BLOCK_ALIASES.put("Latin_Ext_C", "Latin_Ext_C");
        BLOCK_ALIASES.put("Latin_Ext_D", "Latin_Ext_D");
        BLOCK_ALIASES.put("Latin_Ext_E", "Latin_Ext_E");
        BLOCK_ALIASES.put("Latin_Ext_F", "Latin_Ext_F");
        BLOCK_ALIASES.put("Latin_Ext_G", "Latin_Ext_G");
        BLOCK_ALIASES.put("Latin_Extended_A", "Latin_Ext_A");
        BLOCK_ALIASES.put("Latin_Extended_Additional", "Latin_Ext_Additional");
        BLOCK_ALIASES.put("Latin_Extended_B", "Latin_Ext_B");
        BLOCK_ALIASES.put("Latin_Extended_C", "Latin_Ext_C");
        BLOCK_ALIASES.put("Latin_Extended_D", "Latin_Ext_D");
        BLOCK_ALIASES.put("Latin_Extended_E", "Latin_Ext_E");
        BLOCK_ALIASES.put("Latin_Extended_F", "Latin_Ext_F");
        BLOCK_ALIASES.put("Latin_Extended_G", "Latin_Ext_G");
        BLOCK_ALIASES.put("Lepcha", "Lepcha");
        BLOCK_ALIASES.put("Letterlike_Symbols", "Letterlike_Symbols");
        BLOCK_ALIASES.put("Limbu", "Limbu");
        BLOCK_ALIASES.put("Linear_A", "Linear_A");
        BLOCK_ALIASES.put("Linear_B_Ideograms", "Linear_B_Ideograms");
        BLOCK_ALIASES.put("Linear_B_Syllabary", "Linear_B_Syllabary");
        BLOCK_ALIASES.put("Lisu", "Lisu");
        BLOCK_ALIASES.put("Lisu_Sup", "Lisu_Sup");
        BLOCK_ALIASES.put("Lisu_Supplement", "Lisu_Sup");
        BLOCK_ALIASES.put("Low_Surrogates", "Low_Surrogates");
        BLOCK_ALIASES.put("Lycian", "Lycian");
        BLOCK_ALIASES.put("Lydian", "Lydian");
        BLOCK_ALIASES.put("Mahajani", "Mahajani");
        BLOCK_ALIASES.put("Mahjong", "Mahjong");
        BLOCK_ALIASES.put("Mahjong_Tiles", "Mahjong");
        BLOCK_ALIASES.put("Makasar", "Makasar");
        BLOCK_ALIASES.put("Malayalam", "Malayalam");
        BLOCK_ALIASES.put("Mandaic", "Mandaic");
        BLOCK_ALIASES.put("Manichaean", "Manichaean");
        BLOCK_ALIASES.put("Marchen", "Marchen");
        BLOCK_ALIASES.put("Masaram_Gondi", "Masaram_Gondi");
        BLOCK_ALIASES.put("Math_Alphanum", "Math_Alphanum");
        BLOCK_ALIASES.put("Math_Operators", "Math_Operators");
        BLOCK_ALIASES.put("Mathematical_Alphanumeric_Symbols", "Math_Alphanum");
        BLOCK_ALIASES.put("Mathematical_Operators", "Math_Operators");
        BLOCK_ALIASES.put("Mayan_Numerals", "Mayan_Numerals");
        BLOCK_ALIASES.put("Medefaidrin", "Medefaidrin");
        BLOCK_ALIASES.put("Meetei_Mayek", "Meetei_Mayek");
        BLOCK_ALIASES.put("Meetei_Mayek_Ext", "Meetei_Mayek_Ext");
        BLOCK_ALIASES.put("Meetei_Mayek_Extensions", "Meetei_Mayek_Ext");
        BLOCK_ALIASES.put("Mende_Kikakui", "Mende_Kikakui");
        BLOCK_ALIASES.put("Meroitic_Cursive", "Meroitic_Cursive");
        BLOCK_ALIASES.put("Meroitic_Hieroglyphs", "Meroitic_Hieroglyphs");
        BLOCK_ALIASES.put("Miao", "Miao");
        BLOCK_ALIASES.put("Misc_Arrows", "Misc_Arrows");
        BLOCK_ALIASES.put("Misc_Math_Symbols_A", "Misc_Math_Symbols_A");
        BLOCK_ALIASES.put("Misc_Math_Symbols_B", "Misc_Math_Symbols_B");
        BLOCK_ALIASES.put("Misc_Pictographs", "Misc_Pictographs");
        BLOCK_ALIASES.put("Misc_Symbols", "Misc_Symbols");
        BLOCK_ALIASES.put("Misc_Technical", "Misc_Technical");
        BLOCK_ALIASES.put("Miscellaneous_Mathematical_Symbols_A", "Misc_Math_Symbols_A");
        BLOCK_ALIASES.put("Miscellaneous_Mathematical_Symbols_B", "Misc_Math_Symbols_B");
        BLOCK_ALIASES.put("Miscellaneous_Symbols", "Misc_Symbols");
        BLOCK_ALIASES.put("Miscellaneous_Symbols_And_Arrows", "Misc_Arrows");
        BLOCK_ALIASES.put("Miscellaneous_Symbols_And_Pictographs", "Misc_Pictographs");
        BLOCK_ALIASES.put("Miscellaneous_Technical", "Misc_Technical");
        BLOCK_ALIASES.put("Modi", "Modi");
        BLOCK_ALIASES.put("Modifier_Letters", "Modifier_Letters");
        BLOCK_ALIASES.put("Modifier_Tone_Letters", "Modifier_Tone_Letters");
        BLOCK_ALIASES.put("Mongolian", "Mongolian");
        BLOCK_ALIASES.put("Mongolian_Sup", "Mongolian_Sup");
        BLOCK_ALIASES.put("Mongolian_Supplement", "Mongolian_Sup");
        BLOCK_ALIASES.put("Mro", "Mro");
        BLOCK_ALIASES.put("Multani", "Multani");
        BLOCK_ALIASES.put("Music", "Music");
        BLOCK_ALIASES.put("Musical_Symbols", "Music");
        BLOCK_ALIASES.put("Myanmar", "Myanmar");
        BLOCK_ALIASES.put("Myanmar_Ext_A", "Myanmar_Ext_A");
        BLOCK_ALIASES.put("Myanmar_Ext_B", "Myanmar_Ext_B");
        BLOCK_ALIASES.put("Myanmar_Extended_A", "Myanmar_Ext_A");
        BLOCK_ALIASES.put("Myanmar_Extended_B", "Myanmar_Ext_B");
        BLOCK_ALIASES.put("NB", "NB");
        BLOCK_ALIASES.put("NKo", "NKo");
        BLOCK_ALIASES.put("Nabataean", "Nabataean");
        BLOCK_ALIASES.put("Nag_Mundari", "Nag_Mundari");
        BLOCK_ALIASES.put("Nandinagari", "Nandinagari");
        BLOCK_ALIASES.put("New_Tai_Lue", "New_Tai_Lue");
        BLOCK_ALIASES.put("Newa", "Newa");
        BLOCK_ALIASES.put("No_Block", "NB");
        BLOCK_ALIASES.put("Number_Forms", "Number_Forms");
        BLOCK_ALIASES.put("Nushu", "Nushu");
        BLOCK_ALIASES.put("Nyiakeng_Puachue_Hmong", "Nyiakeng_Puachue_Hmong");
        BLOCK_ALIASES.put("OCR", "OCR");
        BLOCK_ALIASES.put("Ogham", "Ogham");
        BLOCK_ALIASES.put("Ol_Chiki", "Ol_Chiki");
        BLOCK_ALIASES.put("Old_Hungarian", "Old_Hungarian");
        BLOCK_ALIASES.put("Old_Italic", "Old_Italic");
        BLOCK_ALIASES.put("Old_North_Arabian", "Old_North_Arabian");
        BLOCK_ALIASES.put("Old_Permic", "Old_Permic");
        BLOCK_ALIASES.put("Old_Persian", "Old_Persian");
        BLOCK_ALIASES.put("Old_Sogdian", "Old_Sogdian");
        BLOCK_ALIASES.put("Old_South_Arabian", "Old_South_Arabian");
        BLOCK_ALIASES.put("Old_Turkic", "Old_Turkic");
        BLOCK_ALIASES.put("Old_Uyghur", "Old_Uyghur");
        BLOCK_ALIASES.put("Optical_Character_Recognition", "OCR");
        BLOCK_ALIASES.put("Oriya", "Oriya");
        BLOCK_ALIASES.put("Ornamental_Dingbats", "Ornamental_Dingbats");
        BLOCK_ALIASES.put("Osage", "Osage");
        BLOCK_ALIASES.put("Osmanya", "Osmanya");
        BLOCK_ALIASES.put("Ottoman_Siyaq_Numbers", "Ottoman_Siyaq_Numbers");
        BLOCK_ALIASES.put("PUA", "PUA");
        BLOCK_ALIASES.put("Pahawh_Hmong", "Pahawh_Hmong");
        BLOCK_ALIASES.put("Palmyrene", "Palmyrene");
        BLOCK_ALIASES.put("Pau_Cin_Hau", "Pau_Cin_Hau");
        BLOCK_ALIASES.put("Phags_Pa", "Phags_Pa");
        BLOCK_ALIASES.put("Phaistos", "Phaistos");
        BLOCK_ALIASES.put("Phaistos_Disc", "Phaistos");
        BLOCK_ALIASES.put("Phoenician", "Phoenician");
        BLOCK_ALIASES.put("Phonetic_Ext", "Phonetic_Ext");
        BLOCK_ALIASES.put("Phonetic_Ext_Sup", "Phonetic_Ext_Sup");
        BLOCK_ALIASES.put("Phonetic_Extensions", "Phonetic_Ext");
        BLOCK_ALIASES.put("Phonetic_Extensions_Supplement", "Phonetic_Ext_Sup");
        BLOCK_ALIASES.put("Playing_Cards", "Playing_Cards");
        BLOCK_ALIASES.put("Private_Use", "PUA");
        BLOCK_ALIASES.put("Private_Use_Area", "PUA");
        BLOCK_ALIASES.put("Psalter_Pahlavi", "Psalter_Pahlavi");
        BLOCK_ALIASES.put("Punctuation", "Punctuation");
        BLOCK_ALIASES.put("Rejang", "Rejang");
        BLOCK_ALIASES.put("Rumi", "Rumi");
        BLOCK_ALIASES.put("Rumi_Numeral_Symbols", "Rumi");
        BLOCK_ALIASES.put("Runic", "Runic");
        BLOCK_ALIASES.put("Samaritan", "Samaritan");
        BLOCK_ALIASES.put("Saurashtra", "Saurashtra");
        BLOCK_ALIASES.put("Sharada", "Sharada");
        BLOCK_ALIASES.put("Shavian", "Shavian");
        BLOCK_ALIASES.put("Shorthand_Format_Controls", "Shorthand_Format_Controls");
        BLOCK_ALIASES.put("Siddham", "Siddham");
        BLOCK_ALIASES.put("Sinhala", "Sinhala");
        BLOCK_ALIASES.put("Sinhala_Archaic_Numbers", "Sinhala_Archaic_Numbers");
        BLOCK_ALIASES.put("Small_Form_Variants", "Small_Forms");
        BLOCK_ALIASES.put("Small_Forms", "Small_Forms");
        BLOCK_ALIASES.put("Small_Kana_Ext", "Small_Kana_Ext");
        BLOCK_ALIASES.put("Small_Kana_Extension", "Small_Kana_Ext");
        BLOCK_ALIASES.put("Sogdian", "Sogdian");
        BLOCK_ALIASES.put("Sora_Sompeng", "Sora_Sompeng");
        BLOCK_ALIASES.put("Soyombo", "Soyombo");
        BLOCK_ALIASES.put("Spacing_Modifier_Letters", "Modifier_Letters");
        BLOCK_ALIASES.put("Specials", "Specials");
        BLOCK_ALIASES.put("Sundanese", "Sundanese");
        BLOCK_ALIASES.put("Sundanese_Sup", "Sundanese_Sup");
        BLOCK_ALIASES.put("Sundanese_Supplement", "Sundanese_Sup");
        BLOCK_ALIASES.put("Sup_Arrows_A", "Sup_Arrows_A");
        BLOCK_ALIASES.put("Sup_Arrows_B", "Sup_Arrows_B");
        BLOCK_ALIASES.put("Sup_Arrows_C", "Sup_Arrows_C");
        BLOCK_ALIASES.put("Sup_Math_Operators", "Sup_Math_Operators");
        BLOCK_ALIASES.put("Sup_PUA_A", "Sup_PUA_A");
        BLOCK_ALIASES.put("Sup_PUA_B", "Sup_PUA_B");
        BLOCK_ALIASES.put("Sup_Punctuation", "Sup_Punctuation");
        BLOCK_ALIASES.put("Sup_Symbols_And_Pictographs", "Sup_Symbols_And_Pictographs");
        BLOCK_ALIASES.put("Super_And_Sub", "Super_And_Sub");
        BLOCK_ALIASES.put("Superscripts_And_Subscripts", "Super_And_Sub");
        BLOCK_ALIASES.put("Supplemental_Arrows_A", "Sup_Arrows_A");
        BLOCK_ALIASES.put("Supplemental_Arrows_B", "Sup_Arrows_B");
        BLOCK_ALIASES.put("Supplemental_Arrows_C", "Sup_Arrows_C");
        BLOCK_ALIASES.put("Supplemental_Mathematical_Operators", "Sup_Math_Operators");
        BLOCK_ALIASES.put("Supplemental_Punctuation", "Sup_Punctuation");
        BLOCK_ALIASES.put("Supplemental_Symbols_And_Pictographs", "Sup_Symbols_And_Pictographs");
        BLOCK_ALIASES.put("Supplementary_Private_Use_Area_A", "Sup_PUA_A");
        BLOCK_ALIASES.put("Supplementary_Private_Use_Area_B", "Sup_PUA_B");
        BLOCK_ALIASES.put("Sutton_SignWriting", "Sutton_SignWriting");
        BLOCK_ALIASES.put("Syloti_Nagri", "Syloti_Nagri");
        BLOCK_ALIASES.put("Symbols_And_Pictographs_Ext_A", "Symbols_And_Pictographs_Ext_A");
        BLOCK_ALIASES.put("Symbols_And_Pictographs_Extended_A", "Symbols_And_Pictographs_Ext_A");
        BLOCK_ALIASES.put("Symbols_For_Legacy_Computing", "Symbols_For_Legacy_Computing");
        BLOCK_ALIASES.put("Syriac", "Syriac");
        BLOCK_ALIASES.put("Syriac_Sup", "Syriac_Sup");
        BLOCK_ALIASES.put("Syriac_Supplement", "Syriac_Sup");
        BLOCK_ALIASES.put("Tagalog", "Tagalog");
        BLOCK_ALIASES.put("Tagbanwa", "Tagbanwa");
        BLOCK_ALIASES.put("Tags", "Tags");
        BLOCK_ALIASES.put("Tai_Le", "Tai_Le");
        BLOCK_ALIASES.put("Tai_Tham", "Tai_Tham");
        BLOCK_ALIASES.put("Tai_Viet", "Tai_Viet");
        BLOCK_ALIASES.put("Tai_Xuan_Jing", "Tai_Xuan_Jing");
        BLOCK_ALIASES.put("Tai_Xuan_Jing_Symbols", "Tai_Xuan_Jing");
        BLOCK_ALIASES.put("Takri", "Takri");
        BLOCK_ALIASES.put("Tamil", "Tamil");
        BLOCK_ALIASES.put("Tamil_Sup", "Tamil_Sup");
        BLOCK_ALIASES.put("Tamil_Supplement", "Tamil_Sup");
        BLOCK_ALIASES.put("Tangsa", "Tangsa");
        BLOCK_ALIASES.put("Tangut", "Tangut");
        BLOCK_ALIASES.put("Tangut_Components", "Tangut_Components");
        BLOCK_ALIASES.put("Tangut_Sup", "Tangut_Sup");
        BLOCK_ALIASES.put("Tangut_Supplement", "Tangut_Sup");
        BLOCK_ALIASES.put("Telugu", "Telugu");
        BLOCK_ALIASES.put("Thaana", "Thaana");
        BLOCK_ALIASES.put("Thai", "Thai");
        BLOCK_ALIASES.put("Tibetan", "Tibetan");
        BLOCK_ALIASES.put("Tifinagh", "Tifinagh");
        BLOCK_ALIASES.put("Tirhuta", "Tirhuta");
        BLOCK_ALIASES.put("Toto", "Toto");
        BLOCK_ALIASES.put("Transport_And_Map", "Transport_And_Map");
        BLOCK_ALIASES.put("Transport_And_Map_Symbols", "Transport_And_Map");
        BLOCK_ALIASES.put("UCAS", "UCAS");
        BLOCK_ALIASES.put("UCAS_Ext", "UCAS_Ext");
        BLOCK_ALIASES.put("UCAS_Ext_A", "UCAS_Ext_A");
        BLOCK_ALIASES.put("Ugaritic", "Ugaritic");
        BLOCK_ALIASES.put("Unified_Canadian_Aboriginal_Syllabics", "UCAS");
        BLOCK_ALIASES.put("Unified_Canadian_Aboriginal_Syllabics_Extended", "UCAS_Ext");
        BLOCK_ALIASES.put("Unified_Canadian_Aboriginal_Syllabics_Extended_A", "UCAS_Ext_A");
        BLOCK_ALIASES.put("VS", "VS");
        BLOCK_ALIASES.put("VS_Sup", "VS_Sup");
        BLOCK_ALIASES.put("Vai", "Vai");
        BLOCK_ALIASES.put("Variation_Selectors", "VS");
        BLOCK_ALIASES.put("Variation_Selectors_Supplement", "VS_Sup");
        BLOCK_ALIASES.put("Vedic_Ext", "Vedic_Ext");
        BLOCK_ALIASES.put("Vedic_Extensions", "Vedic_Ext");
        BLOCK_ALIASES.put("Vertical_Forms", "Vertical_Forms");
        BLOCK_ALIASES.put("Vithkuqi", "Vithkuqi");
        BLOCK_ALIASES.put("Wancho", "Wancho");
        BLOCK_ALIASES.put("Warang_Citi", "Warang_Citi");
        BLOCK_ALIASES.put("Yezidi", "Yezidi");
        BLOCK_ALIASES.put("Yi_Radicals", "Yi_Radicals");
        BLOCK_ALIASES.put("Yi_Syllables", "Yi_Syllables");
        BLOCK_ALIASES.put("Yijing", "Yijing");
        BLOCK_ALIASES.put("Yijing_Hexagram_Symbols", "Yijing");
        BLOCK_ALIASES.put("Zanabazar_Square", "Zanabazar_Square");
        BLOCK_ALIASES.put("Znamenny_Music", "Znamenny_Music");
        BLOCK_ALIASES.put("Znamenny_Musical_Notation", "Znamenny_Music");
        populateAHEX();
        populateASCII();
        populateALPHA();
        populateANY();
        populateASSIGNED();
        populateBIDI_C();
        populateBIDI_M();
        populateCI();
        populateCWCF();
        populateCWCM();
        populateCWKCF();
        populateCWL();
        populateCWT();
        populateCWU();
        populateCASED();
        populateDI();
        populateDASH();
        populateDEP();
        populateDIA();
        populateEBASE();
        populateECOMP();
        populateEMOD();
        populateEPRES();
        populateEMOJI();
        populateEXT();
        populateEXTPICT();
        populateGR_BASE();
        populateGR_EXT();
        populateHEX();
        populateIDC();
        populateIDS();
        populateIDSB();
        populateIDST();
        populateIDEO();
        populateJOIN_C();
        populateLOE();
        populateLOWER();
        populateMATH();
        populateNCHAR();
        populateOALPHA();
        populateOIDC();
        populateOIDS();
        populateOLOWER();
        populateOUPPER();
        populatePAT_SYN();
        populatePAT_WS();
        populateQMARK();
        populateRI();
        populateRADICAL();
        populateSD();
        populateSTERM();
        populateTERM();
        populateUIDEO();
        populateUPPER();
        populateVS();
        populateWSPACE();
        populateXIDC();
        populateXIDS();
        populateBLK_ASCII();
        populateBLK_ADLAM();
        populateBLK_AEGEAN_NUMBERS();
        populateBLK_AHOM();
        populateBLK_ALCHEMICAL();
        populateBLK_ALPHABETIC_PF();
        populateBLK_ANATOLIAN_HIEROGLYPHS();
        populateBLK_ANCIENT_GREEK_MUSIC();
        populateBLK_ANCIENT_GREEK_NUMBERS();
        populateBLK_ANCIENT_SYMBOLS();
        populateBLK_ARABIC();
        populateBLK_ARABIC_EXT_A();
        populateBLK_ARABIC_EXT_B();
        populateBLK_ARABIC_EXT_C();
        populateBLK_ARABIC_MATH();
        populateBLK_ARABIC_PF_A();
        populateBLK_ARABIC_PF_B();
        populateBLK_ARABIC_SUP();
        populateBLK_ARMENIAN();
        populateBLK_ARROWS();
        populateBLK_AVESTAN();
        populateBLK_BALINESE();
        populateBLK_BAMUM();
        populateBLK_BAMUM_SUP();
        populateBLK_BASSA_VAH();
        populateBLK_BATAK();
        populateBLK_BENGALI();
        populateBLK_BHAIKSUKI();
        populateBLK_BLOCK_ELEMENTS();
        populateBLK_BOPOMOFO();
        populateBLK_BOPOMOFO_EXT();
        populateBLK_BOX_DRAWING();
        populateBLK_BRAHMI();
        populateBLK_BRAILLE();
        populateBLK_BUGINESE();
        populateBLK_BUHID();
        populateBLK_BYZANTINE_MUSIC();
        populateBLK_CJK();
        populateBLK_CJK_COMPAT();
        populateBLK_CJK_COMPAT_FORMS();
        populateBLK_CJK_COMPAT_IDEOGRAPHS();
        populateBLK_CJK_COMPAT_IDEOGRAPHS_SUP();
        populateBLK_CJK_EXT_A();
        populateBLK_CJK_EXT_B();
        populateBLK_CJK_EXT_C();
        populateBLK_CJK_EXT_D();
        populateBLK_CJK_EXT_E();
        populateBLK_CJK_EXT_F();
        populateBLK_CJK_EXT_G();
        populateBLK_CJK_EXT_H();
        populateBLK_CJK_RADICALS_SUP();
        populateBLK_CJK_STROKES();
        populateBLK_CJK_SYMBOLS();
        populateBLK_CARIAN();
        populateBLK_CAUCASIAN_ALBANIAN();
        populateBLK_CHAKMA();
        populateBLK_CHAM();
        populateBLK_CHEROKEE();
        populateBLK_CHEROKEE_SUP();
        populateBLK_CHESS_SYMBOLS();
        populateBLK_CHORASMIAN();
        populateBLK_COMPAT_JAMO();
        populateBLK_CONTROL_PICTURES();
        populateBLK_COPTIC();
        populateBLK_COPTIC_EPACT_NUMBERS();
        populateBLK_COUNTING_ROD();
        populateBLK_CUNEIFORM();
        populateBLK_CUNEIFORM_NUMBERS();
        populateBLK_CURRENCY_SYMBOLS();
        populateBLK_CYPRIOT_SYLLABARY();
        populateBLK_CYPRO_MINOAN();
        populateBLK_CYRILLIC();
        populateBLK_CYRILLIC_EXT_A();
        populateBLK_CYRILLIC_EXT_B();
        populateBLK_CYRILLIC_EXT_C();
        populateBLK_CYRILLIC_EXT_D();
        populateBLK_CYRILLIC_SUP();
        populateBLK_DESERET();
        populateBLK_DEVANAGARI();
        populateBLK_DEVANAGARI_EXT();
        populateBLK_DEVANAGARI_EXT_A();
        populateBLK_DIACRITICALS();
        populateBLK_DIACRITICALS_EXT();
        populateBLK_DIACRITICALS_FOR_SYMBOLS();
        populateBLK_DIACRITICALS_SUP();
        populateBLK_DINGBATS();
        populateBLK_DIVES_AKURU();
        populateBLK_DOGRA();
        populateBLK_DOMINO();
        populateBLK_DUPLOYAN();
        populateBLK_EARLY_DYNASTIC_CUNEIFORM();
        populateBLK_EGYPTIAN_HIEROGLYPH_FORMAT_CONTROLS();
        populateBLK_EGYPTIAN_HIEROGLYPHS();
        populateBLK_ELBASAN();
        populateBLK_ELYMAIC();
        populateBLK_EMOTICONS();
        populateBLK_ENCLOSED_ALPHANUM();
        populateBLK_ENCLOSED_ALPHANUM_SUP();
        populateBLK_ENCLOSED_CJK();
        populateBLK_ENCLOSED_IDEOGRAPHIC_SUP();
        populateBLK_ETHIOPIC();
        populateBLK_ETHIOPIC_EXT();
        populateBLK_ETHIOPIC_EXT_A();
        populateBLK_ETHIOPIC_EXT_B();
        populateBLK_ETHIOPIC_SUP();
        populateBLK_GEOMETRIC_SHAPES();
        populateBLK_GEOMETRIC_SHAPES_EXT();
        populateBLK_GEORGIAN();
        populateBLK_GEORGIAN_EXT();
        populateBLK_GEORGIAN_SUP();
        populateBLK_GLAGOLITIC();
        populateBLK_GLAGOLITIC_SUP();
        populateBLK_GOTHIC();
        populateBLK_GRANTHA();
        populateBLK_GREEK();
        populateBLK_GREEK_EXT();
        populateBLK_GUJARATI();
        populateBLK_GUNJALA_GONDI();
        populateBLK_GURMUKHI();
        populateBLK_HALF_AND_FULL_FORMS();
        populateBLK_HALF_MARKS();
        populateBLK_HANGUL();
        populateBLK_HANIFI_ROHINGYA();
        populateBLK_HANUNOO();
        populateBLK_HATRAN();
        populateBLK_HEBREW();
        populateBLK_HIGH_PU_SURROGATES();
        populateBLK_HIGH_SURROGATES();
        populateBLK_HIRAGANA();
        populateBLK_IDC();
        populateBLK_IPA_EXT();
        populateBLK_IDEOGRAPHIC_SYMBOLS();
        populateBLK_IMPERIAL_ARAMAIC();
        populateBLK_INDIC_NUMBER_FORMS();
        populateBLK_INDIC_SIYAQ_NUMBERS();
        populateBLK_INSCRIPTIONAL_PAHLAVI();
        populateBLK_INSCRIPTIONAL_PARTHIAN();
        populateBLK_JAMO();
        populateBLK_JAMO_EXT_A();
        populateBLK_JAMO_EXT_B();
        populateBLK_JAVANESE();
        populateBLK_KAITHI();
        populateBLK_KAKTOVIK_NUMERALS();
        populateBLK_KANA_EXT_A();
        populateBLK_KANA_EXT_B();
        populateBLK_KANA_SUP();
        populateBLK_KANBUN();
        populateBLK_KANGXI();
        populateBLK_KANNADA();
        populateBLK_KATAKANA();
        populateBLK_KATAKANA_EXT();
        populateBLK_KAWI();
        populateBLK_KAYAH_LI();
        populateBLK_KHAROSHTHI();
        populateBLK_KHITAN_SMALL_SCRIPT();
        populateBLK_KHMER();
        populateBLK_KHMER_SYMBOLS();
        populateBLK_KHOJKI();
        populateBLK_KHUDAWADI();
        populateBLK_LAO();
        populateBLK_LATIN_1_SUP();
        populateBLK_LATIN_EXT_A();
        populateBLK_LATIN_EXT_ADDITIONAL();
        populateBLK_LATIN_EXT_B();
        populateBLK_LATIN_EXT_C();
        populateBLK_LATIN_EXT_D();
        populateBLK_LATIN_EXT_E();
        populateBLK_LATIN_EXT_F();
        populateBLK_LATIN_EXT_G();
        populateBLK_LEPCHA();
        populateBLK_LETTERLIKE_SYMBOLS();
        populateBLK_LIMBU();
        populateBLK_LINEAR_A();
        populateBLK_LINEAR_B_IDEOGRAMS();
        populateBLK_LINEAR_B_SYLLABARY();
        populateBLK_LISU();
        populateBLK_LISU_SUP();
        populateBLK_LOW_SURROGATES();
        populateBLK_LYCIAN();
        populateBLK_LYDIAN();
        populateBLK_MAHAJANI();
        populateBLK_MAHJONG();
        populateBLK_MAKASAR();
        populateBLK_MALAYALAM();
        populateBLK_MANDAIC();
        populateBLK_MANICHAEAN();
        populateBLK_MARCHEN();
        populateBLK_MASARAM_GONDI();
        populateBLK_MATH_ALPHANUM();
        populateBLK_MATH_OPERATORS();
        populateBLK_MAYAN_NUMERALS();
        populateBLK_MEDEFAIDRIN();
        populateBLK_MEETEI_MAYEK();
        populateBLK_MEETEI_MAYEK_EXT();
        populateBLK_MENDE_KIKAKUI();
        populateBLK_MEROITIC_CURSIVE();
        populateBLK_MEROITIC_HIEROGLYPHS();
        populateBLK_MIAO();
        populateBLK_MISC_ARROWS();
        populateBLK_MISC_MATH_SYMBOLS_A();
        populateBLK_MISC_MATH_SYMBOLS_B();
        populateBLK_MISC_PICTOGRAPHS();
        populateBLK_MISC_SYMBOLS();
        populateBLK_MISC_TECHNICAL();
        populateBLK_MODI();
        populateBLK_MODIFIER_LETTERS();
        populateBLK_MODIFIER_TONE_LETTERS();
        populateBLK_MONGOLIAN();
        populateBLK_MONGOLIAN_SUP();
        populateBLK_MRO();
        populateBLK_MULTANI();
        populateBLK_MUSIC();
        populateBLK_MYANMAR();
        populateBLK_MYANMAR_EXT_A();
        populateBLK_MYANMAR_EXT_B();
        populateBLK_NB();
        populateBLK_NKO();
        populateBLK_NABATAEAN();
        populateBLK_NAG_MUNDARI();
        populateBLK_NANDINAGARI();
        populateBLK_NEW_TAI_LUE();
        populateBLK_NEWA();
        populateBLK_NUMBER_FORMS();
        populateBLK_NUSHU();
        populateBLK_NYIAKENG_PUACHUE_HMONG();
        populateBLK_OCR();
        populateBLK_OGHAM();
        populateBLK_OL_CHIKI();
        populateBLK_OLD_HUNGARIAN();
        populateBLK_OLD_ITALIC();
        populateBLK_OLD_NORTH_ARABIAN();
        populateBLK_OLD_PERMIC();
        populateBLK_OLD_PERSIAN();
        populateBLK_OLD_SOGDIAN();
        populateBLK_OLD_SOUTH_ARABIAN();
        populateBLK_OLD_TURKIC();
        populateBLK_OLD_UYGHUR();
        populateBLK_ORIYA();
        populateBLK_ORNAMENTAL_DINGBATS();
        populateBLK_OSAGE();
        populateBLK_OSMANYA();
        populateBLK_OTTOMAN_SIYAQ_NUMBERS();
        populateBLK_PUA();
        populateBLK_PAHAWH_HMONG();
        populateBLK_PALMYRENE();
        populateBLK_PAU_CIN_HAU();
        populateBLK_PHAGS_PA();
        populateBLK_PHAISTOS();
        populateBLK_PHOENICIAN();
        populateBLK_PHONETIC_EXT();
        populateBLK_PHONETIC_EXT_SUP();
        populateBLK_PLAYING_CARDS();
        populateBLK_PSALTER_PAHLAVI();
        populateBLK_PUNCTUATION();
        populateBLK_REJANG();
        populateBLK_RUMI();
        populateBLK_RUNIC();
        populateBLK_SAMARITAN();
        populateBLK_SAURASHTRA();
        populateBLK_SHARADA();
        populateBLK_SHAVIAN();
        populateBLK_SHORTHAND_FORMAT_CONTROLS();
        populateBLK_SIDDHAM();
        populateBLK_SINHALA();
        populateBLK_SINHALA_ARCHAIC_NUMBERS();
        populateBLK_SMALL_FORMS();
        populateBLK_SMALL_KANA_EXT();
        populateBLK_SOGDIAN();
        populateBLK_SORA_SOMPENG();
        populateBLK_SOYOMBO();
        populateBLK_SPECIALS();
        populateBLK_SUNDANESE();
        populateBLK_SUNDANESE_SUP();
        populateBLK_SUP_ARROWS_A();
        populateBLK_SUP_ARROWS_B();
        populateBLK_SUP_ARROWS_C();
        populateBLK_SUP_MATH_OPERATORS();
        populateBLK_SUP_PUA_A();
        populateBLK_SUP_PUA_B();
        populateBLK_SUP_PUNCTUATION();
        populateBLK_SUP_SYMBOLS_AND_PICTOGRAPHS();
        populateBLK_SUPER_AND_SUB();
        populateBLK_SUTTON_SIGNWRITING();
        populateBLK_SYLOTI_NAGRI();
        populateBLK_SYMBOLS_AND_PICTOGRAPHS_EXT_A();
        populateBLK_SYMBOLS_FOR_LEGACY_COMPUTING();
        populateBLK_SYRIAC();
        populateBLK_SYRIAC_SUP();
        populateBLK_TAGALOG();
        populateBLK_TAGBANWA();
        populateBLK_TAGS();
        populateBLK_TAI_LE();
        populateBLK_TAI_THAM();
        populateBLK_TAI_VIET();
        populateBLK_TAI_XUAN_JING();
        populateBLK_TAKRI();
        populateBLK_TAMIL();
        populateBLK_TAMIL_SUP();
        populateBLK_TANGSA();
        populateBLK_TANGUT();
        populateBLK_TANGUT_COMPONENTS();
        populateBLK_TANGUT_SUP();
        populateBLK_TELUGU();
        populateBLK_THAANA();
        populateBLK_THAI();
        populateBLK_TIBETAN();
        populateBLK_TIFINAGH();
        populateBLK_TIRHUTA();
        populateBLK_TOTO();
        populateBLK_TRANSPORT_AND_MAP();
        populateBLK_UCAS();
        populateBLK_UCAS_EXT();
        populateBLK_UCAS_EXT_A();
        populateBLK_UGARITIC();
        populateBLK_VS();
        populateBLK_VS_SUP();
        populateBLK_VAI();
        populateBLK_VEDIC_EXT();
        populateBLK_VERTICAL_FORMS();
        populateBLK_VITHKUQI();
        populateBLK_WANCHO();
        populateBLK_WARANG_CITI();
        populateBLK_YEZIDI();
        populateBLK_YI_RADICALS();
        populateBLK_YI_SYLLABLES();
        populateBLK_YIJING();
        populateBLK_ZANABAZAR_SQUARE();
        populateBLK_ZNAMENNY_MUSIC();
        populateGC_C();
        populateGC_CC();
        populateGC_CF();
        populateGC_CN();
        populateGC_CO();
        populateGC_CS();
        populateGC_L();
        populateGC_LC();
        populateGC_LL();
        populateGC_LM();
        populateGC_LO();
        populateGC_LT();
        populateGC_LU();
        populateGC_M();
        populateGC_MC();
        populateGC_ME();
        populateGC_MN();
        populateGC_N();
        populateGC_ND();
        populateGC_NL();
        populateGC_NO();
        populateGC_P();
        populateGC_PC();
        populateGC_PD();
        populateGC_PE();
        populateGC_PF();
        populateGC_PI();
        populateGC_PO();
        populateGC_PS();
        populateGC_S();
        populateGC_SC();
        populateGC_SK();
        populateGC_SM();
        populateGC_SO();
        populateGC_Z();
        populateGC_ZL();
        populateGC_ZP();
        populateGC_ZS();
        populateSC_ADLM();
        populateSC_AGHB();
        populateSC_AHOM();
        populateSC_ARAB();
        populateSC_ARMI();
        populateSC_ARMN();
        populateSC_AVST();
        populateSC_BALI();
        populateSC_BAMU();
        populateSC_BASS();
        populateSC_BATK();
        populateSC_BENG();
        populateSC_BHKS();
        populateSC_BOPO();
        populateSC_BRAH();
        populateSC_BRAI();
        populateSC_BUGI();
        populateSC_BUHD();
        populateSC_CAKM();
        populateSC_CANS();
        populateSC_CARI();
        populateSC_CHAM();
        populateSC_CHER();
        populateSC_CHRS();
        populateSC_COPT();
        populateSC_CPMN();
        populateSC_CPRT();
        populateSC_CYRL();
        populateSC_DEVA();
        populateSC_DIAK();
        populateSC_DOGR();
        populateSC_DSRT();
        populateSC_DUPL();
        populateSC_EGYP();
        populateSC_ELBA();
        populateSC_ELYM();
        populateSC_ETHI();
        populateSC_GEOR();
        populateSC_GLAG();
        populateSC_GONG();
        populateSC_GONM();
        populateSC_GOTH();
        populateSC_GRAN();
        populateSC_GREK();
        populateSC_GUJR();
        populateSC_GURU();
        populateSC_HANG();
        populateSC_HANI();
        populateSC_HANO();
        populateSC_HATR();
        populateSC_HEBR();
        populateSC_HIRA();
        populateSC_HLUW();
        populateSC_HMNG();
        populateSC_HMNP();
        populateSC_HUNG();
        populateSC_ITAL();
        populateSC_JAVA();
        populateSC_KALI();
        populateSC_KANA();
        populateSC_KAWI();
        populateSC_KHAR();
        populateSC_KHMR();
        populateSC_KHOJ();
        populateSC_KITS();
        populateSC_KNDA();
        populateSC_KTHI();
        populateSC_LANA();
        populateSC_LAOO();
        populateSC_LATN();
        populateSC_LEPC();
        populateSC_LIMB();
        populateSC_LINA();
        populateSC_LINB();
        populateSC_LISU();
        populateSC_LYCI();
        populateSC_LYDI();
        populateSC_MAHJ();
        populateSC_MAKA();
        populateSC_MAND();
        populateSC_MANI();
        populateSC_MARC();
        populateSC_MEDF();
        populateSC_MEND();
        populateSC_MERC();
        populateSC_MERO();
        populateSC_MLYM();
        populateSC_MODI();
        populateSC_MONG();
        populateSC_MROO();
        populateSC_MTEI();
        populateSC_MULT();
        populateSC_MYMR();
        populateSC_NAGM();
        populateSC_NAND();
        populateSC_NARB();
        populateSC_NBAT();
        populateSC_NEWA();
        populateSC_NKOO();
        populateSC_NSHU();
        populateSC_OGAM();
        populateSC_OLCK();
        populateSC_ORKH();
        populateSC_ORYA();
        populateSC_OSGE();
        populateSC_OSMA();
        populateSC_OUGR();
        populateSC_PALM();
        populateSC_PAUC();
        populateSC_PERM();
        populateSC_PHAG();
        populateSC_PHLI();
        populateSC_PHLP();
        populateSC_PHNX();
        populateSC_PLRD();
        populateSC_PRTI();
        populateSC_RJNG();
        populateSC_ROHG();
        populateSC_RUNR();
        populateSC_SAMR();
        populateSC_SARB();
        populateSC_SAUR();
        populateSC_SGNW();
        populateSC_SHAW();
        populateSC_SHRD();
        populateSC_SIDD();
        populateSC_SIND();
        populateSC_SINH();
        populateSC_SOGD();
        populateSC_SOGO();
        populateSC_SORA();
        populateSC_SOYO();
        populateSC_SUND();
        populateSC_SYLO();
        populateSC_SYRC();
        populateSC_TAGB();
        populateSC_TAKR();
        populateSC_TALE();
        populateSC_TALU();
        populateSC_TAML();
        populateSC_TANG();
        populateSC_TAVT();
        populateSC_TELU();
        populateSC_TFNG();
        populateSC_TGLG();
        populateSC_THAA();
        populateSC_THAI();
        populateSC_TIBT();
        populateSC_TIRH();
        populateSC_TNSA();
        populateSC_TOTO();
        populateSC_UGAR();
        populateSC_VAII();
        populateSC_VITH();
        populateSC_WARA();
        populateSC_WCHO();
        populateSC_XPEO();
        populateSC_XSUX();
        populateSC_YEZI();
        populateSC_YIII();
        populateSC_ZANB();
        populateSC_ZINH();
        populateSC_ZYYY();
        populateSC_ZZZZ();
        populateSCX_ADLM();
        populateSCX_AGHB();
        populateSCX_AHOM();
        populateSCX_ARAB();
        populateSCX_ARMI();
        populateSCX_ARMN();
        populateSCX_AVST();
        populateSCX_BALI();
        populateSCX_BAMU();
        populateSCX_BASS();
        populateSCX_BATK();
        populateSCX_BENG();
        populateSCX_BHKS();
        populateSCX_BOPO();
        populateSCX_BRAH();
        populateSCX_BRAI();
        populateSCX_BUGI();
        populateSCX_BUHD();
        populateSCX_CAKM();
        populateSCX_CANS();
        populateSCX_CARI();
        populateSCX_CHAM();
        populateSCX_CHER();
        populateSCX_CHRS();
        populateSCX_COPT();
        populateSCX_CPMN();
        populateSCX_CPRT();
        populateSCX_CYRL();
        populateSCX_DEVA();
        populateSCX_DIAK();
        populateSCX_DOGR();
        populateSCX_DSRT();
        populateSCX_DUPL();
        populateSCX_EGYP();
        populateSCX_ELBA();
        populateSCX_ELYM();
        populateSCX_ETHI();
        populateSCX_GEOR();
        populateSCX_GLAG();
        populateSCX_GONG();
        populateSCX_GONM();
        populateSCX_GOTH();
        populateSCX_GRAN();
        populateSCX_GREK();
        populateSCX_GUJR();
        populateSCX_GURU();
        populateSCX_HANG();
        populateSCX_HANI();
        populateSCX_HANO();
        populateSCX_HATR();
        populateSCX_HEBR();
        populateSCX_HIRA();
        populateSCX_HLUW();
        populateSCX_HMNG();
        populateSCX_HMNP();
        populateSCX_HUNG();
        populateSCX_ITAL();
        populateSCX_JAVA();
        populateSCX_KALI();
        populateSCX_KANA();
        populateSCX_KAWI();
        populateSCX_KHAR();
        populateSCX_KHMR();
        populateSCX_KHOJ();
        populateSCX_KITS();
        populateSCX_KNDA();
        populateSCX_KTHI();
        populateSCX_LANA();
        populateSCX_LAOO();
        populateSCX_LATN();
        populateSCX_LEPC();
        populateSCX_LIMB();
        populateSCX_LINA();
        populateSCX_LINB();
        populateSCX_LISU();
        populateSCX_LYCI();
        populateSCX_LYDI();
        populateSCX_MAHJ();
        populateSCX_MAKA();
        populateSCX_MAND();
        populateSCX_MANI();
        populateSCX_MARC();
        populateSCX_MEDF();
        populateSCX_MEND();
        populateSCX_MERC();
        populateSCX_MERO();
        populateSCX_MLYM();
        populateSCX_MODI();
        populateSCX_MONG();
        populateSCX_MROO();
        populateSCX_MTEI();
        populateSCX_MULT();
        populateSCX_MYMR();
        populateSCX_NAGM();
        populateSCX_NAND();
        populateSCX_NARB();
        populateSCX_NBAT();
        populateSCX_NEWA();
        populateSCX_NKOO();
        populateSCX_NSHU();
        populateSCX_OGAM();
        populateSCX_OLCK();
        populateSCX_ORKH();
        populateSCX_ORYA();
        populateSCX_OSGE();
        populateSCX_OSMA();
        populateSCX_OUGR();
        populateSCX_PALM();
        populateSCX_PAUC();
        populateSCX_PERM();
        populateSCX_PHAG();
        populateSCX_PHLI();
        populateSCX_PHLP();
        populateSCX_PHNX();
        populateSCX_PLRD();
        populateSCX_PRTI();
        populateSCX_RJNG();
        populateSCX_ROHG();
        populateSCX_RUNR();
        populateSCX_SAMR();
        populateSCX_SARB();
        populateSCX_SAUR();
        populateSCX_SGNW();
        populateSCX_SHAW();
        populateSCX_SHRD();
        populateSCX_SIDD();
        populateSCX_SIND();
        populateSCX_SINH();
        populateSCX_SOGD();
        populateSCX_SOGO();
        populateSCX_SORA();
        populateSCX_SOYO();
        populateSCX_SUND();
        populateSCX_SYLO();
        populateSCX_SYRC();
        populateSCX_TAGB();
        populateSCX_TAKR();
        populateSCX_TALE();
        populateSCX_TALU();
        populateSCX_TAML();
        populateSCX_TANG();
        populateSCX_TAVT();
        populateSCX_TELU();
        populateSCX_TFNG();
        populateSCX_TGLG();
        populateSCX_THAA();
        populateSCX_THAI();
        populateSCX_TIBT();
        populateSCX_TIRH();
        populateSCX_TNSA();
        populateSCX_TOTO();
        populateSCX_UGAR();
        populateSCX_VAII();
        populateSCX_VITH();
        populateSCX_WARA();
        populateSCX_WCHO();
        populateSCX_XPEO();
        populateSCX_XSUX();
        populateSCX_YEZI();
        populateSCX_YIII();
        populateSCX_ZANB();
        populateSCX_ZINH();
        populateSCX_ZYYY();
        populateSCX_ZZZZ();
        populateBASIC_EMOJI();
        populateEMOJI_KEYCAP_SEQUENCE();
        populateRGI_EMOJI_FLAG_SEQUENCE();
        populateRGI_EMOJI_MODIFIER_SEQUENCE();
        populateRGI_EMOJI_TAG_SEQUENCE();
        populateRGI_EMOJI_ZWJ_SEQUENCE();
        INSTANCE = new UnicodePropertyData(SET_ENCODINGS, CLASS_SET_ENCODINGS, PROPERTY_ALIASES, GENERAL_CATEGORY_ALIASES, SCRIPT_ALIASES, BLOCK_ALIASES);
    }

    private static void populateAHEX() {
        SET_ENCODINGS.put("AHex", CodePointSet.createNoDedup(0x000030, 0x000039, 0x000041, 0x000046, 0x000061, 0x000066));
    }

    private static void populateASCII() {
        SET_ENCODINGS.put("ASCII", CodePointSet.createNoDedup(0x000000, 0x00007f));
    }

    private static void populateALPHA() {
        SET_ENCODINGS.put("Alpha", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000b5, 0x0000b5, 0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8,
                        0x0000f6, 0x0000f8, 0x0002c1, 0x0002c6, 0x0002d1, 0x0002e0, 0x0002e4, 0x0002ec, 0x0002ec, 0x0002ee, 0x0002ee, 0x000345, 0x000345, 0x000370, 0x000374, 0x000376, 0x000377,
                        0x00037a, 0x00037d, 0x00037f, 0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003f5, 0x0003f7, 0x000481, 0x00048a,
                        0x00052f, 0x000531, 0x000556, 0x000559, 0x000559, 0x000560, 0x000588, 0x0005b0, 0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4, 0x0005c5, 0x0005c7, 0x0005c7,
                        0x0005d0, 0x0005ea, 0x0005ef, 0x0005f2, 0x000610, 0x00061a, 0x000620, 0x000657, 0x000659, 0x00065f, 0x00066e, 0x0006d3, 0x0006d5, 0x0006dc, 0x0006e1, 0x0006e8, 0x0006ed,
                        0x0006ef, 0x0006fa, 0x0006fc, 0x0006ff, 0x0006ff, 0x000710, 0x00073f, 0x00074d, 0x0007b1, 0x0007ca, 0x0007ea, 0x0007f4, 0x0007f5, 0x0007fa, 0x0007fa, 0x000800, 0x000817,
                        0x00081a, 0x00082c, 0x000840, 0x000858, 0x000860, 0x00086a, 0x000870, 0x000887, 0x000889, 0x00088e, 0x0008a0, 0x0008c9, 0x0008d4, 0x0008df, 0x0008e3, 0x0008e9, 0x0008f0,
                        0x00093b, 0x00093d, 0x00094c, 0x00094e, 0x000950, 0x000955, 0x000963, 0x000971, 0x000983, 0x000985, 0x00098c, 0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0,
                        0x0009b2, 0x0009b2, 0x0009b6, 0x0009b9, 0x0009bd, 0x0009c4, 0x0009c7, 0x0009c8, 0x0009cb, 0x0009cc, 0x0009ce, 0x0009ce, 0x0009d7, 0x0009d7, 0x0009dc, 0x0009dd, 0x0009df,
                        0x0009e3, 0x0009f0, 0x0009f1, 0x0009fc, 0x0009fc, 0x000a01, 0x000a03, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33,
                        0x000a35, 0x000a36, 0x000a38, 0x000a39, 0x000a3e, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4c, 0x000a51, 0x000a51, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a70,
                        0x000a75, 0x000a81, 0x000a83, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8, 0x000aaa, 0x000ab0, 0x000ab2, 0x000ab3, 0x000ab5, 0x000ab9, 0x000abd, 0x000ac5,
                        0x000ac7, 0x000ac9, 0x000acb, 0x000acc, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae3, 0x000af9, 0x000afc, 0x000b01, 0x000b03, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13,
                        0x000b28, 0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35, 0x000b39, 0x000b3d, 0x000b44, 0x000b47, 0x000b48, 0x000b4b, 0x000b4c, 0x000b56, 0x000b57, 0x000b5c, 0x000b5d,
                        0x000b5f, 0x000b63, 0x000b71, 0x000b71, 0x000b82, 0x000b83, 0x000b85, 0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e,
                        0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa, 0x000bae, 0x000bb9, 0x000bbe, 0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcc, 0x000bd0, 0x000bd0, 0x000bd7, 0x000bd7,
                        0x000c00, 0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3d, 0x000c44, 0x000c46, 0x000c48, 0x000c4a, 0x000c4c, 0x000c55, 0x000c56, 0x000c58,
                        0x000c5a, 0x000c5d, 0x000c5d, 0x000c60, 0x000c63, 0x000c80, 0x000c83, 0x000c85, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9,
                        0x000cbd, 0x000cc4, 0x000cc6, 0x000cc8, 0x000cca, 0x000ccc, 0x000cd5, 0x000cd6, 0x000cdd, 0x000cde, 0x000ce0, 0x000ce3, 0x000cf1, 0x000cf3, 0x000d00, 0x000d0c, 0x000d0e,
                        0x000d10, 0x000d12, 0x000d3a, 0x000d3d, 0x000d44, 0x000d46, 0x000d48, 0x000d4a, 0x000d4c, 0x000d4e, 0x000d4e, 0x000d54, 0x000d57, 0x000d5f, 0x000d63, 0x000d7a, 0x000d7f,
                        0x000d81, 0x000d83, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000dcf, 0x000dd4, 0x000dd6, 0x000dd6, 0x000dd8,
                        0x000ddf, 0x000df2, 0x000df3, 0x000e01, 0x000e3a, 0x000e40, 0x000e46, 0x000e4d, 0x000e4d, 0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3,
                        0x000ea5, 0x000ea5, 0x000ea7, 0x000eb9, 0x000ebb, 0x000ebd, 0x000ec0, 0x000ec4, 0x000ec6, 0x000ec6, 0x000ecd, 0x000ecd, 0x000edc, 0x000edf, 0x000f00, 0x000f00, 0x000f40,
                        0x000f47, 0x000f49, 0x000f6c, 0x000f71, 0x000f83, 0x000f88, 0x000f97, 0x000f99, 0x000fbc, 0x001000, 0x001036, 0x001038, 0x001038, 0x00103b, 0x00103f, 0x001050, 0x00108f,
                        0x00109a, 0x00109d, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x0010fa, 0x0010fc, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258,
                        0x001258, 0x00125a, 0x00125d, 0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290, 0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5,
                        0x0012c8, 0x0012d6, 0x0012d8, 0x001310, 0x001312, 0x001315, 0x001318, 0x00135a, 0x001380, 0x00138f, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001401, 0x00166c, 0x00166f,
                        0x00167f, 0x001681, 0x00169a, 0x0016a0, 0x0016ea, 0x0016ee, 0x0016f8, 0x001700, 0x001713, 0x00171f, 0x001733, 0x001740, 0x001753, 0x001760, 0x00176c, 0x00176e, 0x001770,
                        0x001772, 0x001773, 0x001780, 0x0017b3, 0x0017b6, 0x0017c8, 0x0017d7, 0x0017d7, 0x0017dc, 0x0017dc, 0x001820, 0x001878, 0x001880, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900,
                        0x00191e, 0x001920, 0x00192b, 0x001930, 0x001938, 0x001950, 0x00196d, 0x001970, 0x001974, 0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x001a00, 0x001a1b, 0x001a20, 0x001a5e,
                        0x001a61, 0x001a74, 0x001aa7, 0x001aa7, 0x001abf, 0x001ac0, 0x001acc, 0x001ace, 0x001b00, 0x001b33, 0x001b35, 0x001b43, 0x001b45, 0x001b4c, 0x001b80, 0x001ba9, 0x001bac,
                        0x001baf, 0x001bba, 0x001be5, 0x001be7, 0x001bf1, 0x001c00, 0x001c36, 0x001c4d, 0x001c4f, 0x001c5a, 0x001c7d, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf,
                        0x001ce9, 0x001cec, 0x001cee, 0x001cf3, 0x001cf5, 0x001cf6, 0x001cfa, 0x001cfa, 0x001d00, 0x001dbf, 0x001de7, 0x001df4, 0x001e00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20,
                        0x001f45, 0x001f48, 0x001f4d, 0x001f50, 0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc,
                        0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4, 0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x002071,
                        0x002071, 0x00207f, 0x00207f, 0x002090, 0x00209c, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002119, 0x00211d, 0x002124, 0x002124,
                        0x002126, 0x002126, 0x002128, 0x002128, 0x00212a, 0x00212d, 0x00212f, 0x002139, 0x00213c, 0x00213f, 0x002145, 0x002149, 0x00214e, 0x00214e, 0x002160, 0x002188, 0x0024b6,
                        0x0024e9, 0x002c00, 0x002ce4, 0x002ceb, 0x002cee, 0x002cf2, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x002d30, 0x002d67, 0x002d6f, 0x002d6f,
                        0x002d80, 0x002d96, 0x002da0, 0x002da6, 0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe, 0x002dc0, 0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8,
                        0x002dde, 0x002de0, 0x002dff, 0x002e2f, 0x002e2f, 0x003005, 0x003007, 0x003021, 0x003029, 0x003031, 0x003035, 0x003038, 0x00303c, 0x003041, 0x003096, 0x00309d, 0x00309f,
                        0x0030a1, 0x0030fa, 0x0030fc, 0x0030ff, 0x003105, 0x00312f, 0x003131, 0x00318e, 0x0031a0, 0x0031bf, 0x0031f0, 0x0031ff, 0x003400, 0x004dbf, 0x004e00, 0x00a48c, 0x00a4d0,
                        0x00a4fd, 0x00a500, 0x00a60c, 0x00a610, 0x00a61f, 0x00a62a, 0x00a62b, 0x00a640, 0x00a66e, 0x00a674, 0x00a67b, 0x00a67f, 0x00a6ef, 0x00a717, 0x00a71f, 0x00a722, 0x00a788,
                        0x00a78b, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9, 0x00a7f2, 0x00a805, 0x00a807, 0x00a827, 0x00a840, 0x00a873, 0x00a880, 0x00a8c3, 0x00a8c5,
                        0x00a8c5, 0x00a8f2, 0x00a8f7, 0x00a8fb, 0x00a8fb, 0x00a8fd, 0x00a8ff, 0x00a90a, 0x00a92a, 0x00a930, 0x00a952, 0x00a960, 0x00a97c, 0x00a980, 0x00a9b2, 0x00a9b4, 0x00a9bf,
                        0x00a9cf, 0x00a9cf, 0x00a9e0, 0x00a9ef, 0x00a9fa, 0x00a9fe, 0x00aa00, 0x00aa36, 0x00aa40, 0x00aa4d, 0x00aa60, 0x00aa76, 0x00aa7a, 0x00aabe, 0x00aac0, 0x00aac0, 0x00aac2,
                        0x00aac2, 0x00aadb, 0x00aadd, 0x00aae0, 0x00aaef, 0x00aaf2, 0x00aaf5, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20, 0x00ab26, 0x00ab28, 0x00ab2e,
                        0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab69, 0x00ab70, 0x00abea, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb, 0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb00,
                        0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb28, 0x00fb2a, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40, 0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fbb1,
                        0x00fbd3, 0x00fd3d, 0x00fd50, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdf0, 0x00fdfb, 0x00fe70, 0x00fe74, 0x00fe76, 0x00fefc, 0x00ff21, 0x00ff3a, 0x00ff41, 0x00ff5a, 0x00ff66,
                        0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d,
                        0x01003f, 0x01004d, 0x010050, 0x01005d, 0x010080, 0x0100fa, 0x010140, 0x010174, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x010300, 0x01031f, 0x01032d, 0x01034a, 0x010350,
                        0x01037a, 0x010380, 0x01039d, 0x0103a0, 0x0103c3, 0x0103c8, 0x0103cf, 0x0103d1, 0x0103d5, 0x010400, 0x01049d, 0x0104b0, 0x0104d3, 0x0104d8, 0x0104fb, 0x010500, 0x010527,
                        0x010530, 0x010563, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb,
                        0x0105bc, 0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010800, 0x010805, 0x010808, 0x010808,
                        0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x010855, 0x010860, 0x010876, 0x010880, 0x01089e, 0x0108e0, 0x0108f2, 0x0108f4, 0x0108f5, 0x010900,
                        0x010915, 0x010920, 0x010939, 0x010980, 0x0109b7, 0x0109be, 0x0109bf, 0x010a00, 0x010a03, 0x010a05, 0x010a06, 0x010a0c, 0x010a13, 0x010a15, 0x010a17, 0x010a19, 0x010a35,
                        0x010a60, 0x010a7c, 0x010a80, 0x010a9c, 0x010ac0, 0x010ac7, 0x010ac9, 0x010ae4, 0x010b00, 0x010b35, 0x010b40, 0x010b55, 0x010b60, 0x010b72, 0x010b80, 0x010b91, 0x010c00,
                        0x010c48, 0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x010d00, 0x010d27, 0x010e80, 0x010ea9, 0x010eab, 0x010eac, 0x010eb0, 0x010eb1, 0x010f00, 0x010f1c, 0x010f27, 0x010f27,
                        0x010f30, 0x010f45, 0x010f70, 0x010f81, 0x010fb0, 0x010fc4, 0x010fe0, 0x010ff6, 0x011000, 0x011045, 0x011071, 0x011075, 0x011080, 0x0110b8, 0x0110c2, 0x0110c2, 0x0110d0,
                        0x0110e8, 0x011100, 0x011132, 0x011144, 0x011147, 0x011150, 0x011172, 0x011176, 0x011176, 0x011180, 0x0111bf, 0x0111c1, 0x0111c4, 0x0111ce, 0x0111cf, 0x0111da, 0x0111da,
                        0x0111dc, 0x0111dc, 0x011200, 0x011211, 0x011213, 0x011234, 0x011237, 0x011237, 0x01123e, 0x011241, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f,
                        0x01129d, 0x01129f, 0x0112a8, 0x0112b0, 0x0112e8, 0x011300, 0x011303, 0x011305, 0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333,
                        0x011335, 0x011339, 0x01133d, 0x011344, 0x011347, 0x011348, 0x01134b, 0x01134c, 0x011350, 0x011350, 0x011357, 0x011357, 0x01135d, 0x011363, 0x011400, 0x011441, 0x011443,
                        0x011445, 0x011447, 0x01144a, 0x01145f, 0x011461, 0x011480, 0x0114c1, 0x0114c4, 0x0114c5, 0x0114c7, 0x0114c7, 0x011580, 0x0115b5, 0x0115b8, 0x0115be, 0x0115d8, 0x0115dd,
                        0x011600, 0x01163e, 0x011640, 0x011640, 0x011644, 0x011644, 0x011680, 0x0116b5, 0x0116b8, 0x0116b8, 0x011700, 0x01171a, 0x01171d, 0x01172a, 0x011740, 0x011746, 0x011800,
                        0x011838, 0x0118a0, 0x0118df, 0x0118ff, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x011935, 0x011937, 0x011938, 0x01193b, 0x01193c,
                        0x01193f, 0x011942, 0x0119a0, 0x0119a7, 0x0119aa, 0x0119d7, 0x0119da, 0x0119df, 0x0119e1, 0x0119e1, 0x0119e3, 0x0119e4, 0x011a00, 0x011a32, 0x011a35, 0x011a3e, 0x011a50,
                        0x011a97, 0x011a9d, 0x011a9d, 0x011ab0, 0x011af8, 0x011c00, 0x011c08, 0x011c0a, 0x011c36, 0x011c38, 0x011c3e, 0x011c40, 0x011c40, 0x011c72, 0x011c8f, 0x011c92, 0x011ca7,
                        0x011ca9, 0x011cb6, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d41, 0x011d43, 0x011d43, 0x011d46,
                        0x011d47, 0x011d60, 0x011d65, 0x011d67, 0x011d68, 0x011d6a, 0x011d8e, 0x011d90, 0x011d91, 0x011d93, 0x011d96, 0x011d98, 0x011d98, 0x011ee0, 0x011ef6, 0x011f00, 0x011f10,
                        0x011f12, 0x011f3a, 0x011f3e, 0x011f40, 0x011fb0, 0x011fb0, 0x012000, 0x012399, 0x012400, 0x01246e, 0x012480, 0x012543, 0x012f90, 0x012ff0, 0x013000, 0x01342f, 0x013441,
                        0x013446, 0x014400, 0x014646, 0x016800, 0x016a38, 0x016a40, 0x016a5e, 0x016a70, 0x016abe, 0x016ad0, 0x016aed, 0x016b00, 0x016b2f, 0x016b40, 0x016b43, 0x016b63, 0x016b77,
                        0x016b7d, 0x016b8f, 0x016e40, 0x016e7f, 0x016f00, 0x016f4a, 0x016f4f, 0x016f87, 0x016f8f, 0x016f9f, 0x016fe0, 0x016fe1, 0x016fe3, 0x016fe3, 0x016ff0, 0x016ff1, 0x017000,
                        0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01b000, 0x01b122, 0x01b132, 0x01b132, 0x01b150, 0x01b152,
                        0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb, 0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88, 0x01bc90, 0x01bc99, 0x01bc9e, 0x01bc9e, 0x01d400,
                        0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3,
                        0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a,
                        0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d6c0, 0x01d6c2, 0x01d6da, 0x01d6dc, 0x01d6fa, 0x01d6fc, 0x01d714, 0x01d716, 0x01d734, 0x01d736, 0x01d74e, 0x01d750, 0x01d76e,
                        0x01d770, 0x01d788, 0x01d78a, 0x01d7a8, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7cb, 0x01df00, 0x01df1e, 0x01df25, 0x01df2a, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b,
                        0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a, 0x01e030, 0x01e06d, 0x01e08f, 0x01e08f, 0x01e100, 0x01e12c, 0x01e137, 0x01e13d, 0x01e14e, 0x01e14e, 0x01e290, 0x01e2ad,
                        0x01e2c0, 0x01e2eb, 0x01e4d0, 0x01e4eb, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe, 0x01e800, 0x01e8c4, 0x01e900, 0x01e943, 0x01e947,
                        0x01e947, 0x01e94b, 0x01e94b, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37,
                        0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54,
                        0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a,
                        0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab,
                        0x01eebb, 0x01f130, 0x01f149, 0x01f150, 0x01f169, 0x01f170, 0x01f189, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0,
                        0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af));
    }

    private static void populateANY() {
        SET_ENCODINGS.put("Any", CodePointSet.createNoDedup(0x000000, 0x10ffff));
    }

    private static void populateASSIGNED() {
        SET_ENCODINGS.put("Assigned", CodePointSet.createNoDedup(0x000000, 0x000377, 0x00037a, 0x00037f, 0x000384, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x00052f, 0x000531,
                        0x000556, 0x000559, 0x00058a, 0x00058d, 0x00058f, 0x000591, 0x0005c7, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f4, 0x000600, 0x00070d, 0x00070f, 0x00074a, 0x00074d, 0x0007b1,
                        0x0007c0, 0x0007fa, 0x0007fd, 0x00082d, 0x000830, 0x00083e, 0x000840, 0x00085b, 0x00085e, 0x00085e, 0x000860, 0x00086a, 0x000870, 0x00088e, 0x000890, 0x000891, 0x000898,
                        0x000983, 0x000985, 0x00098c, 0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6, 0x0009b9, 0x0009bc, 0x0009c4, 0x0009c7, 0x0009c8,
                        0x0009cb, 0x0009ce, 0x0009d7, 0x0009d7, 0x0009dc, 0x0009dd, 0x0009df, 0x0009e3, 0x0009e6, 0x0009fe, 0x000a01, 0x000a03, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13,
                        0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36, 0x000a38, 0x000a39, 0x000a3c, 0x000a3c, 0x000a3e, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4d,
                        0x000a51, 0x000a51, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a66, 0x000a76, 0x000a81, 0x000a83, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8, 0x000aaa,
                        0x000ab0, 0x000ab2, 0x000ab3, 0x000ab5, 0x000ab9, 0x000abc, 0x000ac5, 0x000ac7, 0x000ac9, 0x000acb, 0x000acd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae3, 0x000ae6, 0x000af1,
                        0x000af9, 0x000aff, 0x000b01, 0x000b03, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28, 0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35, 0x000b39, 0x000b3c,
                        0x000b44, 0x000b47, 0x000b48, 0x000b4b, 0x000b4d, 0x000b55, 0x000b57, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b63, 0x000b66, 0x000b77, 0x000b82, 0x000b83, 0x000b85, 0x000b8a,
                        0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa, 0x000bae, 0x000bb9, 0x000bbe,
                        0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcd, 0x000bd0, 0x000bd0, 0x000bd7, 0x000bd7, 0x000be6, 0x000bfa, 0x000c00, 0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28,
                        0x000c2a, 0x000c39, 0x000c3c, 0x000c44, 0x000c46, 0x000c48, 0x000c4a, 0x000c4d, 0x000c55, 0x000c56, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d, 0x000c60, 0x000c63, 0x000c66,
                        0x000c6f, 0x000c77, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbc, 0x000cc4, 0x000cc6, 0x000cc8, 0x000cca, 0x000ccd,
                        0x000cd5, 0x000cd6, 0x000cdd, 0x000cde, 0x000ce0, 0x000ce3, 0x000ce6, 0x000cef, 0x000cf1, 0x000cf3, 0x000d00, 0x000d0c, 0x000d0e, 0x000d10, 0x000d12, 0x000d44, 0x000d46,
                        0x000d48, 0x000d4a, 0x000d4f, 0x000d54, 0x000d63, 0x000d66, 0x000d7f, 0x000d81, 0x000d83, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd,
                        0x000dc0, 0x000dc6, 0x000dca, 0x000dca, 0x000dcf, 0x000dd4, 0x000dd6, 0x000dd6, 0x000dd8, 0x000ddf, 0x000de6, 0x000def, 0x000df2, 0x000df4, 0x000e01, 0x000e3a, 0x000e3f,
                        0x000e5b, 0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000ebd, 0x000ec0, 0x000ec4, 0x000ec6, 0x000ec6,
                        0x000ec8, 0x000ece, 0x000ed0, 0x000ed9, 0x000edc, 0x000edf, 0x000f00, 0x000f47, 0x000f49, 0x000f6c, 0x000f71, 0x000f97, 0x000f99, 0x000fbc, 0x000fbe, 0x000fcc, 0x000fce,
                        0x000fda, 0x001000, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d,
                        0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290, 0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8,
                        0x001310, 0x001312, 0x001315, 0x001318, 0x00135a, 0x00135d, 0x00137c, 0x001380, 0x001399, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001400, 0x00169c, 0x0016a0, 0x0016f8,
                        0x001700, 0x001715, 0x00171f, 0x001736, 0x001740, 0x001753, 0x001760, 0x00176c, 0x00176e, 0x001770, 0x001772, 0x001773, 0x001780, 0x0017dd, 0x0017e0, 0x0017e9, 0x0017f0,
                        0x0017f9, 0x001800, 0x001819, 0x001820, 0x001878, 0x001880, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900, 0x00191e, 0x001920, 0x00192b, 0x001930, 0x00193b, 0x001940, 0x001940,
                        0x001944, 0x00196d, 0x001970, 0x001974, 0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x0019d0, 0x0019da, 0x0019de, 0x001a1b, 0x001a1e, 0x001a5e, 0x001a60, 0x001a7c, 0x001a7f,
                        0x001a89, 0x001a90, 0x001a99, 0x001aa0, 0x001aad, 0x001ab0, 0x001ace, 0x001b00, 0x001b4c, 0x001b50, 0x001b7e, 0x001b80, 0x001bf3, 0x001bfc, 0x001c37, 0x001c3b, 0x001c49,
                        0x001c4d, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cc7, 0x001cd0, 0x001cfa, 0x001d00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50,
                        0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fc4, 0x001fc6, 0x001fd3, 0x001fd6, 0x001fdb,
                        0x001fdd, 0x001fef, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffe, 0x002000, 0x002064, 0x002066, 0x002071, 0x002074, 0x00208e, 0x002090, 0x00209c, 0x0020a0, 0x0020c0, 0x0020d0,
                        0x0020f0, 0x002100, 0x00218b, 0x002190, 0x002426, 0x002440, 0x00244a, 0x002460, 0x002b73, 0x002b76, 0x002b95, 0x002b97, 0x002cf3, 0x002cf9, 0x002d25, 0x002d27, 0x002d27,
                        0x002d2d, 0x002d2d, 0x002d30, 0x002d67, 0x002d6f, 0x002d70, 0x002d7f, 0x002d96, 0x002da0, 0x002da6, 0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe, 0x002dc0,
                        0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x002de0, 0x002e5d, 0x002e80, 0x002e99, 0x002e9b, 0x002ef3, 0x002f00, 0x002fd5, 0x002ff0, 0x002ffb,
                        0x003000, 0x00303f, 0x003041, 0x003096, 0x003099, 0x0030ff, 0x003105, 0x00312f, 0x003131, 0x00318e, 0x003190, 0x0031e3, 0x0031f0, 0x00321e, 0x003220, 0x00a48c, 0x00a490,
                        0x00a4c6, 0x00a4d0, 0x00a62b, 0x00a640, 0x00a6f7, 0x00a700, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9, 0x00a7f2, 0x00a82c, 0x00a830, 0x00a839,
                        0x00a840, 0x00a877, 0x00a880, 0x00a8c5, 0x00a8ce, 0x00a8d9, 0x00a8e0, 0x00a953, 0x00a95f, 0x00a97c, 0x00a980, 0x00a9cd, 0x00a9cf, 0x00a9d9, 0x00a9de, 0x00a9fe, 0x00aa00,
                        0x00aa36, 0x00aa40, 0x00aa4d, 0x00aa50, 0x00aa59, 0x00aa5c, 0x00aac2, 0x00aadb, 0x00aaf6, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20, 0x00ab26,
                        0x00ab28, 0x00ab2e, 0x00ab30, 0x00ab6b, 0x00ab70, 0x00abed, 0x00abf0, 0x00abf9, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb, 0x00d800, 0x00fa6d, 0x00fa70,
                        0x00fad9, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40, 0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fbc2,
                        0x00fbd3, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdcf, 0x00fdcf, 0x00fdf0, 0x00fe19, 0x00fe20, 0x00fe52, 0x00fe54, 0x00fe66, 0x00fe68, 0x00fe6b, 0x00fe70, 0x00fe74, 0x00fe76,
                        0x00fefc, 0x00feff, 0x00feff, 0x00ff01, 0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x00ffe0, 0x00ffe6, 0x00ffe8, 0x00ffee,
                        0x00fff9, 0x00fffd, 0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d, 0x01003f, 0x01004d, 0x010050, 0x01005d, 0x010080, 0x0100fa, 0x010100,
                        0x010102, 0x010107, 0x010133, 0x010137, 0x01018e, 0x010190, 0x01019c, 0x0101a0, 0x0101a0, 0x0101d0, 0x0101fd, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x0102e0, 0x0102fb,
                        0x010300, 0x010323, 0x01032d, 0x01034a, 0x010350, 0x01037a, 0x010380, 0x01039d, 0x01039f, 0x0103c3, 0x0103c8, 0x0103d5, 0x010400, 0x01049d, 0x0104a0, 0x0104a9, 0x0104b0,
                        0x0104d3, 0x0104d8, 0x0104fb, 0x010500, 0x010527, 0x010530, 0x010563, 0x01056f, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1,
                        0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2,
                        0x0107ba, 0x010800, 0x010805, 0x010808, 0x010808, 0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x010855, 0x010857, 0x01089e, 0x0108a7, 0x0108af,
                        0x0108e0, 0x0108f2, 0x0108f4, 0x0108f5, 0x0108fb, 0x01091b, 0x01091f, 0x010939, 0x01093f, 0x01093f, 0x010980, 0x0109b7, 0x0109bc, 0x0109cf, 0x0109d2, 0x010a03, 0x010a05,
                        0x010a06, 0x010a0c, 0x010a13, 0x010a15, 0x010a17, 0x010a19, 0x010a35, 0x010a38, 0x010a3a, 0x010a3f, 0x010a48, 0x010a50, 0x010a58, 0x010a60, 0x010a9f, 0x010ac0, 0x010ae6,
                        0x010aeb, 0x010af6, 0x010b00, 0x010b35, 0x010b39, 0x010b55, 0x010b58, 0x010b72, 0x010b78, 0x010b91, 0x010b99, 0x010b9c, 0x010ba9, 0x010baf, 0x010c00, 0x010c48, 0x010c80,
                        0x010cb2, 0x010cc0, 0x010cf2, 0x010cfa, 0x010d27, 0x010d30, 0x010d39, 0x010e60, 0x010e7e, 0x010e80, 0x010ea9, 0x010eab, 0x010ead, 0x010eb0, 0x010eb1, 0x010efd, 0x010f27,
                        0x010f30, 0x010f59, 0x010f70, 0x010f89, 0x010fb0, 0x010fcb, 0x010fe0, 0x010ff6, 0x011000, 0x01104d, 0x011052, 0x011075, 0x01107f, 0x0110c2, 0x0110cd, 0x0110cd, 0x0110d0,
                        0x0110e8, 0x0110f0, 0x0110f9, 0x011100, 0x011134, 0x011136, 0x011147, 0x011150, 0x011176, 0x011180, 0x0111df, 0x0111e1, 0x0111f4, 0x011200, 0x011211, 0x011213, 0x011241,
                        0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a9, 0x0112b0, 0x0112ea, 0x0112f0, 0x0112f9, 0x011300, 0x011303, 0x011305,
                        0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133b, 0x011344, 0x011347, 0x011348, 0x01134b, 0x01134d,
                        0x011350, 0x011350, 0x011357, 0x011357, 0x01135d, 0x011363, 0x011366, 0x01136c, 0x011370, 0x011374, 0x011400, 0x01145b, 0x01145d, 0x011461, 0x011480, 0x0114c7, 0x0114d0,
                        0x0114d9, 0x011580, 0x0115b5, 0x0115b8, 0x0115dd, 0x011600, 0x011644, 0x011650, 0x011659, 0x011660, 0x01166c, 0x011680, 0x0116b9, 0x0116c0, 0x0116c9, 0x011700, 0x01171a,
                        0x01171d, 0x01172b, 0x011730, 0x011746, 0x011800, 0x01183b, 0x0118a0, 0x0118f2, 0x0118ff, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918,
                        0x011935, 0x011937, 0x011938, 0x01193b, 0x011946, 0x011950, 0x011959, 0x0119a0, 0x0119a7, 0x0119aa, 0x0119d7, 0x0119da, 0x0119e4, 0x011a00, 0x011a47, 0x011a50, 0x011aa2,
                        0x011ab0, 0x011af8, 0x011b00, 0x011b09, 0x011c00, 0x011c08, 0x011c0a, 0x011c36, 0x011c38, 0x011c45, 0x011c50, 0x011c6c, 0x011c70, 0x011c8f, 0x011c92, 0x011ca7, 0x011ca9,
                        0x011cb6, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d47, 0x011d50, 0x011d59, 0x011d60, 0x011d65,
                        0x011d67, 0x011d68, 0x011d6a, 0x011d8e, 0x011d90, 0x011d91, 0x011d93, 0x011d98, 0x011da0, 0x011da9, 0x011ee0, 0x011ef8, 0x011f00, 0x011f10, 0x011f12, 0x011f3a, 0x011f3e,
                        0x011f59, 0x011fb0, 0x011fb0, 0x011fc0, 0x011ff1, 0x011fff, 0x012399, 0x012400, 0x01246e, 0x012470, 0x012474, 0x012480, 0x012543, 0x012f90, 0x012ff2, 0x013000, 0x013455,
                        0x014400, 0x014646, 0x016800, 0x016a38, 0x016a40, 0x016a5e, 0x016a60, 0x016a69, 0x016a6e, 0x016abe, 0x016ac0, 0x016ac9, 0x016ad0, 0x016aed, 0x016af0, 0x016af5, 0x016b00,
                        0x016b45, 0x016b50, 0x016b59, 0x016b5b, 0x016b61, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f, 0x016e40, 0x016e9a, 0x016f00, 0x016f4a, 0x016f4f, 0x016f87, 0x016f8f, 0x016f9f,
                        0x016fe0, 0x016fe4, 0x016ff0, 0x016ff1, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01b000,
                        0x01b122, 0x01b132, 0x01b132, 0x01b150, 0x01b152, 0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb, 0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88,
                        0x01bc90, 0x01bc99, 0x01bc9c, 0x01bca3, 0x01cf00, 0x01cf2d, 0x01cf30, 0x01cf46, 0x01cf50, 0x01cfc3, 0x01d000, 0x01d0f5, 0x01d100, 0x01d126, 0x01d129, 0x01d1ea, 0x01d200,
                        0x01d245, 0x01d2c0, 0x01d2d3, 0x01d2e0, 0x01d2f3, 0x01d300, 0x01d356, 0x01d360, 0x01d378, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2,
                        0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516,
                        0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d7cb, 0x01d7ce, 0x01da8b,
                        0x01da9b, 0x01da9f, 0x01daa1, 0x01daaf, 0x01df00, 0x01df1e, 0x01df25, 0x01df2a, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b, 0x01e021, 0x01e023, 0x01e024, 0x01e026,
                        0x01e02a, 0x01e030, 0x01e06d, 0x01e08f, 0x01e08f, 0x01e100, 0x01e12c, 0x01e130, 0x01e13d, 0x01e140, 0x01e149, 0x01e14e, 0x01e14f, 0x01e290, 0x01e2ae, 0x01e2c0, 0x01e2f9,
                        0x01e2ff, 0x01e2ff, 0x01e4d0, 0x01e4f9, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe, 0x01e800, 0x01e8c4, 0x01e8c7, 0x01e8d6, 0x01e900,
                        0x01e94b, 0x01e950, 0x01e959, 0x01e95e, 0x01e95f, 0x01ec71, 0x01ecb4, 0x01ed01, 0x01ed3d, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24,
                        0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b,
                        0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f,
                        0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b,
                        0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x01eef0, 0x01eef1, 0x01f000, 0x01f02b, 0x01f030, 0x01f093, 0x01f0a0, 0x01f0ae, 0x01f0b1, 0x01f0bf,
                        0x01f0c1, 0x01f0cf, 0x01f0d1, 0x01f0f5, 0x01f100, 0x01f1ad, 0x01f1e6, 0x01f202, 0x01f210, 0x01f23b, 0x01f240, 0x01f248, 0x01f250, 0x01f251, 0x01f260, 0x01f265, 0x01f300,
                        0x01f6d7, 0x01f6dc, 0x01f6ec, 0x01f6f0, 0x01f6fc, 0x01f700, 0x01f776, 0x01f77b, 0x01f7d9, 0x01f7e0, 0x01f7eb, 0x01f7f0, 0x01f7f0, 0x01f800, 0x01f80b, 0x01f810, 0x01f847,
                        0x01f850, 0x01f859, 0x01f860, 0x01f887, 0x01f890, 0x01f8ad, 0x01f8b0, 0x01f8b1, 0x01f900, 0x01fa53, 0x01fa60, 0x01fa6d, 0x01fa70, 0x01fa7c, 0x01fa80, 0x01fa88, 0x01fa90,
                        0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb, 0x01fae0, 0x01fae8, 0x01faf0, 0x01faf8, 0x01fb00, 0x01fb92, 0x01fb94, 0x01fbca, 0x01fbf0, 0x01fbf9, 0x020000, 0x02a6df,
                        0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af, 0x0e0001, 0x0e0001, 0x0e0020,
                        0x0e007f, 0x0e0100, 0x0e01ef, 0x0f0000, 0x0ffffd, 0x100000, 0x10fffd));
    }

    private static void populateBIDI_C() {
        SET_ENCODINGS.put("Bidi_C", CodePointSet.createNoDedup(0x00061c, 0x00061c, 0x00200e, 0x00200f, 0x00202a, 0x00202e, 0x002066, 0x002069));
    }

    private static void populateBIDI_M() {
        SET_ENCODINGS.put("Bidi_M", CodePointSet.createNoDedup(0x000028, 0x000029, 0x00003c, 0x00003c, 0x00003e, 0x00003e, 0x00005b, 0x00005b, 0x00005d, 0x00005d, 0x00007b, 0x00007b, 0x00007d,
                        0x00007d, 0x0000ab, 0x0000ab, 0x0000bb, 0x0000bb, 0x000f3a, 0x000f3d, 0x00169b, 0x00169c, 0x002039, 0x00203a, 0x002045, 0x002046, 0x00207d, 0x00207e, 0x00208d, 0x00208e,
                        0x002140, 0x002140, 0x002201, 0x002204, 0x002208, 0x00220d, 0x002211, 0x002211, 0x002215, 0x002216, 0x00221a, 0x00221d, 0x00221f, 0x002222, 0x002224, 0x002224, 0x002226,
                        0x002226, 0x00222b, 0x002233, 0x002239, 0x002239, 0x00223b, 0x00224c, 0x002252, 0x002255, 0x00225f, 0x002260, 0x002262, 0x002262, 0x002264, 0x00226b, 0x00226e, 0x00228c,
                        0x00228f, 0x002292, 0x002298, 0x002298, 0x0022a2, 0x0022a3, 0x0022a6, 0x0022b8, 0x0022be, 0x0022bf, 0x0022c9, 0x0022cd, 0x0022d0, 0x0022d1, 0x0022d6, 0x0022ed, 0x0022f0,
                        0x0022ff, 0x002308, 0x00230b, 0x002320, 0x002321, 0x002329, 0x00232a, 0x002768, 0x002775, 0x0027c0, 0x0027c0, 0x0027c3, 0x0027c6, 0x0027c8, 0x0027c9, 0x0027cb, 0x0027cd,
                        0x0027d3, 0x0027d6, 0x0027dc, 0x0027de, 0x0027e2, 0x0027ef, 0x002983, 0x002998, 0x00299b, 0x0029a0, 0x0029a2, 0x0029af, 0x0029b8, 0x0029b8, 0x0029c0, 0x0029c5, 0x0029c9,
                        0x0029c9, 0x0029ce, 0x0029d2, 0x0029d4, 0x0029d5, 0x0029d8, 0x0029dc, 0x0029e1, 0x0029e1, 0x0029e3, 0x0029e5, 0x0029e8, 0x0029e9, 0x0029f4, 0x0029f9, 0x0029fc, 0x0029fd,
                        0x002a0a, 0x002a1c, 0x002a1e, 0x002a21, 0x002a24, 0x002a24, 0x002a26, 0x002a26, 0x002a29, 0x002a29, 0x002a2b, 0x002a2e, 0x002a34, 0x002a35, 0x002a3c, 0x002a3e, 0x002a57,
                        0x002a58, 0x002a64, 0x002a65, 0x002a6a, 0x002a6d, 0x002a6f, 0x002a70, 0x002a73, 0x002a74, 0x002a79, 0x002aa3, 0x002aa6, 0x002aad, 0x002aaf, 0x002ad6, 0x002adc, 0x002adc,
                        0x002ade, 0x002ade, 0x002ae2, 0x002ae6, 0x002aec, 0x002aee, 0x002af3, 0x002af3, 0x002af7, 0x002afb, 0x002afd, 0x002afd, 0x002bfe, 0x002bfe, 0x002e02, 0x002e05, 0x002e09,
                        0x002e0a, 0x002e0c, 0x002e0d, 0x002e1c, 0x002e1d, 0x002e20, 0x002e29, 0x002e55, 0x002e5c, 0x003008, 0x003011, 0x003014, 0x00301b, 0x00fe59, 0x00fe5e, 0x00fe64, 0x00fe65,
                        0x00ff08, 0x00ff09, 0x00ff1c, 0x00ff1c, 0x00ff1e, 0x00ff1e, 0x00ff3b, 0x00ff3b, 0x00ff3d, 0x00ff3d, 0x00ff5b, 0x00ff5b, 0x00ff5d, 0x00ff5d, 0x00ff5f, 0x00ff60, 0x00ff62,
                        0x00ff63, 0x01d6db, 0x01d6db, 0x01d715, 0x01d715, 0x01d74f, 0x01d74f, 0x01d789, 0x01d789, 0x01d7c3, 0x01d7c3));
    }

    private static void populateCI() {
        SET_ENCODINGS.put("CI", CodePointSet.createNoDedup(0x000027, 0x000027, 0x00002e, 0x00002e, 0x00003a, 0x00003a, 0x00005e, 0x00005e, 0x000060, 0x000060, 0x0000a8, 0x0000a8, 0x0000ad, 0x0000ad,
                        0x0000af, 0x0000af, 0x0000b4, 0x0000b4, 0x0000b7, 0x0000b8, 0x0002b0, 0x00036f, 0x000374, 0x000375, 0x00037a, 0x00037a, 0x000384, 0x000385, 0x000387, 0x000387, 0x000483,
                        0x000489, 0x000559, 0x000559, 0x00055f, 0x00055f, 0x000591, 0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4, 0x0005c5, 0x0005c7, 0x0005c7, 0x0005f4, 0x0005f4,
                        0x000600, 0x000605, 0x000610, 0x00061a, 0x00061c, 0x00061c, 0x000640, 0x000640, 0x00064b, 0x00065f, 0x000670, 0x000670, 0x0006d6, 0x0006dd, 0x0006df, 0x0006e8, 0x0006ea,
                        0x0006ed, 0x00070f, 0x00070f, 0x000711, 0x000711, 0x000730, 0x00074a, 0x0007a6, 0x0007b0, 0x0007eb, 0x0007f5, 0x0007fa, 0x0007fa, 0x0007fd, 0x0007fd, 0x000816, 0x00082d,
                        0x000859, 0x00085b, 0x000888, 0x000888, 0x000890, 0x000891, 0x000898, 0x00089f, 0x0008c9, 0x000902, 0x00093a, 0x00093a, 0x00093c, 0x00093c, 0x000941, 0x000948, 0x00094d,
                        0x00094d, 0x000951, 0x000957, 0x000962, 0x000963, 0x000971, 0x000971, 0x000981, 0x000981, 0x0009bc, 0x0009bc, 0x0009c1, 0x0009c4, 0x0009cd, 0x0009cd, 0x0009e2, 0x0009e3,
                        0x0009fe, 0x0009fe, 0x000a01, 0x000a02, 0x000a3c, 0x000a3c, 0x000a41, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4d, 0x000a51, 0x000a51, 0x000a70, 0x000a71, 0x000a75,
                        0x000a75, 0x000a81, 0x000a82, 0x000abc, 0x000abc, 0x000ac1, 0x000ac5, 0x000ac7, 0x000ac8, 0x000acd, 0x000acd, 0x000ae2, 0x000ae3, 0x000afa, 0x000aff, 0x000b01, 0x000b01,
                        0x000b3c, 0x000b3c, 0x000b3f, 0x000b3f, 0x000b41, 0x000b44, 0x000b4d, 0x000b4d, 0x000b55, 0x000b56, 0x000b62, 0x000b63, 0x000b82, 0x000b82, 0x000bc0, 0x000bc0, 0x000bcd,
                        0x000bcd, 0x000c00, 0x000c00, 0x000c04, 0x000c04, 0x000c3c, 0x000c3c, 0x000c3e, 0x000c40, 0x000c46, 0x000c48, 0x000c4a, 0x000c4d, 0x000c55, 0x000c56, 0x000c62, 0x000c63,
                        0x000c81, 0x000c81, 0x000cbc, 0x000cbc, 0x000cbf, 0x000cbf, 0x000cc6, 0x000cc6, 0x000ccc, 0x000ccd, 0x000ce2, 0x000ce3, 0x000d00, 0x000d01, 0x000d3b, 0x000d3c, 0x000d41,
                        0x000d44, 0x000d4d, 0x000d4d, 0x000d62, 0x000d63, 0x000d81, 0x000d81, 0x000dca, 0x000dca, 0x000dd2, 0x000dd4, 0x000dd6, 0x000dd6, 0x000e31, 0x000e31, 0x000e34, 0x000e3a,
                        0x000e46, 0x000e4e, 0x000eb1, 0x000eb1, 0x000eb4, 0x000ebc, 0x000ec6, 0x000ec6, 0x000ec8, 0x000ece, 0x000f18, 0x000f19, 0x000f35, 0x000f35, 0x000f37, 0x000f37, 0x000f39,
                        0x000f39, 0x000f71, 0x000f7e, 0x000f80, 0x000f84, 0x000f86, 0x000f87, 0x000f8d, 0x000f97, 0x000f99, 0x000fbc, 0x000fc6, 0x000fc6, 0x00102d, 0x001030, 0x001032, 0x001037,
                        0x001039, 0x00103a, 0x00103d, 0x00103e, 0x001058, 0x001059, 0x00105e, 0x001060, 0x001071, 0x001074, 0x001082, 0x001082, 0x001085, 0x001086, 0x00108d, 0x00108d, 0x00109d,
                        0x00109d, 0x0010fc, 0x0010fc, 0x00135d, 0x00135f, 0x001712, 0x001714, 0x001732, 0x001733, 0x001752, 0x001753, 0x001772, 0x001773, 0x0017b4, 0x0017b5, 0x0017b7, 0x0017bd,
                        0x0017c6, 0x0017c6, 0x0017c9, 0x0017d3, 0x0017d7, 0x0017d7, 0x0017dd, 0x0017dd, 0x00180b, 0x00180f, 0x001843, 0x001843, 0x001885, 0x001886, 0x0018a9, 0x0018a9, 0x001920,
                        0x001922, 0x001927, 0x001928, 0x001932, 0x001932, 0x001939, 0x00193b, 0x001a17, 0x001a18, 0x001a1b, 0x001a1b, 0x001a56, 0x001a56, 0x001a58, 0x001a5e, 0x001a60, 0x001a60,
                        0x001a62, 0x001a62, 0x001a65, 0x001a6c, 0x001a73, 0x001a7c, 0x001a7f, 0x001a7f, 0x001aa7, 0x001aa7, 0x001ab0, 0x001ace, 0x001b00, 0x001b03, 0x001b34, 0x001b34, 0x001b36,
                        0x001b3a, 0x001b3c, 0x001b3c, 0x001b42, 0x001b42, 0x001b6b, 0x001b73, 0x001b80, 0x001b81, 0x001ba2, 0x001ba5, 0x001ba8, 0x001ba9, 0x001bab, 0x001bad, 0x001be6, 0x001be6,
                        0x001be8, 0x001be9, 0x001bed, 0x001bed, 0x001bef, 0x001bf1, 0x001c2c, 0x001c33, 0x001c36, 0x001c37, 0x001c78, 0x001c7d, 0x001cd0, 0x001cd2, 0x001cd4, 0x001ce0, 0x001ce2,
                        0x001ce8, 0x001ced, 0x001ced, 0x001cf4, 0x001cf4, 0x001cf8, 0x001cf9, 0x001d2c, 0x001d6a, 0x001d78, 0x001d78, 0x001d9b, 0x001dff, 0x001fbd, 0x001fbd, 0x001fbf, 0x001fc1,
                        0x001fcd, 0x001fcf, 0x001fdd, 0x001fdf, 0x001fed, 0x001fef, 0x001ffd, 0x001ffe, 0x00200b, 0x00200f, 0x002018, 0x002019, 0x002024, 0x002024, 0x002027, 0x002027, 0x00202a,
                        0x00202e, 0x002060, 0x002064, 0x002066, 0x00206f, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090, 0x00209c, 0x0020d0, 0x0020f0, 0x002c7c, 0x002c7d, 0x002cef, 0x002cf1,
                        0x002d6f, 0x002d6f, 0x002d7f, 0x002d7f, 0x002de0, 0x002dff, 0x002e2f, 0x002e2f, 0x003005, 0x003005, 0x00302a, 0x00302d, 0x003031, 0x003035, 0x00303b, 0x00303b, 0x003099,
                        0x00309e, 0x0030fc, 0x0030fe, 0x00a015, 0x00a015, 0x00a4f8, 0x00a4fd, 0x00a60c, 0x00a60c, 0x00a66f, 0x00a672, 0x00a674, 0x00a67d, 0x00a67f, 0x00a67f, 0x00a69c, 0x00a69f,
                        0x00a6f0, 0x00a6f1, 0x00a700, 0x00a721, 0x00a770, 0x00a770, 0x00a788, 0x00a78a, 0x00a7f2, 0x00a7f4, 0x00a7f8, 0x00a7f9, 0x00a802, 0x00a802, 0x00a806, 0x00a806, 0x00a80b,
                        0x00a80b, 0x00a825, 0x00a826, 0x00a82c, 0x00a82c, 0x00a8c4, 0x00a8c5, 0x00a8e0, 0x00a8f1, 0x00a8ff, 0x00a8ff, 0x00a926, 0x00a92d, 0x00a947, 0x00a951, 0x00a980, 0x00a982,
                        0x00a9b3, 0x00a9b3, 0x00a9b6, 0x00a9b9, 0x00a9bc, 0x00a9bd, 0x00a9cf, 0x00a9cf, 0x00a9e5, 0x00a9e6, 0x00aa29, 0x00aa2e, 0x00aa31, 0x00aa32, 0x00aa35, 0x00aa36, 0x00aa43,
                        0x00aa43, 0x00aa4c, 0x00aa4c, 0x00aa70, 0x00aa70, 0x00aa7c, 0x00aa7c, 0x00aab0, 0x00aab0, 0x00aab2, 0x00aab4, 0x00aab7, 0x00aab8, 0x00aabe, 0x00aabf, 0x00aac1, 0x00aac1,
                        0x00aadd, 0x00aadd, 0x00aaec, 0x00aaed, 0x00aaf3, 0x00aaf4, 0x00aaf6, 0x00aaf6, 0x00ab5b, 0x00ab5f, 0x00ab69, 0x00ab6b, 0x00abe5, 0x00abe5, 0x00abe8, 0x00abe8, 0x00abed,
                        0x00abed, 0x00fb1e, 0x00fb1e, 0x00fbb2, 0x00fbc2, 0x00fe00, 0x00fe0f, 0x00fe13, 0x00fe13, 0x00fe20, 0x00fe2f, 0x00fe52, 0x00fe52, 0x00fe55, 0x00fe55, 0x00feff, 0x00feff,
                        0x00ff07, 0x00ff07, 0x00ff0e, 0x00ff0e, 0x00ff1a, 0x00ff1a, 0x00ff3e, 0x00ff3e, 0x00ff40, 0x00ff40, 0x00ff70, 0x00ff70, 0x00ff9e, 0x00ff9f, 0x00ffe3, 0x00ffe3, 0x00fff9,
                        0x00fffb, 0x0101fd, 0x0101fd, 0x0102e0, 0x0102e0, 0x010376, 0x01037a, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010a01, 0x010a03, 0x010a05, 0x010a06,
                        0x010a0c, 0x010a0f, 0x010a38, 0x010a3a, 0x010a3f, 0x010a3f, 0x010ae5, 0x010ae6, 0x010d24, 0x010d27, 0x010eab, 0x010eac, 0x010efd, 0x010eff, 0x010f46, 0x010f50, 0x010f82,
                        0x010f85, 0x011001, 0x011001, 0x011038, 0x011046, 0x011070, 0x011070, 0x011073, 0x011074, 0x01107f, 0x011081, 0x0110b3, 0x0110b6, 0x0110b9, 0x0110ba, 0x0110bd, 0x0110bd,
                        0x0110c2, 0x0110c2, 0x0110cd, 0x0110cd, 0x011100, 0x011102, 0x011127, 0x01112b, 0x01112d, 0x011134, 0x011173, 0x011173, 0x011180, 0x011181, 0x0111b6, 0x0111be, 0x0111c9,
                        0x0111cc, 0x0111cf, 0x0111cf, 0x01122f, 0x011231, 0x011234, 0x011234, 0x011236, 0x011237, 0x01123e, 0x01123e, 0x011241, 0x011241, 0x0112df, 0x0112df, 0x0112e3, 0x0112ea,
                        0x011300, 0x011301, 0x01133b, 0x01133c, 0x011340, 0x011340, 0x011366, 0x01136c, 0x011370, 0x011374, 0x011438, 0x01143f, 0x011442, 0x011444, 0x011446, 0x011446, 0x01145e,
                        0x01145e, 0x0114b3, 0x0114b8, 0x0114ba, 0x0114ba, 0x0114bf, 0x0114c0, 0x0114c2, 0x0114c3, 0x0115b2, 0x0115b5, 0x0115bc, 0x0115bd, 0x0115bf, 0x0115c0, 0x0115dc, 0x0115dd,
                        0x011633, 0x01163a, 0x01163d, 0x01163d, 0x01163f, 0x011640, 0x0116ab, 0x0116ab, 0x0116ad, 0x0116ad, 0x0116b0, 0x0116b5, 0x0116b7, 0x0116b7, 0x01171d, 0x01171f, 0x011722,
                        0x011725, 0x011727, 0x01172b, 0x01182f, 0x011837, 0x011839, 0x01183a, 0x01193b, 0x01193c, 0x01193e, 0x01193e, 0x011943, 0x011943, 0x0119d4, 0x0119d7, 0x0119da, 0x0119db,
                        0x0119e0, 0x0119e0, 0x011a01, 0x011a0a, 0x011a33, 0x011a38, 0x011a3b, 0x011a3e, 0x011a47, 0x011a47, 0x011a51, 0x011a56, 0x011a59, 0x011a5b, 0x011a8a, 0x011a96, 0x011a98,
                        0x011a99, 0x011c30, 0x011c36, 0x011c38, 0x011c3d, 0x011c3f, 0x011c3f, 0x011c92, 0x011ca7, 0x011caa, 0x011cb0, 0x011cb2, 0x011cb3, 0x011cb5, 0x011cb6, 0x011d31, 0x011d36,
                        0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d45, 0x011d47, 0x011d47, 0x011d90, 0x011d91, 0x011d95, 0x011d95, 0x011d97, 0x011d97, 0x011ef3, 0x011ef4, 0x011f00,
                        0x011f01, 0x011f36, 0x011f3a, 0x011f40, 0x011f40, 0x011f42, 0x011f42, 0x013430, 0x013440, 0x013447, 0x013455, 0x016af0, 0x016af4, 0x016b30, 0x016b36, 0x016b40, 0x016b43,
                        0x016f4f, 0x016f4f, 0x016f8f, 0x016f9f, 0x016fe0, 0x016fe1, 0x016fe3, 0x016fe4, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01bc9d, 0x01bc9e, 0x01bca0,
                        0x01bca3, 0x01cf00, 0x01cf2d, 0x01cf30, 0x01cf46, 0x01d167, 0x01d169, 0x01d173, 0x01d182, 0x01d185, 0x01d18b, 0x01d1aa, 0x01d1ad, 0x01d242, 0x01d244, 0x01da00, 0x01da36,
                        0x01da3b, 0x01da6c, 0x01da75, 0x01da75, 0x01da84, 0x01da84, 0x01da9b, 0x01da9f, 0x01daa1, 0x01daaf, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b, 0x01e021, 0x01e023,
                        0x01e024, 0x01e026, 0x01e02a, 0x01e030, 0x01e06d, 0x01e08f, 0x01e08f, 0x01e130, 0x01e13d, 0x01e2ae, 0x01e2ae, 0x01e2ec, 0x01e2ef, 0x01e4eb, 0x01e4ef, 0x01e8d0, 0x01e8d6,
                        0x01e944, 0x01e94b, 0x01f3fb, 0x01f3ff, 0x0e0001, 0x0e0001, 0x0e0020, 0x0e007f, 0x0e0100, 0x0e01ef));
    }

    private static void populateCWCF() {
        SET_ENCODINGS.put("CWCF", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x0000b5, 0x0000b5, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000df, 0x000100, 0x000100, 0x000102, 0x000102, 0x000104, 0x000104,
                        0x000106, 0x000106, 0x000108, 0x000108, 0x00010a, 0x00010a, 0x00010c, 0x00010c, 0x00010e, 0x00010e, 0x000110, 0x000110, 0x000112, 0x000112, 0x000114, 0x000114, 0x000116,
                        0x000116, 0x000118, 0x000118, 0x00011a, 0x00011a, 0x00011c, 0x00011c, 0x00011e, 0x00011e, 0x000120, 0x000120, 0x000122, 0x000122, 0x000124, 0x000124, 0x000126, 0x000126,
                        0x000128, 0x000128, 0x00012a, 0x00012a, 0x00012c, 0x00012c, 0x00012e, 0x00012e, 0x000130, 0x000130, 0x000132, 0x000132, 0x000134, 0x000134, 0x000136, 0x000136, 0x000139,
                        0x000139, 0x00013b, 0x00013b, 0x00013d, 0x00013d, 0x00013f, 0x00013f, 0x000141, 0x000141, 0x000143, 0x000143, 0x000145, 0x000145, 0x000147, 0x000147, 0x000149, 0x00014a,
                        0x00014c, 0x00014c, 0x00014e, 0x00014e, 0x000150, 0x000150, 0x000152, 0x000152, 0x000154, 0x000154, 0x000156, 0x000156, 0x000158, 0x000158, 0x00015a, 0x00015a, 0x00015c,
                        0x00015c, 0x00015e, 0x00015e, 0x000160, 0x000160, 0x000162, 0x000162, 0x000164, 0x000164, 0x000166, 0x000166, 0x000168, 0x000168, 0x00016a, 0x00016a, 0x00016c, 0x00016c,
                        0x00016e, 0x00016e, 0x000170, 0x000170, 0x000172, 0x000172, 0x000174, 0x000174, 0x000176, 0x000176, 0x000178, 0x000179, 0x00017b, 0x00017b, 0x00017d, 0x00017d, 0x00017f,
                        0x00017f, 0x000181, 0x000182, 0x000184, 0x000184, 0x000186, 0x000187, 0x000189, 0x00018b, 0x00018e, 0x000191, 0x000193, 0x000194, 0x000196, 0x000198, 0x00019c, 0x00019d,
                        0x00019f, 0x0001a0, 0x0001a2, 0x0001a2, 0x0001a4, 0x0001a4, 0x0001a6, 0x0001a7, 0x0001a9, 0x0001a9, 0x0001ac, 0x0001ac, 0x0001ae, 0x0001af, 0x0001b1, 0x0001b3, 0x0001b5,
                        0x0001b5, 0x0001b7, 0x0001b8, 0x0001bc, 0x0001bc, 0x0001c4, 0x0001c5, 0x0001c7, 0x0001c8, 0x0001ca, 0x0001cb, 0x0001cd, 0x0001cd, 0x0001cf, 0x0001cf, 0x0001d1, 0x0001d1,
                        0x0001d3, 0x0001d3, 0x0001d5, 0x0001d5, 0x0001d7, 0x0001d7, 0x0001d9, 0x0001d9, 0x0001db, 0x0001db, 0x0001de, 0x0001de, 0x0001e0, 0x0001e0, 0x0001e2, 0x0001e2, 0x0001e4,
                        0x0001e4, 0x0001e6, 0x0001e6, 0x0001e8, 0x0001e8, 0x0001ea, 0x0001ea, 0x0001ec, 0x0001ec, 0x0001ee, 0x0001ee, 0x0001f1, 0x0001f2, 0x0001f4, 0x0001f4, 0x0001f6, 0x0001f8,
                        0x0001fa, 0x0001fa, 0x0001fc, 0x0001fc, 0x0001fe, 0x0001fe, 0x000200, 0x000200, 0x000202, 0x000202, 0x000204, 0x000204, 0x000206, 0x000206, 0x000208, 0x000208, 0x00020a,
                        0x00020a, 0x00020c, 0x00020c, 0x00020e, 0x00020e, 0x000210, 0x000210, 0x000212, 0x000212, 0x000214, 0x000214, 0x000216, 0x000216, 0x000218, 0x000218, 0x00021a, 0x00021a,
                        0x00021c, 0x00021c, 0x00021e, 0x00021e, 0x000220, 0x000220, 0x000222, 0x000222, 0x000224, 0x000224, 0x000226, 0x000226, 0x000228, 0x000228, 0x00022a, 0x00022a, 0x00022c,
                        0x00022c, 0x00022e, 0x00022e, 0x000230, 0x000230, 0x000232, 0x000232, 0x00023a, 0x00023b, 0x00023d, 0x00023e, 0x000241, 0x000241, 0x000243, 0x000246, 0x000248, 0x000248,
                        0x00024a, 0x00024a, 0x00024c, 0x00024c, 0x00024e, 0x00024e, 0x000345, 0x000345, 0x000370, 0x000370, 0x000372, 0x000372, 0x000376, 0x000376, 0x00037f, 0x00037f, 0x000386,
                        0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x00038f, 0x000391, 0x0003a1, 0x0003a3, 0x0003ab, 0x0003c2, 0x0003c2, 0x0003cf, 0x0003d1, 0x0003d5, 0x0003d6,
                        0x0003d8, 0x0003d8, 0x0003da, 0x0003da, 0x0003dc, 0x0003dc, 0x0003de, 0x0003de, 0x0003e0, 0x0003e0, 0x0003e2, 0x0003e2, 0x0003e4, 0x0003e4, 0x0003e6, 0x0003e6, 0x0003e8,
                        0x0003e8, 0x0003ea, 0x0003ea, 0x0003ec, 0x0003ec, 0x0003ee, 0x0003ee, 0x0003f0, 0x0003f1, 0x0003f4, 0x0003f5, 0x0003f7, 0x0003f7, 0x0003f9, 0x0003fa, 0x0003fd, 0x00042f,
                        0x000460, 0x000460, 0x000462, 0x000462, 0x000464, 0x000464, 0x000466, 0x000466, 0x000468, 0x000468, 0x00046a, 0x00046a, 0x00046c, 0x00046c, 0x00046e, 0x00046e, 0x000470,
                        0x000470, 0x000472, 0x000472, 0x000474, 0x000474, 0x000476, 0x000476, 0x000478, 0x000478, 0x00047a, 0x00047a, 0x00047c, 0x00047c, 0x00047e, 0x00047e, 0x000480, 0x000480,
                        0x00048a, 0x00048a, 0x00048c, 0x00048c, 0x00048e, 0x00048e, 0x000490, 0x000490, 0x000492, 0x000492, 0x000494, 0x000494, 0x000496, 0x000496, 0x000498, 0x000498, 0x00049a,
                        0x00049a, 0x00049c, 0x00049c, 0x00049e, 0x00049e, 0x0004a0, 0x0004a0, 0x0004a2, 0x0004a2, 0x0004a4, 0x0004a4, 0x0004a6, 0x0004a6, 0x0004a8, 0x0004a8, 0x0004aa, 0x0004aa,
                        0x0004ac, 0x0004ac, 0x0004ae, 0x0004ae, 0x0004b0, 0x0004b0, 0x0004b2, 0x0004b2, 0x0004b4, 0x0004b4, 0x0004b6, 0x0004b6, 0x0004b8, 0x0004b8, 0x0004ba, 0x0004ba, 0x0004bc,
                        0x0004bc, 0x0004be, 0x0004be, 0x0004c0, 0x0004c1, 0x0004c3, 0x0004c3, 0x0004c5, 0x0004c5, 0x0004c7, 0x0004c7, 0x0004c9, 0x0004c9, 0x0004cb, 0x0004cb, 0x0004cd, 0x0004cd,
                        0x0004d0, 0x0004d0, 0x0004d2, 0x0004d2, 0x0004d4, 0x0004d4, 0x0004d6, 0x0004d6, 0x0004d8, 0x0004d8, 0x0004da, 0x0004da, 0x0004dc, 0x0004dc, 0x0004de, 0x0004de, 0x0004e0,
                        0x0004e0, 0x0004e2, 0x0004e2, 0x0004e4, 0x0004e4, 0x0004e6, 0x0004e6, 0x0004e8, 0x0004e8, 0x0004ea, 0x0004ea, 0x0004ec, 0x0004ec, 0x0004ee, 0x0004ee, 0x0004f0, 0x0004f0,
                        0x0004f2, 0x0004f2, 0x0004f4, 0x0004f4, 0x0004f6, 0x0004f6, 0x0004f8, 0x0004f8, 0x0004fa, 0x0004fa, 0x0004fc, 0x0004fc, 0x0004fe, 0x0004fe, 0x000500, 0x000500, 0x000502,
                        0x000502, 0x000504, 0x000504, 0x000506, 0x000506, 0x000508, 0x000508, 0x00050a, 0x00050a, 0x00050c, 0x00050c, 0x00050e, 0x00050e, 0x000510, 0x000510, 0x000512, 0x000512,
                        0x000514, 0x000514, 0x000516, 0x000516, 0x000518, 0x000518, 0x00051a, 0x00051a, 0x00051c, 0x00051c, 0x00051e, 0x00051e, 0x000520, 0x000520, 0x000522, 0x000522, 0x000524,
                        0x000524, 0x000526, 0x000526, 0x000528, 0x000528, 0x00052a, 0x00052a, 0x00052c, 0x00052c, 0x00052e, 0x00052e, 0x000531, 0x000556, 0x000587, 0x000587, 0x0010a0, 0x0010c5,
                        0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0013f8, 0x0013fd, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001e00, 0x001e00, 0x001e02, 0x001e02, 0x001e04,
                        0x001e04, 0x001e06, 0x001e06, 0x001e08, 0x001e08, 0x001e0a, 0x001e0a, 0x001e0c, 0x001e0c, 0x001e0e, 0x001e0e, 0x001e10, 0x001e10, 0x001e12, 0x001e12, 0x001e14, 0x001e14,
                        0x001e16, 0x001e16, 0x001e18, 0x001e18, 0x001e1a, 0x001e1a, 0x001e1c, 0x001e1c, 0x001e1e, 0x001e1e, 0x001e20, 0x001e20, 0x001e22, 0x001e22, 0x001e24, 0x001e24, 0x001e26,
                        0x001e26, 0x001e28, 0x001e28, 0x001e2a, 0x001e2a, 0x001e2c, 0x001e2c, 0x001e2e, 0x001e2e, 0x001e30, 0x001e30, 0x001e32, 0x001e32, 0x001e34, 0x001e34, 0x001e36, 0x001e36,
                        0x001e38, 0x001e38, 0x001e3a, 0x001e3a, 0x001e3c, 0x001e3c, 0x001e3e, 0x001e3e, 0x001e40, 0x001e40, 0x001e42, 0x001e42, 0x001e44, 0x001e44, 0x001e46, 0x001e46, 0x001e48,
                        0x001e48, 0x001e4a, 0x001e4a, 0x001e4c, 0x001e4c, 0x001e4e, 0x001e4e, 0x001e50, 0x001e50, 0x001e52, 0x001e52, 0x001e54, 0x001e54, 0x001e56, 0x001e56, 0x001e58, 0x001e58,
                        0x001e5a, 0x001e5a, 0x001e5c, 0x001e5c, 0x001e5e, 0x001e5e, 0x001e60, 0x001e60, 0x001e62, 0x001e62, 0x001e64, 0x001e64, 0x001e66, 0x001e66, 0x001e68, 0x001e68, 0x001e6a,
                        0x001e6a, 0x001e6c, 0x001e6c, 0x001e6e, 0x001e6e, 0x001e70, 0x001e70, 0x001e72, 0x001e72, 0x001e74, 0x001e74, 0x001e76, 0x001e76, 0x001e78, 0x001e78, 0x001e7a, 0x001e7a,
                        0x001e7c, 0x001e7c, 0x001e7e, 0x001e7e, 0x001e80, 0x001e80, 0x001e82, 0x001e82, 0x001e84, 0x001e84, 0x001e86, 0x001e86, 0x001e88, 0x001e88, 0x001e8a, 0x001e8a, 0x001e8c,
                        0x001e8c, 0x001e8e, 0x001e8e, 0x001e90, 0x001e90, 0x001e92, 0x001e92, 0x001e94, 0x001e94, 0x001e9a, 0x001e9b, 0x001e9e, 0x001e9e, 0x001ea0, 0x001ea0, 0x001ea2, 0x001ea2,
                        0x001ea4, 0x001ea4, 0x001ea6, 0x001ea6, 0x001ea8, 0x001ea8, 0x001eaa, 0x001eaa, 0x001eac, 0x001eac, 0x001eae, 0x001eae, 0x001eb0, 0x001eb0, 0x001eb2, 0x001eb2, 0x001eb4,
                        0x001eb4, 0x001eb6, 0x001eb6, 0x001eb8, 0x001eb8, 0x001eba, 0x001eba, 0x001ebc, 0x001ebc, 0x001ebe, 0x001ebe, 0x001ec0, 0x001ec0, 0x001ec2, 0x001ec2, 0x001ec4, 0x001ec4,
                        0x001ec6, 0x001ec6, 0x001ec8, 0x001ec8, 0x001eca, 0x001eca, 0x001ecc, 0x001ecc, 0x001ece, 0x001ece, 0x001ed0, 0x001ed0, 0x001ed2, 0x001ed2, 0x001ed4, 0x001ed4, 0x001ed6,
                        0x001ed6, 0x001ed8, 0x001ed8, 0x001eda, 0x001eda, 0x001edc, 0x001edc, 0x001ede, 0x001ede, 0x001ee0, 0x001ee0, 0x001ee2, 0x001ee2, 0x001ee4, 0x001ee4, 0x001ee6, 0x001ee6,
                        0x001ee8, 0x001ee8, 0x001eea, 0x001eea, 0x001eec, 0x001eec, 0x001eee, 0x001eee, 0x001ef0, 0x001ef0, 0x001ef2, 0x001ef2, 0x001ef4, 0x001ef4, 0x001ef6, 0x001ef6, 0x001ef8,
                        0x001ef8, 0x001efa, 0x001efa, 0x001efc, 0x001efc, 0x001efe, 0x001efe, 0x001f08, 0x001f0f, 0x001f18, 0x001f1d, 0x001f28, 0x001f2f, 0x001f38, 0x001f3f, 0x001f48, 0x001f4d,
                        0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f5f, 0x001f68, 0x001f6f, 0x001f80, 0x001faf, 0x001fb2, 0x001fb4, 0x001fb7, 0x001fbc, 0x001fc2,
                        0x001fc4, 0x001fc7, 0x001fcc, 0x001fd8, 0x001fdb, 0x001fe8, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff7, 0x001ffc, 0x002126, 0x002126, 0x00212a, 0x00212b, 0x002132, 0x002132,
                        0x002160, 0x00216f, 0x002183, 0x002183, 0x0024b6, 0x0024cf, 0x002c00, 0x002c2f, 0x002c60, 0x002c60, 0x002c62, 0x002c64, 0x002c67, 0x002c67, 0x002c69, 0x002c69, 0x002c6b,
                        0x002c6b, 0x002c6d, 0x002c70, 0x002c72, 0x002c72, 0x002c75, 0x002c75, 0x002c7e, 0x002c80, 0x002c82, 0x002c82, 0x002c84, 0x002c84, 0x002c86, 0x002c86, 0x002c88, 0x002c88,
                        0x002c8a, 0x002c8a, 0x002c8c, 0x002c8c, 0x002c8e, 0x002c8e, 0x002c90, 0x002c90, 0x002c92, 0x002c92, 0x002c94, 0x002c94, 0x002c96, 0x002c96, 0x002c98, 0x002c98, 0x002c9a,
                        0x002c9a, 0x002c9c, 0x002c9c, 0x002c9e, 0x002c9e, 0x002ca0, 0x002ca0, 0x002ca2, 0x002ca2, 0x002ca4, 0x002ca4, 0x002ca6, 0x002ca6, 0x002ca8, 0x002ca8, 0x002caa, 0x002caa,
                        0x002cac, 0x002cac, 0x002cae, 0x002cae, 0x002cb0, 0x002cb0, 0x002cb2, 0x002cb2, 0x002cb4, 0x002cb4, 0x002cb6, 0x002cb6, 0x002cb8, 0x002cb8, 0x002cba, 0x002cba, 0x002cbc,
                        0x002cbc, 0x002cbe, 0x002cbe, 0x002cc0, 0x002cc0, 0x002cc2, 0x002cc2, 0x002cc4, 0x002cc4, 0x002cc6, 0x002cc6, 0x002cc8, 0x002cc8, 0x002cca, 0x002cca, 0x002ccc, 0x002ccc,
                        0x002cce, 0x002cce, 0x002cd0, 0x002cd0, 0x002cd2, 0x002cd2, 0x002cd4, 0x002cd4, 0x002cd6, 0x002cd6, 0x002cd8, 0x002cd8, 0x002cda, 0x002cda, 0x002cdc, 0x002cdc, 0x002cde,
                        0x002cde, 0x002ce0, 0x002ce0, 0x002ce2, 0x002ce2, 0x002ceb, 0x002ceb, 0x002ced, 0x002ced, 0x002cf2, 0x002cf2, 0x00a640, 0x00a640, 0x00a642, 0x00a642, 0x00a644, 0x00a644,
                        0x00a646, 0x00a646, 0x00a648, 0x00a648, 0x00a64a, 0x00a64a, 0x00a64c, 0x00a64c, 0x00a64e, 0x00a64e, 0x00a650, 0x00a650, 0x00a652, 0x00a652, 0x00a654, 0x00a654, 0x00a656,
                        0x00a656, 0x00a658, 0x00a658, 0x00a65a, 0x00a65a, 0x00a65c, 0x00a65c, 0x00a65e, 0x00a65e, 0x00a660, 0x00a660, 0x00a662, 0x00a662, 0x00a664, 0x00a664, 0x00a666, 0x00a666,
                        0x00a668, 0x00a668, 0x00a66a, 0x00a66a, 0x00a66c, 0x00a66c, 0x00a680, 0x00a680, 0x00a682, 0x00a682, 0x00a684, 0x00a684, 0x00a686, 0x00a686, 0x00a688, 0x00a688, 0x00a68a,
                        0x00a68a, 0x00a68c, 0x00a68c, 0x00a68e, 0x00a68e, 0x00a690, 0x00a690, 0x00a692, 0x00a692, 0x00a694, 0x00a694, 0x00a696, 0x00a696, 0x00a698, 0x00a698, 0x00a69a, 0x00a69a,
                        0x00a722, 0x00a722, 0x00a724, 0x00a724, 0x00a726, 0x00a726, 0x00a728, 0x00a728, 0x00a72a, 0x00a72a, 0x00a72c, 0x00a72c, 0x00a72e, 0x00a72e, 0x00a732, 0x00a732, 0x00a734,
                        0x00a734, 0x00a736, 0x00a736, 0x00a738, 0x00a738, 0x00a73a, 0x00a73a, 0x00a73c, 0x00a73c, 0x00a73e, 0x00a73e, 0x00a740, 0x00a740, 0x00a742, 0x00a742, 0x00a744, 0x00a744,
                        0x00a746, 0x00a746, 0x00a748, 0x00a748, 0x00a74a, 0x00a74a, 0x00a74c, 0x00a74c, 0x00a74e, 0x00a74e, 0x00a750, 0x00a750, 0x00a752, 0x00a752, 0x00a754, 0x00a754, 0x00a756,
                        0x00a756, 0x00a758, 0x00a758, 0x00a75a, 0x00a75a, 0x00a75c, 0x00a75c, 0x00a75e, 0x00a75e, 0x00a760, 0x00a760, 0x00a762, 0x00a762, 0x00a764, 0x00a764, 0x00a766, 0x00a766,
                        0x00a768, 0x00a768, 0x00a76a, 0x00a76a, 0x00a76c, 0x00a76c, 0x00a76e, 0x00a76e, 0x00a779, 0x00a779, 0x00a77b, 0x00a77b, 0x00a77d, 0x00a77e, 0x00a780, 0x00a780, 0x00a782,
                        0x00a782, 0x00a784, 0x00a784, 0x00a786, 0x00a786, 0x00a78b, 0x00a78b, 0x00a78d, 0x00a78d, 0x00a790, 0x00a790, 0x00a792, 0x00a792, 0x00a796, 0x00a796, 0x00a798, 0x00a798,
                        0x00a79a, 0x00a79a, 0x00a79c, 0x00a79c, 0x00a79e, 0x00a79e, 0x00a7a0, 0x00a7a0, 0x00a7a2, 0x00a7a2, 0x00a7a4, 0x00a7a4, 0x00a7a6, 0x00a7a6, 0x00a7a8, 0x00a7a8, 0x00a7aa,
                        0x00a7ae, 0x00a7b0, 0x00a7b4, 0x00a7b6, 0x00a7b6, 0x00a7b8, 0x00a7b8, 0x00a7ba, 0x00a7ba, 0x00a7bc, 0x00a7bc, 0x00a7be, 0x00a7be, 0x00a7c0, 0x00a7c0, 0x00a7c2, 0x00a7c2,
                        0x00a7c4, 0x00a7c7, 0x00a7c9, 0x00a7c9, 0x00a7d0, 0x00a7d0, 0x00a7d6, 0x00a7d6, 0x00a7d8, 0x00a7d8, 0x00a7f5, 0x00a7f5, 0x00ab70, 0x00abbf, 0x00fb00, 0x00fb06, 0x00fb13,
                        0x00fb17, 0x00ff21, 0x00ff3a, 0x010400, 0x010427, 0x0104b0, 0x0104d3, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010c80, 0x010cb2,
                        0x0118a0, 0x0118bf, 0x016e40, 0x016e5f, 0x01e900, 0x01e921));
    }

    private static void populateCWCM() {
        SET_ENCODINGS.put("CWCM", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000b5, 0x0000b5, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6, 0x0000f8, 0x000137, 0x000139, 0x00018c,
                        0x00018e, 0x00019a, 0x00019c, 0x0001a9, 0x0001ac, 0x0001b9, 0x0001bc, 0x0001bd, 0x0001bf, 0x0001bf, 0x0001c4, 0x000220, 0x000222, 0x000233, 0x00023a, 0x000254, 0x000256,
                        0x000257, 0x000259, 0x000259, 0x00025b, 0x00025c, 0x000260, 0x000261, 0x000263, 0x000263, 0x000265, 0x000266, 0x000268, 0x00026c, 0x00026f, 0x00026f, 0x000271, 0x000272,
                        0x000275, 0x000275, 0x00027d, 0x00027d, 0x000280, 0x000280, 0x000282, 0x000283, 0x000287, 0x00028c, 0x000292, 0x000292, 0x00029d, 0x00029e, 0x000345, 0x000345, 0x000370,
                        0x000373, 0x000376, 0x000377, 0x00037b, 0x00037d, 0x00037f, 0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003d1,
                        0x0003d5, 0x0003f5, 0x0003f7, 0x0003fb, 0x0003fd, 0x000481, 0x00048a, 0x00052f, 0x000531, 0x000556, 0x000561, 0x000587, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd,
                        0x0010cd, 0x0010d0, 0x0010fa, 0x0010fd, 0x0010ff, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001d79, 0x001d79,
                        0x001d7d, 0x001d7d, 0x001d8e, 0x001d8e, 0x001e00, 0x001e9b, 0x001e9e, 0x001e9e, 0x001ea0, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50,
                        0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc, 0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4,
                        0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x002126, 0x002126, 0x00212a, 0x00212b, 0x002132,
                        0x002132, 0x00214e, 0x00214e, 0x002160, 0x00217f, 0x002183, 0x002184, 0x0024b6, 0x0024e9, 0x002c00, 0x002c70, 0x002c72, 0x002c73, 0x002c75, 0x002c76, 0x002c7e, 0x002ce3,
                        0x002ceb, 0x002cee, 0x002cf2, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x00a640, 0x00a66d, 0x00a680, 0x00a69b, 0x00a722, 0x00a72f, 0x00a732,
                        0x00a76f, 0x00a779, 0x00a787, 0x00a78b, 0x00a78d, 0x00a790, 0x00a794, 0x00a796, 0x00a7ae, 0x00a7b0, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d6, 0x00a7d9, 0x00a7f5, 0x00a7f6,
                        0x00ab53, 0x00ab53, 0x00ab70, 0x00abbf, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00ff21, 0x00ff3a, 0x00ff41, 0x00ff5a, 0x010400, 0x01044f, 0x0104b0, 0x0104d3, 0x0104d8,
                        0x0104fb, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc,
                        0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x0118a0, 0x0118df, 0x016e40, 0x016e7f, 0x01e900, 0x01e943));
    }

    private static void populateCWKCF() {
        SET_ENCODINGS.put("CWKCF", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x0000a0, 0x0000a0, 0x0000a8, 0x0000a8, 0x0000aa, 0x0000aa, 0x0000ad, 0x0000ad, 0x0000af, 0x0000af, 0x0000b2,
                        0x0000b5, 0x0000b8, 0x0000ba, 0x0000bc, 0x0000be, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000df, 0x000100, 0x000100, 0x000102, 0x000102, 0x000104, 0x000104, 0x000106, 0x000106,
                        0x000108, 0x000108, 0x00010a, 0x00010a, 0x00010c, 0x00010c, 0x00010e, 0x00010e, 0x000110, 0x000110, 0x000112, 0x000112, 0x000114, 0x000114, 0x000116, 0x000116, 0x000118,
                        0x000118, 0x00011a, 0x00011a, 0x00011c, 0x00011c, 0x00011e, 0x00011e, 0x000120, 0x000120, 0x000122, 0x000122, 0x000124, 0x000124, 0x000126, 0x000126, 0x000128, 0x000128,
                        0x00012a, 0x00012a, 0x00012c, 0x00012c, 0x00012e, 0x00012e, 0x000130, 0x000130, 0x000132, 0x000134, 0x000136, 0x000136, 0x000139, 0x000139, 0x00013b, 0x00013b, 0x00013d,
                        0x00013d, 0x00013f, 0x000141, 0x000143, 0x000143, 0x000145, 0x000145, 0x000147, 0x000147, 0x000149, 0x00014a, 0x00014c, 0x00014c, 0x00014e, 0x00014e, 0x000150, 0x000150,
                        0x000152, 0x000152, 0x000154, 0x000154, 0x000156, 0x000156, 0x000158, 0x000158, 0x00015a, 0x00015a, 0x00015c, 0x00015c, 0x00015e, 0x00015e, 0x000160, 0x000160, 0x000162,
                        0x000162, 0x000164, 0x000164, 0x000166, 0x000166, 0x000168, 0x000168, 0x00016a, 0x00016a, 0x00016c, 0x00016c, 0x00016e, 0x00016e, 0x000170, 0x000170, 0x000172, 0x000172,
                        0x000174, 0x000174, 0x000176, 0x000176, 0x000178, 0x000179, 0x00017b, 0x00017b, 0x00017d, 0x00017d, 0x00017f, 0x00017f, 0x000181, 0x000182, 0x000184, 0x000184, 0x000186,
                        0x000187, 0x000189, 0x00018b, 0x00018e, 0x000191, 0x000193, 0x000194, 0x000196, 0x000198, 0x00019c, 0x00019d, 0x00019f, 0x0001a0, 0x0001a2, 0x0001a2, 0x0001a4, 0x0001a4,
                        0x0001a6, 0x0001a7, 0x0001a9, 0x0001a9, 0x0001ac, 0x0001ac, 0x0001ae, 0x0001af, 0x0001b1, 0x0001b3, 0x0001b5, 0x0001b5, 0x0001b7, 0x0001b8, 0x0001bc, 0x0001bc, 0x0001c4,
                        0x0001cd, 0x0001cf, 0x0001cf, 0x0001d1, 0x0001d1, 0x0001d3, 0x0001d3, 0x0001d5, 0x0001d5, 0x0001d7, 0x0001d7, 0x0001d9, 0x0001d9, 0x0001db, 0x0001db, 0x0001de, 0x0001de,
                        0x0001e0, 0x0001e0, 0x0001e2, 0x0001e2, 0x0001e4, 0x0001e4, 0x0001e6, 0x0001e6, 0x0001e8, 0x0001e8, 0x0001ea, 0x0001ea, 0x0001ec, 0x0001ec, 0x0001ee, 0x0001ee, 0x0001f1,
                        0x0001f4, 0x0001f6, 0x0001f8, 0x0001fa, 0x0001fa, 0x0001fc, 0x0001fc, 0x0001fe, 0x0001fe, 0x000200, 0x000200, 0x000202, 0x000202, 0x000204, 0x000204, 0x000206, 0x000206,
                        0x000208, 0x000208, 0x00020a, 0x00020a, 0x00020c, 0x00020c, 0x00020e, 0x00020e, 0x000210, 0x000210, 0x000212, 0x000212, 0x000214, 0x000214, 0x000216, 0x000216, 0x000218,
                        0x000218, 0x00021a, 0x00021a, 0x00021c, 0x00021c, 0x00021e, 0x00021e, 0x000220, 0x000220, 0x000222, 0x000222, 0x000224, 0x000224, 0x000226, 0x000226, 0x000228, 0x000228,
                        0x00022a, 0x00022a, 0x00022c, 0x00022c, 0x00022e, 0x00022e, 0x000230, 0x000230, 0x000232, 0x000232, 0x00023a, 0x00023b, 0x00023d, 0x00023e, 0x000241, 0x000241, 0x000243,
                        0x000246, 0x000248, 0x000248, 0x00024a, 0x00024a, 0x00024c, 0x00024c, 0x00024e, 0x00024e, 0x0002b0, 0x0002b8, 0x0002d8, 0x0002dd, 0x0002e0, 0x0002e4, 0x000340, 0x000341,
                        0x000343, 0x000345, 0x00034f, 0x00034f, 0x000370, 0x000370, 0x000372, 0x000372, 0x000374, 0x000374, 0x000376, 0x000376, 0x00037a, 0x00037a, 0x00037e, 0x00037f, 0x000384,
                        0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x00038f, 0x000391, 0x0003a1, 0x0003a3, 0x0003ab, 0x0003c2, 0x0003c2, 0x0003cf, 0x0003d6, 0x0003d8, 0x0003d8, 0x0003da, 0x0003da,
                        0x0003dc, 0x0003dc, 0x0003de, 0x0003de, 0x0003e0, 0x0003e0, 0x0003e2, 0x0003e2, 0x0003e4, 0x0003e4, 0x0003e6, 0x0003e6, 0x0003e8, 0x0003e8, 0x0003ea, 0x0003ea, 0x0003ec,
                        0x0003ec, 0x0003ee, 0x0003ee, 0x0003f0, 0x0003f2, 0x0003f4, 0x0003f5, 0x0003f7, 0x0003f7, 0x0003f9, 0x0003fa, 0x0003fd, 0x00042f, 0x000460, 0x000460, 0x000462, 0x000462,
                        0x000464, 0x000464, 0x000466, 0x000466, 0x000468, 0x000468, 0x00046a, 0x00046a, 0x00046c, 0x00046c, 0x00046e, 0x00046e, 0x000470, 0x000470, 0x000472, 0x000472, 0x000474,
                        0x000474, 0x000476, 0x000476, 0x000478, 0x000478, 0x00047a, 0x00047a, 0x00047c, 0x00047c, 0x00047e, 0x00047e, 0x000480, 0x000480, 0x00048a, 0x00048a, 0x00048c, 0x00048c,
                        0x00048e, 0x00048e, 0x000490, 0x000490, 0x000492, 0x000492, 0x000494, 0x000494, 0x000496, 0x000496, 0x000498, 0x000498, 0x00049a, 0x00049a, 0x00049c, 0x00049c, 0x00049e,
                        0x00049e, 0x0004a0, 0x0004a0, 0x0004a2, 0x0004a2, 0x0004a4, 0x0004a4, 0x0004a6, 0x0004a6, 0x0004a8, 0x0004a8, 0x0004aa, 0x0004aa, 0x0004ac, 0x0004ac, 0x0004ae, 0x0004ae,
                        0x0004b0, 0x0004b0, 0x0004b2, 0x0004b2, 0x0004b4, 0x0004b4, 0x0004b6, 0x0004b6, 0x0004b8, 0x0004b8, 0x0004ba, 0x0004ba, 0x0004bc, 0x0004bc, 0x0004be, 0x0004be, 0x0004c0,
                        0x0004c1, 0x0004c3, 0x0004c3, 0x0004c5, 0x0004c5, 0x0004c7, 0x0004c7, 0x0004c9, 0x0004c9, 0x0004cb, 0x0004cb, 0x0004cd, 0x0004cd, 0x0004d0, 0x0004d0, 0x0004d2, 0x0004d2,
                        0x0004d4, 0x0004d4, 0x0004d6, 0x0004d6, 0x0004d8, 0x0004d8, 0x0004da, 0x0004da, 0x0004dc, 0x0004dc, 0x0004de, 0x0004de, 0x0004e0, 0x0004e0, 0x0004e2, 0x0004e2, 0x0004e4,
                        0x0004e4, 0x0004e6, 0x0004e6, 0x0004e8, 0x0004e8, 0x0004ea, 0x0004ea, 0x0004ec, 0x0004ec, 0x0004ee, 0x0004ee, 0x0004f0, 0x0004f0, 0x0004f2, 0x0004f2, 0x0004f4, 0x0004f4,
                        0x0004f6, 0x0004f6, 0x0004f8, 0x0004f8, 0x0004fa, 0x0004fa, 0x0004fc, 0x0004fc, 0x0004fe, 0x0004fe, 0x000500, 0x000500, 0x000502, 0x000502, 0x000504, 0x000504, 0x000506,
                        0x000506, 0x000508, 0x000508, 0x00050a, 0x00050a, 0x00050c, 0x00050c, 0x00050e, 0x00050e, 0x000510, 0x000510, 0x000512, 0x000512, 0x000514, 0x000514, 0x000516, 0x000516,
                        0x000518, 0x000518, 0x00051a, 0x00051a, 0x00051c, 0x00051c, 0x00051e, 0x00051e, 0x000520, 0x000520, 0x000522, 0x000522, 0x000524, 0x000524, 0x000526, 0x000526, 0x000528,
                        0x000528, 0x00052a, 0x00052a, 0x00052c, 0x00052c, 0x00052e, 0x00052e, 0x000531, 0x000556, 0x000587, 0x000587, 0x00061c, 0x00061c, 0x000675, 0x000678, 0x000958, 0x00095f,
                        0x0009dc, 0x0009dd, 0x0009df, 0x0009df, 0x000a33, 0x000a33, 0x000a36, 0x000a36, 0x000a59, 0x000a5b, 0x000a5e, 0x000a5e, 0x000b5c, 0x000b5d, 0x000e33, 0x000e33, 0x000eb3,
                        0x000eb3, 0x000edc, 0x000edd, 0x000f0c, 0x000f0c, 0x000f43, 0x000f43, 0x000f4d, 0x000f4d, 0x000f52, 0x000f52, 0x000f57, 0x000f57, 0x000f5c, 0x000f5c, 0x000f69, 0x000f69,
                        0x000f73, 0x000f73, 0x000f75, 0x000f79, 0x000f81, 0x000f81, 0x000f93, 0x000f93, 0x000f9d, 0x000f9d, 0x000fa2, 0x000fa2, 0x000fa7, 0x000fa7, 0x000fac, 0x000fac, 0x000fb9,
                        0x000fb9, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010fc, 0x0010fc, 0x00115f, 0x001160, 0x0013f8, 0x0013fd, 0x0017b4, 0x0017b5, 0x00180b, 0x00180f,
                        0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001d2c, 0x001d2e, 0x001d30, 0x001d3a, 0x001d3c, 0x001d4d, 0x001d4f, 0x001d6a, 0x001d78, 0x001d78, 0x001d9b,
                        0x001dbf, 0x001e00, 0x001e00, 0x001e02, 0x001e02, 0x001e04, 0x001e04, 0x001e06, 0x001e06, 0x001e08, 0x001e08, 0x001e0a, 0x001e0a, 0x001e0c, 0x001e0c, 0x001e0e, 0x001e0e,
                        0x001e10, 0x001e10, 0x001e12, 0x001e12, 0x001e14, 0x001e14, 0x001e16, 0x001e16, 0x001e18, 0x001e18, 0x001e1a, 0x001e1a, 0x001e1c, 0x001e1c, 0x001e1e, 0x001e1e, 0x001e20,
                        0x001e20, 0x001e22, 0x001e22, 0x001e24, 0x001e24, 0x001e26, 0x001e26, 0x001e28, 0x001e28, 0x001e2a, 0x001e2a, 0x001e2c, 0x001e2c, 0x001e2e, 0x001e2e, 0x001e30, 0x001e30,
                        0x001e32, 0x001e32, 0x001e34, 0x001e34, 0x001e36, 0x001e36, 0x001e38, 0x001e38, 0x001e3a, 0x001e3a, 0x001e3c, 0x001e3c, 0x001e3e, 0x001e3e, 0x001e40, 0x001e40, 0x001e42,
                        0x001e42, 0x001e44, 0x001e44, 0x001e46, 0x001e46, 0x001e48, 0x001e48, 0x001e4a, 0x001e4a, 0x001e4c, 0x001e4c, 0x001e4e, 0x001e4e, 0x001e50, 0x001e50, 0x001e52, 0x001e52,
                        0x001e54, 0x001e54, 0x001e56, 0x001e56, 0x001e58, 0x001e58, 0x001e5a, 0x001e5a, 0x001e5c, 0x001e5c, 0x001e5e, 0x001e5e, 0x001e60, 0x001e60, 0x001e62, 0x001e62, 0x001e64,
                        0x001e64, 0x001e66, 0x001e66, 0x001e68, 0x001e68, 0x001e6a, 0x001e6a, 0x001e6c, 0x001e6c, 0x001e6e, 0x001e6e, 0x001e70, 0x001e70, 0x001e72, 0x001e72, 0x001e74, 0x001e74,
                        0x001e76, 0x001e76, 0x001e78, 0x001e78, 0x001e7a, 0x001e7a, 0x001e7c, 0x001e7c, 0x001e7e, 0x001e7e, 0x001e80, 0x001e80, 0x001e82, 0x001e82, 0x001e84, 0x001e84, 0x001e86,
                        0x001e86, 0x001e88, 0x001e88, 0x001e8a, 0x001e8a, 0x001e8c, 0x001e8c, 0x001e8e, 0x001e8e, 0x001e90, 0x001e90, 0x001e92, 0x001e92, 0x001e94, 0x001e94, 0x001e9a, 0x001e9b,
                        0x001e9e, 0x001e9e, 0x001ea0, 0x001ea0, 0x001ea2, 0x001ea2, 0x001ea4, 0x001ea4, 0x001ea6, 0x001ea6, 0x001ea8, 0x001ea8, 0x001eaa, 0x001eaa, 0x001eac, 0x001eac, 0x001eae,
                        0x001eae, 0x001eb0, 0x001eb0, 0x001eb2, 0x001eb2, 0x001eb4, 0x001eb4, 0x001eb6, 0x001eb6, 0x001eb8, 0x001eb8, 0x001eba, 0x001eba, 0x001ebc, 0x001ebc, 0x001ebe, 0x001ebe,
                        0x001ec0, 0x001ec0, 0x001ec2, 0x001ec2, 0x001ec4, 0x001ec4, 0x001ec6, 0x001ec6, 0x001ec8, 0x001ec8, 0x001eca, 0x001eca, 0x001ecc, 0x001ecc, 0x001ece, 0x001ece, 0x001ed0,
                        0x001ed0, 0x001ed2, 0x001ed2, 0x001ed4, 0x001ed4, 0x001ed6, 0x001ed6, 0x001ed8, 0x001ed8, 0x001eda, 0x001eda, 0x001edc, 0x001edc, 0x001ede, 0x001ede, 0x001ee0, 0x001ee0,
                        0x001ee2, 0x001ee2, 0x001ee4, 0x001ee4, 0x001ee6, 0x001ee6, 0x001ee8, 0x001ee8, 0x001eea, 0x001eea, 0x001eec, 0x001eec, 0x001eee, 0x001eee, 0x001ef0, 0x001ef0, 0x001ef2,
                        0x001ef2, 0x001ef4, 0x001ef4, 0x001ef6, 0x001ef6, 0x001ef8, 0x001ef8, 0x001efa, 0x001efa, 0x001efc, 0x001efc, 0x001efe, 0x001efe, 0x001f08, 0x001f0f, 0x001f18, 0x001f1d,
                        0x001f28, 0x001f2f, 0x001f38, 0x001f3f, 0x001f48, 0x001f4d, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f5f, 0x001f68, 0x001f6f, 0x001f71,
                        0x001f71, 0x001f73, 0x001f73, 0x001f75, 0x001f75, 0x001f77, 0x001f77, 0x001f79, 0x001f79, 0x001f7b, 0x001f7b, 0x001f7d, 0x001f7d, 0x001f80, 0x001faf, 0x001fb2, 0x001fb4,
                        0x001fb7, 0x001fc4, 0x001fc7, 0x001fcf, 0x001fd3, 0x001fd3, 0x001fd8, 0x001fdb, 0x001fdd, 0x001fdf, 0x001fe3, 0x001fe3, 0x001fe8, 0x001fef, 0x001ff2, 0x001ff4, 0x001ff7,
                        0x001ffe, 0x002000, 0x00200f, 0x002011, 0x002011, 0x002017, 0x002017, 0x002024, 0x002026, 0x00202a, 0x00202f, 0x002033, 0x002034, 0x002036, 0x002037, 0x00203c, 0x00203c,
                        0x00203e, 0x00203e, 0x002047, 0x002049, 0x002057, 0x002057, 0x00205f, 0x002071, 0x002074, 0x00208e, 0x002090, 0x00209c, 0x0020a8, 0x0020a8, 0x002100, 0x002103, 0x002105,
                        0x002107, 0x002109, 0x002113, 0x002115, 0x002116, 0x002119, 0x00211d, 0x002120, 0x002122, 0x002124, 0x002124, 0x002126, 0x002126, 0x002128, 0x002128, 0x00212a, 0x00212d,
                        0x00212f, 0x002139, 0x00213b, 0x002140, 0x002145, 0x002149, 0x002150, 0x00217f, 0x002183, 0x002183, 0x002189, 0x002189, 0x00222c, 0x00222d, 0x00222f, 0x002230, 0x002329,
                        0x00232a, 0x002460, 0x0024ea, 0x002a0c, 0x002a0c, 0x002a74, 0x002a76, 0x002adc, 0x002adc, 0x002c00, 0x002c2f, 0x002c60, 0x002c60, 0x002c62, 0x002c64, 0x002c67, 0x002c67,
                        0x002c69, 0x002c69, 0x002c6b, 0x002c6b, 0x002c6d, 0x002c70, 0x002c72, 0x002c72, 0x002c75, 0x002c75, 0x002c7c, 0x002c80, 0x002c82, 0x002c82, 0x002c84, 0x002c84, 0x002c86,
                        0x002c86, 0x002c88, 0x002c88, 0x002c8a, 0x002c8a, 0x002c8c, 0x002c8c, 0x002c8e, 0x002c8e, 0x002c90, 0x002c90, 0x002c92, 0x002c92, 0x002c94, 0x002c94, 0x002c96, 0x002c96,
                        0x002c98, 0x002c98, 0x002c9a, 0x002c9a, 0x002c9c, 0x002c9c, 0x002c9e, 0x002c9e, 0x002ca0, 0x002ca0, 0x002ca2, 0x002ca2, 0x002ca4, 0x002ca4, 0x002ca6, 0x002ca6, 0x002ca8,
                        0x002ca8, 0x002caa, 0x002caa, 0x002cac, 0x002cac, 0x002cae, 0x002cae, 0x002cb0, 0x002cb0, 0x002cb2, 0x002cb2, 0x002cb4, 0x002cb4, 0x002cb6, 0x002cb6, 0x002cb8, 0x002cb8,
                        0x002cba, 0x002cba, 0x002cbc, 0x002cbc, 0x002cbe, 0x002cbe, 0x002cc0, 0x002cc0, 0x002cc2, 0x002cc2, 0x002cc4, 0x002cc4, 0x002cc6, 0x002cc6, 0x002cc8, 0x002cc8, 0x002cca,
                        0x002cca, 0x002ccc, 0x002ccc, 0x002cce, 0x002cce, 0x002cd0, 0x002cd0, 0x002cd2, 0x002cd2, 0x002cd4, 0x002cd4, 0x002cd6, 0x002cd6, 0x002cd8, 0x002cd8, 0x002cda, 0x002cda,
                        0x002cdc, 0x002cdc, 0x002cde, 0x002cde, 0x002ce0, 0x002ce0, 0x002ce2, 0x002ce2, 0x002ceb, 0x002ceb, 0x002ced, 0x002ced, 0x002cf2, 0x002cf2, 0x002d6f, 0x002d6f, 0x002e9f,
                        0x002e9f, 0x002ef3, 0x002ef3, 0x002f00, 0x002fd5, 0x003000, 0x003000, 0x003036, 0x003036, 0x003038, 0x00303a, 0x00309b, 0x00309c, 0x00309f, 0x00309f, 0x0030ff, 0x0030ff,
                        0x003131, 0x00318e, 0x003192, 0x00319f, 0x003200, 0x00321e, 0x003220, 0x003247, 0x003250, 0x00327e, 0x003280, 0x0033ff, 0x00a640, 0x00a640, 0x00a642, 0x00a642, 0x00a644,
                        0x00a644, 0x00a646, 0x00a646, 0x00a648, 0x00a648, 0x00a64a, 0x00a64a, 0x00a64c, 0x00a64c, 0x00a64e, 0x00a64e, 0x00a650, 0x00a650, 0x00a652, 0x00a652, 0x00a654, 0x00a654,
                        0x00a656, 0x00a656, 0x00a658, 0x00a658, 0x00a65a, 0x00a65a, 0x00a65c, 0x00a65c, 0x00a65e, 0x00a65e, 0x00a660, 0x00a660, 0x00a662, 0x00a662, 0x00a664, 0x00a664, 0x00a666,
                        0x00a666, 0x00a668, 0x00a668, 0x00a66a, 0x00a66a, 0x00a66c, 0x00a66c, 0x00a680, 0x00a680, 0x00a682, 0x00a682, 0x00a684, 0x00a684, 0x00a686, 0x00a686, 0x00a688, 0x00a688,
                        0x00a68a, 0x00a68a, 0x00a68c, 0x00a68c, 0x00a68e, 0x00a68e, 0x00a690, 0x00a690, 0x00a692, 0x00a692, 0x00a694, 0x00a694, 0x00a696, 0x00a696, 0x00a698, 0x00a698, 0x00a69a,
                        0x00a69a, 0x00a69c, 0x00a69d, 0x00a722, 0x00a722, 0x00a724, 0x00a724, 0x00a726, 0x00a726, 0x00a728, 0x00a728, 0x00a72a, 0x00a72a, 0x00a72c, 0x00a72c, 0x00a72e, 0x00a72e,
                        0x00a732, 0x00a732, 0x00a734, 0x00a734, 0x00a736, 0x00a736, 0x00a738, 0x00a738, 0x00a73a, 0x00a73a, 0x00a73c, 0x00a73c, 0x00a73e, 0x00a73e, 0x00a740, 0x00a740, 0x00a742,
                        0x00a742, 0x00a744, 0x00a744, 0x00a746, 0x00a746, 0x00a748, 0x00a748, 0x00a74a, 0x00a74a, 0x00a74c, 0x00a74c, 0x00a74e, 0x00a74e, 0x00a750, 0x00a750, 0x00a752, 0x00a752,
                        0x00a754, 0x00a754, 0x00a756, 0x00a756, 0x00a758, 0x00a758, 0x00a75a, 0x00a75a, 0x00a75c, 0x00a75c, 0x00a75e, 0x00a75e, 0x00a760, 0x00a760, 0x00a762, 0x00a762, 0x00a764,
                        0x00a764, 0x00a766, 0x00a766, 0x00a768, 0x00a768, 0x00a76a, 0x00a76a, 0x00a76c, 0x00a76c, 0x00a76e, 0x00a76e, 0x00a770, 0x00a770, 0x00a779, 0x00a779, 0x00a77b, 0x00a77b,
                        0x00a77d, 0x00a77e, 0x00a780, 0x00a780, 0x00a782, 0x00a782, 0x00a784, 0x00a784, 0x00a786, 0x00a786, 0x00a78b, 0x00a78b, 0x00a78d, 0x00a78d, 0x00a790, 0x00a790, 0x00a792,
                        0x00a792, 0x00a796, 0x00a796, 0x00a798, 0x00a798, 0x00a79a, 0x00a79a, 0x00a79c, 0x00a79c, 0x00a79e, 0x00a79e, 0x00a7a0, 0x00a7a0, 0x00a7a2, 0x00a7a2, 0x00a7a4, 0x00a7a4,
                        0x00a7a6, 0x00a7a6, 0x00a7a8, 0x00a7a8, 0x00a7aa, 0x00a7ae, 0x00a7b0, 0x00a7b4, 0x00a7b6, 0x00a7b6, 0x00a7b8, 0x00a7b8, 0x00a7ba, 0x00a7ba, 0x00a7bc, 0x00a7bc, 0x00a7be,
                        0x00a7be, 0x00a7c0, 0x00a7c0, 0x00a7c2, 0x00a7c2, 0x00a7c4, 0x00a7c7, 0x00a7c9, 0x00a7c9, 0x00a7d0, 0x00a7d0, 0x00a7d6, 0x00a7d6, 0x00a7d8, 0x00a7d8, 0x00a7f2, 0x00a7f5,
                        0x00a7f8, 0x00a7f9, 0x00ab5c, 0x00ab5f, 0x00ab69, 0x00ab69, 0x00ab70, 0x00abbf, 0x00f900, 0x00fa0d, 0x00fa10, 0x00fa10, 0x00fa12, 0x00fa12, 0x00fa15, 0x00fa1e, 0x00fa20,
                        0x00fa20, 0x00fa22, 0x00fa22, 0x00fa25, 0x00fa26, 0x00fa2a, 0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb1d, 0x00fb1f, 0x00fb36,
                        0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40, 0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fbb1, 0x00fbd3, 0x00fd3d, 0x00fd50, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdf0,
                        0x00fdfc, 0x00fe00, 0x00fe19, 0x00fe30, 0x00fe44, 0x00fe47, 0x00fe52, 0x00fe54, 0x00fe66, 0x00fe68, 0x00fe6b, 0x00fe70, 0x00fe72, 0x00fe74, 0x00fe74, 0x00fe76, 0x00fefc,
                        0x00feff, 0x00feff, 0x00ff01, 0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x00ffe0, 0x00ffe6, 0x00ffe8, 0x00ffee, 0x00fff0,
                        0x00fff8, 0x010400, 0x010427, 0x0104b0, 0x0104d3, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010781, 0x010785, 0x010787, 0x0107b0,
                        0x0107b2, 0x0107ba, 0x010c80, 0x010cb2, 0x0118a0, 0x0118bf, 0x016e40, 0x016e5f, 0x01bca0, 0x01bca3, 0x01d15e, 0x01d164, 0x01d173, 0x01d17a, 0x01d1bb, 0x01d1c0, 0x01d400,
                        0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3,
                        0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a,
                        0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d7cb, 0x01d7ce, 0x01d7ff, 0x01e030, 0x01e06d, 0x01e900, 0x01e921, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22,
                        0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49,
                        0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d,
                        0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80,
                        0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x01f100, 0x01f10a, 0x01f110, 0x01f12e, 0x01f130, 0x01f14f, 0x01f16a, 0x01f16c,
                        0x01f190, 0x01f190, 0x01f200, 0x01f202, 0x01f210, 0x01f23b, 0x01f240, 0x01f248, 0x01f250, 0x01f251, 0x01fbf0, 0x01fbf9, 0x02f800, 0x02fa1d, 0x0e0000, 0x0e0fff));
    }

    private static void populateCWL() {
        SET_ENCODINGS.put("CWL", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000de, 0x000100, 0x000100, 0x000102, 0x000102, 0x000104, 0x000104, 0x000106, 0x000106,
                        0x000108, 0x000108, 0x00010a, 0x00010a, 0x00010c, 0x00010c, 0x00010e, 0x00010e, 0x000110, 0x000110, 0x000112, 0x000112, 0x000114, 0x000114, 0x000116, 0x000116, 0x000118,
                        0x000118, 0x00011a, 0x00011a, 0x00011c, 0x00011c, 0x00011e, 0x00011e, 0x000120, 0x000120, 0x000122, 0x000122, 0x000124, 0x000124, 0x000126, 0x000126, 0x000128, 0x000128,
                        0x00012a, 0x00012a, 0x00012c, 0x00012c, 0x00012e, 0x00012e, 0x000130, 0x000130, 0x000132, 0x000132, 0x000134, 0x000134, 0x000136, 0x000136, 0x000139, 0x000139, 0x00013b,
                        0x00013b, 0x00013d, 0x00013d, 0x00013f, 0x00013f, 0x000141, 0x000141, 0x000143, 0x000143, 0x000145, 0x000145, 0x000147, 0x000147, 0x00014a, 0x00014a, 0x00014c, 0x00014c,
                        0x00014e, 0x00014e, 0x000150, 0x000150, 0x000152, 0x000152, 0x000154, 0x000154, 0x000156, 0x000156, 0x000158, 0x000158, 0x00015a, 0x00015a, 0x00015c, 0x00015c, 0x00015e,
                        0x00015e, 0x000160, 0x000160, 0x000162, 0x000162, 0x000164, 0x000164, 0x000166, 0x000166, 0x000168, 0x000168, 0x00016a, 0x00016a, 0x00016c, 0x00016c, 0x00016e, 0x00016e,
                        0x000170, 0x000170, 0x000172, 0x000172, 0x000174, 0x000174, 0x000176, 0x000176, 0x000178, 0x000179, 0x00017b, 0x00017b, 0x00017d, 0x00017d, 0x000181, 0x000182, 0x000184,
                        0x000184, 0x000186, 0x000187, 0x000189, 0x00018b, 0x00018e, 0x000191, 0x000193, 0x000194, 0x000196, 0x000198, 0x00019c, 0x00019d, 0x00019f, 0x0001a0, 0x0001a2, 0x0001a2,
                        0x0001a4, 0x0001a4, 0x0001a6, 0x0001a7, 0x0001a9, 0x0001a9, 0x0001ac, 0x0001ac, 0x0001ae, 0x0001af, 0x0001b1, 0x0001b3, 0x0001b5, 0x0001b5, 0x0001b7, 0x0001b8, 0x0001bc,
                        0x0001bc, 0x0001c4, 0x0001c5, 0x0001c7, 0x0001c8, 0x0001ca, 0x0001cb, 0x0001cd, 0x0001cd, 0x0001cf, 0x0001cf, 0x0001d1, 0x0001d1, 0x0001d3, 0x0001d3, 0x0001d5, 0x0001d5,
                        0x0001d7, 0x0001d7, 0x0001d9, 0x0001d9, 0x0001db, 0x0001db, 0x0001de, 0x0001de, 0x0001e0, 0x0001e0, 0x0001e2, 0x0001e2, 0x0001e4, 0x0001e4, 0x0001e6, 0x0001e6, 0x0001e8,
                        0x0001e8, 0x0001ea, 0x0001ea, 0x0001ec, 0x0001ec, 0x0001ee, 0x0001ee, 0x0001f1, 0x0001f2, 0x0001f4, 0x0001f4, 0x0001f6, 0x0001f8, 0x0001fa, 0x0001fa, 0x0001fc, 0x0001fc,
                        0x0001fe, 0x0001fe, 0x000200, 0x000200, 0x000202, 0x000202, 0x000204, 0x000204, 0x000206, 0x000206, 0x000208, 0x000208, 0x00020a, 0x00020a, 0x00020c, 0x00020c, 0x00020e,
                        0x00020e, 0x000210, 0x000210, 0x000212, 0x000212, 0x000214, 0x000214, 0x000216, 0x000216, 0x000218, 0x000218, 0x00021a, 0x00021a, 0x00021c, 0x00021c, 0x00021e, 0x00021e,
                        0x000220, 0x000220, 0x000222, 0x000222, 0x000224, 0x000224, 0x000226, 0x000226, 0x000228, 0x000228, 0x00022a, 0x00022a, 0x00022c, 0x00022c, 0x00022e, 0x00022e, 0x000230,
                        0x000230, 0x000232, 0x000232, 0x00023a, 0x00023b, 0x00023d, 0x00023e, 0x000241, 0x000241, 0x000243, 0x000246, 0x000248, 0x000248, 0x00024a, 0x00024a, 0x00024c, 0x00024c,
                        0x00024e, 0x00024e, 0x000370, 0x000370, 0x000372, 0x000372, 0x000376, 0x000376, 0x00037f, 0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e,
                        0x00038f, 0x000391, 0x0003a1, 0x0003a3, 0x0003ab, 0x0003cf, 0x0003cf, 0x0003d8, 0x0003d8, 0x0003da, 0x0003da, 0x0003dc, 0x0003dc, 0x0003de, 0x0003de, 0x0003e0, 0x0003e0,
                        0x0003e2, 0x0003e2, 0x0003e4, 0x0003e4, 0x0003e6, 0x0003e6, 0x0003e8, 0x0003e8, 0x0003ea, 0x0003ea, 0x0003ec, 0x0003ec, 0x0003ee, 0x0003ee, 0x0003f4, 0x0003f4, 0x0003f7,
                        0x0003f7, 0x0003f9, 0x0003fa, 0x0003fd, 0x00042f, 0x000460, 0x000460, 0x000462, 0x000462, 0x000464, 0x000464, 0x000466, 0x000466, 0x000468, 0x000468, 0x00046a, 0x00046a,
                        0x00046c, 0x00046c, 0x00046e, 0x00046e, 0x000470, 0x000470, 0x000472, 0x000472, 0x000474, 0x000474, 0x000476, 0x000476, 0x000478, 0x000478, 0x00047a, 0x00047a, 0x00047c,
                        0x00047c, 0x00047e, 0x00047e, 0x000480, 0x000480, 0x00048a, 0x00048a, 0x00048c, 0x00048c, 0x00048e, 0x00048e, 0x000490, 0x000490, 0x000492, 0x000492, 0x000494, 0x000494,
                        0x000496, 0x000496, 0x000498, 0x000498, 0x00049a, 0x00049a, 0x00049c, 0x00049c, 0x00049e, 0x00049e, 0x0004a0, 0x0004a0, 0x0004a2, 0x0004a2, 0x0004a4, 0x0004a4, 0x0004a6,
                        0x0004a6, 0x0004a8, 0x0004a8, 0x0004aa, 0x0004aa, 0x0004ac, 0x0004ac, 0x0004ae, 0x0004ae, 0x0004b0, 0x0004b0, 0x0004b2, 0x0004b2, 0x0004b4, 0x0004b4, 0x0004b6, 0x0004b6,
                        0x0004b8, 0x0004b8, 0x0004ba, 0x0004ba, 0x0004bc, 0x0004bc, 0x0004be, 0x0004be, 0x0004c0, 0x0004c1, 0x0004c3, 0x0004c3, 0x0004c5, 0x0004c5, 0x0004c7, 0x0004c7, 0x0004c9,
                        0x0004c9, 0x0004cb, 0x0004cb, 0x0004cd, 0x0004cd, 0x0004d0, 0x0004d0, 0x0004d2, 0x0004d2, 0x0004d4, 0x0004d4, 0x0004d6, 0x0004d6, 0x0004d8, 0x0004d8, 0x0004da, 0x0004da,
                        0x0004dc, 0x0004dc, 0x0004de, 0x0004de, 0x0004e0, 0x0004e0, 0x0004e2, 0x0004e2, 0x0004e4, 0x0004e4, 0x0004e6, 0x0004e6, 0x0004e8, 0x0004e8, 0x0004ea, 0x0004ea, 0x0004ec,
                        0x0004ec, 0x0004ee, 0x0004ee, 0x0004f0, 0x0004f0, 0x0004f2, 0x0004f2, 0x0004f4, 0x0004f4, 0x0004f6, 0x0004f6, 0x0004f8, 0x0004f8, 0x0004fa, 0x0004fa, 0x0004fc, 0x0004fc,
                        0x0004fe, 0x0004fe, 0x000500, 0x000500, 0x000502, 0x000502, 0x000504, 0x000504, 0x000506, 0x000506, 0x000508, 0x000508, 0x00050a, 0x00050a, 0x00050c, 0x00050c, 0x00050e,
                        0x00050e, 0x000510, 0x000510, 0x000512, 0x000512, 0x000514, 0x000514, 0x000516, 0x000516, 0x000518, 0x000518, 0x00051a, 0x00051a, 0x00051c, 0x00051c, 0x00051e, 0x00051e,
                        0x000520, 0x000520, 0x000522, 0x000522, 0x000524, 0x000524, 0x000526, 0x000526, 0x000528, 0x000528, 0x00052a, 0x00052a, 0x00052c, 0x00052c, 0x00052e, 0x00052e, 0x000531,
                        0x000556, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0013a0, 0x0013f5, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001e00, 0x001e00, 0x001e02, 0x001e02,
                        0x001e04, 0x001e04, 0x001e06, 0x001e06, 0x001e08, 0x001e08, 0x001e0a, 0x001e0a, 0x001e0c, 0x001e0c, 0x001e0e, 0x001e0e, 0x001e10, 0x001e10, 0x001e12, 0x001e12, 0x001e14,
                        0x001e14, 0x001e16, 0x001e16, 0x001e18, 0x001e18, 0x001e1a, 0x001e1a, 0x001e1c, 0x001e1c, 0x001e1e, 0x001e1e, 0x001e20, 0x001e20, 0x001e22, 0x001e22, 0x001e24, 0x001e24,
                        0x001e26, 0x001e26, 0x001e28, 0x001e28, 0x001e2a, 0x001e2a, 0x001e2c, 0x001e2c, 0x001e2e, 0x001e2e, 0x001e30, 0x001e30, 0x001e32, 0x001e32, 0x001e34, 0x001e34, 0x001e36,
                        0x001e36, 0x001e38, 0x001e38, 0x001e3a, 0x001e3a, 0x001e3c, 0x001e3c, 0x001e3e, 0x001e3e, 0x001e40, 0x001e40, 0x001e42, 0x001e42, 0x001e44, 0x001e44, 0x001e46, 0x001e46,
                        0x001e48, 0x001e48, 0x001e4a, 0x001e4a, 0x001e4c, 0x001e4c, 0x001e4e, 0x001e4e, 0x001e50, 0x001e50, 0x001e52, 0x001e52, 0x001e54, 0x001e54, 0x001e56, 0x001e56, 0x001e58,
                        0x001e58, 0x001e5a, 0x001e5a, 0x001e5c, 0x001e5c, 0x001e5e, 0x001e5e, 0x001e60, 0x001e60, 0x001e62, 0x001e62, 0x001e64, 0x001e64, 0x001e66, 0x001e66, 0x001e68, 0x001e68,
                        0x001e6a, 0x001e6a, 0x001e6c, 0x001e6c, 0x001e6e, 0x001e6e, 0x001e70, 0x001e70, 0x001e72, 0x001e72, 0x001e74, 0x001e74, 0x001e76, 0x001e76, 0x001e78, 0x001e78, 0x001e7a,
                        0x001e7a, 0x001e7c, 0x001e7c, 0x001e7e, 0x001e7e, 0x001e80, 0x001e80, 0x001e82, 0x001e82, 0x001e84, 0x001e84, 0x001e86, 0x001e86, 0x001e88, 0x001e88, 0x001e8a, 0x001e8a,
                        0x001e8c, 0x001e8c, 0x001e8e, 0x001e8e, 0x001e90, 0x001e90, 0x001e92, 0x001e92, 0x001e94, 0x001e94, 0x001e9e, 0x001e9e, 0x001ea0, 0x001ea0, 0x001ea2, 0x001ea2, 0x001ea4,
                        0x001ea4, 0x001ea6, 0x001ea6, 0x001ea8, 0x001ea8, 0x001eaa, 0x001eaa, 0x001eac, 0x001eac, 0x001eae, 0x001eae, 0x001eb0, 0x001eb0, 0x001eb2, 0x001eb2, 0x001eb4, 0x001eb4,
                        0x001eb6, 0x001eb6, 0x001eb8, 0x001eb8, 0x001eba, 0x001eba, 0x001ebc, 0x001ebc, 0x001ebe, 0x001ebe, 0x001ec0, 0x001ec0, 0x001ec2, 0x001ec2, 0x001ec4, 0x001ec4, 0x001ec6,
                        0x001ec6, 0x001ec8, 0x001ec8, 0x001eca, 0x001eca, 0x001ecc, 0x001ecc, 0x001ece, 0x001ece, 0x001ed0, 0x001ed0, 0x001ed2, 0x001ed2, 0x001ed4, 0x001ed4, 0x001ed6, 0x001ed6,
                        0x001ed8, 0x001ed8, 0x001eda, 0x001eda, 0x001edc, 0x001edc, 0x001ede, 0x001ede, 0x001ee0, 0x001ee0, 0x001ee2, 0x001ee2, 0x001ee4, 0x001ee4, 0x001ee6, 0x001ee6, 0x001ee8,
                        0x001ee8, 0x001eea, 0x001eea, 0x001eec, 0x001eec, 0x001eee, 0x001eee, 0x001ef0, 0x001ef0, 0x001ef2, 0x001ef2, 0x001ef4, 0x001ef4, 0x001ef6, 0x001ef6, 0x001ef8, 0x001ef8,
                        0x001efa, 0x001efa, 0x001efc, 0x001efc, 0x001efe, 0x001efe, 0x001f08, 0x001f0f, 0x001f18, 0x001f1d, 0x001f28, 0x001f2f, 0x001f38, 0x001f3f, 0x001f48, 0x001f4d, 0x001f59,
                        0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f5f, 0x001f68, 0x001f6f, 0x001f88, 0x001f8f, 0x001f98, 0x001f9f, 0x001fa8, 0x001faf, 0x001fb8, 0x001fbc,
                        0x001fc8, 0x001fcc, 0x001fd8, 0x001fdb, 0x001fe8, 0x001fec, 0x001ff8, 0x001ffc, 0x002126, 0x002126, 0x00212a, 0x00212b, 0x002132, 0x002132, 0x002160, 0x00216f, 0x002183,
                        0x002183, 0x0024b6, 0x0024cf, 0x002c00, 0x002c2f, 0x002c60, 0x002c60, 0x002c62, 0x002c64, 0x002c67, 0x002c67, 0x002c69, 0x002c69, 0x002c6b, 0x002c6b, 0x002c6d, 0x002c70,
                        0x002c72, 0x002c72, 0x002c75, 0x002c75, 0x002c7e, 0x002c80, 0x002c82, 0x002c82, 0x002c84, 0x002c84, 0x002c86, 0x002c86, 0x002c88, 0x002c88, 0x002c8a, 0x002c8a, 0x002c8c,
                        0x002c8c, 0x002c8e, 0x002c8e, 0x002c90, 0x002c90, 0x002c92, 0x002c92, 0x002c94, 0x002c94, 0x002c96, 0x002c96, 0x002c98, 0x002c98, 0x002c9a, 0x002c9a, 0x002c9c, 0x002c9c,
                        0x002c9e, 0x002c9e, 0x002ca0, 0x002ca0, 0x002ca2, 0x002ca2, 0x002ca4, 0x002ca4, 0x002ca6, 0x002ca6, 0x002ca8, 0x002ca8, 0x002caa, 0x002caa, 0x002cac, 0x002cac, 0x002cae,
                        0x002cae, 0x002cb0, 0x002cb0, 0x002cb2, 0x002cb2, 0x002cb4, 0x002cb4, 0x002cb6, 0x002cb6, 0x002cb8, 0x002cb8, 0x002cba, 0x002cba, 0x002cbc, 0x002cbc, 0x002cbe, 0x002cbe,
                        0x002cc0, 0x002cc0, 0x002cc2, 0x002cc2, 0x002cc4, 0x002cc4, 0x002cc6, 0x002cc6, 0x002cc8, 0x002cc8, 0x002cca, 0x002cca, 0x002ccc, 0x002ccc, 0x002cce, 0x002cce, 0x002cd0,
                        0x002cd0, 0x002cd2, 0x002cd2, 0x002cd4, 0x002cd4, 0x002cd6, 0x002cd6, 0x002cd8, 0x002cd8, 0x002cda, 0x002cda, 0x002cdc, 0x002cdc, 0x002cde, 0x002cde, 0x002ce0, 0x002ce0,
                        0x002ce2, 0x002ce2, 0x002ceb, 0x002ceb, 0x002ced, 0x002ced, 0x002cf2, 0x002cf2, 0x00a640, 0x00a640, 0x00a642, 0x00a642, 0x00a644, 0x00a644, 0x00a646, 0x00a646, 0x00a648,
                        0x00a648, 0x00a64a, 0x00a64a, 0x00a64c, 0x00a64c, 0x00a64e, 0x00a64e, 0x00a650, 0x00a650, 0x00a652, 0x00a652, 0x00a654, 0x00a654, 0x00a656, 0x00a656, 0x00a658, 0x00a658,
                        0x00a65a, 0x00a65a, 0x00a65c, 0x00a65c, 0x00a65e, 0x00a65e, 0x00a660, 0x00a660, 0x00a662, 0x00a662, 0x00a664, 0x00a664, 0x00a666, 0x00a666, 0x00a668, 0x00a668, 0x00a66a,
                        0x00a66a, 0x00a66c, 0x00a66c, 0x00a680, 0x00a680, 0x00a682, 0x00a682, 0x00a684, 0x00a684, 0x00a686, 0x00a686, 0x00a688, 0x00a688, 0x00a68a, 0x00a68a, 0x00a68c, 0x00a68c,
                        0x00a68e, 0x00a68e, 0x00a690, 0x00a690, 0x00a692, 0x00a692, 0x00a694, 0x00a694, 0x00a696, 0x00a696, 0x00a698, 0x00a698, 0x00a69a, 0x00a69a, 0x00a722, 0x00a722, 0x00a724,
                        0x00a724, 0x00a726, 0x00a726, 0x00a728, 0x00a728, 0x00a72a, 0x00a72a, 0x00a72c, 0x00a72c, 0x00a72e, 0x00a72e, 0x00a732, 0x00a732, 0x00a734, 0x00a734, 0x00a736, 0x00a736,
                        0x00a738, 0x00a738, 0x00a73a, 0x00a73a, 0x00a73c, 0x00a73c, 0x00a73e, 0x00a73e, 0x00a740, 0x00a740, 0x00a742, 0x00a742, 0x00a744, 0x00a744, 0x00a746, 0x00a746, 0x00a748,
                        0x00a748, 0x00a74a, 0x00a74a, 0x00a74c, 0x00a74c, 0x00a74e, 0x00a74e, 0x00a750, 0x00a750, 0x00a752, 0x00a752, 0x00a754, 0x00a754, 0x00a756, 0x00a756, 0x00a758, 0x00a758,
                        0x00a75a, 0x00a75a, 0x00a75c, 0x00a75c, 0x00a75e, 0x00a75e, 0x00a760, 0x00a760, 0x00a762, 0x00a762, 0x00a764, 0x00a764, 0x00a766, 0x00a766, 0x00a768, 0x00a768, 0x00a76a,
                        0x00a76a, 0x00a76c, 0x00a76c, 0x00a76e, 0x00a76e, 0x00a779, 0x00a779, 0x00a77b, 0x00a77b, 0x00a77d, 0x00a77e, 0x00a780, 0x00a780, 0x00a782, 0x00a782, 0x00a784, 0x00a784,
                        0x00a786, 0x00a786, 0x00a78b, 0x00a78b, 0x00a78d, 0x00a78d, 0x00a790, 0x00a790, 0x00a792, 0x00a792, 0x00a796, 0x00a796, 0x00a798, 0x00a798, 0x00a79a, 0x00a79a, 0x00a79c,
                        0x00a79c, 0x00a79e, 0x00a79e, 0x00a7a0, 0x00a7a0, 0x00a7a2, 0x00a7a2, 0x00a7a4, 0x00a7a4, 0x00a7a6, 0x00a7a6, 0x00a7a8, 0x00a7a8, 0x00a7aa, 0x00a7ae, 0x00a7b0, 0x00a7b4,
                        0x00a7b6, 0x00a7b6, 0x00a7b8, 0x00a7b8, 0x00a7ba, 0x00a7ba, 0x00a7bc, 0x00a7bc, 0x00a7be, 0x00a7be, 0x00a7c0, 0x00a7c0, 0x00a7c2, 0x00a7c2, 0x00a7c4, 0x00a7c7, 0x00a7c9,
                        0x00a7c9, 0x00a7d0, 0x00a7d0, 0x00a7d6, 0x00a7d6, 0x00a7d8, 0x00a7d8, 0x00a7f5, 0x00a7f5, 0x00ff21, 0x00ff3a, 0x010400, 0x010427, 0x0104b0, 0x0104d3, 0x010570, 0x01057a,
                        0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010c80, 0x010cb2, 0x0118a0, 0x0118bf, 0x016e40, 0x016e5f, 0x01e900, 0x01e921));
    }

    private static void populateCWT() {
        SET_ENCODINGS.put("CWT", CodePointSet.createNoDedup(0x000061, 0x00007a, 0x0000b5, 0x0000b5, 0x0000df, 0x0000f6, 0x0000f8, 0x0000ff, 0x000101, 0x000101, 0x000103, 0x000103, 0x000105, 0x000105,
                        0x000107, 0x000107, 0x000109, 0x000109, 0x00010b, 0x00010b, 0x00010d, 0x00010d, 0x00010f, 0x00010f, 0x000111, 0x000111, 0x000113, 0x000113, 0x000115, 0x000115, 0x000117,
                        0x000117, 0x000119, 0x000119, 0x00011b, 0x00011b, 0x00011d, 0x00011d, 0x00011f, 0x00011f, 0x000121, 0x000121, 0x000123, 0x000123, 0x000125, 0x000125, 0x000127, 0x000127,
                        0x000129, 0x000129, 0x00012b, 0x00012b, 0x00012d, 0x00012d, 0x00012f, 0x00012f, 0x000131, 0x000131, 0x000133, 0x000133, 0x000135, 0x000135, 0x000137, 0x000137, 0x00013a,
                        0x00013a, 0x00013c, 0x00013c, 0x00013e, 0x00013e, 0x000140, 0x000140, 0x000142, 0x000142, 0x000144, 0x000144, 0x000146, 0x000146, 0x000148, 0x000149, 0x00014b, 0x00014b,
                        0x00014d, 0x00014d, 0x00014f, 0x00014f, 0x000151, 0x000151, 0x000153, 0x000153, 0x000155, 0x000155, 0x000157, 0x000157, 0x000159, 0x000159, 0x00015b, 0x00015b, 0x00015d,
                        0x00015d, 0x00015f, 0x00015f, 0x000161, 0x000161, 0x000163, 0x000163, 0x000165, 0x000165, 0x000167, 0x000167, 0x000169, 0x000169, 0x00016b, 0x00016b, 0x00016d, 0x00016d,
                        0x00016f, 0x00016f, 0x000171, 0x000171, 0x000173, 0x000173, 0x000175, 0x000175, 0x000177, 0x000177, 0x00017a, 0x00017a, 0x00017c, 0x00017c, 0x00017e, 0x000180, 0x000183,
                        0x000183, 0x000185, 0x000185, 0x000188, 0x000188, 0x00018c, 0x00018c, 0x000192, 0x000192, 0x000195, 0x000195, 0x000199, 0x00019a, 0x00019e, 0x00019e, 0x0001a1, 0x0001a1,
                        0x0001a3, 0x0001a3, 0x0001a5, 0x0001a5, 0x0001a8, 0x0001a8, 0x0001ad, 0x0001ad, 0x0001b0, 0x0001b0, 0x0001b4, 0x0001b4, 0x0001b6, 0x0001b6, 0x0001b9, 0x0001b9, 0x0001bd,
                        0x0001bd, 0x0001bf, 0x0001bf, 0x0001c4, 0x0001c4, 0x0001c6, 0x0001c7, 0x0001c9, 0x0001ca, 0x0001cc, 0x0001cc, 0x0001ce, 0x0001ce, 0x0001d0, 0x0001d0, 0x0001d2, 0x0001d2,
                        0x0001d4, 0x0001d4, 0x0001d6, 0x0001d6, 0x0001d8, 0x0001d8, 0x0001da, 0x0001da, 0x0001dc, 0x0001dd, 0x0001df, 0x0001df, 0x0001e1, 0x0001e1, 0x0001e3, 0x0001e3, 0x0001e5,
                        0x0001e5, 0x0001e7, 0x0001e7, 0x0001e9, 0x0001e9, 0x0001eb, 0x0001eb, 0x0001ed, 0x0001ed, 0x0001ef, 0x0001f1, 0x0001f3, 0x0001f3, 0x0001f5, 0x0001f5, 0x0001f9, 0x0001f9,
                        0x0001fb, 0x0001fb, 0x0001fd, 0x0001fd, 0x0001ff, 0x0001ff, 0x000201, 0x000201, 0x000203, 0x000203, 0x000205, 0x000205, 0x000207, 0x000207, 0x000209, 0x000209, 0x00020b,
                        0x00020b, 0x00020d, 0x00020d, 0x00020f, 0x00020f, 0x000211, 0x000211, 0x000213, 0x000213, 0x000215, 0x000215, 0x000217, 0x000217, 0x000219, 0x000219, 0x00021b, 0x00021b,
                        0x00021d, 0x00021d, 0x00021f, 0x00021f, 0x000223, 0x000223, 0x000225, 0x000225, 0x000227, 0x000227, 0x000229, 0x000229, 0x00022b, 0x00022b, 0x00022d, 0x00022d, 0x00022f,
                        0x00022f, 0x000231, 0x000231, 0x000233, 0x000233, 0x00023c, 0x00023c, 0x00023f, 0x000240, 0x000242, 0x000242, 0x000247, 0x000247, 0x000249, 0x000249, 0x00024b, 0x00024b,
                        0x00024d, 0x00024d, 0x00024f, 0x000254, 0x000256, 0x000257, 0x000259, 0x000259, 0x00025b, 0x00025c, 0x000260, 0x000261, 0x000263, 0x000263, 0x000265, 0x000266, 0x000268,
                        0x00026c, 0x00026f, 0x00026f, 0x000271, 0x000272, 0x000275, 0x000275, 0x00027d, 0x00027d, 0x000280, 0x000280, 0x000282, 0x000283, 0x000287, 0x00028c, 0x000292, 0x000292,
                        0x00029d, 0x00029e, 0x000345, 0x000345, 0x000371, 0x000371, 0x000373, 0x000373, 0x000377, 0x000377, 0x00037b, 0x00037d, 0x000390, 0x000390, 0x0003ac, 0x0003ce, 0x0003d0,
                        0x0003d1, 0x0003d5, 0x0003d7, 0x0003d9, 0x0003d9, 0x0003db, 0x0003db, 0x0003dd, 0x0003dd, 0x0003df, 0x0003df, 0x0003e1, 0x0003e1, 0x0003e3, 0x0003e3, 0x0003e5, 0x0003e5,
                        0x0003e7, 0x0003e7, 0x0003e9, 0x0003e9, 0x0003eb, 0x0003eb, 0x0003ed, 0x0003ed, 0x0003ef, 0x0003f3, 0x0003f5, 0x0003f5, 0x0003f8, 0x0003f8, 0x0003fb, 0x0003fb, 0x000430,
                        0x00045f, 0x000461, 0x000461, 0x000463, 0x000463, 0x000465, 0x000465, 0x000467, 0x000467, 0x000469, 0x000469, 0x00046b, 0x00046b, 0x00046d, 0x00046d, 0x00046f, 0x00046f,
                        0x000471, 0x000471, 0x000473, 0x000473, 0x000475, 0x000475, 0x000477, 0x000477, 0x000479, 0x000479, 0x00047b, 0x00047b, 0x00047d, 0x00047d, 0x00047f, 0x00047f, 0x000481,
                        0x000481, 0x00048b, 0x00048b, 0x00048d, 0x00048d, 0x00048f, 0x00048f, 0x000491, 0x000491, 0x000493, 0x000493, 0x000495, 0x000495, 0x000497, 0x000497, 0x000499, 0x000499,
                        0x00049b, 0x00049b, 0x00049d, 0x00049d, 0x00049f, 0x00049f, 0x0004a1, 0x0004a1, 0x0004a3, 0x0004a3, 0x0004a5, 0x0004a5, 0x0004a7, 0x0004a7, 0x0004a9, 0x0004a9, 0x0004ab,
                        0x0004ab, 0x0004ad, 0x0004ad, 0x0004af, 0x0004af, 0x0004b1, 0x0004b1, 0x0004b3, 0x0004b3, 0x0004b5, 0x0004b5, 0x0004b7, 0x0004b7, 0x0004b9, 0x0004b9, 0x0004bb, 0x0004bb,
                        0x0004bd, 0x0004bd, 0x0004bf, 0x0004bf, 0x0004c2, 0x0004c2, 0x0004c4, 0x0004c4, 0x0004c6, 0x0004c6, 0x0004c8, 0x0004c8, 0x0004ca, 0x0004ca, 0x0004cc, 0x0004cc, 0x0004ce,
                        0x0004cf, 0x0004d1, 0x0004d1, 0x0004d3, 0x0004d3, 0x0004d5, 0x0004d5, 0x0004d7, 0x0004d7, 0x0004d9, 0x0004d9, 0x0004db, 0x0004db, 0x0004dd, 0x0004dd, 0x0004df, 0x0004df,
                        0x0004e1, 0x0004e1, 0x0004e3, 0x0004e3, 0x0004e5, 0x0004e5, 0x0004e7, 0x0004e7, 0x0004e9, 0x0004e9, 0x0004eb, 0x0004eb, 0x0004ed, 0x0004ed, 0x0004ef, 0x0004ef, 0x0004f1,
                        0x0004f1, 0x0004f3, 0x0004f3, 0x0004f5, 0x0004f5, 0x0004f7, 0x0004f7, 0x0004f9, 0x0004f9, 0x0004fb, 0x0004fb, 0x0004fd, 0x0004fd, 0x0004ff, 0x0004ff, 0x000501, 0x000501,
                        0x000503, 0x000503, 0x000505, 0x000505, 0x000507, 0x000507, 0x000509, 0x000509, 0x00050b, 0x00050b, 0x00050d, 0x00050d, 0x00050f, 0x00050f, 0x000511, 0x000511, 0x000513,
                        0x000513, 0x000515, 0x000515, 0x000517, 0x000517, 0x000519, 0x000519, 0x00051b, 0x00051b, 0x00051d, 0x00051d, 0x00051f, 0x00051f, 0x000521, 0x000521, 0x000523, 0x000523,
                        0x000525, 0x000525, 0x000527, 0x000527, 0x000529, 0x000529, 0x00052b, 0x00052b, 0x00052d, 0x00052d, 0x00052f, 0x00052f, 0x000561, 0x000587, 0x0013f8, 0x0013fd, 0x001c80,
                        0x001c88, 0x001d79, 0x001d79, 0x001d7d, 0x001d7d, 0x001d8e, 0x001d8e, 0x001e01, 0x001e01, 0x001e03, 0x001e03, 0x001e05, 0x001e05, 0x001e07, 0x001e07, 0x001e09, 0x001e09,
                        0x001e0b, 0x001e0b, 0x001e0d, 0x001e0d, 0x001e0f, 0x001e0f, 0x001e11, 0x001e11, 0x001e13, 0x001e13, 0x001e15, 0x001e15, 0x001e17, 0x001e17, 0x001e19, 0x001e19, 0x001e1b,
                        0x001e1b, 0x001e1d, 0x001e1d, 0x001e1f, 0x001e1f, 0x001e21, 0x001e21, 0x001e23, 0x001e23, 0x001e25, 0x001e25, 0x001e27, 0x001e27, 0x001e29, 0x001e29, 0x001e2b, 0x001e2b,
                        0x001e2d, 0x001e2d, 0x001e2f, 0x001e2f, 0x001e31, 0x001e31, 0x001e33, 0x001e33, 0x001e35, 0x001e35, 0x001e37, 0x001e37, 0x001e39, 0x001e39, 0x001e3b, 0x001e3b, 0x001e3d,
                        0x001e3d, 0x001e3f, 0x001e3f, 0x001e41, 0x001e41, 0x001e43, 0x001e43, 0x001e45, 0x001e45, 0x001e47, 0x001e47, 0x001e49, 0x001e49, 0x001e4b, 0x001e4b, 0x001e4d, 0x001e4d,
                        0x001e4f, 0x001e4f, 0x001e51, 0x001e51, 0x001e53, 0x001e53, 0x001e55, 0x001e55, 0x001e57, 0x001e57, 0x001e59, 0x001e59, 0x001e5b, 0x001e5b, 0x001e5d, 0x001e5d, 0x001e5f,
                        0x001e5f, 0x001e61, 0x001e61, 0x001e63, 0x001e63, 0x001e65, 0x001e65, 0x001e67, 0x001e67, 0x001e69, 0x001e69, 0x001e6b, 0x001e6b, 0x001e6d, 0x001e6d, 0x001e6f, 0x001e6f,
                        0x001e71, 0x001e71, 0x001e73, 0x001e73, 0x001e75, 0x001e75, 0x001e77, 0x001e77, 0x001e79, 0x001e79, 0x001e7b, 0x001e7b, 0x001e7d, 0x001e7d, 0x001e7f, 0x001e7f, 0x001e81,
                        0x001e81, 0x001e83, 0x001e83, 0x001e85, 0x001e85, 0x001e87, 0x001e87, 0x001e89, 0x001e89, 0x001e8b, 0x001e8b, 0x001e8d, 0x001e8d, 0x001e8f, 0x001e8f, 0x001e91, 0x001e91,
                        0x001e93, 0x001e93, 0x001e95, 0x001e9b, 0x001ea1, 0x001ea1, 0x001ea3, 0x001ea3, 0x001ea5, 0x001ea5, 0x001ea7, 0x001ea7, 0x001ea9, 0x001ea9, 0x001eab, 0x001eab, 0x001ead,
                        0x001ead, 0x001eaf, 0x001eaf, 0x001eb1, 0x001eb1, 0x001eb3, 0x001eb3, 0x001eb5, 0x001eb5, 0x001eb7, 0x001eb7, 0x001eb9, 0x001eb9, 0x001ebb, 0x001ebb, 0x001ebd, 0x001ebd,
                        0x001ebf, 0x001ebf, 0x001ec1, 0x001ec1, 0x001ec3, 0x001ec3, 0x001ec5, 0x001ec5, 0x001ec7, 0x001ec7, 0x001ec9, 0x001ec9, 0x001ecb, 0x001ecb, 0x001ecd, 0x001ecd, 0x001ecf,
                        0x001ecf, 0x001ed1, 0x001ed1, 0x001ed3, 0x001ed3, 0x001ed5, 0x001ed5, 0x001ed7, 0x001ed7, 0x001ed9, 0x001ed9, 0x001edb, 0x001edb, 0x001edd, 0x001edd, 0x001edf, 0x001edf,
                        0x001ee1, 0x001ee1, 0x001ee3, 0x001ee3, 0x001ee5, 0x001ee5, 0x001ee7, 0x001ee7, 0x001ee9, 0x001ee9, 0x001eeb, 0x001eeb, 0x001eed, 0x001eed, 0x001eef, 0x001eef, 0x001ef1,
                        0x001ef1, 0x001ef3, 0x001ef3, 0x001ef5, 0x001ef5, 0x001ef7, 0x001ef7, 0x001ef9, 0x001ef9, 0x001efb, 0x001efb, 0x001efd, 0x001efd, 0x001eff, 0x001f07, 0x001f10, 0x001f15,
                        0x001f20, 0x001f27, 0x001f30, 0x001f37, 0x001f40, 0x001f45, 0x001f50, 0x001f57, 0x001f60, 0x001f67, 0x001f70, 0x001f7d, 0x001f80, 0x001f87, 0x001f90, 0x001f97, 0x001fa0,
                        0x001fa7, 0x001fb0, 0x001fb4, 0x001fb6, 0x001fb7, 0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4, 0x001fc6, 0x001fc7, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fd7, 0x001fe0, 0x001fe7,
                        0x001ff2, 0x001ff4, 0x001ff6, 0x001ff7, 0x00214e, 0x00214e, 0x002170, 0x00217f, 0x002184, 0x002184, 0x0024d0, 0x0024e9, 0x002c30, 0x002c5f, 0x002c61, 0x002c61, 0x002c65,
                        0x002c66, 0x002c68, 0x002c68, 0x002c6a, 0x002c6a, 0x002c6c, 0x002c6c, 0x002c73, 0x002c73, 0x002c76, 0x002c76, 0x002c81, 0x002c81, 0x002c83, 0x002c83, 0x002c85, 0x002c85,
                        0x002c87, 0x002c87, 0x002c89, 0x002c89, 0x002c8b, 0x002c8b, 0x002c8d, 0x002c8d, 0x002c8f, 0x002c8f, 0x002c91, 0x002c91, 0x002c93, 0x002c93, 0x002c95, 0x002c95, 0x002c97,
                        0x002c97, 0x002c99, 0x002c99, 0x002c9b, 0x002c9b, 0x002c9d, 0x002c9d, 0x002c9f, 0x002c9f, 0x002ca1, 0x002ca1, 0x002ca3, 0x002ca3, 0x002ca5, 0x002ca5, 0x002ca7, 0x002ca7,
                        0x002ca9, 0x002ca9, 0x002cab, 0x002cab, 0x002cad, 0x002cad, 0x002caf, 0x002caf, 0x002cb1, 0x002cb1, 0x002cb3, 0x002cb3, 0x002cb5, 0x002cb5, 0x002cb7, 0x002cb7, 0x002cb9,
                        0x002cb9, 0x002cbb, 0x002cbb, 0x002cbd, 0x002cbd, 0x002cbf, 0x002cbf, 0x002cc1, 0x002cc1, 0x002cc3, 0x002cc3, 0x002cc5, 0x002cc5, 0x002cc7, 0x002cc7, 0x002cc9, 0x002cc9,
                        0x002ccb, 0x002ccb, 0x002ccd, 0x002ccd, 0x002ccf, 0x002ccf, 0x002cd1, 0x002cd1, 0x002cd3, 0x002cd3, 0x002cd5, 0x002cd5, 0x002cd7, 0x002cd7, 0x002cd9, 0x002cd9, 0x002cdb,
                        0x002cdb, 0x002cdd, 0x002cdd, 0x002cdf, 0x002cdf, 0x002ce1, 0x002ce1, 0x002ce3, 0x002ce3, 0x002cec, 0x002cec, 0x002cee, 0x002cee, 0x002cf3, 0x002cf3, 0x002d00, 0x002d25,
                        0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x00a641, 0x00a641, 0x00a643, 0x00a643, 0x00a645, 0x00a645, 0x00a647, 0x00a647, 0x00a649, 0x00a649, 0x00a64b, 0x00a64b, 0x00a64d,
                        0x00a64d, 0x00a64f, 0x00a64f, 0x00a651, 0x00a651, 0x00a653, 0x00a653, 0x00a655, 0x00a655, 0x00a657, 0x00a657, 0x00a659, 0x00a659, 0x00a65b, 0x00a65b, 0x00a65d, 0x00a65d,
                        0x00a65f, 0x00a65f, 0x00a661, 0x00a661, 0x00a663, 0x00a663, 0x00a665, 0x00a665, 0x00a667, 0x00a667, 0x00a669, 0x00a669, 0x00a66b, 0x00a66b, 0x00a66d, 0x00a66d, 0x00a681,
                        0x00a681, 0x00a683, 0x00a683, 0x00a685, 0x00a685, 0x00a687, 0x00a687, 0x00a689, 0x00a689, 0x00a68b, 0x00a68b, 0x00a68d, 0x00a68d, 0x00a68f, 0x00a68f, 0x00a691, 0x00a691,
                        0x00a693, 0x00a693, 0x00a695, 0x00a695, 0x00a697, 0x00a697, 0x00a699, 0x00a699, 0x00a69b, 0x00a69b, 0x00a723, 0x00a723, 0x00a725, 0x00a725, 0x00a727, 0x00a727, 0x00a729,
                        0x00a729, 0x00a72b, 0x00a72b, 0x00a72d, 0x00a72d, 0x00a72f, 0x00a72f, 0x00a733, 0x00a733, 0x00a735, 0x00a735, 0x00a737, 0x00a737, 0x00a739, 0x00a739, 0x00a73b, 0x00a73b,
                        0x00a73d, 0x00a73d, 0x00a73f, 0x00a73f, 0x00a741, 0x00a741, 0x00a743, 0x00a743, 0x00a745, 0x00a745, 0x00a747, 0x00a747, 0x00a749, 0x00a749, 0x00a74b, 0x00a74b, 0x00a74d,
                        0x00a74d, 0x00a74f, 0x00a74f, 0x00a751, 0x00a751, 0x00a753, 0x00a753, 0x00a755, 0x00a755, 0x00a757, 0x00a757, 0x00a759, 0x00a759, 0x00a75b, 0x00a75b, 0x00a75d, 0x00a75d,
                        0x00a75f, 0x00a75f, 0x00a761, 0x00a761, 0x00a763, 0x00a763, 0x00a765, 0x00a765, 0x00a767, 0x00a767, 0x00a769, 0x00a769, 0x00a76b, 0x00a76b, 0x00a76d, 0x00a76d, 0x00a76f,
                        0x00a76f, 0x00a77a, 0x00a77a, 0x00a77c, 0x00a77c, 0x00a77f, 0x00a77f, 0x00a781, 0x00a781, 0x00a783, 0x00a783, 0x00a785, 0x00a785, 0x00a787, 0x00a787, 0x00a78c, 0x00a78c,
                        0x00a791, 0x00a791, 0x00a793, 0x00a794, 0x00a797, 0x00a797, 0x00a799, 0x00a799, 0x00a79b, 0x00a79b, 0x00a79d, 0x00a79d, 0x00a79f, 0x00a79f, 0x00a7a1, 0x00a7a1, 0x00a7a3,
                        0x00a7a3, 0x00a7a5, 0x00a7a5, 0x00a7a7, 0x00a7a7, 0x00a7a9, 0x00a7a9, 0x00a7b5, 0x00a7b5, 0x00a7b7, 0x00a7b7, 0x00a7b9, 0x00a7b9, 0x00a7bb, 0x00a7bb, 0x00a7bd, 0x00a7bd,
                        0x00a7bf, 0x00a7bf, 0x00a7c1, 0x00a7c1, 0x00a7c3, 0x00a7c3, 0x00a7c8, 0x00a7c8, 0x00a7ca, 0x00a7ca, 0x00a7d1, 0x00a7d1, 0x00a7d7, 0x00a7d7, 0x00a7d9, 0x00a7d9, 0x00a7f6,
                        0x00a7f6, 0x00ab53, 0x00ab53, 0x00ab70, 0x00abbf, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00ff41, 0x00ff5a, 0x010428, 0x01044f, 0x0104d8, 0x0104fb, 0x010597, 0x0105a1,
                        0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010cc0, 0x010cf2, 0x0118c0, 0x0118df, 0x016e60, 0x016e7f, 0x01e922, 0x01e943));
    }

    private static void populateCWU() {
        SET_ENCODINGS.put("CWU", CodePointSet.createNoDedup(0x000061, 0x00007a, 0x0000b5, 0x0000b5, 0x0000df, 0x0000f6, 0x0000f8, 0x0000ff, 0x000101, 0x000101, 0x000103, 0x000103, 0x000105, 0x000105,
                        0x000107, 0x000107, 0x000109, 0x000109, 0x00010b, 0x00010b, 0x00010d, 0x00010d, 0x00010f, 0x00010f, 0x000111, 0x000111, 0x000113, 0x000113, 0x000115, 0x000115, 0x000117,
                        0x000117, 0x000119, 0x000119, 0x00011b, 0x00011b, 0x00011d, 0x00011d, 0x00011f, 0x00011f, 0x000121, 0x000121, 0x000123, 0x000123, 0x000125, 0x000125, 0x000127, 0x000127,
                        0x000129, 0x000129, 0x00012b, 0x00012b, 0x00012d, 0x00012d, 0x00012f, 0x00012f, 0x000131, 0x000131, 0x000133, 0x000133, 0x000135, 0x000135, 0x000137, 0x000137, 0x00013a,
                        0x00013a, 0x00013c, 0x00013c, 0x00013e, 0x00013e, 0x000140, 0x000140, 0x000142, 0x000142, 0x000144, 0x000144, 0x000146, 0x000146, 0x000148, 0x000149, 0x00014b, 0x00014b,
                        0x00014d, 0x00014d, 0x00014f, 0x00014f, 0x000151, 0x000151, 0x000153, 0x000153, 0x000155, 0x000155, 0x000157, 0x000157, 0x000159, 0x000159, 0x00015b, 0x00015b, 0x00015d,
                        0x00015d, 0x00015f, 0x00015f, 0x000161, 0x000161, 0x000163, 0x000163, 0x000165, 0x000165, 0x000167, 0x000167, 0x000169, 0x000169, 0x00016b, 0x00016b, 0x00016d, 0x00016d,
                        0x00016f, 0x00016f, 0x000171, 0x000171, 0x000173, 0x000173, 0x000175, 0x000175, 0x000177, 0x000177, 0x00017a, 0x00017a, 0x00017c, 0x00017c, 0x00017e, 0x000180, 0x000183,
                        0x000183, 0x000185, 0x000185, 0x000188, 0x000188, 0x00018c, 0x00018c, 0x000192, 0x000192, 0x000195, 0x000195, 0x000199, 0x00019a, 0x00019e, 0x00019e, 0x0001a1, 0x0001a1,
                        0x0001a3, 0x0001a3, 0x0001a5, 0x0001a5, 0x0001a8, 0x0001a8, 0x0001ad, 0x0001ad, 0x0001b0, 0x0001b0, 0x0001b4, 0x0001b4, 0x0001b6, 0x0001b6, 0x0001b9, 0x0001b9, 0x0001bd,
                        0x0001bd, 0x0001bf, 0x0001bf, 0x0001c5, 0x0001c6, 0x0001c8, 0x0001c9, 0x0001cb, 0x0001cc, 0x0001ce, 0x0001ce, 0x0001d0, 0x0001d0, 0x0001d2, 0x0001d2, 0x0001d4, 0x0001d4,
                        0x0001d6, 0x0001d6, 0x0001d8, 0x0001d8, 0x0001da, 0x0001da, 0x0001dc, 0x0001dd, 0x0001df, 0x0001df, 0x0001e1, 0x0001e1, 0x0001e3, 0x0001e3, 0x0001e5, 0x0001e5, 0x0001e7,
                        0x0001e7, 0x0001e9, 0x0001e9, 0x0001eb, 0x0001eb, 0x0001ed, 0x0001ed, 0x0001ef, 0x0001f0, 0x0001f2, 0x0001f3, 0x0001f5, 0x0001f5, 0x0001f9, 0x0001f9, 0x0001fb, 0x0001fb,
                        0x0001fd, 0x0001fd, 0x0001ff, 0x0001ff, 0x000201, 0x000201, 0x000203, 0x000203, 0x000205, 0x000205, 0x000207, 0x000207, 0x000209, 0x000209, 0x00020b, 0x00020b, 0x00020d,
                        0x00020d, 0x00020f, 0x00020f, 0x000211, 0x000211, 0x000213, 0x000213, 0x000215, 0x000215, 0x000217, 0x000217, 0x000219, 0x000219, 0x00021b, 0x00021b, 0x00021d, 0x00021d,
                        0x00021f, 0x00021f, 0x000223, 0x000223, 0x000225, 0x000225, 0x000227, 0x000227, 0x000229, 0x000229, 0x00022b, 0x00022b, 0x00022d, 0x00022d, 0x00022f, 0x00022f, 0x000231,
                        0x000231, 0x000233, 0x000233, 0x00023c, 0x00023c, 0x00023f, 0x000240, 0x000242, 0x000242, 0x000247, 0x000247, 0x000249, 0x000249, 0x00024b, 0x00024b, 0x00024d, 0x00024d,
                        0x00024f, 0x000254, 0x000256, 0x000257, 0x000259, 0x000259, 0x00025b, 0x00025c, 0x000260, 0x000261, 0x000263, 0x000263, 0x000265, 0x000266, 0x000268, 0x00026c, 0x00026f,
                        0x00026f, 0x000271, 0x000272, 0x000275, 0x000275, 0x00027d, 0x00027d, 0x000280, 0x000280, 0x000282, 0x000283, 0x000287, 0x00028c, 0x000292, 0x000292, 0x00029d, 0x00029e,
                        0x000345, 0x000345, 0x000371, 0x000371, 0x000373, 0x000373, 0x000377, 0x000377, 0x00037b, 0x00037d, 0x000390, 0x000390, 0x0003ac, 0x0003ce, 0x0003d0, 0x0003d1, 0x0003d5,
                        0x0003d7, 0x0003d9, 0x0003d9, 0x0003db, 0x0003db, 0x0003dd, 0x0003dd, 0x0003df, 0x0003df, 0x0003e1, 0x0003e1, 0x0003e3, 0x0003e3, 0x0003e5, 0x0003e5, 0x0003e7, 0x0003e7,
                        0x0003e9, 0x0003e9, 0x0003eb, 0x0003eb, 0x0003ed, 0x0003ed, 0x0003ef, 0x0003f3, 0x0003f5, 0x0003f5, 0x0003f8, 0x0003f8, 0x0003fb, 0x0003fb, 0x000430, 0x00045f, 0x000461,
                        0x000461, 0x000463, 0x000463, 0x000465, 0x000465, 0x000467, 0x000467, 0x000469, 0x000469, 0x00046b, 0x00046b, 0x00046d, 0x00046d, 0x00046f, 0x00046f, 0x000471, 0x000471,
                        0x000473, 0x000473, 0x000475, 0x000475, 0x000477, 0x000477, 0x000479, 0x000479, 0x00047b, 0x00047b, 0x00047d, 0x00047d, 0x00047f, 0x00047f, 0x000481, 0x000481, 0x00048b,
                        0x00048b, 0x00048d, 0x00048d, 0x00048f, 0x00048f, 0x000491, 0x000491, 0x000493, 0x000493, 0x000495, 0x000495, 0x000497, 0x000497, 0x000499, 0x000499, 0x00049b, 0x00049b,
                        0x00049d, 0x00049d, 0x00049f, 0x00049f, 0x0004a1, 0x0004a1, 0x0004a3, 0x0004a3, 0x0004a5, 0x0004a5, 0x0004a7, 0x0004a7, 0x0004a9, 0x0004a9, 0x0004ab, 0x0004ab, 0x0004ad,
                        0x0004ad, 0x0004af, 0x0004af, 0x0004b1, 0x0004b1, 0x0004b3, 0x0004b3, 0x0004b5, 0x0004b5, 0x0004b7, 0x0004b7, 0x0004b9, 0x0004b9, 0x0004bb, 0x0004bb, 0x0004bd, 0x0004bd,
                        0x0004bf, 0x0004bf, 0x0004c2, 0x0004c2, 0x0004c4, 0x0004c4, 0x0004c6, 0x0004c6, 0x0004c8, 0x0004c8, 0x0004ca, 0x0004ca, 0x0004cc, 0x0004cc, 0x0004ce, 0x0004cf, 0x0004d1,
                        0x0004d1, 0x0004d3, 0x0004d3, 0x0004d5, 0x0004d5, 0x0004d7, 0x0004d7, 0x0004d9, 0x0004d9, 0x0004db, 0x0004db, 0x0004dd, 0x0004dd, 0x0004df, 0x0004df, 0x0004e1, 0x0004e1,
                        0x0004e3, 0x0004e3, 0x0004e5, 0x0004e5, 0x0004e7, 0x0004e7, 0x0004e9, 0x0004e9, 0x0004eb, 0x0004eb, 0x0004ed, 0x0004ed, 0x0004ef, 0x0004ef, 0x0004f1, 0x0004f1, 0x0004f3,
                        0x0004f3, 0x0004f5, 0x0004f5, 0x0004f7, 0x0004f7, 0x0004f9, 0x0004f9, 0x0004fb, 0x0004fb, 0x0004fd, 0x0004fd, 0x0004ff, 0x0004ff, 0x000501, 0x000501, 0x000503, 0x000503,
                        0x000505, 0x000505, 0x000507, 0x000507, 0x000509, 0x000509, 0x00050b, 0x00050b, 0x00050d, 0x00050d, 0x00050f, 0x00050f, 0x000511, 0x000511, 0x000513, 0x000513, 0x000515,
                        0x000515, 0x000517, 0x000517, 0x000519, 0x000519, 0x00051b, 0x00051b, 0x00051d, 0x00051d, 0x00051f, 0x00051f, 0x000521, 0x000521, 0x000523, 0x000523, 0x000525, 0x000525,
                        0x000527, 0x000527, 0x000529, 0x000529, 0x00052b, 0x00052b, 0x00052d, 0x00052d, 0x00052f, 0x00052f, 0x000561, 0x000587, 0x0010d0, 0x0010fa, 0x0010fd, 0x0010ff, 0x0013f8,
                        0x0013fd, 0x001c80, 0x001c88, 0x001d79, 0x001d79, 0x001d7d, 0x001d7d, 0x001d8e, 0x001d8e, 0x001e01, 0x001e01, 0x001e03, 0x001e03, 0x001e05, 0x001e05, 0x001e07, 0x001e07,
                        0x001e09, 0x001e09, 0x001e0b, 0x001e0b, 0x001e0d, 0x001e0d, 0x001e0f, 0x001e0f, 0x001e11, 0x001e11, 0x001e13, 0x001e13, 0x001e15, 0x001e15, 0x001e17, 0x001e17, 0x001e19,
                        0x001e19, 0x001e1b, 0x001e1b, 0x001e1d, 0x001e1d, 0x001e1f, 0x001e1f, 0x001e21, 0x001e21, 0x001e23, 0x001e23, 0x001e25, 0x001e25, 0x001e27, 0x001e27, 0x001e29, 0x001e29,
                        0x001e2b, 0x001e2b, 0x001e2d, 0x001e2d, 0x001e2f, 0x001e2f, 0x001e31, 0x001e31, 0x001e33, 0x001e33, 0x001e35, 0x001e35, 0x001e37, 0x001e37, 0x001e39, 0x001e39, 0x001e3b,
                        0x001e3b, 0x001e3d, 0x001e3d, 0x001e3f, 0x001e3f, 0x001e41, 0x001e41, 0x001e43, 0x001e43, 0x001e45, 0x001e45, 0x001e47, 0x001e47, 0x001e49, 0x001e49, 0x001e4b, 0x001e4b,
                        0x001e4d, 0x001e4d, 0x001e4f, 0x001e4f, 0x001e51, 0x001e51, 0x001e53, 0x001e53, 0x001e55, 0x001e55, 0x001e57, 0x001e57, 0x001e59, 0x001e59, 0x001e5b, 0x001e5b, 0x001e5d,
                        0x001e5d, 0x001e5f, 0x001e5f, 0x001e61, 0x001e61, 0x001e63, 0x001e63, 0x001e65, 0x001e65, 0x001e67, 0x001e67, 0x001e69, 0x001e69, 0x001e6b, 0x001e6b, 0x001e6d, 0x001e6d,
                        0x001e6f, 0x001e6f, 0x001e71, 0x001e71, 0x001e73, 0x001e73, 0x001e75, 0x001e75, 0x001e77, 0x001e77, 0x001e79, 0x001e79, 0x001e7b, 0x001e7b, 0x001e7d, 0x001e7d, 0x001e7f,
                        0x001e7f, 0x001e81, 0x001e81, 0x001e83, 0x001e83, 0x001e85, 0x001e85, 0x001e87, 0x001e87, 0x001e89, 0x001e89, 0x001e8b, 0x001e8b, 0x001e8d, 0x001e8d, 0x001e8f, 0x001e8f,
                        0x001e91, 0x001e91, 0x001e93, 0x001e93, 0x001e95, 0x001e9b, 0x001ea1, 0x001ea1, 0x001ea3, 0x001ea3, 0x001ea5, 0x001ea5, 0x001ea7, 0x001ea7, 0x001ea9, 0x001ea9, 0x001eab,
                        0x001eab, 0x001ead, 0x001ead, 0x001eaf, 0x001eaf, 0x001eb1, 0x001eb1, 0x001eb3, 0x001eb3, 0x001eb5, 0x001eb5, 0x001eb7, 0x001eb7, 0x001eb9, 0x001eb9, 0x001ebb, 0x001ebb,
                        0x001ebd, 0x001ebd, 0x001ebf, 0x001ebf, 0x001ec1, 0x001ec1, 0x001ec3, 0x001ec3, 0x001ec5, 0x001ec5, 0x001ec7, 0x001ec7, 0x001ec9, 0x001ec9, 0x001ecb, 0x001ecb, 0x001ecd,
                        0x001ecd, 0x001ecf, 0x001ecf, 0x001ed1, 0x001ed1, 0x001ed3, 0x001ed3, 0x001ed5, 0x001ed5, 0x001ed7, 0x001ed7, 0x001ed9, 0x001ed9, 0x001edb, 0x001edb, 0x001edd, 0x001edd,
                        0x001edf, 0x001edf, 0x001ee1, 0x001ee1, 0x001ee3, 0x001ee3, 0x001ee5, 0x001ee5, 0x001ee7, 0x001ee7, 0x001ee9, 0x001ee9, 0x001eeb, 0x001eeb, 0x001eed, 0x001eed, 0x001eef,
                        0x001eef, 0x001ef1, 0x001ef1, 0x001ef3, 0x001ef3, 0x001ef5, 0x001ef5, 0x001ef7, 0x001ef7, 0x001ef9, 0x001ef9, 0x001efb, 0x001efb, 0x001efd, 0x001efd, 0x001eff, 0x001f07,
                        0x001f10, 0x001f15, 0x001f20, 0x001f27, 0x001f30, 0x001f37, 0x001f40, 0x001f45, 0x001f50, 0x001f57, 0x001f60, 0x001f67, 0x001f70, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6,
                        0x001fb7, 0x001fbc, 0x001fbc, 0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4, 0x001fc6, 0x001fc7, 0x001fcc, 0x001fcc, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fd7, 0x001fe0, 0x001fe7,
                        0x001ff2, 0x001ff4, 0x001ff6, 0x001ff7, 0x001ffc, 0x001ffc, 0x00214e, 0x00214e, 0x002170, 0x00217f, 0x002184, 0x002184, 0x0024d0, 0x0024e9, 0x002c30, 0x002c5f, 0x002c61,
                        0x002c61, 0x002c65, 0x002c66, 0x002c68, 0x002c68, 0x002c6a, 0x002c6a, 0x002c6c, 0x002c6c, 0x002c73, 0x002c73, 0x002c76, 0x002c76, 0x002c81, 0x002c81, 0x002c83, 0x002c83,
                        0x002c85, 0x002c85, 0x002c87, 0x002c87, 0x002c89, 0x002c89, 0x002c8b, 0x002c8b, 0x002c8d, 0x002c8d, 0x002c8f, 0x002c8f, 0x002c91, 0x002c91, 0x002c93, 0x002c93, 0x002c95,
                        0x002c95, 0x002c97, 0x002c97, 0x002c99, 0x002c99, 0x002c9b, 0x002c9b, 0x002c9d, 0x002c9d, 0x002c9f, 0x002c9f, 0x002ca1, 0x002ca1, 0x002ca3, 0x002ca3, 0x002ca5, 0x002ca5,
                        0x002ca7, 0x002ca7, 0x002ca9, 0x002ca9, 0x002cab, 0x002cab, 0x002cad, 0x002cad, 0x002caf, 0x002caf, 0x002cb1, 0x002cb1, 0x002cb3, 0x002cb3, 0x002cb5, 0x002cb5, 0x002cb7,
                        0x002cb7, 0x002cb9, 0x002cb9, 0x002cbb, 0x002cbb, 0x002cbd, 0x002cbd, 0x002cbf, 0x002cbf, 0x002cc1, 0x002cc1, 0x002cc3, 0x002cc3, 0x002cc5, 0x002cc5, 0x002cc7, 0x002cc7,
                        0x002cc9, 0x002cc9, 0x002ccb, 0x002ccb, 0x002ccd, 0x002ccd, 0x002ccf, 0x002ccf, 0x002cd1, 0x002cd1, 0x002cd3, 0x002cd3, 0x002cd5, 0x002cd5, 0x002cd7, 0x002cd7, 0x002cd9,
                        0x002cd9, 0x002cdb, 0x002cdb, 0x002cdd, 0x002cdd, 0x002cdf, 0x002cdf, 0x002ce1, 0x002ce1, 0x002ce3, 0x002ce3, 0x002cec, 0x002cec, 0x002cee, 0x002cee, 0x002cf3, 0x002cf3,
                        0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x00a641, 0x00a641, 0x00a643, 0x00a643, 0x00a645, 0x00a645, 0x00a647, 0x00a647, 0x00a649, 0x00a649, 0x00a64b,
                        0x00a64b, 0x00a64d, 0x00a64d, 0x00a64f, 0x00a64f, 0x00a651, 0x00a651, 0x00a653, 0x00a653, 0x00a655, 0x00a655, 0x00a657, 0x00a657, 0x00a659, 0x00a659, 0x00a65b, 0x00a65b,
                        0x00a65d, 0x00a65d, 0x00a65f, 0x00a65f, 0x00a661, 0x00a661, 0x00a663, 0x00a663, 0x00a665, 0x00a665, 0x00a667, 0x00a667, 0x00a669, 0x00a669, 0x00a66b, 0x00a66b, 0x00a66d,
                        0x00a66d, 0x00a681, 0x00a681, 0x00a683, 0x00a683, 0x00a685, 0x00a685, 0x00a687, 0x00a687, 0x00a689, 0x00a689, 0x00a68b, 0x00a68b, 0x00a68d, 0x00a68d, 0x00a68f, 0x00a68f,
                        0x00a691, 0x00a691, 0x00a693, 0x00a693, 0x00a695, 0x00a695, 0x00a697, 0x00a697, 0x00a699, 0x00a699, 0x00a69b, 0x00a69b, 0x00a723, 0x00a723, 0x00a725, 0x00a725, 0x00a727,
                        0x00a727, 0x00a729, 0x00a729, 0x00a72b, 0x00a72b, 0x00a72d, 0x00a72d, 0x00a72f, 0x00a72f, 0x00a733, 0x00a733, 0x00a735, 0x00a735, 0x00a737, 0x00a737, 0x00a739, 0x00a739,
                        0x00a73b, 0x00a73b, 0x00a73d, 0x00a73d, 0x00a73f, 0x00a73f, 0x00a741, 0x00a741, 0x00a743, 0x00a743, 0x00a745, 0x00a745, 0x00a747, 0x00a747, 0x00a749, 0x00a749, 0x00a74b,
                        0x00a74b, 0x00a74d, 0x00a74d, 0x00a74f, 0x00a74f, 0x00a751, 0x00a751, 0x00a753, 0x00a753, 0x00a755, 0x00a755, 0x00a757, 0x00a757, 0x00a759, 0x00a759, 0x00a75b, 0x00a75b,
                        0x00a75d, 0x00a75d, 0x00a75f, 0x00a75f, 0x00a761, 0x00a761, 0x00a763, 0x00a763, 0x00a765, 0x00a765, 0x00a767, 0x00a767, 0x00a769, 0x00a769, 0x00a76b, 0x00a76b, 0x00a76d,
                        0x00a76d, 0x00a76f, 0x00a76f, 0x00a77a, 0x00a77a, 0x00a77c, 0x00a77c, 0x00a77f, 0x00a77f, 0x00a781, 0x00a781, 0x00a783, 0x00a783, 0x00a785, 0x00a785, 0x00a787, 0x00a787,
                        0x00a78c, 0x00a78c, 0x00a791, 0x00a791, 0x00a793, 0x00a794, 0x00a797, 0x00a797, 0x00a799, 0x00a799, 0x00a79b, 0x00a79b, 0x00a79d, 0x00a79d, 0x00a79f, 0x00a79f, 0x00a7a1,
                        0x00a7a1, 0x00a7a3, 0x00a7a3, 0x00a7a5, 0x00a7a5, 0x00a7a7, 0x00a7a7, 0x00a7a9, 0x00a7a9, 0x00a7b5, 0x00a7b5, 0x00a7b7, 0x00a7b7, 0x00a7b9, 0x00a7b9, 0x00a7bb, 0x00a7bb,
                        0x00a7bd, 0x00a7bd, 0x00a7bf, 0x00a7bf, 0x00a7c1, 0x00a7c1, 0x00a7c3, 0x00a7c3, 0x00a7c8, 0x00a7c8, 0x00a7ca, 0x00a7ca, 0x00a7d1, 0x00a7d1, 0x00a7d7, 0x00a7d7, 0x00a7d9,
                        0x00a7d9, 0x00a7f6, 0x00a7f6, 0x00ab53, 0x00ab53, 0x00ab70, 0x00abbf, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00ff41, 0x00ff5a, 0x010428, 0x01044f, 0x0104d8, 0x0104fb,
                        0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010cc0, 0x010cf2, 0x0118c0, 0x0118df, 0x016e60, 0x016e7f, 0x01e922, 0x01e943));
    }

    private static void populateCASED() {
        SET_ENCODINGS.put("Cased", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000b5, 0x0000b5, 0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8,
                        0x0000f6, 0x0000f8, 0x0001ba, 0x0001bc, 0x0001bf, 0x0001c4, 0x000293, 0x000295, 0x0002b8, 0x0002c0, 0x0002c1, 0x0002e0, 0x0002e4, 0x000345, 0x000345, 0x000370, 0x000373,
                        0x000376, 0x000377, 0x00037a, 0x00037d, 0x00037f, 0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003f5, 0x0003f7,
                        0x000481, 0x00048a, 0x00052f, 0x000531, 0x000556, 0x000560, 0x000588, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x0010fa, 0x0010fc, 0x0010ff,
                        0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001d00, 0x001dbf, 0x001e00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20,
                        0x001f45, 0x001f48, 0x001f4d, 0x001f50, 0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc,
                        0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4, 0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x002071,
                        0x002071, 0x00207f, 0x00207f, 0x002090, 0x00209c, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002119, 0x00211d, 0x002124, 0x002124,
                        0x002126, 0x002126, 0x002128, 0x002128, 0x00212a, 0x00212d, 0x00212f, 0x002134, 0x002139, 0x002139, 0x00213c, 0x00213f, 0x002145, 0x002149, 0x00214e, 0x00214e, 0x002160,
                        0x00217f, 0x002183, 0x002184, 0x0024b6, 0x0024e9, 0x002c00, 0x002ce4, 0x002ceb, 0x002cee, 0x002cf2, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d,
                        0x00a640, 0x00a66d, 0x00a680, 0x00a69d, 0x00a722, 0x00a787, 0x00a78b, 0x00a78e, 0x00a790, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9, 0x00a7f2,
                        0x00a7f6, 0x00a7f8, 0x00a7fa, 0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab69, 0x00ab70, 0x00abbf, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00ff21, 0x00ff3a, 0x00ff41, 0x00ff5a,
                        0x010400, 0x01044f, 0x0104b0, 0x0104d3, 0x0104d8, 0x0104fb, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3,
                        0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010780, 0x010780, 0x010783, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010c80, 0x010cb2, 0x010cc0, 0x010cf2,
                        0x0118a0, 0x0118df, 0x016e40, 0x016e7f, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae,
                        0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e,
                        0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d6c0, 0x01d6c2, 0x01d6da, 0x01d6dc, 0x01d6fa, 0x01d6fc, 0x01d714, 0x01d716,
                        0x01d734, 0x01d736, 0x01d74e, 0x01d750, 0x01d76e, 0x01d770, 0x01d788, 0x01d78a, 0x01d7a8, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7cb, 0x01df00, 0x01df09, 0x01df0b, 0x01df1e,
                        0x01df25, 0x01df2a, 0x01e030, 0x01e06d, 0x01e900, 0x01e943, 0x01f130, 0x01f149, 0x01f150, 0x01f169, 0x01f170, 0x01f189));
    }

    private static void populateDI() {
        SET_ENCODINGS.put("DI",
                        CodePointSet.createNoDedup(0x0000ad, 0x0000ad, 0x00034f, 0x00034f, 0x00061c, 0x00061c, 0x00115f, 0x001160, 0x0017b4, 0x0017b5, 0x00180b, 0x00180f, 0x00200b, 0x00200f, 0x00202a,
                                        0x00202e, 0x002060, 0x00206f, 0x003164, 0x003164, 0x00fe00, 0x00fe0f, 0x00feff, 0x00feff, 0x00ffa0, 0x00ffa0, 0x00fff0, 0x00fff8, 0x01bca0, 0x01bca3, 0x01d173,
                                        0x01d17a, 0x0e0000, 0x0e0fff));
    }

    private static void populateDASH() {
        SET_ENCODINGS.put("Dash",
                        CodePointSet.createNoDedup(0x00002d, 0x00002d, 0x00058a, 0x00058a, 0x0005be, 0x0005be, 0x001400, 0x001400, 0x001806, 0x001806, 0x002010, 0x002015, 0x002053, 0x002053, 0x00207b,
                                        0x00207b, 0x00208b, 0x00208b, 0x002212, 0x002212, 0x002e17, 0x002e17, 0x002e1a, 0x002e1a, 0x002e3a, 0x002e3b, 0x002e40, 0x002e40, 0x002e5d, 0x002e5d, 0x00301c,
                                        0x00301c, 0x003030, 0x003030, 0x0030a0, 0x0030a0, 0x00fe31, 0x00fe32, 0x00fe58, 0x00fe58, 0x00fe63, 0x00fe63, 0x00ff0d, 0x00ff0d, 0x010ead, 0x010ead));
    }

    private static void populateDEP() {
        SET_ENCODINGS.put("Dep", CodePointSet.createNoDedup(0x000149, 0x000149, 0x000673, 0x000673, 0x000f77, 0x000f77, 0x000f79, 0x000f79, 0x0017a3, 0x0017a4, 0x00206a, 0x00206f, 0x002329, 0x00232a,
                        0x0e0001, 0x0e0001));
    }

    private static void populateDIA() {
        SET_ENCODINGS.put("Dia", CodePointSet.createNoDedup(0x00005e, 0x00005e, 0x000060, 0x000060, 0x0000a8, 0x0000a8, 0x0000af, 0x0000af, 0x0000b4, 0x0000b4, 0x0000b7, 0x0000b8, 0x0002b0, 0x00034e,
                        0x000350, 0x000357, 0x00035d, 0x000362, 0x000374, 0x000375, 0x00037a, 0x00037a, 0x000384, 0x000385, 0x000483, 0x000487, 0x000559, 0x000559, 0x000591, 0x0005a1, 0x0005a3,
                        0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4, 0x0005c4, 0x00064b, 0x000652, 0x000657, 0x000658, 0x0006df, 0x0006e0, 0x0006e5, 0x0006e6, 0x0006ea, 0x0006ec,
                        0x000730, 0x00074a, 0x0007a6, 0x0007b0, 0x0007eb, 0x0007f5, 0x000818, 0x000819, 0x000898, 0x00089f, 0x0008c9, 0x0008d2, 0x0008e3, 0x0008fe, 0x00093c, 0x00093c, 0x00094d,
                        0x00094d, 0x000951, 0x000954, 0x000971, 0x000971, 0x0009bc, 0x0009bc, 0x0009cd, 0x0009cd, 0x000a3c, 0x000a3c, 0x000a4d, 0x000a4d, 0x000abc, 0x000abc, 0x000acd, 0x000acd,
                        0x000afd, 0x000aff, 0x000b3c, 0x000b3c, 0x000b4d, 0x000b4d, 0x000b55, 0x000b55, 0x000bcd, 0x000bcd, 0x000c3c, 0x000c3c, 0x000c4d, 0x000c4d, 0x000cbc, 0x000cbc, 0x000ccd,
                        0x000ccd, 0x000d3b, 0x000d3c, 0x000d4d, 0x000d4d, 0x000dca, 0x000dca, 0x000e47, 0x000e4c, 0x000e4e, 0x000e4e, 0x000eba, 0x000eba, 0x000ec8, 0x000ecc, 0x000f18, 0x000f19,
                        0x000f35, 0x000f35, 0x000f37, 0x000f37, 0x000f39, 0x000f39, 0x000f3e, 0x000f3f, 0x000f82, 0x000f84, 0x000f86, 0x000f87, 0x000fc6, 0x000fc6, 0x001037, 0x001037, 0x001039,
                        0x00103a, 0x001063, 0x001064, 0x001069, 0x00106d, 0x001087, 0x00108d, 0x00108f, 0x00108f, 0x00109a, 0x00109b, 0x00135d, 0x00135f, 0x001714, 0x001715, 0x0017c9, 0x0017d3,
                        0x0017dd, 0x0017dd, 0x001939, 0x00193b, 0x001a75, 0x001a7c, 0x001a7f, 0x001a7f, 0x001ab0, 0x001abe, 0x001ac1, 0x001acb, 0x001b34, 0x001b34, 0x001b44, 0x001b44, 0x001b6b,
                        0x001b73, 0x001baa, 0x001bab, 0x001c36, 0x001c37, 0x001c78, 0x001c7d, 0x001cd0, 0x001ce8, 0x001ced, 0x001ced, 0x001cf4, 0x001cf4, 0x001cf7, 0x001cf9, 0x001d2c, 0x001d6a,
                        0x001dc4, 0x001dcf, 0x001df5, 0x001dff, 0x001fbd, 0x001fbd, 0x001fbf, 0x001fc1, 0x001fcd, 0x001fcf, 0x001fdd, 0x001fdf, 0x001fed, 0x001fef, 0x001ffd, 0x001ffe, 0x002cef,
                        0x002cf1, 0x002e2f, 0x002e2f, 0x00302a, 0x00302f, 0x003099, 0x00309c, 0x0030fc, 0x0030fc, 0x00a66f, 0x00a66f, 0x00a67c, 0x00a67d, 0x00a67f, 0x00a67f, 0x00a69c, 0x00a69d,
                        0x00a6f0, 0x00a6f1, 0x00a700, 0x00a721, 0x00a788, 0x00a78a, 0x00a7f8, 0x00a7f9, 0x00a8c4, 0x00a8c4, 0x00a8e0, 0x00a8f1, 0x00a92b, 0x00a92e, 0x00a953, 0x00a953, 0x00a9b3,
                        0x00a9b3, 0x00a9c0, 0x00a9c0, 0x00a9e5, 0x00a9e5, 0x00aa7b, 0x00aa7d, 0x00aabf, 0x00aac2, 0x00aaf6, 0x00aaf6, 0x00ab5b, 0x00ab5f, 0x00ab69, 0x00ab6b, 0x00abec, 0x00abed,
                        0x00fb1e, 0x00fb1e, 0x00fe20, 0x00fe2f, 0x00ff3e, 0x00ff3e, 0x00ff40, 0x00ff40, 0x00ff70, 0x00ff70, 0x00ff9e, 0x00ff9f, 0x00ffe3, 0x00ffe3, 0x0102e0, 0x0102e0, 0x010780,
                        0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010ae5, 0x010ae6, 0x010d22, 0x010d27, 0x010efd, 0x010eff, 0x010f46, 0x010f50, 0x010f82, 0x010f85, 0x011046, 0x011046,
                        0x011070, 0x011070, 0x0110b9, 0x0110ba, 0x011133, 0x011134, 0x011173, 0x011173, 0x0111c0, 0x0111c0, 0x0111ca, 0x0111cc, 0x011235, 0x011236, 0x0112e9, 0x0112ea, 0x01133c,
                        0x01133c, 0x01134d, 0x01134d, 0x011366, 0x01136c, 0x011370, 0x011374, 0x011442, 0x011442, 0x011446, 0x011446, 0x0114c2, 0x0114c3, 0x0115bf, 0x0115c0, 0x01163f, 0x01163f,
                        0x0116b6, 0x0116b7, 0x01172b, 0x01172b, 0x011839, 0x01183a, 0x01193d, 0x01193e, 0x011943, 0x011943, 0x0119e0, 0x0119e0, 0x011a34, 0x011a34, 0x011a47, 0x011a47, 0x011a99,
                        0x011a99, 0x011c3f, 0x011c3f, 0x011d42, 0x011d42, 0x011d44, 0x011d45, 0x011d97, 0x011d97, 0x013447, 0x013455, 0x016af0, 0x016af4, 0x016b30, 0x016b36, 0x016f8f, 0x016f9f,
                        0x016ff0, 0x016ff1, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01cf00, 0x01cf2d, 0x01cf30, 0x01cf46, 0x01d167, 0x01d169, 0x01d16d, 0x01d172, 0x01d17b,
                        0x01d182, 0x01d185, 0x01d18b, 0x01d1aa, 0x01d1ad, 0x01e030, 0x01e06d, 0x01e130, 0x01e136, 0x01e2ae, 0x01e2ae, 0x01e2ec, 0x01e2ef, 0x01e8d0, 0x01e8d6, 0x01e944, 0x01e946,
                        0x01e948, 0x01e94a));
    }

    private static void populateEBASE() {
        SET_ENCODINGS.put("EBase", CodePointSet.createNoDedup(0x00261d, 0x00261d, 0x0026f9, 0x0026f9, 0x00270a, 0x00270d, 0x01f385, 0x01f385, 0x01f3c2, 0x01f3c4, 0x01f3c7, 0x01f3c7, 0x01f3ca,
                        0x01f3cc, 0x01f442, 0x01f443, 0x01f446, 0x01f450, 0x01f466, 0x01f478, 0x01f47c, 0x01f47c, 0x01f481, 0x01f483, 0x01f485, 0x01f487, 0x01f48f, 0x01f48f, 0x01f491, 0x01f491,
                        0x01f4aa, 0x01f4aa, 0x01f574, 0x01f575, 0x01f57a, 0x01f57a, 0x01f590, 0x01f590, 0x01f595, 0x01f596, 0x01f645, 0x01f647, 0x01f64b, 0x01f64f, 0x01f6a3, 0x01f6a3, 0x01f6b4,
                        0x01f6b6, 0x01f6c0, 0x01f6c0, 0x01f6cc, 0x01f6cc, 0x01f90c, 0x01f90c, 0x01f90f, 0x01f90f, 0x01f918, 0x01f91f, 0x01f926, 0x01f926, 0x01f930, 0x01f939, 0x01f93c, 0x01f93e,
                        0x01f977, 0x01f977, 0x01f9b5, 0x01f9b6, 0x01f9b8, 0x01f9b9, 0x01f9bb, 0x01f9bb, 0x01f9cd, 0x01f9cf, 0x01f9d1, 0x01f9dd, 0x01fac3, 0x01fac5, 0x01faf0, 0x01faf8));
    }

    private static void populateECOMP() {
        SET_ENCODINGS.put("EComp", CodePointSet.createNoDedup(0x000023, 0x000023, 0x00002a, 0x00002a, 0x000030, 0x000039, 0x00200d, 0x00200d, 0x0020e3, 0x0020e3, 0x00fe0f, 0x00fe0f, 0x01f1e6,
                        0x01f1ff, 0x01f3fb, 0x01f3ff, 0x01f9b0, 0x01f9b3, 0x0e0020, 0x0e007f));
    }

    private static void populateEMOD() {
        SET_ENCODINGS.put("EMod", CodePointSet.createNoDedup(0x01f3fb, 0x01f3ff));
    }

    private static void populateEPRES() {
        SET_ENCODINGS.put("EPres", CodePointSet.createNoDedup(0x00231a, 0x00231b, 0x0023e9, 0x0023ec, 0x0023f0, 0x0023f0, 0x0023f3, 0x0023f3, 0x0025fd, 0x0025fe, 0x002614, 0x002615, 0x002648,
                        0x002653, 0x00267f, 0x00267f, 0x002693, 0x002693, 0x0026a1, 0x0026a1, 0x0026aa, 0x0026ab, 0x0026bd, 0x0026be, 0x0026c4, 0x0026c5, 0x0026ce, 0x0026ce, 0x0026d4, 0x0026d4,
                        0x0026ea, 0x0026ea, 0x0026f2, 0x0026f3, 0x0026f5, 0x0026f5, 0x0026fa, 0x0026fa, 0x0026fd, 0x0026fd, 0x002705, 0x002705, 0x00270a, 0x00270b, 0x002728, 0x002728, 0x00274c,
                        0x00274c, 0x00274e, 0x00274e, 0x002753, 0x002755, 0x002757, 0x002757, 0x002795, 0x002797, 0x0027b0, 0x0027b0, 0x0027bf, 0x0027bf, 0x002b1b, 0x002b1c, 0x002b50, 0x002b50,
                        0x002b55, 0x002b55, 0x01f004, 0x01f004, 0x01f0cf, 0x01f0cf, 0x01f18e, 0x01f18e, 0x01f191, 0x01f19a, 0x01f1e6, 0x01f1ff, 0x01f201, 0x01f201, 0x01f21a, 0x01f21a, 0x01f22f,
                        0x01f22f, 0x01f232, 0x01f236, 0x01f238, 0x01f23a, 0x01f250, 0x01f251, 0x01f300, 0x01f320, 0x01f32d, 0x01f335, 0x01f337, 0x01f37c, 0x01f37e, 0x01f393, 0x01f3a0, 0x01f3ca,
                        0x01f3cf, 0x01f3d3, 0x01f3e0, 0x01f3f0, 0x01f3f4, 0x01f3f4, 0x01f3f8, 0x01f43e, 0x01f440, 0x01f440, 0x01f442, 0x01f4fc, 0x01f4ff, 0x01f53d, 0x01f54b, 0x01f54e, 0x01f550,
                        0x01f567, 0x01f57a, 0x01f57a, 0x01f595, 0x01f596, 0x01f5a4, 0x01f5a4, 0x01f5fb, 0x01f64f, 0x01f680, 0x01f6c5, 0x01f6cc, 0x01f6cc, 0x01f6d0, 0x01f6d2, 0x01f6d5, 0x01f6d7,
                        0x01f6dc, 0x01f6df, 0x01f6eb, 0x01f6ec, 0x01f6f4, 0x01f6fc, 0x01f7e0, 0x01f7eb, 0x01f7f0, 0x01f7f0, 0x01f90c, 0x01f93a, 0x01f93c, 0x01f945, 0x01f947, 0x01f9ff, 0x01fa70,
                        0x01fa7c, 0x01fa80, 0x01fa88, 0x01fa90, 0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb, 0x01fae0, 0x01fae8, 0x01faf0, 0x01faf8));
    }

    private static void populateEMOJI() {
        SET_ENCODINGS.put("Emoji", CodePointSet.createNoDedup(0x000023, 0x000023, 0x00002a, 0x00002a, 0x000030, 0x000039, 0x0000a9, 0x0000a9, 0x0000ae, 0x0000ae, 0x00203c, 0x00203c, 0x002049,
                        0x002049, 0x002122, 0x002122, 0x002139, 0x002139, 0x002194, 0x002199, 0x0021a9, 0x0021aa, 0x00231a, 0x00231b, 0x002328, 0x002328, 0x0023cf, 0x0023cf, 0x0023e9, 0x0023f3,
                        0x0023f8, 0x0023fa, 0x0024c2, 0x0024c2, 0x0025aa, 0x0025ab, 0x0025b6, 0x0025b6, 0x0025c0, 0x0025c0, 0x0025fb, 0x0025fe, 0x002600, 0x002604, 0x00260e, 0x00260e, 0x002611,
                        0x002611, 0x002614, 0x002615, 0x002618, 0x002618, 0x00261d, 0x00261d, 0x002620, 0x002620, 0x002622, 0x002623, 0x002626, 0x002626, 0x00262a, 0x00262a, 0x00262e, 0x00262f,
                        0x002638, 0x00263a, 0x002640, 0x002640, 0x002642, 0x002642, 0x002648, 0x002653, 0x00265f, 0x002660, 0x002663, 0x002663, 0x002665, 0x002666, 0x002668, 0x002668, 0x00267b,
                        0x00267b, 0x00267e, 0x00267f, 0x002692, 0x002697, 0x002699, 0x002699, 0x00269b, 0x00269c, 0x0026a0, 0x0026a1, 0x0026a7, 0x0026a7, 0x0026aa, 0x0026ab, 0x0026b0, 0x0026b1,
                        0x0026bd, 0x0026be, 0x0026c4, 0x0026c5, 0x0026c8, 0x0026c8, 0x0026ce, 0x0026cf, 0x0026d1, 0x0026d1, 0x0026d3, 0x0026d4, 0x0026e9, 0x0026ea, 0x0026f0, 0x0026f5, 0x0026f7,
                        0x0026fa, 0x0026fd, 0x0026fd, 0x002702, 0x002702, 0x002705, 0x002705, 0x002708, 0x00270d, 0x00270f, 0x00270f, 0x002712, 0x002712, 0x002714, 0x002714, 0x002716, 0x002716,
                        0x00271d, 0x00271d, 0x002721, 0x002721, 0x002728, 0x002728, 0x002733, 0x002734, 0x002744, 0x002744, 0x002747, 0x002747, 0x00274c, 0x00274c, 0x00274e, 0x00274e, 0x002753,
                        0x002755, 0x002757, 0x002757, 0x002763, 0x002764, 0x002795, 0x002797, 0x0027a1, 0x0027a1, 0x0027b0, 0x0027b0, 0x0027bf, 0x0027bf, 0x002934, 0x002935, 0x002b05, 0x002b07,
                        0x002b1b, 0x002b1c, 0x002b50, 0x002b50, 0x002b55, 0x002b55, 0x003030, 0x003030, 0x00303d, 0x00303d, 0x003297, 0x003297, 0x003299, 0x003299, 0x01f004, 0x01f004, 0x01f0cf,
                        0x01f0cf, 0x01f170, 0x01f171, 0x01f17e, 0x01f17f, 0x01f18e, 0x01f18e, 0x01f191, 0x01f19a, 0x01f1e6, 0x01f1ff, 0x01f201, 0x01f202, 0x01f21a, 0x01f21a, 0x01f22f, 0x01f22f,
                        0x01f232, 0x01f23a, 0x01f250, 0x01f251, 0x01f300, 0x01f321, 0x01f324, 0x01f393, 0x01f396, 0x01f397, 0x01f399, 0x01f39b, 0x01f39e, 0x01f3f0, 0x01f3f3, 0x01f3f5, 0x01f3f7,
                        0x01f4fd, 0x01f4ff, 0x01f53d, 0x01f549, 0x01f54e, 0x01f550, 0x01f567, 0x01f56f, 0x01f570, 0x01f573, 0x01f57a, 0x01f587, 0x01f587, 0x01f58a, 0x01f58d, 0x01f590, 0x01f590,
                        0x01f595, 0x01f596, 0x01f5a4, 0x01f5a5, 0x01f5a8, 0x01f5a8, 0x01f5b1, 0x01f5b2, 0x01f5bc, 0x01f5bc, 0x01f5c2, 0x01f5c4, 0x01f5d1, 0x01f5d3, 0x01f5dc, 0x01f5de, 0x01f5e1,
                        0x01f5e1, 0x01f5e3, 0x01f5e3, 0x01f5e8, 0x01f5e8, 0x01f5ef, 0x01f5ef, 0x01f5f3, 0x01f5f3, 0x01f5fa, 0x01f64f, 0x01f680, 0x01f6c5, 0x01f6cb, 0x01f6d2, 0x01f6d5, 0x01f6d7,
                        0x01f6dc, 0x01f6e5, 0x01f6e9, 0x01f6e9, 0x01f6eb, 0x01f6ec, 0x01f6f0, 0x01f6f0, 0x01f6f3, 0x01f6fc, 0x01f7e0, 0x01f7eb, 0x01f7f0, 0x01f7f0, 0x01f90c, 0x01f93a, 0x01f93c,
                        0x01f945, 0x01f947, 0x01f9ff, 0x01fa70, 0x01fa7c, 0x01fa80, 0x01fa88, 0x01fa90, 0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb, 0x01fae0, 0x01fae8, 0x01faf0, 0x01faf8));
    }

    private static void populateEXT() {
        SET_ENCODINGS.put("Ext",
                        CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x0002d0, 0x0002d1, 0x000640, 0x000640, 0x0007fa, 0x0007fa, 0x000b55, 0x000b55, 0x000e46, 0x000e46, 0x000ec6, 0x000ec6, 0x00180a,
                                        0x00180a, 0x001843, 0x001843, 0x001aa7, 0x001aa7, 0x001c36, 0x001c36, 0x001c7b, 0x001c7b, 0x003005, 0x003005, 0x003031, 0x003035, 0x00309d, 0x00309e, 0x0030fc,
                                        0x0030fe, 0x00a015, 0x00a015, 0x00a60c, 0x00a60c, 0x00a9cf, 0x00a9cf, 0x00a9e6, 0x00a9e6, 0x00aa70, 0x00aa70, 0x00aadd, 0x00aadd, 0x00aaf3, 0x00aaf4, 0x00ff70,
                                        0x00ff70, 0x010781, 0x010782, 0x01135d, 0x01135d, 0x0115c6, 0x0115c8, 0x011a98, 0x011a98, 0x016b42, 0x016b43, 0x016fe0, 0x016fe1, 0x016fe3, 0x016fe3, 0x01e13c,
                                        0x01e13d, 0x01e944, 0x01e946));
    }

    private static void populateEXTPICT() {
        SET_ENCODINGS.put("ExtPict",
                        CodePointSet.createNoDedup(0x0000a9, 0x0000a9, 0x0000ae, 0x0000ae, 0x00203c, 0x00203c, 0x002049, 0x002049, 0x002122, 0x002122, 0x002139, 0x002139, 0x002194, 0x002199, 0x0021a9,
                                        0x0021aa, 0x00231a, 0x00231b, 0x002328, 0x002328, 0x002388, 0x002388, 0x0023cf, 0x0023cf, 0x0023e9, 0x0023f3, 0x0023f8, 0x0023fa, 0x0024c2, 0x0024c2, 0x0025aa,
                                        0x0025ab, 0x0025b6, 0x0025b6, 0x0025c0, 0x0025c0, 0x0025fb, 0x0025fe, 0x002600, 0x002605, 0x002607, 0x002612, 0x002614, 0x002685, 0x002690, 0x002705, 0x002708,
                                        0x002712, 0x002714, 0x002714, 0x002716, 0x002716, 0x00271d, 0x00271d, 0x002721, 0x002721, 0x002728, 0x002728, 0x002733, 0x002734, 0x002744, 0x002744, 0x002747,
                                        0x002747, 0x00274c, 0x00274c, 0x00274e, 0x00274e, 0x002753, 0x002755, 0x002757, 0x002757, 0x002763, 0x002767, 0x002795, 0x002797, 0x0027a1, 0x0027a1, 0x0027b0,
                                        0x0027b0, 0x0027bf, 0x0027bf, 0x002934, 0x002935, 0x002b05, 0x002b07, 0x002b1b, 0x002b1c, 0x002b50, 0x002b50, 0x002b55, 0x002b55, 0x003030, 0x003030, 0x00303d,
                                        0x00303d, 0x003297, 0x003297, 0x003299, 0x003299, 0x01f000, 0x01f0ff, 0x01f10d, 0x01f10f, 0x01f12f, 0x01f12f, 0x01f16c, 0x01f171, 0x01f17e, 0x01f17f, 0x01f18e,
                                        0x01f18e, 0x01f191, 0x01f19a, 0x01f1ad, 0x01f1e5, 0x01f201, 0x01f20f, 0x01f21a, 0x01f21a, 0x01f22f, 0x01f22f, 0x01f232, 0x01f23a, 0x01f23c, 0x01f23f, 0x01f249,
                                        0x01f3fa, 0x01f400, 0x01f53d, 0x01f546, 0x01f64f, 0x01f680, 0x01f6ff, 0x01f774, 0x01f77f, 0x01f7d5, 0x01f7ff, 0x01f80c, 0x01f80f, 0x01f848, 0x01f84f, 0x01f85a,
                                        0x01f85f, 0x01f888, 0x01f88f, 0x01f8ae, 0x01f8ff, 0x01f90c, 0x01f93a, 0x01f93c, 0x01f945, 0x01f947, 0x01faff, 0x01fc00, 0x01fffd));
    }

    private static void populateGR_BASE() {
        SET_ENCODINGS.put("Gr_Base", CodePointSet.createNoDedup(0x000020, 0x00007e, 0x0000a0, 0x0000ac, 0x0000ae, 0x0002ff, 0x000370, 0x000377, 0x00037a, 0x00037f, 0x000384, 0x00038a, 0x00038c,
                        0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x000482, 0x00048a, 0x00052f, 0x000531, 0x000556, 0x000559, 0x00058a, 0x00058d, 0x00058f, 0x0005be, 0x0005be, 0x0005c0, 0x0005c0,
                        0x0005c3, 0x0005c3, 0x0005c6, 0x0005c6, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f4, 0x000606, 0x00060f, 0x00061b, 0x00061b, 0x00061d, 0x00064a, 0x000660, 0x00066f, 0x000671,
                        0x0006d5, 0x0006de, 0x0006de, 0x0006e5, 0x0006e6, 0x0006e9, 0x0006e9, 0x0006ee, 0x00070d, 0x000710, 0x000710, 0x000712, 0x00072f, 0x00074d, 0x0007a5, 0x0007b1, 0x0007b1,
                        0x0007c0, 0x0007ea, 0x0007f4, 0x0007fa, 0x0007fe, 0x000815, 0x00081a, 0x00081a, 0x000824, 0x000824, 0x000828, 0x000828, 0x000830, 0x00083e, 0x000840, 0x000858, 0x00085e,
                        0x00085e, 0x000860, 0x00086a, 0x000870, 0x00088e, 0x0008a0, 0x0008c9, 0x000903, 0x000939, 0x00093b, 0x00093b, 0x00093d, 0x000940, 0x000949, 0x00094c, 0x00094e, 0x000950,
                        0x000958, 0x000961, 0x000964, 0x000980, 0x000982, 0x000983, 0x000985, 0x00098c, 0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6,
                        0x0009b9, 0x0009bd, 0x0009bd, 0x0009bf, 0x0009c0, 0x0009c7, 0x0009c8, 0x0009cb, 0x0009cc, 0x0009ce, 0x0009ce, 0x0009dc, 0x0009dd, 0x0009df, 0x0009e1, 0x0009e6, 0x0009fd,
                        0x000a03, 0x000a03, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36, 0x000a38, 0x000a39, 0x000a3e,
                        0x000a40, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a66, 0x000a6f, 0x000a72, 0x000a74, 0x000a76, 0x000a76, 0x000a83, 0x000a83, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91,
                        0x000a93, 0x000aa8, 0x000aaa, 0x000ab0, 0x000ab2, 0x000ab3, 0x000ab5, 0x000ab9, 0x000abd, 0x000ac0, 0x000ac9, 0x000ac9, 0x000acb, 0x000acc, 0x000ad0, 0x000ad0, 0x000ae0,
                        0x000ae1, 0x000ae6, 0x000af1, 0x000af9, 0x000af9, 0x000b02, 0x000b03, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28, 0x000b2a, 0x000b30, 0x000b32, 0x000b33,
                        0x000b35, 0x000b39, 0x000b3d, 0x000b3d, 0x000b40, 0x000b40, 0x000b47, 0x000b48, 0x000b4b, 0x000b4c, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b61, 0x000b66, 0x000b77, 0x000b83,
                        0x000b83, 0x000b85, 0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa,
                        0x000bae, 0x000bb9, 0x000bbf, 0x000bbf, 0x000bc1, 0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcc, 0x000bd0, 0x000bd0, 0x000be6, 0x000bfa, 0x000c01, 0x000c03, 0x000c05,
                        0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3d, 0x000c3d, 0x000c41, 0x000c44, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d, 0x000c60, 0x000c61,
                        0x000c66, 0x000c6f, 0x000c77, 0x000c80, 0x000c82, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbd, 0x000cbe, 0x000cc0,
                        0x000cc1, 0x000cc3, 0x000cc4, 0x000cc7, 0x000cc8, 0x000cca, 0x000ccb, 0x000cdd, 0x000cde, 0x000ce0, 0x000ce1, 0x000ce6, 0x000cef, 0x000cf1, 0x000cf3, 0x000d02, 0x000d0c,
                        0x000d0e, 0x000d10, 0x000d12, 0x000d3a, 0x000d3d, 0x000d3d, 0x000d3f, 0x000d40, 0x000d46, 0x000d48, 0x000d4a, 0x000d4c, 0x000d4e, 0x000d4f, 0x000d54, 0x000d56, 0x000d58,
                        0x000d61, 0x000d66, 0x000d7f, 0x000d82, 0x000d83, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000dd0, 0x000dd1,
                        0x000dd8, 0x000dde, 0x000de6, 0x000def, 0x000df2, 0x000df4, 0x000e01, 0x000e30, 0x000e32, 0x000e33, 0x000e3f, 0x000e46, 0x000e4f, 0x000e5b, 0x000e81, 0x000e82, 0x000e84,
                        0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000eb0, 0x000eb2, 0x000eb3, 0x000ebd, 0x000ebd, 0x000ec0, 0x000ec4, 0x000ec6, 0x000ec6,
                        0x000ed0, 0x000ed9, 0x000edc, 0x000edf, 0x000f00, 0x000f17, 0x000f1a, 0x000f34, 0x000f36, 0x000f36, 0x000f38, 0x000f38, 0x000f3a, 0x000f47, 0x000f49, 0x000f6c, 0x000f7f,
                        0x000f7f, 0x000f85, 0x000f85, 0x000f88, 0x000f8c, 0x000fbe, 0x000fc5, 0x000fc7, 0x000fcc, 0x000fce, 0x000fda, 0x001000, 0x00102c, 0x001031, 0x001031, 0x001038, 0x001038,
                        0x00103b, 0x00103c, 0x00103f, 0x001057, 0x00105a, 0x00105d, 0x001061, 0x001070, 0x001075, 0x001081, 0x001083, 0x001084, 0x001087, 0x00108c, 0x00108e, 0x00109c, 0x00109e,
                        0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d, 0x001260, 0x001288,
                        0x00128a, 0x00128d, 0x001290, 0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8, 0x001310, 0x001312,
                        0x001315, 0x001318, 0x00135a, 0x001360, 0x00137c, 0x001380, 0x001399, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001400, 0x00169c, 0x0016a0, 0x0016f8, 0x001700, 0x001711,
                        0x001715, 0x001715, 0x00171f, 0x001731, 0x001734, 0x001736, 0x001740, 0x001751, 0x001760, 0x00176c, 0x00176e, 0x001770, 0x001780, 0x0017b3, 0x0017b6, 0x0017b6, 0x0017be,
                        0x0017c5, 0x0017c7, 0x0017c8, 0x0017d4, 0x0017dc, 0x0017e0, 0x0017e9, 0x0017f0, 0x0017f9, 0x001800, 0x00180a, 0x001810, 0x001819, 0x001820, 0x001878, 0x001880, 0x001884,
                        0x001887, 0x0018a8, 0x0018aa, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900, 0x00191e, 0x001923, 0x001926, 0x001929, 0x00192b, 0x001930, 0x001931, 0x001933, 0x001938, 0x001940,
                        0x001940, 0x001944, 0x00196d, 0x001970, 0x001974, 0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x0019d0, 0x0019da, 0x0019de, 0x001a16, 0x001a19, 0x001a1a, 0x001a1e, 0x001a55,
                        0x001a57, 0x001a57, 0x001a61, 0x001a61, 0x001a63, 0x001a64, 0x001a6d, 0x001a72, 0x001a80, 0x001a89, 0x001a90, 0x001a99, 0x001aa0, 0x001aad, 0x001b04, 0x001b33, 0x001b3b,
                        0x001b3b, 0x001b3d, 0x001b41, 0x001b43, 0x001b4c, 0x001b50, 0x001b6a, 0x001b74, 0x001b7e, 0x001b82, 0x001ba1, 0x001ba6, 0x001ba7, 0x001baa, 0x001baa, 0x001bae, 0x001be5,
                        0x001be7, 0x001be7, 0x001bea, 0x001bec, 0x001bee, 0x001bee, 0x001bf2, 0x001bf3, 0x001bfc, 0x001c2b, 0x001c34, 0x001c35, 0x001c3b, 0x001c49, 0x001c4d, 0x001c88, 0x001c90,
                        0x001cba, 0x001cbd, 0x001cc7, 0x001cd3, 0x001cd3, 0x001ce1, 0x001ce1, 0x001ce9, 0x001cec, 0x001cee, 0x001cf3, 0x001cf5, 0x001cf7, 0x001cfa, 0x001cfa, 0x001d00, 0x001dbf,
                        0x001e00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50, 0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f,
                        0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fc4, 0x001fc6, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fdd, 0x001fef, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffe, 0x002000, 0x00200a,
                        0x002010, 0x002027, 0x00202f, 0x00205f, 0x002070, 0x002071, 0x002074, 0x00208e, 0x002090, 0x00209c, 0x0020a0, 0x0020c0, 0x002100, 0x00218b, 0x002190, 0x002426, 0x002440,
                        0x00244a, 0x002460, 0x002b73, 0x002b76, 0x002b95, 0x002b97, 0x002cee, 0x002cf2, 0x002cf3, 0x002cf9, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x002d30, 0x002d67,
                        0x002d6f, 0x002d70, 0x002d80, 0x002d96, 0x002da0, 0x002da6, 0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe, 0x002dc0, 0x002dc6, 0x002dc8, 0x002dce, 0x002dd0,
                        0x002dd6, 0x002dd8, 0x002dde, 0x002e00, 0x002e5d, 0x002e80, 0x002e99, 0x002e9b, 0x002ef3, 0x002f00, 0x002fd5, 0x002ff0, 0x002ffb, 0x003000, 0x003029, 0x003030, 0x00303f,
                        0x003041, 0x003096, 0x00309b, 0x0030ff, 0x003105, 0x00312f, 0x003131, 0x00318e, 0x003190, 0x0031e3, 0x0031f0, 0x00321e, 0x003220, 0x00a48c, 0x00a490, 0x00a4c6, 0x00a4d0,
                        0x00a62b, 0x00a640, 0x00a66e, 0x00a673, 0x00a673, 0x00a67e, 0x00a69d, 0x00a6a0, 0x00a6ef, 0x00a6f2, 0x00a6f7, 0x00a700, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3,
                        0x00a7d5, 0x00a7d9, 0x00a7f2, 0x00a801, 0x00a803, 0x00a805, 0x00a807, 0x00a80a, 0x00a80c, 0x00a824, 0x00a827, 0x00a82b, 0x00a830, 0x00a839, 0x00a840, 0x00a877, 0x00a880,
                        0x00a8c3, 0x00a8ce, 0x00a8d9, 0x00a8f2, 0x00a8fe, 0x00a900, 0x00a925, 0x00a92e, 0x00a946, 0x00a952, 0x00a953, 0x00a95f, 0x00a97c, 0x00a983, 0x00a9b2, 0x00a9b4, 0x00a9b5,
                        0x00a9ba, 0x00a9bb, 0x00a9be, 0x00a9cd, 0x00a9cf, 0x00a9d9, 0x00a9de, 0x00a9e4, 0x00a9e6, 0x00a9fe, 0x00aa00, 0x00aa28, 0x00aa2f, 0x00aa30, 0x00aa33, 0x00aa34, 0x00aa40,
                        0x00aa42, 0x00aa44, 0x00aa4b, 0x00aa4d, 0x00aa4d, 0x00aa50, 0x00aa59, 0x00aa5c, 0x00aa7b, 0x00aa7d, 0x00aaaf, 0x00aab1, 0x00aab1, 0x00aab5, 0x00aab6, 0x00aab9, 0x00aabd,
                        0x00aac0, 0x00aac0, 0x00aac2, 0x00aac2, 0x00aadb, 0x00aaeb, 0x00aaee, 0x00aaf5, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20, 0x00ab26, 0x00ab28,
                        0x00ab2e, 0x00ab30, 0x00ab6b, 0x00ab70, 0x00abe4, 0x00abe6, 0x00abe7, 0x00abe9, 0x00abec, 0x00abf0, 0x00abf9, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb,
                        0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb1d, 0x00fb1f, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40,
                        0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fbc2, 0x00fbd3, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdcf, 0x00fdcf, 0x00fdf0, 0x00fdff, 0x00fe10, 0x00fe19, 0x00fe30, 0x00fe52,
                        0x00fe54, 0x00fe66, 0x00fe68, 0x00fe6b, 0x00fe70, 0x00fe74, 0x00fe76, 0x00fefc, 0x00ff01, 0x00ff9d, 0x00ffa0, 0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2,
                        0x00ffd7, 0x00ffda, 0x00ffdc, 0x00ffe0, 0x00ffe6, 0x00ffe8, 0x00ffee, 0x00fffc, 0x00fffd, 0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d,
                        0x01003f, 0x01004d, 0x010050, 0x01005d, 0x010080, 0x0100fa, 0x010100, 0x010102, 0x010107, 0x010133, 0x010137, 0x01018e, 0x010190, 0x01019c, 0x0101a0, 0x0101a0, 0x0101d0,
                        0x0101fc, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x0102e1, 0x0102fb, 0x010300, 0x010323, 0x01032d, 0x01034a, 0x010350, 0x010375, 0x010380, 0x01039d, 0x01039f, 0x0103c3,
                        0x0103c8, 0x0103d5, 0x010400, 0x01049d, 0x0104a0, 0x0104a9, 0x0104b0, 0x0104d3, 0x0104d8, 0x0104fb, 0x010500, 0x010527, 0x010530, 0x010563, 0x01056f, 0x01057a, 0x01057c,
                        0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010600, 0x010736, 0x010740, 0x010755,
                        0x010760, 0x010767, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010800, 0x010805, 0x010808, 0x010808, 0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c,
                        0x01083c, 0x01083f, 0x010855, 0x010857, 0x01089e, 0x0108a7, 0x0108af, 0x0108e0, 0x0108f2, 0x0108f4, 0x0108f5, 0x0108fb, 0x01091b, 0x01091f, 0x010939, 0x01093f, 0x01093f,
                        0x010980, 0x0109b7, 0x0109bc, 0x0109cf, 0x0109d2, 0x010a00, 0x010a10, 0x010a13, 0x010a15, 0x010a17, 0x010a19, 0x010a35, 0x010a40, 0x010a48, 0x010a50, 0x010a58, 0x010a60,
                        0x010a9f, 0x010ac0, 0x010ae4, 0x010aeb, 0x010af6, 0x010b00, 0x010b35, 0x010b39, 0x010b55, 0x010b58, 0x010b72, 0x010b78, 0x010b91, 0x010b99, 0x010b9c, 0x010ba9, 0x010baf,
                        0x010c00, 0x010c48, 0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x010cfa, 0x010d23, 0x010d30, 0x010d39, 0x010e60, 0x010e7e, 0x010e80, 0x010ea9, 0x010ead, 0x010ead, 0x010eb0,
                        0x010eb1, 0x010f00, 0x010f27, 0x010f30, 0x010f45, 0x010f51, 0x010f59, 0x010f70, 0x010f81, 0x010f86, 0x010f89, 0x010fb0, 0x010fcb, 0x010fe0, 0x010ff6, 0x011000, 0x011000,
                        0x011002, 0x011037, 0x011047, 0x01104d, 0x011052, 0x01106f, 0x011071, 0x011072, 0x011075, 0x011075, 0x011082, 0x0110b2, 0x0110b7, 0x0110b8, 0x0110bb, 0x0110bc, 0x0110be,
                        0x0110c1, 0x0110d0, 0x0110e8, 0x0110f0, 0x0110f9, 0x011103, 0x011126, 0x01112c, 0x01112c, 0x011136, 0x011147, 0x011150, 0x011172, 0x011174, 0x011176, 0x011182, 0x0111b5,
                        0x0111bf, 0x0111c8, 0x0111cd, 0x0111ce, 0x0111d0, 0x0111df, 0x0111e1, 0x0111f4, 0x011200, 0x011211, 0x011213, 0x01122e, 0x011232, 0x011233, 0x011235, 0x011235, 0x011238,
                        0x01123d, 0x01123f, 0x011240, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a9, 0x0112b0, 0x0112de, 0x0112e0, 0x0112e2,
                        0x0112f0, 0x0112f9, 0x011302, 0x011303, 0x011305, 0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133d,
                        0x01133d, 0x01133f, 0x01133f, 0x011341, 0x011344, 0x011347, 0x011348, 0x01134b, 0x01134d, 0x011350, 0x011350, 0x01135d, 0x011363, 0x011400, 0x011437, 0x011440, 0x011441,
                        0x011445, 0x011445, 0x011447, 0x01145b, 0x01145d, 0x01145d, 0x01145f, 0x011461, 0x011480, 0x0114af, 0x0114b1, 0x0114b2, 0x0114b9, 0x0114b9, 0x0114bb, 0x0114bc, 0x0114be,
                        0x0114be, 0x0114c1, 0x0114c1, 0x0114c4, 0x0114c7, 0x0114d0, 0x0114d9, 0x011580, 0x0115ae, 0x0115b0, 0x0115b1, 0x0115b8, 0x0115bb, 0x0115be, 0x0115be, 0x0115c1, 0x0115db,
                        0x011600, 0x011632, 0x01163b, 0x01163c, 0x01163e, 0x01163e, 0x011641, 0x011644, 0x011650, 0x011659, 0x011660, 0x01166c, 0x011680, 0x0116aa, 0x0116ac, 0x0116ac, 0x0116ae,
                        0x0116af, 0x0116b6, 0x0116b6, 0x0116b8, 0x0116b9, 0x0116c0, 0x0116c9, 0x011700, 0x01171a, 0x011720, 0x011721, 0x011726, 0x011726, 0x011730, 0x011746, 0x011800, 0x01182e,
                        0x011838, 0x011838, 0x01183b, 0x01183b, 0x0118a0, 0x0118f2, 0x0118ff, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x01192f, 0x011931,
                        0x011935, 0x011937, 0x011938, 0x01193d, 0x01193d, 0x01193f, 0x011942, 0x011944, 0x011946, 0x011950, 0x011959, 0x0119a0, 0x0119a7, 0x0119aa, 0x0119d3, 0x0119dc, 0x0119df,
                        0x0119e1, 0x0119e4, 0x011a00, 0x011a00, 0x011a0b, 0x011a32, 0x011a39, 0x011a3a, 0x011a3f, 0x011a46, 0x011a50, 0x011a50, 0x011a57, 0x011a58, 0x011a5c, 0x011a89, 0x011a97,
                        0x011a97, 0x011a9a, 0x011aa2, 0x011ab0, 0x011af8, 0x011b00, 0x011b09, 0x011c00, 0x011c08, 0x011c0a, 0x011c2f, 0x011c3e, 0x011c3e, 0x011c40, 0x011c45, 0x011c50, 0x011c6c,
                        0x011c70, 0x011c8f, 0x011ca9, 0x011ca9, 0x011cb1, 0x011cb1, 0x011cb4, 0x011cb4, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d30, 0x011d46, 0x011d46, 0x011d50,
                        0x011d59, 0x011d60, 0x011d65, 0x011d67, 0x011d68, 0x011d6a, 0x011d8e, 0x011d93, 0x011d94, 0x011d96, 0x011d96, 0x011d98, 0x011d98, 0x011da0, 0x011da9, 0x011ee0, 0x011ef2,
                        0x011ef5, 0x011ef8, 0x011f02, 0x011f10, 0x011f12, 0x011f35, 0x011f3e, 0x011f3f, 0x011f41, 0x011f41, 0x011f43, 0x011f59, 0x011fb0, 0x011fb0, 0x011fc0, 0x011ff1, 0x011fff,
                        0x012399, 0x012400, 0x01246e, 0x012470, 0x012474, 0x012480, 0x012543, 0x012f90, 0x012ff2, 0x013000, 0x01342f, 0x013441, 0x013446, 0x014400, 0x014646, 0x016800, 0x016a38,
                        0x016a40, 0x016a5e, 0x016a60, 0x016a69, 0x016a6e, 0x016abe, 0x016ac0, 0x016ac9, 0x016ad0, 0x016aed, 0x016af5, 0x016af5, 0x016b00, 0x016b2f, 0x016b37, 0x016b45, 0x016b50,
                        0x016b59, 0x016b5b, 0x016b61, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f, 0x016e40, 0x016e9a, 0x016f00, 0x016f4a, 0x016f50, 0x016f87, 0x016f93, 0x016f9f, 0x016fe0, 0x016fe3,
                        0x016ff0, 0x016ff1, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01b000, 0x01b122, 0x01b132,
                        0x01b132, 0x01b150, 0x01b152, 0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb, 0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88, 0x01bc90, 0x01bc99,
                        0x01bc9c, 0x01bc9c, 0x01bc9f, 0x01bc9f, 0x01cf50, 0x01cfc3, 0x01d000, 0x01d0f5, 0x01d100, 0x01d126, 0x01d129, 0x01d164, 0x01d166, 0x01d166, 0x01d16a, 0x01d16d, 0x01d183,
                        0x01d184, 0x01d18c, 0x01d1a9, 0x01d1ae, 0x01d1ea, 0x01d200, 0x01d241, 0x01d245, 0x01d245, 0x01d2c0, 0x01d2d3, 0x01d2e0, 0x01d2f3, 0x01d300, 0x01d356, 0x01d360, 0x01d378,
                        0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd,
                        0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546,
                        0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d7cb, 0x01d7ce, 0x01d9ff, 0x01da37, 0x01da3a, 0x01da6d, 0x01da74, 0x01da76, 0x01da83, 0x01da85, 0x01da8b, 0x01df00,
                        0x01df1e, 0x01df25, 0x01df2a, 0x01e030, 0x01e06d, 0x01e100, 0x01e12c, 0x01e137, 0x01e13d, 0x01e140, 0x01e149, 0x01e14e, 0x01e14f, 0x01e290, 0x01e2ad, 0x01e2c0, 0x01e2eb,
                        0x01e2f0, 0x01e2f9, 0x01e2ff, 0x01e2ff, 0x01e4d0, 0x01e4eb, 0x01e4f0, 0x01e4f9, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe, 0x01e800,
                        0x01e8c4, 0x01e8c7, 0x01e8cf, 0x01e900, 0x01e943, 0x01e94b, 0x01e94b, 0x01e950, 0x01e959, 0x01e95e, 0x01e95f, 0x01ec71, 0x01ecb4, 0x01ed01, 0x01ed3d, 0x01ee00, 0x01ee03,
                        0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42,
                        0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59,
                        0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79,
                        0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x01eef0, 0x01eef1, 0x01f000, 0x01f02b,
                        0x01f030, 0x01f093, 0x01f0a0, 0x01f0ae, 0x01f0b1, 0x01f0bf, 0x01f0c1, 0x01f0cf, 0x01f0d1, 0x01f0f5, 0x01f100, 0x01f1ad, 0x01f1e6, 0x01f202, 0x01f210, 0x01f23b, 0x01f240,
                        0x01f248, 0x01f250, 0x01f251, 0x01f260, 0x01f265, 0x01f300, 0x01f6d7, 0x01f6dc, 0x01f6ec, 0x01f6f0, 0x01f6fc, 0x01f700, 0x01f776, 0x01f77b, 0x01f7d9, 0x01f7e0, 0x01f7eb,
                        0x01f7f0, 0x01f7f0, 0x01f800, 0x01f80b, 0x01f810, 0x01f847, 0x01f850, 0x01f859, 0x01f860, 0x01f887, 0x01f890, 0x01f8ad, 0x01f8b0, 0x01f8b1, 0x01f900, 0x01fa53, 0x01fa60,
                        0x01fa6d, 0x01fa70, 0x01fa7c, 0x01fa80, 0x01fa88, 0x01fa90, 0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb, 0x01fae0, 0x01fae8, 0x01faf0, 0x01faf8, 0x01fb00, 0x01fb92,
                        0x01fb94, 0x01fbca, 0x01fbf0, 0x01fbf9, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d, 0x030000,
                        0x03134a, 0x031350, 0x0323af));
    }

    private static void populateGR_EXT() {
        SET_ENCODINGS.put("Gr_Ext", CodePointSet.createNoDedup(0x000300, 0x00036f, 0x000483, 0x000489, 0x000591, 0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4, 0x0005c5, 0x0005c7,
                        0x0005c7, 0x000610, 0x00061a, 0x00064b, 0x00065f, 0x000670, 0x000670, 0x0006d6, 0x0006dc, 0x0006df, 0x0006e4, 0x0006e7, 0x0006e8, 0x0006ea, 0x0006ed, 0x000711, 0x000711,
                        0x000730, 0x00074a, 0x0007a6, 0x0007b0, 0x0007eb, 0x0007f3, 0x0007fd, 0x0007fd, 0x000816, 0x000819, 0x00081b, 0x000823, 0x000825, 0x000827, 0x000829, 0x00082d, 0x000859,
                        0x00085b, 0x000898, 0x00089f, 0x0008ca, 0x0008e1, 0x0008e3, 0x000902, 0x00093a, 0x00093a, 0x00093c, 0x00093c, 0x000941, 0x000948, 0x00094d, 0x00094d, 0x000951, 0x000957,
                        0x000962, 0x000963, 0x000981, 0x000981, 0x0009bc, 0x0009bc, 0x0009be, 0x0009be, 0x0009c1, 0x0009c4, 0x0009cd, 0x0009cd, 0x0009d7, 0x0009d7, 0x0009e2, 0x0009e3, 0x0009fe,
                        0x0009fe, 0x000a01, 0x000a02, 0x000a3c, 0x000a3c, 0x000a41, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4d, 0x000a51, 0x000a51, 0x000a70, 0x000a71, 0x000a75, 0x000a75,
                        0x000a81, 0x000a82, 0x000abc, 0x000abc, 0x000ac1, 0x000ac5, 0x000ac7, 0x000ac8, 0x000acd, 0x000acd, 0x000ae2, 0x000ae3, 0x000afa, 0x000aff, 0x000b01, 0x000b01, 0x000b3c,
                        0x000b3c, 0x000b3e, 0x000b3f, 0x000b41, 0x000b44, 0x000b4d, 0x000b4d, 0x000b55, 0x000b57, 0x000b62, 0x000b63, 0x000b82, 0x000b82, 0x000bbe, 0x000bbe, 0x000bc0, 0x000bc0,
                        0x000bcd, 0x000bcd, 0x000bd7, 0x000bd7, 0x000c00, 0x000c00, 0x000c04, 0x000c04, 0x000c3c, 0x000c3c, 0x000c3e, 0x000c40, 0x000c46, 0x000c48, 0x000c4a, 0x000c4d, 0x000c55,
                        0x000c56, 0x000c62, 0x000c63, 0x000c81, 0x000c81, 0x000cbc, 0x000cbc, 0x000cbf, 0x000cbf, 0x000cc2, 0x000cc2, 0x000cc6, 0x000cc6, 0x000ccc, 0x000ccd, 0x000cd5, 0x000cd6,
                        0x000ce2, 0x000ce3, 0x000d00, 0x000d01, 0x000d3b, 0x000d3c, 0x000d3e, 0x000d3e, 0x000d41, 0x000d44, 0x000d4d, 0x000d4d, 0x000d57, 0x000d57, 0x000d62, 0x000d63, 0x000d81,
                        0x000d81, 0x000dca, 0x000dca, 0x000dcf, 0x000dcf, 0x000dd2, 0x000dd4, 0x000dd6, 0x000dd6, 0x000ddf, 0x000ddf, 0x000e31, 0x000e31, 0x000e34, 0x000e3a, 0x000e47, 0x000e4e,
                        0x000eb1, 0x000eb1, 0x000eb4, 0x000ebc, 0x000ec8, 0x000ece, 0x000f18, 0x000f19, 0x000f35, 0x000f35, 0x000f37, 0x000f37, 0x000f39, 0x000f39, 0x000f71, 0x000f7e, 0x000f80,
                        0x000f84, 0x000f86, 0x000f87, 0x000f8d, 0x000f97, 0x000f99, 0x000fbc, 0x000fc6, 0x000fc6, 0x00102d, 0x001030, 0x001032, 0x001037, 0x001039, 0x00103a, 0x00103d, 0x00103e,
                        0x001058, 0x001059, 0x00105e, 0x001060, 0x001071, 0x001074, 0x001082, 0x001082, 0x001085, 0x001086, 0x00108d, 0x00108d, 0x00109d, 0x00109d, 0x00135d, 0x00135f, 0x001712,
                        0x001714, 0x001732, 0x001733, 0x001752, 0x001753, 0x001772, 0x001773, 0x0017b4, 0x0017b5, 0x0017b7, 0x0017bd, 0x0017c6, 0x0017c6, 0x0017c9, 0x0017d3, 0x0017dd, 0x0017dd,
                        0x00180b, 0x00180d, 0x00180f, 0x00180f, 0x001885, 0x001886, 0x0018a9, 0x0018a9, 0x001920, 0x001922, 0x001927, 0x001928, 0x001932, 0x001932, 0x001939, 0x00193b, 0x001a17,
                        0x001a18, 0x001a1b, 0x001a1b, 0x001a56, 0x001a56, 0x001a58, 0x001a5e, 0x001a60, 0x001a60, 0x001a62, 0x001a62, 0x001a65, 0x001a6c, 0x001a73, 0x001a7c, 0x001a7f, 0x001a7f,
                        0x001ab0, 0x001ace, 0x001b00, 0x001b03, 0x001b34, 0x001b3a, 0x001b3c, 0x001b3c, 0x001b42, 0x001b42, 0x001b6b, 0x001b73, 0x001b80, 0x001b81, 0x001ba2, 0x001ba5, 0x001ba8,
                        0x001ba9, 0x001bab, 0x001bad, 0x001be6, 0x001be6, 0x001be8, 0x001be9, 0x001bed, 0x001bed, 0x001bef, 0x001bf1, 0x001c2c, 0x001c33, 0x001c36, 0x001c37, 0x001cd0, 0x001cd2,
                        0x001cd4, 0x001ce0, 0x001ce2, 0x001ce8, 0x001ced, 0x001ced, 0x001cf4, 0x001cf4, 0x001cf8, 0x001cf9, 0x001dc0, 0x001dff, 0x00200c, 0x00200c, 0x0020d0, 0x0020f0, 0x002cef,
                        0x002cf1, 0x002d7f, 0x002d7f, 0x002de0, 0x002dff, 0x00302a, 0x00302f, 0x003099, 0x00309a, 0x00a66f, 0x00a672, 0x00a674, 0x00a67d, 0x00a69e, 0x00a69f, 0x00a6f0, 0x00a6f1,
                        0x00a802, 0x00a802, 0x00a806, 0x00a806, 0x00a80b, 0x00a80b, 0x00a825, 0x00a826, 0x00a82c, 0x00a82c, 0x00a8c4, 0x00a8c5, 0x00a8e0, 0x00a8f1, 0x00a8ff, 0x00a8ff, 0x00a926,
                        0x00a92d, 0x00a947, 0x00a951, 0x00a980, 0x00a982, 0x00a9b3, 0x00a9b3, 0x00a9b6, 0x00a9b9, 0x00a9bc, 0x00a9bd, 0x00a9e5, 0x00a9e5, 0x00aa29, 0x00aa2e, 0x00aa31, 0x00aa32,
                        0x00aa35, 0x00aa36, 0x00aa43, 0x00aa43, 0x00aa4c, 0x00aa4c, 0x00aa7c, 0x00aa7c, 0x00aab0, 0x00aab0, 0x00aab2, 0x00aab4, 0x00aab7, 0x00aab8, 0x00aabe, 0x00aabf, 0x00aac1,
                        0x00aac1, 0x00aaec, 0x00aaed, 0x00aaf6, 0x00aaf6, 0x00abe5, 0x00abe5, 0x00abe8, 0x00abe8, 0x00abed, 0x00abed, 0x00fb1e, 0x00fb1e, 0x00fe00, 0x00fe0f, 0x00fe20, 0x00fe2f,
                        0x00ff9e, 0x00ff9f, 0x0101fd, 0x0101fd, 0x0102e0, 0x0102e0, 0x010376, 0x01037a, 0x010a01, 0x010a03, 0x010a05, 0x010a06, 0x010a0c, 0x010a0f, 0x010a38, 0x010a3a, 0x010a3f,
                        0x010a3f, 0x010ae5, 0x010ae6, 0x010d24, 0x010d27, 0x010eab, 0x010eac, 0x010efd, 0x010eff, 0x010f46, 0x010f50, 0x010f82, 0x010f85, 0x011001, 0x011001, 0x011038, 0x011046,
                        0x011070, 0x011070, 0x011073, 0x011074, 0x01107f, 0x011081, 0x0110b3, 0x0110b6, 0x0110b9, 0x0110ba, 0x0110c2, 0x0110c2, 0x011100, 0x011102, 0x011127, 0x01112b, 0x01112d,
                        0x011134, 0x011173, 0x011173, 0x011180, 0x011181, 0x0111b6, 0x0111be, 0x0111c9, 0x0111cc, 0x0111cf, 0x0111cf, 0x01122f, 0x011231, 0x011234, 0x011234, 0x011236, 0x011237,
                        0x01123e, 0x01123e, 0x011241, 0x011241, 0x0112df, 0x0112df, 0x0112e3, 0x0112ea, 0x011300, 0x011301, 0x01133b, 0x01133c, 0x01133e, 0x01133e, 0x011340, 0x011340, 0x011357,
                        0x011357, 0x011366, 0x01136c, 0x011370, 0x011374, 0x011438, 0x01143f, 0x011442, 0x011444, 0x011446, 0x011446, 0x01145e, 0x01145e, 0x0114b0, 0x0114b0, 0x0114b3, 0x0114b8,
                        0x0114ba, 0x0114ba, 0x0114bd, 0x0114bd, 0x0114bf, 0x0114c0, 0x0114c2, 0x0114c3, 0x0115af, 0x0115af, 0x0115b2, 0x0115b5, 0x0115bc, 0x0115bd, 0x0115bf, 0x0115c0, 0x0115dc,
                        0x0115dd, 0x011633, 0x01163a, 0x01163d, 0x01163d, 0x01163f, 0x011640, 0x0116ab, 0x0116ab, 0x0116ad, 0x0116ad, 0x0116b0, 0x0116b5, 0x0116b7, 0x0116b7, 0x01171d, 0x01171f,
                        0x011722, 0x011725, 0x011727, 0x01172b, 0x01182f, 0x011837, 0x011839, 0x01183a, 0x011930, 0x011930, 0x01193b, 0x01193c, 0x01193e, 0x01193e, 0x011943, 0x011943, 0x0119d4,
                        0x0119d7, 0x0119da, 0x0119db, 0x0119e0, 0x0119e0, 0x011a01, 0x011a0a, 0x011a33, 0x011a38, 0x011a3b, 0x011a3e, 0x011a47, 0x011a47, 0x011a51, 0x011a56, 0x011a59, 0x011a5b,
                        0x011a8a, 0x011a96, 0x011a98, 0x011a99, 0x011c30, 0x011c36, 0x011c38, 0x011c3d, 0x011c3f, 0x011c3f, 0x011c92, 0x011ca7, 0x011caa, 0x011cb0, 0x011cb2, 0x011cb3, 0x011cb5,
                        0x011cb6, 0x011d31, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d45, 0x011d47, 0x011d47, 0x011d90, 0x011d91, 0x011d95, 0x011d95, 0x011d97, 0x011d97,
                        0x011ef3, 0x011ef4, 0x011f00, 0x011f01, 0x011f36, 0x011f3a, 0x011f40, 0x011f40, 0x011f42, 0x011f42, 0x013440, 0x013440, 0x013447, 0x013455, 0x016af0, 0x016af4, 0x016b30,
                        0x016b36, 0x016f4f, 0x016f4f, 0x016f8f, 0x016f92, 0x016fe4, 0x016fe4, 0x01bc9d, 0x01bc9e, 0x01cf00, 0x01cf2d, 0x01cf30, 0x01cf46, 0x01d165, 0x01d165, 0x01d167, 0x01d169,
                        0x01d16e, 0x01d172, 0x01d17b, 0x01d182, 0x01d185, 0x01d18b, 0x01d1aa, 0x01d1ad, 0x01d242, 0x01d244, 0x01da00, 0x01da36, 0x01da3b, 0x01da6c, 0x01da75, 0x01da75, 0x01da84,
                        0x01da84, 0x01da9b, 0x01da9f, 0x01daa1, 0x01daaf, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b, 0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a, 0x01e08f, 0x01e08f,
                        0x01e130, 0x01e136, 0x01e2ae, 0x01e2ae, 0x01e2ec, 0x01e2ef, 0x01e4ec, 0x01e4ef, 0x01e8d0, 0x01e8d6, 0x01e944, 0x01e94a, 0x0e0020, 0x0e007f, 0x0e0100, 0x0e01ef));
    }

    private static void populateHEX() {
        SET_ENCODINGS.put("Hex", CodePointSet.createNoDedup(0x000030, 0x000039, 0x000041, 0x000046, 0x000061, 0x000066, 0x00ff10, 0x00ff19, 0x00ff21, 0x00ff26, 0x00ff41, 0x00ff46));
    }

    private static void populateIDC() {
        SET_ENCODINGS.put("IDC", CodePointSet.createNoDedup(0x000030, 0x000039, 0x000041, 0x00005a, 0x00005f, 0x00005f, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000b5, 0x0000b5, 0x0000b7, 0x0000b7,
                        0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6, 0x0000f8, 0x0002c1, 0x0002c6, 0x0002d1, 0x0002e0, 0x0002e4, 0x0002ec, 0x0002ec, 0x0002ee, 0x0002ee, 0x000300,
                        0x000374, 0x000376, 0x000377, 0x00037a, 0x00037d, 0x00037f, 0x00037f, 0x000386, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003f5, 0x0003f7, 0x000481,
                        0x000483, 0x000487, 0x00048a, 0x00052f, 0x000531, 0x000556, 0x000559, 0x000559, 0x000560, 0x000588, 0x000591, 0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4,
                        0x0005c5, 0x0005c7, 0x0005c7, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f2, 0x000610, 0x00061a, 0x000620, 0x000669, 0x00066e, 0x0006d3, 0x0006d5, 0x0006dc, 0x0006df, 0x0006e8,
                        0x0006ea, 0x0006fc, 0x0006ff, 0x0006ff, 0x000710, 0x00074a, 0x00074d, 0x0007b1, 0x0007c0, 0x0007f5, 0x0007fa, 0x0007fa, 0x0007fd, 0x0007fd, 0x000800, 0x00082d, 0x000840,
                        0x00085b, 0x000860, 0x00086a, 0x000870, 0x000887, 0x000889, 0x00088e, 0x000898, 0x0008e1, 0x0008e3, 0x000963, 0x000966, 0x00096f, 0x000971, 0x000983, 0x000985, 0x00098c,
                        0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6, 0x0009b9, 0x0009bc, 0x0009c4, 0x0009c7, 0x0009c8, 0x0009cb, 0x0009ce, 0x0009d7,
                        0x0009d7, 0x0009dc, 0x0009dd, 0x0009df, 0x0009e3, 0x0009e6, 0x0009f1, 0x0009fc, 0x0009fc, 0x0009fe, 0x0009fe, 0x000a01, 0x000a03, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10,
                        0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36, 0x000a38, 0x000a39, 0x000a3c, 0x000a3c, 0x000a3e, 0x000a42, 0x000a47, 0x000a48, 0x000a4b,
                        0x000a4d, 0x000a51, 0x000a51, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a66, 0x000a75, 0x000a81, 0x000a83, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8,
                        0x000aaa, 0x000ab0, 0x000ab2, 0x000ab3, 0x000ab5, 0x000ab9, 0x000abc, 0x000ac5, 0x000ac7, 0x000ac9, 0x000acb, 0x000acd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae3, 0x000ae6,
                        0x000aef, 0x000af9, 0x000aff, 0x000b01, 0x000b03, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28, 0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35, 0x000b39,
                        0x000b3c, 0x000b44, 0x000b47, 0x000b48, 0x000b4b, 0x000b4d, 0x000b55, 0x000b57, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b63, 0x000b66, 0x000b6f, 0x000b71, 0x000b71, 0x000b82,
                        0x000b83, 0x000b85, 0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa,
                        0x000bae, 0x000bb9, 0x000bbe, 0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcd, 0x000bd0, 0x000bd0, 0x000bd7, 0x000bd7, 0x000be6, 0x000bef, 0x000c00, 0x000c0c, 0x000c0e,
                        0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3c, 0x000c44, 0x000c46, 0x000c48, 0x000c4a, 0x000c4d, 0x000c55, 0x000c56, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d,
                        0x000c60, 0x000c63, 0x000c66, 0x000c6f, 0x000c80, 0x000c83, 0x000c85, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbc,
                        0x000cc4, 0x000cc6, 0x000cc8, 0x000cca, 0x000ccd, 0x000cd5, 0x000cd6, 0x000cdd, 0x000cde, 0x000ce0, 0x000ce3, 0x000ce6, 0x000cef, 0x000cf1, 0x000cf3, 0x000d00, 0x000d0c,
                        0x000d0e, 0x000d10, 0x000d12, 0x000d44, 0x000d46, 0x000d48, 0x000d4a, 0x000d4e, 0x000d54, 0x000d57, 0x000d5f, 0x000d63, 0x000d66, 0x000d6f, 0x000d7a, 0x000d7f, 0x000d81,
                        0x000d83, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000dca, 0x000dca, 0x000dcf, 0x000dd4, 0x000dd6, 0x000dd6,
                        0x000dd8, 0x000ddf, 0x000de6, 0x000def, 0x000df2, 0x000df3, 0x000e01, 0x000e3a, 0x000e40, 0x000e4e, 0x000e50, 0x000e59, 0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86,
                        0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000ebd, 0x000ec0, 0x000ec4, 0x000ec6, 0x000ec6, 0x000ec8, 0x000ece, 0x000ed0, 0x000ed9, 0x000edc, 0x000edf,
                        0x000f00, 0x000f00, 0x000f18, 0x000f19, 0x000f20, 0x000f29, 0x000f35, 0x000f35, 0x000f37, 0x000f37, 0x000f39, 0x000f39, 0x000f3e, 0x000f47, 0x000f49, 0x000f6c, 0x000f71,
                        0x000f84, 0x000f86, 0x000f97, 0x000f99, 0x000fbc, 0x000fc6, 0x000fc6, 0x001000, 0x001049, 0x001050, 0x00109d, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd,
                        0x0010d0, 0x0010fa, 0x0010fc, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d, 0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290,
                        0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8, 0x001310, 0x001312, 0x001315, 0x001318, 0x00135a,
                        0x00135d, 0x00135f, 0x001369, 0x001371, 0x001380, 0x00138f, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001401, 0x00166c, 0x00166f, 0x00167f, 0x001681, 0x00169a, 0x0016a0,
                        0x0016ea, 0x0016ee, 0x0016f8, 0x001700, 0x001715, 0x00171f, 0x001734, 0x001740, 0x001753, 0x001760, 0x00176c, 0x00176e, 0x001770, 0x001772, 0x001773, 0x001780, 0x0017d3,
                        0x0017d7, 0x0017d7, 0x0017dc, 0x0017dd, 0x0017e0, 0x0017e9, 0x00180b, 0x00180d, 0x00180f, 0x001819, 0x001820, 0x001878, 0x001880, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900,
                        0x00191e, 0x001920, 0x00192b, 0x001930, 0x00193b, 0x001946, 0x00196d, 0x001970, 0x001974, 0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x0019d0, 0x0019da, 0x001a00, 0x001a1b,
                        0x001a20, 0x001a5e, 0x001a60, 0x001a7c, 0x001a7f, 0x001a89, 0x001a90, 0x001a99, 0x001aa7, 0x001aa7, 0x001ab0, 0x001abd, 0x001abf, 0x001ace, 0x001b00, 0x001b4c, 0x001b50,
                        0x001b59, 0x001b6b, 0x001b73, 0x001b80, 0x001bf3, 0x001c00, 0x001c37, 0x001c40, 0x001c49, 0x001c4d, 0x001c7d, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf,
                        0x001cd0, 0x001cd2, 0x001cd4, 0x001cfa, 0x001d00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50, 0x001f57, 0x001f59, 0x001f59, 0x001f5b,
                        0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc, 0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4, 0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3,
                        0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x00203f, 0x002040, 0x002054, 0x002054, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090,
                        0x00209c, 0x0020d0, 0x0020dc, 0x0020e1, 0x0020e1, 0x0020e5, 0x0020f0, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002118, 0x00211d,
                        0x002124, 0x002124, 0x002126, 0x002126, 0x002128, 0x002128, 0x00212a, 0x002139, 0x00213c, 0x00213f, 0x002145, 0x002149, 0x00214e, 0x00214e, 0x002160, 0x002188, 0x002c00,
                        0x002ce4, 0x002ceb, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x002d30, 0x002d67, 0x002d6f, 0x002d6f, 0x002d7f, 0x002d96, 0x002da0, 0x002da6,
                        0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe, 0x002dc0, 0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x002de0, 0x002dff, 0x003005,
                        0x003007, 0x003021, 0x00302f, 0x003031, 0x003035, 0x003038, 0x00303c, 0x003041, 0x003096, 0x003099, 0x00309f, 0x0030a1, 0x0030fa, 0x0030fc, 0x0030ff, 0x003105, 0x00312f,
                        0x003131, 0x00318e, 0x0031a0, 0x0031bf, 0x0031f0, 0x0031ff, 0x003400, 0x004dbf, 0x004e00, 0x00a48c, 0x00a4d0, 0x00a4fd, 0x00a500, 0x00a60c, 0x00a610, 0x00a62b, 0x00a640,
                        0x00a66f, 0x00a674, 0x00a67d, 0x00a67f, 0x00a6f1, 0x00a717, 0x00a71f, 0x00a722, 0x00a788, 0x00a78b, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9,
                        0x00a7f2, 0x00a827, 0x00a82c, 0x00a82c, 0x00a840, 0x00a873, 0x00a880, 0x00a8c5, 0x00a8d0, 0x00a8d9, 0x00a8e0, 0x00a8f7, 0x00a8fb, 0x00a8fb, 0x00a8fd, 0x00a92d, 0x00a930,
                        0x00a953, 0x00a960, 0x00a97c, 0x00a980, 0x00a9c0, 0x00a9cf, 0x00a9d9, 0x00a9e0, 0x00a9fe, 0x00aa00, 0x00aa36, 0x00aa40, 0x00aa4d, 0x00aa50, 0x00aa59, 0x00aa60, 0x00aa76,
                        0x00aa7a, 0x00aac2, 0x00aadb, 0x00aadd, 0x00aae0, 0x00aaef, 0x00aaf2, 0x00aaf6, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20, 0x00ab26, 0x00ab28,
                        0x00ab2e, 0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab69, 0x00ab70, 0x00abea, 0x00abec, 0x00abed, 0x00abf0, 0x00abf9, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb,
                        0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb28, 0x00fb2a, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40,
                        0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fbb1, 0x00fbd3, 0x00fd3d, 0x00fd50, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdf0, 0x00fdfb, 0x00fe00, 0x00fe0f, 0x00fe20, 0x00fe2f,
                        0x00fe33, 0x00fe34, 0x00fe4d, 0x00fe4f, 0x00fe70, 0x00fe74, 0x00fe76, 0x00fefc, 0x00ff10, 0x00ff19, 0x00ff21, 0x00ff3a, 0x00ff3f, 0x00ff3f, 0x00ff41, 0x00ff5a, 0x00ff66,
                        0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d,
                        0x01003f, 0x01004d, 0x010050, 0x01005d, 0x010080, 0x0100fa, 0x010140, 0x010174, 0x0101fd, 0x0101fd, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x0102e0, 0x0102e0, 0x010300,
                        0x01031f, 0x01032d, 0x01034a, 0x010350, 0x01037a, 0x010380, 0x01039d, 0x0103a0, 0x0103c3, 0x0103c8, 0x0103cf, 0x0103d1, 0x0103d5, 0x010400, 0x01049d, 0x0104a0, 0x0104a9,
                        0x0104b0, 0x0104d3, 0x0104d8, 0x0104fb, 0x010500, 0x010527, 0x010530, 0x010563, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597,
                        0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767, 0x010780, 0x010785, 0x010787, 0x0107b0,
                        0x0107b2, 0x0107ba, 0x010800, 0x010805, 0x010808, 0x010808, 0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x010855, 0x010860, 0x010876, 0x010880,
                        0x01089e, 0x0108e0, 0x0108f2, 0x0108f4, 0x0108f5, 0x010900, 0x010915, 0x010920, 0x010939, 0x010980, 0x0109b7, 0x0109be, 0x0109bf, 0x010a00, 0x010a03, 0x010a05, 0x010a06,
                        0x010a0c, 0x010a13, 0x010a15, 0x010a17, 0x010a19, 0x010a35, 0x010a38, 0x010a3a, 0x010a3f, 0x010a3f, 0x010a60, 0x010a7c, 0x010a80, 0x010a9c, 0x010ac0, 0x010ac7, 0x010ac9,
                        0x010ae6, 0x010b00, 0x010b35, 0x010b40, 0x010b55, 0x010b60, 0x010b72, 0x010b80, 0x010b91, 0x010c00, 0x010c48, 0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x010d00, 0x010d27,
                        0x010d30, 0x010d39, 0x010e80, 0x010ea9, 0x010eab, 0x010eac, 0x010eb0, 0x010eb1, 0x010efd, 0x010f1c, 0x010f27, 0x010f27, 0x010f30, 0x010f50, 0x010f70, 0x010f85, 0x010fb0,
                        0x010fc4, 0x010fe0, 0x010ff6, 0x011000, 0x011046, 0x011066, 0x011075, 0x01107f, 0x0110ba, 0x0110c2, 0x0110c2, 0x0110d0, 0x0110e8, 0x0110f0, 0x0110f9, 0x011100, 0x011134,
                        0x011136, 0x01113f, 0x011144, 0x011147, 0x011150, 0x011173, 0x011176, 0x011176, 0x011180, 0x0111c4, 0x0111c9, 0x0111cc, 0x0111ce, 0x0111da, 0x0111dc, 0x0111dc, 0x011200,
                        0x011211, 0x011213, 0x011237, 0x01123e, 0x011241, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a8, 0x0112b0, 0x0112ea,
                        0x0112f0, 0x0112f9, 0x011300, 0x011303, 0x011305, 0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133b,
                        0x011344, 0x011347, 0x011348, 0x01134b, 0x01134d, 0x011350, 0x011350, 0x011357, 0x011357, 0x01135d, 0x011363, 0x011366, 0x01136c, 0x011370, 0x011374, 0x011400, 0x01144a,
                        0x011450, 0x011459, 0x01145e, 0x011461, 0x011480, 0x0114c5, 0x0114c7, 0x0114c7, 0x0114d0, 0x0114d9, 0x011580, 0x0115b5, 0x0115b8, 0x0115c0, 0x0115d8, 0x0115dd, 0x011600,
                        0x011640, 0x011644, 0x011644, 0x011650, 0x011659, 0x011680, 0x0116b8, 0x0116c0, 0x0116c9, 0x011700, 0x01171a, 0x01171d, 0x01172b, 0x011730, 0x011739, 0x011740, 0x011746,
                        0x011800, 0x01183a, 0x0118a0, 0x0118e9, 0x0118ff, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x011935, 0x011937, 0x011938, 0x01193b,
                        0x011943, 0x011950, 0x011959, 0x0119a0, 0x0119a7, 0x0119aa, 0x0119d7, 0x0119da, 0x0119e1, 0x0119e3, 0x0119e4, 0x011a00, 0x011a3e, 0x011a47, 0x011a47, 0x011a50, 0x011a99,
                        0x011a9d, 0x011a9d, 0x011ab0, 0x011af8, 0x011c00, 0x011c08, 0x011c0a, 0x011c36, 0x011c38, 0x011c40, 0x011c50, 0x011c59, 0x011c72, 0x011c8f, 0x011c92, 0x011ca7, 0x011ca9,
                        0x011cb6, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d47, 0x011d50, 0x011d59, 0x011d60, 0x011d65,
                        0x011d67, 0x011d68, 0x011d6a, 0x011d8e, 0x011d90, 0x011d91, 0x011d93, 0x011d98, 0x011da0, 0x011da9, 0x011ee0, 0x011ef6, 0x011f00, 0x011f10, 0x011f12, 0x011f3a, 0x011f3e,
                        0x011f42, 0x011f50, 0x011f59, 0x011fb0, 0x011fb0, 0x012000, 0x012399, 0x012400, 0x01246e, 0x012480, 0x012543, 0x012f90, 0x012ff0, 0x013000, 0x01342f, 0x013440, 0x013455,
                        0x014400, 0x014646, 0x016800, 0x016a38, 0x016a40, 0x016a5e, 0x016a60, 0x016a69, 0x016a70, 0x016abe, 0x016ac0, 0x016ac9, 0x016ad0, 0x016aed, 0x016af0, 0x016af4, 0x016b00,
                        0x016b36, 0x016b40, 0x016b43, 0x016b50, 0x016b59, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f, 0x016e40, 0x016e7f, 0x016f00, 0x016f4a, 0x016f4f, 0x016f87, 0x016f8f, 0x016f9f,
                        0x016fe0, 0x016fe1, 0x016fe3, 0x016fe4, 0x016ff0, 0x016ff1, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd,
                        0x01affe, 0x01b000, 0x01b122, 0x01b132, 0x01b132, 0x01b150, 0x01b152, 0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb, 0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c,
                        0x01bc80, 0x01bc88, 0x01bc90, 0x01bc99, 0x01bc9d, 0x01bc9e, 0x01cf00, 0x01cf2d, 0x01cf30, 0x01cf46, 0x01d165, 0x01d169, 0x01d16d, 0x01d172, 0x01d17b, 0x01d182, 0x01d185,
                        0x01d18b, 0x01d1aa, 0x01d1ad, 0x01d242, 0x01d244, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac,
                        0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b,
                        0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d6c0, 0x01d6c2, 0x01d6da, 0x01d6dc, 0x01d6fa, 0x01d6fc, 0x01d714,
                        0x01d716, 0x01d734, 0x01d736, 0x01d74e, 0x01d750, 0x01d76e, 0x01d770, 0x01d788, 0x01d78a, 0x01d7a8, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7cb, 0x01d7ce, 0x01d7ff, 0x01da00,
                        0x01da36, 0x01da3b, 0x01da6c, 0x01da75, 0x01da75, 0x01da84, 0x01da84, 0x01da9b, 0x01da9f, 0x01daa1, 0x01daaf, 0x01df00, 0x01df1e, 0x01df25, 0x01df2a, 0x01e000, 0x01e006,
                        0x01e008, 0x01e018, 0x01e01b, 0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a, 0x01e030, 0x01e06d, 0x01e08f, 0x01e08f, 0x01e100, 0x01e12c, 0x01e130, 0x01e13d, 0x01e140,
                        0x01e149, 0x01e14e, 0x01e14e, 0x01e290, 0x01e2ae, 0x01e2c0, 0x01e2f9, 0x01e4d0, 0x01e4f9, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe,
                        0x01e800, 0x01e8c4, 0x01e8d0, 0x01e8d6, 0x01e900, 0x01e94b, 0x01e950, 0x01e959, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27,
                        0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b,
                        0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61,
                        0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b,
                        0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x01fbf0, 0x01fbf9, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0,
                        0x02ebe0, 0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af, 0x0e0100, 0x0e01ef));
    }

    private static void populateIDS() {
        SET_ENCODINGS.put("IDS", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000b5, 0x0000b5, 0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6,
                        0x0000f8, 0x0002c1, 0x0002c6, 0x0002d1, 0x0002e0, 0x0002e4, 0x0002ec, 0x0002ec, 0x0002ee, 0x0002ee, 0x000370, 0x000374, 0x000376, 0x000377, 0x00037a, 0x00037d, 0x00037f,
                        0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003f5, 0x0003f7, 0x000481, 0x00048a, 0x00052f, 0x000531, 0x000556,
                        0x000559, 0x000559, 0x000560, 0x000588, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f2, 0x000620, 0x00064a, 0x00066e, 0x00066f, 0x000671, 0x0006d3, 0x0006d5, 0x0006d5, 0x0006e5,
                        0x0006e6, 0x0006ee, 0x0006ef, 0x0006fa, 0x0006fc, 0x0006ff, 0x0006ff, 0x000710, 0x000710, 0x000712, 0x00072f, 0x00074d, 0x0007a5, 0x0007b1, 0x0007b1, 0x0007ca, 0x0007ea,
                        0x0007f4, 0x0007f5, 0x0007fa, 0x0007fa, 0x000800, 0x000815, 0x00081a, 0x00081a, 0x000824, 0x000824, 0x000828, 0x000828, 0x000840, 0x000858, 0x000860, 0x00086a, 0x000870,
                        0x000887, 0x000889, 0x00088e, 0x0008a0, 0x0008c9, 0x000904, 0x000939, 0x00093d, 0x00093d, 0x000950, 0x000950, 0x000958, 0x000961, 0x000971, 0x000980, 0x000985, 0x00098c,
                        0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6, 0x0009b9, 0x0009bd, 0x0009bd, 0x0009ce, 0x0009ce, 0x0009dc, 0x0009dd, 0x0009df,
                        0x0009e1, 0x0009f0, 0x0009f1, 0x0009fc, 0x0009fc, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36,
                        0x000a38, 0x000a39, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a72, 0x000a74, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8, 0x000aaa, 0x000ab0, 0x000ab2,
                        0x000ab3, 0x000ab5, 0x000ab9, 0x000abd, 0x000abd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae1, 0x000af9, 0x000af9, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28,
                        0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35, 0x000b39, 0x000b3d, 0x000b3d, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b61, 0x000b71, 0x000b71, 0x000b83, 0x000b83, 0x000b85,
                        0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa, 0x000bae, 0x000bb9,
                        0x000bd0, 0x000bd0, 0x000c05, 0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3d, 0x000c3d, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d, 0x000c60,
                        0x000c61, 0x000c80, 0x000c80, 0x000c85, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbd, 0x000cbd, 0x000cdd, 0x000cde,
                        0x000ce0, 0x000ce1, 0x000cf1, 0x000cf2, 0x000d04, 0x000d0c, 0x000d0e, 0x000d10, 0x000d12, 0x000d3a, 0x000d3d, 0x000d3d, 0x000d4e, 0x000d4e, 0x000d54, 0x000d56, 0x000d5f,
                        0x000d61, 0x000d7a, 0x000d7f, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000e01, 0x000e30, 0x000e32, 0x000e33,
                        0x000e40, 0x000e46, 0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000eb0, 0x000eb2, 0x000eb3, 0x000ebd,
                        0x000ebd, 0x000ec0, 0x000ec4, 0x000ec6, 0x000ec6, 0x000edc, 0x000edf, 0x000f00, 0x000f00, 0x000f40, 0x000f47, 0x000f49, 0x000f6c, 0x000f88, 0x000f8c, 0x001000, 0x00102a,
                        0x00103f, 0x00103f, 0x001050, 0x001055, 0x00105a, 0x00105d, 0x001061, 0x001061, 0x001065, 0x001066, 0x00106e, 0x001070, 0x001075, 0x001081, 0x00108e, 0x00108e, 0x0010a0,
                        0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x0010fa, 0x0010fc, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d,
                        0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290, 0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8,
                        0x001310, 0x001312, 0x001315, 0x001318, 0x00135a, 0x001380, 0x00138f, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001401, 0x00166c, 0x00166f, 0x00167f, 0x001681, 0x00169a,
                        0x0016a0, 0x0016ea, 0x0016ee, 0x0016f8, 0x001700, 0x001711, 0x00171f, 0x001731, 0x001740, 0x001751, 0x001760, 0x00176c, 0x00176e, 0x001770, 0x001780, 0x0017b3, 0x0017d7,
                        0x0017d7, 0x0017dc, 0x0017dc, 0x001820, 0x001878, 0x001880, 0x0018a8, 0x0018aa, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900, 0x00191e, 0x001950, 0x00196d, 0x001970, 0x001974,
                        0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x001a00, 0x001a16, 0x001a20, 0x001a54, 0x001aa7, 0x001aa7, 0x001b05, 0x001b33, 0x001b45, 0x001b4c, 0x001b83, 0x001ba0, 0x001bae,
                        0x001baf, 0x001bba, 0x001be5, 0x001c00, 0x001c23, 0x001c4d, 0x001c4f, 0x001c5a, 0x001c7d, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001ce9, 0x001cec,
                        0x001cee, 0x001cf3, 0x001cf5, 0x001cf6, 0x001cfa, 0x001cfa, 0x001d00, 0x001dbf, 0x001e00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50,
                        0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc, 0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4,
                        0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090,
                        0x00209c, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002118, 0x00211d, 0x002124, 0x002124, 0x002126, 0x002126, 0x002128, 0x002128,
                        0x00212a, 0x002139, 0x00213c, 0x00213f, 0x002145, 0x002149, 0x00214e, 0x00214e, 0x002160, 0x002188, 0x002c00, 0x002ce4, 0x002ceb, 0x002cee, 0x002cf2, 0x002cf3, 0x002d00,
                        0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x002d30, 0x002d67, 0x002d6f, 0x002d6f, 0x002d80, 0x002d96, 0x002da0, 0x002da6, 0x002da8, 0x002dae, 0x002db0, 0x002db6,
                        0x002db8, 0x002dbe, 0x002dc0, 0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x003005, 0x003007, 0x003021, 0x003029, 0x003031, 0x003035, 0x003038,
                        0x00303c, 0x003041, 0x003096, 0x00309b, 0x00309f, 0x0030a1, 0x0030fa, 0x0030fc, 0x0030ff, 0x003105, 0x00312f, 0x003131, 0x00318e, 0x0031a0, 0x0031bf, 0x0031f0, 0x0031ff,
                        0x003400, 0x004dbf, 0x004e00, 0x00a48c, 0x00a4d0, 0x00a4fd, 0x00a500, 0x00a60c, 0x00a610, 0x00a61f, 0x00a62a, 0x00a62b, 0x00a640, 0x00a66e, 0x00a67f, 0x00a69d, 0x00a6a0,
                        0x00a6ef, 0x00a717, 0x00a71f, 0x00a722, 0x00a788, 0x00a78b, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9, 0x00a7f2, 0x00a801, 0x00a803, 0x00a805,
                        0x00a807, 0x00a80a, 0x00a80c, 0x00a822, 0x00a840, 0x00a873, 0x00a882, 0x00a8b3, 0x00a8f2, 0x00a8f7, 0x00a8fb, 0x00a8fb, 0x00a8fd, 0x00a8fe, 0x00a90a, 0x00a925, 0x00a930,
                        0x00a946, 0x00a960, 0x00a97c, 0x00a984, 0x00a9b2, 0x00a9cf, 0x00a9cf, 0x00a9e0, 0x00a9e4, 0x00a9e6, 0x00a9ef, 0x00a9fa, 0x00a9fe, 0x00aa00, 0x00aa28, 0x00aa40, 0x00aa42,
                        0x00aa44, 0x00aa4b, 0x00aa60, 0x00aa76, 0x00aa7a, 0x00aa7a, 0x00aa7e, 0x00aaaf, 0x00aab1, 0x00aab1, 0x00aab5, 0x00aab6, 0x00aab9, 0x00aabd, 0x00aac0, 0x00aac0, 0x00aac2,
                        0x00aac2, 0x00aadb, 0x00aadd, 0x00aae0, 0x00aaea, 0x00aaf2, 0x00aaf4, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20, 0x00ab26, 0x00ab28, 0x00ab2e,
                        0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab69, 0x00ab70, 0x00abe2, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb, 0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb00,
                        0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb1d, 0x00fb1f, 0x00fb28, 0x00fb2a, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40, 0x00fb41, 0x00fb43, 0x00fb44,
                        0x00fb46, 0x00fbb1, 0x00fbd3, 0x00fd3d, 0x00fd50, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdf0, 0x00fdfb, 0x00fe70, 0x00fe74, 0x00fe76, 0x00fefc, 0x00ff21, 0x00ff3a, 0x00ff41,
                        0x00ff5a, 0x00ff66, 0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a,
                        0x01003c, 0x01003d, 0x01003f, 0x01004d, 0x010050, 0x01005d, 0x010080, 0x0100fa, 0x010140, 0x010174, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x010300, 0x01031f, 0x01032d,
                        0x01034a, 0x010350, 0x010375, 0x010380, 0x01039d, 0x0103a0, 0x0103c3, 0x0103c8, 0x0103cf, 0x0103d1, 0x0103d5, 0x010400, 0x01049d, 0x0104b0, 0x0104d3, 0x0104d8, 0x0104fb,
                        0x010500, 0x010527, 0x010530, 0x010563, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3,
                        0x0105b9, 0x0105bb, 0x0105bc, 0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010800, 0x010805,
                        0x010808, 0x010808, 0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x010855, 0x010860, 0x010876, 0x010880, 0x01089e, 0x0108e0, 0x0108f2, 0x0108f4,
                        0x0108f5, 0x010900, 0x010915, 0x010920, 0x010939, 0x010980, 0x0109b7, 0x0109be, 0x0109bf, 0x010a00, 0x010a00, 0x010a10, 0x010a13, 0x010a15, 0x010a17, 0x010a19, 0x010a35,
                        0x010a60, 0x010a7c, 0x010a80, 0x010a9c, 0x010ac0, 0x010ac7, 0x010ac9, 0x010ae4, 0x010b00, 0x010b35, 0x010b40, 0x010b55, 0x010b60, 0x010b72, 0x010b80, 0x010b91, 0x010c00,
                        0x010c48, 0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x010d00, 0x010d23, 0x010e80, 0x010ea9, 0x010eb0, 0x010eb1, 0x010f00, 0x010f1c, 0x010f27, 0x010f27, 0x010f30, 0x010f45,
                        0x010f70, 0x010f81, 0x010fb0, 0x010fc4, 0x010fe0, 0x010ff6, 0x011003, 0x011037, 0x011071, 0x011072, 0x011075, 0x011075, 0x011083, 0x0110af, 0x0110d0, 0x0110e8, 0x011103,
                        0x011126, 0x011144, 0x011144, 0x011147, 0x011147, 0x011150, 0x011172, 0x011176, 0x011176, 0x011183, 0x0111b2, 0x0111c1, 0x0111c4, 0x0111da, 0x0111da, 0x0111dc, 0x0111dc,
                        0x011200, 0x011211, 0x011213, 0x01122b, 0x01123f, 0x011240, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a8, 0x0112b0,
                        0x0112de, 0x011305, 0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133d, 0x01133d, 0x011350, 0x011350,
                        0x01135d, 0x011361, 0x011400, 0x011434, 0x011447, 0x01144a, 0x01145f, 0x011461, 0x011480, 0x0114af, 0x0114c4, 0x0114c5, 0x0114c7, 0x0114c7, 0x011580, 0x0115ae, 0x0115d8,
                        0x0115db, 0x011600, 0x01162f, 0x011644, 0x011644, 0x011680, 0x0116aa, 0x0116b8, 0x0116b8, 0x011700, 0x01171a, 0x011740, 0x011746, 0x011800, 0x01182b, 0x0118a0, 0x0118df,
                        0x0118ff, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x01192f, 0x01193f, 0x01193f, 0x011941, 0x011941, 0x0119a0, 0x0119a7, 0x0119aa,
                        0x0119d0, 0x0119e1, 0x0119e1, 0x0119e3, 0x0119e3, 0x011a00, 0x011a00, 0x011a0b, 0x011a32, 0x011a3a, 0x011a3a, 0x011a50, 0x011a50, 0x011a5c, 0x011a89, 0x011a9d, 0x011a9d,
                        0x011ab0, 0x011af8, 0x011c00, 0x011c08, 0x011c0a, 0x011c2e, 0x011c40, 0x011c40, 0x011c72, 0x011c8f, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d30, 0x011d46,
                        0x011d46, 0x011d60, 0x011d65, 0x011d67, 0x011d68, 0x011d6a, 0x011d89, 0x011d98, 0x011d98, 0x011ee0, 0x011ef2, 0x011f02, 0x011f02, 0x011f04, 0x011f10, 0x011f12, 0x011f33,
                        0x011fb0, 0x011fb0, 0x012000, 0x012399, 0x012400, 0x01246e, 0x012480, 0x012543, 0x012f90, 0x012ff0, 0x013000, 0x01342f, 0x013441, 0x013446, 0x014400, 0x014646, 0x016800,
                        0x016a38, 0x016a40, 0x016a5e, 0x016a70, 0x016abe, 0x016ad0, 0x016aed, 0x016b00, 0x016b2f, 0x016b40, 0x016b43, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f, 0x016e40, 0x016e7f,
                        0x016f00, 0x016f4a, 0x016f50, 0x016f50, 0x016f93, 0x016f9f, 0x016fe0, 0x016fe1, 0x016fe3, 0x016fe3, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01aff0,
                        0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01b000, 0x01b122, 0x01b132, 0x01b132, 0x01b150, 0x01b152, 0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb,
                        0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88, 0x01bc90, 0x01bc99, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5,
                        0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c,
                        0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d6c0, 0x01d6c2, 0x01d6da, 0x01d6dc,
                        0x01d6fa, 0x01d6fc, 0x01d714, 0x01d716, 0x01d734, 0x01d736, 0x01d74e, 0x01d750, 0x01d76e, 0x01d770, 0x01d788, 0x01d78a, 0x01d7a8, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7cb,
                        0x01df00, 0x01df1e, 0x01df25, 0x01df2a, 0x01e030, 0x01e06d, 0x01e100, 0x01e12c, 0x01e137, 0x01e13d, 0x01e14e, 0x01e14e, 0x01e290, 0x01e2ad, 0x01e2c0, 0x01e2eb, 0x01e4d0,
                        0x01e4eb, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe, 0x01e800, 0x01e8c4, 0x01e900, 0x01e943, 0x01e94b, 0x01e94b, 0x01ee00, 0x01ee03,
                        0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42,
                        0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59,
                        0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79,
                        0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x020000, 0x02a6df, 0x02a700, 0x02b739,
                        0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af));
    }

    private static void populateIDSB() {
        SET_ENCODINGS.put("IDSB", CodePointSet.createNoDedup(0x002ff0, 0x002ff1, 0x002ff4, 0x002ffb));
    }

    private static void populateIDST() {
        SET_ENCODINGS.put("IDST", CodePointSet.createNoDedup(0x002ff2, 0x002ff3));
    }

    private static void populateIDEO() {
        SET_ENCODINGS.put("Ideo",
                        CodePointSet.createNoDedup(0x003006, 0x003007, 0x003021, 0x003029, 0x003038, 0x00303a, 0x003400, 0x004dbf, 0x004e00, 0x009fff, 0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x016fe4,
                                        0x016fe4, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01b170, 0x01b2fb, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820,
                                        0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af));
    }

    private static void populateJOIN_C() {
        SET_ENCODINGS.put("Join_C", CodePointSet.createNoDedup(0x00200c, 0x00200d));
    }

    private static void populateLOE() {
        SET_ENCODINGS.put("LOE",
                        CodePointSet.createNoDedup(0x000e40, 0x000e44, 0x000ec0, 0x000ec4, 0x0019b5, 0x0019b7, 0x0019ba, 0x0019ba, 0x00aab5, 0x00aab6, 0x00aab9, 0x00aab9, 0x00aabb, 0x00aabc));
    }

    private static void populateLOWER() {
        SET_ENCODINGS.put("Lower", CodePointSet.createNoDedup(0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000b5, 0x0000b5, 0x0000ba, 0x0000ba, 0x0000df, 0x0000f6, 0x0000f8, 0x0000ff, 0x000101,
                        0x000101, 0x000103, 0x000103, 0x000105, 0x000105, 0x000107, 0x000107, 0x000109, 0x000109, 0x00010b, 0x00010b, 0x00010d, 0x00010d, 0x00010f, 0x00010f, 0x000111, 0x000111,
                        0x000113, 0x000113, 0x000115, 0x000115, 0x000117, 0x000117, 0x000119, 0x000119, 0x00011b, 0x00011b, 0x00011d, 0x00011d, 0x00011f, 0x00011f, 0x000121, 0x000121, 0x000123,
                        0x000123, 0x000125, 0x000125, 0x000127, 0x000127, 0x000129, 0x000129, 0x00012b, 0x00012b, 0x00012d, 0x00012d, 0x00012f, 0x00012f, 0x000131, 0x000131, 0x000133, 0x000133,
                        0x000135, 0x000135, 0x000137, 0x000138, 0x00013a, 0x00013a, 0x00013c, 0x00013c, 0x00013e, 0x00013e, 0x000140, 0x000140, 0x000142, 0x000142, 0x000144, 0x000144, 0x000146,
                        0x000146, 0x000148, 0x000149, 0x00014b, 0x00014b, 0x00014d, 0x00014d, 0x00014f, 0x00014f, 0x000151, 0x000151, 0x000153, 0x000153, 0x000155, 0x000155, 0x000157, 0x000157,
                        0x000159, 0x000159, 0x00015b, 0x00015b, 0x00015d, 0x00015d, 0x00015f, 0x00015f, 0x000161, 0x000161, 0x000163, 0x000163, 0x000165, 0x000165, 0x000167, 0x000167, 0x000169,
                        0x000169, 0x00016b, 0x00016b, 0x00016d, 0x00016d, 0x00016f, 0x00016f, 0x000171, 0x000171, 0x000173, 0x000173, 0x000175, 0x000175, 0x000177, 0x000177, 0x00017a, 0x00017a,
                        0x00017c, 0x00017c, 0x00017e, 0x000180, 0x000183, 0x000183, 0x000185, 0x000185, 0x000188, 0x000188, 0x00018c, 0x00018d, 0x000192, 0x000192, 0x000195, 0x000195, 0x000199,
                        0x00019b, 0x00019e, 0x00019e, 0x0001a1, 0x0001a1, 0x0001a3, 0x0001a3, 0x0001a5, 0x0001a5, 0x0001a8, 0x0001a8, 0x0001aa, 0x0001ab, 0x0001ad, 0x0001ad, 0x0001b0, 0x0001b0,
                        0x0001b4, 0x0001b4, 0x0001b6, 0x0001b6, 0x0001b9, 0x0001ba, 0x0001bd, 0x0001bf, 0x0001c6, 0x0001c6, 0x0001c9, 0x0001c9, 0x0001cc, 0x0001cc, 0x0001ce, 0x0001ce, 0x0001d0,
                        0x0001d0, 0x0001d2, 0x0001d2, 0x0001d4, 0x0001d4, 0x0001d6, 0x0001d6, 0x0001d8, 0x0001d8, 0x0001da, 0x0001da, 0x0001dc, 0x0001dd, 0x0001df, 0x0001df, 0x0001e1, 0x0001e1,
                        0x0001e3, 0x0001e3, 0x0001e5, 0x0001e5, 0x0001e7, 0x0001e7, 0x0001e9, 0x0001e9, 0x0001eb, 0x0001eb, 0x0001ed, 0x0001ed, 0x0001ef, 0x0001f0, 0x0001f3, 0x0001f3, 0x0001f5,
                        0x0001f5, 0x0001f9, 0x0001f9, 0x0001fb, 0x0001fb, 0x0001fd, 0x0001fd, 0x0001ff, 0x0001ff, 0x000201, 0x000201, 0x000203, 0x000203, 0x000205, 0x000205, 0x000207, 0x000207,
                        0x000209, 0x000209, 0x00020b, 0x00020b, 0x00020d, 0x00020d, 0x00020f, 0x00020f, 0x000211, 0x000211, 0x000213, 0x000213, 0x000215, 0x000215, 0x000217, 0x000217, 0x000219,
                        0x000219, 0x00021b, 0x00021b, 0x00021d, 0x00021d, 0x00021f, 0x00021f, 0x000221, 0x000221, 0x000223, 0x000223, 0x000225, 0x000225, 0x000227, 0x000227, 0x000229, 0x000229,
                        0x00022b, 0x00022b, 0x00022d, 0x00022d, 0x00022f, 0x00022f, 0x000231, 0x000231, 0x000233, 0x000239, 0x00023c, 0x00023c, 0x00023f, 0x000240, 0x000242, 0x000242, 0x000247,
                        0x000247, 0x000249, 0x000249, 0x00024b, 0x00024b, 0x00024d, 0x00024d, 0x00024f, 0x000293, 0x000295, 0x0002b8, 0x0002c0, 0x0002c1, 0x0002e0, 0x0002e4, 0x000345, 0x000345,
                        0x000371, 0x000371, 0x000373, 0x000373, 0x000377, 0x000377, 0x00037a, 0x00037d, 0x000390, 0x000390, 0x0003ac, 0x0003ce, 0x0003d0, 0x0003d1, 0x0003d5, 0x0003d7, 0x0003d9,
                        0x0003d9, 0x0003db, 0x0003db, 0x0003dd, 0x0003dd, 0x0003df, 0x0003df, 0x0003e1, 0x0003e1, 0x0003e3, 0x0003e3, 0x0003e5, 0x0003e5, 0x0003e7, 0x0003e7, 0x0003e9, 0x0003e9,
                        0x0003eb, 0x0003eb, 0x0003ed, 0x0003ed, 0x0003ef, 0x0003f3, 0x0003f5, 0x0003f5, 0x0003f8, 0x0003f8, 0x0003fb, 0x0003fc, 0x000430, 0x00045f, 0x000461, 0x000461, 0x000463,
                        0x000463, 0x000465, 0x000465, 0x000467, 0x000467, 0x000469, 0x000469, 0x00046b, 0x00046b, 0x00046d, 0x00046d, 0x00046f, 0x00046f, 0x000471, 0x000471, 0x000473, 0x000473,
                        0x000475, 0x000475, 0x000477, 0x000477, 0x000479, 0x000479, 0x00047b, 0x00047b, 0x00047d, 0x00047d, 0x00047f, 0x00047f, 0x000481, 0x000481, 0x00048b, 0x00048b, 0x00048d,
                        0x00048d, 0x00048f, 0x00048f, 0x000491, 0x000491, 0x000493, 0x000493, 0x000495, 0x000495, 0x000497, 0x000497, 0x000499, 0x000499, 0x00049b, 0x00049b, 0x00049d, 0x00049d,
                        0x00049f, 0x00049f, 0x0004a1, 0x0004a1, 0x0004a3, 0x0004a3, 0x0004a5, 0x0004a5, 0x0004a7, 0x0004a7, 0x0004a9, 0x0004a9, 0x0004ab, 0x0004ab, 0x0004ad, 0x0004ad, 0x0004af,
                        0x0004af, 0x0004b1, 0x0004b1, 0x0004b3, 0x0004b3, 0x0004b5, 0x0004b5, 0x0004b7, 0x0004b7, 0x0004b9, 0x0004b9, 0x0004bb, 0x0004bb, 0x0004bd, 0x0004bd, 0x0004bf, 0x0004bf,
                        0x0004c2, 0x0004c2, 0x0004c4, 0x0004c4, 0x0004c6, 0x0004c6, 0x0004c8, 0x0004c8, 0x0004ca, 0x0004ca, 0x0004cc, 0x0004cc, 0x0004ce, 0x0004cf, 0x0004d1, 0x0004d1, 0x0004d3,
                        0x0004d3, 0x0004d5, 0x0004d5, 0x0004d7, 0x0004d7, 0x0004d9, 0x0004d9, 0x0004db, 0x0004db, 0x0004dd, 0x0004dd, 0x0004df, 0x0004df, 0x0004e1, 0x0004e1, 0x0004e3, 0x0004e3,
                        0x0004e5, 0x0004e5, 0x0004e7, 0x0004e7, 0x0004e9, 0x0004e9, 0x0004eb, 0x0004eb, 0x0004ed, 0x0004ed, 0x0004ef, 0x0004ef, 0x0004f1, 0x0004f1, 0x0004f3, 0x0004f3, 0x0004f5,
                        0x0004f5, 0x0004f7, 0x0004f7, 0x0004f9, 0x0004f9, 0x0004fb, 0x0004fb, 0x0004fd, 0x0004fd, 0x0004ff, 0x0004ff, 0x000501, 0x000501, 0x000503, 0x000503, 0x000505, 0x000505,
                        0x000507, 0x000507, 0x000509, 0x000509, 0x00050b, 0x00050b, 0x00050d, 0x00050d, 0x00050f, 0x00050f, 0x000511, 0x000511, 0x000513, 0x000513, 0x000515, 0x000515, 0x000517,
                        0x000517, 0x000519, 0x000519, 0x00051b, 0x00051b, 0x00051d, 0x00051d, 0x00051f, 0x00051f, 0x000521, 0x000521, 0x000523, 0x000523, 0x000525, 0x000525, 0x000527, 0x000527,
                        0x000529, 0x000529, 0x00052b, 0x00052b, 0x00052d, 0x00052d, 0x00052f, 0x00052f, 0x000560, 0x000588, 0x0010d0, 0x0010fa, 0x0010fc, 0x0010ff, 0x0013f8, 0x0013fd, 0x001c80,
                        0x001c88, 0x001d00, 0x001dbf, 0x001e01, 0x001e01, 0x001e03, 0x001e03, 0x001e05, 0x001e05, 0x001e07, 0x001e07, 0x001e09, 0x001e09, 0x001e0b, 0x001e0b, 0x001e0d, 0x001e0d,
                        0x001e0f, 0x001e0f, 0x001e11, 0x001e11, 0x001e13, 0x001e13, 0x001e15, 0x001e15, 0x001e17, 0x001e17, 0x001e19, 0x001e19, 0x001e1b, 0x001e1b, 0x001e1d, 0x001e1d, 0x001e1f,
                        0x001e1f, 0x001e21, 0x001e21, 0x001e23, 0x001e23, 0x001e25, 0x001e25, 0x001e27, 0x001e27, 0x001e29, 0x001e29, 0x001e2b, 0x001e2b, 0x001e2d, 0x001e2d, 0x001e2f, 0x001e2f,
                        0x001e31, 0x001e31, 0x001e33, 0x001e33, 0x001e35, 0x001e35, 0x001e37, 0x001e37, 0x001e39, 0x001e39, 0x001e3b, 0x001e3b, 0x001e3d, 0x001e3d, 0x001e3f, 0x001e3f, 0x001e41,
                        0x001e41, 0x001e43, 0x001e43, 0x001e45, 0x001e45, 0x001e47, 0x001e47, 0x001e49, 0x001e49, 0x001e4b, 0x001e4b, 0x001e4d, 0x001e4d, 0x001e4f, 0x001e4f, 0x001e51, 0x001e51,
                        0x001e53, 0x001e53, 0x001e55, 0x001e55, 0x001e57, 0x001e57, 0x001e59, 0x001e59, 0x001e5b, 0x001e5b, 0x001e5d, 0x001e5d, 0x001e5f, 0x001e5f, 0x001e61, 0x001e61, 0x001e63,
                        0x001e63, 0x001e65, 0x001e65, 0x001e67, 0x001e67, 0x001e69, 0x001e69, 0x001e6b, 0x001e6b, 0x001e6d, 0x001e6d, 0x001e6f, 0x001e6f, 0x001e71, 0x001e71, 0x001e73, 0x001e73,
                        0x001e75, 0x001e75, 0x001e77, 0x001e77, 0x001e79, 0x001e79, 0x001e7b, 0x001e7b, 0x001e7d, 0x001e7d, 0x001e7f, 0x001e7f, 0x001e81, 0x001e81, 0x001e83, 0x001e83, 0x001e85,
                        0x001e85, 0x001e87, 0x001e87, 0x001e89, 0x001e89, 0x001e8b, 0x001e8b, 0x001e8d, 0x001e8d, 0x001e8f, 0x001e8f, 0x001e91, 0x001e91, 0x001e93, 0x001e93, 0x001e95, 0x001e9d,
                        0x001e9f, 0x001e9f, 0x001ea1, 0x001ea1, 0x001ea3, 0x001ea3, 0x001ea5, 0x001ea5, 0x001ea7, 0x001ea7, 0x001ea9, 0x001ea9, 0x001eab, 0x001eab, 0x001ead, 0x001ead, 0x001eaf,
                        0x001eaf, 0x001eb1, 0x001eb1, 0x001eb3, 0x001eb3, 0x001eb5, 0x001eb5, 0x001eb7, 0x001eb7, 0x001eb9, 0x001eb9, 0x001ebb, 0x001ebb, 0x001ebd, 0x001ebd, 0x001ebf, 0x001ebf,
                        0x001ec1, 0x001ec1, 0x001ec3, 0x001ec3, 0x001ec5, 0x001ec5, 0x001ec7, 0x001ec7, 0x001ec9, 0x001ec9, 0x001ecb, 0x001ecb, 0x001ecd, 0x001ecd, 0x001ecf, 0x001ecf, 0x001ed1,
                        0x001ed1, 0x001ed3, 0x001ed3, 0x001ed5, 0x001ed5, 0x001ed7, 0x001ed7, 0x001ed9, 0x001ed9, 0x001edb, 0x001edb, 0x001edd, 0x001edd, 0x001edf, 0x001edf, 0x001ee1, 0x001ee1,
                        0x001ee3, 0x001ee3, 0x001ee5, 0x001ee5, 0x001ee7, 0x001ee7, 0x001ee9, 0x001ee9, 0x001eeb, 0x001eeb, 0x001eed, 0x001eed, 0x001eef, 0x001eef, 0x001ef1, 0x001ef1, 0x001ef3,
                        0x001ef3, 0x001ef5, 0x001ef5, 0x001ef7, 0x001ef7, 0x001ef9, 0x001ef9, 0x001efb, 0x001efb, 0x001efd, 0x001efd, 0x001eff, 0x001f07, 0x001f10, 0x001f15, 0x001f20, 0x001f27,
                        0x001f30, 0x001f37, 0x001f40, 0x001f45, 0x001f50, 0x001f57, 0x001f60, 0x001f67, 0x001f70, 0x001f7d, 0x001f80, 0x001f87, 0x001f90, 0x001f97, 0x001fa0, 0x001fa7, 0x001fb0,
                        0x001fb4, 0x001fb6, 0x001fb7, 0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4, 0x001fc6, 0x001fc7, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fd7, 0x001fe0, 0x001fe7, 0x001ff2, 0x001ff4,
                        0x001ff6, 0x001ff7, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090, 0x00209c, 0x00210a, 0x00210a, 0x00210e, 0x00210f, 0x002113, 0x002113, 0x00212f, 0x00212f, 0x002134,
                        0x002134, 0x002139, 0x002139, 0x00213c, 0x00213d, 0x002146, 0x002149, 0x00214e, 0x00214e, 0x002170, 0x00217f, 0x002184, 0x002184, 0x0024d0, 0x0024e9, 0x002c30, 0x002c5f,
                        0x002c61, 0x002c61, 0x002c65, 0x002c66, 0x002c68, 0x002c68, 0x002c6a, 0x002c6a, 0x002c6c, 0x002c6c, 0x002c71, 0x002c71, 0x002c73, 0x002c74, 0x002c76, 0x002c7d, 0x002c81,
                        0x002c81, 0x002c83, 0x002c83, 0x002c85, 0x002c85, 0x002c87, 0x002c87, 0x002c89, 0x002c89, 0x002c8b, 0x002c8b, 0x002c8d, 0x002c8d, 0x002c8f, 0x002c8f, 0x002c91, 0x002c91,
                        0x002c93, 0x002c93, 0x002c95, 0x002c95, 0x002c97, 0x002c97, 0x002c99, 0x002c99, 0x002c9b, 0x002c9b, 0x002c9d, 0x002c9d, 0x002c9f, 0x002c9f, 0x002ca1, 0x002ca1, 0x002ca3,
                        0x002ca3, 0x002ca5, 0x002ca5, 0x002ca7, 0x002ca7, 0x002ca9, 0x002ca9, 0x002cab, 0x002cab, 0x002cad, 0x002cad, 0x002caf, 0x002caf, 0x002cb1, 0x002cb1, 0x002cb3, 0x002cb3,
                        0x002cb5, 0x002cb5, 0x002cb7, 0x002cb7, 0x002cb9, 0x002cb9, 0x002cbb, 0x002cbb, 0x002cbd, 0x002cbd, 0x002cbf, 0x002cbf, 0x002cc1, 0x002cc1, 0x002cc3, 0x002cc3, 0x002cc5,
                        0x002cc5, 0x002cc7, 0x002cc7, 0x002cc9, 0x002cc9, 0x002ccb, 0x002ccb, 0x002ccd, 0x002ccd, 0x002ccf, 0x002ccf, 0x002cd1, 0x002cd1, 0x002cd3, 0x002cd3, 0x002cd5, 0x002cd5,
                        0x002cd7, 0x002cd7, 0x002cd9, 0x002cd9, 0x002cdb, 0x002cdb, 0x002cdd, 0x002cdd, 0x002cdf, 0x002cdf, 0x002ce1, 0x002ce1, 0x002ce3, 0x002ce4, 0x002cec, 0x002cec, 0x002cee,
                        0x002cee, 0x002cf3, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x00a641, 0x00a641, 0x00a643, 0x00a643, 0x00a645, 0x00a645, 0x00a647, 0x00a647,
                        0x00a649, 0x00a649, 0x00a64b, 0x00a64b, 0x00a64d, 0x00a64d, 0x00a64f, 0x00a64f, 0x00a651, 0x00a651, 0x00a653, 0x00a653, 0x00a655, 0x00a655, 0x00a657, 0x00a657, 0x00a659,
                        0x00a659, 0x00a65b, 0x00a65b, 0x00a65d, 0x00a65d, 0x00a65f, 0x00a65f, 0x00a661, 0x00a661, 0x00a663, 0x00a663, 0x00a665, 0x00a665, 0x00a667, 0x00a667, 0x00a669, 0x00a669,
                        0x00a66b, 0x00a66b, 0x00a66d, 0x00a66d, 0x00a681, 0x00a681, 0x00a683, 0x00a683, 0x00a685, 0x00a685, 0x00a687, 0x00a687, 0x00a689, 0x00a689, 0x00a68b, 0x00a68b, 0x00a68d,
                        0x00a68d, 0x00a68f, 0x00a68f, 0x00a691, 0x00a691, 0x00a693, 0x00a693, 0x00a695, 0x00a695, 0x00a697, 0x00a697, 0x00a699, 0x00a699, 0x00a69b, 0x00a69d, 0x00a723, 0x00a723,
                        0x00a725, 0x00a725, 0x00a727, 0x00a727, 0x00a729, 0x00a729, 0x00a72b, 0x00a72b, 0x00a72d, 0x00a72d, 0x00a72f, 0x00a731, 0x00a733, 0x00a733, 0x00a735, 0x00a735, 0x00a737,
                        0x00a737, 0x00a739, 0x00a739, 0x00a73b, 0x00a73b, 0x00a73d, 0x00a73d, 0x00a73f, 0x00a73f, 0x00a741, 0x00a741, 0x00a743, 0x00a743, 0x00a745, 0x00a745, 0x00a747, 0x00a747,
                        0x00a749, 0x00a749, 0x00a74b, 0x00a74b, 0x00a74d, 0x00a74d, 0x00a74f, 0x00a74f, 0x00a751, 0x00a751, 0x00a753, 0x00a753, 0x00a755, 0x00a755, 0x00a757, 0x00a757, 0x00a759,
                        0x00a759, 0x00a75b, 0x00a75b, 0x00a75d, 0x00a75d, 0x00a75f, 0x00a75f, 0x00a761, 0x00a761, 0x00a763, 0x00a763, 0x00a765, 0x00a765, 0x00a767, 0x00a767, 0x00a769, 0x00a769,
                        0x00a76b, 0x00a76b, 0x00a76d, 0x00a76d, 0x00a76f, 0x00a778, 0x00a77a, 0x00a77a, 0x00a77c, 0x00a77c, 0x00a77f, 0x00a77f, 0x00a781, 0x00a781, 0x00a783, 0x00a783, 0x00a785,
                        0x00a785, 0x00a787, 0x00a787, 0x00a78c, 0x00a78c, 0x00a78e, 0x00a78e, 0x00a791, 0x00a791, 0x00a793, 0x00a795, 0x00a797, 0x00a797, 0x00a799, 0x00a799, 0x00a79b, 0x00a79b,
                        0x00a79d, 0x00a79d, 0x00a79f, 0x00a79f, 0x00a7a1, 0x00a7a1, 0x00a7a3, 0x00a7a3, 0x00a7a5, 0x00a7a5, 0x00a7a7, 0x00a7a7, 0x00a7a9, 0x00a7a9, 0x00a7af, 0x00a7af, 0x00a7b5,
                        0x00a7b5, 0x00a7b7, 0x00a7b7, 0x00a7b9, 0x00a7b9, 0x00a7bb, 0x00a7bb, 0x00a7bd, 0x00a7bd, 0x00a7bf, 0x00a7bf, 0x00a7c1, 0x00a7c1, 0x00a7c3, 0x00a7c3, 0x00a7c8, 0x00a7c8,
                        0x00a7ca, 0x00a7ca, 0x00a7d1, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d5, 0x00a7d7, 0x00a7d7, 0x00a7d9, 0x00a7d9, 0x00a7f2, 0x00a7f4, 0x00a7f6, 0x00a7f6, 0x00a7f8,
                        0x00a7fa, 0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab69, 0x00ab70, 0x00abbf, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00ff41, 0x00ff5a, 0x010428, 0x01044f, 0x0104d8, 0x0104fb,
                        0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010780, 0x010780, 0x010783, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010cc0,
                        0x010cf2, 0x0118c0, 0x0118df, 0x016e60, 0x016e7f, 0x01d41a, 0x01d433, 0x01d44e, 0x01d454, 0x01d456, 0x01d467, 0x01d482, 0x01d49b, 0x01d4b6, 0x01d4b9, 0x01d4bb, 0x01d4bb,
                        0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d4cf, 0x01d4ea, 0x01d503, 0x01d51e, 0x01d537, 0x01d552, 0x01d56b, 0x01d586, 0x01d59f, 0x01d5ba, 0x01d5d3, 0x01d5ee, 0x01d607, 0x01d622,
                        0x01d63b, 0x01d656, 0x01d66f, 0x01d68a, 0x01d6a5, 0x01d6c2, 0x01d6da, 0x01d6dc, 0x01d6e1, 0x01d6fc, 0x01d714, 0x01d716, 0x01d71b, 0x01d736, 0x01d74e, 0x01d750, 0x01d755,
                        0x01d770, 0x01d788, 0x01d78a, 0x01d78f, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7c9, 0x01d7cb, 0x01d7cb, 0x01df00, 0x01df09, 0x01df0b, 0x01df1e, 0x01df25, 0x01df2a, 0x01e030,
                        0x01e06d, 0x01e922, 0x01e943));
    }

    private static void populateMATH() {
        SET_ENCODINGS.put("Math", CodePointSet.createNoDedup(0x00002b, 0x00002b, 0x00003c, 0x00003e, 0x00005e, 0x00005e, 0x00007c, 0x00007c, 0x00007e, 0x00007e, 0x0000ac, 0x0000ac, 0x0000b1, 0x0000b1,
                        0x0000d7, 0x0000d7, 0x0000f7, 0x0000f7, 0x0003d0, 0x0003d2, 0x0003d5, 0x0003d5, 0x0003f0, 0x0003f1, 0x0003f4, 0x0003f6, 0x000606, 0x000608, 0x002016, 0x002016, 0x002032,
                        0x002034, 0x002040, 0x002040, 0x002044, 0x002044, 0x002052, 0x002052, 0x002061, 0x002064, 0x00207a, 0x00207e, 0x00208a, 0x00208e, 0x0020d0, 0x0020dc, 0x0020e1, 0x0020e1,
                        0x0020e5, 0x0020e6, 0x0020eb, 0x0020ef, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002118, 0x00211d, 0x002124, 0x002124, 0x002128,
                        0x002129, 0x00212c, 0x00212d, 0x00212f, 0x002131, 0x002133, 0x002138, 0x00213c, 0x002149, 0x00214b, 0x00214b, 0x002190, 0x0021a7, 0x0021a9, 0x0021ae, 0x0021b0, 0x0021b1,
                        0x0021b6, 0x0021b7, 0x0021bc, 0x0021db, 0x0021dd, 0x0021dd, 0x0021e4, 0x0021e5, 0x0021f4, 0x0022ff, 0x002308, 0x00230b, 0x002320, 0x002321, 0x00237c, 0x00237c, 0x00239b,
                        0x0023b5, 0x0023b7, 0x0023b7, 0x0023d0, 0x0023d0, 0x0023dc, 0x0023e2, 0x0025a0, 0x0025a1, 0x0025ae, 0x0025b7, 0x0025bc, 0x0025c1, 0x0025c6, 0x0025c7, 0x0025ca, 0x0025cb,
                        0x0025cf, 0x0025d3, 0x0025e2, 0x0025e2, 0x0025e4, 0x0025e4, 0x0025e7, 0x0025ec, 0x0025f8, 0x0025ff, 0x002605, 0x002606, 0x002640, 0x002640, 0x002642, 0x002642, 0x002660,
                        0x002663, 0x00266d, 0x00266f, 0x0027c0, 0x0027ff, 0x002900, 0x002aff, 0x002b30, 0x002b44, 0x002b47, 0x002b4c, 0x00fb29, 0x00fb29, 0x00fe61, 0x00fe66, 0x00fe68, 0x00fe68,
                        0x00ff0b, 0x00ff0b, 0x00ff1c, 0x00ff1e, 0x00ff3c, 0x00ff3c, 0x00ff3e, 0x00ff3e, 0x00ff5c, 0x00ff5c, 0x00ff5e, 0x00ff5e, 0x00ffe2, 0x00ffe2, 0x00ffe9, 0x00ffec, 0x01d400,
                        0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3,
                        0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a,
                        0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d7cb, 0x01d7ce, 0x01d7ff, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27,
                        0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d,
                        0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62,
                        0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1,
                        0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x01eef0, 0x01eef1));
    }

    private static void populateNCHAR() {
        SET_ENCODINGS.put("NChar",
                        CodePointSet.createNoDedup(0x00fdd0, 0x00fdef, 0x00fffe, 0x00ffff, 0x01fffe, 0x01ffff, 0x02fffe, 0x02ffff, 0x03fffe, 0x03ffff, 0x04fffe, 0x04ffff, 0x05fffe, 0x05ffff, 0x06fffe,
                                        0x06ffff, 0x07fffe, 0x07ffff, 0x08fffe, 0x08ffff, 0x09fffe, 0x09ffff, 0x0afffe, 0x0affff, 0x0bfffe, 0x0bffff, 0x0cfffe, 0x0cffff, 0x0dfffe, 0x0dffff, 0x0efffe,
                                        0x0effff, 0x0ffffe, 0x0fffff, 0x10fffe, 0x10ffff));
    }

    private static void populateOALPHA() {
        SET_ENCODINGS.put("OAlpha", CodePointSet.createNoDedup(0x000345, 0x000345, 0x0005b0, 0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4, 0x0005c5, 0x0005c7, 0x0005c7, 0x000610,
                        0x00061a, 0x00064b, 0x000657, 0x000659, 0x00065f, 0x000670, 0x000670, 0x0006d6, 0x0006dc, 0x0006e1, 0x0006e4, 0x0006e7, 0x0006e8, 0x0006ed, 0x0006ed, 0x000711, 0x000711,
                        0x000730, 0x00073f, 0x0007a6, 0x0007b0, 0x000816, 0x000817, 0x00081b, 0x000823, 0x000825, 0x000827, 0x000829, 0x00082c, 0x0008d4, 0x0008df, 0x0008e3, 0x0008e9, 0x0008f0,
                        0x000903, 0x00093a, 0x00093b, 0x00093e, 0x00094c, 0x00094e, 0x00094f, 0x000955, 0x000957, 0x000962, 0x000963, 0x000981, 0x000983, 0x0009be, 0x0009c4, 0x0009c7, 0x0009c8,
                        0x0009cb, 0x0009cc, 0x0009d7, 0x0009d7, 0x0009e2, 0x0009e3, 0x000a01, 0x000a03, 0x000a3e, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4c, 0x000a51, 0x000a51, 0x000a70,
                        0x000a71, 0x000a75, 0x000a75, 0x000a81, 0x000a83, 0x000abe, 0x000ac5, 0x000ac7, 0x000ac9, 0x000acb, 0x000acc, 0x000ae2, 0x000ae3, 0x000afa, 0x000afc, 0x000b01, 0x000b03,
                        0x000b3e, 0x000b44, 0x000b47, 0x000b48, 0x000b4b, 0x000b4c, 0x000b56, 0x000b57, 0x000b62, 0x000b63, 0x000b82, 0x000b82, 0x000bbe, 0x000bc2, 0x000bc6, 0x000bc8, 0x000bca,
                        0x000bcc, 0x000bd7, 0x000bd7, 0x000c00, 0x000c04, 0x000c3e, 0x000c44, 0x000c46, 0x000c48, 0x000c4a, 0x000c4c, 0x000c55, 0x000c56, 0x000c62, 0x000c63, 0x000c81, 0x000c83,
                        0x000cbe, 0x000cc4, 0x000cc6, 0x000cc8, 0x000cca, 0x000ccc, 0x000cd5, 0x000cd6, 0x000ce2, 0x000ce3, 0x000cf3, 0x000cf3, 0x000d00, 0x000d03, 0x000d3e, 0x000d44, 0x000d46,
                        0x000d48, 0x000d4a, 0x000d4c, 0x000d57, 0x000d57, 0x000d62, 0x000d63, 0x000d81, 0x000d83, 0x000dcf, 0x000dd4, 0x000dd6, 0x000dd6, 0x000dd8, 0x000ddf, 0x000df2, 0x000df3,
                        0x000e31, 0x000e31, 0x000e34, 0x000e3a, 0x000e4d, 0x000e4d, 0x000eb1, 0x000eb1, 0x000eb4, 0x000eb9, 0x000ebb, 0x000ebc, 0x000ecd, 0x000ecd, 0x000f71, 0x000f83, 0x000f8d,
                        0x000f97, 0x000f99, 0x000fbc, 0x00102b, 0x001036, 0x001038, 0x001038, 0x00103b, 0x00103e, 0x001056, 0x001059, 0x00105e, 0x001060, 0x001062, 0x001064, 0x001067, 0x00106d,
                        0x001071, 0x001074, 0x001082, 0x00108d, 0x00108f, 0x00108f, 0x00109a, 0x00109d, 0x001712, 0x001713, 0x001732, 0x001733, 0x001752, 0x001753, 0x001772, 0x001773, 0x0017b6,
                        0x0017c8, 0x001885, 0x001886, 0x0018a9, 0x0018a9, 0x001920, 0x00192b, 0x001930, 0x001938, 0x001a17, 0x001a1b, 0x001a55, 0x001a5e, 0x001a61, 0x001a74, 0x001abf, 0x001ac0,
                        0x001acc, 0x001ace, 0x001b00, 0x001b04, 0x001b35, 0x001b43, 0x001b80, 0x001b82, 0x001ba1, 0x001ba9, 0x001bac, 0x001bad, 0x001be7, 0x001bf1, 0x001c24, 0x001c36, 0x001de7,
                        0x001df4, 0x0024b6, 0x0024e9, 0x002de0, 0x002dff, 0x00a674, 0x00a67b, 0x00a69e, 0x00a69f, 0x00a802, 0x00a802, 0x00a80b, 0x00a80b, 0x00a823, 0x00a827, 0x00a880, 0x00a881,
                        0x00a8b4, 0x00a8c3, 0x00a8c5, 0x00a8c5, 0x00a8ff, 0x00a8ff, 0x00a926, 0x00a92a, 0x00a947, 0x00a952, 0x00a980, 0x00a983, 0x00a9b4, 0x00a9bf, 0x00a9e5, 0x00a9e5, 0x00aa29,
                        0x00aa36, 0x00aa43, 0x00aa43, 0x00aa4c, 0x00aa4d, 0x00aa7b, 0x00aa7d, 0x00aab0, 0x00aab0, 0x00aab2, 0x00aab4, 0x00aab7, 0x00aab8, 0x00aabe, 0x00aabe, 0x00aaeb, 0x00aaef,
                        0x00aaf5, 0x00aaf5, 0x00abe3, 0x00abea, 0x00fb1e, 0x00fb1e, 0x010376, 0x01037a, 0x010a01, 0x010a03, 0x010a05, 0x010a06, 0x010a0c, 0x010a0f, 0x010d24, 0x010d27, 0x010eab,
                        0x010eac, 0x011000, 0x011002, 0x011038, 0x011045, 0x011073, 0x011074, 0x011080, 0x011082, 0x0110b0, 0x0110b8, 0x0110c2, 0x0110c2, 0x011100, 0x011102, 0x011127, 0x011132,
                        0x011145, 0x011146, 0x011180, 0x011182, 0x0111b3, 0x0111bf, 0x0111ce, 0x0111cf, 0x01122c, 0x011234, 0x011237, 0x011237, 0x01123e, 0x01123e, 0x011241, 0x011241, 0x0112df,
                        0x0112e8, 0x011300, 0x011303, 0x01133e, 0x011344, 0x011347, 0x011348, 0x01134b, 0x01134c, 0x011357, 0x011357, 0x011362, 0x011363, 0x011435, 0x011441, 0x011443, 0x011445,
                        0x0114b0, 0x0114c1, 0x0115af, 0x0115b5, 0x0115b8, 0x0115be, 0x0115dc, 0x0115dd, 0x011630, 0x01163e, 0x011640, 0x011640, 0x0116ab, 0x0116b5, 0x01171d, 0x01172a, 0x01182c,
                        0x011838, 0x011930, 0x011935, 0x011937, 0x011938, 0x01193b, 0x01193c, 0x011940, 0x011940, 0x011942, 0x011942, 0x0119d1, 0x0119d7, 0x0119da, 0x0119df, 0x0119e4, 0x0119e4,
                        0x011a01, 0x011a0a, 0x011a35, 0x011a39, 0x011a3b, 0x011a3e, 0x011a51, 0x011a5b, 0x011a8a, 0x011a97, 0x011c2f, 0x011c36, 0x011c38, 0x011c3e, 0x011c92, 0x011ca7, 0x011ca9,
                        0x011cb6, 0x011d31, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d41, 0x011d43, 0x011d43, 0x011d47, 0x011d47, 0x011d8a, 0x011d8e, 0x011d90, 0x011d91,
                        0x011d93, 0x011d96, 0x011ef3, 0x011ef6, 0x011f00, 0x011f01, 0x011f03, 0x011f03, 0x011f34, 0x011f3a, 0x011f3e, 0x011f40, 0x016f4f, 0x016f4f, 0x016f51, 0x016f87, 0x016f8f,
                        0x016f92, 0x016ff0, 0x016ff1, 0x01bc9e, 0x01bc9e, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b, 0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a, 0x01e08f, 0x01e08f,
                        0x01e947, 0x01e947, 0x01f130, 0x01f149, 0x01f150, 0x01f169, 0x01f170, 0x01f189));
    }

    private static void populateOIDC() {
        SET_ENCODINGS.put("OIDC", CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000387, 0x000387, 0x001369, 0x001371, 0x0019da, 0x0019da));
    }

    private static void populateOIDS() {
        SET_ENCODINGS.put("OIDS", CodePointSet.createNoDedup(0x001885, 0x001886, 0x002118, 0x002118, 0x00212e, 0x00212e, 0x00309b, 0x00309c));
    }

    private static void populateOLOWER() {
        SET_ENCODINGS.put("OLower",
                        CodePointSet.createNoDedup(0x0000aa, 0x0000aa, 0x0000ba, 0x0000ba, 0x0002b0, 0x0002b8, 0x0002c0, 0x0002c1, 0x0002e0, 0x0002e4, 0x000345, 0x000345, 0x00037a, 0x00037a, 0x0010fc,
                                        0x0010fc, 0x001d2c, 0x001d6a, 0x001d78, 0x001d78, 0x001d9b, 0x001dbf, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090, 0x00209c, 0x002170, 0x00217f, 0x0024d0,
                                        0x0024e9, 0x002c7c, 0x002c7d, 0x00a69c, 0x00a69d, 0x00a770, 0x00a770, 0x00a7f2, 0x00a7f4, 0x00a7f8, 0x00a7f9, 0x00ab5c, 0x00ab5f, 0x00ab69, 0x00ab69, 0x010780,
                                        0x010780, 0x010783, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x01e030, 0x01e06d));
    }

    private static void populateOUPPER() {
        SET_ENCODINGS.put("OUpper", CodePointSet.createNoDedup(0x002160, 0x00216f, 0x0024b6, 0x0024cf, 0x01f130, 0x01f149, 0x01f150, 0x01f169, 0x01f170, 0x01f189));
    }

    private static void populatePAT_SYN() {
        SET_ENCODINGS.put("Pat_Syn",
                        CodePointSet.createNoDedup(0x000021, 0x00002f, 0x00003a, 0x000040, 0x00005b, 0x00005e, 0x000060, 0x000060, 0x00007b, 0x00007e, 0x0000a1, 0x0000a7, 0x0000a9, 0x0000a9, 0x0000ab,
                                        0x0000ac, 0x0000ae, 0x0000ae, 0x0000b0, 0x0000b1, 0x0000b6, 0x0000b6, 0x0000bb, 0x0000bb, 0x0000bf, 0x0000bf, 0x0000d7, 0x0000d7, 0x0000f7, 0x0000f7, 0x002010,
                                        0x002027, 0x002030, 0x00203e, 0x002041, 0x002053, 0x002055, 0x00205e, 0x002190, 0x00245f, 0x002500, 0x002775, 0x002794, 0x002bff, 0x002e00, 0x002e7f, 0x003001,
                                        0x003003, 0x003008, 0x003020, 0x003030, 0x003030, 0x00fd3e, 0x00fd3f, 0x00fe45, 0x00fe46));
    }

    private static void populatePAT_WS() {
        SET_ENCODINGS.put("Pat_WS", CodePointSet.createNoDedup(0x000009, 0x00000d, 0x000020, 0x000020, 0x000085, 0x000085, 0x00200e, 0x00200f, 0x002028, 0x002029));
    }

    private static void populateQMARK() {
        SET_ENCODINGS.put("QMark", CodePointSet.createNoDedup(0x000022, 0x000022, 0x000027, 0x000027, 0x0000ab, 0x0000ab, 0x0000bb, 0x0000bb, 0x002018, 0x00201f, 0x002039, 0x00203a, 0x002e42,
                        0x002e42, 0x00300c, 0x00300f, 0x00301d, 0x00301f, 0x00fe41, 0x00fe44, 0x00ff02, 0x00ff02, 0x00ff07, 0x00ff07, 0x00ff62, 0x00ff63));
    }

    private static void populateRI() {
        SET_ENCODINGS.put("RI", CodePointSet.createNoDedup(0x01f1e6, 0x01f1ff));
    }

    private static void populateRADICAL() {
        SET_ENCODINGS.put("Radical", CodePointSet.createNoDedup(0x002e80, 0x002e99, 0x002e9b, 0x002ef3, 0x002f00, 0x002fd5));
    }

    private static void populateSD() {
        SET_ENCODINGS.put("SD",
                        CodePointSet.createNoDedup(0x000069, 0x00006a, 0x00012f, 0x00012f, 0x000249, 0x000249, 0x000268, 0x000268, 0x00029d, 0x00029d, 0x0002b2, 0x0002b2, 0x0003f3, 0x0003f3, 0x000456,
                                        0x000456, 0x000458, 0x000458, 0x001d62, 0x001d62, 0x001d96, 0x001d96, 0x001da4, 0x001da4, 0x001da8, 0x001da8, 0x001e2d, 0x001e2d, 0x001ecb, 0x001ecb, 0x002071,
                                        0x002071, 0x002148, 0x002149, 0x002c7c, 0x002c7c, 0x01d422, 0x01d423, 0x01d456, 0x01d457, 0x01d48a, 0x01d48b, 0x01d4be, 0x01d4bf, 0x01d4f2, 0x01d4f3, 0x01d526,
                                        0x01d527, 0x01d55a, 0x01d55b, 0x01d58e, 0x01d58f, 0x01d5c2, 0x01d5c3, 0x01d5f6, 0x01d5f7, 0x01d62a, 0x01d62b, 0x01d65e, 0x01d65f, 0x01d692, 0x01d693, 0x01df1a,
                                        0x01df1a, 0x01e04c, 0x01e04d, 0x01e068, 0x01e068));
    }

    private static void populateSTERM() {
        SET_ENCODINGS.put("STerm", CodePointSet.createNoDedup(0x000021, 0x000021, 0x00002e, 0x00002e, 0x00003f, 0x00003f, 0x000589, 0x000589, 0x00061d, 0x00061f, 0x0006d4, 0x0006d4, 0x000700,
                        0x000702, 0x0007f9, 0x0007f9, 0x000837, 0x000837, 0x000839, 0x000839, 0x00083d, 0x00083e, 0x000964, 0x000965, 0x00104a, 0x00104b, 0x001362, 0x001362, 0x001367, 0x001368,
                        0x00166e, 0x00166e, 0x001735, 0x001736, 0x001803, 0x001803, 0x001809, 0x001809, 0x001944, 0x001945, 0x001aa8, 0x001aab, 0x001b5a, 0x001b5b, 0x001b5e, 0x001b5f, 0x001b7d,
                        0x001b7e, 0x001c3b, 0x001c3c, 0x001c7e, 0x001c7f, 0x00203c, 0x00203d, 0x002047, 0x002049, 0x002e2e, 0x002e2e, 0x002e3c, 0x002e3c, 0x002e53, 0x002e54, 0x003002, 0x003002,
                        0x00a4ff, 0x00a4ff, 0x00a60e, 0x00a60f, 0x00a6f3, 0x00a6f3, 0x00a6f7, 0x00a6f7, 0x00a876, 0x00a877, 0x00a8ce, 0x00a8cf, 0x00a92f, 0x00a92f, 0x00a9c8, 0x00a9c9, 0x00aa5d,
                        0x00aa5f, 0x00aaf0, 0x00aaf1, 0x00abeb, 0x00abeb, 0x00fe52, 0x00fe52, 0x00fe56, 0x00fe57, 0x00ff01, 0x00ff01, 0x00ff0e, 0x00ff0e, 0x00ff1f, 0x00ff1f, 0x00ff61, 0x00ff61,
                        0x010a56, 0x010a57, 0x010f55, 0x010f59, 0x010f86, 0x010f89, 0x011047, 0x011048, 0x0110be, 0x0110c1, 0x011141, 0x011143, 0x0111c5, 0x0111c6, 0x0111cd, 0x0111cd, 0x0111de,
                        0x0111df, 0x011238, 0x011239, 0x01123b, 0x01123c, 0x0112a9, 0x0112a9, 0x01144b, 0x01144c, 0x0115c2, 0x0115c3, 0x0115c9, 0x0115d7, 0x011641, 0x011642, 0x01173c, 0x01173e,
                        0x011944, 0x011944, 0x011946, 0x011946, 0x011a42, 0x011a43, 0x011a9b, 0x011a9c, 0x011c41, 0x011c42, 0x011ef7, 0x011ef8, 0x011f43, 0x011f44, 0x016a6e, 0x016a6f, 0x016af5,
                        0x016af5, 0x016b37, 0x016b38, 0x016b44, 0x016b44, 0x016e98, 0x016e98, 0x01bc9f, 0x01bc9f, 0x01da88, 0x01da88));
    }

    private static void populateTERM() {
        SET_ENCODINGS.put("Term", CodePointSet.createNoDedup(0x000021, 0x000021, 0x00002c, 0x00002c, 0x00002e, 0x00002e, 0x00003a, 0x00003b, 0x00003f, 0x00003f, 0x00037e, 0x00037e, 0x000387, 0x000387,
                        0x000589, 0x000589, 0x0005c3, 0x0005c3, 0x00060c, 0x00060c, 0x00061b, 0x00061b, 0x00061d, 0x00061f, 0x0006d4, 0x0006d4, 0x000700, 0x00070a, 0x00070c, 0x00070c, 0x0007f8,
                        0x0007f9, 0x000830, 0x00083e, 0x00085e, 0x00085e, 0x000964, 0x000965, 0x000e5a, 0x000e5b, 0x000f08, 0x000f08, 0x000f0d, 0x000f12, 0x00104a, 0x00104b, 0x001361, 0x001368,
                        0x00166e, 0x00166e, 0x0016eb, 0x0016ed, 0x001735, 0x001736, 0x0017d4, 0x0017d6, 0x0017da, 0x0017da, 0x001802, 0x001805, 0x001808, 0x001809, 0x001944, 0x001945, 0x001aa8,
                        0x001aab, 0x001b5a, 0x001b5b, 0x001b5d, 0x001b5f, 0x001b7d, 0x001b7e, 0x001c3b, 0x001c3f, 0x001c7e, 0x001c7f, 0x00203c, 0x00203d, 0x002047, 0x002049, 0x002e2e, 0x002e2e,
                        0x002e3c, 0x002e3c, 0x002e41, 0x002e41, 0x002e4c, 0x002e4c, 0x002e4e, 0x002e4f, 0x002e53, 0x002e54, 0x003001, 0x003002, 0x00a4fe, 0x00a4ff, 0x00a60d, 0x00a60f, 0x00a6f3,
                        0x00a6f7, 0x00a876, 0x00a877, 0x00a8ce, 0x00a8cf, 0x00a92f, 0x00a92f, 0x00a9c7, 0x00a9c9, 0x00aa5d, 0x00aa5f, 0x00aadf, 0x00aadf, 0x00aaf0, 0x00aaf1, 0x00abeb, 0x00abeb,
                        0x00fe50, 0x00fe52, 0x00fe54, 0x00fe57, 0x00ff01, 0x00ff01, 0x00ff0c, 0x00ff0c, 0x00ff0e, 0x00ff0e, 0x00ff1a, 0x00ff1b, 0x00ff1f, 0x00ff1f, 0x00ff61, 0x00ff61, 0x00ff64,
                        0x00ff64, 0x01039f, 0x01039f, 0x0103d0, 0x0103d0, 0x010857, 0x010857, 0x01091f, 0x01091f, 0x010a56, 0x010a57, 0x010af0, 0x010af5, 0x010b3a, 0x010b3f, 0x010b99, 0x010b9c,
                        0x010f55, 0x010f59, 0x010f86, 0x010f89, 0x011047, 0x01104d, 0x0110be, 0x0110c1, 0x011141, 0x011143, 0x0111c5, 0x0111c6, 0x0111cd, 0x0111cd, 0x0111de, 0x0111df, 0x011238,
                        0x01123c, 0x0112a9, 0x0112a9, 0x01144b, 0x01144d, 0x01145a, 0x01145b, 0x0115c2, 0x0115c5, 0x0115c9, 0x0115d7, 0x011641, 0x011642, 0x01173c, 0x01173e, 0x011944, 0x011944,
                        0x011946, 0x011946, 0x011a42, 0x011a43, 0x011a9b, 0x011a9c, 0x011aa1, 0x011aa2, 0x011c41, 0x011c43, 0x011c71, 0x011c71, 0x011ef7, 0x011ef8, 0x011f43, 0x011f44, 0x012470,
                        0x012474, 0x016a6e, 0x016a6f, 0x016af5, 0x016af5, 0x016b37, 0x016b39, 0x016b44, 0x016b44, 0x016e97, 0x016e98, 0x01bc9f, 0x01bc9f, 0x01da87, 0x01da8a));
    }

    private static void populateUIDEO() {
        SET_ENCODINGS.put("UIdeo",
                        CodePointSet.createNoDedup(0x003400, 0x004dbf, 0x004e00, 0x009fff, 0x00fa0e, 0x00fa0f, 0x00fa11, 0x00fa11, 0x00fa13, 0x00fa14, 0x00fa1f, 0x00fa1f, 0x00fa21, 0x00fa21, 0x00fa23,
                                        0x00fa24, 0x00fa27, 0x00fa29, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x030000, 0x03134a, 0x031350,
                                        0x0323af));
    }

    private static void populateUPPER() {
        SET_ENCODINGS.put("Upper", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000de, 0x000100, 0x000100, 0x000102, 0x000102, 0x000104, 0x000104, 0x000106,
                        0x000106, 0x000108, 0x000108, 0x00010a, 0x00010a, 0x00010c, 0x00010c, 0x00010e, 0x00010e, 0x000110, 0x000110, 0x000112, 0x000112, 0x000114, 0x000114, 0x000116, 0x000116,
                        0x000118, 0x000118, 0x00011a, 0x00011a, 0x00011c, 0x00011c, 0x00011e, 0x00011e, 0x000120, 0x000120, 0x000122, 0x000122, 0x000124, 0x000124, 0x000126, 0x000126, 0x000128,
                        0x000128, 0x00012a, 0x00012a, 0x00012c, 0x00012c, 0x00012e, 0x00012e, 0x000130, 0x000130, 0x000132, 0x000132, 0x000134, 0x000134, 0x000136, 0x000136, 0x000139, 0x000139,
                        0x00013b, 0x00013b, 0x00013d, 0x00013d, 0x00013f, 0x00013f, 0x000141, 0x000141, 0x000143, 0x000143, 0x000145, 0x000145, 0x000147, 0x000147, 0x00014a, 0x00014a, 0x00014c,
                        0x00014c, 0x00014e, 0x00014e, 0x000150, 0x000150, 0x000152, 0x000152, 0x000154, 0x000154, 0x000156, 0x000156, 0x000158, 0x000158, 0x00015a, 0x00015a, 0x00015c, 0x00015c,
                        0x00015e, 0x00015e, 0x000160, 0x000160, 0x000162, 0x000162, 0x000164, 0x000164, 0x000166, 0x000166, 0x000168, 0x000168, 0x00016a, 0x00016a, 0x00016c, 0x00016c, 0x00016e,
                        0x00016e, 0x000170, 0x000170, 0x000172, 0x000172, 0x000174, 0x000174, 0x000176, 0x000176, 0x000178, 0x000179, 0x00017b, 0x00017b, 0x00017d, 0x00017d, 0x000181, 0x000182,
                        0x000184, 0x000184, 0x000186, 0x000187, 0x000189, 0x00018b, 0x00018e, 0x000191, 0x000193, 0x000194, 0x000196, 0x000198, 0x00019c, 0x00019d, 0x00019f, 0x0001a0, 0x0001a2,
                        0x0001a2, 0x0001a4, 0x0001a4, 0x0001a6, 0x0001a7, 0x0001a9, 0x0001a9, 0x0001ac, 0x0001ac, 0x0001ae, 0x0001af, 0x0001b1, 0x0001b3, 0x0001b5, 0x0001b5, 0x0001b7, 0x0001b8,
                        0x0001bc, 0x0001bc, 0x0001c4, 0x0001c4, 0x0001c7, 0x0001c7, 0x0001ca, 0x0001ca, 0x0001cd, 0x0001cd, 0x0001cf, 0x0001cf, 0x0001d1, 0x0001d1, 0x0001d3, 0x0001d3, 0x0001d5,
                        0x0001d5, 0x0001d7, 0x0001d7, 0x0001d9, 0x0001d9, 0x0001db, 0x0001db, 0x0001de, 0x0001de, 0x0001e0, 0x0001e0, 0x0001e2, 0x0001e2, 0x0001e4, 0x0001e4, 0x0001e6, 0x0001e6,
                        0x0001e8, 0x0001e8, 0x0001ea, 0x0001ea, 0x0001ec, 0x0001ec, 0x0001ee, 0x0001ee, 0x0001f1, 0x0001f1, 0x0001f4, 0x0001f4, 0x0001f6, 0x0001f8, 0x0001fa, 0x0001fa, 0x0001fc,
                        0x0001fc, 0x0001fe, 0x0001fe, 0x000200, 0x000200, 0x000202, 0x000202, 0x000204, 0x000204, 0x000206, 0x000206, 0x000208, 0x000208, 0x00020a, 0x00020a, 0x00020c, 0x00020c,
                        0x00020e, 0x00020e, 0x000210, 0x000210, 0x000212, 0x000212, 0x000214, 0x000214, 0x000216, 0x000216, 0x000218, 0x000218, 0x00021a, 0x00021a, 0x00021c, 0x00021c, 0x00021e,
                        0x00021e, 0x000220, 0x000220, 0x000222, 0x000222, 0x000224, 0x000224, 0x000226, 0x000226, 0x000228, 0x000228, 0x00022a, 0x00022a, 0x00022c, 0x00022c, 0x00022e, 0x00022e,
                        0x000230, 0x000230, 0x000232, 0x000232, 0x00023a, 0x00023b, 0x00023d, 0x00023e, 0x000241, 0x000241, 0x000243, 0x000246, 0x000248, 0x000248, 0x00024a, 0x00024a, 0x00024c,
                        0x00024c, 0x00024e, 0x00024e, 0x000370, 0x000370, 0x000372, 0x000372, 0x000376, 0x000376, 0x00037f, 0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c,
                        0x00038e, 0x00038f, 0x000391, 0x0003a1, 0x0003a3, 0x0003ab, 0x0003cf, 0x0003cf, 0x0003d2, 0x0003d4, 0x0003d8, 0x0003d8, 0x0003da, 0x0003da, 0x0003dc, 0x0003dc, 0x0003de,
                        0x0003de, 0x0003e0, 0x0003e0, 0x0003e2, 0x0003e2, 0x0003e4, 0x0003e4, 0x0003e6, 0x0003e6, 0x0003e8, 0x0003e8, 0x0003ea, 0x0003ea, 0x0003ec, 0x0003ec, 0x0003ee, 0x0003ee,
                        0x0003f4, 0x0003f4, 0x0003f7, 0x0003f7, 0x0003f9, 0x0003fa, 0x0003fd, 0x00042f, 0x000460, 0x000460, 0x000462, 0x000462, 0x000464, 0x000464, 0x000466, 0x000466, 0x000468,
                        0x000468, 0x00046a, 0x00046a, 0x00046c, 0x00046c, 0x00046e, 0x00046e, 0x000470, 0x000470, 0x000472, 0x000472, 0x000474, 0x000474, 0x000476, 0x000476, 0x000478, 0x000478,
                        0x00047a, 0x00047a, 0x00047c, 0x00047c, 0x00047e, 0x00047e, 0x000480, 0x000480, 0x00048a, 0x00048a, 0x00048c, 0x00048c, 0x00048e, 0x00048e, 0x000490, 0x000490, 0x000492,
                        0x000492, 0x000494, 0x000494, 0x000496, 0x000496, 0x000498, 0x000498, 0x00049a, 0x00049a, 0x00049c, 0x00049c, 0x00049e, 0x00049e, 0x0004a0, 0x0004a0, 0x0004a2, 0x0004a2,
                        0x0004a4, 0x0004a4, 0x0004a6, 0x0004a6, 0x0004a8, 0x0004a8, 0x0004aa, 0x0004aa, 0x0004ac, 0x0004ac, 0x0004ae, 0x0004ae, 0x0004b0, 0x0004b0, 0x0004b2, 0x0004b2, 0x0004b4,
                        0x0004b4, 0x0004b6, 0x0004b6, 0x0004b8, 0x0004b8, 0x0004ba, 0x0004ba, 0x0004bc, 0x0004bc, 0x0004be, 0x0004be, 0x0004c0, 0x0004c1, 0x0004c3, 0x0004c3, 0x0004c5, 0x0004c5,
                        0x0004c7, 0x0004c7, 0x0004c9, 0x0004c9, 0x0004cb, 0x0004cb, 0x0004cd, 0x0004cd, 0x0004d0, 0x0004d0, 0x0004d2, 0x0004d2, 0x0004d4, 0x0004d4, 0x0004d6, 0x0004d6, 0x0004d8,
                        0x0004d8, 0x0004da, 0x0004da, 0x0004dc, 0x0004dc, 0x0004de, 0x0004de, 0x0004e0, 0x0004e0, 0x0004e2, 0x0004e2, 0x0004e4, 0x0004e4, 0x0004e6, 0x0004e6, 0x0004e8, 0x0004e8,
                        0x0004ea, 0x0004ea, 0x0004ec, 0x0004ec, 0x0004ee, 0x0004ee, 0x0004f0, 0x0004f0, 0x0004f2, 0x0004f2, 0x0004f4, 0x0004f4, 0x0004f6, 0x0004f6, 0x0004f8, 0x0004f8, 0x0004fa,
                        0x0004fa, 0x0004fc, 0x0004fc, 0x0004fe, 0x0004fe, 0x000500, 0x000500, 0x000502, 0x000502, 0x000504, 0x000504, 0x000506, 0x000506, 0x000508, 0x000508, 0x00050a, 0x00050a,
                        0x00050c, 0x00050c, 0x00050e, 0x00050e, 0x000510, 0x000510, 0x000512, 0x000512, 0x000514, 0x000514, 0x000516, 0x000516, 0x000518, 0x000518, 0x00051a, 0x00051a, 0x00051c,
                        0x00051c, 0x00051e, 0x00051e, 0x000520, 0x000520, 0x000522, 0x000522, 0x000524, 0x000524, 0x000526, 0x000526, 0x000528, 0x000528, 0x00052a, 0x00052a, 0x00052c, 0x00052c,
                        0x00052e, 0x00052e, 0x000531, 0x000556, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0013a0, 0x0013f5, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001e00,
                        0x001e00, 0x001e02, 0x001e02, 0x001e04, 0x001e04, 0x001e06, 0x001e06, 0x001e08, 0x001e08, 0x001e0a, 0x001e0a, 0x001e0c, 0x001e0c, 0x001e0e, 0x001e0e, 0x001e10, 0x001e10,
                        0x001e12, 0x001e12, 0x001e14, 0x001e14, 0x001e16, 0x001e16, 0x001e18, 0x001e18, 0x001e1a, 0x001e1a, 0x001e1c, 0x001e1c, 0x001e1e, 0x001e1e, 0x001e20, 0x001e20, 0x001e22,
                        0x001e22, 0x001e24, 0x001e24, 0x001e26, 0x001e26, 0x001e28, 0x001e28, 0x001e2a, 0x001e2a, 0x001e2c, 0x001e2c, 0x001e2e, 0x001e2e, 0x001e30, 0x001e30, 0x001e32, 0x001e32,
                        0x001e34, 0x001e34, 0x001e36, 0x001e36, 0x001e38, 0x001e38, 0x001e3a, 0x001e3a, 0x001e3c, 0x001e3c, 0x001e3e, 0x001e3e, 0x001e40, 0x001e40, 0x001e42, 0x001e42, 0x001e44,
                        0x001e44, 0x001e46, 0x001e46, 0x001e48, 0x001e48, 0x001e4a, 0x001e4a, 0x001e4c, 0x001e4c, 0x001e4e, 0x001e4e, 0x001e50, 0x001e50, 0x001e52, 0x001e52, 0x001e54, 0x001e54,
                        0x001e56, 0x001e56, 0x001e58, 0x001e58, 0x001e5a, 0x001e5a, 0x001e5c, 0x001e5c, 0x001e5e, 0x001e5e, 0x001e60, 0x001e60, 0x001e62, 0x001e62, 0x001e64, 0x001e64, 0x001e66,
                        0x001e66, 0x001e68, 0x001e68, 0x001e6a, 0x001e6a, 0x001e6c, 0x001e6c, 0x001e6e, 0x001e6e, 0x001e70, 0x001e70, 0x001e72, 0x001e72, 0x001e74, 0x001e74, 0x001e76, 0x001e76,
                        0x001e78, 0x001e78, 0x001e7a, 0x001e7a, 0x001e7c, 0x001e7c, 0x001e7e, 0x001e7e, 0x001e80, 0x001e80, 0x001e82, 0x001e82, 0x001e84, 0x001e84, 0x001e86, 0x001e86, 0x001e88,
                        0x001e88, 0x001e8a, 0x001e8a, 0x001e8c, 0x001e8c, 0x001e8e, 0x001e8e, 0x001e90, 0x001e90, 0x001e92, 0x001e92, 0x001e94, 0x001e94, 0x001e9e, 0x001e9e, 0x001ea0, 0x001ea0,
                        0x001ea2, 0x001ea2, 0x001ea4, 0x001ea4, 0x001ea6, 0x001ea6, 0x001ea8, 0x001ea8, 0x001eaa, 0x001eaa, 0x001eac, 0x001eac, 0x001eae, 0x001eae, 0x001eb0, 0x001eb0, 0x001eb2,
                        0x001eb2, 0x001eb4, 0x001eb4, 0x001eb6, 0x001eb6, 0x001eb8, 0x001eb8, 0x001eba, 0x001eba, 0x001ebc, 0x001ebc, 0x001ebe, 0x001ebe, 0x001ec0, 0x001ec0, 0x001ec2, 0x001ec2,
                        0x001ec4, 0x001ec4, 0x001ec6, 0x001ec6, 0x001ec8, 0x001ec8, 0x001eca, 0x001eca, 0x001ecc, 0x001ecc, 0x001ece, 0x001ece, 0x001ed0, 0x001ed0, 0x001ed2, 0x001ed2, 0x001ed4,
                        0x001ed4, 0x001ed6, 0x001ed6, 0x001ed8, 0x001ed8, 0x001eda, 0x001eda, 0x001edc, 0x001edc, 0x001ede, 0x001ede, 0x001ee0, 0x001ee0, 0x001ee2, 0x001ee2, 0x001ee4, 0x001ee4,
                        0x001ee6, 0x001ee6, 0x001ee8, 0x001ee8, 0x001eea, 0x001eea, 0x001eec, 0x001eec, 0x001eee, 0x001eee, 0x001ef0, 0x001ef0, 0x001ef2, 0x001ef2, 0x001ef4, 0x001ef4, 0x001ef6,
                        0x001ef6, 0x001ef8, 0x001ef8, 0x001efa, 0x001efa, 0x001efc, 0x001efc, 0x001efe, 0x001efe, 0x001f08, 0x001f0f, 0x001f18, 0x001f1d, 0x001f28, 0x001f2f, 0x001f38, 0x001f3f,
                        0x001f48, 0x001f4d, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f5f, 0x001f68, 0x001f6f, 0x001fb8, 0x001fbb, 0x001fc8, 0x001fcb, 0x001fd8,
                        0x001fdb, 0x001fe8, 0x001fec, 0x001ff8, 0x001ffb, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210b, 0x00210d, 0x002110, 0x002112, 0x002115, 0x002115, 0x002119, 0x00211d,
                        0x002124, 0x002124, 0x002126, 0x002126, 0x002128, 0x002128, 0x00212a, 0x00212d, 0x002130, 0x002133, 0x00213e, 0x00213f, 0x002145, 0x002145, 0x002160, 0x00216f, 0x002183,
                        0x002183, 0x0024b6, 0x0024cf, 0x002c00, 0x002c2f, 0x002c60, 0x002c60, 0x002c62, 0x002c64, 0x002c67, 0x002c67, 0x002c69, 0x002c69, 0x002c6b, 0x002c6b, 0x002c6d, 0x002c70,
                        0x002c72, 0x002c72, 0x002c75, 0x002c75, 0x002c7e, 0x002c80, 0x002c82, 0x002c82, 0x002c84, 0x002c84, 0x002c86, 0x002c86, 0x002c88, 0x002c88, 0x002c8a, 0x002c8a, 0x002c8c,
                        0x002c8c, 0x002c8e, 0x002c8e, 0x002c90, 0x002c90, 0x002c92, 0x002c92, 0x002c94, 0x002c94, 0x002c96, 0x002c96, 0x002c98, 0x002c98, 0x002c9a, 0x002c9a, 0x002c9c, 0x002c9c,
                        0x002c9e, 0x002c9e, 0x002ca0, 0x002ca0, 0x002ca2, 0x002ca2, 0x002ca4, 0x002ca4, 0x002ca6, 0x002ca6, 0x002ca8, 0x002ca8, 0x002caa, 0x002caa, 0x002cac, 0x002cac, 0x002cae,
                        0x002cae, 0x002cb0, 0x002cb0, 0x002cb2, 0x002cb2, 0x002cb4, 0x002cb4, 0x002cb6, 0x002cb6, 0x002cb8, 0x002cb8, 0x002cba, 0x002cba, 0x002cbc, 0x002cbc, 0x002cbe, 0x002cbe,
                        0x002cc0, 0x002cc0, 0x002cc2, 0x002cc2, 0x002cc4, 0x002cc4, 0x002cc6, 0x002cc6, 0x002cc8, 0x002cc8, 0x002cca, 0x002cca, 0x002ccc, 0x002ccc, 0x002cce, 0x002cce, 0x002cd0,
                        0x002cd0, 0x002cd2, 0x002cd2, 0x002cd4, 0x002cd4, 0x002cd6, 0x002cd6, 0x002cd8, 0x002cd8, 0x002cda, 0x002cda, 0x002cdc, 0x002cdc, 0x002cde, 0x002cde, 0x002ce0, 0x002ce0,
                        0x002ce2, 0x002ce2, 0x002ceb, 0x002ceb, 0x002ced, 0x002ced, 0x002cf2, 0x002cf2, 0x00a640, 0x00a640, 0x00a642, 0x00a642, 0x00a644, 0x00a644, 0x00a646, 0x00a646, 0x00a648,
                        0x00a648, 0x00a64a, 0x00a64a, 0x00a64c, 0x00a64c, 0x00a64e, 0x00a64e, 0x00a650, 0x00a650, 0x00a652, 0x00a652, 0x00a654, 0x00a654, 0x00a656, 0x00a656, 0x00a658, 0x00a658,
                        0x00a65a, 0x00a65a, 0x00a65c, 0x00a65c, 0x00a65e, 0x00a65e, 0x00a660, 0x00a660, 0x00a662, 0x00a662, 0x00a664, 0x00a664, 0x00a666, 0x00a666, 0x00a668, 0x00a668, 0x00a66a,
                        0x00a66a, 0x00a66c, 0x00a66c, 0x00a680, 0x00a680, 0x00a682, 0x00a682, 0x00a684, 0x00a684, 0x00a686, 0x00a686, 0x00a688, 0x00a688, 0x00a68a, 0x00a68a, 0x00a68c, 0x00a68c,
                        0x00a68e, 0x00a68e, 0x00a690, 0x00a690, 0x00a692, 0x00a692, 0x00a694, 0x00a694, 0x00a696, 0x00a696, 0x00a698, 0x00a698, 0x00a69a, 0x00a69a, 0x00a722, 0x00a722, 0x00a724,
                        0x00a724, 0x00a726, 0x00a726, 0x00a728, 0x00a728, 0x00a72a, 0x00a72a, 0x00a72c, 0x00a72c, 0x00a72e, 0x00a72e, 0x00a732, 0x00a732, 0x00a734, 0x00a734, 0x00a736, 0x00a736,
                        0x00a738, 0x00a738, 0x00a73a, 0x00a73a, 0x00a73c, 0x00a73c, 0x00a73e, 0x00a73e, 0x00a740, 0x00a740, 0x00a742, 0x00a742, 0x00a744, 0x00a744, 0x00a746, 0x00a746, 0x00a748,
                        0x00a748, 0x00a74a, 0x00a74a, 0x00a74c, 0x00a74c, 0x00a74e, 0x00a74e, 0x00a750, 0x00a750, 0x00a752, 0x00a752, 0x00a754, 0x00a754, 0x00a756, 0x00a756, 0x00a758, 0x00a758,
                        0x00a75a, 0x00a75a, 0x00a75c, 0x00a75c, 0x00a75e, 0x00a75e, 0x00a760, 0x00a760, 0x00a762, 0x00a762, 0x00a764, 0x00a764, 0x00a766, 0x00a766, 0x00a768, 0x00a768, 0x00a76a,
                        0x00a76a, 0x00a76c, 0x00a76c, 0x00a76e, 0x00a76e, 0x00a779, 0x00a779, 0x00a77b, 0x00a77b, 0x00a77d, 0x00a77e, 0x00a780, 0x00a780, 0x00a782, 0x00a782, 0x00a784, 0x00a784,
                        0x00a786, 0x00a786, 0x00a78b, 0x00a78b, 0x00a78d, 0x00a78d, 0x00a790, 0x00a790, 0x00a792, 0x00a792, 0x00a796, 0x00a796, 0x00a798, 0x00a798, 0x00a79a, 0x00a79a, 0x00a79c,
                        0x00a79c, 0x00a79e, 0x00a79e, 0x00a7a0, 0x00a7a0, 0x00a7a2, 0x00a7a2, 0x00a7a4, 0x00a7a4, 0x00a7a6, 0x00a7a6, 0x00a7a8, 0x00a7a8, 0x00a7aa, 0x00a7ae, 0x00a7b0, 0x00a7b4,
                        0x00a7b6, 0x00a7b6, 0x00a7b8, 0x00a7b8, 0x00a7ba, 0x00a7ba, 0x00a7bc, 0x00a7bc, 0x00a7be, 0x00a7be, 0x00a7c0, 0x00a7c0, 0x00a7c2, 0x00a7c2, 0x00a7c4, 0x00a7c7, 0x00a7c9,
                        0x00a7c9, 0x00a7d0, 0x00a7d0, 0x00a7d6, 0x00a7d6, 0x00a7d8, 0x00a7d8, 0x00a7f5, 0x00a7f5, 0x00ff21, 0x00ff3a, 0x010400, 0x010427, 0x0104b0, 0x0104d3, 0x010570, 0x01057a,
                        0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010c80, 0x010cb2, 0x0118a0, 0x0118bf, 0x016e40, 0x016e5f, 0x01d400, 0x01d419, 0x01d434, 0x01d44d, 0x01d468,
                        0x01d481, 0x01d49c, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b5, 0x01d4d0, 0x01d4e9, 0x01d504, 0x01d505,
                        0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d538, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d56c,
                        0x01d585, 0x01d5a0, 0x01d5b9, 0x01d5d4, 0x01d5ed, 0x01d608, 0x01d621, 0x01d63c, 0x01d655, 0x01d670, 0x01d689, 0x01d6a8, 0x01d6c0, 0x01d6e2, 0x01d6fa, 0x01d71c, 0x01d734,
                        0x01d756, 0x01d76e, 0x01d790, 0x01d7a8, 0x01d7ca, 0x01d7ca, 0x01e900, 0x01e921, 0x01f130, 0x01f149, 0x01f150, 0x01f169, 0x01f170, 0x01f189));
    }

    private static void populateVS() {
        SET_ENCODINGS.put("VS", CodePointSet.createNoDedup(0x00180b, 0x00180d, 0x00180f, 0x00180f, 0x00fe00, 0x00fe0f, 0x0e0100, 0x0e01ef));
    }

    private static void populateWSPACE() {
        SET_ENCODINGS.put("WSpace", CodePointSet.createNoDedup(0x000009, 0x00000d, 0x000020, 0x000020, 0x000085, 0x000085, 0x0000a0, 0x0000a0, 0x001680, 0x001680, 0x002000, 0x00200a, 0x002028,
                        0x002029, 0x00202f, 0x00202f, 0x00205f, 0x00205f, 0x003000, 0x003000));
    }

    private static void populateXIDC() {
        SET_ENCODINGS.put("XIDC", CodePointSet.createNoDedup(0x000030, 0x000039, 0x000041, 0x00005a, 0x00005f, 0x00005f, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000b5, 0x0000b5, 0x0000b7, 0x0000b7,
                        0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6, 0x0000f8, 0x0002c1, 0x0002c6, 0x0002d1, 0x0002e0, 0x0002e4, 0x0002ec, 0x0002ec, 0x0002ee, 0x0002ee, 0x000300,
                        0x000374, 0x000376, 0x000377, 0x00037b, 0x00037d, 0x00037f, 0x00037f, 0x000386, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003f5, 0x0003f7, 0x000481,
                        0x000483, 0x000487, 0x00048a, 0x00052f, 0x000531, 0x000556, 0x000559, 0x000559, 0x000560, 0x000588, 0x000591, 0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4,
                        0x0005c5, 0x0005c7, 0x0005c7, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f2, 0x000610, 0x00061a, 0x000620, 0x000669, 0x00066e, 0x0006d3, 0x0006d5, 0x0006dc, 0x0006df, 0x0006e8,
                        0x0006ea, 0x0006fc, 0x0006ff, 0x0006ff, 0x000710, 0x00074a, 0x00074d, 0x0007b1, 0x0007c0, 0x0007f5, 0x0007fa, 0x0007fa, 0x0007fd, 0x0007fd, 0x000800, 0x00082d, 0x000840,
                        0x00085b, 0x000860, 0x00086a, 0x000870, 0x000887, 0x000889, 0x00088e, 0x000898, 0x0008e1, 0x0008e3, 0x000963, 0x000966, 0x00096f, 0x000971, 0x000983, 0x000985, 0x00098c,
                        0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6, 0x0009b9, 0x0009bc, 0x0009c4, 0x0009c7, 0x0009c8, 0x0009cb, 0x0009ce, 0x0009d7,
                        0x0009d7, 0x0009dc, 0x0009dd, 0x0009df, 0x0009e3, 0x0009e6, 0x0009f1, 0x0009fc, 0x0009fc, 0x0009fe, 0x0009fe, 0x000a01, 0x000a03, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10,
                        0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36, 0x000a38, 0x000a39, 0x000a3c, 0x000a3c, 0x000a3e, 0x000a42, 0x000a47, 0x000a48, 0x000a4b,
                        0x000a4d, 0x000a51, 0x000a51, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a66, 0x000a75, 0x000a81, 0x000a83, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8,
                        0x000aaa, 0x000ab0, 0x000ab2, 0x000ab3, 0x000ab5, 0x000ab9, 0x000abc, 0x000ac5, 0x000ac7, 0x000ac9, 0x000acb, 0x000acd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae3, 0x000ae6,
                        0x000aef, 0x000af9, 0x000aff, 0x000b01, 0x000b03, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28, 0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35, 0x000b39,
                        0x000b3c, 0x000b44, 0x000b47, 0x000b48, 0x000b4b, 0x000b4d, 0x000b55, 0x000b57, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b63, 0x000b66, 0x000b6f, 0x000b71, 0x000b71, 0x000b82,
                        0x000b83, 0x000b85, 0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa,
                        0x000bae, 0x000bb9, 0x000bbe, 0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcd, 0x000bd0, 0x000bd0, 0x000bd7, 0x000bd7, 0x000be6, 0x000bef, 0x000c00, 0x000c0c, 0x000c0e,
                        0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3c, 0x000c44, 0x000c46, 0x000c48, 0x000c4a, 0x000c4d, 0x000c55, 0x000c56, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d,
                        0x000c60, 0x000c63, 0x000c66, 0x000c6f, 0x000c80, 0x000c83, 0x000c85, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbc,
                        0x000cc4, 0x000cc6, 0x000cc8, 0x000cca, 0x000ccd, 0x000cd5, 0x000cd6, 0x000cdd, 0x000cde, 0x000ce0, 0x000ce3, 0x000ce6, 0x000cef, 0x000cf1, 0x000cf3, 0x000d00, 0x000d0c,
                        0x000d0e, 0x000d10, 0x000d12, 0x000d44, 0x000d46, 0x000d48, 0x000d4a, 0x000d4e, 0x000d54, 0x000d57, 0x000d5f, 0x000d63, 0x000d66, 0x000d6f, 0x000d7a, 0x000d7f, 0x000d81,
                        0x000d83, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000dca, 0x000dca, 0x000dcf, 0x000dd4, 0x000dd6, 0x000dd6,
                        0x000dd8, 0x000ddf, 0x000de6, 0x000def, 0x000df2, 0x000df3, 0x000e01, 0x000e3a, 0x000e40, 0x000e4e, 0x000e50, 0x000e59, 0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86,
                        0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000ebd, 0x000ec0, 0x000ec4, 0x000ec6, 0x000ec6, 0x000ec8, 0x000ece, 0x000ed0, 0x000ed9, 0x000edc, 0x000edf,
                        0x000f00, 0x000f00, 0x000f18, 0x000f19, 0x000f20, 0x000f29, 0x000f35, 0x000f35, 0x000f37, 0x000f37, 0x000f39, 0x000f39, 0x000f3e, 0x000f47, 0x000f49, 0x000f6c, 0x000f71,
                        0x000f84, 0x000f86, 0x000f97, 0x000f99, 0x000fbc, 0x000fc6, 0x000fc6, 0x001000, 0x001049, 0x001050, 0x00109d, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd,
                        0x0010d0, 0x0010fa, 0x0010fc, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d, 0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290,
                        0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8, 0x001310, 0x001312, 0x001315, 0x001318, 0x00135a,
                        0x00135d, 0x00135f, 0x001369, 0x001371, 0x001380, 0x00138f, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001401, 0x00166c, 0x00166f, 0x00167f, 0x001681, 0x00169a, 0x0016a0,
                        0x0016ea, 0x0016ee, 0x0016f8, 0x001700, 0x001715, 0x00171f, 0x001734, 0x001740, 0x001753, 0x001760, 0x00176c, 0x00176e, 0x001770, 0x001772, 0x001773, 0x001780, 0x0017d3,
                        0x0017d7, 0x0017d7, 0x0017dc, 0x0017dd, 0x0017e0, 0x0017e9, 0x00180b, 0x00180d, 0x00180f, 0x001819, 0x001820, 0x001878, 0x001880, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900,
                        0x00191e, 0x001920, 0x00192b, 0x001930, 0x00193b, 0x001946, 0x00196d, 0x001970, 0x001974, 0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x0019d0, 0x0019da, 0x001a00, 0x001a1b,
                        0x001a20, 0x001a5e, 0x001a60, 0x001a7c, 0x001a7f, 0x001a89, 0x001a90, 0x001a99, 0x001aa7, 0x001aa7, 0x001ab0, 0x001abd, 0x001abf, 0x001ace, 0x001b00, 0x001b4c, 0x001b50,
                        0x001b59, 0x001b6b, 0x001b73, 0x001b80, 0x001bf3, 0x001c00, 0x001c37, 0x001c40, 0x001c49, 0x001c4d, 0x001c7d, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf,
                        0x001cd0, 0x001cd2, 0x001cd4, 0x001cfa, 0x001d00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50, 0x001f57, 0x001f59, 0x001f59, 0x001f5b,
                        0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc, 0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4, 0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3,
                        0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x00203f, 0x002040, 0x002054, 0x002054, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090,
                        0x00209c, 0x0020d0, 0x0020dc, 0x0020e1, 0x0020e1, 0x0020e5, 0x0020f0, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002118, 0x00211d,
                        0x002124, 0x002124, 0x002126, 0x002126, 0x002128, 0x002128, 0x00212a, 0x002139, 0x00213c, 0x00213f, 0x002145, 0x002149, 0x00214e, 0x00214e, 0x002160, 0x002188, 0x002c00,
                        0x002ce4, 0x002ceb, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x002d30, 0x002d67, 0x002d6f, 0x002d6f, 0x002d7f, 0x002d96, 0x002da0, 0x002da6,
                        0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe, 0x002dc0, 0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x002de0, 0x002dff, 0x003005,
                        0x003007, 0x003021, 0x00302f, 0x003031, 0x003035, 0x003038, 0x00303c, 0x003041, 0x003096, 0x003099, 0x00309a, 0x00309d, 0x00309f, 0x0030a1, 0x0030fa, 0x0030fc, 0x0030ff,
                        0x003105, 0x00312f, 0x003131, 0x00318e, 0x0031a0, 0x0031bf, 0x0031f0, 0x0031ff, 0x003400, 0x004dbf, 0x004e00, 0x00a48c, 0x00a4d0, 0x00a4fd, 0x00a500, 0x00a60c, 0x00a610,
                        0x00a62b, 0x00a640, 0x00a66f, 0x00a674, 0x00a67d, 0x00a67f, 0x00a6f1, 0x00a717, 0x00a71f, 0x00a722, 0x00a788, 0x00a78b, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3,
                        0x00a7d5, 0x00a7d9, 0x00a7f2, 0x00a827, 0x00a82c, 0x00a82c, 0x00a840, 0x00a873, 0x00a880, 0x00a8c5, 0x00a8d0, 0x00a8d9, 0x00a8e0, 0x00a8f7, 0x00a8fb, 0x00a8fb, 0x00a8fd,
                        0x00a92d, 0x00a930, 0x00a953, 0x00a960, 0x00a97c, 0x00a980, 0x00a9c0, 0x00a9cf, 0x00a9d9, 0x00a9e0, 0x00a9fe, 0x00aa00, 0x00aa36, 0x00aa40, 0x00aa4d, 0x00aa50, 0x00aa59,
                        0x00aa60, 0x00aa76, 0x00aa7a, 0x00aac2, 0x00aadb, 0x00aadd, 0x00aae0, 0x00aaef, 0x00aaf2, 0x00aaf6, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20,
                        0x00ab26, 0x00ab28, 0x00ab2e, 0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab69, 0x00ab70, 0x00abea, 0x00abec, 0x00abed, 0x00abf0, 0x00abf9, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6,
                        0x00d7cb, 0x00d7fb, 0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb28, 0x00fb2a, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e,
                        0x00fb3e, 0x00fb40, 0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fbb1, 0x00fbd3, 0x00fc5d, 0x00fc64, 0x00fd3d, 0x00fd50, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdf0, 0x00fdf9,
                        0x00fe00, 0x00fe0f, 0x00fe20, 0x00fe2f, 0x00fe33, 0x00fe34, 0x00fe4d, 0x00fe4f, 0x00fe71, 0x00fe71, 0x00fe73, 0x00fe73, 0x00fe77, 0x00fe77, 0x00fe79, 0x00fe79, 0x00fe7b,
                        0x00fe7b, 0x00fe7d, 0x00fe7d, 0x00fe7f, 0x00fefc, 0x00ff10, 0x00ff19, 0x00ff21, 0x00ff3a, 0x00ff3f, 0x00ff3f, 0x00ff41, 0x00ff5a, 0x00ff66, 0x00ffbe, 0x00ffc2, 0x00ffc7,
                        0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d, 0x01003f, 0x01004d, 0x010050,
                        0x01005d, 0x010080, 0x0100fa, 0x010140, 0x010174, 0x0101fd, 0x0101fd, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x0102e0, 0x0102e0, 0x010300, 0x01031f, 0x01032d, 0x01034a,
                        0x010350, 0x01037a, 0x010380, 0x01039d, 0x0103a0, 0x0103c3, 0x0103c8, 0x0103cf, 0x0103d1, 0x0103d5, 0x010400, 0x01049d, 0x0104a0, 0x0104a9, 0x0104b0, 0x0104d3, 0x0104d8,
                        0x0104fb, 0x010500, 0x010527, 0x010530, 0x010563, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1,
                        0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010800,
                        0x010805, 0x010808, 0x010808, 0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x010855, 0x010860, 0x010876, 0x010880, 0x01089e, 0x0108e0, 0x0108f2,
                        0x0108f4, 0x0108f5, 0x010900, 0x010915, 0x010920, 0x010939, 0x010980, 0x0109b7, 0x0109be, 0x0109bf, 0x010a00, 0x010a03, 0x010a05, 0x010a06, 0x010a0c, 0x010a13, 0x010a15,
                        0x010a17, 0x010a19, 0x010a35, 0x010a38, 0x010a3a, 0x010a3f, 0x010a3f, 0x010a60, 0x010a7c, 0x010a80, 0x010a9c, 0x010ac0, 0x010ac7, 0x010ac9, 0x010ae6, 0x010b00, 0x010b35,
                        0x010b40, 0x010b55, 0x010b60, 0x010b72, 0x010b80, 0x010b91, 0x010c00, 0x010c48, 0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x010d00, 0x010d27, 0x010d30, 0x010d39, 0x010e80,
                        0x010ea9, 0x010eab, 0x010eac, 0x010eb0, 0x010eb1, 0x010efd, 0x010f1c, 0x010f27, 0x010f27, 0x010f30, 0x010f50, 0x010f70, 0x010f85, 0x010fb0, 0x010fc4, 0x010fe0, 0x010ff6,
                        0x011000, 0x011046, 0x011066, 0x011075, 0x01107f, 0x0110ba, 0x0110c2, 0x0110c2, 0x0110d0, 0x0110e8, 0x0110f0, 0x0110f9, 0x011100, 0x011134, 0x011136, 0x01113f, 0x011144,
                        0x011147, 0x011150, 0x011173, 0x011176, 0x011176, 0x011180, 0x0111c4, 0x0111c9, 0x0111cc, 0x0111ce, 0x0111da, 0x0111dc, 0x0111dc, 0x011200, 0x011211, 0x011213, 0x011237,
                        0x01123e, 0x011241, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a8, 0x0112b0, 0x0112ea, 0x0112f0, 0x0112f9, 0x011300,
                        0x011303, 0x011305, 0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133b, 0x011344, 0x011347, 0x011348,
                        0x01134b, 0x01134d, 0x011350, 0x011350, 0x011357, 0x011357, 0x01135d, 0x011363, 0x011366, 0x01136c, 0x011370, 0x011374, 0x011400, 0x01144a, 0x011450, 0x011459, 0x01145e,
                        0x011461, 0x011480, 0x0114c5, 0x0114c7, 0x0114c7, 0x0114d0, 0x0114d9, 0x011580, 0x0115b5, 0x0115b8, 0x0115c0, 0x0115d8, 0x0115dd, 0x011600, 0x011640, 0x011644, 0x011644,
                        0x011650, 0x011659, 0x011680, 0x0116b8, 0x0116c0, 0x0116c9, 0x011700, 0x01171a, 0x01171d, 0x01172b, 0x011730, 0x011739, 0x011740, 0x011746, 0x011800, 0x01183a, 0x0118a0,
                        0x0118e9, 0x0118ff, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x011935, 0x011937, 0x011938, 0x01193b, 0x011943, 0x011950, 0x011959,
                        0x0119a0, 0x0119a7, 0x0119aa, 0x0119d7, 0x0119da, 0x0119e1, 0x0119e3, 0x0119e4, 0x011a00, 0x011a3e, 0x011a47, 0x011a47, 0x011a50, 0x011a99, 0x011a9d, 0x011a9d, 0x011ab0,
                        0x011af8, 0x011c00, 0x011c08, 0x011c0a, 0x011c36, 0x011c38, 0x011c40, 0x011c50, 0x011c59, 0x011c72, 0x011c8f, 0x011c92, 0x011ca7, 0x011ca9, 0x011cb6, 0x011d00, 0x011d06,
                        0x011d08, 0x011d09, 0x011d0b, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d47, 0x011d50, 0x011d59, 0x011d60, 0x011d65, 0x011d67, 0x011d68, 0x011d6a,
                        0x011d8e, 0x011d90, 0x011d91, 0x011d93, 0x011d98, 0x011da0, 0x011da9, 0x011ee0, 0x011ef6, 0x011f00, 0x011f10, 0x011f12, 0x011f3a, 0x011f3e, 0x011f42, 0x011f50, 0x011f59,
                        0x011fb0, 0x011fb0, 0x012000, 0x012399, 0x012400, 0x01246e, 0x012480, 0x012543, 0x012f90, 0x012ff0, 0x013000, 0x01342f, 0x013440, 0x013455, 0x014400, 0x014646, 0x016800,
                        0x016a38, 0x016a40, 0x016a5e, 0x016a60, 0x016a69, 0x016a70, 0x016abe, 0x016ac0, 0x016ac9, 0x016ad0, 0x016aed, 0x016af0, 0x016af4, 0x016b00, 0x016b36, 0x016b40, 0x016b43,
                        0x016b50, 0x016b59, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f, 0x016e40, 0x016e7f, 0x016f00, 0x016f4a, 0x016f4f, 0x016f87, 0x016f8f, 0x016f9f, 0x016fe0, 0x016fe1, 0x016fe3,
                        0x016fe4, 0x016ff0, 0x016ff1, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01b000, 0x01b122,
                        0x01b132, 0x01b132, 0x01b150, 0x01b152, 0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb, 0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88, 0x01bc90,
                        0x01bc99, 0x01bc9d, 0x01bc9e, 0x01cf00, 0x01cf2d, 0x01cf30, 0x01cf46, 0x01d165, 0x01d169, 0x01d16d, 0x01d172, 0x01d17b, 0x01d182, 0x01d185, 0x01d18b, 0x01d1aa, 0x01d1ad,
                        0x01d242, 0x01d244, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb,
                        0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544,
                        0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d6c0, 0x01d6c2, 0x01d6da, 0x01d6dc, 0x01d6fa, 0x01d6fc, 0x01d714, 0x01d716, 0x01d734, 0x01d736,
                        0x01d74e, 0x01d750, 0x01d76e, 0x01d770, 0x01d788, 0x01d78a, 0x01d7a8, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7cb, 0x01d7ce, 0x01d7ff, 0x01da00, 0x01da36, 0x01da3b, 0x01da6c,
                        0x01da75, 0x01da75, 0x01da84, 0x01da84, 0x01da9b, 0x01da9f, 0x01daa1, 0x01daaf, 0x01df00, 0x01df1e, 0x01df25, 0x01df2a, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b,
                        0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a, 0x01e030, 0x01e06d, 0x01e08f, 0x01e08f, 0x01e100, 0x01e12c, 0x01e130, 0x01e13d, 0x01e140, 0x01e149, 0x01e14e, 0x01e14e,
                        0x01e290, 0x01e2ae, 0x01e2c0, 0x01e2f9, 0x01e4d0, 0x01e4f9, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe, 0x01e800, 0x01e8c4, 0x01e8d0,
                        0x01e8d6, 0x01e900, 0x01e94b, 0x01e950, 0x01e959, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32,
                        0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51,
                        0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64,
                        0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5,
                        0x01eea9, 0x01eeab, 0x01eebb, 0x01fbf0, 0x01fbf9, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d,
                        0x030000, 0x03134a, 0x031350, 0x0323af, 0x0e0100, 0x0e01ef));
    }

    private static void populateXIDS() {
        SET_ENCODINGS.put("XIDS", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000b5, 0x0000b5, 0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6,
                        0x0000f8, 0x0002c1, 0x0002c6, 0x0002d1, 0x0002e0, 0x0002e4, 0x0002ec, 0x0002ec, 0x0002ee, 0x0002ee, 0x000370, 0x000374, 0x000376, 0x000377, 0x00037b, 0x00037d, 0x00037f,
                        0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003f5, 0x0003f7, 0x000481, 0x00048a, 0x00052f, 0x000531, 0x000556,
                        0x000559, 0x000559, 0x000560, 0x000588, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f2, 0x000620, 0x00064a, 0x00066e, 0x00066f, 0x000671, 0x0006d3, 0x0006d5, 0x0006d5, 0x0006e5,
                        0x0006e6, 0x0006ee, 0x0006ef, 0x0006fa, 0x0006fc, 0x0006ff, 0x0006ff, 0x000710, 0x000710, 0x000712, 0x00072f, 0x00074d, 0x0007a5, 0x0007b1, 0x0007b1, 0x0007ca, 0x0007ea,
                        0x0007f4, 0x0007f5, 0x0007fa, 0x0007fa, 0x000800, 0x000815, 0x00081a, 0x00081a, 0x000824, 0x000824, 0x000828, 0x000828, 0x000840, 0x000858, 0x000860, 0x00086a, 0x000870,
                        0x000887, 0x000889, 0x00088e, 0x0008a0, 0x0008c9, 0x000904, 0x000939, 0x00093d, 0x00093d, 0x000950, 0x000950, 0x000958, 0x000961, 0x000971, 0x000980, 0x000985, 0x00098c,
                        0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6, 0x0009b9, 0x0009bd, 0x0009bd, 0x0009ce, 0x0009ce, 0x0009dc, 0x0009dd, 0x0009df,
                        0x0009e1, 0x0009f0, 0x0009f1, 0x0009fc, 0x0009fc, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36,
                        0x000a38, 0x000a39, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a72, 0x000a74, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8, 0x000aaa, 0x000ab0, 0x000ab2,
                        0x000ab3, 0x000ab5, 0x000ab9, 0x000abd, 0x000abd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae1, 0x000af9, 0x000af9, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28,
                        0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35, 0x000b39, 0x000b3d, 0x000b3d, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b61, 0x000b71, 0x000b71, 0x000b83, 0x000b83, 0x000b85,
                        0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa, 0x000bae, 0x000bb9,
                        0x000bd0, 0x000bd0, 0x000c05, 0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3d, 0x000c3d, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d, 0x000c60,
                        0x000c61, 0x000c80, 0x000c80, 0x000c85, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbd, 0x000cbd, 0x000cdd, 0x000cde,
                        0x000ce0, 0x000ce1, 0x000cf1, 0x000cf2, 0x000d04, 0x000d0c, 0x000d0e, 0x000d10, 0x000d12, 0x000d3a, 0x000d3d, 0x000d3d, 0x000d4e, 0x000d4e, 0x000d54, 0x000d56, 0x000d5f,
                        0x000d61, 0x000d7a, 0x000d7f, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000e01, 0x000e30, 0x000e32, 0x000e32,
                        0x000e40, 0x000e46, 0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000eb0, 0x000eb2, 0x000eb2, 0x000ebd,
                        0x000ebd, 0x000ec0, 0x000ec4, 0x000ec6, 0x000ec6, 0x000edc, 0x000edf, 0x000f00, 0x000f00, 0x000f40, 0x000f47, 0x000f49, 0x000f6c, 0x000f88, 0x000f8c, 0x001000, 0x00102a,
                        0x00103f, 0x00103f, 0x001050, 0x001055, 0x00105a, 0x00105d, 0x001061, 0x001061, 0x001065, 0x001066, 0x00106e, 0x001070, 0x001075, 0x001081, 0x00108e, 0x00108e, 0x0010a0,
                        0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x0010fa, 0x0010fc, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d,
                        0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290, 0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8,
                        0x001310, 0x001312, 0x001315, 0x001318, 0x00135a, 0x001380, 0x00138f, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001401, 0x00166c, 0x00166f, 0x00167f, 0x001681, 0x00169a,
                        0x0016a0, 0x0016ea, 0x0016ee, 0x0016f8, 0x001700, 0x001711, 0x00171f, 0x001731, 0x001740, 0x001751, 0x001760, 0x00176c, 0x00176e, 0x001770, 0x001780, 0x0017b3, 0x0017d7,
                        0x0017d7, 0x0017dc, 0x0017dc, 0x001820, 0x001878, 0x001880, 0x0018a8, 0x0018aa, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900, 0x00191e, 0x001950, 0x00196d, 0x001970, 0x001974,
                        0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x001a00, 0x001a16, 0x001a20, 0x001a54, 0x001aa7, 0x001aa7, 0x001b05, 0x001b33, 0x001b45, 0x001b4c, 0x001b83, 0x001ba0, 0x001bae,
                        0x001baf, 0x001bba, 0x001be5, 0x001c00, 0x001c23, 0x001c4d, 0x001c4f, 0x001c5a, 0x001c7d, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001ce9, 0x001cec,
                        0x001cee, 0x001cf3, 0x001cf5, 0x001cf6, 0x001cfa, 0x001cfa, 0x001d00, 0x001dbf, 0x001e00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50,
                        0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc, 0x001fbe, 0x001fbe, 0x001fc2, 0x001fc4,
                        0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090,
                        0x00209c, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002118, 0x00211d, 0x002124, 0x002124, 0x002126, 0x002126, 0x002128, 0x002128,
                        0x00212a, 0x002139, 0x00213c, 0x00213f, 0x002145, 0x002149, 0x00214e, 0x00214e, 0x002160, 0x002188, 0x002c00, 0x002ce4, 0x002ceb, 0x002cee, 0x002cf2, 0x002cf3, 0x002d00,
                        0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x002d30, 0x002d67, 0x002d6f, 0x002d6f, 0x002d80, 0x002d96, 0x002da0, 0x002da6, 0x002da8, 0x002dae, 0x002db0, 0x002db6,
                        0x002db8, 0x002dbe, 0x002dc0, 0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x003005, 0x003007, 0x003021, 0x003029, 0x003031, 0x003035, 0x003038,
                        0x00303c, 0x003041, 0x003096, 0x00309d, 0x00309f, 0x0030a1, 0x0030fa, 0x0030fc, 0x0030ff, 0x003105, 0x00312f, 0x003131, 0x00318e, 0x0031a0, 0x0031bf, 0x0031f0, 0x0031ff,
                        0x003400, 0x004dbf, 0x004e00, 0x00a48c, 0x00a4d0, 0x00a4fd, 0x00a500, 0x00a60c, 0x00a610, 0x00a61f, 0x00a62a, 0x00a62b, 0x00a640, 0x00a66e, 0x00a67f, 0x00a69d, 0x00a6a0,
                        0x00a6ef, 0x00a717, 0x00a71f, 0x00a722, 0x00a788, 0x00a78b, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9, 0x00a7f2, 0x00a801, 0x00a803, 0x00a805,
                        0x00a807, 0x00a80a, 0x00a80c, 0x00a822, 0x00a840, 0x00a873, 0x00a882, 0x00a8b3, 0x00a8f2, 0x00a8f7, 0x00a8fb, 0x00a8fb, 0x00a8fd, 0x00a8fe, 0x00a90a, 0x00a925, 0x00a930,
                        0x00a946, 0x00a960, 0x00a97c, 0x00a984, 0x00a9b2, 0x00a9cf, 0x00a9cf, 0x00a9e0, 0x00a9e4, 0x00a9e6, 0x00a9ef, 0x00a9fa, 0x00a9fe, 0x00aa00, 0x00aa28, 0x00aa40, 0x00aa42,
                        0x00aa44, 0x00aa4b, 0x00aa60, 0x00aa76, 0x00aa7a, 0x00aa7a, 0x00aa7e, 0x00aaaf, 0x00aab1, 0x00aab1, 0x00aab5, 0x00aab6, 0x00aab9, 0x00aabd, 0x00aac0, 0x00aac0, 0x00aac2,
                        0x00aac2, 0x00aadb, 0x00aadd, 0x00aae0, 0x00aaea, 0x00aaf2, 0x00aaf4, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20, 0x00ab26, 0x00ab28, 0x00ab2e,
                        0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab69, 0x00ab70, 0x00abe2, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb, 0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb00,
                        0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb1d, 0x00fb1f, 0x00fb28, 0x00fb2a, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40, 0x00fb41, 0x00fb43, 0x00fb44,
                        0x00fb46, 0x00fbb1, 0x00fbd3, 0x00fc5d, 0x00fc64, 0x00fd3d, 0x00fd50, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdf0, 0x00fdf9, 0x00fe71, 0x00fe71, 0x00fe73, 0x00fe73, 0x00fe77,
                        0x00fe77, 0x00fe79, 0x00fe79, 0x00fe7b, 0x00fe7b, 0x00fe7d, 0x00fe7d, 0x00fe7f, 0x00fefc, 0x00ff21, 0x00ff3a, 0x00ff41, 0x00ff5a, 0x00ff66, 0x00ff9d, 0x00ffa0, 0x00ffbe,
                        0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d, 0x01003f,
                        0x01004d, 0x010050, 0x01005d, 0x010080, 0x0100fa, 0x010140, 0x010174, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x010300, 0x01031f, 0x01032d, 0x01034a, 0x010350, 0x010375,
                        0x010380, 0x01039d, 0x0103a0, 0x0103c3, 0x0103c8, 0x0103cf, 0x0103d1, 0x0103d5, 0x010400, 0x01049d, 0x0104b0, 0x0104d3, 0x0104d8, 0x0104fb, 0x010500, 0x010527, 0x010530,
                        0x010563, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc,
                        0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x010800, 0x010805, 0x010808, 0x010808, 0x01080a,
                        0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x010855, 0x010860, 0x010876, 0x010880, 0x01089e, 0x0108e0, 0x0108f2, 0x0108f4, 0x0108f5, 0x010900, 0x010915,
                        0x010920, 0x010939, 0x010980, 0x0109b7, 0x0109be, 0x0109bf, 0x010a00, 0x010a00, 0x010a10, 0x010a13, 0x010a15, 0x010a17, 0x010a19, 0x010a35, 0x010a60, 0x010a7c, 0x010a80,
                        0x010a9c, 0x010ac0, 0x010ac7, 0x010ac9, 0x010ae4, 0x010b00, 0x010b35, 0x010b40, 0x010b55, 0x010b60, 0x010b72, 0x010b80, 0x010b91, 0x010c00, 0x010c48, 0x010c80, 0x010cb2,
                        0x010cc0, 0x010cf2, 0x010d00, 0x010d23, 0x010e80, 0x010ea9, 0x010eb0, 0x010eb1, 0x010f00, 0x010f1c, 0x010f27, 0x010f27, 0x010f30, 0x010f45, 0x010f70, 0x010f81, 0x010fb0,
                        0x010fc4, 0x010fe0, 0x010ff6, 0x011003, 0x011037, 0x011071, 0x011072, 0x011075, 0x011075, 0x011083, 0x0110af, 0x0110d0, 0x0110e8, 0x011103, 0x011126, 0x011144, 0x011144,
                        0x011147, 0x011147, 0x011150, 0x011172, 0x011176, 0x011176, 0x011183, 0x0111b2, 0x0111c1, 0x0111c4, 0x0111da, 0x0111da, 0x0111dc, 0x0111dc, 0x011200, 0x011211, 0x011213,
                        0x01122b, 0x01123f, 0x011240, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a8, 0x0112b0, 0x0112de, 0x011305, 0x01130c,
                        0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133d, 0x01133d, 0x011350, 0x011350, 0x01135d, 0x011361, 0x011400,
                        0x011434, 0x011447, 0x01144a, 0x01145f, 0x011461, 0x011480, 0x0114af, 0x0114c4, 0x0114c5, 0x0114c7, 0x0114c7, 0x011580, 0x0115ae, 0x0115d8, 0x0115db, 0x011600, 0x01162f,
                        0x011644, 0x011644, 0x011680, 0x0116aa, 0x0116b8, 0x0116b8, 0x011700, 0x01171a, 0x011740, 0x011746, 0x011800, 0x01182b, 0x0118a0, 0x0118df, 0x0118ff, 0x011906, 0x011909,
                        0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x01192f, 0x01193f, 0x01193f, 0x011941, 0x011941, 0x0119a0, 0x0119a7, 0x0119aa, 0x0119d0, 0x0119e1, 0x0119e1,
                        0x0119e3, 0x0119e3, 0x011a00, 0x011a00, 0x011a0b, 0x011a32, 0x011a3a, 0x011a3a, 0x011a50, 0x011a50, 0x011a5c, 0x011a89, 0x011a9d, 0x011a9d, 0x011ab0, 0x011af8, 0x011c00,
                        0x011c08, 0x011c0a, 0x011c2e, 0x011c40, 0x011c40, 0x011c72, 0x011c8f, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d30, 0x011d46, 0x011d46, 0x011d60, 0x011d65,
                        0x011d67, 0x011d68, 0x011d6a, 0x011d89, 0x011d98, 0x011d98, 0x011ee0, 0x011ef2, 0x011f02, 0x011f02, 0x011f04, 0x011f10, 0x011f12, 0x011f33, 0x011fb0, 0x011fb0, 0x012000,
                        0x012399, 0x012400, 0x01246e, 0x012480, 0x012543, 0x012f90, 0x012ff0, 0x013000, 0x01342f, 0x013441, 0x013446, 0x014400, 0x014646, 0x016800, 0x016a38, 0x016a40, 0x016a5e,
                        0x016a70, 0x016abe, 0x016ad0, 0x016aed, 0x016b00, 0x016b2f, 0x016b40, 0x016b43, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f, 0x016e40, 0x016e7f, 0x016f00, 0x016f4a, 0x016f50,
                        0x016f50, 0x016f93, 0x016f9f, 0x016fe0, 0x016fe1, 0x016fe3, 0x016fe3, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb,
                        0x01affd, 0x01affe, 0x01b000, 0x01b122, 0x01b132, 0x01b132, 0x01b150, 0x01b152, 0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb, 0x01bc00, 0x01bc6a, 0x01bc70,
                        0x01bc7c, 0x01bc80, 0x01bc88, 0x01bc90, 0x01bc99, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac,
                        0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b,
                        0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d6c0, 0x01d6c2, 0x01d6da, 0x01d6dc, 0x01d6fa, 0x01d6fc, 0x01d714,
                        0x01d716, 0x01d734, 0x01d736, 0x01d74e, 0x01d750, 0x01d76e, 0x01d770, 0x01d788, 0x01d78a, 0x01d7a8, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7cb, 0x01df00, 0x01df1e, 0x01df25,
                        0x01df2a, 0x01e030, 0x01e06d, 0x01e100, 0x01e12c, 0x01e137, 0x01e13d, 0x01e14e, 0x01e14e, 0x01e290, 0x01e2ad, 0x01e2c0, 0x01e2eb, 0x01e4d0, 0x01e4eb, 0x01e7e0, 0x01e7e6,
                        0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe, 0x01e800, 0x01e8c4, 0x01e900, 0x01e943, 0x01e94b, 0x01e94b, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21,
                        0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47,
                        0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d,
                        0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e,
                        0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820,
                        0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af));
    }

    private static void populateBLK_ASCII() {
        SET_ENCODINGS.put("blk=ASCII", CodePointSet.createNoDedup(0x000000, 0x00007f));
    }

    private static void populateBLK_ADLAM() {
        SET_ENCODINGS.put("blk=Adlam", CodePointSet.createNoDedup(0x01e900, 0x01e95f));
    }

    private static void populateBLK_AEGEAN_NUMBERS() {
        SET_ENCODINGS.put("blk=Aegean_Numbers", CodePointSet.createNoDedup(0x010100, 0x01013f));
    }

    private static void populateBLK_AHOM() {
        SET_ENCODINGS.put("blk=Ahom", CodePointSet.createNoDedup(0x011700, 0x01174f));
    }

    private static void populateBLK_ALCHEMICAL() {
        SET_ENCODINGS.put("blk=Alchemical", CodePointSet.createNoDedup(0x01f700, 0x01f77f));
    }

    private static void populateBLK_ALPHABETIC_PF() {
        SET_ENCODINGS.put("blk=Alphabetic_PF", CodePointSet.createNoDedup(0x00fb00, 0x00fb4f));
    }

    private static void populateBLK_ANATOLIAN_HIEROGLYPHS() {
        SET_ENCODINGS.put("blk=Anatolian_Hieroglyphs", CodePointSet.createNoDedup(0x014400, 0x01467f));
    }

    private static void populateBLK_ANCIENT_GREEK_MUSIC() {
        SET_ENCODINGS.put("blk=Ancient_Greek_Music", CodePointSet.createNoDedup(0x01d200, 0x01d24f));
    }

    private static void populateBLK_ANCIENT_GREEK_NUMBERS() {
        SET_ENCODINGS.put("blk=Ancient_Greek_Numbers", CodePointSet.createNoDedup(0x010140, 0x01018f));
    }

    private static void populateBLK_ANCIENT_SYMBOLS() {
        SET_ENCODINGS.put("blk=Ancient_Symbols", CodePointSet.createNoDedup(0x010190, 0x0101cf));
    }

    private static void populateBLK_ARABIC() {
        SET_ENCODINGS.put("blk=Arabic", CodePointSet.createNoDedup(0x000600, 0x0006ff));
    }

    private static void populateBLK_ARABIC_EXT_A() {
        SET_ENCODINGS.put("blk=Arabic_Ext_A", CodePointSet.createNoDedup(0x0008a0, 0x0008ff));
    }

    private static void populateBLK_ARABIC_EXT_B() {
        SET_ENCODINGS.put("blk=Arabic_Ext_B", CodePointSet.createNoDedup(0x000870, 0x00089f));
    }

    private static void populateBLK_ARABIC_EXT_C() {
        SET_ENCODINGS.put("blk=Arabic_Ext_C", CodePointSet.createNoDedup(0x010ec0, 0x010eff));
    }

    private static void populateBLK_ARABIC_MATH() {
        SET_ENCODINGS.put("blk=Arabic_Math", CodePointSet.createNoDedup(0x01ee00, 0x01eeff));
    }

    private static void populateBLK_ARABIC_PF_A() {
        SET_ENCODINGS.put("blk=Arabic_PF_A", CodePointSet.createNoDedup(0x00fb50, 0x00fdff));
    }

    private static void populateBLK_ARABIC_PF_B() {
        SET_ENCODINGS.put("blk=Arabic_PF_B", CodePointSet.createNoDedup(0x00fe70, 0x00feff));
    }

    private static void populateBLK_ARABIC_SUP() {
        SET_ENCODINGS.put("blk=Arabic_Sup", CodePointSet.createNoDedup(0x000750, 0x00077f));
    }

    private static void populateBLK_ARMENIAN() {
        SET_ENCODINGS.put("blk=Armenian", CodePointSet.createNoDedup(0x000530, 0x00058f));
    }

    private static void populateBLK_ARROWS() {
        SET_ENCODINGS.put("blk=Arrows", CodePointSet.createNoDedup(0x002190, 0x0021ff));
    }

    private static void populateBLK_AVESTAN() {
        SET_ENCODINGS.put("blk=Avestan", CodePointSet.createNoDedup(0x010b00, 0x010b3f));
    }

    private static void populateBLK_BALINESE() {
        SET_ENCODINGS.put("blk=Balinese", CodePointSet.createNoDedup(0x001b00, 0x001b7f));
    }

    private static void populateBLK_BAMUM() {
        SET_ENCODINGS.put("blk=Bamum", CodePointSet.createNoDedup(0x00a6a0, 0x00a6ff));
    }

    private static void populateBLK_BAMUM_SUP() {
        SET_ENCODINGS.put("blk=Bamum_Sup", CodePointSet.createNoDedup(0x016800, 0x016a3f));
    }

    private static void populateBLK_BASSA_VAH() {
        SET_ENCODINGS.put("blk=Bassa_Vah", CodePointSet.createNoDedup(0x016ad0, 0x016aff));
    }

    private static void populateBLK_BATAK() {
        SET_ENCODINGS.put("blk=Batak", CodePointSet.createNoDedup(0x001bc0, 0x001bff));
    }

    private static void populateBLK_BENGALI() {
        SET_ENCODINGS.put("blk=Bengali", CodePointSet.createNoDedup(0x000980, 0x0009ff));
    }

    private static void populateBLK_BHAIKSUKI() {
        SET_ENCODINGS.put("blk=Bhaiksuki", CodePointSet.createNoDedup(0x011c00, 0x011c6f));
    }

    private static void populateBLK_BLOCK_ELEMENTS() {
        SET_ENCODINGS.put("blk=Block_Elements", CodePointSet.createNoDedup(0x002580, 0x00259f));
    }

    private static void populateBLK_BOPOMOFO() {
        SET_ENCODINGS.put("blk=Bopomofo", CodePointSet.createNoDedup(0x003100, 0x00312f));
    }

    private static void populateBLK_BOPOMOFO_EXT() {
        SET_ENCODINGS.put("blk=Bopomofo_Ext", CodePointSet.createNoDedup(0x0031a0, 0x0031bf));
    }

    private static void populateBLK_BOX_DRAWING() {
        SET_ENCODINGS.put("blk=Box_Drawing", CodePointSet.createNoDedup(0x002500, 0x00257f));
    }

    private static void populateBLK_BRAHMI() {
        SET_ENCODINGS.put("blk=Brahmi", CodePointSet.createNoDedup(0x011000, 0x01107f));
    }

    private static void populateBLK_BRAILLE() {
        SET_ENCODINGS.put("blk=Braille", CodePointSet.createNoDedup(0x002800, 0x0028ff));
    }

    private static void populateBLK_BUGINESE() {
        SET_ENCODINGS.put("blk=Buginese", CodePointSet.createNoDedup(0x001a00, 0x001a1f));
    }

    private static void populateBLK_BUHID() {
        SET_ENCODINGS.put("blk=Buhid", CodePointSet.createNoDedup(0x001740, 0x00175f));
    }

    private static void populateBLK_BYZANTINE_MUSIC() {
        SET_ENCODINGS.put("blk=Byzantine_Music", CodePointSet.createNoDedup(0x01d000, 0x01d0ff));
    }

    private static void populateBLK_CJK() {
        SET_ENCODINGS.put("blk=CJK", CodePointSet.createNoDedup(0x004e00, 0x009fff));
    }

    private static void populateBLK_CJK_COMPAT() {
        SET_ENCODINGS.put("blk=CJK_Compat", CodePointSet.createNoDedup(0x003300, 0x0033ff));
    }

    private static void populateBLK_CJK_COMPAT_FORMS() {
        SET_ENCODINGS.put("blk=CJK_Compat_Forms", CodePointSet.createNoDedup(0x00fe30, 0x00fe4f));
    }

    private static void populateBLK_CJK_COMPAT_IDEOGRAPHS() {
        SET_ENCODINGS.put("blk=CJK_Compat_Ideographs", CodePointSet.createNoDedup(0x00f900, 0x00faff));
    }

    private static void populateBLK_CJK_COMPAT_IDEOGRAPHS_SUP() {
        SET_ENCODINGS.put("blk=CJK_Compat_Ideographs_Sup", CodePointSet.createNoDedup(0x02f800, 0x02fa1f));
    }

    private static void populateBLK_CJK_EXT_A() {
        SET_ENCODINGS.put("blk=CJK_Ext_A", CodePointSet.createNoDedup(0x003400, 0x004dbf));
    }

    private static void populateBLK_CJK_EXT_B() {
        SET_ENCODINGS.put("blk=CJK_Ext_B", CodePointSet.createNoDedup(0x020000, 0x02a6df));
    }

    private static void populateBLK_CJK_EXT_C() {
        SET_ENCODINGS.put("blk=CJK_Ext_C", CodePointSet.createNoDedup(0x02a700, 0x02b73f));
    }

    private static void populateBLK_CJK_EXT_D() {
        SET_ENCODINGS.put("blk=CJK_Ext_D", CodePointSet.createNoDedup(0x02b740, 0x02b81f));
    }

    private static void populateBLK_CJK_EXT_E() {
        SET_ENCODINGS.put("blk=CJK_Ext_E", CodePointSet.createNoDedup(0x02b820, 0x02ceaf));
    }

    private static void populateBLK_CJK_EXT_F() {
        SET_ENCODINGS.put("blk=CJK_Ext_F", CodePointSet.createNoDedup(0x02ceb0, 0x02ebef));
    }

    private static void populateBLK_CJK_EXT_G() {
        SET_ENCODINGS.put("blk=CJK_Ext_G", CodePointSet.createNoDedup(0x030000, 0x03134f));
    }

    private static void populateBLK_CJK_EXT_H() {
        SET_ENCODINGS.put("blk=CJK_Ext_H", CodePointSet.createNoDedup(0x031350, 0x0323af));
    }

    private static void populateBLK_CJK_RADICALS_SUP() {
        SET_ENCODINGS.put("blk=CJK_Radicals_Sup", CodePointSet.createNoDedup(0x002e80, 0x002eff));
    }

    private static void populateBLK_CJK_STROKES() {
        SET_ENCODINGS.put("blk=CJK_Strokes", CodePointSet.createNoDedup(0x0031c0, 0x0031ef));
    }

    private static void populateBLK_CJK_SYMBOLS() {
        SET_ENCODINGS.put("blk=CJK_Symbols", CodePointSet.createNoDedup(0x003000, 0x00303f));
    }

    private static void populateBLK_CARIAN() {
        SET_ENCODINGS.put("blk=Carian", CodePointSet.createNoDedup(0x0102a0, 0x0102df));
    }

    private static void populateBLK_CAUCASIAN_ALBANIAN() {
        SET_ENCODINGS.put("blk=Caucasian_Albanian", CodePointSet.createNoDedup(0x010530, 0x01056f));
    }

    private static void populateBLK_CHAKMA() {
        SET_ENCODINGS.put("blk=Chakma", CodePointSet.createNoDedup(0x011100, 0x01114f));
    }

    private static void populateBLK_CHAM() {
        SET_ENCODINGS.put("blk=Cham", CodePointSet.createNoDedup(0x00aa00, 0x00aa5f));
    }

    private static void populateBLK_CHEROKEE() {
        SET_ENCODINGS.put("blk=Cherokee", CodePointSet.createNoDedup(0x0013a0, 0x0013ff));
    }

    private static void populateBLK_CHEROKEE_SUP() {
        SET_ENCODINGS.put("blk=Cherokee_Sup", CodePointSet.createNoDedup(0x00ab70, 0x00abbf));
    }

    private static void populateBLK_CHESS_SYMBOLS() {
        SET_ENCODINGS.put("blk=Chess_Symbols", CodePointSet.createNoDedup(0x01fa00, 0x01fa6f));
    }

    private static void populateBLK_CHORASMIAN() {
        SET_ENCODINGS.put("blk=Chorasmian", CodePointSet.createNoDedup(0x010fb0, 0x010fdf));
    }

    private static void populateBLK_COMPAT_JAMO() {
        SET_ENCODINGS.put("blk=Compat_Jamo", CodePointSet.createNoDedup(0x003130, 0x00318f));
    }

    private static void populateBLK_CONTROL_PICTURES() {
        SET_ENCODINGS.put("blk=Control_Pictures", CodePointSet.createNoDedup(0x002400, 0x00243f));
    }

    private static void populateBLK_COPTIC() {
        SET_ENCODINGS.put("blk=Coptic", CodePointSet.createNoDedup(0x002c80, 0x002cff));
    }

    private static void populateBLK_COPTIC_EPACT_NUMBERS() {
        SET_ENCODINGS.put("blk=Coptic_Epact_Numbers", CodePointSet.createNoDedup(0x0102e0, 0x0102ff));
    }

    private static void populateBLK_COUNTING_ROD() {
        SET_ENCODINGS.put("blk=Counting_Rod", CodePointSet.createNoDedup(0x01d360, 0x01d37f));
    }

    private static void populateBLK_CUNEIFORM() {
        SET_ENCODINGS.put("blk=Cuneiform", CodePointSet.createNoDedup(0x012000, 0x0123ff));
    }

    private static void populateBLK_CUNEIFORM_NUMBERS() {
        SET_ENCODINGS.put("blk=Cuneiform_Numbers", CodePointSet.createNoDedup(0x012400, 0x01247f));
    }

    private static void populateBLK_CURRENCY_SYMBOLS() {
        SET_ENCODINGS.put("blk=Currency_Symbols", CodePointSet.createNoDedup(0x0020a0, 0x0020cf));
    }

    private static void populateBLK_CYPRIOT_SYLLABARY() {
        SET_ENCODINGS.put("blk=Cypriot_Syllabary", CodePointSet.createNoDedup(0x010800, 0x01083f));
    }

    private static void populateBLK_CYPRO_MINOAN() {
        SET_ENCODINGS.put("blk=Cypro_Minoan", CodePointSet.createNoDedup(0x012f90, 0x012fff));
    }

    private static void populateBLK_CYRILLIC() {
        SET_ENCODINGS.put("blk=Cyrillic", CodePointSet.createNoDedup(0x000400, 0x0004ff));
    }

    private static void populateBLK_CYRILLIC_EXT_A() {
        SET_ENCODINGS.put("blk=Cyrillic_Ext_A", CodePointSet.createNoDedup(0x002de0, 0x002dff));
    }

    private static void populateBLK_CYRILLIC_EXT_B() {
        SET_ENCODINGS.put("blk=Cyrillic_Ext_B", CodePointSet.createNoDedup(0x00a640, 0x00a69f));
    }

    private static void populateBLK_CYRILLIC_EXT_C() {
        SET_ENCODINGS.put("blk=Cyrillic_Ext_C", CodePointSet.createNoDedup(0x001c80, 0x001c8f));
    }

    private static void populateBLK_CYRILLIC_EXT_D() {
        SET_ENCODINGS.put("blk=Cyrillic_Ext_D", CodePointSet.createNoDedup(0x01e030, 0x01e08f));
    }

    private static void populateBLK_CYRILLIC_SUP() {
        SET_ENCODINGS.put("blk=Cyrillic_Sup", CodePointSet.createNoDedup(0x000500, 0x00052f));
    }

    private static void populateBLK_DESERET() {
        SET_ENCODINGS.put("blk=Deseret", CodePointSet.createNoDedup(0x010400, 0x01044f));
    }

    private static void populateBLK_DEVANAGARI() {
        SET_ENCODINGS.put("blk=Devanagari", CodePointSet.createNoDedup(0x000900, 0x00097f));
    }

    private static void populateBLK_DEVANAGARI_EXT() {
        SET_ENCODINGS.put("blk=Devanagari_Ext", CodePointSet.createNoDedup(0x00a8e0, 0x00a8ff));
    }

    private static void populateBLK_DEVANAGARI_EXT_A() {
        SET_ENCODINGS.put("blk=Devanagari_Ext_A", CodePointSet.createNoDedup(0x011b00, 0x011b5f));
    }

    private static void populateBLK_DIACRITICALS() {
        SET_ENCODINGS.put("blk=Diacriticals", CodePointSet.createNoDedup(0x000300, 0x00036f));
    }

    private static void populateBLK_DIACRITICALS_EXT() {
        SET_ENCODINGS.put("blk=Diacriticals_Ext", CodePointSet.createNoDedup(0x001ab0, 0x001aff));
    }

    private static void populateBLK_DIACRITICALS_FOR_SYMBOLS() {
        SET_ENCODINGS.put("blk=Diacriticals_For_Symbols", CodePointSet.createNoDedup(0x0020d0, 0x0020ff));
    }

    private static void populateBLK_DIACRITICALS_SUP() {
        SET_ENCODINGS.put("blk=Diacriticals_Sup", CodePointSet.createNoDedup(0x001dc0, 0x001dff));
    }

    private static void populateBLK_DINGBATS() {
        SET_ENCODINGS.put("blk=Dingbats", CodePointSet.createNoDedup(0x002700, 0x0027bf));
    }

    private static void populateBLK_DIVES_AKURU() {
        SET_ENCODINGS.put("blk=Dives_Akuru", CodePointSet.createNoDedup(0x011900, 0x01195f));
    }

    private static void populateBLK_DOGRA() {
        SET_ENCODINGS.put("blk=Dogra", CodePointSet.createNoDedup(0x011800, 0x01184f));
    }

    private static void populateBLK_DOMINO() {
        SET_ENCODINGS.put("blk=Domino", CodePointSet.createNoDedup(0x01f030, 0x01f09f));
    }

    private static void populateBLK_DUPLOYAN() {
        SET_ENCODINGS.put("blk=Duployan", CodePointSet.createNoDedup(0x01bc00, 0x01bc9f));
    }

    private static void populateBLK_EARLY_DYNASTIC_CUNEIFORM() {
        SET_ENCODINGS.put("blk=Early_Dynastic_Cuneiform", CodePointSet.createNoDedup(0x012480, 0x01254f));
    }

    private static void populateBLK_EGYPTIAN_HIEROGLYPH_FORMAT_CONTROLS() {
        SET_ENCODINGS.put("blk=Egyptian_Hieroglyph_Format_Controls", CodePointSet.createNoDedup(0x013430, 0x01345f));
    }

    private static void populateBLK_EGYPTIAN_HIEROGLYPHS() {
        SET_ENCODINGS.put("blk=Egyptian_Hieroglyphs", CodePointSet.createNoDedup(0x013000, 0x01342f));
    }

    private static void populateBLK_ELBASAN() {
        SET_ENCODINGS.put("blk=Elbasan", CodePointSet.createNoDedup(0x010500, 0x01052f));
    }

    private static void populateBLK_ELYMAIC() {
        SET_ENCODINGS.put("blk=Elymaic", CodePointSet.createNoDedup(0x010fe0, 0x010fff));
    }

    private static void populateBLK_EMOTICONS() {
        SET_ENCODINGS.put("blk=Emoticons", CodePointSet.createNoDedup(0x01f600, 0x01f64f));
    }

    private static void populateBLK_ENCLOSED_ALPHANUM() {
        SET_ENCODINGS.put("blk=Enclosed_Alphanum", CodePointSet.createNoDedup(0x002460, 0x0024ff));
    }

    private static void populateBLK_ENCLOSED_ALPHANUM_SUP() {
        SET_ENCODINGS.put("blk=Enclosed_Alphanum_Sup", CodePointSet.createNoDedup(0x01f100, 0x01f1ff));
    }

    private static void populateBLK_ENCLOSED_CJK() {
        SET_ENCODINGS.put("blk=Enclosed_CJK", CodePointSet.createNoDedup(0x003200, 0x0032ff));
    }

    private static void populateBLK_ENCLOSED_IDEOGRAPHIC_SUP() {
        SET_ENCODINGS.put("blk=Enclosed_Ideographic_Sup", CodePointSet.createNoDedup(0x01f200, 0x01f2ff));
    }

    private static void populateBLK_ETHIOPIC() {
        SET_ENCODINGS.put("blk=Ethiopic", CodePointSet.createNoDedup(0x001200, 0x00137f));
    }

    private static void populateBLK_ETHIOPIC_EXT() {
        SET_ENCODINGS.put("blk=Ethiopic_Ext", CodePointSet.createNoDedup(0x002d80, 0x002ddf));
    }

    private static void populateBLK_ETHIOPIC_EXT_A() {
        SET_ENCODINGS.put("blk=Ethiopic_Ext_A", CodePointSet.createNoDedup(0x00ab00, 0x00ab2f));
    }

    private static void populateBLK_ETHIOPIC_EXT_B() {
        SET_ENCODINGS.put("blk=Ethiopic_Ext_B", CodePointSet.createNoDedup(0x01e7e0, 0x01e7ff));
    }

    private static void populateBLK_ETHIOPIC_SUP() {
        SET_ENCODINGS.put("blk=Ethiopic_Sup", CodePointSet.createNoDedup(0x001380, 0x00139f));
    }

    private static void populateBLK_GEOMETRIC_SHAPES() {
        SET_ENCODINGS.put("blk=Geometric_Shapes", CodePointSet.createNoDedup(0x0025a0, 0x0025ff));
    }

    private static void populateBLK_GEOMETRIC_SHAPES_EXT() {
        SET_ENCODINGS.put("blk=Geometric_Shapes_Ext", CodePointSet.createNoDedup(0x01f780, 0x01f7ff));
    }

    private static void populateBLK_GEORGIAN() {
        SET_ENCODINGS.put("blk=Georgian", CodePointSet.createNoDedup(0x0010a0, 0x0010ff));
    }

    private static void populateBLK_GEORGIAN_EXT() {
        SET_ENCODINGS.put("blk=Georgian_Ext", CodePointSet.createNoDedup(0x001c90, 0x001cbf));
    }

    private static void populateBLK_GEORGIAN_SUP() {
        SET_ENCODINGS.put("blk=Georgian_Sup", CodePointSet.createNoDedup(0x002d00, 0x002d2f));
    }

    private static void populateBLK_GLAGOLITIC() {
        SET_ENCODINGS.put("blk=Glagolitic", CodePointSet.createNoDedup(0x002c00, 0x002c5f));
    }

    private static void populateBLK_GLAGOLITIC_SUP() {
        SET_ENCODINGS.put("blk=Glagolitic_Sup", CodePointSet.createNoDedup(0x01e000, 0x01e02f));
    }

    private static void populateBLK_GOTHIC() {
        SET_ENCODINGS.put("blk=Gothic", CodePointSet.createNoDedup(0x010330, 0x01034f));
    }

    private static void populateBLK_GRANTHA() {
        SET_ENCODINGS.put("blk=Grantha", CodePointSet.createNoDedup(0x011300, 0x01137f));
    }

    private static void populateBLK_GREEK() {
        SET_ENCODINGS.put("blk=Greek", CodePointSet.createNoDedup(0x000370, 0x0003ff));
    }

    private static void populateBLK_GREEK_EXT() {
        SET_ENCODINGS.put("blk=Greek_Ext", CodePointSet.createNoDedup(0x001f00, 0x001fff));
    }

    private static void populateBLK_GUJARATI() {
        SET_ENCODINGS.put("blk=Gujarati", CodePointSet.createNoDedup(0x000a80, 0x000aff));
    }

    private static void populateBLK_GUNJALA_GONDI() {
        SET_ENCODINGS.put("blk=Gunjala_Gondi", CodePointSet.createNoDedup(0x011d60, 0x011daf));
    }

    private static void populateBLK_GURMUKHI() {
        SET_ENCODINGS.put("blk=Gurmukhi", CodePointSet.createNoDedup(0x000a00, 0x000a7f));
    }

    private static void populateBLK_HALF_AND_FULL_FORMS() {
        SET_ENCODINGS.put("blk=Half_And_Full_Forms", CodePointSet.createNoDedup(0x00ff00, 0x00ffef));
    }

    private static void populateBLK_HALF_MARKS() {
        SET_ENCODINGS.put("blk=Half_Marks", CodePointSet.createNoDedup(0x00fe20, 0x00fe2f));
    }

    private static void populateBLK_HANGUL() {
        SET_ENCODINGS.put("blk=Hangul", CodePointSet.createNoDedup(0x00ac00, 0x00d7af));
    }

    private static void populateBLK_HANIFI_ROHINGYA() {
        SET_ENCODINGS.put("blk=Hanifi_Rohingya", CodePointSet.createNoDedup(0x010d00, 0x010d3f));
    }

    private static void populateBLK_HANUNOO() {
        SET_ENCODINGS.put("blk=Hanunoo", CodePointSet.createNoDedup(0x001720, 0x00173f));
    }

    private static void populateBLK_HATRAN() {
        SET_ENCODINGS.put("blk=Hatran", CodePointSet.createNoDedup(0x0108e0, 0x0108ff));
    }

    private static void populateBLK_HEBREW() {
        SET_ENCODINGS.put("blk=Hebrew", CodePointSet.createNoDedup(0x000590, 0x0005ff));
    }

    private static void populateBLK_HIGH_PU_SURROGATES() {
        SET_ENCODINGS.put("blk=High_PU_Surrogates", CodePointSet.createNoDedup(0x00db80, 0x00dbff));
    }

    private static void populateBLK_HIGH_SURROGATES() {
        SET_ENCODINGS.put("blk=High_Surrogates", CodePointSet.createNoDedup(0x00d800, 0x00db7f));
    }

    private static void populateBLK_HIRAGANA() {
        SET_ENCODINGS.put("blk=Hiragana", CodePointSet.createNoDedup(0x003040, 0x00309f));
    }

    private static void populateBLK_IDC() {
        SET_ENCODINGS.put("blk=IDC", CodePointSet.createNoDedup(0x002ff0, 0x002fff));
    }

    private static void populateBLK_IPA_EXT() {
        SET_ENCODINGS.put("blk=IPA_Ext", CodePointSet.createNoDedup(0x000250, 0x0002af));
    }

    private static void populateBLK_IDEOGRAPHIC_SYMBOLS() {
        SET_ENCODINGS.put("blk=Ideographic_Symbols", CodePointSet.createNoDedup(0x016fe0, 0x016fff));
    }

    private static void populateBLK_IMPERIAL_ARAMAIC() {
        SET_ENCODINGS.put("blk=Imperial_Aramaic", CodePointSet.createNoDedup(0x010840, 0x01085f));
    }

    private static void populateBLK_INDIC_NUMBER_FORMS() {
        SET_ENCODINGS.put("blk=Indic_Number_Forms", CodePointSet.createNoDedup(0x00a830, 0x00a83f));
    }

    private static void populateBLK_INDIC_SIYAQ_NUMBERS() {
        SET_ENCODINGS.put("blk=Indic_Siyaq_Numbers", CodePointSet.createNoDedup(0x01ec70, 0x01ecbf));
    }

    private static void populateBLK_INSCRIPTIONAL_PAHLAVI() {
        SET_ENCODINGS.put("blk=Inscriptional_Pahlavi", CodePointSet.createNoDedup(0x010b60, 0x010b7f));
    }

    private static void populateBLK_INSCRIPTIONAL_PARTHIAN() {
        SET_ENCODINGS.put("blk=Inscriptional_Parthian", CodePointSet.createNoDedup(0x010b40, 0x010b5f));
    }

    private static void populateBLK_JAMO() {
        SET_ENCODINGS.put("blk=Jamo", CodePointSet.createNoDedup(0x001100, 0x0011ff));
    }

    private static void populateBLK_JAMO_EXT_A() {
        SET_ENCODINGS.put("blk=Jamo_Ext_A", CodePointSet.createNoDedup(0x00a960, 0x00a97f));
    }

    private static void populateBLK_JAMO_EXT_B() {
        SET_ENCODINGS.put("blk=Jamo_Ext_B", CodePointSet.createNoDedup(0x00d7b0, 0x00d7ff));
    }

    private static void populateBLK_JAVANESE() {
        SET_ENCODINGS.put("blk=Javanese", CodePointSet.createNoDedup(0x00a980, 0x00a9df));
    }

    private static void populateBLK_KAITHI() {
        SET_ENCODINGS.put("blk=Kaithi", CodePointSet.createNoDedup(0x011080, 0x0110cf));
    }

    private static void populateBLK_KAKTOVIK_NUMERALS() {
        SET_ENCODINGS.put("blk=Kaktovik_Numerals", CodePointSet.createNoDedup(0x01d2c0, 0x01d2df));
    }

    private static void populateBLK_KANA_EXT_A() {
        SET_ENCODINGS.put("blk=Kana_Ext_A", CodePointSet.createNoDedup(0x01b100, 0x01b12f));
    }

    private static void populateBLK_KANA_EXT_B() {
        SET_ENCODINGS.put("blk=Kana_Ext_B", CodePointSet.createNoDedup(0x01aff0, 0x01afff));
    }

    private static void populateBLK_KANA_SUP() {
        SET_ENCODINGS.put("blk=Kana_Sup", CodePointSet.createNoDedup(0x01b000, 0x01b0ff));
    }

    private static void populateBLK_KANBUN() {
        SET_ENCODINGS.put("blk=Kanbun", CodePointSet.createNoDedup(0x003190, 0x00319f));
    }

    private static void populateBLK_KANGXI() {
        SET_ENCODINGS.put("blk=Kangxi", CodePointSet.createNoDedup(0x002f00, 0x002fdf));
    }

    private static void populateBLK_KANNADA() {
        SET_ENCODINGS.put("blk=Kannada", CodePointSet.createNoDedup(0x000c80, 0x000cff));
    }

    private static void populateBLK_KATAKANA() {
        SET_ENCODINGS.put("blk=Katakana", CodePointSet.createNoDedup(0x0030a0, 0x0030ff));
    }

    private static void populateBLK_KATAKANA_EXT() {
        SET_ENCODINGS.put("blk=Katakana_Ext", CodePointSet.createNoDedup(0x0031f0, 0x0031ff));
    }

    private static void populateBLK_KAWI() {
        SET_ENCODINGS.put("blk=Kawi", CodePointSet.createNoDedup(0x011f00, 0x011f5f));
    }

    private static void populateBLK_KAYAH_LI() {
        SET_ENCODINGS.put("blk=Kayah_Li", CodePointSet.createNoDedup(0x00a900, 0x00a92f));
    }

    private static void populateBLK_KHAROSHTHI() {
        SET_ENCODINGS.put("blk=Kharoshthi", CodePointSet.createNoDedup(0x010a00, 0x010a5f));
    }

    private static void populateBLK_KHITAN_SMALL_SCRIPT() {
        SET_ENCODINGS.put("blk=Khitan_Small_Script", CodePointSet.createNoDedup(0x018b00, 0x018cff));
    }

    private static void populateBLK_KHMER() {
        SET_ENCODINGS.put("blk=Khmer", CodePointSet.createNoDedup(0x001780, 0x0017ff));
    }

    private static void populateBLK_KHMER_SYMBOLS() {
        SET_ENCODINGS.put("blk=Khmer_Symbols", CodePointSet.createNoDedup(0x0019e0, 0x0019ff));
    }

    private static void populateBLK_KHOJKI() {
        SET_ENCODINGS.put("blk=Khojki", CodePointSet.createNoDedup(0x011200, 0x01124f));
    }

    private static void populateBLK_KHUDAWADI() {
        SET_ENCODINGS.put("blk=Khudawadi", CodePointSet.createNoDedup(0x0112b0, 0x0112ff));
    }

    private static void populateBLK_LAO() {
        SET_ENCODINGS.put("blk=Lao", CodePointSet.createNoDedup(0x000e80, 0x000eff));
    }

    private static void populateBLK_LATIN_1_SUP() {
        SET_ENCODINGS.put("blk=Latin_1_Sup", CodePointSet.createNoDedup(0x000080, 0x0000ff));
    }

    private static void populateBLK_LATIN_EXT_A() {
        SET_ENCODINGS.put("blk=Latin_Ext_A", CodePointSet.createNoDedup(0x000100, 0x00017f));
    }

    private static void populateBLK_LATIN_EXT_ADDITIONAL() {
        SET_ENCODINGS.put("blk=Latin_Ext_Additional", CodePointSet.createNoDedup(0x001e00, 0x001eff));
    }

    private static void populateBLK_LATIN_EXT_B() {
        SET_ENCODINGS.put("blk=Latin_Ext_B", CodePointSet.createNoDedup(0x000180, 0x00024f));
    }

    private static void populateBLK_LATIN_EXT_C() {
        SET_ENCODINGS.put("blk=Latin_Ext_C", CodePointSet.createNoDedup(0x002c60, 0x002c7f));
    }

    private static void populateBLK_LATIN_EXT_D() {
        SET_ENCODINGS.put("blk=Latin_Ext_D", CodePointSet.createNoDedup(0x00a720, 0x00a7ff));
    }

    private static void populateBLK_LATIN_EXT_E() {
        SET_ENCODINGS.put("blk=Latin_Ext_E", CodePointSet.createNoDedup(0x00ab30, 0x00ab6f));
    }

    private static void populateBLK_LATIN_EXT_F() {
        SET_ENCODINGS.put("blk=Latin_Ext_F", CodePointSet.createNoDedup(0x010780, 0x0107bf));
    }

    private static void populateBLK_LATIN_EXT_G() {
        SET_ENCODINGS.put("blk=Latin_Ext_G", CodePointSet.createNoDedup(0x01df00, 0x01dfff));
    }

    private static void populateBLK_LEPCHA() {
        SET_ENCODINGS.put("blk=Lepcha", CodePointSet.createNoDedup(0x001c00, 0x001c4f));
    }

    private static void populateBLK_LETTERLIKE_SYMBOLS() {
        SET_ENCODINGS.put("blk=Letterlike_Symbols", CodePointSet.createNoDedup(0x002100, 0x00214f));
    }

    private static void populateBLK_LIMBU() {
        SET_ENCODINGS.put("blk=Limbu", CodePointSet.createNoDedup(0x001900, 0x00194f));
    }

    private static void populateBLK_LINEAR_A() {
        SET_ENCODINGS.put("blk=Linear_A", CodePointSet.createNoDedup(0x010600, 0x01077f));
    }

    private static void populateBLK_LINEAR_B_IDEOGRAMS() {
        SET_ENCODINGS.put("blk=Linear_B_Ideograms", CodePointSet.createNoDedup(0x010080, 0x0100ff));
    }

    private static void populateBLK_LINEAR_B_SYLLABARY() {
        SET_ENCODINGS.put("blk=Linear_B_Syllabary", CodePointSet.createNoDedup(0x010000, 0x01007f));
    }

    private static void populateBLK_LISU() {
        SET_ENCODINGS.put("blk=Lisu", CodePointSet.createNoDedup(0x00a4d0, 0x00a4ff));
    }

    private static void populateBLK_LISU_SUP() {
        SET_ENCODINGS.put("blk=Lisu_Sup", CodePointSet.createNoDedup(0x011fb0, 0x011fbf));
    }

    private static void populateBLK_LOW_SURROGATES() {
        SET_ENCODINGS.put("blk=Low_Surrogates", CodePointSet.createNoDedup(0x00dc00, 0x00dfff));
    }

    private static void populateBLK_LYCIAN() {
        SET_ENCODINGS.put("blk=Lycian", CodePointSet.createNoDedup(0x010280, 0x01029f));
    }

    private static void populateBLK_LYDIAN() {
        SET_ENCODINGS.put("blk=Lydian", CodePointSet.createNoDedup(0x010920, 0x01093f));
    }

    private static void populateBLK_MAHAJANI() {
        SET_ENCODINGS.put("blk=Mahajani", CodePointSet.createNoDedup(0x011150, 0x01117f));
    }

    private static void populateBLK_MAHJONG() {
        SET_ENCODINGS.put("blk=Mahjong", CodePointSet.createNoDedup(0x01f000, 0x01f02f));
    }

    private static void populateBLK_MAKASAR() {
        SET_ENCODINGS.put("blk=Makasar", CodePointSet.createNoDedup(0x011ee0, 0x011eff));
    }

    private static void populateBLK_MALAYALAM() {
        SET_ENCODINGS.put("blk=Malayalam", CodePointSet.createNoDedup(0x000d00, 0x000d7f));
    }

    private static void populateBLK_MANDAIC() {
        SET_ENCODINGS.put("blk=Mandaic", CodePointSet.createNoDedup(0x000840, 0x00085f));
    }

    private static void populateBLK_MANICHAEAN() {
        SET_ENCODINGS.put("blk=Manichaean", CodePointSet.createNoDedup(0x010ac0, 0x010aff));
    }

    private static void populateBLK_MARCHEN() {
        SET_ENCODINGS.put("blk=Marchen", CodePointSet.createNoDedup(0x011c70, 0x011cbf));
    }

    private static void populateBLK_MASARAM_GONDI() {
        SET_ENCODINGS.put("blk=Masaram_Gondi", CodePointSet.createNoDedup(0x011d00, 0x011d5f));
    }

    private static void populateBLK_MATH_ALPHANUM() {
        SET_ENCODINGS.put("blk=Math_Alphanum", CodePointSet.createNoDedup(0x01d400, 0x01d7ff));
    }

    private static void populateBLK_MATH_OPERATORS() {
        SET_ENCODINGS.put("blk=Math_Operators", CodePointSet.createNoDedup(0x002200, 0x0022ff));
    }

    private static void populateBLK_MAYAN_NUMERALS() {
        SET_ENCODINGS.put("blk=Mayan_Numerals", CodePointSet.createNoDedup(0x01d2e0, 0x01d2ff));
    }

    private static void populateBLK_MEDEFAIDRIN() {
        SET_ENCODINGS.put("blk=Medefaidrin", CodePointSet.createNoDedup(0x016e40, 0x016e9f));
    }

    private static void populateBLK_MEETEI_MAYEK() {
        SET_ENCODINGS.put("blk=Meetei_Mayek", CodePointSet.createNoDedup(0x00abc0, 0x00abff));
    }

    private static void populateBLK_MEETEI_MAYEK_EXT() {
        SET_ENCODINGS.put("blk=Meetei_Mayek_Ext", CodePointSet.createNoDedup(0x00aae0, 0x00aaff));
    }

    private static void populateBLK_MENDE_KIKAKUI() {
        SET_ENCODINGS.put("blk=Mende_Kikakui", CodePointSet.createNoDedup(0x01e800, 0x01e8df));
    }

    private static void populateBLK_MEROITIC_CURSIVE() {
        SET_ENCODINGS.put("blk=Meroitic_Cursive", CodePointSet.createNoDedup(0x0109a0, 0x0109ff));
    }

    private static void populateBLK_MEROITIC_HIEROGLYPHS() {
        SET_ENCODINGS.put("blk=Meroitic_Hieroglyphs", CodePointSet.createNoDedup(0x010980, 0x01099f));
    }

    private static void populateBLK_MIAO() {
        SET_ENCODINGS.put("blk=Miao", CodePointSet.createNoDedup(0x016f00, 0x016f9f));
    }

    private static void populateBLK_MISC_ARROWS() {
        SET_ENCODINGS.put("blk=Misc_Arrows", CodePointSet.createNoDedup(0x002b00, 0x002bff));
    }

    private static void populateBLK_MISC_MATH_SYMBOLS_A() {
        SET_ENCODINGS.put("blk=Misc_Math_Symbols_A", CodePointSet.createNoDedup(0x0027c0, 0x0027ef));
    }

    private static void populateBLK_MISC_MATH_SYMBOLS_B() {
        SET_ENCODINGS.put("blk=Misc_Math_Symbols_B", CodePointSet.createNoDedup(0x002980, 0x0029ff));
    }

    private static void populateBLK_MISC_PICTOGRAPHS() {
        SET_ENCODINGS.put("blk=Misc_Pictographs", CodePointSet.createNoDedup(0x01f300, 0x01f5ff));
    }

    private static void populateBLK_MISC_SYMBOLS() {
        SET_ENCODINGS.put("blk=Misc_Symbols", CodePointSet.createNoDedup(0x002600, 0x0026ff));
    }

    private static void populateBLK_MISC_TECHNICAL() {
        SET_ENCODINGS.put("blk=Misc_Technical", CodePointSet.createNoDedup(0x002300, 0x0023ff));
    }

    private static void populateBLK_MODI() {
        SET_ENCODINGS.put("blk=Modi", CodePointSet.createNoDedup(0x011600, 0x01165f));
    }

    private static void populateBLK_MODIFIER_LETTERS() {
        SET_ENCODINGS.put("blk=Modifier_Letters", CodePointSet.createNoDedup(0x0002b0, 0x0002ff));
    }

    private static void populateBLK_MODIFIER_TONE_LETTERS() {
        SET_ENCODINGS.put("blk=Modifier_Tone_Letters", CodePointSet.createNoDedup(0x00a700, 0x00a71f));
    }

    private static void populateBLK_MONGOLIAN() {
        SET_ENCODINGS.put("blk=Mongolian", CodePointSet.createNoDedup(0x001800, 0x0018af));
    }

    private static void populateBLK_MONGOLIAN_SUP() {
        SET_ENCODINGS.put("blk=Mongolian_Sup", CodePointSet.createNoDedup(0x011660, 0x01167f));
    }

    private static void populateBLK_MRO() {
        SET_ENCODINGS.put("blk=Mro", CodePointSet.createNoDedup(0x016a40, 0x016a6f));
    }

    private static void populateBLK_MULTANI() {
        SET_ENCODINGS.put("blk=Multani", CodePointSet.createNoDedup(0x011280, 0x0112af));
    }

    private static void populateBLK_MUSIC() {
        SET_ENCODINGS.put("blk=Music", CodePointSet.createNoDedup(0x01d100, 0x01d1ff));
    }

    private static void populateBLK_MYANMAR() {
        SET_ENCODINGS.put("blk=Myanmar", CodePointSet.createNoDedup(0x001000, 0x00109f));
    }

    private static void populateBLK_MYANMAR_EXT_A() {
        SET_ENCODINGS.put("blk=Myanmar_Ext_A", CodePointSet.createNoDedup(0x00aa60, 0x00aa7f));
    }

    private static void populateBLK_MYANMAR_EXT_B() {
        SET_ENCODINGS.put("blk=Myanmar_Ext_B", CodePointSet.createNoDedup(0x00a9e0, 0x00a9ff));
    }

    private static void populateBLK_NB() {
        SET_ENCODINGS.put("blk=NB",
                        CodePointSet.createNoDedup(0x002fe0, 0x002fef, 0x010200, 0x01027f, 0x0103e0, 0x0103ff, 0x0105c0, 0x0105ff, 0x0107c0, 0x0107ff, 0x0108b0, 0x0108df, 0x010940, 0x01097f, 0x010aa0,
                                        0x010abf, 0x010bb0, 0x010bff, 0x010c50, 0x010c7f, 0x010d40, 0x010e5f, 0x011250, 0x01127f, 0x011380, 0x0113ff, 0x0114e0, 0x01157f, 0x0116d0, 0x0116ff, 0x011750,
                                        0x0117ff, 0x011850, 0x01189f, 0x011960, 0x01199f, 0x011b60, 0x011bff, 0x011cc0, 0x011cff, 0x011db0, 0x011edf, 0x011f60, 0x011faf, 0x012550, 0x012f8f, 0x013460,
                                        0x0143ff, 0x014680, 0x0167ff, 0x016b90, 0x016e3f, 0x016ea0, 0x016eff, 0x016fa0, 0x016fdf, 0x018d80, 0x01afef, 0x01b300, 0x01bbff, 0x01bcb0, 0x01ceff, 0x01cfd0,
                                        0x01cfff, 0x01d250, 0x01d2bf, 0x01d380, 0x01d3ff, 0x01dab0, 0x01deff, 0x01e090, 0x01e0ff, 0x01e150, 0x01e28f, 0x01e300, 0x01e4cf, 0x01e500, 0x01e7df, 0x01e8e0,
                                        0x01e8ff, 0x01e960, 0x01ec6f, 0x01ecc0, 0x01ecff, 0x01ed50, 0x01edff, 0x01ef00, 0x01efff, 0x01fc00, 0x01ffff, 0x02a6e0, 0x02a6ff, 0x02ebf0, 0x02f7ff, 0x02fa20,
                                        0x02ffff, 0x0323b0, 0x0dffff, 0x0e0080, 0x0e00ff, 0x0e01f0, 0x0effff));
    }

    private static void populateBLK_NKO() {
        SET_ENCODINGS.put("blk=NKo", CodePointSet.createNoDedup(0x0007c0, 0x0007ff));
    }

    private static void populateBLK_NABATAEAN() {
        SET_ENCODINGS.put("blk=Nabataean", CodePointSet.createNoDedup(0x010880, 0x0108af));
    }

    private static void populateBLK_NAG_MUNDARI() {
        SET_ENCODINGS.put("blk=Nag_Mundari", CodePointSet.createNoDedup(0x01e4d0, 0x01e4ff));
    }

    private static void populateBLK_NANDINAGARI() {
        SET_ENCODINGS.put("blk=Nandinagari", CodePointSet.createNoDedup(0x0119a0, 0x0119ff));
    }

    private static void populateBLK_NEW_TAI_LUE() {
        SET_ENCODINGS.put("blk=New_Tai_Lue", CodePointSet.createNoDedup(0x001980, 0x0019df));
    }

    private static void populateBLK_NEWA() {
        SET_ENCODINGS.put("blk=Newa", CodePointSet.createNoDedup(0x011400, 0x01147f));
    }

    private static void populateBLK_NUMBER_FORMS() {
        SET_ENCODINGS.put("blk=Number_Forms", CodePointSet.createNoDedup(0x002150, 0x00218f));
    }

    private static void populateBLK_NUSHU() {
        SET_ENCODINGS.put("blk=Nushu", CodePointSet.createNoDedup(0x01b170, 0x01b2ff));
    }

    private static void populateBLK_NYIAKENG_PUACHUE_HMONG() {
        SET_ENCODINGS.put("blk=Nyiakeng_Puachue_Hmong", CodePointSet.createNoDedup(0x01e100, 0x01e14f));
    }

    private static void populateBLK_OCR() {
        SET_ENCODINGS.put("blk=OCR", CodePointSet.createNoDedup(0x002440, 0x00245f));
    }

    private static void populateBLK_OGHAM() {
        SET_ENCODINGS.put("blk=Ogham", CodePointSet.createNoDedup(0x001680, 0x00169f));
    }

    private static void populateBLK_OL_CHIKI() {
        SET_ENCODINGS.put("blk=Ol_Chiki", CodePointSet.createNoDedup(0x001c50, 0x001c7f));
    }

    private static void populateBLK_OLD_HUNGARIAN() {
        SET_ENCODINGS.put("blk=Old_Hungarian", CodePointSet.createNoDedup(0x010c80, 0x010cff));
    }

    private static void populateBLK_OLD_ITALIC() {
        SET_ENCODINGS.put("blk=Old_Italic", CodePointSet.createNoDedup(0x010300, 0x01032f));
    }

    private static void populateBLK_OLD_NORTH_ARABIAN() {
        SET_ENCODINGS.put("blk=Old_North_Arabian", CodePointSet.createNoDedup(0x010a80, 0x010a9f));
    }

    private static void populateBLK_OLD_PERMIC() {
        SET_ENCODINGS.put("blk=Old_Permic", CodePointSet.createNoDedup(0x010350, 0x01037f));
    }

    private static void populateBLK_OLD_PERSIAN() {
        SET_ENCODINGS.put("blk=Old_Persian", CodePointSet.createNoDedup(0x0103a0, 0x0103df));
    }

    private static void populateBLK_OLD_SOGDIAN() {
        SET_ENCODINGS.put("blk=Old_Sogdian", CodePointSet.createNoDedup(0x010f00, 0x010f2f));
    }

    private static void populateBLK_OLD_SOUTH_ARABIAN() {
        SET_ENCODINGS.put("blk=Old_South_Arabian", CodePointSet.createNoDedup(0x010a60, 0x010a7f));
    }

    private static void populateBLK_OLD_TURKIC() {
        SET_ENCODINGS.put("blk=Old_Turkic", CodePointSet.createNoDedup(0x010c00, 0x010c4f));
    }

    private static void populateBLK_OLD_UYGHUR() {
        SET_ENCODINGS.put("blk=Old_Uyghur", CodePointSet.createNoDedup(0x010f70, 0x010faf));
    }

    private static void populateBLK_ORIYA() {
        SET_ENCODINGS.put("blk=Oriya", CodePointSet.createNoDedup(0x000b00, 0x000b7f));
    }

    private static void populateBLK_ORNAMENTAL_DINGBATS() {
        SET_ENCODINGS.put("blk=Ornamental_Dingbats", CodePointSet.createNoDedup(0x01f650, 0x01f67f));
    }

    private static void populateBLK_OSAGE() {
        SET_ENCODINGS.put("blk=Osage", CodePointSet.createNoDedup(0x0104b0, 0x0104ff));
    }

    private static void populateBLK_OSMANYA() {
        SET_ENCODINGS.put("blk=Osmanya", CodePointSet.createNoDedup(0x010480, 0x0104af));
    }

    private static void populateBLK_OTTOMAN_SIYAQ_NUMBERS() {
        SET_ENCODINGS.put("blk=Ottoman_Siyaq_Numbers", CodePointSet.createNoDedup(0x01ed00, 0x01ed4f));
    }

    private static void populateBLK_PUA() {
        SET_ENCODINGS.put("blk=PUA", CodePointSet.createNoDedup(0x00e000, 0x00f8ff));
    }

    private static void populateBLK_PAHAWH_HMONG() {
        SET_ENCODINGS.put("blk=Pahawh_Hmong", CodePointSet.createNoDedup(0x016b00, 0x016b8f));
    }

    private static void populateBLK_PALMYRENE() {
        SET_ENCODINGS.put("blk=Palmyrene", CodePointSet.createNoDedup(0x010860, 0x01087f));
    }

    private static void populateBLK_PAU_CIN_HAU() {
        SET_ENCODINGS.put("blk=Pau_Cin_Hau", CodePointSet.createNoDedup(0x011ac0, 0x011aff));
    }

    private static void populateBLK_PHAGS_PA() {
        SET_ENCODINGS.put("blk=Phags_Pa", CodePointSet.createNoDedup(0x00a840, 0x00a87f));
    }

    private static void populateBLK_PHAISTOS() {
        SET_ENCODINGS.put("blk=Phaistos", CodePointSet.createNoDedup(0x0101d0, 0x0101ff));
    }

    private static void populateBLK_PHOENICIAN() {
        SET_ENCODINGS.put("blk=Phoenician", CodePointSet.createNoDedup(0x010900, 0x01091f));
    }

    private static void populateBLK_PHONETIC_EXT() {
        SET_ENCODINGS.put("blk=Phonetic_Ext", CodePointSet.createNoDedup(0x001d00, 0x001d7f));
    }

    private static void populateBLK_PHONETIC_EXT_SUP() {
        SET_ENCODINGS.put("blk=Phonetic_Ext_Sup", CodePointSet.createNoDedup(0x001d80, 0x001dbf));
    }

    private static void populateBLK_PLAYING_CARDS() {
        SET_ENCODINGS.put("blk=Playing_Cards", CodePointSet.createNoDedup(0x01f0a0, 0x01f0ff));
    }

    private static void populateBLK_PSALTER_PAHLAVI() {
        SET_ENCODINGS.put("blk=Psalter_Pahlavi", CodePointSet.createNoDedup(0x010b80, 0x010baf));
    }

    private static void populateBLK_PUNCTUATION() {
        SET_ENCODINGS.put("blk=Punctuation", CodePointSet.createNoDedup(0x002000, 0x00206f));
    }

    private static void populateBLK_REJANG() {
        SET_ENCODINGS.put("blk=Rejang", CodePointSet.createNoDedup(0x00a930, 0x00a95f));
    }

    private static void populateBLK_RUMI() {
        SET_ENCODINGS.put("blk=Rumi", CodePointSet.createNoDedup(0x010e60, 0x010e7f));
    }

    private static void populateBLK_RUNIC() {
        SET_ENCODINGS.put("blk=Runic", CodePointSet.createNoDedup(0x0016a0, 0x0016ff));
    }

    private static void populateBLK_SAMARITAN() {
        SET_ENCODINGS.put("blk=Samaritan", CodePointSet.createNoDedup(0x000800, 0x00083f));
    }

    private static void populateBLK_SAURASHTRA() {
        SET_ENCODINGS.put("blk=Saurashtra", CodePointSet.createNoDedup(0x00a880, 0x00a8df));
    }

    private static void populateBLK_SHARADA() {
        SET_ENCODINGS.put("blk=Sharada", CodePointSet.createNoDedup(0x011180, 0x0111df));
    }

    private static void populateBLK_SHAVIAN() {
        SET_ENCODINGS.put("blk=Shavian", CodePointSet.createNoDedup(0x010450, 0x01047f));
    }

    private static void populateBLK_SHORTHAND_FORMAT_CONTROLS() {
        SET_ENCODINGS.put("blk=Shorthand_Format_Controls", CodePointSet.createNoDedup(0x01bca0, 0x01bcaf));
    }

    private static void populateBLK_SIDDHAM() {
        SET_ENCODINGS.put("blk=Siddham", CodePointSet.createNoDedup(0x011580, 0x0115ff));
    }

    private static void populateBLK_SINHALA() {
        SET_ENCODINGS.put("blk=Sinhala", CodePointSet.createNoDedup(0x000d80, 0x000dff));
    }

    private static void populateBLK_SINHALA_ARCHAIC_NUMBERS() {
        SET_ENCODINGS.put("blk=Sinhala_Archaic_Numbers", CodePointSet.createNoDedup(0x0111e0, 0x0111ff));
    }

    private static void populateBLK_SMALL_FORMS() {
        SET_ENCODINGS.put("blk=Small_Forms", CodePointSet.createNoDedup(0x00fe50, 0x00fe6f));
    }

    private static void populateBLK_SMALL_KANA_EXT() {
        SET_ENCODINGS.put("blk=Small_Kana_Ext", CodePointSet.createNoDedup(0x01b130, 0x01b16f));
    }

    private static void populateBLK_SOGDIAN() {
        SET_ENCODINGS.put("blk=Sogdian", CodePointSet.createNoDedup(0x010f30, 0x010f6f));
    }

    private static void populateBLK_SORA_SOMPENG() {
        SET_ENCODINGS.put("blk=Sora_Sompeng", CodePointSet.createNoDedup(0x0110d0, 0x0110ff));
    }

    private static void populateBLK_SOYOMBO() {
        SET_ENCODINGS.put("blk=Soyombo", CodePointSet.createNoDedup(0x011a50, 0x011aaf));
    }

    private static void populateBLK_SPECIALS() {
        SET_ENCODINGS.put("blk=Specials", CodePointSet.createNoDedup(0x00fff0, 0x00ffff));
    }

    private static void populateBLK_SUNDANESE() {
        SET_ENCODINGS.put("blk=Sundanese", CodePointSet.createNoDedup(0x001b80, 0x001bbf));
    }

    private static void populateBLK_SUNDANESE_SUP() {
        SET_ENCODINGS.put("blk=Sundanese_Sup", CodePointSet.createNoDedup(0x001cc0, 0x001ccf));
    }

    private static void populateBLK_SUP_ARROWS_A() {
        SET_ENCODINGS.put("blk=Sup_Arrows_A", CodePointSet.createNoDedup(0x0027f0, 0x0027ff));
    }

    private static void populateBLK_SUP_ARROWS_B() {
        SET_ENCODINGS.put("blk=Sup_Arrows_B", CodePointSet.createNoDedup(0x002900, 0x00297f));
    }

    private static void populateBLK_SUP_ARROWS_C() {
        SET_ENCODINGS.put("blk=Sup_Arrows_C", CodePointSet.createNoDedup(0x01f800, 0x01f8ff));
    }

    private static void populateBLK_SUP_MATH_OPERATORS() {
        SET_ENCODINGS.put("blk=Sup_Math_Operators", CodePointSet.createNoDedup(0x002a00, 0x002aff));
    }

    private static void populateBLK_SUP_PUA_A() {
        SET_ENCODINGS.put("blk=Sup_PUA_A", CodePointSet.createNoDedup(0x0f0000, 0x0fffff));
    }

    private static void populateBLK_SUP_PUA_B() {
        SET_ENCODINGS.put("blk=Sup_PUA_B", CodePointSet.createNoDedup(0x100000, 0x10ffff));
    }

    private static void populateBLK_SUP_PUNCTUATION() {
        SET_ENCODINGS.put("blk=Sup_Punctuation", CodePointSet.createNoDedup(0x002e00, 0x002e7f));
    }

    private static void populateBLK_SUP_SYMBOLS_AND_PICTOGRAPHS() {
        SET_ENCODINGS.put("blk=Sup_Symbols_And_Pictographs", CodePointSet.createNoDedup(0x01f900, 0x01f9ff));
    }

    private static void populateBLK_SUPER_AND_SUB() {
        SET_ENCODINGS.put("blk=Super_And_Sub", CodePointSet.createNoDedup(0x002070, 0x00209f));
    }

    private static void populateBLK_SUTTON_SIGNWRITING() {
        SET_ENCODINGS.put("blk=Sutton_SignWriting", CodePointSet.createNoDedup(0x01d800, 0x01daaf));
    }

    private static void populateBLK_SYLOTI_NAGRI() {
        SET_ENCODINGS.put("blk=Syloti_Nagri", CodePointSet.createNoDedup(0x00a800, 0x00a82f));
    }

    private static void populateBLK_SYMBOLS_AND_PICTOGRAPHS_EXT_A() {
        SET_ENCODINGS.put("blk=Symbols_And_Pictographs_Ext_A", CodePointSet.createNoDedup(0x01fa70, 0x01faff));
    }

    private static void populateBLK_SYMBOLS_FOR_LEGACY_COMPUTING() {
        SET_ENCODINGS.put("blk=Symbols_For_Legacy_Computing", CodePointSet.createNoDedup(0x01fb00, 0x01fbff));
    }

    private static void populateBLK_SYRIAC() {
        SET_ENCODINGS.put("blk=Syriac", CodePointSet.createNoDedup(0x000700, 0x00074f));
    }

    private static void populateBLK_SYRIAC_SUP() {
        SET_ENCODINGS.put("blk=Syriac_Sup", CodePointSet.createNoDedup(0x000860, 0x00086f));
    }

    private static void populateBLK_TAGALOG() {
        SET_ENCODINGS.put("blk=Tagalog", CodePointSet.createNoDedup(0x001700, 0x00171f));
    }

    private static void populateBLK_TAGBANWA() {
        SET_ENCODINGS.put("blk=Tagbanwa", CodePointSet.createNoDedup(0x001760, 0x00177f));
    }

    private static void populateBLK_TAGS() {
        SET_ENCODINGS.put("blk=Tags", CodePointSet.createNoDedup(0x0e0000, 0x0e007f));
    }

    private static void populateBLK_TAI_LE() {
        SET_ENCODINGS.put("blk=Tai_Le", CodePointSet.createNoDedup(0x001950, 0x00197f));
    }

    private static void populateBLK_TAI_THAM() {
        SET_ENCODINGS.put("blk=Tai_Tham", CodePointSet.createNoDedup(0x001a20, 0x001aaf));
    }

    private static void populateBLK_TAI_VIET() {
        SET_ENCODINGS.put("blk=Tai_Viet", CodePointSet.createNoDedup(0x00aa80, 0x00aadf));
    }

    private static void populateBLK_TAI_XUAN_JING() {
        SET_ENCODINGS.put("blk=Tai_Xuan_Jing", CodePointSet.createNoDedup(0x01d300, 0x01d35f));
    }

    private static void populateBLK_TAKRI() {
        SET_ENCODINGS.put("blk=Takri", CodePointSet.createNoDedup(0x011680, 0x0116cf));
    }

    private static void populateBLK_TAMIL() {
        SET_ENCODINGS.put("blk=Tamil", CodePointSet.createNoDedup(0x000b80, 0x000bff));
    }

    private static void populateBLK_TAMIL_SUP() {
        SET_ENCODINGS.put("blk=Tamil_Sup", CodePointSet.createNoDedup(0x011fc0, 0x011fff));
    }

    private static void populateBLK_TANGSA() {
        SET_ENCODINGS.put("blk=Tangsa", CodePointSet.createNoDedup(0x016a70, 0x016acf));
    }

    private static void populateBLK_TANGUT() {
        SET_ENCODINGS.put("blk=Tangut", CodePointSet.createNoDedup(0x017000, 0x0187ff));
    }

    private static void populateBLK_TANGUT_COMPONENTS() {
        SET_ENCODINGS.put("blk=Tangut_Components", CodePointSet.createNoDedup(0x018800, 0x018aff));
    }

    private static void populateBLK_TANGUT_SUP() {
        SET_ENCODINGS.put("blk=Tangut_Sup", CodePointSet.createNoDedup(0x018d00, 0x018d7f));
    }

    private static void populateBLK_TELUGU() {
        SET_ENCODINGS.put("blk=Telugu", CodePointSet.createNoDedup(0x000c00, 0x000c7f));
    }

    private static void populateBLK_THAANA() {
        SET_ENCODINGS.put("blk=Thaana", CodePointSet.createNoDedup(0x000780, 0x0007bf));
    }

    private static void populateBLK_THAI() {
        SET_ENCODINGS.put("blk=Thai", CodePointSet.createNoDedup(0x000e00, 0x000e7f));
    }

    private static void populateBLK_TIBETAN() {
        SET_ENCODINGS.put("blk=Tibetan", CodePointSet.createNoDedup(0x000f00, 0x000fff));
    }

    private static void populateBLK_TIFINAGH() {
        SET_ENCODINGS.put("blk=Tifinagh", CodePointSet.createNoDedup(0x002d30, 0x002d7f));
    }

    private static void populateBLK_TIRHUTA() {
        SET_ENCODINGS.put("blk=Tirhuta", CodePointSet.createNoDedup(0x011480, 0x0114df));
    }

    private static void populateBLK_TOTO() {
        SET_ENCODINGS.put("blk=Toto", CodePointSet.createNoDedup(0x01e290, 0x01e2bf));
    }

    private static void populateBLK_TRANSPORT_AND_MAP() {
        SET_ENCODINGS.put("blk=Transport_And_Map", CodePointSet.createNoDedup(0x01f680, 0x01f6ff));
    }

    private static void populateBLK_UCAS() {
        SET_ENCODINGS.put("blk=UCAS", CodePointSet.createNoDedup(0x001400, 0x00167f));
    }

    private static void populateBLK_UCAS_EXT() {
        SET_ENCODINGS.put("blk=UCAS_Ext", CodePointSet.createNoDedup(0x0018b0, 0x0018ff));
    }

    private static void populateBLK_UCAS_EXT_A() {
        SET_ENCODINGS.put("blk=UCAS_Ext_A", CodePointSet.createNoDedup(0x011ab0, 0x011abf));
    }

    private static void populateBLK_UGARITIC() {
        SET_ENCODINGS.put("blk=Ugaritic", CodePointSet.createNoDedup(0x010380, 0x01039f));
    }

    private static void populateBLK_VS() {
        SET_ENCODINGS.put("blk=VS", CodePointSet.createNoDedup(0x00fe00, 0x00fe0f));
    }

    private static void populateBLK_VS_SUP() {
        SET_ENCODINGS.put("blk=VS_Sup", CodePointSet.createNoDedup(0x0e0100, 0x0e01ef));
    }

    private static void populateBLK_VAI() {
        SET_ENCODINGS.put("blk=Vai", CodePointSet.createNoDedup(0x00a500, 0x00a63f));
    }

    private static void populateBLK_VEDIC_EXT() {
        SET_ENCODINGS.put("blk=Vedic_Ext", CodePointSet.createNoDedup(0x001cd0, 0x001cff));
    }

    private static void populateBLK_VERTICAL_FORMS() {
        SET_ENCODINGS.put("blk=Vertical_Forms", CodePointSet.createNoDedup(0x00fe10, 0x00fe1f));
    }

    private static void populateBLK_VITHKUQI() {
        SET_ENCODINGS.put("blk=Vithkuqi", CodePointSet.createNoDedup(0x010570, 0x0105bf));
    }

    private static void populateBLK_WANCHO() {
        SET_ENCODINGS.put("blk=Wancho", CodePointSet.createNoDedup(0x01e2c0, 0x01e2ff));
    }

    private static void populateBLK_WARANG_CITI() {
        SET_ENCODINGS.put("blk=Warang_Citi", CodePointSet.createNoDedup(0x0118a0, 0x0118ff));
    }

    private static void populateBLK_YEZIDI() {
        SET_ENCODINGS.put("blk=Yezidi", CodePointSet.createNoDedup(0x010e80, 0x010ebf));
    }

    private static void populateBLK_YI_RADICALS() {
        SET_ENCODINGS.put("blk=Yi_Radicals", CodePointSet.createNoDedup(0x00a490, 0x00a4cf));
    }

    private static void populateBLK_YI_SYLLABLES() {
        SET_ENCODINGS.put("blk=Yi_Syllables", CodePointSet.createNoDedup(0x00a000, 0x00a48f));
    }

    private static void populateBLK_YIJING() {
        SET_ENCODINGS.put("blk=Yijing", CodePointSet.createNoDedup(0x004dc0, 0x004dff));
    }

    private static void populateBLK_ZANABAZAR_SQUARE() {
        SET_ENCODINGS.put("blk=Zanabazar_Square", CodePointSet.createNoDedup(0x011a00, 0x011a4f));
    }

    private static void populateBLK_ZNAMENNY_MUSIC() {
        SET_ENCODINGS.put("blk=Znamenny_Music", CodePointSet.createNoDedup(0x01cf00, 0x01cfcf));
    }

    private static void populateGC_C() {
        SET_ENCODINGS.put("gc=C", CodePointSet.createNoDedup(0x000000, 0x00001f, 0x00007f, 0x00009f, 0x0000ad, 0x0000ad, 0x000378, 0x000379, 0x000380, 0x000383, 0x00038b, 0x00038b, 0x00038d, 0x00038d,
                        0x0003a2, 0x0003a2, 0x000530, 0x000530, 0x000557, 0x000558, 0x00058b, 0x00058c, 0x000590, 0x000590, 0x0005c8, 0x0005cf, 0x0005eb, 0x0005ee, 0x0005f5, 0x000605, 0x00061c,
                        0x00061c, 0x0006dd, 0x0006dd, 0x00070e, 0x00070f, 0x00074b, 0x00074c, 0x0007b2, 0x0007bf, 0x0007fb, 0x0007fc, 0x00082e, 0x00082f, 0x00083f, 0x00083f, 0x00085c, 0x00085d,
                        0x00085f, 0x00085f, 0x00086b, 0x00086f, 0x00088f, 0x000897, 0x0008e2, 0x0008e2, 0x000984, 0x000984, 0x00098d, 0x00098e, 0x000991, 0x000992, 0x0009a9, 0x0009a9, 0x0009b1,
                        0x0009b1, 0x0009b3, 0x0009b5, 0x0009ba, 0x0009bb, 0x0009c5, 0x0009c6, 0x0009c9, 0x0009ca, 0x0009cf, 0x0009d6, 0x0009d8, 0x0009db, 0x0009de, 0x0009de, 0x0009e4, 0x0009e5,
                        0x0009ff, 0x000a00, 0x000a04, 0x000a04, 0x000a0b, 0x000a0e, 0x000a11, 0x000a12, 0x000a29, 0x000a29, 0x000a31, 0x000a31, 0x000a34, 0x000a34, 0x000a37, 0x000a37, 0x000a3a,
                        0x000a3b, 0x000a3d, 0x000a3d, 0x000a43, 0x000a46, 0x000a49, 0x000a4a, 0x000a4e, 0x000a50, 0x000a52, 0x000a58, 0x000a5d, 0x000a5d, 0x000a5f, 0x000a65, 0x000a77, 0x000a80,
                        0x000a84, 0x000a84, 0x000a8e, 0x000a8e, 0x000a92, 0x000a92, 0x000aa9, 0x000aa9, 0x000ab1, 0x000ab1, 0x000ab4, 0x000ab4, 0x000aba, 0x000abb, 0x000ac6, 0x000ac6, 0x000aca,
                        0x000aca, 0x000ace, 0x000acf, 0x000ad1, 0x000adf, 0x000ae4, 0x000ae5, 0x000af2, 0x000af8, 0x000b00, 0x000b00, 0x000b04, 0x000b04, 0x000b0d, 0x000b0e, 0x000b11, 0x000b12,
                        0x000b29, 0x000b29, 0x000b31, 0x000b31, 0x000b34, 0x000b34, 0x000b3a, 0x000b3b, 0x000b45, 0x000b46, 0x000b49, 0x000b4a, 0x000b4e, 0x000b54, 0x000b58, 0x000b5b, 0x000b5e,
                        0x000b5e, 0x000b64, 0x000b65, 0x000b78, 0x000b81, 0x000b84, 0x000b84, 0x000b8b, 0x000b8d, 0x000b91, 0x000b91, 0x000b96, 0x000b98, 0x000b9b, 0x000b9b, 0x000b9d, 0x000b9d,
                        0x000ba0, 0x000ba2, 0x000ba5, 0x000ba7, 0x000bab, 0x000bad, 0x000bba, 0x000bbd, 0x000bc3, 0x000bc5, 0x000bc9, 0x000bc9, 0x000bce, 0x000bcf, 0x000bd1, 0x000bd6, 0x000bd8,
                        0x000be5, 0x000bfb, 0x000bff, 0x000c0d, 0x000c0d, 0x000c11, 0x000c11, 0x000c29, 0x000c29, 0x000c3a, 0x000c3b, 0x000c45, 0x000c45, 0x000c49, 0x000c49, 0x000c4e, 0x000c54,
                        0x000c57, 0x000c57, 0x000c5b, 0x000c5c, 0x000c5e, 0x000c5f, 0x000c64, 0x000c65, 0x000c70, 0x000c76, 0x000c8d, 0x000c8d, 0x000c91, 0x000c91, 0x000ca9, 0x000ca9, 0x000cb4,
                        0x000cb4, 0x000cba, 0x000cbb, 0x000cc5, 0x000cc5, 0x000cc9, 0x000cc9, 0x000cce, 0x000cd4, 0x000cd7, 0x000cdc, 0x000cdf, 0x000cdf, 0x000ce4, 0x000ce5, 0x000cf0, 0x000cf0,
                        0x000cf4, 0x000cff, 0x000d0d, 0x000d0d, 0x000d11, 0x000d11, 0x000d45, 0x000d45, 0x000d49, 0x000d49, 0x000d50, 0x000d53, 0x000d64, 0x000d65, 0x000d80, 0x000d80, 0x000d84,
                        0x000d84, 0x000d97, 0x000d99, 0x000db2, 0x000db2, 0x000dbc, 0x000dbc, 0x000dbe, 0x000dbf, 0x000dc7, 0x000dc9, 0x000dcb, 0x000dce, 0x000dd5, 0x000dd5, 0x000dd7, 0x000dd7,
                        0x000de0, 0x000de5, 0x000df0, 0x000df1, 0x000df5, 0x000e00, 0x000e3b, 0x000e3e, 0x000e5c, 0x000e80, 0x000e83, 0x000e83, 0x000e85, 0x000e85, 0x000e8b, 0x000e8b, 0x000ea4,
                        0x000ea4, 0x000ea6, 0x000ea6, 0x000ebe, 0x000ebf, 0x000ec5, 0x000ec5, 0x000ec7, 0x000ec7, 0x000ecf, 0x000ecf, 0x000eda, 0x000edb, 0x000ee0, 0x000eff, 0x000f48, 0x000f48,
                        0x000f6d, 0x000f70, 0x000f98, 0x000f98, 0x000fbd, 0x000fbd, 0x000fcd, 0x000fcd, 0x000fdb, 0x000fff, 0x0010c6, 0x0010c6, 0x0010c8, 0x0010cc, 0x0010ce, 0x0010cf, 0x001249,
                        0x001249, 0x00124e, 0x00124f, 0x001257, 0x001257, 0x001259, 0x001259, 0x00125e, 0x00125f, 0x001289, 0x001289, 0x00128e, 0x00128f, 0x0012b1, 0x0012b1, 0x0012b6, 0x0012b7,
                        0x0012bf, 0x0012bf, 0x0012c1, 0x0012c1, 0x0012c6, 0x0012c7, 0x0012d7, 0x0012d7, 0x001311, 0x001311, 0x001316, 0x001317, 0x00135b, 0x00135c, 0x00137d, 0x00137f, 0x00139a,
                        0x00139f, 0x0013f6, 0x0013f7, 0x0013fe, 0x0013ff, 0x00169d, 0x00169f, 0x0016f9, 0x0016ff, 0x001716, 0x00171e, 0x001737, 0x00173f, 0x001754, 0x00175f, 0x00176d, 0x00176d,
                        0x001771, 0x001771, 0x001774, 0x00177f, 0x0017de, 0x0017df, 0x0017ea, 0x0017ef, 0x0017fa, 0x0017ff, 0x00180e, 0x00180e, 0x00181a, 0x00181f, 0x001879, 0x00187f, 0x0018ab,
                        0x0018af, 0x0018f6, 0x0018ff, 0x00191f, 0x00191f, 0x00192c, 0x00192f, 0x00193c, 0x00193f, 0x001941, 0x001943, 0x00196e, 0x00196f, 0x001975, 0x00197f, 0x0019ac, 0x0019af,
                        0x0019ca, 0x0019cf, 0x0019db, 0x0019dd, 0x001a1c, 0x001a1d, 0x001a5f, 0x001a5f, 0x001a7d, 0x001a7e, 0x001a8a, 0x001a8f, 0x001a9a, 0x001a9f, 0x001aae, 0x001aaf, 0x001acf,
                        0x001aff, 0x001b4d, 0x001b4f, 0x001b7f, 0x001b7f, 0x001bf4, 0x001bfb, 0x001c38, 0x001c3a, 0x001c4a, 0x001c4c, 0x001c89, 0x001c8f, 0x001cbb, 0x001cbc, 0x001cc8, 0x001ccf,
                        0x001cfb, 0x001cff, 0x001f16, 0x001f17, 0x001f1e, 0x001f1f, 0x001f46, 0x001f47, 0x001f4e, 0x001f4f, 0x001f58, 0x001f58, 0x001f5a, 0x001f5a, 0x001f5c, 0x001f5c, 0x001f5e,
                        0x001f5e, 0x001f7e, 0x001f7f, 0x001fb5, 0x001fb5, 0x001fc5, 0x001fc5, 0x001fd4, 0x001fd5, 0x001fdc, 0x001fdc, 0x001ff0, 0x001ff1, 0x001ff5, 0x001ff5, 0x001fff, 0x001fff,
                        0x00200b, 0x00200f, 0x00202a, 0x00202e, 0x002060, 0x00206f, 0x002072, 0x002073, 0x00208f, 0x00208f, 0x00209d, 0x00209f, 0x0020c1, 0x0020cf, 0x0020f1, 0x0020ff, 0x00218c,
                        0x00218f, 0x002427, 0x00243f, 0x00244b, 0x00245f, 0x002b74, 0x002b75, 0x002b96, 0x002b96, 0x002cf4, 0x002cf8, 0x002d26, 0x002d26, 0x002d28, 0x002d2c, 0x002d2e, 0x002d2f,
                        0x002d68, 0x002d6e, 0x002d71, 0x002d7e, 0x002d97, 0x002d9f, 0x002da7, 0x002da7, 0x002daf, 0x002daf, 0x002db7, 0x002db7, 0x002dbf, 0x002dbf, 0x002dc7, 0x002dc7, 0x002dcf,
                        0x002dcf, 0x002dd7, 0x002dd7, 0x002ddf, 0x002ddf, 0x002e5e, 0x002e7f, 0x002e9a, 0x002e9a, 0x002ef4, 0x002eff, 0x002fd6, 0x002fef, 0x002ffc, 0x002fff, 0x003040, 0x003040,
                        0x003097, 0x003098, 0x003100, 0x003104, 0x003130, 0x003130, 0x00318f, 0x00318f, 0x0031e4, 0x0031ef, 0x00321f, 0x00321f, 0x00a48d, 0x00a48f, 0x00a4c7, 0x00a4cf, 0x00a62c,
                        0x00a63f, 0x00a6f8, 0x00a6ff, 0x00a7cb, 0x00a7cf, 0x00a7d2, 0x00a7d2, 0x00a7d4, 0x00a7d4, 0x00a7da, 0x00a7f1, 0x00a82d, 0x00a82f, 0x00a83a, 0x00a83f, 0x00a878, 0x00a87f,
                        0x00a8c6, 0x00a8cd, 0x00a8da, 0x00a8df, 0x00a954, 0x00a95e, 0x00a97d, 0x00a97f, 0x00a9ce, 0x00a9ce, 0x00a9da, 0x00a9dd, 0x00a9ff, 0x00a9ff, 0x00aa37, 0x00aa3f, 0x00aa4e,
                        0x00aa4f, 0x00aa5a, 0x00aa5b, 0x00aac3, 0x00aada, 0x00aaf7, 0x00ab00, 0x00ab07, 0x00ab08, 0x00ab0f, 0x00ab10, 0x00ab17, 0x00ab1f, 0x00ab27, 0x00ab27, 0x00ab2f, 0x00ab2f,
                        0x00ab6c, 0x00ab6f, 0x00abee, 0x00abef, 0x00abfa, 0x00abff, 0x00d7a4, 0x00d7af, 0x00d7c7, 0x00d7ca, 0x00d7fc, 0x00f8ff, 0x00fa6e, 0x00fa6f, 0x00fada, 0x00faff, 0x00fb07,
                        0x00fb12, 0x00fb18, 0x00fb1c, 0x00fb37, 0x00fb37, 0x00fb3d, 0x00fb3d, 0x00fb3f, 0x00fb3f, 0x00fb42, 0x00fb42, 0x00fb45, 0x00fb45, 0x00fbc3, 0x00fbd2, 0x00fd90, 0x00fd91,
                        0x00fdc8, 0x00fdce, 0x00fdd0, 0x00fdef, 0x00fe1a, 0x00fe1f, 0x00fe53, 0x00fe53, 0x00fe67, 0x00fe67, 0x00fe6c, 0x00fe6f, 0x00fe75, 0x00fe75, 0x00fefd, 0x00ff00, 0x00ffbf,
                        0x00ffc1, 0x00ffc8, 0x00ffc9, 0x00ffd0, 0x00ffd1, 0x00ffd8, 0x00ffd9, 0x00ffdd, 0x00ffdf, 0x00ffe7, 0x00ffe7, 0x00ffef, 0x00fffb, 0x00fffe, 0x00ffff, 0x01000c, 0x01000c,
                        0x010027, 0x010027, 0x01003b, 0x01003b, 0x01003e, 0x01003e, 0x01004e, 0x01004f, 0x01005e, 0x01007f, 0x0100fb, 0x0100ff, 0x010103, 0x010106, 0x010134, 0x010136, 0x01018f,
                        0x01018f, 0x01019d, 0x01019f, 0x0101a1, 0x0101cf, 0x0101fe, 0x01027f, 0x01029d, 0x01029f, 0x0102d1, 0x0102df, 0x0102fc, 0x0102ff, 0x010324, 0x01032c, 0x01034b, 0x01034f,
                        0x01037b, 0x01037f, 0x01039e, 0x01039e, 0x0103c4, 0x0103c7, 0x0103d6, 0x0103ff, 0x01049e, 0x01049f, 0x0104aa, 0x0104af, 0x0104d4, 0x0104d7, 0x0104fc, 0x0104ff, 0x010528,
                        0x01052f, 0x010564, 0x01056e, 0x01057b, 0x01057b, 0x01058b, 0x01058b, 0x010593, 0x010593, 0x010596, 0x010596, 0x0105a2, 0x0105a2, 0x0105b2, 0x0105b2, 0x0105ba, 0x0105ba,
                        0x0105bd, 0x0105ff, 0x010737, 0x01073f, 0x010756, 0x01075f, 0x010768, 0x01077f, 0x010786, 0x010786, 0x0107b1, 0x0107b1, 0x0107bb, 0x0107ff, 0x010806, 0x010807, 0x010809,
                        0x010809, 0x010836, 0x010836, 0x010839, 0x01083b, 0x01083d, 0x01083e, 0x010856, 0x010856, 0x01089f, 0x0108a6, 0x0108b0, 0x0108df, 0x0108f3, 0x0108f3, 0x0108f6, 0x0108fa,
                        0x01091c, 0x01091e, 0x01093a, 0x01093e, 0x010940, 0x01097f, 0x0109b8, 0x0109bb, 0x0109d0, 0x0109d1, 0x010a04, 0x010a04, 0x010a07, 0x010a0b, 0x010a14, 0x010a14, 0x010a18,
                        0x010a18, 0x010a36, 0x010a37, 0x010a3b, 0x010a3e, 0x010a49, 0x010a4f, 0x010a59, 0x010a5f, 0x010aa0, 0x010abf, 0x010ae7, 0x010aea, 0x010af7, 0x010aff, 0x010b36, 0x010b38,
                        0x010b56, 0x010b57, 0x010b73, 0x010b77, 0x010b92, 0x010b98, 0x010b9d, 0x010ba8, 0x010bb0, 0x010bff, 0x010c49, 0x010c7f, 0x010cb3, 0x010cbf, 0x010cf3, 0x010cf9, 0x010d28,
                        0x010d2f, 0x010d3a, 0x010e5f, 0x010e7f, 0x010e7f, 0x010eaa, 0x010eaa, 0x010eae, 0x010eaf, 0x010eb2, 0x010efc, 0x010f28, 0x010f2f, 0x010f5a, 0x010f6f, 0x010f8a, 0x010faf,
                        0x010fcc, 0x010fdf, 0x010ff7, 0x010fff, 0x01104e, 0x011051, 0x011076, 0x01107e, 0x0110bd, 0x0110bd, 0x0110c3, 0x0110cf, 0x0110e9, 0x0110ef, 0x0110fa, 0x0110ff, 0x011135,
                        0x011135, 0x011148, 0x01114f, 0x011177, 0x01117f, 0x0111e0, 0x0111e0, 0x0111f5, 0x0111ff, 0x011212, 0x011212, 0x011242, 0x01127f, 0x011287, 0x011287, 0x011289, 0x011289,
                        0x01128e, 0x01128e, 0x01129e, 0x01129e, 0x0112aa, 0x0112af, 0x0112eb, 0x0112ef, 0x0112fa, 0x0112ff, 0x011304, 0x011304, 0x01130d, 0x01130e, 0x011311, 0x011312, 0x011329,
                        0x011329, 0x011331, 0x011331, 0x011334, 0x011334, 0x01133a, 0x01133a, 0x011345, 0x011346, 0x011349, 0x01134a, 0x01134e, 0x01134f, 0x011351, 0x011356, 0x011358, 0x01135c,
                        0x011364, 0x011365, 0x01136d, 0x01136f, 0x011375, 0x0113ff, 0x01145c, 0x01145c, 0x011462, 0x01147f, 0x0114c8, 0x0114cf, 0x0114da, 0x01157f, 0x0115b6, 0x0115b7, 0x0115de,
                        0x0115ff, 0x011645, 0x01164f, 0x01165a, 0x01165f, 0x01166d, 0x01167f, 0x0116ba, 0x0116bf, 0x0116ca, 0x0116ff, 0x01171b, 0x01171c, 0x01172c, 0x01172f, 0x011747, 0x0117ff,
                        0x01183c, 0x01189f, 0x0118f3, 0x0118fe, 0x011907, 0x011908, 0x01190a, 0x01190b, 0x011914, 0x011914, 0x011917, 0x011917, 0x011936, 0x011936, 0x011939, 0x01193a, 0x011947,
                        0x01194f, 0x01195a, 0x01199f, 0x0119a8, 0x0119a9, 0x0119d8, 0x0119d9, 0x0119e5, 0x0119ff, 0x011a48, 0x011a4f, 0x011aa3, 0x011aaf, 0x011af9, 0x011aff, 0x011b0a, 0x011bff,
                        0x011c09, 0x011c09, 0x011c37, 0x011c37, 0x011c46, 0x011c4f, 0x011c6d, 0x011c6f, 0x011c90, 0x011c91, 0x011ca8, 0x011ca8, 0x011cb7, 0x011cff, 0x011d07, 0x011d07, 0x011d0a,
                        0x011d0a, 0x011d37, 0x011d39, 0x011d3b, 0x011d3b, 0x011d3e, 0x011d3e, 0x011d48, 0x011d4f, 0x011d5a, 0x011d5f, 0x011d66, 0x011d66, 0x011d69, 0x011d69, 0x011d8f, 0x011d8f,
                        0x011d92, 0x011d92, 0x011d99, 0x011d9f, 0x011daa, 0x011edf, 0x011ef9, 0x011eff, 0x011f11, 0x011f11, 0x011f3b, 0x011f3d, 0x011f5a, 0x011faf, 0x011fb1, 0x011fbf, 0x011ff2,
                        0x011ffe, 0x01239a, 0x0123ff, 0x01246f, 0x01246f, 0x012475, 0x01247f, 0x012544, 0x012f8f, 0x012ff3, 0x012fff, 0x013430, 0x01343f, 0x013456, 0x0143ff, 0x014647, 0x0167ff,
                        0x016a39, 0x016a3f, 0x016a5f, 0x016a5f, 0x016a6a, 0x016a6d, 0x016abf, 0x016abf, 0x016aca, 0x016acf, 0x016aee, 0x016aef, 0x016af6, 0x016aff, 0x016b46, 0x016b4f, 0x016b5a,
                        0x016b5a, 0x016b62, 0x016b62, 0x016b78, 0x016b7c, 0x016b90, 0x016e3f, 0x016e9b, 0x016eff, 0x016f4b, 0x016f4e, 0x016f88, 0x016f8e, 0x016fa0, 0x016fdf, 0x016fe5, 0x016fef,
                        0x016ff2, 0x016fff, 0x0187f8, 0x0187ff, 0x018cd6, 0x018cff, 0x018d09, 0x01afef, 0x01aff4, 0x01aff4, 0x01affc, 0x01affc, 0x01afff, 0x01afff, 0x01b123, 0x01b131, 0x01b133,
                        0x01b14f, 0x01b153, 0x01b154, 0x01b156, 0x01b163, 0x01b168, 0x01b16f, 0x01b2fc, 0x01bbff, 0x01bc6b, 0x01bc6f, 0x01bc7d, 0x01bc7f, 0x01bc89, 0x01bc8f, 0x01bc9a, 0x01bc9b,
                        0x01bca0, 0x01ceff, 0x01cf2e, 0x01cf2f, 0x01cf47, 0x01cf4f, 0x01cfc4, 0x01cfff, 0x01d0f6, 0x01d0ff, 0x01d127, 0x01d128, 0x01d173, 0x01d17a, 0x01d1eb, 0x01d1ff, 0x01d246,
                        0x01d2bf, 0x01d2d4, 0x01d2df, 0x01d2f4, 0x01d2ff, 0x01d357, 0x01d35f, 0x01d379, 0x01d3ff, 0x01d455, 0x01d455, 0x01d49d, 0x01d49d, 0x01d4a0, 0x01d4a1, 0x01d4a3, 0x01d4a4,
                        0x01d4a7, 0x01d4a8, 0x01d4ad, 0x01d4ad, 0x01d4ba, 0x01d4ba, 0x01d4bc, 0x01d4bc, 0x01d4c4, 0x01d4c4, 0x01d506, 0x01d506, 0x01d50b, 0x01d50c, 0x01d515, 0x01d515, 0x01d51d,
                        0x01d51d, 0x01d53a, 0x01d53a, 0x01d53f, 0x01d53f, 0x01d545, 0x01d545, 0x01d547, 0x01d549, 0x01d551, 0x01d551, 0x01d6a6, 0x01d6a7, 0x01d7cc, 0x01d7cd, 0x01da8c, 0x01da9a,
                        0x01daa0, 0x01daa0, 0x01dab0, 0x01deff, 0x01df1f, 0x01df24, 0x01df2b, 0x01dfff, 0x01e007, 0x01e007, 0x01e019, 0x01e01a, 0x01e022, 0x01e022, 0x01e025, 0x01e025, 0x01e02b,
                        0x01e02f, 0x01e06e, 0x01e08e, 0x01e090, 0x01e0ff, 0x01e12d, 0x01e12f, 0x01e13e, 0x01e13f, 0x01e14a, 0x01e14d, 0x01e150, 0x01e28f, 0x01e2af, 0x01e2bf, 0x01e2fa, 0x01e2fe,
                        0x01e300, 0x01e4cf, 0x01e4fa, 0x01e7df, 0x01e7e7, 0x01e7e7, 0x01e7ec, 0x01e7ec, 0x01e7ef, 0x01e7ef, 0x01e7ff, 0x01e7ff, 0x01e8c5, 0x01e8c6, 0x01e8d7, 0x01e8ff, 0x01e94c,
                        0x01e94f, 0x01e95a, 0x01e95d, 0x01e960, 0x01ec70, 0x01ecb5, 0x01ed00, 0x01ed3e, 0x01edff, 0x01ee04, 0x01ee04, 0x01ee20, 0x01ee20, 0x01ee23, 0x01ee23, 0x01ee25, 0x01ee26,
                        0x01ee28, 0x01ee28, 0x01ee33, 0x01ee33, 0x01ee38, 0x01ee38, 0x01ee3a, 0x01ee3a, 0x01ee3c, 0x01ee41, 0x01ee43, 0x01ee46, 0x01ee48, 0x01ee48, 0x01ee4a, 0x01ee4a, 0x01ee4c,
                        0x01ee4c, 0x01ee50, 0x01ee50, 0x01ee53, 0x01ee53, 0x01ee55, 0x01ee56, 0x01ee58, 0x01ee58, 0x01ee5a, 0x01ee5a, 0x01ee5c, 0x01ee5c, 0x01ee5e, 0x01ee5e, 0x01ee60, 0x01ee60,
                        0x01ee63, 0x01ee63, 0x01ee65, 0x01ee66, 0x01ee6b, 0x01ee6b, 0x01ee73, 0x01ee73, 0x01ee78, 0x01ee78, 0x01ee7d, 0x01ee7d, 0x01ee7f, 0x01ee7f, 0x01ee8a, 0x01ee8a, 0x01ee9c,
                        0x01eea0, 0x01eea4, 0x01eea4, 0x01eeaa, 0x01eeaa, 0x01eebc, 0x01eeef, 0x01eef2, 0x01efff, 0x01f02c, 0x01f02f, 0x01f094, 0x01f09f, 0x01f0af, 0x01f0b0, 0x01f0c0, 0x01f0c0,
                        0x01f0d0, 0x01f0d0, 0x01f0f6, 0x01f0ff, 0x01f1ae, 0x01f1e5, 0x01f203, 0x01f20f, 0x01f23c, 0x01f23f, 0x01f249, 0x01f24f, 0x01f252, 0x01f25f, 0x01f266, 0x01f2ff, 0x01f6d8,
                        0x01f6db, 0x01f6ed, 0x01f6ef, 0x01f6fd, 0x01f6ff, 0x01f777, 0x01f77a, 0x01f7da, 0x01f7df, 0x01f7ec, 0x01f7ef, 0x01f7f1, 0x01f7ff, 0x01f80c, 0x01f80f, 0x01f848, 0x01f84f,
                        0x01f85a, 0x01f85f, 0x01f888, 0x01f88f, 0x01f8ae, 0x01f8af, 0x01f8b2, 0x01f8ff, 0x01fa54, 0x01fa5f, 0x01fa6e, 0x01fa6f, 0x01fa7d, 0x01fa7f, 0x01fa89, 0x01fa8f, 0x01fabe,
                        0x01fabe, 0x01fac6, 0x01facd, 0x01fadc, 0x01fadf, 0x01fae9, 0x01faef, 0x01faf9, 0x01faff, 0x01fb93, 0x01fb93, 0x01fbcb, 0x01fbef, 0x01fbfa, 0x01ffff, 0x02a6e0, 0x02a6ff,
                        0x02b73a, 0x02b73f, 0x02b81e, 0x02b81f, 0x02cea2, 0x02ceaf, 0x02ebe1, 0x02f7ff, 0x02fa1e, 0x02ffff, 0x03134b, 0x03134f, 0x0323b0, 0x0e00ff, 0x0e01f0, 0x10ffff));
    }

    private static void populateGC_CC() {
        SET_ENCODINGS.put("gc=Cc", CodePointSet.createNoDedup(0x000000, 0x00001f, 0x00007f, 0x00009f));
    }

    private static void populateGC_CF() {
        SET_ENCODINGS.put("gc=Cf",
                        CodePointSet.createNoDedup(0x0000ad, 0x0000ad, 0x000600, 0x000605, 0x00061c, 0x00061c, 0x0006dd, 0x0006dd, 0x00070f, 0x00070f, 0x000890, 0x000891, 0x0008e2, 0x0008e2, 0x00180e,
                                        0x00180e, 0x00200b, 0x00200f, 0x00202a, 0x00202e, 0x002060, 0x002064, 0x002066, 0x00206f, 0x00feff, 0x00feff, 0x00fff9, 0x00fffb, 0x0110bd, 0x0110bd, 0x0110cd,
                                        0x0110cd, 0x013430, 0x01343f, 0x01bca0, 0x01bca3, 0x01d173, 0x01d17a, 0x0e0001, 0x0e0001, 0x0e0020, 0x0e007f));
    }

    private static void populateGC_CN() {
        SET_ENCODINGS.put("gc=Cn", CodePointSet.createNoDedup(0x000378, 0x000379, 0x000380, 0x000383, 0x00038b, 0x00038b, 0x00038d, 0x00038d, 0x0003a2, 0x0003a2, 0x000530, 0x000530, 0x000557,
                        0x000558, 0x00058b, 0x00058c, 0x000590, 0x000590, 0x0005c8, 0x0005cf, 0x0005eb, 0x0005ee, 0x0005f5, 0x0005ff, 0x00070e, 0x00070e, 0x00074b, 0x00074c, 0x0007b2, 0x0007bf,
                        0x0007fb, 0x0007fc, 0x00082e, 0x00082f, 0x00083f, 0x00083f, 0x00085c, 0x00085d, 0x00085f, 0x00085f, 0x00086b, 0x00086f, 0x00088f, 0x00088f, 0x000892, 0x000897, 0x000984,
                        0x000984, 0x00098d, 0x00098e, 0x000991, 0x000992, 0x0009a9, 0x0009a9, 0x0009b1, 0x0009b1, 0x0009b3, 0x0009b5, 0x0009ba, 0x0009bb, 0x0009c5, 0x0009c6, 0x0009c9, 0x0009ca,
                        0x0009cf, 0x0009d6, 0x0009d8, 0x0009db, 0x0009de, 0x0009de, 0x0009e4, 0x0009e5, 0x0009ff, 0x000a00, 0x000a04, 0x000a04, 0x000a0b, 0x000a0e, 0x000a11, 0x000a12, 0x000a29,
                        0x000a29, 0x000a31, 0x000a31, 0x000a34, 0x000a34, 0x000a37, 0x000a37, 0x000a3a, 0x000a3b, 0x000a3d, 0x000a3d, 0x000a43, 0x000a46, 0x000a49, 0x000a4a, 0x000a4e, 0x000a50,
                        0x000a52, 0x000a58, 0x000a5d, 0x000a5d, 0x000a5f, 0x000a65, 0x000a77, 0x000a80, 0x000a84, 0x000a84, 0x000a8e, 0x000a8e, 0x000a92, 0x000a92, 0x000aa9, 0x000aa9, 0x000ab1,
                        0x000ab1, 0x000ab4, 0x000ab4, 0x000aba, 0x000abb, 0x000ac6, 0x000ac6, 0x000aca, 0x000aca, 0x000ace, 0x000acf, 0x000ad1, 0x000adf, 0x000ae4, 0x000ae5, 0x000af2, 0x000af8,
                        0x000b00, 0x000b00, 0x000b04, 0x000b04, 0x000b0d, 0x000b0e, 0x000b11, 0x000b12, 0x000b29, 0x000b29, 0x000b31, 0x000b31, 0x000b34, 0x000b34, 0x000b3a, 0x000b3b, 0x000b45,
                        0x000b46, 0x000b49, 0x000b4a, 0x000b4e, 0x000b54, 0x000b58, 0x000b5b, 0x000b5e, 0x000b5e, 0x000b64, 0x000b65, 0x000b78, 0x000b81, 0x000b84, 0x000b84, 0x000b8b, 0x000b8d,
                        0x000b91, 0x000b91, 0x000b96, 0x000b98, 0x000b9b, 0x000b9b, 0x000b9d, 0x000b9d, 0x000ba0, 0x000ba2, 0x000ba5, 0x000ba7, 0x000bab, 0x000bad, 0x000bba, 0x000bbd, 0x000bc3,
                        0x000bc5, 0x000bc9, 0x000bc9, 0x000bce, 0x000bcf, 0x000bd1, 0x000bd6, 0x000bd8, 0x000be5, 0x000bfb, 0x000bff, 0x000c0d, 0x000c0d, 0x000c11, 0x000c11, 0x000c29, 0x000c29,
                        0x000c3a, 0x000c3b, 0x000c45, 0x000c45, 0x000c49, 0x000c49, 0x000c4e, 0x000c54, 0x000c57, 0x000c57, 0x000c5b, 0x000c5c, 0x000c5e, 0x000c5f, 0x000c64, 0x000c65, 0x000c70,
                        0x000c76, 0x000c8d, 0x000c8d, 0x000c91, 0x000c91, 0x000ca9, 0x000ca9, 0x000cb4, 0x000cb4, 0x000cba, 0x000cbb, 0x000cc5, 0x000cc5, 0x000cc9, 0x000cc9, 0x000cce, 0x000cd4,
                        0x000cd7, 0x000cdc, 0x000cdf, 0x000cdf, 0x000ce4, 0x000ce5, 0x000cf0, 0x000cf0, 0x000cf4, 0x000cff, 0x000d0d, 0x000d0d, 0x000d11, 0x000d11, 0x000d45, 0x000d45, 0x000d49,
                        0x000d49, 0x000d50, 0x000d53, 0x000d64, 0x000d65, 0x000d80, 0x000d80, 0x000d84, 0x000d84, 0x000d97, 0x000d99, 0x000db2, 0x000db2, 0x000dbc, 0x000dbc, 0x000dbe, 0x000dbf,
                        0x000dc7, 0x000dc9, 0x000dcb, 0x000dce, 0x000dd5, 0x000dd5, 0x000dd7, 0x000dd7, 0x000de0, 0x000de5, 0x000df0, 0x000df1, 0x000df5, 0x000e00, 0x000e3b, 0x000e3e, 0x000e5c,
                        0x000e80, 0x000e83, 0x000e83, 0x000e85, 0x000e85, 0x000e8b, 0x000e8b, 0x000ea4, 0x000ea4, 0x000ea6, 0x000ea6, 0x000ebe, 0x000ebf, 0x000ec5, 0x000ec5, 0x000ec7, 0x000ec7,
                        0x000ecf, 0x000ecf, 0x000eda, 0x000edb, 0x000ee0, 0x000eff, 0x000f48, 0x000f48, 0x000f6d, 0x000f70, 0x000f98, 0x000f98, 0x000fbd, 0x000fbd, 0x000fcd, 0x000fcd, 0x000fdb,
                        0x000fff, 0x0010c6, 0x0010c6, 0x0010c8, 0x0010cc, 0x0010ce, 0x0010cf, 0x001249, 0x001249, 0x00124e, 0x00124f, 0x001257, 0x001257, 0x001259, 0x001259, 0x00125e, 0x00125f,
                        0x001289, 0x001289, 0x00128e, 0x00128f, 0x0012b1, 0x0012b1, 0x0012b6, 0x0012b7, 0x0012bf, 0x0012bf, 0x0012c1, 0x0012c1, 0x0012c6, 0x0012c7, 0x0012d7, 0x0012d7, 0x001311,
                        0x001311, 0x001316, 0x001317, 0x00135b, 0x00135c, 0x00137d, 0x00137f, 0x00139a, 0x00139f, 0x0013f6, 0x0013f7, 0x0013fe, 0x0013ff, 0x00169d, 0x00169f, 0x0016f9, 0x0016ff,
                        0x001716, 0x00171e, 0x001737, 0x00173f, 0x001754, 0x00175f, 0x00176d, 0x00176d, 0x001771, 0x001771, 0x001774, 0x00177f, 0x0017de, 0x0017df, 0x0017ea, 0x0017ef, 0x0017fa,
                        0x0017ff, 0x00181a, 0x00181f, 0x001879, 0x00187f, 0x0018ab, 0x0018af, 0x0018f6, 0x0018ff, 0x00191f, 0x00191f, 0x00192c, 0x00192f, 0x00193c, 0x00193f, 0x001941, 0x001943,
                        0x00196e, 0x00196f, 0x001975, 0x00197f, 0x0019ac, 0x0019af, 0x0019ca, 0x0019cf, 0x0019db, 0x0019dd, 0x001a1c, 0x001a1d, 0x001a5f, 0x001a5f, 0x001a7d, 0x001a7e, 0x001a8a,
                        0x001a8f, 0x001a9a, 0x001a9f, 0x001aae, 0x001aaf, 0x001acf, 0x001aff, 0x001b4d, 0x001b4f, 0x001b7f, 0x001b7f, 0x001bf4, 0x001bfb, 0x001c38, 0x001c3a, 0x001c4a, 0x001c4c,
                        0x001c89, 0x001c8f, 0x001cbb, 0x001cbc, 0x001cc8, 0x001ccf, 0x001cfb, 0x001cff, 0x001f16, 0x001f17, 0x001f1e, 0x001f1f, 0x001f46, 0x001f47, 0x001f4e, 0x001f4f, 0x001f58,
                        0x001f58, 0x001f5a, 0x001f5a, 0x001f5c, 0x001f5c, 0x001f5e, 0x001f5e, 0x001f7e, 0x001f7f, 0x001fb5, 0x001fb5, 0x001fc5, 0x001fc5, 0x001fd4, 0x001fd5, 0x001fdc, 0x001fdc,
                        0x001ff0, 0x001ff1, 0x001ff5, 0x001ff5, 0x001fff, 0x001fff, 0x002065, 0x002065, 0x002072, 0x002073, 0x00208f, 0x00208f, 0x00209d, 0x00209f, 0x0020c1, 0x0020cf, 0x0020f1,
                        0x0020ff, 0x00218c, 0x00218f, 0x002427, 0x00243f, 0x00244b, 0x00245f, 0x002b74, 0x002b75, 0x002b96, 0x002b96, 0x002cf4, 0x002cf8, 0x002d26, 0x002d26, 0x002d28, 0x002d2c,
                        0x002d2e, 0x002d2f, 0x002d68, 0x002d6e, 0x002d71, 0x002d7e, 0x002d97, 0x002d9f, 0x002da7, 0x002da7, 0x002daf, 0x002daf, 0x002db7, 0x002db7, 0x002dbf, 0x002dbf, 0x002dc7,
                        0x002dc7, 0x002dcf, 0x002dcf, 0x002dd7, 0x002dd7, 0x002ddf, 0x002ddf, 0x002e5e, 0x002e7f, 0x002e9a, 0x002e9a, 0x002ef4, 0x002eff, 0x002fd6, 0x002fef, 0x002ffc, 0x002fff,
                        0x003040, 0x003040, 0x003097, 0x003098, 0x003100, 0x003104, 0x003130, 0x003130, 0x00318f, 0x00318f, 0x0031e4, 0x0031ef, 0x00321f, 0x00321f, 0x00a48d, 0x00a48f, 0x00a4c7,
                        0x00a4cf, 0x00a62c, 0x00a63f, 0x00a6f8, 0x00a6ff, 0x00a7cb, 0x00a7cf, 0x00a7d2, 0x00a7d2, 0x00a7d4, 0x00a7d4, 0x00a7da, 0x00a7f1, 0x00a82d, 0x00a82f, 0x00a83a, 0x00a83f,
                        0x00a878, 0x00a87f, 0x00a8c6, 0x00a8cd, 0x00a8da, 0x00a8df, 0x00a954, 0x00a95e, 0x00a97d, 0x00a97f, 0x00a9ce, 0x00a9ce, 0x00a9da, 0x00a9dd, 0x00a9ff, 0x00a9ff, 0x00aa37,
                        0x00aa3f, 0x00aa4e, 0x00aa4f, 0x00aa5a, 0x00aa5b, 0x00aac3, 0x00aada, 0x00aaf7, 0x00ab00, 0x00ab07, 0x00ab08, 0x00ab0f, 0x00ab10, 0x00ab17, 0x00ab1f, 0x00ab27, 0x00ab27,
                        0x00ab2f, 0x00ab2f, 0x00ab6c, 0x00ab6f, 0x00abee, 0x00abef, 0x00abfa, 0x00abff, 0x00d7a4, 0x00d7af, 0x00d7c7, 0x00d7ca, 0x00d7fc, 0x00d7ff, 0x00fa6e, 0x00fa6f, 0x00fada,
                        0x00faff, 0x00fb07, 0x00fb12, 0x00fb18, 0x00fb1c, 0x00fb37, 0x00fb37, 0x00fb3d, 0x00fb3d, 0x00fb3f, 0x00fb3f, 0x00fb42, 0x00fb42, 0x00fb45, 0x00fb45, 0x00fbc3, 0x00fbd2,
                        0x00fd90, 0x00fd91, 0x00fdc8, 0x00fdce, 0x00fdd0, 0x00fdef, 0x00fe1a, 0x00fe1f, 0x00fe53, 0x00fe53, 0x00fe67, 0x00fe67, 0x00fe6c, 0x00fe6f, 0x00fe75, 0x00fe75, 0x00fefd,
                        0x00fefe, 0x00ff00, 0x00ff00, 0x00ffbf, 0x00ffc1, 0x00ffc8, 0x00ffc9, 0x00ffd0, 0x00ffd1, 0x00ffd8, 0x00ffd9, 0x00ffdd, 0x00ffdf, 0x00ffe7, 0x00ffe7, 0x00ffef, 0x00fff8,
                        0x00fffe, 0x00ffff, 0x01000c, 0x01000c, 0x010027, 0x010027, 0x01003b, 0x01003b, 0x01003e, 0x01003e, 0x01004e, 0x01004f, 0x01005e, 0x01007f, 0x0100fb, 0x0100ff, 0x010103,
                        0x010106, 0x010134, 0x010136, 0x01018f, 0x01018f, 0x01019d, 0x01019f, 0x0101a1, 0x0101cf, 0x0101fe, 0x01027f, 0x01029d, 0x01029f, 0x0102d1, 0x0102df, 0x0102fc, 0x0102ff,
                        0x010324, 0x01032c, 0x01034b, 0x01034f, 0x01037b, 0x01037f, 0x01039e, 0x01039e, 0x0103c4, 0x0103c7, 0x0103d6, 0x0103ff, 0x01049e, 0x01049f, 0x0104aa, 0x0104af, 0x0104d4,
                        0x0104d7, 0x0104fc, 0x0104ff, 0x010528, 0x01052f, 0x010564, 0x01056e, 0x01057b, 0x01057b, 0x01058b, 0x01058b, 0x010593, 0x010593, 0x010596, 0x010596, 0x0105a2, 0x0105a2,
                        0x0105b2, 0x0105b2, 0x0105ba, 0x0105ba, 0x0105bd, 0x0105ff, 0x010737, 0x01073f, 0x010756, 0x01075f, 0x010768, 0x01077f, 0x010786, 0x010786, 0x0107b1, 0x0107b1, 0x0107bb,
                        0x0107ff, 0x010806, 0x010807, 0x010809, 0x010809, 0x010836, 0x010836, 0x010839, 0x01083b, 0x01083d, 0x01083e, 0x010856, 0x010856, 0x01089f, 0x0108a6, 0x0108b0, 0x0108df,
                        0x0108f3, 0x0108f3, 0x0108f6, 0x0108fa, 0x01091c, 0x01091e, 0x01093a, 0x01093e, 0x010940, 0x01097f, 0x0109b8, 0x0109bb, 0x0109d0, 0x0109d1, 0x010a04, 0x010a04, 0x010a07,
                        0x010a0b, 0x010a14, 0x010a14, 0x010a18, 0x010a18, 0x010a36, 0x010a37, 0x010a3b, 0x010a3e, 0x010a49, 0x010a4f, 0x010a59, 0x010a5f, 0x010aa0, 0x010abf, 0x010ae7, 0x010aea,
                        0x010af7, 0x010aff, 0x010b36, 0x010b38, 0x010b56, 0x010b57, 0x010b73, 0x010b77, 0x010b92, 0x010b98, 0x010b9d, 0x010ba8, 0x010bb0, 0x010bff, 0x010c49, 0x010c7f, 0x010cb3,
                        0x010cbf, 0x010cf3, 0x010cf9, 0x010d28, 0x010d2f, 0x010d3a, 0x010e5f, 0x010e7f, 0x010e7f, 0x010eaa, 0x010eaa, 0x010eae, 0x010eaf, 0x010eb2, 0x010efc, 0x010f28, 0x010f2f,
                        0x010f5a, 0x010f6f, 0x010f8a, 0x010faf, 0x010fcc, 0x010fdf, 0x010ff7, 0x010fff, 0x01104e, 0x011051, 0x011076, 0x01107e, 0x0110c3, 0x0110cc, 0x0110ce, 0x0110cf, 0x0110e9,
                        0x0110ef, 0x0110fa, 0x0110ff, 0x011135, 0x011135, 0x011148, 0x01114f, 0x011177, 0x01117f, 0x0111e0, 0x0111e0, 0x0111f5, 0x0111ff, 0x011212, 0x011212, 0x011242, 0x01127f,
                        0x011287, 0x011287, 0x011289, 0x011289, 0x01128e, 0x01128e, 0x01129e, 0x01129e, 0x0112aa, 0x0112af, 0x0112eb, 0x0112ef, 0x0112fa, 0x0112ff, 0x011304, 0x011304, 0x01130d,
                        0x01130e, 0x011311, 0x011312, 0x011329, 0x011329, 0x011331, 0x011331, 0x011334, 0x011334, 0x01133a, 0x01133a, 0x011345, 0x011346, 0x011349, 0x01134a, 0x01134e, 0x01134f,
                        0x011351, 0x011356, 0x011358, 0x01135c, 0x011364, 0x011365, 0x01136d, 0x01136f, 0x011375, 0x0113ff, 0x01145c, 0x01145c, 0x011462, 0x01147f, 0x0114c8, 0x0114cf, 0x0114da,
                        0x01157f, 0x0115b6, 0x0115b7, 0x0115de, 0x0115ff, 0x011645, 0x01164f, 0x01165a, 0x01165f, 0x01166d, 0x01167f, 0x0116ba, 0x0116bf, 0x0116ca, 0x0116ff, 0x01171b, 0x01171c,
                        0x01172c, 0x01172f, 0x011747, 0x0117ff, 0x01183c, 0x01189f, 0x0118f3, 0x0118fe, 0x011907, 0x011908, 0x01190a, 0x01190b, 0x011914, 0x011914, 0x011917, 0x011917, 0x011936,
                        0x011936, 0x011939, 0x01193a, 0x011947, 0x01194f, 0x01195a, 0x01199f, 0x0119a8, 0x0119a9, 0x0119d8, 0x0119d9, 0x0119e5, 0x0119ff, 0x011a48, 0x011a4f, 0x011aa3, 0x011aaf,
                        0x011af9, 0x011aff, 0x011b0a, 0x011bff, 0x011c09, 0x011c09, 0x011c37, 0x011c37, 0x011c46, 0x011c4f, 0x011c6d, 0x011c6f, 0x011c90, 0x011c91, 0x011ca8, 0x011ca8, 0x011cb7,
                        0x011cff, 0x011d07, 0x011d07, 0x011d0a, 0x011d0a, 0x011d37, 0x011d39, 0x011d3b, 0x011d3b, 0x011d3e, 0x011d3e, 0x011d48, 0x011d4f, 0x011d5a, 0x011d5f, 0x011d66, 0x011d66,
                        0x011d69, 0x011d69, 0x011d8f, 0x011d8f, 0x011d92, 0x011d92, 0x011d99, 0x011d9f, 0x011daa, 0x011edf, 0x011ef9, 0x011eff, 0x011f11, 0x011f11, 0x011f3b, 0x011f3d, 0x011f5a,
                        0x011faf, 0x011fb1, 0x011fbf, 0x011ff2, 0x011ffe, 0x01239a, 0x0123ff, 0x01246f, 0x01246f, 0x012475, 0x01247f, 0x012544, 0x012f8f, 0x012ff3, 0x012fff, 0x013456, 0x0143ff,
                        0x014647, 0x0167ff, 0x016a39, 0x016a3f, 0x016a5f, 0x016a5f, 0x016a6a, 0x016a6d, 0x016abf, 0x016abf, 0x016aca, 0x016acf, 0x016aee, 0x016aef, 0x016af6, 0x016aff, 0x016b46,
                        0x016b4f, 0x016b5a, 0x016b5a, 0x016b62, 0x016b62, 0x016b78, 0x016b7c, 0x016b90, 0x016e3f, 0x016e9b, 0x016eff, 0x016f4b, 0x016f4e, 0x016f88, 0x016f8e, 0x016fa0, 0x016fdf,
                        0x016fe5, 0x016fef, 0x016ff2, 0x016fff, 0x0187f8, 0x0187ff, 0x018cd6, 0x018cff, 0x018d09, 0x01afef, 0x01aff4, 0x01aff4, 0x01affc, 0x01affc, 0x01afff, 0x01afff, 0x01b123,
                        0x01b131, 0x01b133, 0x01b14f, 0x01b153, 0x01b154, 0x01b156, 0x01b163, 0x01b168, 0x01b16f, 0x01b2fc, 0x01bbff, 0x01bc6b, 0x01bc6f, 0x01bc7d, 0x01bc7f, 0x01bc89, 0x01bc8f,
                        0x01bc9a, 0x01bc9b, 0x01bca4, 0x01ceff, 0x01cf2e, 0x01cf2f, 0x01cf47, 0x01cf4f, 0x01cfc4, 0x01cfff, 0x01d0f6, 0x01d0ff, 0x01d127, 0x01d128, 0x01d1eb, 0x01d1ff, 0x01d246,
                        0x01d2bf, 0x01d2d4, 0x01d2df, 0x01d2f4, 0x01d2ff, 0x01d357, 0x01d35f, 0x01d379, 0x01d3ff, 0x01d455, 0x01d455, 0x01d49d, 0x01d49d, 0x01d4a0, 0x01d4a1, 0x01d4a3, 0x01d4a4,
                        0x01d4a7, 0x01d4a8, 0x01d4ad, 0x01d4ad, 0x01d4ba, 0x01d4ba, 0x01d4bc, 0x01d4bc, 0x01d4c4, 0x01d4c4, 0x01d506, 0x01d506, 0x01d50b, 0x01d50c, 0x01d515, 0x01d515, 0x01d51d,
                        0x01d51d, 0x01d53a, 0x01d53a, 0x01d53f, 0x01d53f, 0x01d545, 0x01d545, 0x01d547, 0x01d549, 0x01d551, 0x01d551, 0x01d6a6, 0x01d6a7, 0x01d7cc, 0x01d7cd, 0x01da8c, 0x01da9a,
                        0x01daa0, 0x01daa0, 0x01dab0, 0x01deff, 0x01df1f, 0x01df24, 0x01df2b, 0x01dfff, 0x01e007, 0x01e007, 0x01e019, 0x01e01a, 0x01e022, 0x01e022, 0x01e025, 0x01e025, 0x01e02b,
                        0x01e02f, 0x01e06e, 0x01e08e, 0x01e090, 0x01e0ff, 0x01e12d, 0x01e12f, 0x01e13e, 0x01e13f, 0x01e14a, 0x01e14d, 0x01e150, 0x01e28f, 0x01e2af, 0x01e2bf, 0x01e2fa, 0x01e2fe,
                        0x01e300, 0x01e4cf, 0x01e4fa, 0x01e7df, 0x01e7e7, 0x01e7e7, 0x01e7ec, 0x01e7ec, 0x01e7ef, 0x01e7ef, 0x01e7ff, 0x01e7ff, 0x01e8c5, 0x01e8c6, 0x01e8d7, 0x01e8ff, 0x01e94c,
                        0x01e94f, 0x01e95a, 0x01e95d, 0x01e960, 0x01ec70, 0x01ecb5, 0x01ed00, 0x01ed3e, 0x01edff, 0x01ee04, 0x01ee04, 0x01ee20, 0x01ee20, 0x01ee23, 0x01ee23, 0x01ee25, 0x01ee26,
                        0x01ee28, 0x01ee28, 0x01ee33, 0x01ee33, 0x01ee38, 0x01ee38, 0x01ee3a, 0x01ee3a, 0x01ee3c, 0x01ee41, 0x01ee43, 0x01ee46, 0x01ee48, 0x01ee48, 0x01ee4a, 0x01ee4a, 0x01ee4c,
                        0x01ee4c, 0x01ee50, 0x01ee50, 0x01ee53, 0x01ee53, 0x01ee55, 0x01ee56, 0x01ee58, 0x01ee58, 0x01ee5a, 0x01ee5a, 0x01ee5c, 0x01ee5c, 0x01ee5e, 0x01ee5e, 0x01ee60, 0x01ee60,
                        0x01ee63, 0x01ee63, 0x01ee65, 0x01ee66, 0x01ee6b, 0x01ee6b, 0x01ee73, 0x01ee73, 0x01ee78, 0x01ee78, 0x01ee7d, 0x01ee7d, 0x01ee7f, 0x01ee7f, 0x01ee8a, 0x01ee8a, 0x01ee9c,
                        0x01eea0, 0x01eea4, 0x01eea4, 0x01eeaa, 0x01eeaa, 0x01eebc, 0x01eeef, 0x01eef2, 0x01efff, 0x01f02c, 0x01f02f, 0x01f094, 0x01f09f, 0x01f0af, 0x01f0b0, 0x01f0c0, 0x01f0c0,
                        0x01f0d0, 0x01f0d0, 0x01f0f6, 0x01f0ff, 0x01f1ae, 0x01f1e5, 0x01f203, 0x01f20f, 0x01f23c, 0x01f23f, 0x01f249, 0x01f24f, 0x01f252, 0x01f25f, 0x01f266, 0x01f2ff, 0x01f6d8,
                        0x01f6db, 0x01f6ed, 0x01f6ef, 0x01f6fd, 0x01f6ff, 0x01f777, 0x01f77a, 0x01f7da, 0x01f7df, 0x01f7ec, 0x01f7ef, 0x01f7f1, 0x01f7ff, 0x01f80c, 0x01f80f, 0x01f848, 0x01f84f,
                        0x01f85a, 0x01f85f, 0x01f888, 0x01f88f, 0x01f8ae, 0x01f8af, 0x01f8b2, 0x01f8ff, 0x01fa54, 0x01fa5f, 0x01fa6e, 0x01fa6f, 0x01fa7d, 0x01fa7f, 0x01fa89, 0x01fa8f, 0x01fabe,
                        0x01fabe, 0x01fac6, 0x01facd, 0x01fadc, 0x01fadf, 0x01fae9, 0x01faef, 0x01faf9, 0x01faff, 0x01fb93, 0x01fb93, 0x01fbcb, 0x01fbef, 0x01fbfa, 0x01ffff, 0x02a6e0, 0x02a6ff,
                        0x02b73a, 0x02b73f, 0x02b81e, 0x02b81f, 0x02cea2, 0x02ceaf, 0x02ebe1, 0x02f7ff, 0x02fa1e, 0x02ffff, 0x03134b, 0x03134f, 0x0323b0, 0x0e0000, 0x0e0002, 0x0e001f, 0x0e0080,
                        0x0e00ff, 0x0e01f0, 0x0effff, 0x0ffffe, 0x0fffff, 0x10fffe, 0x10ffff));
    }

    private static void populateGC_CO() {
        SET_ENCODINGS.put("gc=Co", CodePointSet.createNoDedup(0x00e000, 0x00f8ff, 0x0f0000, 0x0ffffd, 0x100000, 0x10fffd));
    }

    private static void populateGC_CS() {
        SET_ENCODINGS.put("gc=Cs", CodePointSet.createNoDedup(0x00d800, 0x00dfff));
    }

    private static void populateGC_L() {
        SET_ENCODINGS.put("gc=L", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000b5, 0x0000b5, 0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6,
                        0x0000f8, 0x0002c1, 0x0002c6, 0x0002d1, 0x0002e0, 0x0002e4, 0x0002ec, 0x0002ec, 0x0002ee, 0x0002ee, 0x000370, 0x000374, 0x000376, 0x000377, 0x00037a, 0x00037d, 0x00037f,
                        0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003f5, 0x0003f7, 0x000481, 0x00048a, 0x00052f, 0x000531, 0x000556,
                        0x000559, 0x000559, 0x000560, 0x000588, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f2, 0x000620, 0x00064a, 0x00066e, 0x00066f, 0x000671, 0x0006d3, 0x0006d5, 0x0006d5, 0x0006e5,
                        0x0006e6, 0x0006ee, 0x0006ef, 0x0006fa, 0x0006fc, 0x0006ff, 0x0006ff, 0x000710, 0x000710, 0x000712, 0x00072f, 0x00074d, 0x0007a5, 0x0007b1, 0x0007b1, 0x0007ca, 0x0007ea,
                        0x0007f4, 0x0007f5, 0x0007fa, 0x0007fa, 0x000800, 0x000815, 0x00081a, 0x00081a, 0x000824, 0x000824, 0x000828, 0x000828, 0x000840, 0x000858, 0x000860, 0x00086a, 0x000870,
                        0x000887, 0x000889, 0x00088e, 0x0008a0, 0x0008c9, 0x000904, 0x000939, 0x00093d, 0x00093d, 0x000950, 0x000950, 0x000958, 0x000961, 0x000971, 0x000980, 0x000985, 0x00098c,
                        0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6, 0x0009b9, 0x0009bd, 0x0009bd, 0x0009ce, 0x0009ce, 0x0009dc, 0x0009dd, 0x0009df,
                        0x0009e1, 0x0009f0, 0x0009f1, 0x0009fc, 0x0009fc, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36,
                        0x000a38, 0x000a39, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a72, 0x000a74, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8, 0x000aaa, 0x000ab0, 0x000ab2,
                        0x000ab3, 0x000ab5, 0x000ab9, 0x000abd, 0x000abd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae1, 0x000af9, 0x000af9, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28,
                        0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35, 0x000b39, 0x000b3d, 0x000b3d, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b61, 0x000b71, 0x000b71, 0x000b83, 0x000b83, 0x000b85,
                        0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa, 0x000bae, 0x000bb9,
                        0x000bd0, 0x000bd0, 0x000c05, 0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3d, 0x000c3d, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d, 0x000c60,
                        0x000c61, 0x000c80, 0x000c80, 0x000c85, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbd, 0x000cbd, 0x000cdd, 0x000cde,
                        0x000ce0, 0x000ce1, 0x000cf1, 0x000cf2, 0x000d04, 0x000d0c, 0x000d0e, 0x000d10, 0x000d12, 0x000d3a, 0x000d3d, 0x000d3d, 0x000d4e, 0x000d4e, 0x000d54, 0x000d56, 0x000d5f,
                        0x000d61, 0x000d7a, 0x000d7f, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000e01, 0x000e30, 0x000e32, 0x000e33,
                        0x000e40, 0x000e46, 0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000eb0, 0x000eb2, 0x000eb3, 0x000ebd,
                        0x000ebd, 0x000ec0, 0x000ec4, 0x000ec6, 0x000ec6, 0x000edc, 0x000edf, 0x000f00, 0x000f00, 0x000f40, 0x000f47, 0x000f49, 0x000f6c, 0x000f88, 0x000f8c, 0x001000, 0x00102a,
                        0x00103f, 0x00103f, 0x001050, 0x001055, 0x00105a, 0x00105d, 0x001061, 0x001061, 0x001065, 0x001066, 0x00106e, 0x001070, 0x001075, 0x001081, 0x00108e, 0x00108e, 0x0010a0,
                        0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x0010fa, 0x0010fc, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d,
                        0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290, 0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8,
                        0x001310, 0x001312, 0x001315, 0x001318, 0x00135a, 0x001380, 0x00138f, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001401, 0x00166c, 0x00166f, 0x00167f, 0x001681, 0x00169a,
                        0x0016a0, 0x0016ea, 0x0016f1, 0x0016f8, 0x001700, 0x001711, 0x00171f, 0x001731, 0x001740, 0x001751, 0x001760, 0x00176c, 0x00176e, 0x001770, 0x001780, 0x0017b3, 0x0017d7,
                        0x0017d7, 0x0017dc, 0x0017dc, 0x001820, 0x001878, 0x001880, 0x001884, 0x001887, 0x0018a8, 0x0018aa, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900, 0x00191e, 0x001950, 0x00196d,
                        0x001970, 0x001974, 0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x001a00, 0x001a16, 0x001a20, 0x001a54, 0x001aa7, 0x001aa7, 0x001b05, 0x001b33, 0x001b45, 0x001b4c, 0x001b83,
                        0x001ba0, 0x001bae, 0x001baf, 0x001bba, 0x001be5, 0x001c00, 0x001c23, 0x001c4d, 0x001c4f, 0x001c5a, 0x001c7d, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf,
                        0x001ce9, 0x001cec, 0x001cee, 0x001cf3, 0x001cf5, 0x001cf6, 0x001cfa, 0x001cfa, 0x001d00, 0x001dbf, 0x001e00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48,
                        0x001f4d, 0x001f50, 0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc, 0x001fbe, 0x001fbe,
                        0x001fc2, 0x001fc4, 0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x002071, 0x002071, 0x00207f,
                        0x00207f, 0x002090, 0x00209c, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002119, 0x00211d, 0x002124, 0x002124, 0x002126, 0x002126,
                        0x002128, 0x002128, 0x00212a, 0x00212d, 0x00212f, 0x002139, 0x00213c, 0x00213f, 0x002145, 0x002149, 0x00214e, 0x00214e, 0x002183, 0x002184, 0x002c00, 0x002ce4, 0x002ceb,
                        0x002cee, 0x002cf2, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x002d30, 0x002d67, 0x002d6f, 0x002d6f, 0x002d80, 0x002d96, 0x002da0, 0x002da6,
                        0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe, 0x002dc0, 0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x002e2f, 0x002e2f, 0x003005,
                        0x003006, 0x003031, 0x003035, 0x00303b, 0x00303c, 0x003041, 0x003096, 0x00309d, 0x00309f, 0x0030a1, 0x0030fa, 0x0030fc, 0x0030ff, 0x003105, 0x00312f, 0x003131, 0x00318e,
                        0x0031a0, 0x0031bf, 0x0031f0, 0x0031ff, 0x003400, 0x004dbf, 0x004e00, 0x00a48c, 0x00a4d0, 0x00a4fd, 0x00a500, 0x00a60c, 0x00a610, 0x00a61f, 0x00a62a, 0x00a62b, 0x00a640,
                        0x00a66e, 0x00a67f, 0x00a69d, 0x00a6a0, 0x00a6e5, 0x00a717, 0x00a71f, 0x00a722, 0x00a788, 0x00a78b, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9,
                        0x00a7f2, 0x00a801, 0x00a803, 0x00a805, 0x00a807, 0x00a80a, 0x00a80c, 0x00a822, 0x00a840, 0x00a873, 0x00a882, 0x00a8b3, 0x00a8f2, 0x00a8f7, 0x00a8fb, 0x00a8fb, 0x00a8fd,
                        0x00a8fe, 0x00a90a, 0x00a925, 0x00a930, 0x00a946, 0x00a960, 0x00a97c, 0x00a984, 0x00a9b2, 0x00a9cf, 0x00a9cf, 0x00a9e0, 0x00a9e4, 0x00a9e6, 0x00a9ef, 0x00a9fa, 0x00a9fe,
                        0x00aa00, 0x00aa28, 0x00aa40, 0x00aa42, 0x00aa44, 0x00aa4b, 0x00aa60, 0x00aa76, 0x00aa7a, 0x00aa7a, 0x00aa7e, 0x00aaaf, 0x00aab1, 0x00aab1, 0x00aab5, 0x00aab6, 0x00aab9,
                        0x00aabd, 0x00aac0, 0x00aac0, 0x00aac2, 0x00aac2, 0x00aadb, 0x00aadd, 0x00aae0, 0x00aaea, 0x00aaf2, 0x00aaf4, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16,
                        0x00ab20, 0x00ab26, 0x00ab28, 0x00ab2e, 0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab69, 0x00ab70, 0x00abe2, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb, 0x00f900,
                        0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00fb1d, 0x00fb1d, 0x00fb1f, 0x00fb28, 0x00fb2a, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e,
                        0x00fb40, 0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fbb1, 0x00fbd3, 0x00fd3d, 0x00fd50, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdf0, 0x00fdfb, 0x00fe70, 0x00fe74, 0x00fe76,
                        0x00fefc, 0x00ff21, 0x00ff3a, 0x00ff41, 0x00ff5a, 0x00ff66, 0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x010000, 0x01000b,
                        0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d, 0x01003f, 0x01004d, 0x010050, 0x01005d, 0x010080, 0x0100fa, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x010300,
                        0x01031f, 0x01032d, 0x010340, 0x010342, 0x010349, 0x010350, 0x010375, 0x010380, 0x01039d, 0x0103a0, 0x0103c3, 0x0103c8, 0x0103cf, 0x010400, 0x01049d, 0x0104b0, 0x0104d3,
                        0x0104d8, 0x0104fb, 0x010500, 0x010527, 0x010530, 0x010563, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3,
                        0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba,
                        0x010800, 0x010805, 0x010808, 0x010808, 0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x010855, 0x010860, 0x010876, 0x010880, 0x01089e, 0x0108e0,
                        0x0108f2, 0x0108f4, 0x0108f5, 0x010900, 0x010915, 0x010920, 0x010939, 0x010980, 0x0109b7, 0x0109be, 0x0109bf, 0x010a00, 0x010a00, 0x010a10, 0x010a13, 0x010a15, 0x010a17,
                        0x010a19, 0x010a35, 0x010a60, 0x010a7c, 0x010a80, 0x010a9c, 0x010ac0, 0x010ac7, 0x010ac9, 0x010ae4, 0x010b00, 0x010b35, 0x010b40, 0x010b55, 0x010b60, 0x010b72, 0x010b80,
                        0x010b91, 0x010c00, 0x010c48, 0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x010d00, 0x010d23, 0x010e80, 0x010ea9, 0x010eb0, 0x010eb1, 0x010f00, 0x010f1c, 0x010f27, 0x010f27,
                        0x010f30, 0x010f45, 0x010f70, 0x010f81, 0x010fb0, 0x010fc4, 0x010fe0, 0x010ff6, 0x011003, 0x011037, 0x011071, 0x011072, 0x011075, 0x011075, 0x011083, 0x0110af, 0x0110d0,
                        0x0110e8, 0x011103, 0x011126, 0x011144, 0x011144, 0x011147, 0x011147, 0x011150, 0x011172, 0x011176, 0x011176, 0x011183, 0x0111b2, 0x0111c1, 0x0111c4, 0x0111da, 0x0111da,
                        0x0111dc, 0x0111dc, 0x011200, 0x011211, 0x011213, 0x01122b, 0x01123f, 0x011240, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f,
                        0x0112a8, 0x0112b0, 0x0112de, 0x011305, 0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133d, 0x01133d,
                        0x011350, 0x011350, 0x01135d, 0x011361, 0x011400, 0x011434, 0x011447, 0x01144a, 0x01145f, 0x011461, 0x011480, 0x0114af, 0x0114c4, 0x0114c5, 0x0114c7, 0x0114c7, 0x011580,
                        0x0115ae, 0x0115d8, 0x0115db, 0x011600, 0x01162f, 0x011644, 0x011644, 0x011680, 0x0116aa, 0x0116b8, 0x0116b8, 0x011700, 0x01171a, 0x011740, 0x011746, 0x011800, 0x01182b,
                        0x0118a0, 0x0118df, 0x0118ff, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x01192f, 0x01193f, 0x01193f, 0x011941, 0x011941, 0x0119a0,
                        0x0119a7, 0x0119aa, 0x0119d0, 0x0119e1, 0x0119e1, 0x0119e3, 0x0119e3, 0x011a00, 0x011a00, 0x011a0b, 0x011a32, 0x011a3a, 0x011a3a, 0x011a50, 0x011a50, 0x011a5c, 0x011a89,
                        0x011a9d, 0x011a9d, 0x011ab0, 0x011af8, 0x011c00, 0x011c08, 0x011c0a, 0x011c2e, 0x011c40, 0x011c40, 0x011c72, 0x011c8f, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b,
                        0x011d30, 0x011d46, 0x011d46, 0x011d60, 0x011d65, 0x011d67, 0x011d68, 0x011d6a, 0x011d89, 0x011d98, 0x011d98, 0x011ee0, 0x011ef2, 0x011f02, 0x011f02, 0x011f04, 0x011f10,
                        0x011f12, 0x011f33, 0x011fb0, 0x011fb0, 0x012000, 0x012399, 0x012480, 0x012543, 0x012f90, 0x012ff0, 0x013000, 0x01342f, 0x013441, 0x013446, 0x014400, 0x014646, 0x016800,
                        0x016a38, 0x016a40, 0x016a5e, 0x016a70, 0x016abe, 0x016ad0, 0x016aed, 0x016b00, 0x016b2f, 0x016b40, 0x016b43, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f, 0x016e40, 0x016e7f,
                        0x016f00, 0x016f4a, 0x016f50, 0x016f50, 0x016f93, 0x016f9f, 0x016fe0, 0x016fe1, 0x016fe3, 0x016fe3, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01aff0,
                        0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01b000, 0x01b122, 0x01b132, 0x01b132, 0x01b150, 0x01b152, 0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb,
                        0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88, 0x01bc90, 0x01bc99, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5,
                        0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c,
                        0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d6c0, 0x01d6c2, 0x01d6da, 0x01d6dc,
                        0x01d6fa, 0x01d6fc, 0x01d714, 0x01d716, 0x01d734, 0x01d736, 0x01d74e, 0x01d750, 0x01d76e, 0x01d770, 0x01d788, 0x01d78a, 0x01d7a8, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7cb,
                        0x01df00, 0x01df1e, 0x01df25, 0x01df2a, 0x01e030, 0x01e06d, 0x01e100, 0x01e12c, 0x01e137, 0x01e13d, 0x01e14e, 0x01e14e, 0x01e290, 0x01e2ad, 0x01e2c0, 0x01e2eb, 0x01e4d0,
                        0x01e4eb, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe, 0x01e800, 0x01e8c4, 0x01e900, 0x01e943, 0x01e94b, 0x01e94b, 0x01ee00, 0x01ee03,
                        0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42,
                        0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59,
                        0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79,
                        0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x020000, 0x02a6df, 0x02a700, 0x02b739,
                        0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af));
    }

    private static void populateGC_LC() {
        SET_ENCODINGS.put("gc=LC",
                        CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000b5, 0x0000b5, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6, 0x0000f8, 0x0001ba, 0x0001bc, 0x0001bf, 0x0001c4,
                                        0x000293, 0x000295, 0x0002af, 0x000370, 0x000373, 0x000376, 0x000377, 0x00037b, 0x00037d, 0x00037f, 0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c,
                                        0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003f5, 0x0003f7, 0x000481, 0x00048a, 0x00052f, 0x000531, 0x000556, 0x000560, 0x000588, 0x0010a0, 0x0010c5, 0x0010c7,
                                        0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x0010fa, 0x0010fd, 0x0010ff, 0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x001c80, 0x001c88, 0x001c90, 0x001cba, 0x001cbd,
                                        0x001cbf, 0x001d00, 0x001d2b, 0x001d6b, 0x001d77, 0x001d79, 0x001d9a, 0x001e00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50,
                                        0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fbc, 0x001fbe, 0x001fbe, 0x001fc2,
                                        0x001fc4, 0x001fc6, 0x001fcc, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fe0, 0x001fec, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffc, 0x002102, 0x002102, 0x002107,
                                        0x002107, 0x00210a, 0x002113, 0x002115, 0x002115, 0x002119, 0x00211d, 0x002124, 0x002124, 0x002126, 0x002126, 0x002128, 0x002128, 0x00212a, 0x00212d, 0x00212f,
                                        0x002134, 0x002139, 0x002139, 0x00213c, 0x00213f, 0x002145, 0x002149, 0x00214e, 0x00214e, 0x002183, 0x002184, 0x002c00, 0x002c7b, 0x002c7e, 0x002ce4, 0x002ceb,
                                        0x002cee, 0x002cf2, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x00a640, 0x00a66d, 0x00a680, 0x00a69b, 0x00a722, 0x00a76f, 0x00a771,
                                        0x00a787, 0x00a78b, 0x00a78e, 0x00a790, 0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9, 0x00a7f5, 0x00a7f6, 0x00a7fa, 0x00a7fa, 0x00ab30,
                                        0x00ab5a, 0x00ab60, 0x00ab68, 0x00ab70, 0x00abbf, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00ff21, 0x00ff3a, 0x00ff41, 0x00ff5a, 0x010400, 0x01044f, 0x0104b0,
                                        0x0104d3, 0x0104d8, 0x0104fb, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3,
                                        0x0105b9, 0x0105bb, 0x0105bc, 0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x0118a0, 0x0118df, 0x016e40, 0x016e7f, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e,
                                        0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507,
                                        0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552,
                                        0x01d6a5, 0x01d6a8, 0x01d6c0, 0x01d6c2, 0x01d6da, 0x01d6dc, 0x01d6fa, 0x01d6fc, 0x01d714, 0x01d716, 0x01d734, 0x01d736, 0x01d74e, 0x01d750, 0x01d76e, 0x01d770,
                                        0x01d788, 0x01d78a, 0x01d7a8, 0x01d7aa, 0x01d7c2, 0x01d7c4, 0x01d7cb, 0x01df00, 0x01df09, 0x01df0b, 0x01df1e, 0x01df25, 0x01df2a, 0x01e900, 0x01e943));
    }

    private static void populateGC_LL() {
        SET_ENCODINGS.put("gc=Ll", CodePointSet.createNoDedup(0x000061, 0x00007a, 0x0000b5, 0x0000b5, 0x0000df, 0x0000f6, 0x0000f8, 0x0000ff, 0x000101, 0x000101, 0x000103, 0x000103, 0x000105,
                        0x000105, 0x000107, 0x000107, 0x000109, 0x000109, 0x00010b, 0x00010b, 0x00010d, 0x00010d, 0x00010f, 0x00010f, 0x000111, 0x000111, 0x000113, 0x000113, 0x000115, 0x000115,
                        0x000117, 0x000117, 0x000119, 0x000119, 0x00011b, 0x00011b, 0x00011d, 0x00011d, 0x00011f, 0x00011f, 0x000121, 0x000121, 0x000123, 0x000123, 0x000125, 0x000125, 0x000127,
                        0x000127, 0x000129, 0x000129, 0x00012b, 0x00012b, 0x00012d, 0x00012d, 0x00012f, 0x00012f, 0x000131, 0x000131, 0x000133, 0x000133, 0x000135, 0x000135, 0x000137, 0x000138,
                        0x00013a, 0x00013a, 0x00013c, 0x00013c, 0x00013e, 0x00013e, 0x000140, 0x000140, 0x000142, 0x000142, 0x000144, 0x000144, 0x000146, 0x000146, 0x000148, 0x000149, 0x00014b,
                        0x00014b, 0x00014d, 0x00014d, 0x00014f, 0x00014f, 0x000151, 0x000151, 0x000153, 0x000153, 0x000155, 0x000155, 0x000157, 0x000157, 0x000159, 0x000159, 0x00015b, 0x00015b,
                        0x00015d, 0x00015d, 0x00015f, 0x00015f, 0x000161, 0x000161, 0x000163, 0x000163, 0x000165, 0x000165, 0x000167, 0x000167, 0x000169, 0x000169, 0x00016b, 0x00016b, 0x00016d,
                        0x00016d, 0x00016f, 0x00016f, 0x000171, 0x000171, 0x000173, 0x000173, 0x000175, 0x000175, 0x000177, 0x000177, 0x00017a, 0x00017a, 0x00017c, 0x00017c, 0x00017e, 0x000180,
                        0x000183, 0x000183, 0x000185, 0x000185, 0x000188, 0x000188, 0x00018c, 0x00018d, 0x000192, 0x000192, 0x000195, 0x000195, 0x000199, 0x00019b, 0x00019e, 0x00019e, 0x0001a1,
                        0x0001a1, 0x0001a3, 0x0001a3, 0x0001a5, 0x0001a5, 0x0001a8, 0x0001a8, 0x0001aa, 0x0001ab, 0x0001ad, 0x0001ad, 0x0001b0, 0x0001b0, 0x0001b4, 0x0001b4, 0x0001b6, 0x0001b6,
                        0x0001b9, 0x0001ba, 0x0001bd, 0x0001bf, 0x0001c6, 0x0001c6, 0x0001c9, 0x0001c9, 0x0001cc, 0x0001cc, 0x0001ce, 0x0001ce, 0x0001d0, 0x0001d0, 0x0001d2, 0x0001d2, 0x0001d4,
                        0x0001d4, 0x0001d6, 0x0001d6, 0x0001d8, 0x0001d8, 0x0001da, 0x0001da, 0x0001dc, 0x0001dd, 0x0001df, 0x0001df, 0x0001e1, 0x0001e1, 0x0001e3, 0x0001e3, 0x0001e5, 0x0001e5,
                        0x0001e7, 0x0001e7, 0x0001e9, 0x0001e9, 0x0001eb, 0x0001eb, 0x0001ed, 0x0001ed, 0x0001ef, 0x0001f0, 0x0001f3, 0x0001f3, 0x0001f5, 0x0001f5, 0x0001f9, 0x0001f9, 0x0001fb,
                        0x0001fb, 0x0001fd, 0x0001fd, 0x0001ff, 0x0001ff, 0x000201, 0x000201, 0x000203, 0x000203, 0x000205, 0x000205, 0x000207, 0x000207, 0x000209, 0x000209, 0x00020b, 0x00020b,
                        0x00020d, 0x00020d, 0x00020f, 0x00020f, 0x000211, 0x000211, 0x000213, 0x000213, 0x000215, 0x000215, 0x000217, 0x000217, 0x000219, 0x000219, 0x00021b, 0x00021b, 0x00021d,
                        0x00021d, 0x00021f, 0x00021f, 0x000221, 0x000221, 0x000223, 0x000223, 0x000225, 0x000225, 0x000227, 0x000227, 0x000229, 0x000229, 0x00022b, 0x00022b, 0x00022d, 0x00022d,
                        0x00022f, 0x00022f, 0x000231, 0x000231, 0x000233, 0x000239, 0x00023c, 0x00023c, 0x00023f, 0x000240, 0x000242, 0x000242, 0x000247, 0x000247, 0x000249, 0x000249, 0x00024b,
                        0x00024b, 0x00024d, 0x00024d, 0x00024f, 0x000293, 0x000295, 0x0002af, 0x000371, 0x000371, 0x000373, 0x000373, 0x000377, 0x000377, 0x00037b, 0x00037d, 0x000390, 0x000390,
                        0x0003ac, 0x0003ce, 0x0003d0, 0x0003d1, 0x0003d5, 0x0003d7, 0x0003d9, 0x0003d9, 0x0003db, 0x0003db, 0x0003dd, 0x0003dd, 0x0003df, 0x0003df, 0x0003e1, 0x0003e1, 0x0003e3,
                        0x0003e3, 0x0003e5, 0x0003e5, 0x0003e7, 0x0003e7, 0x0003e9, 0x0003e9, 0x0003eb, 0x0003eb, 0x0003ed, 0x0003ed, 0x0003ef, 0x0003f3, 0x0003f5, 0x0003f5, 0x0003f8, 0x0003f8,
                        0x0003fb, 0x0003fc, 0x000430, 0x00045f, 0x000461, 0x000461, 0x000463, 0x000463, 0x000465, 0x000465, 0x000467, 0x000467, 0x000469, 0x000469, 0x00046b, 0x00046b, 0x00046d,
                        0x00046d, 0x00046f, 0x00046f, 0x000471, 0x000471, 0x000473, 0x000473, 0x000475, 0x000475, 0x000477, 0x000477, 0x000479, 0x000479, 0x00047b, 0x00047b, 0x00047d, 0x00047d,
                        0x00047f, 0x00047f, 0x000481, 0x000481, 0x00048b, 0x00048b, 0x00048d, 0x00048d, 0x00048f, 0x00048f, 0x000491, 0x000491, 0x000493, 0x000493, 0x000495, 0x000495, 0x000497,
                        0x000497, 0x000499, 0x000499, 0x00049b, 0x00049b, 0x00049d, 0x00049d, 0x00049f, 0x00049f, 0x0004a1, 0x0004a1, 0x0004a3, 0x0004a3, 0x0004a5, 0x0004a5, 0x0004a7, 0x0004a7,
                        0x0004a9, 0x0004a9, 0x0004ab, 0x0004ab, 0x0004ad, 0x0004ad, 0x0004af, 0x0004af, 0x0004b1, 0x0004b1, 0x0004b3, 0x0004b3, 0x0004b5, 0x0004b5, 0x0004b7, 0x0004b7, 0x0004b9,
                        0x0004b9, 0x0004bb, 0x0004bb, 0x0004bd, 0x0004bd, 0x0004bf, 0x0004bf, 0x0004c2, 0x0004c2, 0x0004c4, 0x0004c4, 0x0004c6, 0x0004c6, 0x0004c8, 0x0004c8, 0x0004ca, 0x0004ca,
                        0x0004cc, 0x0004cc, 0x0004ce, 0x0004cf, 0x0004d1, 0x0004d1, 0x0004d3, 0x0004d3, 0x0004d5, 0x0004d5, 0x0004d7, 0x0004d7, 0x0004d9, 0x0004d9, 0x0004db, 0x0004db, 0x0004dd,
                        0x0004dd, 0x0004df, 0x0004df, 0x0004e1, 0x0004e1, 0x0004e3, 0x0004e3, 0x0004e5, 0x0004e5, 0x0004e7, 0x0004e7, 0x0004e9, 0x0004e9, 0x0004eb, 0x0004eb, 0x0004ed, 0x0004ed,
                        0x0004ef, 0x0004ef, 0x0004f1, 0x0004f1, 0x0004f3, 0x0004f3, 0x0004f5, 0x0004f5, 0x0004f7, 0x0004f7, 0x0004f9, 0x0004f9, 0x0004fb, 0x0004fb, 0x0004fd, 0x0004fd, 0x0004ff,
                        0x0004ff, 0x000501, 0x000501, 0x000503, 0x000503, 0x000505, 0x000505, 0x000507, 0x000507, 0x000509, 0x000509, 0x00050b, 0x00050b, 0x00050d, 0x00050d, 0x00050f, 0x00050f,
                        0x000511, 0x000511, 0x000513, 0x000513, 0x000515, 0x000515, 0x000517, 0x000517, 0x000519, 0x000519, 0x00051b, 0x00051b, 0x00051d, 0x00051d, 0x00051f, 0x00051f, 0x000521,
                        0x000521, 0x000523, 0x000523, 0x000525, 0x000525, 0x000527, 0x000527, 0x000529, 0x000529, 0x00052b, 0x00052b, 0x00052d, 0x00052d, 0x00052f, 0x00052f, 0x000560, 0x000588,
                        0x0010d0, 0x0010fa, 0x0010fd, 0x0010ff, 0x0013f8, 0x0013fd, 0x001c80, 0x001c88, 0x001d00, 0x001d2b, 0x001d6b, 0x001d77, 0x001d79, 0x001d9a, 0x001e01, 0x001e01, 0x001e03,
                        0x001e03, 0x001e05, 0x001e05, 0x001e07, 0x001e07, 0x001e09, 0x001e09, 0x001e0b, 0x001e0b, 0x001e0d, 0x001e0d, 0x001e0f, 0x001e0f, 0x001e11, 0x001e11, 0x001e13, 0x001e13,
                        0x001e15, 0x001e15, 0x001e17, 0x001e17, 0x001e19, 0x001e19, 0x001e1b, 0x001e1b, 0x001e1d, 0x001e1d, 0x001e1f, 0x001e1f, 0x001e21, 0x001e21, 0x001e23, 0x001e23, 0x001e25,
                        0x001e25, 0x001e27, 0x001e27, 0x001e29, 0x001e29, 0x001e2b, 0x001e2b, 0x001e2d, 0x001e2d, 0x001e2f, 0x001e2f, 0x001e31, 0x001e31, 0x001e33, 0x001e33, 0x001e35, 0x001e35,
                        0x001e37, 0x001e37, 0x001e39, 0x001e39, 0x001e3b, 0x001e3b, 0x001e3d, 0x001e3d, 0x001e3f, 0x001e3f, 0x001e41, 0x001e41, 0x001e43, 0x001e43, 0x001e45, 0x001e45, 0x001e47,
                        0x001e47, 0x001e49, 0x001e49, 0x001e4b, 0x001e4b, 0x001e4d, 0x001e4d, 0x001e4f, 0x001e4f, 0x001e51, 0x001e51, 0x001e53, 0x001e53, 0x001e55, 0x001e55, 0x001e57, 0x001e57,
                        0x001e59, 0x001e59, 0x001e5b, 0x001e5b, 0x001e5d, 0x001e5d, 0x001e5f, 0x001e5f, 0x001e61, 0x001e61, 0x001e63, 0x001e63, 0x001e65, 0x001e65, 0x001e67, 0x001e67, 0x001e69,
                        0x001e69, 0x001e6b, 0x001e6b, 0x001e6d, 0x001e6d, 0x001e6f, 0x001e6f, 0x001e71, 0x001e71, 0x001e73, 0x001e73, 0x001e75, 0x001e75, 0x001e77, 0x001e77, 0x001e79, 0x001e79,
                        0x001e7b, 0x001e7b, 0x001e7d, 0x001e7d, 0x001e7f, 0x001e7f, 0x001e81, 0x001e81, 0x001e83, 0x001e83, 0x001e85, 0x001e85, 0x001e87, 0x001e87, 0x001e89, 0x001e89, 0x001e8b,
                        0x001e8b, 0x001e8d, 0x001e8d, 0x001e8f, 0x001e8f, 0x001e91, 0x001e91, 0x001e93, 0x001e93, 0x001e95, 0x001e9d, 0x001e9f, 0x001e9f, 0x001ea1, 0x001ea1, 0x001ea3, 0x001ea3,
                        0x001ea5, 0x001ea5, 0x001ea7, 0x001ea7, 0x001ea9, 0x001ea9, 0x001eab, 0x001eab, 0x001ead, 0x001ead, 0x001eaf, 0x001eaf, 0x001eb1, 0x001eb1, 0x001eb3, 0x001eb3, 0x001eb5,
                        0x001eb5, 0x001eb7, 0x001eb7, 0x001eb9, 0x001eb9, 0x001ebb, 0x001ebb, 0x001ebd, 0x001ebd, 0x001ebf, 0x001ebf, 0x001ec1, 0x001ec1, 0x001ec3, 0x001ec3, 0x001ec5, 0x001ec5,
                        0x001ec7, 0x001ec7, 0x001ec9, 0x001ec9, 0x001ecb, 0x001ecb, 0x001ecd, 0x001ecd, 0x001ecf, 0x001ecf, 0x001ed1, 0x001ed1, 0x001ed3, 0x001ed3, 0x001ed5, 0x001ed5, 0x001ed7,
                        0x001ed7, 0x001ed9, 0x001ed9, 0x001edb, 0x001edb, 0x001edd, 0x001edd, 0x001edf, 0x001edf, 0x001ee1, 0x001ee1, 0x001ee3, 0x001ee3, 0x001ee5, 0x001ee5, 0x001ee7, 0x001ee7,
                        0x001ee9, 0x001ee9, 0x001eeb, 0x001eeb, 0x001eed, 0x001eed, 0x001eef, 0x001eef, 0x001ef1, 0x001ef1, 0x001ef3, 0x001ef3, 0x001ef5, 0x001ef5, 0x001ef7, 0x001ef7, 0x001ef9,
                        0x001ef9, 0x001efb, 0x001efb, 0x001efd, 0x001efd, 0x001eff, 0x001f07, 0x001f10, 0x001f15, 0x001f20, 0x001f27, 0x001f30, 0x001f37, 0x001f40, 0x001f45, 0x001f50, 0x001f57,
                        0x001f60, 0x001f67, 0x001f70, 0x001f7d, 0x001f80, 0x001f87, 0x001f90, 0x001f97, 0x001fa0, 0x001fa7, 0x001fb0, 0x001fb4, 0x001fb6, 0x001fb7, 0x001fbe, 0x001fbe, 0x001fc2,
                        0x001fc4, 0x001fc6, 0x001fc7, 0x001fd0, 0x001fd3, 0x001fd6, 0x001fd7, 0x001fe0, 0x001fe7, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ff7, 0x00210a, 0x00210a, 0x00210e, 0x00210f,
                        0x002113, 0x002113, 0x00212f, 0x00212f, 0x002134, 0x002134, 0x002139, 0x002139, 0x00213c, 0x00213d, 0x002146, 0x002149, 0x00214e, 0x00214e, 0x002184, 0x002184, 0x002c30,
                        0x002c5f, 0x002c61, 0x002c61, 0x002c65, 0x002c66, 0x002c68, 0x002c68, 0x002c6a, 0x002c6a, 0x002c6c, 0x002c6c, 0x002c71, 0x002c71, 0x002c73, 0x002c74, 0x002c76, 0x002c7b,
                        0x002c81, 0x002c81, 0x002c83, 0x002c83, 0x002c85, 0x002c85, 0x002c87, 0x002c87, 0x002c89, 0x002c89, 0x002c8b, 0x002c8b, 0x002c8d, 0x002c8d, 0x002c8f, 0x002c8f, 0x002c91,
                        0x002c91, 0x002c93, 0x002c93, 0x002c95, 0x002c95, 0x002c97, 0x002c97, 0x002c99, 0x002c99, 0x002c9b, 0x002c9b, 0x002c9d, 0x002c9d, 0x002c9f, 0x002c9f, 0x002ca1, 0x002ca1,
                        0x002ca3, 0x002ca3, 0x002ca5, 0x002ca5, 0x002ca7, 0x002ca7, 0x002ca9, 0x002ca9, 0x002cab, 0x002cab, 0x002cad, 0x002cad, 0x002caf, 0x002caf, 0x002cb1, 0x002cb1, 0x002cb3,
                        0x002cb3, 0x002cb5, 0x002cb5, 0x002cb7, 0x002cb7, 0x002cb9, 0x002cb9, 0x002cbb, 0x002cbb, 0x002cbd, 0x002cbd, 0x002cbf, 0x002cbf, 0x002cc1, 0x002cc1, 0x002cc3, 0x002cc3,
                        0x002cc5, 0x002cc5, 0x002cc7, 0x002cc7, 0x002cc9, 0x002cc9, 0x002ccb, 0x002ccb, 0x002ccd, 0x002ccd, 0x002ccf, 0x002ccf, 0x002cd1, 0x002cd1, 0x002cd3, 0x002cd3, 0x002cd5,
                        0x002cd5, 0x002cd7, 0x002cd7, 0x002cd9, 0x002cd9, 0x002cdb, 0x002cdb, 0x002cdd, 0x002cdd, 0x002cdf, 0x002cdf, 0x002ce1, 0x002ce1, 0x002ce3, 0x002ce4, 0x002cec, 0x002cec,
                        0x002cee, 0x002cee, 0x002cf3, 0x002cf3, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d, 0x00a641, 0x00a641, 0x00a643, 0x00a643, 0x00a645, 0x00a645, 0x00a647,
                        0x00a647, 0x00a649, 0x00a649, 0x00a64b, 0x00a64b, 0x00a64d, 0x00a64d, 0x00a64f, 0x00a64f, 0x00a651, 0x00a651, 0x00a653, 0x00a653, 0x00a655, 0x00a655, 0x00a657, 0x00a657,
                        0x00a659, 0x00a659, 0x00a65b, 0x00a65b, 0x00a65d, 0x00a65d, 0x00a65f, 0x00a65f, 0x00a661, 0x00a661, 0x00a663, 0x00a663, 0x00a665, 0x00a665, 0x00a667, 0x00a667, 0x00a669,
                        0x00a669, 0x00a66b, 0x00a66b, 0x00a66d, 0x00a66d, 0x00a681, 0x00a681, 0x00a683, 0x00a683, 0x00a685, 0x00a685, 0x00a687, 0x00a687, 0x00a689, 0x00a689, 0x00a68b, 0x00a68b,
                        0x00a68d, 0x00a68d, 0x00a68f, 0x00a68f, 0x00a691, 0x00a691, 0x00a693, 0x00a693, 0x00a695, 0x00a695, 0x00a697, 0x00a697, 0x00a699, 0x00a699, 0x00a69b, 0x00a69b, 0x00a723,
                        0x00a723, 0x00a725, 0x00a725, 0x00a727, 0x00a727, 0x00a729, 0x00a729, 0x00a72b, 0x00a72b, 0x00a72d, 0x00a72d, 0x00a72f, 0x00a731, 0x00a733, 0x00a733, 0x00a735, 0x00a735,
                        0x00a737, 0x00a737, 0x00a739, 0x00a739, 0x00a73b, 0x00a73b, 0x00a73d, 0x00a73d, 0x00a73f, 0x00a73f, 0x00a741, 0x00a741, 0x00a743, 0x00a743, 0x00a745, 0x00a745, 0x00a747,
                        0x00a747, 0x00a749, 0x00a749, 0x00a74b, 0x00a74b, 0x00a74d, 0x00a74d, 0x00a74f, 0x00a74f, 0x00a751, 0x00a751, 0x00a753, 0x00a753, 0x00a755, 0x00a755, 0x00a757, 0x00a757,
                        0x00a759, 0x00a759, 0x00a75b, 0x00a75b, 0x00a75d, 0x00a75d, 0x00a75f, 0x00a75f, 0x00a761, 0x00a761, 0x00a763, 0x00a763, 0x00a765, 0x00a765, 0x00a767, 0x00a767, 0x00a769,
                        0x00a769, 0x00a76b, 0x00a76b, 0x00a76d, 0x00a76d, 0x00a76f, 0x00a76f, 0x00a771, 0x00a778, 0x00a77a, 0x00a77a, 0x00a77c, 0x00a77c, 0x00a77f, 0x00a77f, 0x00a781, 0x00a781,
                        0x00a783, 0x00a783, 0x00a785, 0x00a785, 0x00a787, 0x00a787, 0x00a78c, 0x00a78c, 0x00a78e, 0x00a78e, 0x00a791, 0x00a791, 0x00a793, 0x00a795, 0x00a797, 0x00a797, 0x00a799,
                        0x00a799, 0x00a79b, 0x00a79b, 0x00a79d, 0x00a79d, 0x00a79f, 0x00a79f, 0x00a7a1, 0x00a7a1, 0x00a7a3, 0x00a7a3, 0x00a7a5, 0x00a7a5, 0x00a7a7, 0x00a7a7, 0x00a7a9, 0x00a7a9,
                        0x00a7af, 0x00a7af, 0x00a7b5, 0x00a7b5, 0x00a7b7, 0x00a7b7, 0x00a7b9, 0x00a7b9, 0x00a7bb, 0x00a7bb, 0x00a7bd, 0x00a7bd, 0x00a7bf, 0x00a7bf, 0x00a7c1, 0x00a7c1, 0x00a7c3,
                        0x00a7c3, 0x00a7c8, 0x00a7c8, 0x00a7ca, 0x00a7ca, 0x00a7d1, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d5, 0x00a7d7, 0x00a7d7, 0x00a7d9, 0x00a7d9, 0x00a7f6, 0x00a7f6,
                        0x00a7fa, 0x00a7fa, 0x00ab30, 0x00ab5a, 0x00ab60, 0x00ab68, 0x00ab70, 0x00abbf, 0x00fb00, 0x00fb06, 0x00fb13, 0x00fb17, 0x00ff41, 0x00ff5a, 0x010428, 0x01044f, 0x0104d8,
                        0x0104fb, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3, 0x0105b9, 0x0105bb, 0x0105bc, 0x010cc0, 0x010cf2, 0x0118c0, 0x0118df, 0x016e60, 0x016e7f, 0x01d41a, 0x01d433,
                        0x01d44e, 0x01d454, 0x01d456, 0x01d467, 0x01d482, 0x01d49b, 0x01d4b6, 0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d4cf, 0x01d4ea, 0x01d503, 0x01d51e,
                        0x01d537, 0x01d552, 0x01d56b, 0x01d586, 0x01d59f, 0x01d5ba, 0x01d5d3, 0x01d5ee, 0x01d607, 0x01d622, 0x01d63b, 0x01d656, 0x01d66f, 0x01d68a, 0x01d6a5, 0x01d6c2, 0x01d6da,
                        0x01d6dc, 0x01d6e1, 0x01d6fc, 0x01d714, 0x01d716, 0x01d71b, 0x01d736, 0x01d74e, 0x01d750, 0x01d755, 0x01d770, 0x01d788, 0x01d78a, 0x01d78f, 0x01d7aa, 0x01d7c2, 0x01d7c4,
                        0x01d7c9, 0x01d7cb, 0x01d7cb, 0x01df00, 0x01df09, 0x01df0b, 0x01df1e, 0x01df25, 0x01df2a, 0x01e922, 0x01e943));
    }

    private static void populateGC_LM() {
        SET_ENCODINGS.put("gc=Lm",
                        CodePointSet.createNoDedup(0x0002b0, 0x0002c1, 0x0002c6, 0x0002d1, 0x0002e0, 0x0002e4, 0x0002ec, 0x0002ec, 0x0002ee, 0x0002ee, 0x000374, 0x000374, 0x00037a, 0x00037a, 0x000559,
                                        0x000559, 0x000640, 0x000640, 0x0006e5, 0x0006e6, 0x0007f4, 0x0007f5, 0x0007fa, 0x0007fa, 0x00081a, 0x00081a, 0x000824, 0x000824, 0x000828, 0x000828, 0x0008c9,
                                        0x0008c9, 0x000971, 0x000971, 0x000e46, 0x000e46, 0x000ec6, 0x000ec6, 0x0010fc, 0x0010fc, 0x0017d7, 0x0017d7, 0x001843, 0x001843, 0x001aa7, 0x001aa7, 0x001c78,
                                        0x001c7d, 0x001d2c, 0x001d6a, 0x001d78, 0x001d78, 0x001d9b, 0x001dbf, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090, 0x00209c, 0x002c7c, 0x002c7d, 0x002d6f,
                                        0x002d6f, 0x002e2f, 0x002e2f, 0x003005, 0x003005, 0x003031, 0x003035, 0x00303b, 0x00303b, 0x00309d, 0x00309e, 0x0030fc, 0x0030fe, 0x00a015, 0x00a015, 0x00a4f8,
                                        0x00a4fd, 0x00a60c, 0x00a60c, 0x00a67f, 0x00a67f, 0x00a69c, 0x00a69d, 0x00a717, 0x00a71f, 0x00a770, 0x00a770, 0x00a788, 0x00a788, 0x00a7f2, 0x00a7f4, 0x00a7f8,
                                        0x00a7f9, 0x00a9cf, 0x00a9cf, 0x00a9e6, 0x00a9e6, 0x00aa70, 0x00aa70, 0x00aadd, 0x00aadd, 0x00aaf3, 0x00aaf4, 0x00ab5c, 0x00ab5f, 0x00ab69, 0x00ab69, 0x00ff70,
                                        0x00ff70, 0x00ff9e, 0x00ff9f, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x016b40, 0x016b43, 0x016f93, 0x016f9f, 0x016fe0, 0x016fe1, 0x016fe3,
                                        0x016fe3, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01e030, 0x01e06d, 0x01e137, 0x01e13d, 0x01e4eb, 0x01e4eb, 0x01e94b, 0x01e94b));
    }

    private static void populateGC_LO() {
        SET_ENCODINGS.put("gc=Lo", CodePointSet.createNoDedup(0x0000aa, 0x0000aa, 0x0000ba, 0x0000ba, 0x0001bb, 0x0001bb, 0x0001c0, 0x0001c3, 0x000294, 0x000294, 0x0005d0, 0x0005ea, 0x0005ef,
                        0x0005f2, 0x000620, 0x00063f, 0x000641, 0x00064a, 0x00066e, 0x00066f, 0x000671, 0x0006d3, 0x0006d5, 0x0006d5, 0x0006ee, 0x0006ef, 0x0006fa, 0x0006fc, 0x0006ff, 0x0006ff,
                        0x000710, 0x000710, 0x000712, 0x00072f, 0x00074d, 0x0007a5, 0x0007b1, 0x0007b1, 0x0007ca, 0x0007ea, 0x000800, 0x000815, 0x000840, 0x000858, 0x000860, 0x00086a, 0x000870,
                        0x000887, 0x000889, 0x00088e, 0x0008a0, 0x0008c8, 0x000904, 0x000939, 0x00093d, 0x00093d, 0x000950, 0x000950, 0x000958, 0x000961, 0x000972, 0x000980, 0x000985, 0x00098c,
                        0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6, 0x0009b9, 0x0009bd, 0x0009bd, 0x0009ce, 0x0009ce, 0x0009dc, 0x0009dd, 0x0009df,
                        0x0009e1, 0x0009f0, 0x0009f1, 0x0009fc, 0x0009fc, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36,
                        0x000a38, 0x000a39, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a72, 0x000a74, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8, 0x000aaa, 0x000ab0, 0x000ab2,
                        0x000ab3, 0x000ab5, 0x000ab9, 0x000abd, 0x000abd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae1, 0x000af9, 0x000af9, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28,
                        0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35, 0x000b39, 0x000b3d, 0x000b3d, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b61, 0x000b71, 0x000b71, 0x000b83, 0x000b83, 0x000b85,
                        0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa, 0x000bae, 0x000bb9,
                        0x000bd0, 0x000bd0, 0x000c05, 0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3d, 0x000c3d, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d, 0x000c60,
                        0x000c61, 0x000c80, 0x000c80, 0x000c85, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbd, 0x000cbd, 0x000cdd, 0x000cde,
                        0x000ce0, 0x000ce1, 0x000cf1, 0x000cf2, 0x000d04, 0x000d0c, 0x000d0e, 0x000d10, 0x000d12, 0x000d3a, 0x000d3d, 0x000d3d, 0x000d4e, 0x000d4e, 0x000d54, 0x000d56, 0x000d5f,
                        0x000d61, 0x000d7a, 0x000d7f, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000e01, 0x000e30, 0x000e32, 0x000e33,
                        0x000e40, 0x000e45, 0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000eb0, 0x000eb2, 0x000eb3, 0x000ebd,
                        0x000ebd, 0x000ec0, 0x000ec4, 0x000edc, 0x000edf, 0x000f00, 0x000f00, 0x000f40, 0x000f47, 0x000f49, 0x000f6c, 0x000f88, 0x000f8c, 0x001000, 0x00102a, 0x00103f, 0x00103f,
                        0x001050, 0x001055, 0x00105a, 0x00105d, 0x001061, 0x001061, 0x001065, 0x001066, 0x00106e, 0x001070, 0x001075, 0x001081, 0x00108e, 0x00108e, 0x001100, 0x001248, 0x00124a,
                        0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d, 0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290, 0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be,
                        0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8, 0x001310, 0x001312, 0x001315, 0x001318, 0x00135a, 0x001380, 0x00138f, 0x001401, 0x00166c, 0x00166f,
                        0x00167f, 0x001681, 0x00169a, 0x0016a0, 0x0016ea, 0x0016f1, 0x0016f8, 0x001700, 0x001711, 0x00171f, 0x001731, 0x001740, 0x001751, 0x001760, 0x00176c, 0x00176e, 0x001770,
                        0x001780, 0x0017b3, 0x0017dc, 0x0017dc, 0x001820, 0x001842, 0x001844, 0x001878, 0x001880, 0x001884, 0x001887, 0x0018a8, 0x0018aa, 0x0018aa, 0x0018b0, 0x0018f5, 0x001900,
                        0x00191e, 0x001950, 0x00196d, 0x001970, 0x001974, 0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x001a00, 0x001a16, 0x001a20, 0x001a54, 0x001b05, 0x001b33, 0x001b45, 0x001b4c,
                        0x001b83, 0x001ba0, 0x001bae, 0x001baf, 0x001bba, 0x001be5, 0x001c00, 0x001c23, 0x001c4d, 0x001c4f, 0x001c5a, 0x001c77, 0x001ce9, 0x001cec, 0x001cee, 0x001cf3, 0x001cf5,
                        0x001cf6, 0x001cfa, 0x001cfa, 0x002135, 0x002138, 0x002d30, 0x002d67, 0x002d80, 0x002d96, 0x002da0, 0x002da6, 0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe,
                        0x002dc0, 0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x003006, 0x003006, 0x00303c, 0x00303c, 0x003041, 0x003096, 0x00309f, 0x00309f, 0x0030a1,
                        0x0030fa, 0x0030ff, 0x0030ff, 0x003105, 0x00312f, 0x003131, 0x00318e, 0x0031a0, 0x0031bf, 0x0031f0, 0x0031ff, 0x003400, 0x004dbf, 0x004e00, 0x00a014, 0x00a016, 0x00a48c,
                        0x00a4d0, 0x00a4f7, 0x00a500, 0x00a60b, 0x00a610, 0x00a61f, 0x00a62a, 0x00a62b, 0x00a66e, 0x00a66e, 0x00a6a0, 0x00a6e5, 0x00a78f, 0x00a78f, 0x00a7f7, 0x00a7f7, 0x00a7fb,
                        0x00a801, 0x00a803, 0x00a805, 0x00a807, 0x00a80a, 0x00a80c, 0x00a822, 0x00a840, 0x00a873, 0x00a882, 0x00a8b3, 0x00a8f2, 0x00a8f7, 0x00a8fb, 0x00a8fb, 0x00a8fd, 0x00a8fe,
                        0x00a90a, 0x00a925, 0x00a930, 0x00a946, 0x00a960, 0x00a97c, 0x00a984, 0x00a9b2, 0x00a9e0, 0x00a9e4, 0x00a9e7, 0x00a9ef, 0x00a9fa, 0x00a9fe, 0x00aa00, 0x00aa28, 0x00aa40,
                        0x00aa42, 0x00aa44, 0x00aa4b, 0x00aa60, 0x00aa6f, 0x00aa71, 0x00aa76, 0x00aa7a, 0x00aa7a, 0x00aa7e, 0x00aaaf, 0x00aab1, 0x00aab1, 0x00aab5, 0x00aab6, 0x00aab9, 0x00aabd,
                        0x00aac0, 0x00aac0, 0x00aac2, 0x00aac2, 0x00aadb, 0x00aadc, 0x00aae0, 0x00aaea, 0x00aaf2, 0x00aaf2, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20,
                        0x00ab26, 0x00ab28, 0x00ab2e, 0x00abc0, 0x00abe2, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb, 0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x00fb1d, 0x00fb1d,
                        0x00fb1f, 0x00fb28, 0x00fb2a, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40, 0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fbb1, 0x00fbd3, 0x00fd3d, 0x00fd50,
                        0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdf0, 0x00fdfb, 0x00fe70, 0x00fe74, 0x00fe76, 0x00fefc, 0x00ff66, 0x00ff6f, 0x00ff71, 0x00ff9d, 0x00ffa0, 0x00ffbe, 0x00ffc2, 0x00ffc7,
                        0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc, 0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d, 0x01003f, 0x01004d, 0x010050,
                        0x01005d, 0x010080, 0x0100fa, 0x010280, 0x01029c, 0x0102a0, 0x0102d0, 0x010300, 0x01031f, 0x01032d, 0x010340, 0x010342, 0x010349, 0x010350, 0x010375, 0x010380, 0x01039d,
                        0x0103a0, 0x0103c3, 0x0103c8, 0x0103cf, 0x010450, 0x01049d, 0x010500, 0x010527, 0x010530, 0x010563, 0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767, 0x010800,
                        0x010805, 0x010808, 0x010808, 0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x010855, 0x010860, 0x010876, 0x010880, 0x01089e, 0x0108e0, 0x0108f2,
                        0x0108f4, 0x0108f5, 0x010900, 0x010915, 0x010920, 0x010939, 0x010980, 0x0109b7, 0x0109be, 0x0109bf, 0x010a00, 0x010a00, 0x010a10, 0x010a13, 0x010a15, 0x010a17, 0x010a19,
                        0x010a35, 0x010a60, 0x010a7c, 0x010a80, 0x010a9c, 0x010ac0, 0x010ac7, 0x010ac9, 0x010ae4, 0x010b00, 0x010b35, 0x010b40, 0x010b55, 0x010b60, 0x010b72, 0x010b80, 0x010b91,
                        0x010c00, 0x010c48, 0x010d00, 0x010d23, 0x010e80, 0x010ea9, 0x010eb0, 0x010eb1, 0x010f00, 0x010f1c, 0x010f27, 0x010f27, 0x010f30, 0x010f45, 0x010f70, 0x010f81, 0x010fb0,
                        0x010fc4, 0x010fe0, 0x010ff6, 0x011003, 0x011037, 0x011071, 0x011072, 0x011075, 0x011075, 0x011083, 0x0110af, 0x0110d0, 0x0110e8, 0x011103, 0x011126, 0x011144, 0x011144,
                        0x011147, 0x011147, 0x011150, 0x011172, 0x011176, 0x011176, 0x011183, 0x0111b2, 0x0111c1, 0x0111c4, 0x0111da, 0x0111da, 0x0111dc, 0x0111dc, 0x011200, 0x011211, 0x011213,
                        0x01122b, 0x01123f, 0x011240, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a8, 0x0112b0, 0x0112de, 0x011305, 0x01130c,
                        0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133d, 0x01133d, 0x011350, 0x011350, 0x01135d, 0x011361, 0x011400,
                        0x011434, 0x011447, 0x01144a, 0x01145f, 0x011461, 0x011480, 0x0114af, 0x0114c4, 0x0114c5, 0x0114c7, 0x0114c7, 0x011580, 0x0115ae, 0x0115d8, 0x0115db, 0x011600, 0x01162f,
                        0x011644, 0x011644, 0x011680, 0x0116aa, 0x0116b8, 0x0116b8, 0x011700, 0x01171a, 0x011740, 0x011746, 0x011800, 0x01182b, 0x0118ff, 0x011906, 0x011909, 0x011909, 0x01190c,
                        0x011913, 0x011915, 0x011916, 0x011918, 0x01192f, 0x01193f, 0x01193f, 0x011941, 0x011941, 0x0119a0, 0x0119a7, 0x0119aa, 0x0119d0, 0x0119e1, 0x0119e1, 0x0119e3, 0x0119e3,
                        0x011a00, 0x011a00, 0x011a0b, 0x011a32, 0x011a3a, 0x011a3a, 0x011a50, 0x011a50, 0x011a5c, 0x011a89, 0x011a9d, 0x011a9d, 0x011ab0, 0x011af8, 0x011c00, 0x011c08, 0x011c0a,
                        0x011c2e, 0x011c40, 0x011c40, 0x011c72, 0x011c8f, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d30, 0x011d46, 0x011d46, 0x011d60, 0x011d65, 0x011d67, 0x011d68,
                        0x011d6a, 0x011d89, 0x011d98, 0x011d98, 0x011ee0, 0x011ef2, 0x011f02, 0x011f02, 0x011f04, 0x011f10, 0x011f12, 0x011f33, 0x011fb0, 0x011fb0, 0x012000, 0x012399, 0x012480,
                        0x012543, 0x012f90, 0x012ff0, 0x013000, 0x01342f, 0x013441, 0x013446, 0x014400, 0x014646, 0x016800, 0x016a38, 0x016a40, 0x016a5e, 0x016a70, 0x016abe, 0x016ad0, 0x016aed,
                        0x016b00, 0x016b2f, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f, 0x016f00, 0x016f4a, 0x016f50, 0x016f50, 0x017000, 0x0187f7, 0x018800, 0x018cd5, 0x018d00, 0x018d08, 0x01b000,
                        0x01b122, 0x01b132, 0x01b132, 0x01b150, 0x01b152, 0x01b155, 0x01b155, 0x01b164, 0x01b167, 0x01b170, 0x01b2fb, 0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88,
                        0x01bc90, 0x01bc99, 0x01df0a, 0x01df0a, 0x01e100, 0x01e12c, 0x01e14e, 0x01e14e, 0x01e290, 0x01e2ad, 0x01e2c0, 0x01e2eb, 0x01e4d0, 0x01e4ea, 0x01e7e0, 0x01e7e6, 0x01e7e8,
                        0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe, 0x01e800, 0x01e8c4, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27,
                        0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d,
                        0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62,
                        0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1,
                        0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d,
                        0x030000, 0x03134a, 0x031350, 0x0323af));
    }

    private static void populateGC_LT() {
        SET_ENCODINGS.put("gc=Lt", CodePointSet.createNoDedup(0x0001c5, 0x0001c5, 0x0001c8, 0x0001c8, 0x0001cb, 0x0001cb, 0x0001f2, 0x0001f2, 0x001f88, 0x001f8f, 0x001f98, 0x001f9f, 0x001fa8,
                        0x001faf, 0x001fbc, 0x001fbc, 0x001fcc, 0x001fcc, 0x001ffc, 0x001ffc));
    }

    private static void populateGC_LU() {
        SET_ENCODINGS.put("gc=Lu", CodePointSet.createNoDedup(0x000041, 0x00005a, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000de, 0x000100, 0x000100, 0x000102, 0x000102, 0x000104, 0x000104, 0x000106,
                        0x000106, 0x000108, 0x000108, 0x00010a, 0x00010a, 0x00010c, 0x00010c, 0x00010e, 0x00010e, 0x000110, 0x000110, 0x000112, 0x000112, 0x000114, 0x000114, 0x000116, 0x000116,
                        0x000118, 0x000118, 0x00011a, 0x00011a, 0x00011c, 0x00011c, 0x00011e, 0x00011e, 0x000120, 0x000120, 0x000122, 0x000122, 0x000124, 0x000124, 0x000126, 0x000126, 0x000128,
                        0x000128, 0x00012a, 0x00012a, 0x00012c, 0x00012c, 0x00012e, 0x00012e, 0x000130, 0x000130, 0x000132, 0x000132, 0x000134, 0x000134, 0x000136, 0x000136, 0x000139, 0x000139,
                        0x00013b, 0x00013b, 0x00013d, 0x00013d, 0x00013f, 0x00013f, 0x000141, 0x000141, 0x000143, 0x000143, 0x000145, 0x000145, 0x000147, 0x000147, 0x00014a, 0x00014a, 0x00014c,
                        0x00014c, 0x00014e, 0x00014e, 0x000150, 0x000150, 0x000152, 0x000152, 0x000154, 0x000154, 0x000156, 0x000156, 0x000158, 0x000158, 0x00015a, 0x00015a, 0x00015c, 0x00015c,
                        0x00015e, 0x00015e, 0x000160, 0x000160, 0x000162, 0x000162, 0x000164, 0x000164, 0x000166, 0x000166, 0x000168, 0x000168, 0x00016a, 0x00016a, 0x00016c, 0x00016c, 0x00016e,
                        0x00016e, 0x000170, 0x000170, 0x000172, 0x000172, 0x000174, 0x000174, 0x000176, 0x000176, 0x000178, 0x000179, 0x00017b, 0x00017b, 0x00017d, 0x00017d, 0x000181, 0x000182,
                        0x000184, 0x000184, 0x000186, 0x000187, 0x000189, 0x00018b, 0x00018e, 0x000191, 0x000193, 0x000194, 0x000196, 0x000198, 0x00019c, 0x00019d, 0x00019f, 0x0001a0, 0x0001a2,
                        0x0001a2, 0x0001a4, 0x0001a4, 0x0001a6, 0x0001a7, 0x0001a9, 0x0001a9, 0x0001ac, 0x0001ac, 0x0001ae, 0x0001af, 0x0001b1, 0x0001b3, 0x0001b5, 0x0001b5, 0x0001b7, 0x0001b8,
                        0x0001bc, 0x0001bc, 0x0001c4, 0x0001c4, 0x0001c7, 0x0001c7, 0x0001ca, 0x0001ca, 0x0001cd, 0x0001cd, 0x0001cf, 0x0001cf, 0x0001d1, 0x0001d1, 0x0001d3, 0x0001d3, 0x0001d5,
                        0x0001d5, 0x0001d7, 0x0001d7, 0x0001d9, 0x0001d9, 0x0001db, 0x0001db, 0x0001de, 0x0001de, 0x0001e0, 0x0001e0, 0x0001e2, 0x0001e2, 0x0001e4, 0x0001e4, 0x0001e6, 0x0001e6,
                        0x0001e8, 0x0001e8, 0x0001ea, 0x0001ea, 0x0001ec, 0x0001ec, 0x0001ee, 0x0001ee, 0x0001f1, 0x0001f1, 0x0001f4, 0x0001f4, 0x0001f6, 0x0001f8, 0x0001fa, 0x0001fa, 0x0001fc,
                        0x0001fc, 0x0001fe, 0x0001fe, 0x000200, 0x000200, 0x000202, 0x000202, 0x000204, 0x000204, 0x000206, 0x000206, 0x000208, 0x000208, 0x00020a, 0x00020a, 0x00020c, 0x00020c,
                        0x00020e, 0x00020e, 0x000210, 0x000210, 0x000212, 0x000212, 0x000214, 0x000214, 0x000216, 0x000216, 0x000218, 0x000218, 0x00021a, 0x00021a, 0x00021c, 0x00021c, 0x00021e,
                        0x00021e, 0x000220, 0x000220, 0x000222, 0x000222, 0x000224, 0x000224, 0x000226, 0x000226, 0x000228, 0x000228, 0x00022a, 0x00022a, 0x00022c, 0x00022c, 0x00022e, 0x00022e,
                        0x000230, 0x000230, 0x000232, 0x000232, 0x00023a, 0x00023b, 0x00023d, 0x00023e, 0x000241, 0x000241, 0x000243, 0x000246, 0x000248, 0x000248, 0x00024a, 0x00024a, 0x00024c,
                        0x00024c, 0x00024e, 0x00024e, 0x000370, 0x000370, 0x000372, 0x000372, 0x000376, 0x000376, 0x00037f, 0x00037f, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c,
                        0x00038e, 0x00038f, 0x000391, 0x0003a1, 0x0003a3, 0x0003ab, 0x0003cf, 0x0003cf, 0x0003d2, 0x0003d4, 0x0003d8, 0x0003d8, 0x0003da, 0x0003da, 0x0003dc, 0x0003dc, 0x0003de,
                        0x0003de, 0x0003e0, 0x0003e0, 0x0003e2, 0x0003e2, 0x0003e4, 0x0003e4, 0x0003e6, 0x0003e6, 0x0003e8, 0x0003e8, 0x0003ea, 0x0003ea, 0x0003ec, 0x0003ec, 0x0003ee, 0x0003ee,
                        0x0003f4, 0x0003f4, 0x0003f7, 0x0003f7, 0x0003f9, 0x0003fa, 0x0003fd, 0x00042f, 0x000460, 0x000460, 0x000462, 0x000462, 0x000464, 0x000464, 0x000466, 0x000466, 0x000468,
                        0x000468, 0x00046a, 0x00046a, 0x00046c, 0x00046c, 0x00046e, 0x00046e, 0x000470, 0x000470, 0x000472, 0x000472, 0x000474, 0x000474, 0x000476, 0x000476, 0x000478, 0x000478,
                        0x00047a, 0x00047a, 0x00047c, 0x00047c, 0x00047e, 0x00047e, 0x000480, 0x000480, 0x00048a, 0x00048a, 0x00048c, 0x00048c, 0x00048e, 0x00048e, 0x000490, 0x000490, 0x000492,
                        0x000492, 0x000494, 0x000494, 0x000496, 0x000496, 0x000498, 0x000498, 0x00049a, 0x00049a, 0x00049c, 0x00049c, 0x00049e, 0x00049e, 0x0004a0, 0x0004a0, 0x0004a2, 0x0004a2,
                        0x0004a4, 0x0004a4, 0x0004a6, 0x0004a6, 0x0004a8, 0x0004a8, 0x0004aa, 0x0004aa, 0x0004ac, 0x0004ac, 0x0004ae, 0x0004ae, 0x0004b0, 0x0004b0, 0x0004b2, 0x0004b2, 0x0004b4,
                        0x0004b4, 0x0004b6, 0x0004b6, 0x0004b8, 0x0004b8, 0x0004ba, 0x0004ba, 0x0004bc, 0x0004bc, 0x0004be, 0x0004be, 0x0004c0, 0x0004c1, 0x0004c3, 0x0004c3, 0x0004c5, 0x0004c5,
                        0x0004c7, 0x0004c7, 0x0004c9, 0x0004c9, 0x0004cb, 0x0004cb, 0x0004cd, 0x0004cd, 0x0004d0, 0x0004d0, 0x0004d2, 0x0004d2, 0x0004d4, 0x0004d4, 0x0004d6, 0x0004d6, 0x0004d8,
                        0x0004d8, 0x0004da, 0x0004da, 0x0004dc, 0x0004dc, 0x0004de, 0x0004de, 0x0004e0, 0x0004e0, 0x0004e2, 0x0004e2, 0x0004e4, 0x0004e4, 0x0004e6, 0x0004e6, 0x0004e8, 0x0004e8,
                        0x0004ea, 0x0004ea, 0x0004ec, 0x0004ec, 0x0004ee, 0x0004ee, 0x0004f0, 0x0004f0, 0x0004f2, 0x0004f2, 0x0004f4, 0x0004f4, 0x0004f6, 0x0004f6, 0x0004f8, 0x0004f8, 0x0004fa,
                        0x0004fa, 0x0004fc, 0x0004fc, 0x0004fe, 0x0004fe, 0x000500, 0x000500, 0x000502, 0x000502, 0x000504, 0x000504, 0x000506, 0x000506, 0x000508, 0x000508, 0x00050a, 0x00050a,
                        0x00050c, 0x00050c, 0x00050e, 0x00050e, 0x000510, 0x000510, 0x000512, 0x000512, 0x000514, 0x000514, 0x000516, 0x000516, 0x000518, 0x000518, 0x00051a, 0x00051a, 0x00051c,
                        0x00051c, 0x00051e, 0x00051e, 0x000520, 0x000520, 0x000522, 0x000522, 0x000524, 0x000524, 0x000526, 0x000526, 0x000528, 0x000528, 0x00052a, 0x00052a, 0x00052c, 0x00052c,
                        0x00052e, 0x00052e, 0x000531, 0x000556, 0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0013a0, 0x0013f5, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x001e00,
                        0x001e00, 0x001e02, 0x001e02, 0x001e04, 0x001e04, 0x001e06, 0x001e06, 0x001e08, 0x001e08, 0x001e0a, 0x001e0a, 0x001e0c, 0x001e0c, 0x001e0e, 0x001e0e, 0x001e10, 0x001e10,
                        0x001e12, 0x001e12, 0x001e14, 0x001e14, 0x001e16, 0x001e16, 0x001e18, 0x001e18, 0x001e1a, 0x001e1a, 0x001e1c, 0x001e1c, 0x001e1e, 0x001e1e, 0x001e20, 0x001e20, 0x001e22,
                        0x001e22, 0x001e24, 0x001e24, 0x001e26, 0x001e26, 0x001e28, 0x001e28, 0x001e2a, 0x001e2a, 0x001e2c, 0x001e2c, 0x001e2e, 0x001e2e, 0x001e30, 0x001e30, 0x001e32, 0x001e32,
                        0x001e34, 0x001e34, 0x001e36, 0x001e36, 0x001e38, 0x001e38, 0x001e3a, 0x001e3a, 0x001e3c, 0x001e3c, 0x001e3e, 0x001e3e, 0x001e40, 0x001e40, 0x001e42, 0x001e42, 0x001e44,
                        0x001e44, 0x001e46, 0x001e46, 0x001e48, 0x001e48, 0x001e4a, 0x001e4a, 0x001e4c, 0x001e4c, 0x001e4e, 0x001e4e, 0x001e50, 0x001e50, 0x001e52, 0x001e52, 0x001e54, 0x001e54,
                        0x001e56, 0x001e56, 0x001e58, 0x001e58, 0x001e5a, 0x001e5a, 0x001e5c, 0x001e5c, 0x001e5e, 0x001e5e, 0x001e60, 0x001e60, 0x001e62, 0x001e62, 0x001e64, 0x001e64, 0x001e66,
                        0x001e66, 0x001e68, 0x001e68, 0x001e6a, 0x001e6a, 0x001e6c, 0x001e6c, 0x001e6e, 0x001e6e, 0x001e70, 0x001e70, 0x001e72, 0x001e72, 0x001e74, 0x001e74, 0x001e76, 0x001e76,
                        0x001e78, 0x001e78, 0x001e7a, 0x001e7a, 0x001e7c, 0x001e7c, 0x001e7e, 0x001e7e, 0x001e80, 0x001e80, 0x001e82, 0x001e82, 0x001e84, 0x001e84, 0x001e86, 0x001e86, 0x001e88,
                        0x001e88, 0x001e8a, 0x001e8a, 0x001e8c, 0x001e8c, 0x001e8e, 0x001e8e, 0x001e90, 0x001e90, 0x001e92, 0x001e92, 0x001e94, 0x001e94, 0x001e9e, 0x001e9e, 0x001ea0, 0x001ea0,
                        0x001ea2, 0x001ea2, 0x001ea4, 0x001ea4, 0x001ea6, 0x001ea6, 0x001ea8, 0x001ea8, 0x001eaa, 0x001eaa, 0x001eac, 0x001eac, 0x001eae, 0x001eae, 0x001eb0, 0x001eb0, 0x001eb2,
                        0x001eb2, 0x001eb4, 0x001eb4, 0x001eb6, 0x001eb6, 0x001eb8, 0x001eb8, 0x001eba, 0x001eba, 0x001ebc, 0x001ebc, 0x001ebe, 0x001ebe, 0x001ec0, 0x001ec0, 0x001ec2, 0x001ec2,
                        0x001ec4, 0x001ec4, 0x001ec6, 0x001ec6, 0x001ec8, 0x001ec8, 0x001eca, 0x001eca, 0x001ecc, 0x001ecc, 0x001ece, 0x001ece, 0x001ed0, 0x001ed0, 0x001ed2, 0x001ed2, 0x001ed4,
                        0x001ed4, 0x001ed6, 0x001ed6, 0x001ed8, 0x001ed8, 0x001eda, 0x001eda, 0x001edc, 0x001edc, 0x001ede, 0x001ede, 0x001ee0, 0x001ee0, 0x001ee2, 0x001ee2, 0x001ee4, 0x001ee4,
                        0x001ee6, 0x001ee6, 0x001ee8, 0x001ee8, 0x001eea, 0x001eea, 0x001eec, 0x001eec, 0x001eee, 0x001eee, 0x001ef0, 0x001ef0, 0x001ef2, 0x001ef2, 0x001ef4, 0x001ef4, 0x001ef6,
                        0x001ef6, 0x001ef8, 0x001ef8, 0x001efa, 0x001efa, 0x001efc, 0x001efc, 0x001efe, 0x001efe, 0x001f08, 0x001f0f, 0x001f18, 0x001f1d, 0x001f28, 0x001f2f, 0x001f38, 0x001f3f,
                        0x001f48, 0x001f4d, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f5f, 0x001f68, 0x001f6f, 0x001fb8, 0x001fbb, 0x001fc8, 0x001fcb, 0x001fd8,
                        0x001fdb, 0x001fe8, 0x001fec, 0x001ff8, 0x001ffb, 0x002102, 0x002102, 0x002107, 0x002107, 0x00210b, 0x00210d, 0x002110, 0x002112, 0x002115, 0x002115, 0x002119, 0x00211d,
                        0x002124, 0x002124, 0x002126, 0x002126, 0x002128, 0x002128, 0x00212a, 0x00212d, 0x002130, 0x002133, 0x00213e, 0x00213f, 0x002145, 0x002145, 0x002183, 0x002183, 0x002c00,
                        0x002c2f, 0x002c60, 0x002c60, 0x002c62, 0x002c64, 0x002c67, 0x002c67, 0x002c69, 0x002c69, 0x002c6b, 0x002c6b, 0x002c6d, 0x002c70, 0x002c72, 0x002c72, 0x002c75, 0x002c75,
                        0x002c7e, 0x002c80, 0x002c82, 0x002c82, 0x002c84, 0x002c84, 0x002c86, 0x002c86, 0x002c88, 0x002c88, 0x002c8a, 0x002c8a, 0x002c8c, 0x002c8c, 0x002c8e, 0x002c8e, 0x002c90,
                        0x002c90, 0x002c92, 0x002c92, 0x002c94, 0x002c94, 0x002c96, 0x002c96, 0x002c98, 0x002c98, 0x002c9a, 0x002c9a, 0x002c9c, 0x002c9c, 0x002c9e, 0x002c9e, 0x002ca0, 0x002ca0,
                        0x002ca2, 0x002ca2, 0x002ca4, 0x002ca4, 0x002ca6, 0x002ca6, 0x002ca8, 0x002ca8, 0x002caa, 0x002caa, 0x002cac, 0x002cac, 0x002cae, 0x002cae, 0x002cb0, 0x002cb0, 0x002cb2,
                        0x002cb2, 0x002cb4, 0x002cb4, 0x002cb6, 0x002cb6, 0x002cb8, 0x002cb8, 0x002cba, 0x002cba, 0x002cbc, 0x002cbc, 0x002cbe, 0x002cbe, 0x002cc0, 0x002cc0, 0x002cc2, 0x002cc2,
                        0x002cc4, 0x002cc4, 0x002cc6, 0x002cc6, 0x002cc8, 0x002cc8, 0x002cca, 0x002cca, 0x002ccc, 0x002ccc, 0x002cce, 0x002cce, 0x002cd0, 0x002cd0, 0x002cd2, 0x002cd2, 0x002cd4,
                        0x002cd4, 0x002cd6, 0x002cd6, 0x002cd8, 0x002cd8, 0x002cda, 0x002cda, 0x002cdc, 0x002cdc, 0x002cde, 0x002cde, 0x002ce0, 0x002ce0, 0x002ce2, 0x002ce2, 0x002ceb, 0x002ceb,
                        0x002ced, 0x002ced, 0x002cf2, 0x002cf2, 0x00a640, 0x00a640, 0x00a642, 0x00a642, 0x00a644, 0x00a644, 0x00a646, 0x00a646, 0x00a648, 0x00a648, 0x00a64a, 0x00a64a, 0x00a64c,
                        0x00a64c, 0x00a64e, 0x00a64e, 0x00a650, 0x00a650, 0x00a652, 0x00a652, 0x00a654, 0x00a654, 0x00a656, 0x00a656, 0x00a658, 0x00a658, 0x00a65a, 0x00a65a, 0x00a65c, 0x00a65c,
                        0x00a65e, 0x00a65e, 0x00a660, 0x00a660, 0x00a662, 0x00a662, 0x00a664, 0x00a664, 0x00a666, 0x00a666, 0x00a668, 0x00a668, 0x00a66a, 0x00a66a, 0x00a66c, 0x00a66c, 0x00a680,
                        0x00a680, 0x00a682, 0x00a682, 0x00a684, 0x00a684, 0x00a686, 0x00a686, 0x00a688, 0x00a688, 0x00a68a, 0x00a68a, 0x00a68c, 0x00a68c, 0x00a68e, 0x00a68e, 0x00a690, 0x00a690,
                        0x00a692, 0x00a692, 0x00a694, 0x00a694, 0x00a696, 0x00a696, 0x00a698, 0x00a698, 0x00a69a, 0x00a69a, 0x00a722, 0x00a722, 0x00a724, 0x00a724, 0x00a726, 0x00a726, 0x00a728,
                        0x00a728, 0x00a72a, 0x00a72a, 0x00a72c, 0x00a72c, 0x00a72e, 0x00a72e, 0x00a732, 0x00a732, 0x00a734, 0x00a734, 0x00a736, 0x00a736, 0x00a738, 0x00a738, 0x00a73a, 0x00a73a,
                        0x00a73c, 0x00a73c, 0x00a73e, 0x00a73e, 0x00a740, 0x00a740, 0x00a742, 0x00a742, 0x00a744, 0x00a744, 0x00a746, 0x00a746, 0x00a748, 0x00a748, 0x00a74a, 0x00a74a, 0x00a74c,
                        0x00a74c, 0x00a74e, 0x00a74e, 0x00a750, 0x00a750, 0x00a752, 0x00a752, 0x00a754, 0x00a754, 0x00a756, 0x00a756, 0x00a758, 0x00a758, 0x00a75a, 0x00a75a, 0x00a75c, 0x00a75c,
                        0x00a75e, 0x00a75e, 0x00a760, 0x00a760, 0x00a762, 0x00a762, 0x00a764, 0x00a764, 0x00a766, 0x00a766, 0x00a768, 0x00a768, 0x00a76a, 0x00a76a, 0x00a76c, 0x00a76c, 0x00a76e,
                        0x00a76e, 0x00a779, 0x00a779, 0x00a77b, 0x00a77b, 0x00a77d, 0x00a77e, 0x00a780, 0x00a780, 0x00a782, 0x00a782, 0x00a784, 0x00a784, 0x00a786, 0x00a786, 0x00a78b, 0x00a78b,
                        0x00a78d, 0x00a78d, 0x00a790, 0x00a790, 0x00a792, 0x00a792, 0x00a796, 0x00a796, 0x00a798, 0x00a798, 0x00a79a, 0x00a79a, 0x00a79c, 0x00a79c, 0x00a79e, 0x00a79e, 0x00a7a0,
                        0x00a7a0, 0x00a7a2, 0x00a7a2, 0x00a7a4, 0x00a7a4, 0x00a7a6, 0x00a7a6, 0x00a7a8, 0x00a7a8, 0x00a7aa, 0x00a7ae, 0x00a7b0, 0x00a7b4, 0x00a7b6, 0x00a7b6, 0x00a7b8, 0x00a7b8,
                        0x00a7ba, 0x00a7ba, 0x00a7bc, 0x00a7bc, 0x00a7be, 0x00a7be, 0x00a7c0, 0x00a7c0, 0x00a7c2, 0x00a7c2, 0x00a7c4, 0x00a7c7, 0x00a7c9, 0x00a7c9, 0x00a7d0, 0x00a7d0, 0x00a7d6,
                        0x00a7d6, 0x00a7d8, 0x00a7d8, 0x00a7f5, 0x00a7f5, 0x00ff21, 0x00ff3a, 0x010400, 0x010427, 0x0104b0, 0x0104d3, 0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592,
                        0x010594, 0x010595, 0x010c80, 0x010cb2, 0x0118a0, 0x0118bf, 0x016e40, 0x016e5f, 0x01d400, 0x01d419, 0x01d434, 0x01d44d, 0x01d468, 0x01d481, 0x01d49c, 0x01d49c, 0x01d49e,
                        0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b5, 0x01d4d0, 0x01d4e9, 0x01d504, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514,
                        0x01d516, 0x01d51c, 0x01d538, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d56c, 0x01d585, 0x01d5a0, 0x01d5b9, 0x01d5d4,
                        0x01d5ed, 0x01d608, 0x01d621, 0x01d63c, 0x01d655, 0x01d670, 0x01d689, 0x01d6a8, 0x01d6c0, 0x01d6e2, 0x01d6fa, 0x01d71c, 0x01d734, 0x01d756, 0x01d76e, 0x01d790, 0x01d7a8,
                        0x01d7ca, 0x01d7ca, 0x01e900, 0x01e921));
    }

    private static void populateGC_M() {
        SET_ENCODINGS.put("gc=M", CodePointSet.createNoDedup(0x000300, 0x00036f, 0x000483, 0x000489, 0x000591, 0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4, 0x0005c5, 0x0005c7, 0x0005c7,
                        0x000610, 0x00061a, 0x00064b, 0x00065f, 0x000670, 0x000670, 0x0006d6, 0x0006dc, 0x0006df, 0x0006e4, 0x0006e7, 0x0006e8, 0x0006ea, 0x0006ed, 0x000711, 0x000711, 0x000730,
                        0x00074a, 0x0007a6, 0x0007b0, 0x0007eb, 0x0007f3, 0x0007fd, 0x0007fd, 0x000816, 0x000819, 0x00081b, 0x000823, 0x000825, 0x000827, 0x000829, 0x00082d, 0x000859, 0x00085b,
                        0x000898, 0x00089f, 0x0008ca, 0x0008e1, 0x0008e3, 0x000903, 0x00093a, 0x00093c, 0x00093e, 0x00094f, 0x000951, 0x000957, 0x000962, 0x000963, 0x000981, 0x000983, 0x0009bc,
                        0x0009bc, 0x0009be, 0x0009c4, 0x0009c7, 0x0009c8, 0x0009cb, 0x0009cd, 0x0009d7, 0x0009d7, 0x0009e2, 0x0009e3, 0x0009fe, 0x0009fe, 0x000a01, 0x000a03, 0x000a3c, 0x000a3c,
                        0x000a3e, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4d, 0x000a51, 0x000a51, 0x000a70, 0x000a71, 0x000a75, 0x000a75, 0x000a81, 0x000a83, 0x000abc, 0x000abc, 0x000abe,
                        0x000ac5, 0x000ac7, 0x000ac9, 0x000acb, 0x000acd, 0x000ae2, 0x000ae3, 0x000afa, 0x000aff, 0x000b01, 0x000b03, 0x000b3c, 0x000b3c, 0x000b3e, 0x000b44, 0x000b47, 0x000b48,
                        0x000b4b, 0x000b4d, 0x000b55, 0x000b57, 0x000b62, 0x000b63, 0x000b82, 0x000b82, 0x000bbe, 0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcd, 0x000bd7, 0x000bd7, 0x000c00,
                        0x000c04, 0x000c3c, 0x000c3c, 0x000c3e, 0x000c44, 0x000c46, 0x000c48, 0x000c4a, 0x000c4d, 0x000c55, 0x000c56, 0x000c62, 0x000c63, 0x000c81, 0x000c83, 0x000cbc, 0x000cbc,
                        0x000cbe, 0x000cc4, 0x000cc6, 0x000cc8, 0x000cca, 0x000ccd, 0x000cd5, 0x000cd6, 0x000ce2, 0x000ce3, 0x000cf3, 0x000cf3, 0x000d00, 0x000d03, 0x000d3b, 0x000d3c, 0x000d3e,
                        0x000d44, 0x000d46, 0x000d48, 0x000d4a, 0x000d4d, 0x000d57, 0x000d57, 0x000d62, 0x000d63, 0x000d81, 0x000d83, 0x000dca, 0x000dca, 0x000dcf, 0x000dd4, 0x000dd6, 0x000dd6,
                        0x000dd8, 0x000ddf, 0x000df2, 0x000df3, 0x000e31, 0x000e31, 0x000e34, 0x000e3a, 0x000e47, 0x000e4e, 0x000eb1, 0x000eb1, 0x000eb4, 0x000ebc, 0x000ec8, 0x000ece, 0x000f18,
                        0x000f19, 0x000f35, 0x000f35, 0x000f37, 0x000f37, 0x000f39, 0x000f39, 0x000f3e, 0x000f3f, 0x000f71, 0x000f84, 0x000f86, 0x000f87, 0x000f8d, 0x000f97, 0x000f99, 0x000fbc,
                        0x000fc6, 0x000fc6, 0x00102b, 0x00103e, 0x001056, 0x001059, 0x00105e, 0x001060, 0x001062, 0x001064, 0x001067, 0x00106d, 0x001071, 0x001074, 0x001082, 0x00108d, 0x00108f,
                        0x00108f, 0x00109a, 0x00109d, 0x00135d, 0x00135f, 0x001712, 0x001715, 0x001732, 0x001734, 0x001752, 0x001753, 0x001772, 0x001773, 0x0017b4, 0x0017d3, 0x0017dd, 0x0017dd,
                        0x00180b, 0x00180d, 0x00180f, 0x00180f, 0x001885, 0x001886, 0x0018a9, 0x0018a9, 0x001920, 0x00192b, 0x001930, 0x00193b, 0x001a17, 0x001a1b, 0x001a55, 0x001a5e, 0x001a60,
                        0x001a7c, 0x001a7f, 0x001a7f, 0x001ab0, 0x001ace, 0x001b00, 0x001b04, 0x001b34, 0x001b44, 0x001b6b, 0x001b73, 0x001b80, 0x001b82, 0x001ba1, 0x001bad, 0x001be6, 0x001bf3,
                        0x001c24, 0x001c37, 0x001cd0, 0x001cd2, 0x001cd4, 0x001ce8, 0x001ced, 0x001ced, 0x001cf4, 0x001cf4, 0x001cf7, 0x001cf9, 0x001dc0, 0x001dff, 0x0020d0, 0x0020f0, 0x002cef,
                        0x002cf1, 0x002d7f, 0x002d7f, 0x002de0, 0x002dff, 0x00302a, 0x00302f, 0x003099, 0x00309a, 0x00a66f, 0x00a672, 0x00a674, 0x00a67d, 0x00a69e, 0x00a69f, 0x00a6f0, 0x00a6f1,
                        0x00a802, 0x00a802, 0x00a806, 0x00a806, 0x00a80b, 0x00a80b, 0x00a823, 0x00a827, 0x00a82c, 0x00a82c, 0x00a880, 0x00a881, 0x00a8b4, 0x00a8c5, 0x00a8e0, 0x00a8f1, 0x00a8ff,
                        0x00a8ff, 0x00a926, 0x00a92d, 0x00a947, 0x00a953, 0x00a980, 0x00a983, 0x00a9b3, 0x00a9c0, 0x00a9e5, 0x00a9e5, 0x00aa29, 0x00aa36, 0x00aa43, 0x00aa43, 0x00aa4c, 0x00aa4d,
                        0x00aa7b, 0x00aa7d, 0x00aab0, 0x00aab0, 0x00aab2, 0x00aab4, 0x00aab7, 0x00aab8, 0x00aabe, 0x00aabf, 0x00aac1, 0x00aac1, 0x00aaeb, 0x00aaef, 0x00aaf5, 0x00aaf6, 0x00abe3,
                        0x00abea, 0x00abec, 0x00abed, 0x00fb1e, 0x00fb1e, 0x00fe00, 0x00fe0f, 0x00fe20, 0x00fe2f, 0x0101fd, 0x0101fd, 0x0102e0, 0x0102e0, 0x010376, 0x01037a, 0x010a01, 0x010a03,
                        0x010a05, 0x010a06, 0x010a0c, 0x010a0f, 0x010a38, 0x010a3a, 0x010a3f, 0x010a3f, 0x010ae5, 0x010ae6, 0x010d24, 0x010d27, 0x010eab, 0x010eac, 0x010efd, 0x010eff, 0x010f46,
                        0x010f50, 0x010f82, 0x010f85, 0x011000, 0x011002, 0x011038, 0x011046, 0x011070, 0x011070, 0x011073, 0x011074, 0x01107f, 0x011082, 0x0110b0, 0x0110ba, 0x0110c2, 0x0110c2,
                        0x011100, 0x011102, 0x011127, 0x011134, 0x011145, 0x011146, 0x011173, 0x011173, 0x011180, 0x011182, 0x0111b3, 0x0111c0, 0x0111c9, 0x0111cc, 0x0111ce, 0x0111cf, 0x01122c,
                        0x011237, 0x01123e, 0x01123e, 0x011241, 0x011241, 0x0112df, 0x0112ea, 0x011300, 0x011303, 0x01133b, 0x01133c, 0x01133e, 0x011344, 0x011347, 0x011348, 0x01134b, 0x01134d,
                        0x011357, 0x011357, 0x011362, 0x011363, 0x011366, 0x01136c, 0x011370, 0x011374, 0x011435, 0x011446, 0x01145e, 0x01145e, 0x0114b0, 0x0114c3, 0x0115af, 0x0115b5, 0x0115b8,
                        0x0115c0, 0x0115dc, 0x0115dd, 0x011630, 0x011640, 0x0116ab, 0x0116b7, 0x01171d, 0x01172b, 0x01182c, 0x01183a, 0x011930, 0x011935, 0x011937, 0x011938, 0x01193b, 0x01193e,
                        0x011940, 0x011940, 0x011942, 0x011943, 0x0119d1, 0x0119d7, 0x0119da, 0x0119e0, 0x0119e4, 0x0119e4, 0x011a01, 0x011a0a, 0x011a33, 0x011a39, 0x011a3b, 0x011a3e, 0x011a47,
                        0x011a47, 0x011a51, 0x011a5b, 0x011a8a, 0x011a99, 0x011c2f, 0x011c36, 0x011c38, 0x011c3f, 0x011c92, 0x011ca7, 0x011ca9, 0x011cb6, 0x011d31, 0x011d36, 0x011d3a, 0x011d3a,
                        0x011d3c, 0x011d3d, 0x011d3f, 0x011d45, 0x011d47, 0x011d47, 0x011d8a, 0x011d8e, 0x011d90, 0x011d91, 0x011d93, 0x011d97, 0x011ef3, 0x011ef6, 0x011f00, 0x011f01, 0x011f03,
                        0x011f03, 0x011f34, 0x011f3a, 0x011f3e, 0x011f42, 0x013440, 0x013440, 0x013447, 0x013455, 0x016af0, 0x016af4, 0x016b30, 0x016b36, 0x016f4f, 0x016f4f, 0x016f51, 0x016f87,
                        0x016f8f, 0x016f92, 0x016fe4, 0x016fe4, 0x016ff0, 0x016ff1, 0x01bc9d, 0x01bc9e, 0x01cf00, 0x01cf2d, 0x01cf30, 0x01cf46, 0x01d165, 0x01d169, 0x01d16d, 0x01d172, 0x01d17b,
                        0x01d182, 0x01d185, 0x01d18b, 0x01d1aa, 0x01d1ad, 0x01d242, 0x01d244, 0x01da00, 0x01da36, 0x01da3b, 0x01da6c, 0x01da75, 0x01da75, 0x01da84, 0x01da84, 0x01da9b, 0x01da9f,
                        0x01daa1, 0x01daaf, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b, 0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a, 0x01e08f, 0x01e08f, 0x01e130, 0x01e136, 0x01e2ae,
                        0x01e2ae, 0x01e2ec, 0x01e2ef, 0x01e4ec, 0x01e4ef, 0x01e8d0, 0x01e8d6, 0x01e944, 0x01e94a, 0x0e0100, 0x0e01ef));
    }

    private static void populateGC_MC() {
        SET_ENCODINGS.put("gc=Mc", CodePointSet.createNoDedup(0x000903, 0x000903, 0x00093b, 0x00093b, 0x00093e, 0x000940, 0x000949, 0x00094c, 0x00094e, 0x00094f, 0x000982, 0x000983, 0x0009be,
                        0x0009c0, 0x0009c7, 0x0009c8, 0x0009cb, 0x0009cc, 0x0009d7, 0x0009d7, 0x000a03, 0x000a03, 0x000a3e, 0x000a40, 0x000a83, 0x000a83, 0x000abe, 0x000ac0, 0x000ac9, 0x000ac9,
                        0x000acb, 0x000acc, 0x000b02, 0x000b03, 0x000b3e, 0x000b3e, 0x000b40, 0x000b40, 0x000b47, 0x000b48, 0x000b4b, 0x000b4c, 0x000b57, 0x000b57, 0x000bbe, 0x000bbf, 0x000bc1,
                        0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcc, 0x000bd7, 0x000bd7, 0x000c01, 0x000c03, 0x000c41, 0x000c44, 0x000c82, 0x000c83, 0x000cbe, 0x000cbe, 0x000cc0, 0x000cc4,
                        0x000cc7, 0x000cc8, 0x000cca, 0x000ccb, 0x000cd5, 0x000cd6, 0x000cf3, 0x000cf3, 0x000d02, 0x000d03, 0x000d3e, 0x000d40, 0x000d46, 0x000d48, 0x000d4a, 0x000d4c, 0x000d57,
                        0x000d57, 0x000d82, 0x000d83, 0x000dcf, 0x000dd1, 0x000dd8, 0x000ddf, 0x000df2, 0x000df3, 0x000f3e, 0x000f3f, 0x000f7f, 0x000f7f, 0x00102b, 0x00102c, 0x001031, 0x001031,
                        0x001038, 0x001038, 0x00103b, 0x00103c, 0x001056, 0x001057, 0x001062, 0x001064, 0x001067, 0x00106d, 0x001083, 0x001084, 0x001087, 0x00108c, 0x00108f, 0x00108f, 0x00109a,
                        0x00109c, 0x001715, 0x001715, 0x001734, 0x001734, 0x0017b6, 0x0017b6, 0x0017be, 0x0017c5, 0x0017c7, 0x0017c8, 0x001923, 0x001926, 0x001929, 0x00192b, 0x001930, 0x001931,
                        0x001933, 0x001938, 0x001a19, 0x001a1a, 0x001a55, 0x001a55, 0x001a57, 0x001a57, 0x001a61, 0x001a61, 0x001a63, 0x001a64, 0x001a6d, 0x001a72, 0x001b04, 0x001b04, 0x001b35,
                        0x001b35, 0x001b3b, 0x001b3b, 0x001b3d, 0x001b41, 0x001b43, 0x001b44, 0x001b82, 0x001b82, 0x001ba1, 0x001ba1, 0x001ba6, 0x001ba7, 0x001baa, 0x001baa, 0x001be7, 0x001be7,
                        0x001bea, 0x001bec, 0x001bee, 0x001bee, 0x001bf2, 0x001bf3, 0x001c24, 0x001c2b, 0x001c34, 0x001c35, 0x001ce1, 0x001ce1, 0x001cf7, 0x001cf7, 0x00302e, 0x00302f, 0x00a823,
                        0x00a824, 0x00a827, 0x00a827, 0x00a880, 0x00a881, 0x00a8b4, 0x00a8c3, 0x00a952, 0x00a953, 0x00a983, 0x00a983, 0x00a9b4, 0x00a9b5, 0x00a9ba, 0x00a9bb, 0x00a9be, 0x00a9c0,
                        0x00aa2f, 0x00aa30, 0x00aa33, 0x00aa34, 0x00aa4d, 0x00aa4d, 0x00aa7b, 0x00aa7b, 0x00aa7d, 0x00aa7d, 0x00aaeb, 0x00aaeb, 0x00aaee, 0x00aaef, 0x00aaf5, 0x00aaf5, 0x00abe3,
                        0x00abe4, 0x00abe6, 0x00abe7, 0x00abe9, 0x00abea, 0x00abec, 0x00abec, 0x011000, 0x011000, 0x011002, 0x011002, 0x011082, 0x011082, 0x0110b0, 0x0110b2, 0x0110b7, 0x0110b8,
                        0x01112c, 0x01112c, 0x011145, 0x011146, 0x011182, 0x011182, 0x0111b3, 0x0111b5, 0x0111bf, 0x0111c0, 0x0111ce, 0x0111ce, 0x01122c, 0x01122e, 0x011232, 0x011233, 0x011235,
                        0x011235, 0x0112e0, 0x0112e2, 0x011302, 0x011303, 0x01133e, 0x01133f, 0x011341, 0x011344, 0x011347, 0x011348, 0x01134b, 0x01134d, 0x011357, 0x011357, 0x011362, 0x011363,
                        0x011435, 0x011437, 0x011440, 0x011441, 0x011445, 0x011445, 0x0114b0, 0x0114b2, 0x0114b9, 0x0114b9, 0x0114bb, 0x0114be, 0x0114c1, 0x0114c1, 0x0115af, 0x0115b1, 0x0115b8,
                        0x0115bb, 0x0115be, 0x0115be, 0x011630, 0x011632, 0x01163b, 0x01163c, 0x01163e, 0x01163e, 0x0116ac, 0x0116ac, 0x0116ae, 0x0116af, 0x0116b6, 0x0116b6, 0x011720, 0x011721,
                        0x011726, 0x011726, 0x01182c, 0x01182e, 0x011838, 0x011838, 0x011930, 0x011935, 0x011937, 0x011938, 0x01193d, 0x01193d, 0x011940, 0x011940, 0x011942, 0x011942, 0x0119d1,
                        0x0119d3, 0x0119dc, 0x0119df, 0x0119e4, 0x0119e4, 0x011a39, 0x011a39, 0x011a57, 0x011a58, 0x011a97, 0x011a97, 0x011c2f, 0x011c2f, 0x011c3e, 0x011c3e, 0x011ca9, 0x011ca9,
                        0x011cb1, 0x011cb1, 0x011cb4, 0x011cb4, 0x011d8a, 0x011d8e, 0x011d93, 0x011d94, 0x011d96, 0x011d96, 0x011ef5, 0x011ef6, 0x011f03, 0x011f03, 0x011f34, 0x011f35, 0x011f3e,
                        0x011f3f, 0x011f41, 0x011f41, 0x016f51, 0x016f87, 0x016ff0, 0x016ff1, 0x01d165, 0x01d166, 0x01d16d, 0x01d172));
    }

    private static void populateGC_ME() {
        SET_ENCODINGS.put("gc=Me", CodePointSet.createNoDedup(0x000488, 0x000489, 0x001abe, 0x001abe, 0x0020dd, 0x0020e0, 0x0020e2, 0x0020e4, 0x00a670, 0x00a672));
    }

    private static void populateGC_MN() {
        SET_ENCODINGS.put("gc=Mn", CodePointSet.createNoDedup(0x000300, 0x00036f, 0x000483, 0x000487, 0x000591, 0x0005bd, 0x0005bf, 0x0005bf, 0x0005c1, 0x0005c2, 0x0005c4, 0x0005c5, 0x0005c7,
                        0x0005c7, 0x000610, 0x00061a, 0x00064b, 0x00065f, 0x000670, 0x000670, 0x0006d6, 0x0006dc, 0x0006df, 0x0006e4, 0x0006e7, 0x0006e8, 0x0006ea, 0x0006ed, 0x000711, 0x000711,
                        0x000730, 0x00074a, 0x0007a6, 0x0007b0, 0x0007eb, 0x0007f3, 0x0007fd, 0x0007fd, 0x000816, 0x000819, 0x00081b, 0x000823, 0x000825, 0x000827, 0x000829, 0x00082d, 0x000859,
                        0x00085b, 0x000898, 0x00089f, 0x0008ca, 0x0008e1, 0x0008e3, 0x000902, 0x00093a, 0x00093a, 0x00093c, 0x00093c, 0x000941, 0x000948, 0x00094d, 0x00094d, 0x000951, 0x000957,
                        0x000962, 0x000963, 0x000981, 0x000981, 0x0009bc, 0x0009bc, 0x0009c1, 0x0009c4, 0x0009cd, 0x0009cd, 0x0009e2, 0x0009e3, 0x0009fe, 0x0009fe, 0x000a01, 0x000a02, 0x000a3c,
                        0x000a3c, 0x000a41, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4d, 0x000a51, 0x000a51, 0x000a70, 0x000a71, 0x000a75, 0x000a75, 0x000a81, 0x000a82, 0x000abc, 0x000abc,
                        0x000ac1, 0x000ac5, 0x000ac7, 0x000ac8, 0x000acd, 0x000acd, 0x000ae2, 0x000ae3, 0x000afa, 0x000aff, 0x000b01, 0x000b01, 0x000b3c, 0x000b3c, 0x000b3f, 0x000b3f, 0x000b41,
                        0x000b44, 0x000b4d, 0x000b4d, 0x000b55, 0x000b56, 0x000b62, 0x000b63, 0x000b82, 0x000b82, 0x000bc0, 0x000bc0, 0x000bcd, 0x000bcd, 0x000c00, 0x000c00, 0x000c04, 0x000c04,
                        0x000c3c, 0x000c3c, 0x000c3e, 0x000c40, 0x000c46, 0x000c48, 0x000c4a, 0x000c4d, 0x000c55, 0x000c56, 0x000c62, 0x000c63, 0x000c81, 0x000c81, 0x000cbc, 0x000cbc, 0x000cbf,
                        0x000cbf, 0x000cc6, 0x000cc6, 0x000ccc, 0x000ccd, 0x000ce2, 0x000ce3, 0x000d00, 0x000d01, 0x000d3b, 0x000d3c, 0x000d41, 0x000d44, 0x000d4d, 0x000d4d, 0x000d62, 0x000d63,
                        0x000d81, 0x000d81, 0x000dca, 0x000dca, 0x000dd2, 0x000dd4, 0x000dd6, 0x000dd6, 0x000e31, 0x000e31, 0x000e34, 0x000e3a, 0x000e47, 0x000e4e, 0x000eb1, 0x000eb1, 0x000eb4,
                        0x000ebc, 0x000ec8, 0x000ece, 0x000f18, 0x000f19, 0x000f35, 0x000f35, 0x000f37, 0x000f37, 0x000f39, 0x000f39, 0x000f71, 0x000f7e, 0x000f80, 0x000f84, 0x000f86, 0x000f87,
                        0x000f8d, 0x000f97, 0x000f99, 0x000fbc, 0x000fc6, 0x000fc6, 0x00102d, 0x001030, 0x001032, 0x001037, 0x001039, 0x00103a, 0x00103d, 0x00103e, 0x001058, 0x001059, 0x00105e,
                        0x001060, 0x001071, 0x001074, 0x001082, 0x001082, 0x001085, 0x001086, 0x00108d, 0x00108d, 0x00109d, 0x00109d, 0x00135d, 0x00135f, 0x001712, 0x001714, 0x001732, 0x001733,
                        0x001752, 0x001753, 0x001772, 0x001773, 0x0017b4, 0x0017b5, 0x0017b7, 0x0017bd, 0x0017c6, 0x0017c6, 0x0017c9, 0x0017d3, 0x0017dd, 0x0017dd, 0x00180b, 0x00180d, 0x00180f,
                        0x00180f, 0x001885, 0x001886, 0x0018a9, 0x0018a9, 0x001920, 0x001922, 0x001927, 0x001928, 0x001932, 0x001932, 0x001939, 0x00193b, 0x001a17, 0x001a18, 0x001a1b, 0x001a1b,
                        0x001a56, 0x001a56, 0x001a58, 0x001a5e, 0x001a60, 0x001a60, 0x001a62, 0x001a62, 0x001a65, 0x001a6c, 0x001a73, 0x001a7c, 0x001a7f, 0x001a7f, 0x001ab0, 0x001abd, 0x001abf,
                        0x001ace, 0x001b00, 0x001b03, 0x001b34, 0x001b34, 0x001b36, 0x001b3a, 0x001b3c, 0x001b3c, 0x001b42, 0x001b42, 0x001b6b, 0x001b73, 0x001b80, 0x001b81, 0x001ba2, 0x001ba5,
                        0x001ba8, 0x001ba9, 0x001bab, 0x001bad, 0x001be6, 0x001be6, 0x001be8, 0x001be9, 0x001bed, 0x001bed, 0x001bef, 0x001bf1, 0x001c2c, 0x001c33, 0x001c36, 0x001c37, 0x001cd0,
                        0x001cd2, 0x001cd4, 0x001ce0, 0x001ce2, 0x001ce8, 0x001ced, 0x001ced, 0x001cf4, 0x001cf4, 0x001cf8, 0x001cf9, 0x001dc0, 0x001dff, 0x0020d0, 0x0020dc, 0x0020e1, 0x0020e1,
                        0x0020e5, 0x0020f0, 0x002cef, 0x002cf1, 0x002d7f, 0x002d7f, 0x002de0, 0x002dff, 0x00302a, 0x00302d, 0x003099, 0x00309a, 0x00a66f, 0x00a66f, 0x00a674, 0x00a67d, 0x00a69e,
                        0x00a69f, 0x00a6f0, 0x00a6f1, 0x00a802, 0x00a802, 0x00a806, 0x00a806, 0x00a80b, 0x00a80b, 0x00a825, 0x00a826, 0x00a82c, 0x00a82c, 0x00a8c4, 0x00a8c5, 0x00a8e0, 0x00a8f1,
                        0x00a8ff, 0x00a8ff, 0x00a926, 0x00a92d, 0x00a947, 0x00a951, 0x00a980, 0x00a982, 0x00a9b3, 0x00a9b3, 0x00a9b6, 0x00a9b9, 0x00a9bc, 0x00a9bd, 0x00a9e5, 0x00a9e5, 0x00aa29,
                        0x00aa2e, 0x00aa31, 0x00aa32, 0x00aa35, 0x00aa36, 0x00aa43, 0x00aa43, 0x00aa4c, 0x00aa4c, 0x00aa7c, 0x00aa7c, 0x00aab0, 0x00aab0, 0x00aab2, 0x00aab4, 0x00aab7, 0x00aab8,
                        0x00aabe, 0x00aabf, 0x00aac1, 0x00aac1, 0x00aaec, 0x00aaed, 0x00aaf6, 0x00aaf6, 0x00abe5, 0x00abe5, 0x00abe8, 0x00abe8, 0x00abed, 0x00abed, 0x00fb1e, 0x00fb1e, 0x00fe00,
                        0x00fe0f, 0x00fe20, 0x00fe2f, 0x0101fd, 0x0101fd, 0x0102e0, 0x0102e0, 0x010376, 0x01037a, 0x010a01, 0x010a03, 0x010a05, 0x010a06, 0x010a0c, 0x010a0f, 0x010a38, 0x010a3a,
                        0x010a3f, 0x010a3f, 0x010ae5, 0x010ae6, 0x010d24, 0x010d27, 0x010eab, 0x010eac, 0x010efd, 0x010eff, 0x010f46, 0x010f50, 0x010f82, 0x010f85, 0x011001, 0x011001, 0x011038,
                        0x011046, 0x011070, 0x011070, 0x011073, 0x011074, 0x01107f, 0x011081, 0x0110b3, 0x0110b6, 0x0110b9, 0x0110ba, 0x0110c2, 0x0110c2, 0x011100, 0x011102, 0x011127, 0x01112b,
                        0x01112d, 0x011134, 0x011173, 0x011173, 0x011180, 0x011181, 0x0111b6, 0x0111be, 0x0111c9, 0x0111cc, 0x0111cf, 0x0111cf, 0x01122f, 0x011231, 0x011234, 0x011234, 0x011236,
                        0x011237, 0x01123e, 0x01123e, 0x011241, 0x011241, 0x0112df, 0x0112df, 0x0112e3, 0x0112ea, 0x011300, 0x011301, 0x01133b, 0x01133c, 0x011340, 0x011340, 0x011366, 0x01136c,
                        0x011370, 0x011374, 0x011438, 0x01143f, 0x011442, 0x011444, 0x011446, 0x011446, 0x01145e, 0x01145e, 0x0114b3, 0x0114b8, 0x0114ba, 0x0114ba, 0x0114bf, 0x0114c0, 0x0114c2,
                        0x0114c3, 0x0115b2, 0x0115b5, 0x0115bc, 0x0115bd, 0x0115bf, 0x0115c0, 0x0115dc, 0x0115dd, 0x011633, 0x01163a, 0x01163d, 0x01163d, 0x01163f, 0x011640, 0x0116ab, 0x0116ab,
                        0x0116ad, 0x0116ad, 0x0116b0, 0x0116b5, 0x0116b7, 0x0116b7, 0x01171d, 0x01171f, 0x011722, 0x011725, 0x011727, 0x01172b, 0x01182f, 0x011837, 0x011839, 0x01183a, 0x01193b,
                        0x01193c, 0x01193e, 0x01193e, 0x011943, 0x011943, 0x0119d4, 0x0119d7, 0x0119da, 0x0119db, 0x0119e0, 0x0119e0, 0x011a01, 0x011a0a, 0x011a33, 0x011a38, 0x011a3b, 0x011a3e,
                        0x011a47, 0x011a47, 0x011a51, 0x011a56, 0x011a59, 0x011a5b, 0x011a8a, 0x011a96, 0x011a98, 0x011a99, 0x011c30, 0x011c36, 0x011c38, 0x011c3d, 0x011c3f, 0x011c3f, 0x011c92,
                        0x011ca7, 0x011caa, 0x011cb0, 0x011cb2, 0x011cb3, 0x011cb5, 0x011cb6, 0x011d31, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d45, 0x011d47, 0x011d47,
                        0x011d90, 0x011d91, 0x011d95, 0x011d95, 0x011d97, 0x011d97, 0x011ef3, 0x011ef4, 0x011f00, 0x011f01, 0x011f36, 0x011f3a, 0x011f40, 0x011f40, 0x011f42, 0x011f42, 0x013440,
                        0x013440, 0x013447, 0x013455, 0x016af0, 0x016af4, 0x016b30, 0x016b36, 0x016f4f, 0x016f4f, 0x016f8f, 0x016f92, 0x016fe4, 0x016fe4, 0x01bc9d, 0x01bc9e, 0x01cf00, 0x01cf2d,
                        0x01cf30, 0x01cf46, 0x01d167, 0x01d169, 0x01d17b, 0x01d182, 0x01d185, 0x01d18b, 0x01d1aa, 0x01d1ad, 0x01d242, 0x01d244, 0x01da00, 0x01da36, 0x01da3b, 0x01da6c, 0x01da75,
                        0x01da75, 0x01da84, 0x01da84, 0x01da9b, 0x01da9f, 0x01daa1, 0x01daaf, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b, 0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a,
                        0x01e08f, 0x01e08f, 0x01e130, 0x01e136, 0x01e2ae, 0x01e2ae, 0x01e2ec, 0x01e2ef, 0x01e4ec, 0x01e4ef, 0x01e8d0, 0x01e8d6, 0x01e944, 0x01e94a, 0x0e0100, 0x0e01ef));
    }

    private static void populateGC_N() {
        SET_ENCODINGS.put("gc=N", CodePointSet.createNoDedup(0x000030, 0x000039, 0x0000b2, 0x0000b3, 0x0000b9, 0x0000b9, 0x0000bc, 0x0000be, 0x000660, 0x000669, 0x0006f0, 0x0006f9, 0x0007c0, 0x0007c9,
                        0x000966, 0x00096f, 0x0009e6, 0x0009ef, 0x0009f4, 0x0009f9, 0x000a66, 0x000a6f, 0x000ae6, 0x000aef, 0x000b66, 0x000b6f, 0x000b72, 0x000b77, 0x000be6, 0x000bf2, 0x000c66,
                        0x000c6f, 0x000c78, 0x000c7e, 0x000ce6, 0x000cef, 0x000d58, 0x000d5e, 0x000d66, 0x000d78, 0x000de6, 0x000def, 0x000e50, 0x000e59, 0x000ed0, 0x000ed9, 0x000f20, 0x000f33,
                        0x001040, 0x001049, 0x001090, 0x001099, 0x001369, 0x00137c, 0x0016ee, 0x0016f0, 0x0017e0, 0x0017e9, 0x0017f0, 0x0017f9, 0x001810, 0x001819, 0x001946, 0x00194f, 0x0019d0,
                        0x0019da, 0x001a80, 0x001a89, 0x001a90, 0x001a99, 0x001b50, 0x001b59, 0x001bb0, 0x001bb9, 0x001c40, 0x001c49, 0x001c50, 0x001c59, 0x002070, 0x002070, 0x002074, 0x002079,
                        0x002080, 0x002089, 0x002150, 0x002182, 0x002185, 0x002189, 0x002460, 0x00249b, 0x0024ea, 0x0024ff, 0x002776, 0x002793, 0x002cfd, 0x002cfd, 0x003007, 0x003007, 0x003021,
                        0x003029, 0x003038, 0x00303a, 0x003192, 0x003195, 0x003220, 0x003229, 0x003248, 0x00324f, 0x003251, 0x00325f, 0x003280, 0x003289, 0x0032b1, 0x0032bf, 0x00a620, 0x00a629,
                        0x00a6e6, 0x00a6ef, 0x00a830, 0x00a835, 0x00a8d0, 0x00a8d9, 0x00a900, 0x00a909, 0x00a9d0, 0x00a9d9, 0x00a9f0, 0x00a9f9, 0x00aa50, 0x00aa59, 0x00abf0, 0x00abf9, 0x00ff10,
                        0x00ff19, 0x010107, 0x010133, 0x010140, 0x010178, 0x01018a, 0x01018b, 0x0102e1, 0x0102fb, 0x010320, 0x010323, 0x010341, 0x010341, 0x01034a, 0x01034a, 0x0103d1, 0x0103d5,
                        0x0104a0, 0x0104a9, 0x010858, 0x01085f, 0x010879, 0x01087f, 0x0108a7, 0x0108af, 0x0108fb, 0x0108ff, 0x010916, 0x01091b, 0x0109bc, 0x0109bd, 0x0109c0, 0x0109cf, 0x0109d2,
                        0x0109ff, 0x010a40, 0x010a48, 0x010a7d, 0x010a7e, 0x010a9d, 0x010a9f, 0x010aeb, 0x010aef, 0x010b58, 0x010b5f, 0x010b78, 0x010b7f, 0x010ba9, 0x010baf, 0x010cfa, 0x010cff,
                        0x010d30, 0x010d39, 0x010e60, 0x010e7e, 0x010f1d, 0x010f26, 0x010f51, 0x010f54, 0x010fc5, 0x010fcb, 0x011052, 0x01106f, 0x0110f0, 0x0110f9, 0x011136, 0x01113f, 0x0111d0,
                        0x0111d9, 0x0111e1, 0x0111f4, 0x0112f0, 0x0112f9, 0x011450, 0x011459, 0x0114d0, 0x0114d9, 0x011650, 0x011659, 0x0116c0, 0x0116c9, 0x011730, 0x01173b, 0x0118e0, 0x0118f2,
                        0x011950, 0x011959, 0x011c50, 0x011c6c, 0x011d50, 0x011d59, 0x011da0, 0x011da9, 0x011f50, 0x011f59, 0x011fc0, 0x011fd4, 0x012400, 0x01246e, 0x016a60, 0x016a69, 0x016ac0,
                        0x016ac9, 0x016b50, 0x016b59, 0x016b5b, 0x016b61, 0x016e80, 0x016e96, 0x01d2c0, 0x01d2d3, 0x01d2e0, 0x01d2f3, 0x01d360, 0x01d378, 0x01d7ce, 0x01d7ff, 0x01e140, 0x01e149,
                        0x01e2f0, 0x01e2f9, 0x01e4f0, 0x01e4f9, 0x01e8c7, 0x01e8cf, 0x01e950, 0x01e959, 0x01ec71, 0x01ecab, 0x01ecad, 0x01ecaf, 0x01ecb1, 0x01ecb4, 0x01ed01, 0x01ed2d, 0x01ed2f,
                        0x01ed3d, 0x01f100, 0x01f10c, 0x01fbf0, 0x01fbf9));
    }

    private static void populateGC_ND() {
        SET_ENCODINGS.put("gc=Nd", CodePointSet.createNoDedup(0x000030, 0x000039, 0x000660, 0x000669, 0x0006f0, 0x0006f9, 0x0007c0, 0x0007c9, 0x000966, 0x00096f, 0x0009e6, 0x0009ef, 0x000a66,
                        0x000a6f, 0x000ae6, 0x000aef, 0x000b66, 0x000b6f, 0x000be6, 0x000bef, 0x000c66, 0x000c6f, 0x000ce6, 0x000cef, 0x000d66, 0x000d6f, 0x000de6, 0x000def, 0x000e50, 0x000e59,
                        0x000ed0, 0x000ed9, 0x000f20, 0x000f29, 0x001040, 0x001049, 0x001090, 0x001099, 0x0017e0, 0x0017e9, 0x001810, 0x001819, 0x001946, 0x00194f, 0x0019d0, 0x0019d9, 0x001a80,
                        0x001a89, 0x001a90, 0x001a99, 0x001b50, 0x001b59, 0x001bb0, 0x001bb9, 0x001c40, 0x001c49, 0x001c50, 0x001c59, 0x00a620, 0x00a629, 0x00a8d0, 0x00a8d9, 0x00a900, 0x00a909,
                        0x00a9d0, 0x00a9d9, 0x00a9f0, 0x00a9f9, 0x00aa50, 0x00aa59, 0x00abf0, 0x00abf9, 0x00ff10, 0x00ff19, 0x0104a0, 0x0104a9, 0x010d30, 0x010d39, 0x011066, 0x01106f, 0x0110f0,
                        0x0110f9, 0x011136, 0x01113f, 0x0111d0, 0x0111d9, 0x0112f0, 0x0112f9, 0x011450, 0x011459, 0x0114d0, 0x0114d9, 0x011650, 0x011659, 0x0116c0, 0x0116c9, 0x011730, 0x011739,
                        0x0118e0, 0x0118e9, 0x011950, 0x011959, 0x011c50, 0x011c59, 0x011d50, 0x011d59, 0x011da0, 0x011da9, 0x011f50, 0x011f59, 0x016a60, 0x016a69, 0x016ac0, 0x016ac9, 0x016b50,
                        0x016b59, 0x01d7ce, 0x01d7ff, 0x01e140, 0x01e149, 0x01e2f0, 0x01e2f9, 0x01e4f0, 0x01e4f9, 0x01e950, 0x01e959, 0x01fbf0, 0x01fbf9));
    }

    private static void populateGC_NL() {
        SET_ENCODINGS.put("gc=Nl", CodePointSet.createNoDedup(0x0016ee, 0x0016f0, 0x002160, 0x002182, 0x002185, 0x002188, 0x003007, 0x003007, 0x003021, 0x003029, 0x003038, 0x00303a, 0x00a6e6,
                        0x00a6ef, 0x010140, 0x010174, 0x010341, 0x010341, 0x01034a, 0x01034a, 0x0103d1, 0x0103d5, 0x012400, 0x01246e));
    }

    private static void populateGC_NO() {
        SET_ENCODINGS.put("gc=No", CodePointSet.createNoDedup(0x0000b2, 0x0000b3, 0x0000b9, 0x0000b9, 0x0000bc, 0x0000be, 0x0009f4, 0x0009f9, 0x000b72, 0x000b77, 0x000bf0, 0x000bf2, 0x000c78,
                        0x000c7e, 0x000d58, 0x000d5e, 0x000d70, 0x000d78, 0x000f2a, 0x000f33, 0x001369, 0x00137c, 0x0017f0, 0x0017f9, 0x0019da, 0x0019da, 0x002070, 0x002070, 0x002074, 0x002079,
                        0x002080, 0x002089, 0x002150, 0x00215f, 0x002189, 0x002189, 0x002460, 0x00249b, 0x0024ea, 0x0024ff, 0x002776, 0x002793, 0x002cfd, 0x002cfd, 0x003192, 0x003195, 0x003220,
                        0x003229, 0x003248, 0x00324f, 0x003251, 0x00325f, 0x003280, 0x003289, 0x0032b1, 0x0032bf, 0x00a830, 0x00a835, 0x010107, 0x010133, 0x010175, 0x010178, 0x01018a, 0x01018b,
                        0x0102e1, 0x0102fb, 0x010320, 0x010323, 0x010858, 0x01085f, 0x010879, 0x01087f, 0x0108a7, 0x0108af, 0x0108fb, 0x0108ff, 0x010916, 0x01091b, 0x0109bc, 0x0109bd, 0x0109c0,
                        0x0109cf, 0x0109d2, 0x0109ff, 0x010a40, 0x010a48, 0x010a7d, 0x010a7e, 0x010a9d, 0x010a9f, 0x010aeb, 0x010aef, 0x010b58, 0x010b5f, 0x010b78, 0x010b7f, 0x010ba9, 0x010baf,
                        0x010cfa, 0x010cff, 0x010e60, 0x010e7e, 0x010f1d, 0x010f26, 0x010f51, 0x010f54, 0x010fc5, 0x010fcb, 0x011052, 0x011065, 0x0111e1, 0x0111f4, 0x01173a, 0x01173b, 0x0118ea,
                        0x0118f2, 0x011c5a, 0x011c6c, 0x011fc0, 0x011fd4, 0x016b5b, 0x016b61, 0x016e80, 0x016e96, 0x01d2c0, 0x01d2d3, 0x01d2e0, 0x01d2f3, 0x01d360, 0x01d378, 0x01e8c7, 0x01e8cf,
                        0x01ec71, 0x01ecab, 0x01ecad, 0x01ecaf, 0x01ecb1, 0x01ecb4, 0x01ed01, 0x01ed2d, 0x01ed2f, 0x01ed3d, 0x01f100, 0x01f10c));
    }

    private static void populateGC_P() {
        SET_ENCODINGS.put("gc=P", CodePointSet.createNoDedup(0x000021, 0x000023, 0x000025, 0x00002a, 0x00002c, 0x00002f, 0x00003a, 0x00003b, 0x00003f, 0x000040, 0x00005b, 0x00005d, 0x00005f, 0x00005f,
                        0x00007b, 0x00007b, 0x00007d, 0x00007d, 0x0000a1, 0x0000a1, 0x0000a7, 0x0000a7, 0x0000ab, 0x0000ab, 0x0000b6, 0x0000b7, 0x0000bb, 0x0000bb, 0x0000bf, 0x0000bf, 0x00037e,
                        0x00037e, 0x000387, 0x000387, 0x00055a, 0x00055f, 0x000589, 0x00058a, 0x0005be, 0x0005be, 0x0005c0, 0x0005c0, 0x0005c3, 0x0005c3, 0x0005c6, 0x0005c6, 0x0005f3, 0x0005f4,
                        0x000609, 0x00060a, 0x00060c, 0x00060d, 0x00061b, 0x00061b, 0x00061d, 0x00061f, 0x00066a, 0x00066d, 0x0006d4, 0x0006d4, 0x000700, 0x00070d, 0x0007f7, 0x0007f9, 0x000830,
                        0x00083e, 0x00085e, 0x00085e, 0x000964, 0x000965, 0x000970, 0x000970, 0x0009fd, 0x0009fd, 0x000a76, 0x000a76, 0x000af0, 0x000af0, 0x000c77, 0x000c77, 0x000c84, 0x000c84,
                        0x000df4, 0x000df4, 0x000e4f, 0x000e4f, 0x000e5a, 0x000e5b, 0x000f04, 0x000f12, 0x000f14, 0x000f14, 0x000f3a, 0x000f3d, 0x000f85, 0x000f85, 0x000fd0, 0x000fd4, 0x000fd9,
                        0x000fda, 0x00104a, 0x00104f, 0x0010fb, 0x0010fb, 0x001360, 0x001368, 0x001400, 0x001400, 0x00166e, 0x00166e, 0x00169b, 0x00169c, 0x0016eb, 0x0016ed, 0x001735, 0x001736,
                        0x0017d4, 0x0017d6, 0x0017d8, 0x0017da, 0x001800, 0x00180a, 0x001944, 0x001945, 0x001a1e, 0x001a1f, 0x001aa0, 0x001aa6, 0x001aa8, 0x001aad, 0x001b5a, 0x001b60, 0x001b7d,
                        0x001b7e, 0x001bfc, 0x001bff, 0x001c3b, 0x001c3f, 0x001c7e, 0x001c7f, 0x001cc0, 0x001cc7, 0x001cd3, 0x001cd3, 0x002010, 0x002027, 0x002030, 0x002043, 0x002045, 0x002051,
                        0x002053, 0x00205e, 0x00207d, 0x00207e, 0x00208d, 0x00208e, 0x002308, 0x00230b, 0x002329, 0x00232a, 0x002768, 0x002775, 0x0027c5, 0x0027c6, 0x0027e6, 0x0027ef, 0x002983,
                        0x002998, 0x0029d8, 0x0029db, 0x0029fc, 0x0029fd, 0x002cf9, 0x002cfc, 0x002cfe, 0x002cff, 0x002d70, 0x002d70, 0x002e00, 0x002e2e, 0x002e30, 0x002e4f, 0x002e52, 0x002e5d,
                        0x003001, 0x003003, 0x003008, 0x003011, 0x003014, 0x00301f, 0x003030, 0x003030, 0x00303d, 0x00303d, 0x0030a0, 0x0030a0, 0x0030fb, 0x0030fb, 0x00a4fe, 0x00a4ff, 0x00a60d,
                        0x00a60f, 0x00a673, 0x00a673, 0x00a67e, 0x00a67e, 0x00a6f2, 0x00a6f7, 0x00a874, 0x00a877, 0x00a8ce, 0x00a8cf, 0x00a8f8, 0x00a8fa, 0x00a8fc, 0x00a8fc, 0x00a92e, 0x00a92f,
                        0x00a95f, 0x00a95f, 0x00a9c1, 0x00a9cd, 0x00a9de, 0x00a9df, 0x00aa5c, 0x00aa5f, 0x00aade, 0x00aadf, 0x00aaf0, 0x00aaf1, 0x00abeb, 0x00abeb, 0x00fd3e, 0x00fd3f, 0x00fe10,
                        0x00fe19, 0x00fe30, 0x00fe52, 0x00fe54, 0x00fe61, 0x00fe63, 0x00fe63, 0x00fe68, 0x00fe68, 0x00fe6a, 0x00fe6b, 0x00ff01, 0x00ff03, 0x00ff05, 0x00ff0a, 0x00ff0c, 0x00ff0f,
                        0x00ff1a, 0x00ff1b, 0x00ff1f, 0x00ff20, 0x00ff3b, 0x00ff3d, 0x00ff3f, 0x00ff3f, 0x00ff5b, 0x00ff5b, 0x00ff5d, 0x00ff5d, 0x00ff5f, 0x00ff65, 0x010100, 0x010102, 0x01039f,
                        0x01039f, 0x0103d0, 0x0103d0, 0x01056f, 0x01056f, 0x010857, 0x010857, 0x01091f, 0x01091f, 0x01093f, 0x01093f, 0x010a50, 0x010a58, 0x010a7f, 0x010a7f, 0x010af0, 0x010af6,
                        0x010b39, 0x010b3f, 0x010b99, 0x010b9c, 0x010ead, 0x010ead, 0x010f55, 0x010f59, 0x010f86, 0x010f89, 0x011047, 0x01104d, 0x0110bb, 0x0110bc, 0x0110be, 0x0110c1, 0x011140,
                        0x011143, 0x011174, 0x011175, 0x0111c5, 0x0111c8, 0x0111cd, 0x0111cd, 0x0111db, 0x0111db, 0x0111dd, 0x0111df, 0x011238, 0x01123d, 0x0112a9, 0x0112a9, 0x01144b, 0x01144f,
                        0x01145a, 0x01145b, 0x01145d, 0x01145d, 0x0114c6, 0x0114c6, 0x0115c1, 0x0115d7, 0x011641, 0x011643, 0x011660, 0x01166c, 0x0116b9, 0x0116b9, 0x01173c, 0x01173e, 0x01183b,
                        0x01183b, 0x011944, 0x011946, 0x0119e2, 0x0119e2, 0x011a3f, 0x011a46, 0x011a9a, 0x011a9c, 0x011a9e, 0x011aa2, 0x011b00, 0x011b09, 0x011c41, 0x011c45, 0x011c70, 0x011c71,
                        0x011ef7, 0x011ef8, 0x011f43, 0x011f4f, 0x011fff, 0x011fff, 0x012470, 0x012474, 0x012ff1, 0x012ff2, 0x016a6e, 0x016a6f, 0x016af5, 0x016af5, 0x016b37, 0x016b3b, 0x016b44,
                        0x016b44, 0x016e97, 0x016e9a, 0x016fe2, 0x016fe2, 0x01bc9f, 0x01bc9f, 0x01da87, 0x01da8b, 0x01e95e, 0x01e95f));
    }

    private static void populateGC_PC() {
        SET_ENCODINGS.put("gc=Pc", CodePointSet.createNoDedup(0x00005f, 0x00005f, 0x00203f, 0x002040, 0x002054, 0x002054, 0x00fe33, 0x00fe34, 0x00fe4d, 0x00fe4f, 0x00ff3f, 0x00ff3f));
    }

    private static void populateGC_PD() {
        SET_ENCODINGS.put("gc=Pd",
                        CodePointSet.createNoDedup(0x00002d, 0x00002d, 0x00058a, 0x00058a, 0x0005be, 0x0005be, 0x001400, 0x001400, 0x001806, 0x001806, 0x002010, 0x002015, 0x002e17, 0x002e17, 0x002e1a,
                                        0x002e1a, 0x002e3a, 0x002e3b, 0x002e40, 0x002e40, 0x002e5d, 0x002e5d, 0x00301c, 0x00301c, 0x003030, 0x003030, 0x0030a0, 0x0030a0, 0x00fe31, 0x00fe32, 0x00fe58,
                                        0x00fe58, 0x00fe63, 0x00fe63, 0x00ff0d, 0x00ff0d, 0x010ead, 0x010ead));
    }

    private static void populateGC_PE() {
        SET_ENCODINGS.put("gc=Pe",
                        CodePointSet.createNoDedup(0x000029, 0x000029, 0x00005d, 0x00005d, 0x00007d, 0x00007d, 0x000f3b, 0x000f3b, 0x000f3d, 0x000f3d, 0x00169c, 0x00169c, 0x002046, 0x002046, 0x00207e,
                                        0x00207e, 0x00208e, 0x00208e, 0x002309, 0x002309, 0x00230b, 0x00230b, 0x00232a, 0x00232a, 0x002769, 0x002769, 0x00276b, 0x00276b, 0x00276d, 0x00276d, 0x00276f,
                                        0x00276f, 0x002771, 0x002771, 0x002773, 0x002773, 0x002775, 0x002775, 0x0027c6, 0x0027c6, 0x0027e7, 0x0027e7, 0x0027e9, 0x0027e9, 0x0027eb, 0x0027eb, 0x0027ed,
                                        0x0027ed, 0x0027ef, 0x0027ef, 0x002984, 0x002984, 0x002986, 0x002986, 0x002988, 0x002988, 0x00298a, 0x00298a, 0x00298c, 0x00298c, 0x00298e, 0x00298e, 0x002990,
                                        0x002990, 0x002992, 0x002992, 0x002994, 0x002994, 0x002996, 0x002996, 0x002998, 0x002998, 0x0029d9, 0x0029d9, 0x0029db, 0x0029db, 0x0029fd, 0x0029fd, 0x002e23,
                                        0x002e23, 0x002e25, 0x002e25, 0x002e27, 0x002e27, 0x002e29, 0x002e29, 0x002e56, 0x002e56, 0x002e58, 0x002e58, 0x002e5a, 0x002e5a, 0x002e5c, 0x002e5c, 0x003009,
                                        0x003009, 0x00300b, 0x00300b, 0x00300d, 0x00300d, 0x00300f, 0x00300f, 0x003011, 0x003011, 0x003015, 0x003015, 0x003017, 0x003017, 0x003019, 0x003019, 0x00301b,
                                        0x00301b, 0x00301e, 0x00301f, 0x00fd3e, 0x00fd3e, 0x00fe18, 0x00fe18, 0x00fe36, 0x00fe36, 0x00fe38, 0x00fe38, 0x00fe3a, 0x00fe3a, 0x00fe3c, 0x00fe3c, 0x00fe3e,
                                        0x00fe3e, 0x00fe40, 0x00fe40, 0x00fe42, 0x00fe42, 0x00fe44, 0x00fe44, 0x00fe48, 0x00fe48, 0x00fe5a, 0x00fe5a, 0x00fe5c, 0x00fe5c, 0x00fe5e, 0x00fe5e, 0x00ff09,
                                        0x00ff09, 0x00ff3d, 0x00ff3d, 0x00ff5d, 0x00ff5d, 0x00ff60, 0x00ff60, 0x00ff63, 0x00ff63));
    }

    private static void populateGC_PF() {
        SET_ENCODINGS.put("gc=Pf", CodePointSet.createNoDedup(0x0000bb, 0x0000bb, 0x002019, 0x002019, 0x00201d, 0x00201d, 0x00203a, 0x00203a, 0x002e03, 0x002e03, 0x002e05, 0x002e05, 0x002e0a,
                        0x002e0a, 0x002e0d, 0x002e0d, 0x002e1d, 0x002e1d, 0x002e21, 0x002e21));
    }

    private static void populateGC_PI() {
        SET_ENCODINGS.put("gc=Pi", CodePointSet.createNoDedup(0x0000ab, 0x0000ab, 0x002018, 0x002018, 0x00201b, 0x00201c, 0x00201f, 0x00201f, 0x002039, 0x002039, 0x002e02, 0x002e02, 0x002e04,
                        0x002e04, 0x002e09, 0x002e09, 0x002e0c, 0x002e0c, 0x002e1c, 0x002e1c, 0x002e20, 0x002e20));
    }

    private static void populateGC_PO() {
        SET_ENCODINGS.put("gc=Po", CodePointSet.createNoDedup(0x000021, 0x000023, 0x000025, 0x000027, 0x00002a, 0x00002a, 0x00002c, 0x00002c, 0x00002e, 0x00002f, 0x00003a, 0x00003b, 0x00003f,
                        0x000040, 0x00005c, 0x00005c, 0x0000a1, 0x0000a1, 0x0000a7, 0x0000a7, 0x0000b6, 0x0000b7, 0x0000bf, 0x0000bf, 0x00037e, 0x00037e, 0x000387, 0x000387, 0x00055a, 0x00055f,
                        0x000589, 0x000589, 0x0005c0, 0x0005c0, 0x0005c3, 0x0005c3, 0x0005c6, 0x0005c6, 0x0005f3, 0x0005f4, 0x000609, 0x00060a, 0x00060c, 0x00060d, 0x00061b, 0x00061b, 0x00061d,
                        0x00061f, 0x00066a, 0x00066d, 0x0006d4, 0x0006d4, 0x000700, 0x00070d, 0x0007f7, 0x0007f9, 0x000830, 0x00083e, 0x00085e, 0x00085e, 0x000964, 0x000965, 0x000970, 0x000970,
                        0x0009fd, 0x0009fd, 0x000a76, 0x000a76, 0x000af0, 0x000af0, 0x000c77, 0x000c77, 0x000c84, 0x000c84, 0x000df4, 0x000df4, 0x000e4f, 0x000e4f, 0x000e5a, 0x000e5b, 0x000f04,
                        0x000f12, 0x000f14, 0x000f14, 0x000f85, 0x000f85, 0x000fd0, 0x000fd4, 0x000fd9, 0x000fda, 0x00104a, 0x00104f, 0x0010fb, 0x0010fb, 0x001360, 0x001368, 0x00166e, 0x00166e,
                        0x0016eb, 0x0016ed, 0x001735, 0x001736, 0x0017d4, 0x0017d6, 0x0017d8, 0x0017da, 0x001800, 0x001805, 0x001807, 0x00180a, 0x001944, 0x001945, 0x001a1e, 0x001a1f, 0x001aa0,
                        0x001aa6, 0x001aa8, 0x001aad, 0x001b5a, 0x001b60, 0x001b7d, 0x001b7e, 0x001bfc, 0x001bff, 0x001c3b, 0x001c3f, 0x001c7e, 0x001c7f, 0x001cc0, 0x001cc7, 0x001cd3, 0x001cd3,
                        0x002016, 0x002017, 0x002020, 0x002027, 0x002030, 0x002038, 0x00203b, 0x00203e, 0x002041, 0x002043, 0x002047, 0x002051, 0x002053, 0x002053, 0x002055, 0x00205e, 0x002cf9,
                        0x002cfc, 0x002cfe, 0x002cff, 0x002d70, 0x002d70, 0x002e00, 0x002e01, 0x002e06, 0x002e08, 0x002e0b, 0x002e0b, 0x002e0e, 0x002e16, 0x002e18, 0x002e19, 0x002e1b, 0x002e1b,
                        0x002e1e, 0x002e1f, 0x002e2a, 0x002e2e, 0x002e30, 0x002e39, 0x002e3c, 0x002e3f, 0x002e41, 0x002e41, 0x002e43, 0x002e4f, 0x002e52, 0x002e54, 0x003001, 0x003003, 0x00303d,
                        0x00303d, 0x0030fb, 0x0030fb, 0x00a4fe, 0x00a4ff, 0x00a60d, 0x00a60f, 0x00a673, 0x00a673, 0x00a67e, 0x00a67e, 0x00a6f2, 0x00a6f7, 0x00a874, 0x00a877, 0x00a8ce, 0x00a8cf,
                        0x00a8f8, 0x00a8fa, 0x00a8fc, 0x00a8fc, 0x00a92e, 0x00a92f, 0x00a95f, 0x00a95f, 0x00a9c1, 0x00a9cd, 0x00a9de, 0x00a9df, 0x00aa5c, 0x00aa5f, 0x00aade, 0x00aadf, 0x00aaf0,
                        0x00aaf1, 0x00abeb, 0x00abeb, 0x00fe10, 0x00fe16, 0x00fe19, 0x00fe19, 0x00fe30, 0x00fe30, 0x00fe45, 0x00fe46, 0x00fe49, 0x00fe4c, 0x00fe50, 0x00fe52, 0x00fe54, 0x00fe57,
                        0x00fe5f, 0x00fe61, 0x00fe68, 0x00fe68, 0x00fe6a, 0x00fe6b, 0x00ff01, 0x00ff03, 0x00ff05, 0x00ff07, 0x00ff0a, 0x00ff0a, 0x00ff0c, 0x00ff0c, 0x00ff0e, 0x00ff0f, 0x00ff1a,
                        0x00ff1b, 0x00ff1f, 0x00ff20, 0x00ff3c, 0x00ff3c, 0x00ff61, 0x00ff61, 0x00ff64, 0x00ff65, 0x010100, 0x010102, 0x01039f, 0x01039f, 0x0103d0, 0x0103d0, 0x01056f, 0x01056f,
                        0x010857, 0x010857, 0x01091f, 0x01091f, 0x01093f, 0x01093f, 0x010a50, 0x010a58, 0x010a7f, 0x010a7f, 0x010af0, 0x010af6, 0x010b39, 0x010b3f, 0x010b99, 0x010b9c, 0x010f55,
                        0x010f59, 0x010f86, 0x010f89, 0x011047, 0x01104d, 0x0110bb, 0x0110bc, 0x0110be, 0x0110c1, 0x011140, 0x011143, 0x011174, 0x011175, 0x0111c5, 0x0111c8, 0x0111cd, 0x0111cd,
                        0x0111db, 0x0111db, 0x0111dd, 0x0111df, 0x011238, 0x01123d, 0x0112a9, 0x0112a9, 0x01144b, 0x01144f, 0x01145a, 0x01145b, 0x01145d, 0x01145d, 0x0114c6, 0x0114c6, 0x0115c1,
                        0x0115d7, 0x011641, 0x011643, 0x011660, 0x01166c, 0x0116b9, 0x0116b9, 0x01173c, 0x01173e, 0x01183b, 0x01183b, 0x011944, 0x011946, 0x0119e2, 0x0119e2, 0x011a3f, 0x011a46,
                        0x011a9a, 0x011a9c, 0x011a9e, 0x011aa2, 0x011b00, 0x011b09, 0x011c41, 0x011c45, 0x011c70, 0x011c71, 0x011ef7, 0x011ef8, 0x011f43, 0x011f4f, 0x011fff, 0x011fff, 0x012470,
                        0x012474, 0x012ff1, 0x012ff2, 0x016a6e, 0x016a6f, 0x016af5, 0x016af5, 0x016b37, 0x016b3b, 0x016b44, 0x016b44, 0x016e97, 0x016e9a, 0x016fe2, 0x016fe2, 0x01bc9f, 0x01bc9f,
                        0x01da87, 0x01da8b, 0x01e95e, 0x01e95f));
    }

    private static void populateGC_PS() {
        SET_ENCODINGS.put("gc=Ps",
                        CodePointSet.createNoDedup(0x000028, 0x000028, 0x00005b, 0x00005b, 0x00007b, 0x00007b, 0x000f3a, 0x000f3a, 0x000f3c, 0x000f3c, 0x00169b, 0x00169b, 0x00201a, 0x00201a, 0x00201e,
                                        0x00201e, 0x002045, 0x002045, 0x00207d, 0x00207d, 0x00208d, 0x00208d, 0x002308, 0x002308, 0x00230a, 0x00230a, 0x002329, 0x002329, 0x002768, 0x002768, 0x00276a,
                                        0x00276a, 0x00276c, 0x00276c, 0x00276e, 0x00276e, 0x002770, 0x002770, 0x002772, 0x002772, 0x002774, 0x002774, 0x0027c5, 0x0027c5, 0x0027e6, 0x0027e6, 0x0027e8,
                                        0x0027e8, 0x0027ea, 0x0027ea, 0x0027ec, 0x0027ec, 0x0027ee, 0x0027ee, 0x002983, 0x002983, 0x002985, 0x002985, 0x002987, 0x002987, 0x002989, 0x002989, 0x00298b,
                                        0x00298b, 0x00298d, 0x00298d, 0x00298f, 0x00298f, 0x002991, 0x002991, 0x002993, 0x002993, 0x002995, 0x002995, 0x002997, 0x002997, 0x0029d8, 0x0029d8, 0x0029da,
                                        0x0029da, 0x0029fc, 0x0029fc, 0x002e22, 0x002e22, 0x002e24, 0x002e24, 0x002e26, 0x002e26, 0x002e28, 0x002e28, 0x002e42, 0x002e42, 0x002e55, 0x002e55, 0x002e57,
                                        0x002e57, 0x002e59, 0x002e59, 0x002e5b, 0x002e5b, 0x003008, 0x003008, 0x00300a, 0x00300a, 0x00300c, 0x00300c, 0x00300e, 0x00300e, 0x003010, 0x003010, 0x003014,
                                        0x003014, 0x003016, 0x003016, 0x003018, 0x003018, 0x00301a, 0x00301a, 0x00301d, 0x00301d, 0x00fd3f, 0x00fd3f, 0x00fe17, 0x00fe17, 0x00fe35, 0x00fe35, 0x00fe37,
                                        0x00fe37, 0x00fe39, 0x00fe39, 0x00fe3b, 0x00fe3b, 0x00fe3d, 0x00fe3d, 0x00fe3f, 0x00fe3f, 0x00fe41, 0x00fe41, 0x00fe43, 0x00fe43, 0x00fe47, 0x00fe47, 0x00fe59,
                                        0x00fe59, 0x00fe5b, 0x00fe5b, 0x00fe5d, 0x00fe5d, 0x00ff08, 0x00ff08, 0x00ff3b, 0x00ff3b, 0x00ff5b, 0x00ff5b, 0x00ff5f, 0x00ff5f, 0x00ff62, 0x00ff62));
    }

    private static void populateGC_S() {
        SET_ENCODINGS.put("gc=S", CodePointSet.createNoDedup(0x000024, 0x000024, 0x00002b, 0x00002b, 0x00003c, 0x00003e, 0x00005e, 0x00005e, 0x000060, 0x000060, 0x00007c, 0x00007c, 0x00007e, 0x00007e,
                        0x0000a2, 0x0000a6, 0x0000a8, 0x0000a9, 0x0000ac, 0x0000ac, 0x0000ae, 0x0000b1, 0x0000b4, 0x0000b4, 0x0000b8, 0x0000b8, 0x0000d7, 0x0000d7, 0x0000f7, 0x0000f7, 0x0002c2,
                        0x0002c5, 0x0002d2, 0x0002df, 0x0002e5, 0x0002eb, 0x0002ed, 0x0002ed, 0x0002ef, 0x0002ff, 0x000375, 0x000375, 0x000384, 0x000385, 0x0003f6, 0x0003f6, 0x000482, 0x000482,
                        0x00058d, 0x00058f, 0x000606, 0x000608, 0x00060b, 0x00060b, 0x00060e, 0x00060f, 0x0006de, 0x0006de, 0x0006e9, 0x0006e9, 0x0006fd, 0x0006fe, 0x0007f6, 0x0007f6, 0x0007fe,
                        0x0007ff, 0x000888, 0x000888, 0x0009f2, 0x0009f3, 0x0009fa, 0x0009fb, 0x000af1, 0x000af1, 0x000b70, 0x000b70, 0x000bf3, 0x000bfa, 0x000c7f, 0x000c7f, 0x000d4f, 0x000d4f,
                        0x000d79, 0x000d79, 0x000e3f, 0x000e3f, 0x000f01, 0x000f03, 0x000f13, 0x000f13, 0x000f15, 0x000f17, 0x000f1a, 0x000f1f, 0x000f34, 0x000f34, 0x000f36, 0x000f36, 0x000f38,
                        0x000f38, 0x000fbe, 0x000fc5, 0x000fc7, 0x000fcc, 0x000fce, 0x000fcf, 0x000fd5, 0x000fd8, 0x00109e, 0x00109f, 0x001390, 0x001399, 0x00166d, 0x00166d, 0x0017db, 0x0017db,
                        0x001940, 0x001940, 0x0019de, 0x0019ff, 0x001b61, 0x001b6a, 0x001b74, 0x001b7c, 0x001fbd, 0x001fbd, 0x001fbf, 0x001fc1, 0x001fcd, 0x001fcf, 0x001fdd, 0x001fdf, 0x001fed,
                        0x001fef, 0x001ffd, 0x001ffe, 0x002044, 0x002044, 0x002052, 0x002052, 0x00207a, 0x00207c, 0x00208a, 0x00208c, 0x0020a0, 0x0020c0, 0x002100, 0x002101, 0x002103, 0x002106,
                        0x002108, 0x002109, 0x002114, 0x002114, 0x002116, 0x002118, 0x00211e, 0x002123, 0x002125, 0x002125, 0x002127, 0x002127, 0x002129, 0x002129, 0x00212e, 0x00212e, 0x00213a,
                        0x00213b, 0x002140, 0x002144, 0x00214a, 0x00214d, 0x00214f, 0x00214f, 0x00218a, 0x00218b, 0x002190, 0x002307, 0x00230c, 0x002328, 0x00232b, 0x002426, 0x002440, 0x00244a,
                        0x00249c, 0x0024e9, 0x002500, 0x002767, 0x002794, 0x0027c4, 0x0027c7, 0x0027e5, 0x0027f0, 0x002982, 0x002999, 0x0029d7, 0x0029dc, 0x0029fb, 0x0029fe, 0x002b73, 0x002b76,
                        0x002b95, 0x002b97, 0x002bff, 0x002ce5, 0x002cea, 0x002e50, 0x002e51, 0x002e80, 0x002e99, 0x002e9b, 0x002ef3, 0x002f00, 0x002fd5, 0x002ff0, 0x002ffb, 0x003004, 0x003004,
                        0x003012, 0x003013, 0x003020, 0x003020, 0x003036, 0x003037, 0x00303e, 0x00303f, 0x00309b, 0x00309c, 0x003190, 0x003191, 0x003196, 0x00319f, 0x0031c0, 0x0031e3, 0x003200,
                        0x00321e, 0x00322a, 0x003247, 0x003250, 0x003250, 0x003260, 0x00327f, 0x00328a, 0x0032b0, 0x0032c0, 0x0033ff, 0x004dc0, 0x004dff, 0x00a490, 0x00a4c6, 0x00a700, 0x00a716,
                        0x00a720, 0x00a721, 0x00a789, 0x00a78a, 0x00a828, 0x00a82b, 0x00a836, 0x00a839, 0x00aa77, 0x00aa79, 0x00ab5b, 0x00ab5b, 0x00ab6a, 0x00ab6b, 0x00fb29, 0x00fb29, 0x00fbb2,
                        0x00fbc2, 0x00fd40, 0x00fd4f, 0x00fdcf, 0x00fdcf, 0x00fdfc, 0x00fdff, 0x00fe62, 0x00fe62, 0x00fe64, 0x00fe66, 0x00fe69, 0x00fe69, 0x00ff04, 0x00ff04, 0x00ff0b, 0x00ff0b,
                        0x00ff1c, 0x00ff1e, 0x00ff3e, 0x00ff3e, 0x00ff40, 0x00ff40, 0x00ff5c, 0x00ff5c, 0x00ff5e, 0x00ff5e, 0x00ffe0, 0x00ffe6, 0x00ffe8, 0x00ffee, 0x00fffc, 0x00fffd, 0x010137,
                        0x01013f, 0x010179, 0x010189, 0x01018c, 0x01018e, 0x010190, 0x01019c, 0x0101a0, 0x0101a0, 0x0101d0, 0x0101fc, 0x010877, 0x010878, 0x010ac8, 0x010ac8, 0x01173f, 0x01173f,
                        0x011fd5, 0x011ff1, 0x016b3c, 0x016b3f, 0x016b45, 0x016b45, 0x01bc9c, 0x01bc9c, 0x01cf50, 0x01cfc3, 0x01d000, 0x01d0f5, 0x01d100, 0x01d126, 0x01d129, 0x01d164, 0x01d16a,
                        0x01d16c, 0x01d183, 0x01d184, 0x01d18c, 0x01d1a9, 0x01d1ae, 0x01d1ea, 0x01d200, 0x01d241, 0x01d245, 0x01d245, 0x01d300, 0x01d356, 0x01d6c1, 0x01d6c1, 0x01d6db, 0x01d6db,
                        0x01d6fb, 0x01d6fb, 0x01d715, 0x01d715, 0x01d735, 0x01d735, 0x01d74f, 0x01d74f, 0x01d76f, 0x01d76f, 0x01d789, 0x01d789, 0x01d7a9, 0x01d7a9, 0x01d7c3, 0x01d7c3, 0x01d800,
                        0x01d9ff, 0x01da37, 0x01da3a, 0x01da6d, 0x01da74, 0x01da76, 0x01da83, 0x01da85, 0x01da86, 0x01e14f, 0x01e14f, 0x01e2ff, 0x01e2ff, 0x01ecac, 0x01ecac, 0x01ecb0, 0x01ecb0,
                        0x01ed2e, 0x01ed2e, 0x01eef0, 0x01eef1, 0x01f000, 0x01f02b, 0x01f030, 0x01f093, 0x01f0a0, 0x01f0ae, 0x01f0b1, 0x01f0bf, 0x01f0c1, 0x01f0cf, 0x01f0d1, 0x01f0f5, 0x01f10d,
                        0x01f1ad, 0x01f1e6, 0x01f202, 0x01f210, 0x01f23b, 0x01f240, 0x01f248, 0x01f250, 0x01f251, 0x01f260, 0x01f265, 0x01f300, 0x01f6d7, 0x01f6dc, 0x01f6ec, 0x01f6f0, 0x01f6fc,
                        0x01f700, 0x01f776, 0x01f77b, 0x01f7d9, 0x01f7e0, 0x01f7eb, 0x01f7f0, 0x01f7f0, 0x01f800, 0x01f80b, 0x01f810, 0x01f847, 0x01f850, 0x01f859, 0x01f860, 0x01f887, 0x01f890,
                        0x01f8ad, 0x01f8b0, 0x01f8b1, 0x01f900, 0x01fa53, 0x01fa60, 0x01fa6d, 0x01fa70, 0x01fa7c, 0x01fa80, 0x01fa88, 0x01fa90, 0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb,
                        0x01fae0, 0x01fae8, 0x01faf0, 0x01faf8, 0x01fb00, 0x01fb92, 0x01fb94, 0x01fbca));
    }

    private static void populateGC_SC() {
        SET_ENCODINGS.put("gc=Sc",
                        CodePointSet.createNoDedup(0x000024, 0x000024, 0x0000a2, 0x0000a5, 0x00058f, 0x00058f, 0x00060b, 0x00060b, 0x0007fe, 0x0007ff, 0x0009f2, 0x0009f3, 0x0009fb, 0x0009fb, 0x000af1,
                                        0x000af1, 0x000bf9, 0x000bf9, 0x000e3f, 0x000e3f, 0x0017db, 0x0017db, 0x0020a0, 0x0020c0, 0x00a838, 0x00a838, 0x00fdfc, 0x00fdfc, 0x00fe69, 0x00fe69, 0x00ff04,
                                        0x00ff04, 0x00ffe0, 0x00ffe1, 0x00ffe5, 0x00ffe6, 0x011fdd, 0x011fe0, 0x01e2ff, 0x01e2ff, 0x01ecb0, 0x01ecb0));
    }

    private static void populateGC_SK() {
        SET_ENCODINGS.put("gc=Sk",
                        CodePointSet.createNoDedup(0x00005e, 0x00005e, 0x000060, 0x000060, 0x0000a8, 0x0000a8, 0x0000af, 0x0000af, 0x0000b4, 0x0000b4, 0x0000b8, 0x0000b8, 0x0002c2, 0x0002c5, 0x0002d2,
                                        0x0002df, 0x0002e5, 0x0002eb, 0x0002ed, 0x0002ed, 0x0002ef, 0x0002ff, 0x000375, 0x000375, 0x000384, 0x000385, 0x000888, 0x000888, 0x001fbd, 0x001fbd, 0x001fbf,
                                        0x001fc1, 0x001fcd, 0x001fcf, 0x001fdd, 0x001fdf, 0x001fed, 0x001fef, 0x001ffd, 0x001ffe, 0x00309b, 0x00309c, 0x00a700, 0x00a716, 0x00a720, 0x00a721, 0x00a789,
                                        0x00a78a, 0x00ab5b, 0x00ab5b, 0x00ab6a, 0x00ab6b, 0x00fbb2, 0x00fbc2, 0x00ff3e, 0x00ff3e, 0x00ff40, 0x00ff40, 0x00ffe3, 0x00ffe3, 0x01f3fb, 0x01f3ff));
    }

    private static void populateGC_SM() {
        SET_ENCODINGS.put("gc=Sm", CodePointSet.createNoDedup(0x00002b, 0x00002b, 0x00003c, 0x00003e, 0x00007c, 0x00007c, 0x00007e, 0x00007e, 0x0000ac, 0x0000ac, 0x0000b1, 0x0000b1, 0x0000d7,
                        0x0000d7, 0x0000f7, 0x0000f7, 0x0003f6, 0x0003f6, 0x000606, 0x000608, 0x002044, 0x002044, 0x002052, 0x002052, 0x00207a, 0x00207c, 0x00208a, 0x00208c, 0x002118, 0x002118,
                        0x002140, 0x002144, 0x00214b, 0x00214b, 0x002190, 0x002194, 0x00219a, 0x00219b, 0x0021a0, 0x0021a0, 0x0021a3, 0x0021a3, 0x0021a6, 0x0021a6, 0x0021ae, 0x0021ae, 0x0021ce,
                        0x0021cf, 0x0021d2, 0x0021d2, 0x0021d4, 0x0021d4, 0x0021f4, 0x0022ff, 0x002320, 0x002321, 0x00237c, 0x00237c, 0x00239b, 0x0023b3, 0x0023dc, 0x0023e1, 0x0025b7, 0x0025b7,
                        0x0025c1, 0x0025c1, 0x0025f8, 0x0025ff, 0x00266f, 0x00266f, 0x0027c0, 0x0027c4, 0x0027c7, 0x0027e5, 0x0027f0, 0x0027ff, 0x002900, 0x002982, 0x002999, 0x0029d7, 0x0029dc,
                        0x0029fb, 0x0029fe, 0x002aff, 0x002b30, 0x002b44, 0x002b47, 0x002b4c, 0x00fb29, 0x00fb29, 0x00fe62, 0x00fe62, 0x00fe64, 0x00fe66, 0x00ff0b, 0x00ff0b, 0x00ff1c, 0x00ff1e,
                        0x00ff5c, 0x00ff5c, 0x00ff5e, 0x00ff5e, 0x00ffe2, 0x00ffe2, 0x00ffe9, 0x00ffec, 0x01d6c1, 0x01d6c1, 0x01d6db, 0x01d6db, 0x01d6fb, 0x01d6fb, 0x01d715, 0x01d715, 0x01d735,
                        0x01d735, 0x01d74f, 0x01d74f, 0x01d76f, 0x01d76f, 0x01d789, 0x01d789, 0x01d7a9, 0x01d7a9, 0x01d7c3, 0x01d7c3, 0x01eef0, 0x01eef1));
    }

    private static void populateGC_SO() {
        SET_ENCODINGS.put("gc=So", CodePointSet.createNoDedup(0x0000a6, 0x0000a6, 0x0000a9, 0x0000a9, 0x0000ae, 0x0000ae, 0x0000b0, 0x0000b0, 0x000482, 0x000482, 0x00058d, 0x00058e, 0x00060e,
                        0x00060f, 0x0006de, 0x0006de, 0x0006e9, 0x0006e9, 0x0006fd, 0x0006fe, 0x0007f6, 0x0007f6, 0x0009fa, 0x0009fa, 0x000b70, 0x000b70, 0x000bf3, 0x000bf8, 0x000bfa, 0x000bfa,
                        0x000c7f, 0x000c7f, 0x000d4f, 0x000d4f, 0x000d79, 0x000d79, 0x000f01, 0x000f03, 0x000f13, 0x000f13, 0x000f15, 0x000f17, 0x000f1a, 0x000f1f, 0x000f34, 0x000f34, 0x000f36,
                        0x000f36, 0x000f38, 0x000f38, 0x000fbe, 0x000fc5, 0x000fc7, 0x000fcc, 0x000fce, 0x000fcf, 0x000fd5, 0x000fd8, 0x00109e, 0x00109f, 0x001390, 0x001399, 0x00166d, 0x00166d,
                        0x001940, 0x001940, 0x0019de, 0x0019ff, 0x001b61, 0x001b6a, 0x001b74, 0x001b7c, 0x002100, 0x002101, 0x002103, 0x002106, 0x002108, 0x002109, 0x002114, 0x002114, 0x002116,
                        0x002117, 0x00211e, 0x002123, 0x002125, 0x002125, 0x002127, 0x002127, 0x002129, 0x002129, 0x00212e, 0x00212e, 0x00213a, 0x00213b, 0x00214a, 0x00214a, 0x00214c, 0x00214d,
                        0x00214f, 0x00214f, 0x00218a, 0x00218b, 0x002195, 0x002199, 0x00219c, 0x00219f, 0x0021a1, 0x0021a2, 0x0021a4, 0x0021a5, 0x0021a7, 0x0021ad, 0x0021af, 0x0021cd, 0x0021d0,
                        0x0021d1, 0x0021d3, 0x0021d3, 0x0021d5, 0x0021f3, 0x002300, 0x002307, 0x00230c, 0x00231f, 0x002322, 0x002328, 0x00232b, 0x00237b, 0x00237d, 0x00239a, 0x0023b4, 0x0023db,
                        0x0023e2, 0x002426, 0x002440, 0x00244a, 0x00249c, 0x0024e9, 0x002500, 0x0025b6, 0x0025b8, 0x0025c0, 0x0025c2, 0x0025f7, 0x002600, 0x00266e, 0x002670, 0x002767, 0x002794,
                        0x0027bf, 0x002800, 0x0028ff, 0x002b00, 0x002b2f, 0x002b45, 0x002b46, 0x002b4d, 0x002b73, 0x002b76, 0x002b95, 0x002b97, 0x002bff, 0x002ce5, 0x002cea, 0x002e50, 0x002e51,
                        0x002e80, 0x002e99, 0x002e9b, 0x002ef3, 0x002f00, 0x002fd5, 0x002ff0, 0x002ffb, 0x003004, 0x003004, 0x003012, 0x003013, 0x003020, 0x003020, 0x003036, 0x003037, 0x00303e,
                        0x00303f, 0x003190, 0x003191, 0x003196, 0x00319f, 0x0031c0, 0x0031e3, 0x003200, 0x00321e, 0x00322a, 0x003247, 0x003250, 0x003250, 0x003260, 0x00327f, 0x00328a, 0x0032b0,
                        0x0032c0, 0x0033ff, 0x004dc0, 0x004dff, 0x00a490, 0x00a4c6, 0x00a828, 0x00a82b, 0x00a836, 0x00a837, 0x00a839, 0x00a839, 0x00aa77, 0x00aa79, 0x00fd40, 0x00fd4f, 0x00fdcf,
                        0x00fdcf, 0x00fdfd, 0x00fdff, 0x00ffe4, 0x00ffe4, 0x00ffe8, 0x00ffe8, 0x00ffed, 0x00ffee, 0x00fffc, 0x00fffd, 0x010137, 0x01013f, 0x010179, 0x010189, 0x01018c, 0x01018e,
                        0x010190, 0x01019c, 0x0101a0, 0x0101a0, 0x0101d0, 0x0101fc, 0x010877, 0x010878, 0x010ac8, 0x010ac8, 0x01173f, 0x01173f, 0x011fd5, 0x011fdc, 0x011fe1, 0x011ff1, 0x016b3c,
                        0x016b3f, 0x016b45, 0x016b45, 0x01bc9c, 0x01bc9c, 0x01cf50, 0x01cfc3, 0x01d000, 0x01d0f5, 0x01d100, 0x01d126, 0x01d129, 0x01d164, 0x01d16a, 0x01d16c, 0x01d183, 0x01d184,
                        0x01d18c, 0x01d1a9, 0x01d1ae, 0x01d1ea, 0x01d200, 0x01d241, 0x01d245, 0x01d245, 0x01d300, 0x01d356, 0x01d800, 0x01d9ff, 0x01da37, 0x01da3a, 0x01da6d, 0x01da74, 0x01da76,
                        0x01da83, 0x01da85, 0x01da86, 0x01e14f, 0x01e14f, 0x01ecac, 0x01ecac, 0x01ed2e, 0x01ed2e, 0x01f000, 0x01f02b, 0x01f030, 0x01f093, 0x01f0a0, 0x01f0ae, 0x01f0b1, 0x01f0bf,
                        0x01f0c1, 0x01f0cf, 0x01f0d1, 0x01f0f5, 0x01f10d, 0x01f1ad, 0x01f1e6, 0x01f202, 0x01f210, 0x01f23b, 0x01f240, 0x01f248, 0x01f250, 0x01f251, 0x01f260, 0x01f265, 0x01f300,
                        0x01f3fa, 0x01f400, 0x01f6d7, 0x01f6dc, 0x01f6ec, 0x01f6f0, 0x01f6fc, 0x01f700, 0x01f776, 0x01f77b, 0x01f7d9, 0x01f7e0, 0x01f7eb, 0x01f7f0, 0x01f7f0, 0x01f800, 0x01f80b,
                        0x01f810, 0x01f847, 0x01f850, 0x01f859, 0x01f860, 0x01f887, 0x01f890, 0x01f8ad, 0x01f8b0, 0x01f8b1, 0x01f900, 0x01fa53, 0x01fa60, 0x01fa6d, 0x01fa70, 0x01fa7c, 0x01fa80,
                        0x01fa88, 0x01fa90, 0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb, 0x01fae0, 0x01fae8, 0x01faf0, 0x01faf8, 0x01fb00, 0x01fb92, 0x01fb94, 0x01fbca));
    }

    private static void populateGC_Z() {
        SET_ENCODINGS.put("gc=Z", CodePointSet.createNoDedup(0x000020, 0x000020, 0x0000a0, 0x0000a0, 0x001680, 0x001680, 0x002000, 0x00200a, 0x002028, 0x002029, 0x00202f, 0x00202f, 0x00205f, 0x00205f,
                        0x003000, 0x003000));
    }

    private static void populateGC_ZL() {
        SET_ENCODINGS.put("gc=Zl", CodePointSet.createNoDedup(0x002028, 0x002028));
    }

    private static void populateGC_ZP() {
        SET_ENCODINGS.put("gc=Zp", CodePointSet.createNoDedup(0x002029, 0x002029));
    }

    private static void populateGC_ZS() {
        SET_ENCODINGS.put("gc=Zs",
                        CodePointSet.createNoDedup(0x000020, 0x000020, 0x0000a0, 0x0000a0, 0x001680, 0x001680, 0x002000, 0x00200a, 0x00202f, 0x00202f, 0x00205f, 0x00205f, 0x003000, 0x003000));
    }

    private static void populateSC_ADLM() {
        SET_ENCODINGS.put("sc=Adlm", CodePointSet.createNoDedup(0x01e900, 0x01e94b, 0x01e950, 0x01e959, 0x01e95e, 0x01e95f));
    }

    private static void populateSC_AGHB() {
        SET_ENCODINGS.put("sc=Aghb", CodePointSet.createNoDedup(0x010530, 0x010563, 0x01056f, 0x01056f));
    }

    private static void populateSC_AHOM() {
        SET_ENCODINGS.put("sc=Ahom", CodePointSet.createNoDedup(0x011700, 0x01171a, 0x01171d, 0x01172b, 0x011730, 0x011746));
    }

    private static void populateSC_ARAB() {
        SET_ENCODINGS.put("sc=Arab",
                        CodePointSet.createNoDedup(0x000600, 0x000604, 0x000606, 0x00060b, 0x00060d, 0x00061a, 0x00061c, 0x00061e, 0x000620, 0x00063f, 0x000641, 0x00064a, 0x000656, 0x00066f, 0x000671,
                                        0x0006dc, 0x0006de, 0x0006ff, 0x000750, 0x00077f, 0x000870, 0x00088e, 0x000890, 0x000891, 0x000898, 0x0008e1, 0x0008e3, 0x0008ff, 0x00fb50, 0x00fbc2, 0x00fbd3,
                                        0x00fd3d, 0x00fd40, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdcf, 0x00fdcf, 0x00fdf0, 0x00fdff, 0x00fe70, 0x00fe74, 0x00fe76, 0x00fefc, 0x010e60, 0x010e7e, 0x010efd,
                                        0x010eff, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29, 0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39,
                                        0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d, 0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54,
                                        0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61, 0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67,
                                        0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b, 0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5,
                                        0x01eea9, 0x01eeab, 0x01eebb, 0x01eef0, 0x01eef1));
    }

    private static void populateSC_ARMI() {
        SET_ENCODINGS.put("sc=Armi", CodePointSet.createNoDedup(0x010840, 0x010855, 0x010857, 0x01085f));
    }

    private static void populateSC_ARMN() {
        SET_ENCODINGS.put("sc=Armn", CodePointSet.createNoDedup(0x000531, 0x000556, 0x000559, 0x00058a, 0x00058d, 0x00058f, 0x00fb13, 0x00fb17));
    }

    private static void populateSC_AVST() {
        SET_ENCODINGS.put("sc=Avst", CodePointSet.createNoDedup(0x010b00, 0x010b35, 0x010b39, 0x010b3f));
    }

    private static void populateSC_BALI() {
        SET_ENCODINGS.put("sc=Bali", CodePointSet.createNoDedup(0x001b00, 0x001b4c, 0x001b50, 0x001b7e));
    }

    private static void populateSC_BAMU() {
        SET_ENCODINGS.put("sc=Bamu", CodePointSet.createNoDedup(0x00a6a0, 0x00a6f7, 0x016800, 0x016a38));
    }

    private static void populateSC_BASS() {
        SET_ENCODINGS.put("sc=Bass", CodePointSet.createNoDedup(0x016ad0, 0x016aed, 0x016af0, 0x016af5));
    }

    private static void populateSC_BATK() {
        SET_ENCODINGS.put("sc=Batk", CodePointSet.createNoDedup(0x001bc0, 0x001bf3, 0x001bfc, 0x001bff));
    }

    private static void populateSC_BENG() {
        SET_ENCODINGS.put("sc=Beng", CodePointSet.createNoDedup(0x000980, 0x000983, 0x000985, 0x00098c, 0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2, 0x0009b2, 0x0009b6,
                        0x0009b9, 0x0009bc, 0x0009c4, 0x0009c7, 0x0009c8, 0x0009cb, 0x0009ce, 0x0009d7, 0x0009d7, 0x0009dc, 0x0009dd, 0x0009df, 0x0009e3, 0x0009e6, 0x0009fe));
    }

    private static void populateSC_BHKS() {
        SET_ENCODINGS.put("sc=Bhks", CodePointSet.createNoDedup(0x011c00, 0x011c08, 0x011c0a, 0x011c36, 0x011c38, 0x011c45, 0x011c50, 0x011c6c));
    }

    private static void populateSC_BOPO() {
        SET_ENCODINGS.put("sc=Bopo", CodePointSet.createNoDedup(0x0002ea, 0x0002eb, 0x003105, 0x00312f, 0x0031a0, 0x0031bf));
    }

    private static void populateSC_BRAH() {
        SET_ENCODINGS.put("sc=Brah", CodePointSet.createNoDedup(0x011000, 0x01104d, 0x011052, 0x011075, 0x01107f, 0x01107f));
    }

    private static void populateSC_BRAI() {
        SET_ENCODINGS.put("sc=Brai", CodePointSet.createNoDedup(0x002800, 0x0028ff));
    }

    private static void populateSC_BUGI() {
        SET_ENCODINGS.put("sc=Bugi", CodePointSet.createNoDedup(0x001a00, 0x001a1b, 0x001a1e, 0x001a1f));
    }

    private static void populateSC_BUHD() {
        SET_ENCODINGS.put("sc=Buhd", CodePointSet.createNoDedup(0x001740, 0x001753));
    }

    private static void populateSC_CAKM() {
        SET_ENCODINGS.put("sc=Cakm", CodePointSet.createNoDedup(0x011100, 0x011134, 0x011136, 0x011147));
    }

    private static void populateSC_CANS() {
        SET_ENCODINGS.put("sc=Cans", CodePointSet.createNoDedup(0x001400, 0x00167f, 0x0018b0, 0x0018f5, 0x011ab0, 0x011abf));
    }

    private static void populateSC_CARI() {
        SET_ENCODINGS.put("sc=Cari", CodePointSet.createNoDedup(0x0102a0, 0x0102d0));
    }

    private static void populateSC_CHAM() {
        SET_ENCODINGS.put("sc=Cham", CodePointSet.createNoDedup(0x00aa00, 0x00aa36, 0x00aa40, 0x00aa4d, 0x00aa50, 0x00aa59, 0x00aa5c, 0x00aa5f));
    }

    private static void populateSC_CHER() {
        SET_ENCODINGS.put("sc=Cher", CodePointSet.createNoDedup(0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x00ab70, 0x00abbf));
    }

    private static void populateSC_CHRS() {
        SET_ENCODINGS.put("sc=Chrs", CodePointSet.createNoDedup(0x010fb0, 0x010fcb));
    }

    private static void populateSC_COPT() {
        SET_ENCODINGS.put("sc=Copt", CodePointSet.createNoDedup(0x0003e2, 0x0003ef, 0x002c80, 0x002cf3, 0x002cf9, 0x002cff));
    }

    private static void populateSC_CPMN() {
        SET_ENCODINGS.put("sc=Cpmn", CodePointSet.createNoDedup(0x012f90, 0x012ff2));
    }

    private static void populateSC_CPRT() {
        SET_ENCODINGS.put("sc=Cprt", CodePointSet.createNoDedup(0x010800, 0x010805, 0x010808, 0x010808, 0x01080a, 0x010835, 0x010837, 0x010838, 0x01083c, 0x01083c, 0x01083f, 0x01083f));
    }

    private static void populateSC_CYRL() {
        SET_ENCODINGS.put("sc=Cyrl", CodePointSet.createNoDedup(0x000400, 0x000484, 0x000487, 0x00052f, 0x001c80, 0x001c88, 0x001d2b, 0x001d2b, 0x001d78, 0x001d78, 0x002de0, 0x002dff, 0x00a640,
                        0x00a69f, 0x00fe2e, 0x00fe2f, 0x01e030, 0x01e06d, 0x01e08f, 0x01e08f));
    }

    private static void populateSC_DEVA() {
        SET_ENCODINGS.put("sc=Deva", CodePointSet.createNoDedup(0x000900, 0x000950, 0x000955, 0x000963, 0x000966, 0x00097f, 0x00a8e0, 0x00a8ff, 0x011b00, 0x011b09));
    }

    private static void populateSC_DIAK() {
        SET_ENCODINGS.put("sc=Diak", CodePointSet.createNoDedup(0x011900, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x011935, 0x011937, 0x011938, 0x01193b,
                        0x011946, 0x011950, 0x011959));
    }

    private static void populateSC_DOGR() {
        SET_ENCODINGS.put("sc=Dogr", CodePointSet.createNoDedup(0x011800, 0x01183b));
    }

    private static void populateSC_DSRT() {
        SET_ENCODINGS.put("sc=Dsrt", CodePointSet.createNoDedup(0x010400, 0x01044f));
    }

    private static void populateSC_DUPL() {
        SET_ENCODINGS.put("sc=Dupl", CodePointSet.createNoDedup(0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88, 0x01bc90, 0x01bc99, 0x01bc9c, 0x01bc9f));
    }

    private static void populateSC_EGYP() {
        SET_ENCODINGS.put("sc=Egyp", CodePointSet.createNoDedup(0x013000, 0x013455));
    }

    private static void populateSC_ELBA() {
        SET_ENCODINGS.put("sc=Elba", CodePointSet.createNoDedup(0x010500, 0x010527));
    }

    private static void populateSC_ELYM() {
        SET_ENCODINGS.put("sc=Elym", CodePointSet.createNoDedup(0x010fe0, 0x010ff6));
    }

    private static void populateSC_ETHI() {
        SET_ENCODINGS.put("sc=Ethi",
                        CodePointSet.createNoDedup(0x001200, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d, 0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290,
                                        0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8, 0x001310, 0x001312, 0x001315, 0x001318,
                                        0x00135a, 0x00135d, 0x00137c, 0x001380, 0x001399, 0x002d80, 0x002d96, 0x002da0, 0x002da6, 0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe, 0x002dc0,
                                        0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20, 0x00ab26, 0x00ab28,
                                        0x00ab2e, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe));
    }

    private static void populateSC_GEOR() {
        SET_ENCODINGS.put("sc=Geor", CodePointSet.createNoDedup(0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x0010fa, 0x0010fc, 0x0010ff, 0x001c90, 0x001cba, 0x001cbd,
                        0x001cbf, 0x002d00, 0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d));
    }

    private static void populateSC_GLAG() {
        SET_ENCODINGS.put("sc=Glag", CodePointSet.createNoDedup(0x002c00, 0x002c5f, 0x01e000, 0x01e006, 0x01e008, 0x01e018, 0x01e01b, 0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a));
    }

    private static void populateSC_GONG() {
        SET_ENCODINGS.put("sc=Gong", CodePointSet.createNoDedup(0x011d60, 0x011d65, 0x011d67, 0x011d68, 0x011d6a, 0x011d8e, 0x011d90, 0x011d91, 0x011d93, 0x011d98, 0x011da0, 0x011da9));
    }

    private static void populateSC_GONM() {
        SET_ENCODINGS.put("sc=Gonm",
                        CodePointSet.createNoDedup(0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f, 0x011d47, 0x011d50, 0x011d59));
    }

    private static void populateSC_GOTH() {
        SET_ENCODINGS.put("sc=Goth", CodePointSet.createNoDedup(0x010330, 0x01034a));
    }

    private static void populateSC_GRAN() {
        SET_ENCODINGS.put("sc=Gran", CodePointSet.createNoDedup(0x011300, 0x011303, 0x011305, 0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335,
                        0x011339, 0x01133c, 0x011344, 0x011347, 0x011348, 0x01134b, 0x01134d, 0x011350, 0x011350, 0x011357, 0x011357, 0x01135d, 0x011363, 0x011366, 0x01136c, 0x011370, 0x011374));
    }

    private static void populateSC_GREK() {
        SET_ENCODINGS.put("sc=Grek",
                        CodePointSet.createNoDedup(0x000370, 0x000373, 0x000375, 0x000377, 0x00037a, 0x00037d, 0x00037f, 0x00037f, 0x000384, 0x000384, 0x000386, 0x000386, 0x000388, 0x00038a, 0x00038c,
                                        0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003e1, 0x0003f0, 0x0003ff, 0x001d26, 0x001d2a, 0x001d5d, 0x001d61, 0x001d66, 0x001d6a, 0x001dbf, 0x001dbf, 0x001f00,
                                        0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50, 0x001f57, 0x001f59, 0x001f59, 0x001f5b, 0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f,
                                        0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fc4, 0x001fc6, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fdd, 0x001fef, 0x001ff2, 0x001ff4, 0x001ff6, 0x001ffe, 0x002126,
                                        0x002126, 0x00ab65, 0x00ab65, 0x010140, 0x01018e, 0x0101a0, 0x0101a0, 0x01d200, 0x01d245));
    }

    private static void populateSC_GUJR() {
        SET_ENCODINGS.put("sc=Gujr", CodePointSet.createNoDedup(0x000a81, 0x000a83, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8, 0x000aaa, 0x000ab0, 0x000ab2, 0x000ab3, 0x000ab5,
                        0x000ab9, 0x000abc, 0x000ac5, 0x000ac7, 0x000ac9, 0x000acb, 0x000acd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae3, 0x000ae6, 0x000af1, 0x000af9, 0x000aff));
    }

    private static void populateSC_GURU() {
        SET_ENCODINGS.put("sc=Guru",
                        CodePointSet.createNoDedup(0x000a01, 0x000a03, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32, 0x000a33, 0x000a35, 0x000a36, 0x000a38,
                                        0x000a39, 0x000a3c, 0x000a3c, 0x000a3e, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4d, 0x000a51, 0x000a51, 0x000a59, 0x000a5c, 0x000a5e, 0x000a5e, 0x000a66,
                                        0x000a76));
    }

    private static void populateSC_HANG() {
        SET_ENCODINGS.put("sc=Hang", CodePointSet.createNoDedup(0x001100, 0x0011ff, 0x00302e, 0x00302f, 0x003131, 0x00318e, 0x003200, 0x00321e, 0x003260, 0x00327e, 0x00a960, 0x00a97c, 0x00ac00,
                        0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb, 0x00ffa0, 0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc));
    }

    private static void populateSC_HANI() {
        SET_ENCODINGS.put("sc=Hani",
                        CodePointSet.createNoDedup(0x002e80, 0x002e99, 0x002e9b, 0x002ef3, 0x002f00, 0x002fd5, 0x003005, 0x003005, 0x003007, 0x003007, 0x003021, 0x003029, 0x003038, 0x00303b, 0x003400,
                                        0x004dbf, 0x004e00, 0x009fff, 0x00f900, 0x00fa6d, 0x00fa70, 0x00fad9, 0x016fe2, 0x016fe3, 0x016ff0, 0x016ff1, 0x020000, 0x02a6df, 0x02a700, 0x02b739, 0x02b740,
                                        0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af));
    }

    private static void populateSC_HANO() {
        SET_ENCODINGS.put("sc=Hano", CodePointSet.createNoDedup(0x001720, 0x001734));
    }

    private static void populateSC_HATR() {
        SET_ENCODINGS.put("sc=Hatr", CodePointSet.createNoDedup(0x0108e0, 0x0108f2, 0x0108f4, 0x0108f5, 0x0108fb, 0x0108ff));
    }

    private static void populateSC_HEBR() {
        SET_ENCODINGS.put("sc=Hebr", CodePointSet.createNoDedup(0x000591, 0x0005c7, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f4, 0x00fb1d, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40,
                        0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fb4f));
    }

    private static void populateSC_HIRA() {
        SET_ENCODINGS.put("sc=Hira", CodePointSet.createNoDedup(0x003041, 0x003096, 0x00309d, 0x00309f, 0x01b001, 0x01b11f, 0x01b132, 0x01b132, 0x01b150, 0x01b152, 0x01f200, 0x01f200));
    }

    private static void populateSC_HLUW() {
        SET_ENCODINGS.put("sc=Hluw", CodePointSet.createNoDedup(0x014400, 0x014646));
    }

    private static void populateSC_HMNG() {
        SET_ENCODINGS.put("sc=Hmng", CodePointSet.createNoDedup(0x016b00, 0x016b45, 0x016b50, 0x016b59, 0x016b5b, 0x016b61, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f));
    }

    private static void populateSC_HMNP() {
        SET_ENCODINGS.put("sc=Hmnp", CodePointSet.createNoDedup(0x01e100, 0x01e12c, 0x01e130, 0x01e13d, 0x01e140, 0x01e149, 0x01e14e, 0x01e14f));
    }

    private static void populateSC_HUNG() {
        SET_ENCODINGS.put("sc=Hung", CodePointSet.createNoDedup(0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x010cfa, 0x010cff));
    }

    private static void populateSC_ITAL() {
        SET_ENCODINGS.put("sc=Ital", CodePointSet.createNoDedup(0x010300, 0x010323, 0x01032d, 0x01032f));
    }

    private static void populateSC_JAVA() {
        SET_ENCODINGS.put("sc=Java", CodePointSet.createNoDedup(0x00a980, 0x00a9cd, 0x00a9d0, 0x00a9d9, 0x00a9de, 0x00a9df));
    }

    private static void populateSC_KALI() {
        SET_ENCODINGS.put("sc=Kali", CodePointSet.createNoDedup(0x00a900, 0x00a92d, 0x00a92f, 0x00a92f));
    }

    private static void populateSC_KANA() {
        SET_ENCODINGS.put("sc=Kana", CodePointSet.createNoDedup(0x0030a1, 0x0030fa, 0x0030fd, 0x0030ff, 0x0031f0, 0x0031ff, 0x0032d0, 0x0032fe, 0x003300, 0x003357, 0x00ff66, 0x00ff6f, 0x00ff71,
                        0x00ff9d, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd, 0x01affe, 0x01b000, 0x01b000, 0x01b120, 0x01b122, 0x01b155, 0x01b155, 0x01b164, 0x01b167));
    }

    private static void populateSC_KAWI() {
        SET_ENCODINGS.put("sc=Kawi", CodePointSet.createNoDedup(0x011f00, 0x011f10, 0x011f12, 0x011f3a, 0x011f3e, 0x011f59));
    }

    private static void populateSC_KHAR() {
        SET_ENCODINGS.put("sc=Khar", CodePointSet.createNoDedup(0x010a00, 0x010a03, 0x010a05, 0x010a06, 0x010a0c, 0x010a13, 0x010a15, 0x010a17, 0x010a19, 0x010a35, 0x010a38, 0x010a3a, 0x010a3f,
                        0x010a48, 0x010a50, 0x010a58));
    }

    private static void populateSC_KHMR() {
        SET_ENCODINGS.put("sc=Khmr", CodePointSet.createNoDedup(0x001780, 0x0017dd, 0x0017e0, 0x0017e9, 0x0017f0, 0x0017f9, 0x0019e0, 0x0019ff));
    }

    private static void populateSC_KHOJ() {
        SET_ENCODINGS.put("sc=Khoj", CodePointSet.createNoDedup(0x011200, 0x011211, 0x011213, 0x011241));
    }

    private static void populateSC_KITS() {
        SET_ENCODINGS.put("sc=Kits", CodePointSet.createNoDedup(0x016fe4, 0x016fe4, 0x018b00, 0x018cd5));
    }

    private static void populateSC_KNDA() {
        SET_ENCODINGS.put("sc=Knda", CodePointSet.createNoDedup(0x000c80, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbc, 0x000cc4, 0x000cc6,
                        0x000cc8, 0x000cca, 0x000ccd, 0x000cd5, 0x000cd6, 0x000cdd, 0x000cde, 0x000ce0, 0x000ce3, 0x000ce6, 0x000cef, 0x000cf1, 0x000cf3));
    }

    private static void populateSC_KTHI() {
        SET_ENCODINGS.put("sc=Kthi", CodePointSet.createNoDedup(0x011080, 0x0110c2, 0x0110cd, 0x0110cd));
    }

    private static void populateSC_LANA() {
        SET_ENCODINGS.put("sc=Lana", CodePointSet.createNoDedup(0x001a20, 0x001a5e, 0x001a60, 0x001a7c, 0x001a7f, 0x001a89, 0x001a90, 0x001a99, 0x001aa0, 0x001aad));
    }

    private static void populateSC_LAOO() {
        SET_ENCODINGS.put("sc=Laoo", CodePointSet.createNoDedup(0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000ebd, 0x000ec0,
                        0x000ec4, 0x000ec6, 0x000ec6, 0x000ec8, 0x000ece, 0x000ed0, 0x000ed9, 0x000edc, 0x000edf));
    }

    private static void populateSC_LATN() {
        SET_ENCODINGS.put("sc=Latn",
                        CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6, 0x0000f8, 0x0002b8, 0x0002e0,
                                        0x0002e4, 0x001d00, 0x001d25, 0x001d2c, 0x001d5c, 0x001d62, 0x001d65, 0x001d6b, 0x001d77, 0x001d79, 0x001dbe, 0x001e00, 0x001eff, 0x002071, 0x002071, 0x00207f,
                                        0x00207f, 0x002090, 0x00209c, 0x00212a, 0x00212b, 0x002132, 0x002132, 0x00214e, 0x00214e, 0x002160, 0x002188, 0x002c60, 0x002c7f, 0x00a722, 0x00a787, 0x00a78b,
                                        0x00a7ca, 0x00a7d0, 0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9, 0x00a7f2, 0x00a7ff, 0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab64, 0x00ab66, 0x00ab69, 0x00fb00,
                                        0x00fb06, 0x00ff21, 0x00ff3a, 0x00ff41, 0x00ff5a, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x01df00, 0x01df1e, 0x01df25, 0x01df2a));
    }

    private static void populateSC_LEPC() {
        SET_ENCODINGS.put("sc=Lepc", CodePointSet.createNoDedup(0x001c00, 0x001c37, 0x001c3b, 0x001c49, 0x001c4d, 0x001c4f));
    }

    private static void populateSC_LIMB() {
        SET_ENCODINGS.put("sc=Limb", CodePointSet.createNoDedup(0x001900, 0x00191e, 0x001920, 0x00192b, 0x001930, 0x00193b, 0x001940, 0x001940, 0x001944, 0x00194f));
    }

    private static void populateSC_LINA() {
        SET_ENCODINGS.put("sc=Lina", CodePointSet.createNoDedup(0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767));
    }

    private static void populateSC_LINB() {
        SET_ENCODINGS.put("sc=Linb",
                        CodePointSet.createNoDedup(0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d, 0x01003f, 0x01004d, 0x010050, 0x01005d, 0x010080, 0x0100fa));
    }

    private static void populateSC_LISU() {
        SET_ENCODINGS.put("sc=Lisu", CodePointSet.createNoDedup(0x00a4d0, 0x00a4ff, 0x011fb0, 0x011fb0));
    }

    private static void populateSC_LYCI() {
        SET_ENCODINGS.put("sc=Lyci", CodePointSet.createNoDedup(0x010280, 0x01029c));
    }

    private static void populateSC_LYDI() {
        SET_ENCODINGS.put("sc=Lydi", CodePointSet.createNoDedup(0x010920, 0x010939, 0x01093f, 0x01093f));
    }

    private static void populateSC_MAHJ() {
        SET_ENCODINGS.put("sc=Mahj", CodePointSet.createNoDedup(0x011150, 0x011176));
    }

    private static void populateSC_MAKA() {
        SET_ENCODINGS.put("sc=Maka", CodePointSet.createNoDedup(0x011ee0, 0x011ef8));
    }

    private static void populateSC_MAND() {
        SET_ENCODINGS.put("sc=Mand", CodePointSet.createNoDedup(0x000840, 0x00085b, 0x00085e, 0x00085e));
    }

    private static void populateSC_MANI() {
        SET_ENCODINGS.put("sc=Mani", CodePointSet.createNoDedup(0x010ac0, 0x010ae6, 0x010aeb, 0x010af6));
    }

    private static void populateSC_MARC() {
        SET_ENCODINGS.put("sc=Marc", CodePointSet.createNoDedup(0x011c70, 0x011c8f, 0x011c92, 0x011ca7, 0x011ca9, 0x011cb6));
    }

    private static void populateSC_MEDF() {
        SET_ENCODINGS.put("sc=Medf", CodePointSet.createNoDedup(0x016e40, 0x016e9a));
    }

    private static void populateSC_MEND() {
        SET_ENCODINGS.put("sc=Mend", CodePointSet.createNoDedup(0x01e800, 0x01e8c4, 0x01e8c7, 0x01e8d6));
    }

    private static void populateSC_MERC() {
        SET_ENCODINGS.put("sc=Merc", CodePointSet.createNoDedup(0x0109a0, 0x0109b7, 0x0109bc, 0x0109cf, 0x0109d2, 0x0109ff));
    }

    private static void populateSC_MERO() {
        SET_ENCODINGS.put("sc=Mero", CodePointSet.createNoDedup(0x010980, 0x01099f));
    }

    private static void populateSC_MLYM() {
        SET_ENCODINGS.put("sc=Mlym",
                        CodePointSet.createNoDedup(0x000d00, 0x000d0c, 0x000d0e, 0x000d10, 0x000d12, 0x000d44, 0x000d46, 0x000d48, 0x000d4a, 0x000d4f, 0x000d54, 0x000d63, 0x000d66, 0x000d7f));
    }

    private static void populateSC_MODI() {
        SET_ENCODINGS.put("sc=Modi", CodePointSet.createNoDedup(0x011600, 0x011644, 0x011650, 0x011659));
    }

    private static void populateSC_MONG() {
        SET_ENCODINGS.put("sc=Mong", CodePointSet.createNoDedup(0x001800, 0x001801, 0x001804, 0x001804, 0x001806, 0x001819, 0x001820, 0x001878, 0x001880, 0x0018aa, 0x011660, 0x01166c));
    }

    private static void populateSC_MROO() {
        SET_ENCODINGS.put("sc=Mroo", CodePointSet.createNoDedup(0x016a40, 0x016a5e, 0x016a60, 0x016a69, 0x016a6e, 0x016a6f));
    }

    private static void populateSC_MTEI() {
        SET_ENCODINGS.put("sc=Mtei", CodePointSet.createNoDedup(0x00aae0, 0x00aaf6, 0x00abc0, 0x00abed, 0x00abf0, 0x00abf9));
    }

    private static void populateSC_MULT() {
        SET_ENCODINGS.put("sc=Mult", CodePointSet.createNoDedup(0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a9));
    }

    private static void populateSC_MYMR() {
        SET_ENCODINGS.put("sc=Mymr", CodePointSet.createNoDedup(0x001000, 0x00109f, 0x00a9e0, 0x00a9fe, 0x00aa60, 0x00aa7f));
    }

    private static void populateSC_NAGM() {
        SET_ENCODINGS.put("sc=Nagm", CodePointSet.createNoDedup(0x01e4d0, 0x01e4f9));
    }

    private static void populateSC_NAND() {
        SET_ENCODINGS.put("sc=Nand", CodePointSet.createNoDedup(0x0119a0, 0x0119a7, 0x0119aa, 0x0119d7, 0x0119da, 0x0119e4));
    }

    private static void populateSC_NARB() {
        SET_ENCODINGS.put("sc=Narb", CodePointSet.createNoDedup(0x010a80, 0x010a9f));
    }

    private static void populateSC_NBAT() {
        SET_ENCODINGS.put("sc=Nbat", CodePointSet.createNoDedup(0x010880, 0x01089e, 0x0108a7, 0x0108af));
    }

    private static void populateSC_NEWA() {
        SET_ENCODINGS.put("sc=Newa", CodePointSet.createNoDedup(0x011400, 0x01145b, 0x01145d, 0x011461));
    }

    private static void populateSC_NKOO() {
        SET_ENCODINGS.put("sc=Nkoo", CodePointSet.createNoDedup(0x0007c0, 0x0007fa, 0x0007fd, 0x0007ff));
    }

    private static void populateSC_NSHU() {
        SET_ENCODINGS.put("sc=Nshu", CodePointSet.createNoDedup(0x016fe1, 0x016fe1, 0x01b170, 0x01b2fb));
    }

    private static void populateSC_OGAM() {
        SET_ENCODINGS.put("sc=Ogam", CodePointSet.createNoDedup(0x001680, 0x00169c));
    }

    private static void populateSC_OLCK() {
        SET_ENCODINGS.put("sc=Olck", CodePointSet.createNoDedup(0x001c50, 0x001c7f));
    }

    private static void populateSC_ORKH() {
        SET_ENCODINGS.put("sc=Orkh", CodePointSet.createNoDedup(0x010c00, 0x010c48));
    }

    private static void populateSC_ORYA() {
        SET_ENCODINGS.put("sc=Orya", CodePointSet.createNoDedup(0x000b01, 0x000b03, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28, 0x000b2a, 0x000b30, 0x000b32, 0x000b33, 0x000b35,
                        0x000b39, 0x000b3c, 0x000b44, 0x000b47, 0x000b48, 0x000b4b, 0x000b4d, 0x000b55, 0x000b57, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b63, 0x000b66, 0x000b77));
    }

    private static void populateSC_OSGE() {
        SET_ENCODINGS.put("sc=Osge", CodePointSet.createNoDedup(0x0104b0, 0x0104d3, 0x0104d8, 0x0104fb));
    }

    private static void populateSC_OSMA() {
        SET_ENCODINGS.put("sc=Osma", CodePointSet.createNoDedup(0x010480, 0x01049d, 0x0104a0, 0x0104a9));
    }

    private static void populateSC_OUGR() {
        SET_ENCODINGS.put("sc=Ougr", CodePointSet.createNoDedup(0x010f70, 0x010f89));
    }

    private static void populateSC_PALM() {
        SET_ENCODINGS.put("sc=Palm", CodePointSet.createNoDedup(0x010860, 0x01087f));
    }

    private static void populateSC_PAUC() {
        SET_ENCODINGS.put("sc=Pauc", CodePointSet.createNoDedup(0x011ac0, 0x011af8));
    }

    private static void populateSC_PERM() {
        SET_ENCODINGS.put("sc=Perm", CodePointSet.createNoDedup(0x010350, 0x01037a));
    }

    private static void populateSC_PHAG() {
        SET_ENCODINGS.put("sc=Phag", CodePointSet.createNoDedup(0x00a840, 0x00a877));
    }

    private static void populateSC_PHLI() {
        SET_ENCODINGS.put("sc=Phli", CodePointSet.createNoDedup(0x010b60, 0x010b72, 0x010b78, 0x010b7f));
    }

    private static void populateSC_PHLP() {
        SET_ENCODINGS.put("sc=Phlp", CodePointSet.createNoDedup(0x010b80, 0x010b91, 0x010b99, 0x010b9c, 0x010ba9, 0x010baf));
    }

    private static void populateSC_PHNX() {
        SET_ENCODINGS.put("sc=Phnx", CodePointSet.createNoDedup(0x010900, 0x01091b, 0x01091f, 0x01091f));
    }

    private static void populateSC_PLRD() {
        SET_ENCODINGS.put("sc=Plrd", CodePointSet.createNoDedup(0x016f00, 0x016f4a, 0x016f4f, 0x016f87, 0x016f8f, 0x016f9f));
    }

    private static void populateSC_PRTI() {
        SET_ENCODINGS.put("sc=Prti", CodePointSet.createNoDedup(0x010b40, 0x010b55, 0x010b58, 0x010b5f));
    }

    private static void populateSC_RJNG() {
        SET_ENCODINGS.put("sc=Rjng", CodePointSet.createNoDedup(0x00a930, 0x00a953, 0x00a95f, 0x00a95f));
    }

    private static void populateSC_ROHG() {
        SET_ENCODINGS.put("sc=Rohg", CodePointSet.createNoDedup(0x010d00, 0x010d27, 0x010d30, 0x010d39));
    }

    private static void populateSC_RUNR() {
        SET_ENCODINGS.put("sc=Runr", CodePointSet.createNoDedup(0x0016a0, 0x0016ea, 0x0016ee, 0x0016f8));
    }

    private static void populateSC_SAMR() {
        SET_ENCODINGS.put("sc=Samr", CodePointSet.createNoDedup(0x000800, 0x00082d, 0x000830, 0x00083e));
    }

    private static void populateSC_SARB() {
        SET_ENCODINGS.put("sc=Sarb", CodePointSet.createNoDedup(0x010a60, 0x010a7f));
    }

    private static void populateSC_SAUR() {
        SET_ENCODINGS.put("sc=Saur", CodePointSet.createNoDedup(0x00a880, 0x00a8c5, 0x00a8ce, 0x00a8d9));
    }

    private static void populateSC_SGNW() {
        SET_ENCODINGS.put("sc=Sgnw", CodePointSet.createNoDedup(0x01d800, 0x01da8b, 0x01da9b, 0x01da9f, 0x01daa1, 0x01daaf));
    }

    private static void populateSC_SHAW() {
        SET_ENCODINGS.put("sc=Shaw", CodePointSet.createNoDedup(0x010450, 0x01047f));
    }

    private static void populateSC_SHRD() {
        SET_ENCODINGS.put("sc=Shrd", CodePointSet.createNoDedup(0x011180, 0x0111df));
    }

    private static void populateSC_SIDD() {
        SET_ENCODINGS.put("sc=Sidd", CodePointSet.createNoDedup(0x011580, 0x0115b5, 0x0115b8, 0x0115dd));
    }

    private static void populateSC_SIND() {
        SET_ENCODINGS.put("sc=Sind", CodePointSet.createNoDedup(0x0112b0, 0x0112ea, 0x0112f0, 0x0112f9));
    }

    private static void populateSC_SINH() {
        SET_ENCODINGS.put("sc=Sinh", CodePointSet.createNoDedup(0x000d81, 0x000d83, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0, 0x000dc6, 0x000dca,
                        0x000dca, 0x000dcf, 0x000dd4, 0x000dd6, 0x000dd6, 0x000dd8, 0x000ddf, 0x000de6, 0x000def, 0x000df2, 0x000df4, 0x0111e1, 0x0111f4));
    }

    private static void populateSC_SOGD() {
        SET_ENCODINGS.put("sc=Sogd", CodePointSet.createNoDedup(0x010f30, 0x010f59));
    }

    private static void populateSC_SOGO() {
        SET_ENCODINGS.put("sc=Sogo", CodePointSet.createNoDedup(0x010f00, 0x010f27));
    }

    private static void populateSC_SORA() {
        SET_ENCODINGS.put("sc=Sora", CodePointSet.createNoDedup(0x0110d0, 0x0110e8, 0x0110f0, 0x0110f9));
    }

    private static void populateSC_SOYO() {
        SET_ENCODINGS.put("sc=Soyo", CodePointSet.createNoDedup(0x011a50, 0x011aa2));
    }

    private static void populateSC_SUND() {
        SET_ENCODINGS.put("sc=Sund", CodePointSet.createNoDedup(0x001b80, 0x001bbf, 0x001cc0, 0x001cc7));
    }

    private static void populateSC_SYLO() {
        SET_ENCODINGS.put("sc=Sylo", CodePointSet.createNoDedup(0x00a800, 0x00a82c));
    }

    private static void populateSC_SYRC() {
        SET_ENCODINGS.put("sc=Syrc", CodePointSet.createNoDedup(0x000700, 0x00070d, 0x00070f, 0x00074a, 0x00074d, 0x00074f, 0x000860, 0x00086a));
    }

    private static void populateSC_TAGB() {
        SET_ENCODINGS.put("sc=Tagb", CodePointSet.createNoDedup(0x001760, 0x00176c, 0x00176e, 0x001770, 0x001772, 0x001773));
    }

    private static void populateSC_TAKR() {
        SET_ENCODINGS.put("sc=Takr", CodePointSet.createNoDedup(0x011680, 0x0116b9, 0x0116c0, 0x0116c9));
    }

    private static void populateSC_TALE() {
        SET_ENCODINGS.put("sc=Tale", CodePointSet.createNoDedup(0x001950, 0x00196d, 0x001970, 0x001974));
    }

    private static void populateSC_TALU() {
        SET_ENCODINGS.put("sc=Talu", CodePointSet.createNoDedup(0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x0019d0, 0x0019da, 0x0019de, 0x0019df));
    }

    private static void populateSC_TAML() {
        SET_ENCODINGS.put("sc=Taml",
                        CodePointSet.createNoDedup(0x000b82, 0x000b83, 0x000b85, 0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c, 0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3,
                                        0x000ba4, 0x000ba8, 0x000baa, 0x000bae, 0x000bb9, 0x000bbe, 0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcd, 0x000bd0, 0x000bd0, 0x000bd7, 0x000bd7, 0x000be6,
                                        0x000bfa, 0x011fc0, 0x011ff1, 0x011fff, 0x011fff));
    }

    private static void populateSC_TANG() {
        SET_ENCODINGS.put("sc=Tang", CodePointSet.createNoDedup(0x016fe0, 0x016fe0, 0x017000, 0x0187f7, 0x018800, 0x018aff, 0x018d00, 0x018d08));
    }

    private static void populateSC_TAVT() {
        SET_ENCODINGS.put("sc=Tavt", CodePointSet.createNoDedup(0x00aa80, 0x00aac2, 0x00aadb, 0x00aadf));
    }

    private static void populateSC_TELU() {
        SET_ENCODINGS.put("sc=Telu", CodePointSet.createNoDedup(0x000c00, 0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3c, 0x000c44, 0x000c46, 0x000c48, 0x000c4a,
                        0x000c4d, 0x000c55, 0x000c56, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d, 0x000c60, 0x000c63, 0x000c66, 0x000c6f, 0x000c77, 0x000c7f));
    }

    private static void populateSC_TFNG() {
        SET_ENCODINGS.put("sc=Tfng", CodePointSet.createNoDedup(0x002d30, 0x002d67, 0x002d6f, 0x002d70, 0x002d7f, 0x002d7f));
    }

    private static void populateSC_TGLG() {
        SET_ENCODINGS.put("sc=Tglg", CodePointSet.createNoDedup(0x001700, 0x001715, 0x00171f, 0x00171f));
    }

    private static void populateSC_THAA() {
        SET_ENCODINGS.put("sc=Thaa", CodePointSet.createNoDedup(0x000780, 0x0007b1));
    }

    private static void populateSC_THAI() {
        SET_ENCODINGS.put("sc=Thai", CodePointSet.createNoDedup(0x000e01, 0x000e3a, 0x000e40, 0x000e5b));
    }

    private static void populateSC_TIBT() {
        SET_ENCODINGS.put("sc=Tibt",
                        CodePointSet.createNoDedup(0x000f00, 0x000f47, 0x000f49, 0x000f6c, 0x000f71, 0x000f97, 0x000f99, 0x000fbc, 0x000fbe, 0x000fcc, 0x000fce, 0x000fd4, 0x000fd9, 0x000fda));
    }

    private static void populateSC_TIRH() {
        SET_ENCODINGS.put("sc=Tirh", CodePointSet.createNoDedup(0x011480, 0x0114c7, 0x0114d0, 0x0114d9));
    }

    private static void populateSC_TNSA() {
        SET_ENCODINGS.put("sc=Tnsa", CodePointSet.createNoDedup(0x016a70, 0x016abe, 0x016ac0, 0x016ac9));
    }

    private static void populateSC_TOTO() {
        SET_ENCODINGS.put("sc=Toto", CodePointSet.createNoDedup(0x01e290, 0x01e2ae));
    }

    private static void populateSC_UGAR() {
        SET_ENCODINGS.put("sc=Ugar", CodePointSet.createNoDedup(0x010380, 0x01039d, 0x01039f, 0x01039f));
    }

    private static void populateSC_VAII() {
        SET_ENCODINGS.put("sc=Vaii", CodePointSet.createNoDedup(0x00a500, 0x00a62b));
    }

    private static void populateSC_VITH() {
        SET_ENCODINGS.put("sc=Vith", CodePointSet.createNoDedup(0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3,
                        0x0105b9, 0x0105bb, 0x0105bc));
    }

    private static void populateSC_WARA() {
        SET_ENCODINGS.put("sc=Wara", CodePointSet.createNoDedup(0x0118a0, 0x0118f2, 0x0118ff, 0x0118ff));
    }

    private static void populateSC_WCHO() {
        SET_ENCODINGS.put("sc=Wcho", CodePointSet.createNoDedup(0x01e2c0, 0x01e2f9, 0x01e2ff, 0x01e2ff));
    }

    private static void populateSC_XPEO() {
        SET_ENCODINGS.put("sc=Xpeo", CodePointSet.createNoDedup(0x0103a0, 0x0103c3, 0x0103c8, 0x0103d5));
    }

    private static void populateSC_XSUX() {
        SET_ENCODINGS.put("sc=Xsux", CodePointSet.createNoDedup(0x012000, 0x012399, 0x012400, 0x01246e, 0x012470, 0x012474, 0x012480, 0x012543));
    }

    private static void populateSC_YEZI() {
        SET_ENCODINGS.put("sc=Yezi", CodePointSet.createNoDedup(0x010e80, 0x010ea9, 0x010eab, 0x010ead, 0x010eb0, 0x010eb1));
    }

    private static void populateSC_YIII() {
        SET_ENCODINGS.put("sc=Yiii", CodePointSet.createNoDedup(0x00a000, 0x00a48c, 0x00a490, 0x00a4c6));
    }

    private static void populateSC_ZANB() {
        SET_ENCODINGS.put("sc=Zanb", CodePointSet.createNoDedup(0x011a00, 0x011a47));
    }

    private static void populateSC_ZINH() {
        SET_ENCODINGS.put("sc=Zinh",
                        CodePointSet.createNoDedup(0x000300, 0x00036f, 0x000485, 0x000486, 0x00064b, 0x000655, 0x000670, 0x000670, 0x000951, 0x000954, 0x001ab0, 0x001ace, 0x001cd0, 0x001cd2, 0x001cd4,
                                        0x001ce0, 0x001ce2, 0x001ce8, 0x001ced, 0x001ced, 0x001cf4, 0x001cf4, 0x001cf8, 0x001cf9, 0x001dc0, 0x001dff, 0x00200c, 0x00200d, 0x0020d0, 0x0020f0, 0x00302a,
                                        0x00302d, 0x003099, 0x00309a, 0x00fe00, 0x00fe0f, 0x00fe20, 0x00fe2d, 0x0101fd, 0x0101fd, 0x0102e0, 0x0102e0, 0x01133b, 0x01133b, 0x01cf00, 0x01cf2d, 0x01cf30,
                                        0x01cf46, 0x01d167, 0x01d169, 0x01d17b, 0x01d182, 0x01d185, 0x01d18b, 0x01d1aa, 0x01d1ad, 0x0e0100, 0x0e01ef));
    }

    private static void populateSC_ZYYY() {
        SET_ENCODINGS.put("sc=Zyyy", CodePointSet.createNoDedup(0x000000, 0x000040, 0x00005b, 0x000060, 0x00007b, 0x0000a9, 0x0000ab, 0x0000b9, 0x0000bb, 0x0000bf, 0x0000d7, 0x0000d7, 0x0000f7,
                        0x0000f7, 0x0002b9, 0x0002df, 0x0002e5, 0x0002e9, 0x0002ec, 0x0002ff, 0x000374, 0x000374, 0x00037e, 0x00037e, 0x000385, 0x000385, 0x000387, 0x000387, 0x000605, 0x000605,
                        0x00060c, 0x00060c, 0x00061b, 0x00061b, 0x00061f, 0x00061f, 0x000640, 0x000640, 0x0006dd, 0x0006dd, 0x0008e2, 0x0008e2, 0x000964, 0x000965, 0x000e3f, 0x000e3f, 0x000fd5,
                        0x000fd8, 0x0010fb, 0x0010fb, 0x0016eb, 0x0016ed, 0x001735, 0x001736, 0x001802, 0x001803, 0x001805, 0x001805, 0x001cd3, 0x001cd3, 0x001ce1, 0x001ce1, 0x001ce9, 0x001cec,
                        0x001cee, 0x001cf3, 0x001cf5, 0x001cf7, 0x001cfa, 0x001cfa, 0x002000, 0x00200b, 0x00200e, 0x002064, 0x002066, 0x002070, 0x002074, 0x00207e, 0x002080, 0x00208e, 0x0020a0,
                        0x0020c0, 0x002100, 0x002125, 0x002127, 0x002129, 0x00212c, 0x002131, 0x002133, 0x00214d, 0x00214f, 0x00215f, 0x002189, 0x00218b, 0x002190, 0x002426, 0x002440, 0x00244a,
                        0x002460, 0x0027ff, 0x002900, 0x002b73, 0x002b76, 0x002b95, 0x002b97, 0x002bff, 0x002e00, 0x002e5d, 0x002ff0, 0x002ffb, 0x003000, 0x003004, 0x003006, 0x003006, 0x003008,
                        0x003020, 0x003030, 0x003037, 0x00303c, 0x00303f, 0x00309b, 0x00309c, 0x0030a0, 0x0030a0, 0x0030fb, 0x0030fc, 0x003190, 0x00319f, 0x0031c0, 0x0031e3, 0x003220, 0x00325f,
                        0x00327f, 0x0032cf, 0x0032ff, 0x0032ff, 0x003358, 0x0033ff, 0x004dc0, 0x004dff, 0x00a700, 0x00a721, 0x00a788, 0x00a78a, 0x00a830, 0x00a839, 0x00a92e, 0x00a92e, 0x00a9cf,
                        0x00a9cf, 0x00ab5b, 0x00ab5b, 0x00ab6a, 0x00ab6b, 0x00fd3e, 0x00fd3f, 0x00fe10, 0x00fe19, 0x00fe30, 0x00fe52, 0x00fe54, 0x00fe66, 0x00fe68, 0x00fe6b, 0x00feff, 0x00feff,
                        0x00ff01, 0x00ff20, 0x00ff3b, 0x00ff40, 0x00ff5b, 0x00ff65, 0x00ff70, 0x00ff70, 0x00ff9e, 0x00ff9f, 0x00ffe0, 0x00ffe6, 0x00ffe8, 0x00ffee, 0x00fff9, 0x00fffd, 0x010100,
                        0x010102, 0x010107, 0x010133, 0x010137, 0x01013f, 0x010190, 0x01019c, 0x0101d0, 0x0101fc, 0x0102e1, 0x0102fb, 0x01bca0, 0x01bca3, 0x01cf50, 0x01cfc3, 0x01d000, 0x01d0f5,
                        0x01d100, 0x01d126, 0x01d129, 0x01d166, 0x01d16a, 0x01d17a, 0x01d183, 0x01d184, 0x01d18c, 0x01d1a9, 0x01d1ae, 0x01d1ea, 0x01d2c0, 0x01d2d3, 0x01d2e0, 0x01d2f3, 0x01d300,
                        0x01d356, 0x01d360, 0x01d378, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae, 0x01d4b9,
                        0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e, 0x01d540,
                        0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d7cb, 0x01d7ce, 0x01d7ff, 0x01ec71, 0x01ecb4, 0x01ed01, 0x01ed3d, 0x01f000, 0x01f02b,
                        0x01f030, 0x01f093, 0x01f0a0, 0x01f0ae, 0x01f0b1, 0x01f0bf, 0x01f0c1, 0x01f0cf, 0x01f0d1, 0x01f0f5, 0x01f100, 0x01f1ad, 0x01f1e6, 0x01f1ff, 0x01f201, 0x01f202, 0x01f210,
                        0x01f23b, 0x01f240, 0x01f248, 0x01f250, 0x01f251, 0x01f260, 0x01f265, 0x01f300, 0x01f6d7, 0x01f6dc, 0x01f6ec, 0x01f6f0, 0x01f6fc, 0x01f700, 0x01f776, 0x01f77b, 0x01f7d9,
                        0x01f7e0, 0x01f7eb, 0x01f7f0, 0x01f7f0, 0x01f800, 0x01f80b, 0x01f810, 0x01f847, 0x01f850, 0x01f859, 0x01f860, 0x01f887, 0x01f890, 0x01f8ad, 0x01f8b0, 0x01f8b1, 0x01f900,
                        0x01fa53, 0x01fa60, 0x01fa6d, 0x01fa70, 0x01fa7c, 0x01fa80, 0x01fa88, 0x01fa90, 0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb, 0x01fae0, 0x01fae8, 0x01faf0, 0x01faf8,
                        0x01fb00, 0x01fb92, 0x01fb94, 0x01fbca, 0x01fbf0, 0x01fbf9, 0x0e0001, 0x0e0001, 0x0e0020, 0x0e007f));
    }

    private static void populateSC_ZZZZ() {
        SET_ENCODINGS.put("sc=Zzzz", CodePointSet.createNoDedup(0x000378, 0x000379, 0x000380, 0x000383, 0x00038b, 0x00038b, 0x00038d, 0x00038d, 0x0003a2, 0x0003a2, 0x000530, 0x000530, 0x000557,
                        0x000558, 0x00058b, 0x00058c, 0x000590, 0x000590, 0x0005c8, 0x0005cf, 0x0005eb, 0x0005ee, 0x0005f5, 0x0005ff, 0x00070e, 0x00070e, 0x00074b, 0x00074c, 0x0007b2, 0x0007bf,
                        0x0007fb, 0x0007fc, 0x00082e, 0x00082f, 0x00083f, 0x00083f, 0x00085c, 0x00085d, 0x00085f, 0x00085f, 0x00086b, 0x00086f, 0x00088f, 0x00088f, 0x000892, 0x000897, 0x000984,
                        0x000984, 0x00098d, 0x00098e, 0x000991, 0x000992, 0x0009a9, 0x0009a9, 0x0009b1, 0x0009b1, 0x0009b3, 0x0009b5, 0x0009ba, 0x0009bb, 0x0009c5, 0x0009c6, 0x0009c9, 0x0009ca,
                        0x0009cf, 0x0009d6, 0x0009d8, 0x0009db, 0x0009de, 0x0009de, 0x0009e4, 0x0009e5, 0x0009ff, 0x000a00, 0x000a04, 0x000a04, 0x000a0b, 0x000a0e, 0x000a11, 0x000a12, 0x000a29,
                        0x000a29, 0x000a31, 0x000a31, 0x000a34, 0x000a34, 0x000a37, 0x000a37, 0x000a3a, 0x000a3b, 0x000a3d, 0x000a3d, 0x000a43, 0x000a46, 0x000a49, 0x000a4a, 0x000a4e, 0x000a50,
                        0x000a52, 0x000a58, 0x000a5d, 0x000a5d, 0x000a5f, 0x000a65, 0x000a77, 0x000a80, 0x000a84, 0x000a84, 0x000a8e, 0x000a8e, 0x000a92, 0x000a92, 0x000aa9, 0x000aa9, 0x000ab1,
                        0x000ab1, 0x000ab4, 0x000ab4, 0x000aba, 0x000abb, 0x000ac6, 0x000ac6, 0x000aca, 0x000aca, 0x000ace, 0x000acf, 0x000ad1, 0x000adf, 0x000ae4, 0x000ae5, 0x000af2, 0x000af8,
                        0x000b00, 0x000b00, 0x000b04, 0x000b04, 0x000b0d, 0x000b0e, 0x000b11, 0x000b12, 0x000b29, 0x000b29, 0x000b31, 0x000b31, 0x000b34, 0x000b34, 0x000b3a, 0x000b3b, 0x000b45,
                        0x000b46, 0x000b49, 0x000b4a, 0x000b4e, 0x000b54, 0x000b58, 0x000b5b, 0x000b5e, 0x000b5e, 0x000b64, 0x000b65, 0x000b78, 0x000b81, 0x000b84, 0x000b84, 0x000b8b, 0x000b8d,
                        0x000b91, 0x000b91, 0x000b96, 0x000b98, 0x000b9b, 0x000b9b, 0x000b9d, 0x000b9d, 0x000ba0, 0x000ba2, 0x000ba5, 0x000ba7, 0x000bab, 0x000bad, 0x000bba, 0x000bbd, 0x000bc3,
                        0x000bc5, 0x000bc9, 0x000bc9, 0x000bce, 0x000bcf, 0x000bd1, 0x000bd6, 0x000bd8, 0x000be5, 0x000bfb, 0x000bff, 0x000c0d, 0x000c0d, 0x000c11, 0x000c11, 0x000c29, 0x000c29,
                        0x000c3a, 0x000c3b, 0x000c45, 0x000c45, 0x000c49, 0x000c49, 0x000c4e, 0x000c54, 0x000c57, 0x000c57, 0x000c5b, 0x000c5c, 0x000c5e, 0x000c5f, 0x000c64, 0x000c65, 0x000c70,
                        0x000c76, 0x000c8d, 0x000c8d, 0x000c91, 0x000c91, 0x000ca9, 0x000ca9, 0x000cb4, 0x000cb4, 0x000cba, 0x000cbb, 0x000cc5, 0x000cc5, 0x000cc9, 0x000cc9, 0x000cce, 0x000cd4,
                        0x000cd7, 0x000cdc, 0x000cdf, 0x000cdf, 0x000ce4, 0x000ce5, 0x000cf0, 0x000cf0, 0x000cf4, 0x000cff, 0x000d0d, 0x000d0d, 0x000d11, 0x000d11, 0x000d45, 0x000d45, 0x000d49,
                        0x000d49, 0x000d50, 0x000d53, 0x000d64, 0x000d65, 0x000d80, 0x000d80, 0x000d84, 0x000d84, 0x000d97, 0x000d99, 0x000db2, 0x000db2, 0x000dbc, 0x000dbc, 0x000dbe, 0x000dbf,
                        0x000dc7, 0x000dc9, 0x000dcb, 0x000dce, 0x000dd5, 0x000dd5, 0x000dd7, 0x000dd7, 0x000de0, 0x000de5, 0x000df0, 0x000df1, 0x000df5, 0x000e00, 0x000e3b, 0x000e3e, 0x000e5c,
                        0x000e80, 0x000e83, 0x000e83, 0x000e85, 0x000e85, 0x000e8b, 0x000e8b, 0x000ea4, 0x000ea4, 0x000ea6, 0x000ea6, 0x000ebe, 0x000ebf, 0x000ec5, 0x000ec5, 0x000ec7, 0x000ec7,
                        0x000ecf, 0x000ecf, 0x000eda, 0x000edb, 0x000ee0, 0x000eff, 0x000f48, 0x000f48, 0x000f6d, 0x000f70, 0x000f98, 0x000f98, 0x000fbd, 0x000fbd, 0x000fcd, 0x000fcd, 0x000fdb,
                        0x000fff, 0x0010c6, 0x0010c6, 0x0010c8, 0x0010cc, 0x0010ce, 0x0010cf, 0x001249, 0x001249, 0x00124e, 0x00124f, 0x001257, 0x001257, 0x001259, 0x001259, 0x00125e, 0x00125f,
                        0x001289, 0x001289, 0x00128e, 0x00128f, 0x0012b1, 0x0012b1, 0x0012b6, 0x0012b7, 0x0012bf, 0x0012bf, 0x0012c1, 0x0012c1, 0x0012c6, 0x0012c7, 0x0012d7, 0x0012d7, 0x001311,
                        0x001311, 0x001316, 0x001317, 0x00135b, 0x00135c, 0x00137d, 0x00137f, 0x00139a, 0x00139f, 0x0013f6, 0x0013f7, 0x0013fe, 0x0013ff, 0x00169d, 0x00169f, 0x0016f9, 0x0016ff,
                        0x001716, 0x00171e, 0x001737, 0x00173f, 0x001754, 0x00175f, 0x00176d, 0x00176d, 0x001771, 0x001771, 0x001774, 0x00177f, 0x0017de, 0x0017df, 0x0017ea, 0x0017ef, 0x0017fa,
                        0x0017ff, 0x00181a, 0x00181f, 0x001879, 0x00187f, 0x0018ab, 0x0018af, 0x0018f6, 0x0018ff, 0x00191f, 0x00191f, 0x00192c, 0x00192f, 0x00193c, 0x00193f, 0x001941, 0x001943,
                        0x00196e, 0x00196f, 0x001975, 0x00197f, 0x0019ac, 0x0019af, 0x0019ca, 0x0019cf, 0x0019db, 0x0019dd, 0x001a1c, 0x001a1d, 0x001a5f, 0x001a5f, 0x001a7d, 0x001a7e, 0x001a8a,
                        0x001a8f, 0x001a9a, 0x001a9f, 0x001aae, 0x001aaf, 0x001acf, 0x001aff, 0x001b4d, 0x001b4f, 0x001b7f, 0x001b7f, 0x001bf4, 0x001bfb, 0x001c38, 0x001c3a, 0x001c4a, 0x001c4c,
                        0x001c89, 0x001c8f, 0x001cbb, 0x001cbc, 0x001cc8, 0x001ccf, 0x001cfb, 0x001cff, 0x001f16, 0x001f17, 0x001f1e, 0x001f1f, 0x001f46, 0x001f47, 0x001f4e, 0x001f4f, 0x001f58,
                        0x001f58, 0x001f5a, 0x001f5a, 0x001f5c, 0x001f5c, 0x001f5e, 0x001f5e, 0x001f7e, 0x001f7f, 0x001fb5, 0x001fb5, 0x001fc5, 0x001fc5, 0x001fd4, 0x001fd5, 0x001fdc, 0x001fdc,
                        0x001ff0, 0x001ff1, 0x001ff5, 0x001ff5, 0x001fff, 0x001fff, 0x002065, 0x002065, 0x002072, 0x002073, 0x00208f, 0x00208f, 0x00209d, 0x00209f, 0x0020c1, 0x0020cf, 0x0020f1,
                        0x0020ff, 0x00218c, 0x00218f, 0x002427, 0x00243f, 0x00244b, 0x00245f, 0x002b74, 0x002b75, 0x002b96, 0x002b96, 0x002cf4, 0x002cf8, 0x002d26, 0x002d26, 0x002d28, 0x002d2c,
                        0x002d2e, 0x002d2f, 0x002d68, 0x002d6e, 0x002d71, 0x002d7e, 0x002d97, 0x002d9f, 0x002da7, 0x002da7, 0x002daf, 0x002daf, 0x002db7, 0x002db7, 0x002dbf, 0x002dbf, 0x002dc7,
                        0x002dc7, 0x002dcf, 0x002dcf, 0x002dd7, 0x002dd7, 0x002ddf, 0x002ddf, 0x002e5e, 0x002e7f, 0x002e9a, 0x002e9a, 0x002ef4, 0x002eff, 0x002fd6, 0x002fef, 0x002ffc, 0x002fff,
                        0x003040, 0x003040, 0x003097, 0x003098, 0x003100, 0x003104, 0x003130, 0x003130, 0x00318f, 0x00318f, 0x0031e4, 0x0031ef, 0x00321f, 0x00321f, 0x00a48d, 0x00a48f, 0x00a4c7,
                        0x00a4cf, 0x00a62c, 0x00a63f, 0x00a6f8, 0x00a6ff, 0x00a7cb, 0x00a7cf, 0x00a7d2, 0x00a7d2, 0x00a7d4, 0x00a7d4, 0x00a7da, 0x00a7f1, 0x00a82d, 0x00a82f, 0x00a83a, 0x00a83f,
                        0x00a878, 0x00a87f, 0x00a8c6, 0x00a8cd, 0x00a8da, 0x00a8df, 0x00a954, 0x00a95e, 0x00a97d, 0x00a97f, 0x00a9ce, 0x00a9ce, 0x00a9da, 0x00a9dd, 0x00a9ff, 0x00a9ff, 0x00aa37,
                        0x00aa3f, 0x00aa4e, 0x00aa4f, 0x00aa5a, 0x00aa5b, 0x00aac3, 0x00aada, 0x00aaf7, 0x00ab00, 0x00ab07, 0x00ab08, 0x00ab0f, 0x00ab10, 0x00ab17, 0x00ab1f, 0x00ab27, 0x00ab27,
                        0x00ab2f, 0x00ab2f, 0x00ab6c, 0x00ab6f, 0x00abee, 0x00abef, 0x00abfa, 0x00abff, 0x00d7a4, 0x00d7af, 0x00d7c7, 0x00d7ca, 0x00d7fc, 0x00f8ff, 0x00fa6e, 0x00fa6f, 0x00fada,
                        0x00faff, 0x00fb07, 0x00fb12, 0x00fb18, 0x00fb1c, 0x00fb37, 0x00fb37, 0x00fb3d, 0x00fb3d, 0x00fb3f, 0x00fb3f, 0x00fb42, 0x00fb42, 0x00fb45, 0x00fb45, 0x00fbc3, 0x00fbd2,
                        0x00fd90, 0x00fd91, 0x00fdc8, 0x00fdce, 0x00fdd0, 0x00fdef, 0x00fe1a, 0x00fe1f, 0x00fe53, 0x00fe53, 0x00fe67, 0x00fe67, 0x00fe6c, 0x00fe6f, 0x00fe75, 0x00fe75, 0x00fefd,
                        0x00fefe, 0x00ff00, 0x00ff00, 0x00ffbf, 0x00ffc1, 0x00ffc8, 0x00ffc9, 0x00ffd0, 0x00ffd1, 0x00ffd8, 0x00ffd9, 0x00ffdd, 0x00ffdf, 0x00ffe7, 0x00ffe7, 0x00ffef, 0x00fff8,
                        0x00fffe, 0x00ffff, 0x01000c, 0x01000c, 0x010027, 0x010027, 0x01003b, 0x01003b, 0x01003e, 0x01003e, 0x01004e, 0x01004f, 0x01005e, 0x01007f, 0x0100fb, 0x0100ff, 0x010103,
                        0x010106, 0x010134, 0x010136, 0x01018f, 0x01018f, 0x01019d, 0x01019f, 0x0101a1, 0x0101cf, 0x0101fe, 0x01027f, 0x01029d, 0x01029f, 0x0102d1, 0x0102df, 0x0102fc, 0x0102ff,
                        0x010324, 0x01032c, 0x01034b, 0x01034f, 0x01037b, 0x01037f, 0x01039e, 0x01039e, 0x0103c4, 0x0103c7, 0x0103d6, 0x0103ff, 0x01049e, 0x01049f, 0x0104aa, 0x0104af, 0x0104d4,
                        0x0104d7, 0x0104fc, 0x0104ff, 0x010528, 0x01052f, 0x010564, 0x01056e, 0x01057b, 0x01057b, 0x01058b, 0x01058b, 0x010593, 0x010593, 0x010596, 0x010596, 0x0105a2, 0x0105a2,
                        0x0105b2, 0x0105b2, 0x0105ba, 0x0105ba, 0x0105bd, 0x0105ff, 0x010737, 0x01073f, 0x010756, 0x01075f, 0x010768, 0x01077f, 0x010786, 0x010786, 0x0107b1, 0x0107b1, 0x0107bb,
                        0x0107ff, 0x010806, 0x010807, 0x010809, 0x010809, 0x010836, 0x010836, 0x010839, 0x01083b, 0x01083d, 0x01083e, 0x010856, 0x010856, 0x01089f, 0x0108a6, 0x0108b0, 0x0108df,
                        0x0108f3, 0x0108f3, 0x0108f6, 0x0108fa, 0x01091c, 0x01091e, 0x01093a, 0x01093e, 0x010940, 0x01097f, 0x0109b8, 0x0109bb, 0x0109d0, 0x0109d1, 0x010a04, 0x010a04, 0x010a07,
                        0x010a0b, 0x010a14, 0x010a14, 0x010a18, 0x010a18, 0x010a36, 0x010a37, 0x010a3b, 0x010a3e, 0x010a49, 0x010a4f, 0x010a59, 0x010a5f, 0x010aa0, 0x010abf, 0x010ae7, 0x010aea,
                        0x010af7, 0x010aff, 0x010b36, 0x010b38, 0x010b56, 0x010b57, 0x010b73, 0x010b77, 0x010b92, 0x010b98, 0x010b9d, 0x010ba8, 0x010bb0, 0x010bff, 0x010c49, 0x010c7f, 0x010cb3,
                        0x010cbf, 0x010cf3, 0x010cf9, 0x010d28, 0x010d2f, 0x010d3a, 0x010e5f, 0x010e7f, 0x010e7f, 0x010eaa, 0x010eaa, 0x010eae, 0x010eaf, 0x010eb2, 0x010efc, 0x010f28, 0x010f2f,
                        0x010f5a, 0x010f6f, 0x010f8a, 0x010faf, 0x010fcc, 0x010fdf, 0x010ff7, 0x010fff, 0x01104e, 0x011051, 0x011076, 0x01107e, 0x0110c3, 0x0110cc, 0x0110ce, 0x0110cf, 0x0110e9,
                        0x0110ef, 0x0110fa, 0x0110ff, 0x011135, 0x011135, 0x011148, 0x01114f, 0x011177, 0x01117f, 0x0111e0, 0x0111e0, 0x0111f5, 0x0111ff, 0x011212, 0x011212, 0x011242, 0x01127f,
                        0x011287, 0x011287, 0x011289, 0x011289, 0x01128e, 0x01128e, 0x01129e, 0x01129e, 0x0112aa, 0x0112af, 0x0112eb, 0x0112ef, 0x0112fa, 0x0112ff, 0x011304, 0x011304, 0x01130d,
                        0x01130e, 0x011311, 0x011312, 0x011329, 0x011329, 0x011331, 0x011331, 0x011334, 0x011334, 0x01133a, 0x01133a, 0x011345, 0x011346, 0x011349, 0x01134a, 0x01134e, 0x01134f,
                        0x011351, 0x011356, 0x011358, 0x01135c, 0x011364, 0x011365, 0x01136d, 0x01136f, 0x011375, 0x0113ff, 0x01145c, 0x01145c, 0x011462, 0x01147f, 0x0114c8, 0x0114cf, 0x0114da,
                        0x01157f, 0x0115b6, 0x0115b7, 0x0115de, 0x0115ff, 0x011645, 0x01164f, 0x01165a, 0x01165f, 0x01166d, 0x01167f, 0x0116ba, 0x0116bf, 0x0116ca, 0x0116ff, 0x01171b, 0x01171c,
                        0x01172c, 0x01172f, 0x011747, 0x0117ff, 0x01183c, 0x01189f, 0x0118f3, 0x0118fe, 0x011907, 0x011908, 0x01190a, 0x01190b, 0x011914, 0x011914, 0x011917, 0x011917, 0x011936,
                        0x011936, 0x011939, 0x01193a, 0x011947, 0x01194f, 0x01195a, 0x01199f, 0x0119a8, 0x0119a9, 0x0119d8, 0x0119d9, 0x0119e5, 0x0119ff, 0x011a48, 0x011a4f, 0x011aa3, 0x011aaf,
                        0x011af9, 0x011aff, 0x011b0a, 0x011bff, 0x011c09, 0x011c09, 0x011c37, 0x011c37, 0x011c46, 0x011c4f, 0x011c6d, 0x011c6f, 0x011c90, 0x011c91, 0x011ca8, 0x011ca8, 0x011cb7,
                        0x011cff, 0x011d07, 0x011d07, 0x011d0a, 0x011d0a, 0x011d37, 0x011d39, 0x011d3b, 0x011d3b, 0x011d3e, 0x011d3e, 0x011d48, 0x011d4f, 0x011d5a, 0x011d5f, 0x011d66, 0x011d66,
                        0x011d69, 0x011d69, 0x011d8f, 0x011d8f, 0x011d92, 0x011d92, 0x011d99, 0x011d9f, 0x011daa, 0x011edf, 0x011ef9, 0x011eff, 0x011f11, 0x011f11, 0x011f3b, 0x011f3d, 0x011f5a,
                        0x011faf, 0x011fb1, 0x011fbf, 0x011ff2, 0x011ffe, 0x01239a, 0x0123ff, 0x01246f, 0x01246f, 0x012475, 0x01247f, 0x012544, 0x012f8f, 0x012ff3, 0x012fff, 0x013456, 0x0143ff,
                        0x014647, 0x0167ff, 0x016a39, 0x016a3f, 0x016a5f, 0x016a5f, 0x016a6a, 0x016a6d, 0x016abf, 0x016abf, 0x016aca, 0x016acf, 0x016aee, 0x016aef, 0x016af6, 0x016aff, 0x016b46,
                        0x016b4f, 0x016b5a, 0x016b5a, 0x016b62, 0x016b62, 0x016b78, 0x016b7c, 0x016b90, 0x016e3f, 0x016e9b, 0x016eff, 0x016f4b, 0x016f4e, 0x016f88, 0x016f8e, 0x016fa0, 0x016fdf,
                        0x016fe5, 0x016fef, 0x016ff2, 0x016fff, 0x0187f8, 0x0187ff, 0x018cd6, 0x018cff, 0x018d09, 0x01afef, 0x01aff4, 0x01aff4, 0x01affc, 0x01affc, 0x01afff, 0x01afff, 0x01b123,
                        0x01b131, 0x01b133, 0x01b14f, 0x01b153, 0x01b154, 0x01b156, 0x01b163, 0x01b168, 0x01b16f, 0x01b2fc, 0x01bbff, 0x01bc6b, 0x01bc6f, 0x01bc7d, 0x01bc7f, 0x01bc89, 0x01bc8f,
                        0x01bc9a, 0x01bc9b, 0x01bca4, 0x01ceff, 0x01cf2e, 0x01cf2f, 0x01cf47, 0x01cf4f, 0x01cfc4, 0x01cfff, 0x01d0f6, 0x01d0ff, 0x01d127, 0x01d128, 0x01d1eb, 0x01d1ff, 0x01d246,
                        0x01d2bf, 0x01d2d4, 0x01d2df, 0x01d2f4, 0x01d2ff, 0x01d357, 0x01d35f, 0x01d379, 0x01d3ff, 0x01d455, 0x01d455, 0x01d49d, 0x01d49d, 0x01d4a0, 0x01d4a1, 0x01d4a3, 0x01d4a4,
                        0x01d4a7, 0x01d4a8, 0x01d4ad, 0x01d4ad, 0x01d4ba, 0x01d4ba, 0x01d4bc, 0x01d4bc, 0x01d4c4, 0x01d4c4, 0x01d506, 0x01d506, 0x01d50b, 0x01d50c, 0x01d515, 0x01d515, 0x01d51d,
                        0x01d51d, 0x01d53a, 0x01d53a, 0x01d53f, 0x01d53f, 0x01d545, 0x01d545, 0x01d547, 0x01d549, 0x01d551, 0x01d551, 0x01d6a6, 0x01d6a7, 0x01d7cc, 0x01d7cd, 0x01da8c, 0x01da9a,
                        0x01daa0, 0x01daa0, 0x01dab0, 0x01deff, 0x01df1f, 0x01df24, 0x01df2b, 0x01dfff, 0x01e007, 0x01e007, 0x01e019, 0x01e01a, 0x01e022, 0x01e022, 0x01e025, 0x01e025, 0x01e02b,
                        0x01e02f, 0x01e06e, 0x01e08e, 0x01e090, 0x01e0ff, 0x01e12d, 0x01e12f, 0x01e13e, 0x01e13f, 0x01e14a, 0x01e14d, 0x01e150, 0x01e28f, 0x01e2af, 0x01e2bf, 0x01e2fa, 0x01e2fe,
                        0x01e300, 0x01e4cf, 0x01e4fa, 0x01e7df, 0x01e7e7, 0x01e7e7, 0x01e7ec, 0x01e7ec, 0x01e7ef, 0x01e7ef, 0x01e7ff, 0x01e7ff, 0x01e8c5, 0x01e8c6, 0x01e8d7, 0x01e8ff, 0x01e94c,
                        0x01e94f, 0x01e95a, 0x01e95d, 0x01e960, 0x01ec70, 0x01ecb5, 0x01ed00, 0x01ed3e, 0x01edff, 0x01ee04, 0x01ee04, 0x01ee20, 0x01ee20, 0x01ee23, 0x01ee23, 0x01ee25, 0x01ee26,
                        0x01ee28, 0x01ee28, 0x01ee33, 0x01ee33, 0x01ee38, 0x01ee38, 0x01ee3a, 0x01ee3a, 0x01ee3c, 0x01ee41, 0x01ee43, 0x01ee46, 0x01ee48, 0x01ee48, 0x01ee4a, 0x01ee4a, 0x01ee4c,
                        0x01ee4c, 0x01ee50, 0x01ee50, 0x01ee53, 0x01ee53, 0x01ee55, 0x01ee56, 0x01ee58, 0x01ee58, 0x01ee5a, 0x01ee5a, 0x01ee5c, 0x01ee5c, 0x01ee5e, 0x01ee5e, 0x01ee60, 0x01ee60,
                        0x01ee63, 0x01ee63, 0x01ee65, 0x01ee66, 0x01ee6b, 0x01ee6b, 0x01ee73, 0x01ee73, 0x01ee78, 0x01ee78, 0x01ee7d, 0x01ee7d, 0x01ee7f, 0x01ee7f, 0x01ee8a, 0x01ee8a, 0x01ee9c,
                        0x01eea0, 0x01eea4, 0x01eea4, 0x01eeaa, 0x01eeaa, 0x01eebc, 0x01eeef, 0x01eef2, 0x01efff, 0x01f02c, 0x01f02f, 0x01f094, 0x01f09f, 0x01f0af, 0x01f0b0, 0x01f0c0, 0x01f0c0,
                        0x01f0d0, 0x01f0d0, 0x01f0f6, 0x01f0ff, 0x01f1ae, 0x01f1e5, 0x01f203, 0x01f20f, 0x01f23c, 0x01f23f, 0x01f249, 0x01f24f, 0x01f252, 0x01f25f, 0x01f266, 0x01f2ff, 0x01f6d8,
                        0x01f6db, 0x01f6ed, 0x01f6ef, 0x01f6fd, 0x01f6ff, 0x01f777, 0x01f77a, 0x01f7da, 0x01f7df, 0x01f7ec, 0x01f7ef, 0x01f7f1, 0x01f7ff, 0x01f80c, 0x01f80f, 0x01f848, 0x01f84f,
                        0x01f85a, 0x01f85f, 0x01f888, 0x01f88f, 0x01f8ae, 0x01f8af, 0x01f8b2, 0x01f8ff, 0x01fa54, 0x01fa5f, 0x01fa6e, 0x01fa6f, 0x01fa7d, 0x01fa7f, 0x01fa89, 0x01fa8f, 0x01fabe,
                        0x01fabe, 0x01fac6, 0x01facd, 0x01fadc, 0x01fadf, 0x01fae9, 0x01faef, 0x01faf9, 0x01faff, 0x01fb93, 0x01fb93, 0x01fbcb, 0x01fbef, 0x01fbfa, 0x01ffff, 0x02a6e0, 0x02a6ff,
                        0x02b73a, 0x02b73f, 0x02b81e, 0x02b81f, 0x02cea2, 0x02ceaf, 0x02ebe1, 0x02f7ff, 0x02fa1e, 0x02ffff, 0x03134b, 0x03134f, 0x0323b0, 0x0e0000, 0x0e0002, 0x0e001f, 0x0e0080,
                        0x0e00ff, 0x0e01f0, 0x10ffff));
    }

    private static void populateSCX_ADLM() {
        SET_ENCODINGS.put("scx=Adlm", CodePointSet.createNoDedup(0x00061f, 0x00061f, 0x000640, 0x000640, 0x01e900, 0x01e94b, 0x01e950, 0x01e959, 0x01e95e, 0x01e95f));
    }

    private static void populateSCX_AGHB() {
        SET_ENCODINGS.put("scx=Aghb", CodePointSet.createNoDedup(0x010530, 0x010563, 0x01056f, 0x01056f));
    }

    private static void populateSCX_AHOM() {
        SET_ENCODINGS.put("scx=Ahom", CodePointSet.createNoDedup(0x011700, 0x01171a, 0x01171d, 0x01172b, 0x011730, 0x011746));
    }

    private static void populateSCX_ARAB() {
        SET_ENCODINGS.put("scx=Arab",
                        CodePointSet.createNoDedup(0x000600, 0x000604, 0x000606, 0x0006dc, 0x0006de, 0x0006ff, 0x000750, 0x00077f, 0x000870, 0x00088e, 0x000890, 0x000891, 0x000898, 0x0008e1, 0x0008e3,
                                        0x0008ff, 0x00fb50, 0x00fbc2, 0x00fbd3, 0x00fd8f, 0x00fd92, 0x00fdc7, 0x00fdcf, 0x00fdcf, 0x00fdf0, 0x00fdff, 0x00fe70, 0x00fe74, 0x00fe76, 0x00fefc, 0x0102e0,
                                        0x0102fb, 0x010e60, 0x010e7e, 0x010efd, 0x010eff, 0x01ee00, 0x01ee03, 0x01ee05, 0x01ee1f, 0x01ee21, 0x01ee22, 0x01ee24, 0x01ee24, 0x01ee27, 0x01ee27, 0x01ee29,
                                        0x01ee32, 0x01ee34, 0x01ee37, 0x01ee39, 0x01ee39, 0x01ee3b, 0x01ee3b, 0x01ee42, 0x01ee42, 0x01ee47, 0x01ee47, 0x01ee49, 0x01ee49, 0x01ee4b, 0x01ee4b, 0x01ee4d,
                                        0x01ee4f, 0x01ee51, 0x01ee52, 0x01ee54, 0x01ee54, 0x01ee57, 0x01ee57, 0x01ee59, 0x01ee59, 0x01ee5b, 0x01ee5b, 0x01ee5d, 0x01ee5d, 0x01ee5f, 0x01ee5f, 0x01ee61,
                                        0x01ee62, 0x01ee64, 0x01ee64, 0x01ee67, 0x01ee6a, 0x01ee6c, 0x01ee72, 0x01ee74, 0x01ee77, 0x01ee79, 0x01ee7c, 0x01ee7e, 0x01ee7e, 0x01ee80, 0x01ee89, 0x01ee8b,
                                        0x01ee9b, 0x01eea1, 0x01eea3, 0x01eea5, 0x01eea9, 0x01eeab, 0x01eebb, 0x01eef0, 0x01eef1));
    }

    private static void populateSCX_ARMI() {
        SET_ENCODINGS.put("scx=Armi", CodePointSet.createNoDedup(0x010840, 0x010855, 0x010857, 0x01085f));
    }

    private static void populateSCX_ARMN() {
        SET_ENCODINGS.put("scx=Armn", CodePointSet.createNoDedup(0x000531, 0x000556, 0x000559, 0x00058a, 0x00058d, 0x00058f, 0x00fb13, 0x00fb17));
    }

    private static void populateSCX_AVST() {
        SET_ENCODINGS.put("scx=Avst", CodePointSet.createNoDedup(0x010b00, 0x010b35, 0x010b39, 0x010b3f));
    }

    private static void populateSCX_BALI() {
        SET_ENCODINGS.put("scx=Bali", CodePointSet.createNoDedup(0x001b00, 0x001b4c, 0x001b50, 0x001b7e));
    }

    private static void populateSCX_BAMU() {
        SET_ENCODINGS.put("scx=Bamu", CodePointSet.createNoDedup(0x00a6a0, 0x00a6f7, 0x016800, 0x016a38));
    }

    private static void populateSCX_BASS() {
        SET_ENCODINGS.put("scx=Bass", CodePointSet.createNoDedup(0x016ad0, 0x016aed, 0x016af0, 0x016af5));
    }

    private static void populateSCX_BATK() {
        SET_ENCODINGS.put("scx=Batk", CodePointSet.createNoDedup(0x001bc0, 0x001bf3, 0x001bfc, 0x001bff));
    }

    private static void populateSCX_BENG() {
        SET_ENCODINGS.put("scx=Beng",
                        CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000980, 0x000983, 0x000985, 0x00098c, 0x00098f, 0x000990, 0x000993, 0x0009a8, 0x0009aa, 0x0009b0, 0x0009b2,
                                        0x0009b2, 0x0009b6, 0x0009b9, 0x0009bc, 0x0009c4, 0x0009c7, 0x0009c8, 0x0009cb, 0x0009ce, 0x0009d7, 0x0009d7, 0x0009dc, 0x0009dd, 0x0009df, 0x0009e3, 0x0009e6,
                                        0x0009fe, 0x001cd0, 0x001cd0, 0x001cd2, 0x001cd2, 0x001cd5, 0x001cd6, 0x001cd8, 0x001cd8, 0x001ce1, 0x001ce1, 0x001cea, 0x001cea, 0x001ced, 0x001ced, 0x001cf2,
                                        0x001cf2, 0x001cf5, 0x001cf7, 0x00a8f1, 0x00a8f1));
    }

    private static void populateSCX_BHKS() {
        SET_ENCODINGS.put("scx=Bhks", CodePointSet.createNoDedup(0x011c00, 0x011c08, 0x011c0a, 0x011c36, 0x011c38, 0x011c45, 0x011c50, 0x011c6c));
    }

    private static void populateSCX_BOPO() {
        SET_ENCODINGS.put("scx=Bopo", CodePointSet.createNoDedup(0x0002ea, 0x0002eb, 0x003001, 0x003003, 0x003008, 0x003011, 0x003013, 0x00301f, 0x00302a, 0x00302d, 0x003030, 0x003030, 0x003037,
                        0x003037, 0x0030fb, 0x0030fb, 0x003105, 0x00312f, 0x0031a0, 0x0031bf, 0x00fe45, 0x00fe46, 0x00ff61, 0x00ff65));
    }

    private static void populateSCX_BRAH() {
        SET_ENCODINGS.put("scx=Brah", CodePointSet.createNoDedup(0x011000, 0x01104d, 0x011052, 0x011075, 0x01107f, 0x01107f));
    }

    private static void populateSCX_BRAI() {
        SET_ENCODINGS.put("scx=Brai", CodePointSet.createNoDedup(0x002800, 0x0028ff));
    }

    private static void populateSCX_BUGI() {
        SET_ENCODINGS.put("scx=Bugi", CodePointSet.createNoDedup(0x001a00, 0x001a1b, 0x001a1e, 0x001a1f, 0x00a9cf, 0x00a9cf));
    }

    private static void populateSCX_BUHD() {
        SET_ENCODINGS.put("scx=Buhd", CodePointSet.createNoDedup(0x001735, 0x001736, 0x001740, 0x001753));
    }

    private static void populateSCX_CAKM() {
        SET_ENCODINGS.put("scx=Cakm", CodePointSet.createNoDedup(0x0009e6, 0x0009ef, 0x001040, 0x001049, 0x011100, 0x011134, 0x011136, 0x011147));
    }

    private static void populateSCX_CANS() {
        SET_ENCODINGS.put("scx=Cans", CodePointSet.createNoDedup(0x001400, 0x00167f, 0x0018b0, 0x0018f5, 0x011ab0, 0x011abf));
    }

    private static void populateSCX_CARI() {
        SET_ENCODINGS.put("scx=Cari", CodePointSet.createNoDedup(0x0102a0, 0x0102d0));
    }

    private static void populateSCX_CHAM() {
        SET_ENCODINGS.put("scx=Cham", CodePointSet.createNoDedup(0x00aa00, 0x00aa36, 0x00aa40, 0x00aa4d, 0x00aa50, 0x00aa59, 0x00aa5c, 0x00aa5f));
    }

    private static void populateSCX_CHER() {
        SET_ENCODINGS.put("scx=Cher", CodePointSet.createNoDedup(0x0013a0, 0x0013f5, 0x0013f8, 0x0013fd, 0x00ab70, 0x00abbf));
    }

    private static void populateSCX_CHRS() {
        SET_ENCODINGS.put("scx=Chrs", CodePointSet.createNoDedup(0x010fb0, 0x010fcb));
    }

    private static void populateSCX_COPT() {
        SET_ENCODINGS.put("scx=Copt", CodePointSet.createNoDedup(0x0003e2, 0x0003ef, 0x002c80, 0x002cf3, 0x002cf9, 0x002cff, 0x0102e0, 0x0102fb));
    }

    private static void populateSCX_CPMN() {
        SET_ENCODINGS.put("scx=Cpmn", CodePointSet.createNoDedup(0x010100, 0x010101, 0x012f90, 0x012ff2));
    }

    private static void populateSCX_CPRT() {
        SET_ENCODINGS.put("scx=Cprt", CodePointSet.createNoDedup(0x010100, 0x010102, 0x010107, 0x010133, 0x010137, 0x01013f, 0x010800, 0x010805, 0x010808, 0x010808, 0x01080a, 0x010835, 0x010837,
                        0x010838, 0x01083c, 0x01083c, 0x01083f, 0x01083f));
    }

    private static void populateSCX_CYRL() {
        SET_ENCODINGS.put("scx=Cyrl", CodePointSet.createNoDedup(0x000400, 0x00052f, 0x001c80, 0x001c88, 0x001d2b, 0x001d2b, 0x001d78, 0x001d78, 0x001df8, 0x001df8, 0x002de0, 0x002dff, 0x002e43,
                        0x002e43, 0x00a640, 0x00a69f, 0x00fe2e, 0x00fe2f, 0x01e030, 0x01e06d, 0x01e08f, 0x01e08f));
    }

    private static void populateSCX_DEVA() {
        SET_ENCODINGS.put("scx=Deva", CodePointSet.createNoDedup(0x000900, 0x000952, 0x000955, 0x00097f, 0x001cd0, 0x001cf6, 0x001cf8, 0x001cf9, 0x0020f0, 0x0020f0, 0x00a830, 0x00a839, 0x00a8e0,
                        0x00a8ff, 0x011b00, 0x011b09));
    }

    private static void populateSCX_DIAK() {
        SET_ENCODINGS.put("scx=Diak", CodePointSet.createNoDedup(0x011900, 0x011906, 0x011909, 0x011909, 0x01190c, 0x011913, 0x011915, 0x011916, 0x011918, 0x011935, 0x011937, 0x011938, 0x01193b,
                        0x011946, 0x011950, 0x011959));
    }

    private static void populateSCX_DOGR() {
        SET_ENCODINGS.put("scx=Dogr", CodePointSet.createNoDedup(0x000964, 0x00096f, 0x00a830, 0x00a839, 0x011800, 0x01183b));
    }

    private static void populateSCX_DSRT() {
        SET_ENCODINGS.put("scx=Dsrt", CodePointSet.createNoDedup(0x010400, 0x01044f));
    }

    private static void populateSCX_DUPL() {
        SET_ENCODINGS.put("scx=Dupl", CodePointSet.createNoDedup(0x01bc00, 0x01bc6a, 0x01bc70, 0x01bc7c, 0x01bc80, 0x01bc88, 0x01bc90, 0x01bc99, 0x01bc9c, 0x01bca3));
    }

    private static void populateSCX_EGYP() {
        SET_ENCODINGS.put("scx=Egyp", CodePointSet.createNoDedup(0x013000, 0x013455));
    }

    private static void populateSCX_ELBA() {
        SET_ENCODINGS.put("scx=Elba", CodePointSet.createNoDedup(0x010500, 0x010527));
    }

    private static void populateSCX_ELYM() {
        SET_ENCODINGS.put("scx=Elym", CodePointSet.createNoDedup(0x010fe0, 0x010ff6));
    }

    private static void populateSCX_ETHI() {
        SET_ENCODINGS.put("scx=Ethi",
                        CodePointSet.createNoDedup(0x001200, 0x001248, 0x00124a, 0x00124d, 0x001250, 0x001256, 0x001258, 0x001258, 0x00125a, 0x00125d, 0x001260, 0x001288, 0x00128a, 0x00128d, 0x001290,
                                        0x0012b0, 0x0012b2, 0x0012b5, 0x0012b8, 0x0012be, 0x0012c0, 0x0012c0, 0x0012c2, 0x0012c5, 0x0012c8, 0x0012d6, 0x0012d8, 0x001310, 0x001312, 0x001315, 0x001318,
                                        0x00135a, 0x00135d, 0x00137c, 0x001380, 0x001399, 0x002d80, 0x002d96, 0x002da0, 0x002da6, 0x002da8, 0x002dae, 0x002db0, 0x002db6, 0x002db8, 0x002dbe, 0x002dc0,
                                        0x002dc6, 0x002dc8, 0x002dce, 0x002dd0, 0x002dd6, 0x002dd8, 0x002dde, 0x00ab01, 0x00ab06, 0x00ab09, 0x00ab0e, 0x00ab11, 0x00ab16, 0x00ab20, 0x00ab26, 0x00ab28,
                                        0x00ab2e, 0x01e7e0, 0x01e7e6, 0x01e7e8, 0x01e7eb, 0x01e7ed, 0x01e7ee, 0x01e7f0, 0x01e7fe));
    }

    private static void populateSCX_GEOR() {
        SET_ENCODINGS.put("scx=Geor", CodePointSet.createNoDedup(0x0010a0, 0x0010c5, 0x0010c7, 0x0010c7, 0x0010cd, 0x0010cd, 0x0010d0, 0x0010ff, 0x001c90, 0x001cba, 0x001cbd, 0x001cbf, 0x002d00,
                        0x002d25, 0x002d27, 0x002d27, 0x002d2d, 0x002d2d));
    }

    private static void populateSCX_GLAG() {
        SET_ENCODINGS.put("scx=Glag", CodePointSet.createNoDedup(0x000484, 0x000484, 0x000487, 0x000487, 0x002c00, 0x002c5f, 0x002e43, 0x002e43, 0x00a66f, 0x00a66f, 0x01e000, 0x01e006, 0x01e008,
                        0x01e018, 0x01e01b, 0x01e021, 0x01e023, 0x01e024, 0x01e026, 0x01e02a));
    }

    private static void populateSCX_GONG() {
        SET_ENCODINGS.put("scx=Gong",
                        CodePointSet.createNoDedup(0x000964, 0x000965, 0x011d60, 0x011d65, 0x011d67, 0x011d68, 0x011d6a, 0x011d8e, 0x011d90, 0x011d91, 0x011d93, 0x011d98, 0x011da0, 0x011da9));
    }

    private static void populateSCX_GONM() {
        SET_ENCODINGS.put("scx=Gonm", CodePointSet.createNoDedup(0x000964, 0x000965, 0x011d00, 0x011d06, 0x011d08, 0x011d09, 0x011d0b, 0x011d36, 0x011d3a, 0x011d3a, 0x011d3c, 0x011d3d, 0x011d3f,
                        0x011d47, 0x011d50, 0x011d59));
    }

    private static void populateSCX_GOTH() {
        SET_ENCODINGS.put("scx=Goth", CodePointSet.createNoDedup(0x010330, 0x01034a));
    }

    private static void populateSCX_GRAN() {
        SET_ENCODINGS.put("scx=Gran",
                        CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000be6, 0x000bf3, 0x001cd0, 0x001cd0, 0x001cd2, 0x001cd3, 0x001cf2, 0x001cf4, 0x001cf8, 0x001cf9, 0x0020f0,
                                        0x0020f0, 0x011300, 0x011303, 0x011305, 0x01130c, 0x01130f, 0x011310, 0x011313, 0x011328, 0x01132a, 0x011330, 0x011332, 0x011333, 0x011335, 0x011339, 0x01133b,
                                        0x011344, 0x011347, 0x011348, 0x01134b, 0x01134d, 0x011350, 0x011350, 0x011357, 0x011357, 0x01135d, 0x011363, 0x011366, 0x01136c, 0x011370, 0x011374, 0x011fd0,
                                        0x011fd1, 0x011fd3, 0x011fd3));
    }

    private static void populateSCX_GREK() {
        SET_ENCODINGS.put("scx=Grek",
                        CodePointSet.createNoDedup(0x000342, 0x000342, 0x000345, 0x000345, 0x000370, 0x000373, 0x000375, 0x000377, 0x00037a, 0x00037d, 0x00037f, 0x00037f, 0x000384, 0x000384, 0x000386,
                                        0x000386, 0x000388, 0x00038a, 0x00038c, 0x00038c, 0x00038e, 0x0003a1, 0x0003a3, 0x0003e1, 0x0003f0, 0x0003ff, 0x001d26, 0x001d2a, 0x001d5d, 0x001d61, 0x001d66,
                                        0x001d6a, 0x001dbf, 0x001dc1, 0x001f00, 0x001f15, 0x001f18, 0x001f1d, 0x001f20, 0x001f45, 0x001f48, 0x001f4d, 0x001f50, 0x001f57, 0x001f59, 0x001f59, 0x001f5b,
                                        0x001f5b, 0x001f5d, 0x001f5d, 0x001f5f, 0x001f7d, 0x001f80, 0x001fb4, 0x001fb6, 0x001fc4, 0x001fc6, 0x001fd3, 0x001fd6, 0x001fdb, 0x001fdd, 0x001fef, 0x001ff2,
                                        0x001ff4, 0x001ff6, 0x001ffe, 0x002126, 0x002126, 0x00ab65, 0x00ab65, 0x010140, 0x01018e, 0x0101a0, 0x0101a0, 0x01d200, 0x01d245));
    }

    private static void populateSCX_GUJR() {
        SET_ENCODINGS.put("scx=Gujr",
                        CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000a81, 0x000a83, 0x000a85, 0x000a8d, 0x000a8f, 0x000a91, 0x000a93, 0x000aa8, 0x000aaa, 0x000ab0, 0x000ab2,
                                        0x000ab3, 0x000ab5, 0x000ab9, 0x000abc, 0x000ac5, 0x000ac7, 0x000ac9, 0x000acb, 0x000acd, 0x000ad0, 0x000ad0, 0x000ae0, 0x000ae3, 0x000ae6, 0x000af1, 0x000af9,
                                        0x000aff, 0x00a830, 0x00a839));
    }

    private static void populateSCX_GURU() {
        SET_ENCODINGS.put("scx=Guru",
                        CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000a01, 0x000a03, 0x000a05, 0x000a0a, 0x000a0f, 0x000a10, 0x000a13, 0x000a28, 0x000a2a, 0x000a30, 0x000a32,
                                        0x000a33, 0x000a35, 0x000a36, 0x000a38, 0x000a39, 0x000a3c, 0x000a3c, 0x000a3e, 0x000a42, 0x000a47, 0x000a48, 0x000a4b, 0x000a4d, 0x000a51, 0x000a51, 0x000a59,
                                        0x000a5c, 0x000a5e, 0x000a5e, 0x000a66, 0x000a76, 0x00a830, 0x00a839));
    }

    private static void populateSCX_HANG() {
        SET_ENCODINGS.put("scx=Hang",
                        CodePointSet.createNoDedup(0x001100, 0x0011ff, 0x003001, 0x003003, 0x003008, 0x003011, 0x003013, 0x00301f, 0x00302e, 0x003030, 0x003037, 0x003037, 0x0030fb, 0x0030fb, 0x003131,
                                        0x00318e, 0x003200, 0x00321e, 0x003260, 0x00327e, 0x00a960, 0x00a97c, 0x00ac00, 0x00d7a3, 0x00d7b0, 0x00d7c6, 0x00d7cb, 0x00d7fb, 0x00fe45, 0x00fe46, 0x00ff61,
                                        0x00ff65, 0x00ffa0, 0x00ffbe, 0x00ffc2, 0x00ffc7, 0x00ffca, 0x00ffcf, 0x00ffd2, 0x00ffd7, 0x00ffda, 0x00ffdc));
    }

    private static void populateSCX_HANI() {
        SET_ENCODINGS.put("scx=Hani",
                        CodePointSet.createNoDedup(0x002e80, 0x002e99, 0x002e9b, 0x002ef3, 0x002f00, 0x002fd5, 0x003001, 0x003003, 0x003005, 0x003011, 0x003013, 0x00301f, 0x003021, 0x00302d, 0x003030,
                                        0x003030, 0x003037, 0x00303f, 0x0030fb, 0x0030fb, 0x003190, 0x00319f, 0x0031c0, 0x0031e3, 0x003220, 0x003247, 0x003280, 0x0032b0, 0x0032c0, 0x0032cb, 0x0032ff,
                                        0x0032ff, 0x003358, 0x003370, 0x00337b, 0x00337f, 0x0033e0, 0x0033fe, 0x003400, 0x004dbf, 0x004e00, 0x009fff, 0x00a700, 0x00a707, 0x00f900, 0x00fa6d, 0x00fa70,
                                        0x00fad9, 0x00fe45, 0x00fe46, 0x00ff61, 0x00ff65, 0x016fe2, 0x016fe3, 0x016ff0, 0x016ff1, 0x01d360, 0x01d371, 0x01f250, 0x01f251, 0x020000, 0x02a6df, 0x02a700,
                                        0x02b739, 0x02b740, 0x02b81d, 0x02b820, 0x02cea1, 0x02ceb0, 0x02ebe0, 0x02f800, 0x02fa1d, 0x030000, 0x03134a, 0x031350, 0x0323af));
    }

    private static void populateSCX_HANO() {
        SET_ENCODINGS.put("scx=Hano", CodePointSet.createNoDedup(0x001720, 0x001736));
    }

    private static void populateSCX_HATR() {
        SET_ENCODINGS.put("scx=Hatr", CodePointSet.createNoDedup(0x0108e0, 0x0108f2, 0x0108f4, 0x0108f5, 0x0108fb, 0x0108ff));
    }

    private static void populateSCX_HEBR() {
        SET_ENCODINGS.put("scx=Hebr", CodePointSet.createNoDedup(0x000591, 0x0005c7, 0x0005d0, 0x0005ea, 0x0005ef, 0x0005f4, 0x00fb1d, 0x00fb36, 0x00fb38, 0x00fb3c, 0x00fb3e, 0x00fb3e, 0x00fb40,
                        0x00fb41, 0x00fb43, 0x00fb44, 0x00fb46, 0x00fb4f));
    }

    private static void populateSCX_HIRA() {
        SET_ENCODINGS.put("scx=Hira",
                        CodePointSet.createNoDedup(0x003001, 0x003003, 0x003008, 0x003011, 0x003013, 0x00301f, 0x003030, 0x003035, 0x003037, 0x003037, 0x00303c, 0x00303d, 0x003041, 0x003096, 0x003099,
                                        0x0030a0, 0x0030fb, 0x0030fc, 0x00fe45, 0x00fe46, 0x00ff61, 0x00ff65, 0x00ff70, 0x00ff70, 0x00ff9e, 0x00ff9f, 0x01b001, 0x01b11f, 0x01b132, 0x01b132, 0x01b150,
                                        0x01b152, 0x01f200, 0x01f200));
    }

    private static void populateSCX_HLUW() {
        SET_ENCODINGS.put("scx=Hluw", CodePointSet.createNoDedup(0x014400, 0x014646));
    }

    private static void populateSCX_HMNG() {
        SET_ENCODINGS.put("scx=Hmng", CodePointSet.createNoDedup(0x016b00, 0x016b45, 0x016b50, 0x016b59, 0x016b5b, 0x016b61, 0x016b63, 0x016b77, 0x016b7d, 0x016b8f));
    }

    private static void populateSCX_HMNP() {
        SET_ENCODINGS.put("scx=Hmnp", CodePointSet.createNoDedup(0x01e100, 0x01e12c, 0x01e130, 0x01e13d, 0x01e140, 0x01e149, 0x01e14e, 0x01e14f));
    }

    private static void populateSCX_HUNG() {
        SET_ENCODINGS.put("scx=Hung", CodePointSet.createNoDedup(0x010c80, 0x010cb2, 0x010cc0, 0x010cf2, 0x010cfa, 0x010cff));
    }

    private static void populateSCX_ITAL() {
        SET_ENCODINGS.put("scx=Ital", CodePointSet.createNoDedup(0x010300, 0x010323, 0x01032d, 0x01032f));
    }

    private static void populateSCX_JAVA() {
        SET_ENCODINGS.put("scx=Java", CodePointSet.createNoDedup(0x00a980, 0x00a9cd, 0x00a9cf, 0x00a9d9, 0x00a9de, 0x00a9df));
    }

    private static void populateSCX_KALI() {
        SET_ENCODINGS.put("scx=Kali", CodePointSet.createNoDedup(0x00a900, 0x00a92f));
    }

    private static void populateSCX_KANA() {
        SET_ENCODINGS.put("scx=Kana",
                        CodePointSet.createNoDedup(0x003001, 0x003003, 0x003008, 0x003011, 0x003013, 0x00301f, 0x003030, 0x003035, 0x003037, 0x003037, 0x00303c, 0x00303d, 0x003099, 0x00309c, 0x0030a0,
                                        0x0030ff, 0x0031f0, 0x0031ff, 0x0032d0, 0x0032fe, 0x003300, 0x003357, 0x00fe45, 0x00fe46, 0x00ff61, 0x00ff9f, 0x01aff0, 0x01aff3, 0x01aff5, 0x01affb, 0x01affd,
                                        0x01affe, 0x01b000, 0x01b000, 0x01b120, 0x01b122, 0x01b155, 0x01b155, 0x01b164, 0x01b167));
    }

    private static void populateSCX_KAWI() {
        SET_ENCODINGS.put("scx=Kawi", CodePointSet.createNoDedup(0x011f00, 0x011f10, 0x011f12, 0x011f3a, 0x011f3e, 0x011f59));
    }

    private static void populateSCX_KHAR() {
        SET_ENCODINGS.put("scx=Khar", CodePointSet.createNoDedup(0x010a00, 0x010a03, 0x010a05, 0x010a06, 0x010a0c, 0x010a13, 0x010a15, 0x010a17, 0x010a19, 0x010a35, 0x010a38, 0x010a3a, 0x010a3f,
                        0x010a48, 0x010a50, 0x010a58));
    }

    private static void populateSCX_KHMR() {
        SET_ENCODINGS.put("scx=Khmr", CodePointSet.createNoDedup(0x001780, 0x0017dd, 0x0017e0, 0x0017e9, 0x0017f0, 0x0017f9, 0x0019e0, 0x0019ff));
    }

    private static void populateSCX_KHOJ() {
        SET_ENCODINGS.put("scx=Khoj", CodePointSet.createNoDedup(0x000ae6, 0x000aef, 0x00a830, 0x00a839, 0x011200, 0x011211, 0x011213, 0x011241));
    }

    private static void populateSCX_KITS() {
        SET_ENCODINGS.put("scx=Kits", CodePointSet.createNoDedup(0x016fe4, 0x016fe4, 0x018b00, 0x018cd5));
    }

    private static void populateSCX_KNDA() {
        SET_ENCODINGS.put("scx=Knda",
                        CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000c80, 0x000c8c, 0x000c8e, 0x000c90, 0x000c92, 0x000ca8, 0x000caa, 0x000cb3, 0x000cb5, 0x000cb9, 0x000cbc,
                                        0x000cc4, 0x000cc6, 0x000cc8, 0x000cca, 0x000ccd, 0x000cd5, 0x000cd6, 0x000cdd, 0x000cde, 0x000ce0, 0x000ce3, 0x000ce6, 0x000cef, 0x000cf1, 0x000cf3, 0x001cd0,
                                        0x001cd0, 0x001cd2, 0x001cd2, 0x001cda, 0x001cda, 0x001cf2, 0x001cf2, 0x001cf4, 0x001cf4, 0x00a830, 0x00a835));
    }

    private static void populateSCX_KTHI() {
        SET_ENCODINGS.put("scx=Kthi", CodePointSet.createNoDedup(0x000966, 0x00096f, 0x00a830, 0x00a839, 0x011080, 0x0110c2, 0x0110cd, 0x0110cd));
    }

    private static void populateSCX_LANA() {
        SET_ENCODINGS.put("scx=Lana", CodePointSet.createNoDedup(0x001a20, 0x001a5e, 0x001a60, 0x001a7c, 0x001a7f, 0x001a89, 0x001a90, 0x001a99, 0x001aa0, 0x001aad));
    }

    private static void populateSCX_LAOO() {
        SET_ENCODINGS.put("scx=Laoo", CodePointSet.createNoDedup(0x000e81, 0x000e82, 0x000e84, 0x000e84, 0x000e86, 0x000e8a, 0x000e8c, 0x000ea3, 0x000ea5, 0x000ea5, 0x000ea7, 0x000ebd, 0x000ec0,
                        0x000ec4, 0x000ec6, 0x000ec6, 0x000ec8, 0x000ece, 0x000ed0, 0x000ed9, 0x000edc, 0x000edf));
    }

    private static void populateSCX_LATN() {
        SET_ENCODINGS.put("scx=Latn",
                        CodePointSet.createNoDedup(0x000041, 0x00005a, 0x000061, 0x00007a, 0x0000aa, 0x0000aa, 0x0000ba, 0x0000ba, 0x0000c0, 0x0000d6, 0x0000d8, 0x0000f6, 0x0000f8, 0x0002b8, 0x0002e0,
                                        0x0002e4, 0x000363, 0x00036f, 0x000485, 0x000486, 0x000951, 0x000952, 0x0010fb, 0x0010fb, 0x001d00, 0x001d25, 0x001d2c, 0x001d5c, 0x001d62, 0x001d65, 0x001d6b,
                                        0x001d77, 0x001d79, 0x001dbe, 0x001e00, 0x001eff, 0x00202f, 0x00202f, 0x002071, 0x002071, 0x00207f, 0x00207f, 0x002090, 0x00209c, 0x0020f0, 0x0020f0, 0x00212a,
                                        0x00212b, 0x002132, 0x002132, 0x00214e, 0x00214e, 0x002160, 0x002188, 0x002c60, 0x002c7f, 0x00a700, 0x00a707, 0x00a722, 0x00a787, 0x00a78b, 0x00a7ca, 0x00a7d0,
                                        0x00a7d1, 0x00a7d3, 0x00a7d3, 0x00a7d5, 0x00a7d9, 0x00a7f2, 0x00a7ff, 0x00a92e, 0x00a92e, 0x00ab30, 0x00ab5a, 0x00ab5c, 0x00ab64, 0x00ab66, 0x00ab69, 0x00fb00,
                                        0x00fb06, 0x00ff21, 0x00ff3a, 0x00ff41, 0x00ff5a, 0x010780, 0x010785, 0x010787, 0x0107b0, 0x0107b2, 0x0107ba, 0x01df00, 0x01df1e, 0x01df25, 0x01df2a));
    }

    private static void populateSCX_LEPC() {
        SET_ENCODINGS.put("scx=Lepc", CodePointSet.createNoDedup(0x001c00, 0x001c37, 0x001c3b, 0x001c49, 0x001c4d, 0x001c4f));
    }

    private static void populateSCX_LIMB() {
        SET_ENCODINGS.put("scx=Limb", CodePointSet.createNoDedup(0x000965, 0x000965, 0x001900, 0x00191e, 0x001920, 0x00192b, 0x001930, 0x00193b, 0x001940, 0x001940, 0x001944, 0x00194f));
    }

    private static void populateSCX_LINA() {
        SET_ENCODINGS.put("scx=Lina", CodePointSet.createNoDedup(0x010107, 0x010133, 0x010600, 0x010736, 0x010740, 0x010755, 0x010760, 0x010767));
    }

    private static void populateSCX_LINB() {
        SET_ENCODINGS.put("scx=Linb", CodePointSet.createNoDedup(0x010000, 0x01000b, 0x01000d, 0x010026, 0x010028, 0x01003a, 0x01003c, 0x01003d, 0x01003f, 0x01004d, 0x010050, 0x01005d, 0x010080,
                        0x0100fa, 0x010100, 0x010102, 0x010107, 0x010133, 0x010137, 0x01013f));
    }

    private static void populateSCX_LISU() {
        SET_ENCODINGS.put("scx=Lisu", CodePointSet.createNoDedup(0x00a4d0, 0x00a4ff, 0x011fb0, 0x011fb0));
    }

    private static void populateSCX_LYCI() {
        SET_ENCODINGS.put("scx=Lyci", CodePointSet.createNoDedup(0x010280, 0x01029c));
    }

    private static void populateSCX_LYDI() {
        SET_ENCODINGS.put("scx=Lydi", CodePointSet.createNoDedup(0x010920, 0x010939, 0x01093f, 0x01093f));
    }

    private static void populateSCX_MAHJ() {
        SET_ENCODINGS.put("scx=Mahj", CodePointSet.createNoDedup(0x000964, 0x00096f, 0x00a830, 0x00a839, 0x011150, 0x011176));
    }

    private static void populateSCX_MAKA() {
        SET_ENCODINGS.put("scx=Maka", CodePointSet.createNoDedup(0x011ee0, 0x011ef8));
    }

    private static void populateSCX_MAND() {
        SET_ENCODINGS.put("scx=Mand", CodePointSet.createNoDedup(0x000640, 0x000640, 0x000840, 0x00085b, 0x00085e, 0x00085e));
    }

    private static void populateSCX_MANI() {
        SET_ENCODINGS.put("scx=Mani", CodePointSet.createNoDedup(0x000640, 0x000640, 0x010ac0, 0x010ae6, 0x010aeb, 0x010af6));
    }

    private static void populateSCX_MARC() {
        SET_ENCODINGS.put("scx=Marc", CodePointSet.createNoDedup(0x011c70, 0x011c8f, 0x011c92, 0x011ca7, 0x011ca9, 0x011cb6));
    }

    private static void populateSCX_MEDF() {
        SET_ENCODINGS.put("scx=Medf", CodePointSet.createNoDedup(0x016e40, 0x016e9a));
    }

    private static void populateSCX_MEND() {
        SET_ENCODINGS.put("scx=Mend", CodePointSet.createNoDedup(0x01e800, 0x01e8c4, 0x01e8c7, 0x01e8d6));
    }

    private static void populateSCX_MERC() {
        SET_ENCODINGS.put("scx=Merc", CodePointSet.createNoDedup(0x0109a0, 0x0109b7, 0x0109bc, 0x0109cf, 0x0109d2, 0x0109ff));
    }

    private static void populateSCX_MERO() {
        SET_ENCODINGS.put("scx=Mero", CodePointSet.createNoDedup(0x010980, 0x01099f));
    }

    private static void populateSCX_MLYM() {
        SET_ENCODINGS.put("scx=Mlym", CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000d00, 0x000d0c, 0x000d0e, 0x000d10, 0x000d12, 0x000d44, 0x000d46, 0x000d48, 0x000d4a,
                        0x000d4f, 0x000d54, 0x000d63, 0x000d66, 0x000d7f, 0x001cda, 0x001cda, 0x00a830, 0x00a832));
    }

    private static void populateSCX_MODI() {
        SET_ENCODINGS.put("scx=Modi", CodePointSet.createNoDedup(0x00a830, 0x00a839, 0x011600, 0x011644, 0x011650, 0x011659));
    }

    private static void populateSCX_MONG() {
        SET_ENCODINGS.put("scx=Mong", CodePointSet.createNoDedup(0x001800, 0x001819, 0x001820, 0x001878, 0x001880, 0x0018aa, 0x00202f, 0x00202f, 0x011660, 0x01166c));
    }

    private static void populateSCX_MROO() {
        SET_ENCODINGS.put("scx=Mroo", CodePointSet.createNoDedup(0x016a40, 0x016a5e, 0x016a60, 0x016a69, 0x016a6e, 0x016a6f));
    }

    private static void populateSCX_MTEI() {
        SET_ENCODINGS.put("scx=Mtei", CodePointSet.createNoDedup(0x00aae0, 0x00aaf6, 0x00abc0, 0x00abed, 0x00abf0, 0x00abf9));
    }

    private static void populateSCX_MULT() {
        SET_ENCODINGS.put("scx=Mult", CodePointSet.createNoDedup(0x000a66, 0x000a6f, 0x011280, 0x011286, 0x011288, 0x011288, 0x01128a, 0x01128d, 0x01128f, 0x01129d, 0x01129f, 0x0112a9));
    }

    private static void populateSCX_MYMR() {
        SET_ENCODINGS.put("scx=Mymr", CodePointSet.createNoDedup(0x001000, 0x00109f, 0x00a92e, 0x00a92e, 0x00a9e0, 0x00a9fe, 0x00aa60, 0x00aa7f));
    }

    private static void populateSCX_NAGM() {
        SET_ENCODINGS.put("scx=Nagm", CodePointSet.createNoDedup(0x01e4d0, 0x01e4f9));
    }

    private static void populateSCX_NAND() {
        SET_ENCODINGS.put("scx=Nand", CodePointSet.createNoDedup(0x000964, 0x000965, 0x000ce6, 0x000cef, 0x001ce9, 0x001ce9, 0x001cf2, 0x001cf2, 0x001cfa, 0x001cfa, 0x00a830, 0x00a835, 0x0119a0,
                        0x0119a7, 0x0119aa, 0x0119d7, 0x0119da, 0x0119e4));
    }

    private static void populateSCX_NARB() {
        SET_ENCODINGS.put("scx=Narb", CodePointSet.createNoDedup(0x010a80, 0x010a9f));
    }

    private static void populateSCX_NBAT() {
        SET_ENCODINGS.put("scx=Nbat", CodePointSet.createNoDedup(0x010880, 0x01089e, 0x0108a7, 0x0108af));
    }

    private static void populateSCX_NEWA() {
        SET_ENCODINGS.put("scx=Newa", CodePointSet.createNoDedup(0x011400, 0x01145b, 0x01145d, 0x011461));
    }

    private static void populateSCX_NKOO() {
        SET_ENCODINGS.put("scx=Nkoo", CodePointSet.createNoDedup(0x00060c, 0x00060c, 0x00061b, 0x00061b, 0x00061f, 0x00061f, 0x0007c0, 0x0007fa, 0x0007fd, 0x0007ff, 0x00fd3e, 0x00fd3f));
    }

    private static void populateSCX_NSHU() {
        SET_ENCODINGS.put("scx=Nshu", CodePointSet.createNoDedup(0x016fe1, 0x016fe1, 0x01b170, 0x01b2fb));
    }

    private static void populateSCX_OGAM() {
        SET_ENCODINGS.put("scx=Ogam", CodePointSet.createNoDedup(0x001680, 0x00169c));
    }

    private static void populateSCX_OLCK() {
        SET_ENCODINGS.put("scx=Olck", CodePointSet.createNoDedup(0x001c50, 0x001c7f));
    }

    private static void populateSCX_ORKH() {
        SET_ENCODINGS.put("scx=Orkh", CodePointSet.createNoDedup(0x010c00, 0x010c48));
    }

    private static void populateSCX_ORYA() {
        SET_ENCODINGS.put("scx=Orya",
                        CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000b01, 0x000b03, 0x000b05, 0x000b0c, 0x000b0f, 0x000b10, 0x000b13, 0x000b28, 0x000b2a, 0x000b30, 0x000b32,
                                        0x000b33, 0x000b35, 0x000b39, 0x000b3c, 0x000b44, 0x000b47, 0x000b48, 0x000b4b, 0x000b4d, 0x000b55, 0x000b57, 0x000b5c, 0x000b5d, 0x000b5f, 0x000b63, 0x000b66,
                                        0x000b77, 0x001cda, 0x001cda, 0x001cf2, 0x001cf2));
    }

    private static void populateSCX_OSGE() {
        SET_ENCODINGS.put("scx=Osge", CodePointSet.createNoDedup(0x0104b0, 0x0104d3, 0x0104d8, 0x0104fb));
    }

    private static void populateSCX_OSMA() {
        SET_ENCODINGS.put("scx=Osma", CodePointSet.createNoDedup(0x010480, 0x01049d, 0x0104a0, 0x0104a9));
    }

    private static void populateSCX_OUGR() {
        SET_ENCODINGS.put("scx=Ougr", CodePointSet.createNoDedup(0x000640, 0x000640, 0x010af2, 0x010af2, 0x010f70, 0x010f89));
    }

    private static void populateSCX_PALM() {
        SET_ENCODINGS.put("scx=Palm", CodePointSet.createNoDedup(0x010860, 0x01087f));
    }

    private static void populateSCX_PAUC() {
        SET_ENCODINGS.put("scx=Pauc", CodePointSet.createNoDedup(0x011ac0, 0x011af8));
    }

    private static void populateSCX_PERM() {
        SET_ENCODINGS.put("scx=Perm", CodePointSet.createNoDedup(0x000483, 0x000483, 0x010350, 0x01037a));
    }

    private static void populateSCX_PHAG() {
        SET_ENCODINGS.put("scx=Phag", CodePointSet.createNoDedup(0x001802, 0x001803, 0x001805, 0x001805, 0x00a840, 0x00a877));
    }

    private static void populateSCX_PHLI() {
        SET_ENCODINGS.put("scx=Phli", CodePointSet.createNoDedup(0x010b60, 0x010b72, 0x010b78, 0x010b7f));
    }

    private static void populateSCX_PHLP() {
        SET_ENCODINGS.put("scx=Phlp", CodePointSet.createNoDedup(0x000640, 0x000640, 0x010b80, 0x010b91, 0x010b99, 0x010b9c, 0x010ba9, 0x010baf));
    }

    private static void populateSCX_PHNX() {
        SET_ENCODINGS.put("scx=Phnx", CodePointSet.createNoDedup(0x010900, 0x01091b, 0x01091f, 0x01091f));
    }

    private static void populateSCX_PLRD() {
        SET_ENCODINGS.put("scx=Plrd", CodePointSet.createNoDedup(0x016f00, 0x016f4a, 0x016f4f, 0x016f87, 0x016f8f, 0x016f9f));
    }

    private static void populateSCX_PRTI() {
        SET_ENCODINGS.put("scx=Prti", CodePointSet.createNoDedup(0x010b40, 0x010b55, 0x010b58, 0x010b5f));
    }

    private static void populateSCX_RJNG() {
        SET_ENCODINGS.put("scx=Rjng", CodePointSet.createNoDedup(0x00a930, 0x00a953, 0x00a95f, 0x00a95f));
    }

    private static void populateSCX_ROHG() {
        SET_ENCODINGS.put("scx=Rohg",
                        CodePointSet.createNoDedup(0x00060c, 0x00060c, 0x00061b, 0x00061b, 0x00061f, 0x00061f, 0x000640, 0x000640, 0x0006d4, 0x0006d4, 0x010d00, 0x010d27, 0x010d30, 0x010d39));
    }

    private static void populateSCX_RUNR() {
        SET_ENCODINGS.put("scx=Runr", CodePointSet.createNoDedup(0x0016a0, 0x0016ea, 0x0016ee, 0x0016f8));
    }

    private static void populateSCX_SAMR() {
        SET_ENCODINGS.put("scx=Samr", CodePointSet.createNoDedup(0x000800, 0x00082d, 0x000830, 0x00083e));
    }

    private static void populateSCX_SARB() {
        SET_ENCODINGS.put("scx=Sarb", CodePointSet.createNoDedup(0x010a60, 0x010a7f));
    }

    private static void populateSCX_SAUR() {
        SET_ENCODINGS.put("scx=Saur", CodePointSet.createNoDedup(0x00a880, 0x00a8c5, 0x00a8ce, 0x00a8d9));
    }

    private static void populateSCX_SGNW() {
        SET_ENCODINGS.put("scx=Sgnw", CodePointSet.createNoDedup(0x01d800, 0x01da8b, 0x01da9b, 0x01da9f, 0x01daa1, 0x01daaf));
    }

    private static void populateSCX_SHAW() {
        SET_ENCODINGS.put("scx=Shaw", CodePointSet.createNoDedup(0x010450, 0x01047f));
    }

    private static void populateSCX_SHRD() {
        SET_ENCODINGS.put("scx=Shrd", CodePointSet.createNoDedup(0x000951, 0x000951, 0x001cd7, 0x001cd7, 0x001cd9, 0x001cd9, 0x001cdc, 0x001cdd, 0x001ce0, 0x001ce0, 0x011180, 0x0111df));
    }

    private static void populateSCX_SIDD() {
        SET_ENCODINGS.put("scx=Sidd", CodePointSet.createNoDedup(0x011580, 0x0115b5, 0x0115b8, 0x0115dd));
    }

    private static void populateSCX_SIND() {
        SET_ENCODINGS.put("scx=Sind", CodePointSet.createNoDedup(0x000964, 0x000965, 0x00a830, 0x00a839, 0x0112b0, 0x0112ea, 0x0112f0, 0x0112f9));
    }

    private static void populateSCX_SINH() {
        SET_ENCODINGS.put("scx=Sinh", CodePointSet.createNoDedup(0x000964, 0x000965, 0x000d81, 0x000d83, 0x000d85, 0x000d96, 0x000d9a, 0x000db1, 0x000db3, 0x000dbb, 0x000dbd, 0x000dbd, 0x000dc0,
                        0x000dc6, 0x000dca, 0x000dca, 0x000dcf, 0x000dd4, 0x000dd6, 0x000dd6, 0x000dd8, 0x000ddf, 0x000de6, 0x000def, 0x000df2, 0x000df4, 0x0111e1, 0x0111f4));
    }

    private static void populateSCX_SOGD() {
        SET_ENCODINGS.put("scx=Sogd", CodePointSet.createNoDedup(0x000640, 0x000640, 0x010f30, 0x010f59));
    }

    private static void populateSCX_SOGO() {
        SET_ENCODINGS.put("scx=Sogo", CodePointSet.createNoDedup(0x010f00, 0x010f27));
    }

    private static void populateSCX_SORA() {
        SET_ENCODINGS.put("scx=Sora", CodePointSet.createNoDedup(0x0110d0, 0x0110e8, 0x0110f0, 0x0110f9));
    }

    private static void populateSCX_SOYO() {
        SET_ENCODINGS.put("scx=Soyo", CodePointSet.createNoDedup(0x011a50, 0x011aa2));
    }

    private static void populateSCX_SUND() {
        SET_ENCODINGS.put("scx=Sund", CodePointSet.createNoDedup(0x001b80, 0x001bbf, 0x001cc0, 0x001cc7));
    }

    private static void populateSCX_SYLO() {
        SET_ENCODINGS.put("scx=Sylo", CodePointSet.createNoDedup(0x000964, 0x000965, 0x0009e6, 0x0009ef, 0x00a800, 0x00a82c));
    }

    private static void populateSCX_SYRC() {
        SET_ENCODINGS.put("scx=Syrc", CodePointSet.createNoDedup(0x00060c, 0x00060c, 0x00061b, 0x00061c, 0x00061f, 0x00061f, 0x000640, 0x000640, 0x00064b, 0x000655, 0x000670, 0x000670, 0x000700,
                        0x00070d, 0x00070f, 0x00074a, 0x00074d, 0x00074f, 0x000860, 0x00086a, 0x001df8, 0x001df8, 0x001dfa, 0x001dfa));
    }

    private static void populateSCX_TAGB() {
        SET_ENCODINGS.put("scx=Tagb", CodePointSet.createNoDedup(0x001735, 0x001736, 0x001760, 0x00176c, 0x00176e, 0x001770, 0x001772, 0x001773));
    }

    private static void populateSCX_TAKR() {
        SET_ENCODINGS.put("scx=Takr", CodePointSet.createNoDedup(0x000964, 0x000965, 0x00a830, 0x00a839, 0x011680, 0x0116b9, 0x0116c0, 0x0116c9));
    }

    private static void populateSCX_TALE() {
        SET_ENCODINGS.put("scx=Tale", CodePointSet.createNoDedup(0x001040, 0x001049, 0x001950, 0x00196d, 0x001970, 0x001974));
    }

    private static void populateSCX_TALU() {
        SET_ENCODINGS.put("scx=Talu", CodePointSet.createNoDedup(0x001980, 0x0019ab, 0x0019b0, 0x0019c9, 0x0019d0, 0x0019da, 0x0019de, 0x0019df));
    }

    private static void populateSCX_TAML() {
        SET_ENCODINGS.put("scx=Taml",
                        CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000b82, 0x000b83, 0x000b85, 0x000b8a, 0x000b8e, 0x000b90, 0x000b92, 0x000b95, 0x000b99, 0x000b9a, 0x000b9c,
                                        0x000b9c, 0x000b9e, 0x000b9f, 0x000ba3, 0x000ba4, 0x000ba8, 0x000baa, 0x000bae, 0x000bb9, 0x000bbe, 0x000bc2, 0x000bc6, 0x000bc8, 0x000bca, 0x000bcd, 0x000bd0,
                                        0x000bd0, 0x000bd7, 0x000bd7, 0x000be6, 0x000bfa, 0x001cda, 0x001cda, 0x00a8f3, 0x00a8f3, 0x011301, 0x011301, 0x011303, 0x011303, 0x01133b, 0x01133c, 0x011fc0,
                                        0x011ff1, 0x011fff, 0x011fff));
    }

    private static void populateSCX_TANG() {
        SET_ENCODINGS.put("scx=Tang", CodePointSet.createNoDedup(0x016fe0, 0x016fe0, 0x017000, 0x0187f7, 0x018800, 0x018aff, 0x018d00, 0x018d08));
    }

    private static void populateSCX_TAVT() {
        SET_ENCODINGS.put("scx=Tavt", CodePointSet.createNoDedup(0x00aa80, 0x00aac2, 0x00aadb, 0x00aadf));
    }

    private static void populateSCX_TELU() {
        SET_ENCODINGS.put("scx=Telu",
                        CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x000c00, 0x000c0c, 0x000c0e, 0x000c10, 0x000c12, 0x000c28, 0x000c2a, 0x000c39, 0x000c3c, 0x000c44, 0x000c46,
                                        0x000c48, 0x000c4a, 0x000c4d, 0x000c55, 0x000c56, 0x000c58, 0x000c5a, 0x000c5d, 0x000c5d, 0x000c60, 0x000c63, 0x000c66, 0x000c6f, 0x000c77, 0x000c7f, 0x001cda,
                                        0x001cda, 0x001cf2, 0x001cf2));
    }

    private static void populateSCX_TFNG() {
        SET_ENCODINGS.put("scx=Tfng", CodePointSet.createNoDedup(0x002d30, 0x002d67, 0x002d6f, 0x002d70, 0x002d7f, 0x002d7f));
    }

    private static void populateSCX_TGLG() {
        SET_ENCODINGS.put("scx=Tglg", CodePointSet.createNoDedup(0x001700, 0x001715, 0x00171f, 0x00171f, 0x001735, 0x001736));
    }

    private static void populateSCX_THAA() {
        SET_ENCODINGS.put("scx=Thaa",
                        CodePointSet.createNoDedup(0x00060c, 0x00060c, 0x00061b, 0x00061c, 0x00061f, 0x00061f, 0x000660, 0x000669, 0x000780, 0x0007b1, 0x00fdf2, 0x00fdf2, 0x00fdfd, 0x00fdfd));
    }

    private static void populateSCX_THAI() {
        SET_ENCODINGS.put("scx=Thai", CodePointSet.createNoDedup(0x000e01, 0x000e3a, 0x000e40, 0x000e5b));
    }

    private static void populateSCX_TIBT() {
        SET_ENCODINGS.put("scx=Tibt",
                        CodePointSet.createNoDedup(0x000f00, 0x000f47, 0x000f49, 0x000f6c, 0x000f71, 0x000f97, 0x000f99, 0x000fbc, 0x000fbe, 0x000fcc, 0x000fce, 0x000fd4, 0x000fd9, 0x000fda));
    }

    private static void populateSCX_TIRH() {
        SET_ENCODINGS.put("scx=Tirh", CodePointSet.createNoDedup(0x000951, 0x000952, 0x000964, 0x000965, 0x001cf2, 0x001cf2, 0x00a830, 0x00a839, 0x011480, 0x0114c7, 0x0114d0, 0x0114d9));
    }

    private static void populateSCX_TNSA() {
        SET_ENCODINGS.put("scx=Tnsa", CodePointSet.createNoDedup(0x016a70, 0x016abe, 0x016ac0, 0x016ac9));
    }

    private static void populateSCX_TOTO() {
        SET_ENCODINGS.put("scx=Toto", CodePointSet.createNoDedup(0x01e290, 0x01e2ae));
    }

    private static void populateSCX_UGAR() {
        SET_ENCODINGS.put("scx=Ugar", CodePointSet.createNoDedup(0x010380, 0x01039d, 0x01039f, 0x01039f));
    }

    private static void populateSCX_VAII() {
        SET_ENCODINGS.put("scx=Vaii", CodePointSet.createNoDedup(0x00a500, 0x00a62b));
    }

    private static void populateSCX_VITH() {
        SET_ENCODINGS.put("scx=Vith", CodePointSet.createNoDedup(0x010570, 0x01057a, 0x01057c, 0x01058a, 0x01058c, 0x010592, 0x010594, 0x010595, 0x010597, 0x0105a1, 0x0105a3, 0x0105b1, 0x0105b3,
                        0x0105b9, 0x0105bb, 0x0105bc));
    }

    private static void populateSCX_WARA() {
        SET_ENCODINGS.put("scx=Wara", CodePointSet.createNoDedup(0x0118a0, 0x0118f2, 0x0118ff, 0x0118ff));
    }

    private static void populateSCX_WCHO() {
        SET_ENCODINGS.put("scx=Wcho", CodePointSet.createNoDedup(0x01e2c0, 0x01e2f9, 0x01e2ff, 0x01e2ff));
    }

    private static void populateSCX_XPEO() {
        SET_ENCODINGS.put("scx=Xpeo", CodePointSet.createNoDedup(0x0103a0, 0x0103c3, 0x0103c8, 0x0103d5));
    }

    private static void populateSCX_XSUX() {
        SET_ENCODINGS.put("scx=Xsux", CodePointSet.createNoDedup(0x012000, 0x012399, 0x012400, 0x01246e, 0x012470, 0x012474, 0x012480, 0x012543));
    }

    private static void populateSCX_YEZI() {
        SET_ENCODINGS.put("scx=Yezi",
                        CodePointSet.createNoDedup(0x00060c, 0x00060c, 0x00061b, 0x00061b, 0x00061f, 0x00061f, 0x000660, 0x000669, 0x010e80, 0x010ea9, 0x010eab, 0x010ead, 0x010eb0, 0x010eb1));
    }

    private static void populateSCX_YIII() {
        SET_ENCODINGS.put("scx=Yiii",
                        CodePointSet.createNoDedup(0x003001, 0x003002, 0x003008, 0x003011, 0x003014, 0x00301b, 0x0030fb, 0x0030fb, 0x00a000, 0x00a48c, 0x00a490, 0x00a4c6, 0x00ff61, 0x00ff65));
    }

    private static void populateSCX_ZANB() {
        SET_ENCODINGS.put("scx=Zanb", CodePointSet.createNoDedup(0x011a00, 0x011a47));
    }

    private static void populateSCX_ZINH() {
        SET_ENCODINGS.put("scx=Zinh",
                        CodePointSet.createNoDedup(0x000300, 0x000341, 0x000343, 0x000344, 0x000346, 0x000362, 0x000953, 0x000954, 0x001ab0, 0x001ace, 0x001dc2, 0x001df7, 0x001df9, 0x001df9, 0x001dfb,
                                        0x001dff, 0x00200c, 0x00200d, 0x0020d0, 0x0020ef, 0x00fe00, 0x00fe0f, 0x00fe20, 0x00fe2d, 0x0101fd, 0x0101fd, 0x01cf00, 0x01cf2d, 0x01cf30, 0x01cf46, 0x01d167,
                                        0x01d169, 0x01d17b, 0x01d182, 0x01d185, 0x01d18b, 0x01d1aa, 0x01d1ad, 0x0e0100, 0x0e01ef));
    }

    private static void populateSCX_ZYYY() {
        SET_ENCODINGS.put("scx=Zyyy", CodePointSet.createNoDedup(0x000000, 0x000040, 0x00005b, 0x000060, 0x00007b, 0x0000a9, 0x0000ab, 0x0000b9, 0x0000bb, 0x0000bf, 0x0000d7, 0x0000d7, 0x0000f7,
                        0x0000f7, 0x0002b9, 0x0002df, 0x0002e5, 0x0002e9, 0x0002ec, 0x0002ff, 0x000374, 0x000374, 0x00037e, 0x00037e, 0x000385, 0x000385, 0x000387, 0x000387, 0x000605, 0x000605,
                        0x0006dd, 0x0006dd, 0x0008e2, 0x0008e2, 0x000e3f, 0x000e3f, 0x000fd5, 0x000fd8, 0x0016eb, 0x0016ed, 0x002000, 0x00200b, 0x00200e, 0x00202e, 0x002030, 0x002064, 0x002066,
                        0x002070, 0x002074, 0x00207e, 0x002080, 0x00208e, 0x0020a0, 0x0020c0, 0x002100, 0x002125, 0x002127, 0x002129, 0x00212c, 0x002131, 0x002133, 0x00214d, 0x00214f, 0x00215f,
                        0x002189, 0x00218b, 0x002190, 0x002426, 0x002440, 0x00244a, 0x002460, 0x0027ff, 0x002900, 0x002b73, 0x002b76, 0x002b95, 0x002b97, 0x002bff, 0x002e00, 0x002e42, 0x002e44,
                        0x002e5d, 0x002ff0, 0x002ffb, 0x003000, 0x003000, 0x003004, 0x003004, 0x003012, 0x003012, 0x003020, 0x003020, 0x003036, 0x003036, 0x003248, 0x00325f, 0x00327f, 0x00327f,
                        0x0032b1, 0x0032bf, 0x0032cc, 0x0032cf, 0x003371, 0x00337a, 0x003380, 0x0033df, 0x0033ff, 0x0033ff, 0x004dc0, 0x004dff, 0x00a708, 0x00a721, 0x00a788, 0x00a78a, 0x00ab5b,
                        0x00ab5b, 0x00ab6a, 0x00ab6b, 0x00fe10, 0x00fe19, 0x00fe30, 0x00fe44, 0x00fe47, 0x00fe52, 0x00fe54, 0x00fe66, 0x00fe68, 0x00fe6b, 0x00feff, 0x00feff, 0x00ff01, 0x00ff20,
                        0x00ff3b, 0x00ff40, 0x00ff5b, 0x00ff60, 0x00ffe0, 0x00ffe6, 0x00ffe8, 0x00ffee, 0x00fff9, 0x00fffd, 0x010190, 0x01019c, 0x0101d0, 0x0101fc, 0x01cf50, 0x01cfc3, 0x01d000,
                        0x01d0f5, 0x01d100, 0x01d126, 0x01d129, 0x01d166, 0x01d16a, 0x01d17a, 0x01d183, 0x01d184, 0x01d18c, 0x01d1a9, 0x01d1ae, 0x01d1ea, 0x01d2c0, 0x01d2d3, 0x01d2e0, 0x01d2f3,
                        0x01d300, 0x01d356, 0x01d372, 0x01d378, 0x01d400, 0x01d454, 0x01d456, 0x01d49c, 0x01d49e, 0x01d49f, 0x01d4a2, 0x01d4a2, 0x01d4a5, 0x01d4a6, 0x01d4a9, 0x01d4ac, 0x01d4ae,
                        0x01d4b9, 0x01d4bb, 0x01d4bb, 0x01d4bd, 0x01d4c3, 0x01d4c5, 0x01d505, 0x01d507, 0x01d50a, 0x01d50d, 0x01d514, 0x01d516, 0x01d51c, 0x01d51e, 0x01d539, 0x01d53b, 0x01d53e,
                        0x01d540, 0x01d544, 0x01d546, 0x01d546, 0x01d54a, 0x01d550, 0x01d552, 0x01d6a5, 0x01d6a8, 0x01d7cb, 0x01d7ce, 0x01d7ff, 0x01ec71, 0x01ecb4, 0x01ed01, 0x01ed3d, 0x01f000,
                        0x01f02b, 0x01f030, 0x01f093, 0x01f0a0, 0x01f0ae, 0x01f0b1, 0x01f0bf, 0x01f0c1, 0x01f0cf, 0x01f0d1, 0x01f0f5, 0x01f100, 0x01f1ad, 0x01f1e6, 0x01f1ff, 0x01f201, 0x01f202,
                        0x01f210, 0x01f23b, 0x01f240, 0x01f248, 0x01f260, 0x01f265, 0x01f300, 0x01f6d7, 0x01f6dc, 0x01f6ec, 0x01f6f0, 0x01f6fc, 0x01f700, 0x01f776, 0x01f77b, 0x01f7d9, 0x01f7e0,
                        0x01f7eb, 0x01f7f0, 0x01f7f0, 0x01f800, 0x01f80b, 0x01f810, 0x01f847, 0x01f850, 0x01f859, 0x01f860, 0x01f887, 0x01f890, 0x01f8ad, 0x01f8b0, 0x01f8b1, 0x01f900, 0x01fa53,
                        0x01fa60, 0x01fa6d, 0x01fa70, 0x01fa7c, 0x01fa80, 0x01fa88, 0x01fa90, 0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb, 0x01fae0, 0x01fae8, 0x01faf0, 0x01faf8, 0x01fb00,
                        0x01fb92, 0x01fb94, 0x01fbca, 0x01fbf0, 0x01fbf9, 0x0e0001, 0x0e0001, 0x0e0020, 0x0e007f));
    }

    private static void populateSCX_ZZZZ() {
        SET_ENCODINGS.put("scx=Zzzz", CodePointSet.createNoDedup(0x000378, 0x000379, 0x000380, 0x000383, 0x00038b, 0x00038b, 0x00038d, 0x00038d, 0x0003a2, 0x0003a2, 0x000530, 0x000530, 0x000557,
                        0x000558, 0x00058b, 0x00058c, 0x000590, 0x000590, 0x0005c8, 0x0005cf, 0x0005eb, 0x0005ee, 0x0005f5, 0x0005ff, 0x00070e, 0x00070e, 0x00074b, 0x00074c, 0x0007b2, 0x0007bf,
                        0x0007fb, 0x0007fc, 0x00082e, 0x00082f, 0x00083f, 0x00083f, 0x00085c, 0x00085d, 0x00085f, 0x00085f, 0x00086b, 0x00086f, 0x00088f, 0x00088f, 0x000892, 0x000897, 0x000984,
                        0x000984, 0x00098d, 0x00098e, 0x000991, 0x000992, 0x0009a9, 0x0009a9, 0x0009b1, 0x0009b1, 0x0009b3, 0x0009b5, 0x0009ba, 0x0009bb, 0x0009c5, 0x0009c6, 0x0009c9, 0x0009ca,
                        0x0009cf, 0x0009d6, 0x0009d8, 0x0009db, 0x0009de, 0x0009de, 0x0009e4, 0x0009e5, 0x0009ff, 0x000a00, 0x000a04, 0x000a04, 0x000a0b, 0x000a0e, 0x000a11, 0x000a12, 0x000a29,
                        0x000a29, 0x000a31, 0x000a31, 0x000a34, 0x000a34, 0x000a37, 0x000a37, 0x000a3a, 0x000a3b, 0x000a3d, 0x000a3d, 0x000a43, 0x000a46, 0x000a49, 0x000a4a, 0x000a4e, 0x000a50,
                        0x000a52, 0x000a58, 0x000a5d, 0x000a5d, 0x000a5f, 0x000a65, 0x000a77, 0x000a80, 0x000a84, 0x000a84, 0x000a8e, 0x000a8e, 0x000a92, 0x000a92, 0x000aa9, 0x000aa9, 0x000ab1,
                        0x000ab1, 0x000ab4, 0x000ab4, 0x000aba, 0x000abb, 0x000ac6, 0x000ac6, 0x000aca, 0x000aca, 0x000ace, 0x000acf, 0x000ad1, 0x000adf, 0x000ae4, 0x000ae5, 0x000af2, 0x000af8,
                        0x000b00, 0x000b00, 0x000b04, 0x000b04, 0x000b0d, 0x000b0e, 0x000b11, 0x000b12, 0x000b29, 0x000b29, 0x000b31, 0x000b31, 0x000b34, 0x000b34, 0x000b3a, 0x000b3b, 0x000b45,
                        0x000b46, 0x000b49, 0x000b4a, 0x000b4e, 0x000b54, 0x000b58, 0x000b5b, 0x000b5e, 0x000b5e, 0x000b64, 0x000b65, 0x000b78, 0x000b81, 0x000b84, 0x000b84, 0x000b8b, 0x000b8d,
                        0x000b91, 0x000b91, 0x000b96, 0x000b98, 0x000b9b, 0x000b9b, 0x000b9d, 0x000b9d, 0x000ba0, 0x000ba2, 0x000ba5, 0x000ba7, 0x000bab, 0x000bad, 0x000bba, 0x000bbd, 0x000bc3,
                        0x000bc5, 0x000bc9, 0x000bc9, 0x000bce, 0x000bcf, 0x000bd1, 0x000bd6, 0x000bd8, 0x000be5, 0x000bfb, 0x000bff, 0x000c0d, 0x000c0d, 0x000c11, 0x000c11, 0x000c29, 0x000c29,
                        0x000c3a, 0x000c3b, 0x000c45, 0x000c45, 0x000c49, 0x000c49, 0x000c4e, 0x000c54, 0x000c57, 0x000c57, 0x000c5b, 0x000c5c, 0x000c5e, 0x000c5f, 0x000c64, 0x000c65, 0x000c70,
                        0x000c76, 0x000c8d, 0x000c8d, 0x000c91, 0x000c91, 0x000ca9, 0x000ca9, 0x000cb4, 0x000cb4, 0x000cba, 0x000cbb, 0x000cc5, 0x000cc5, 0x000cc9, 0x000cc9, 0x000cce, 0x000cd4,
                        0x000cd7, 0x000cdc, 0x000cdf, 0x000cdf, 0x000ce4, 0x000ce5, 0x000cf0, 0x000cf0, 0x000cf4, 0x000cff, 0x000d0d, 0x000d0d, 0x000d11, 0x000d11, 0x000d45, 0x000d45, 0x000d49,
                        0x000d49, 0x000d50, 0x000d53, 0x000d64, 0x000d65, 0x000d80, 0x000d80, 0x000d84, 0x000d84, 0x000d97, 0x000d99, 0x000db2, 0x000db2, 0x000dbc, 0x000dbc, 0x000dbe, 0x000dbf,
                        0x000dc7, 0x000dc9, 0x000dcb, 0x000dce, 0x000dd5, 0x000dd5, 0x000dd7, 0x000dd7, 0x000de0, 0x000de5, 0x000df0, 0x000df1, 0x000df5, 0x000e00, 0x000e3b, 0x000e3e, 0x000e5c,
                        0x000e80, 0x000e83, 0x000e83, 0x000e85, 0x000e85, 0x000e8b, 0x000e8b, 0x000ea4, 0x000ea4, 0x000ea6, 0x000ea6, 0x000ebe, 0x000ebf, 0x000ec5, 0x000ec5, 0x000ec7, 0x000ec7,
                        0x000ecf, 0x000ecf, 0x000eda, 0x000edb, 0x000ee0, 0x000eff, 0x000f48, 0x000f48, 0x000f6d, 0x000f70, 0x000f98, 0x000f98, 0x000fbd, 0x000fbd, 0x000fcd, 0x000fcd, 0x000fdb,
                        0x000fff, 0x0010c6, 0x0010c6, 0x0010c8, 0x0010cc, 0x0010ce, 0x0010cf, 0x001249, 0x001249, 0x00124e, 0x00124f, 0x001257, 0x001257, 0x001259, 0x001259, 0x00125e, 0x00125f,
                        0x001289, 0x001289, 0x00128e, 0x00128f, 0x0012b1, 0x0012b1, 0x0012b6, 0x0012b7, 0x0012bf, 0x0012bf, 0x0012c1, 0x0012c1, 0x0012c6, 0x0012c7, 0x0012d7, 0x0012d7, 0x001311,
                        0x001311, 0x001316, 0x001317, 0x00135b, 0x00135c, 0x00137d, 0x00137f, 0x00139a, 0x00139f, 0x0013f6, 0x0013f7, 0x0013fe, 0x0013ff, 0x00169d, 0x00169f, 0x0016f9, 0x0016ff,
                        0x001716, 0x00171e, 0x001737, 0x00173f, 0x001754, 0x00175f, 0x00176d, 0x00176d, 0x001771, 0x001771, 0x001774, 0x00177f, 0x0017de, 0x0017df, 0x0017ea, 0x0017ef, 0x0017fa,
                        0x0017ff, 0x00181a, 0x00181f, 0x001879, 0x00187f, 0x0018ab, 0x0018af, 0x0018f6, 0x0018ff, 0x00191f, 0x00191f, 0x00192c, 0x00192f, 0x00193c, 0x00193f, 0x001941, 0x001943,
                        0x00196e, 0x00196f, 0x001975, 0x00197f, 0x0019ac, 0x0019af, 0x0019ca, 0x0019cf, 0x0019db, 0x0019dd, 0x001a1c, 0x001a1d, 0x001a5f, 0x001a5f, 0x001a7d, 0x001a7e, 0x001a8a,
                        0x001a8f, 0x001a9a, 0x001a9f, 0x001aae, 0x001aaf, 0x001acf, 0x001aff, 0x001b4d, 0x001b4f, 0x001b7f, 0x001b7f, 0x001bf4, 0x001bfb, 0x001c38, 0x001c3a, 0x001c4a, 0x001c4c,
                        0x001c89, 0x001c8f, 0x001cbb, 0x001cbc, 0x001cc8, 0x001ccf, 0x001cfb, 0x001cff, 0x001f16, 0x001f17, 0x001f1e, 0x001f1f, 0x001f46, 0x001f47, 0x001f4e, 0x001f4f, 0x001f58,
                        0x001f58, 0x001f5a, 0x001f5a, 0x001f5c, 0x001f5c, 0x001f5e, 0x001f5e, 0x001f7e, 0x001f7f, 0x001fb5, 0x001fb5, 0x001fc5, 0x001fc5, 0x001fd4, 0x001fd5, 0x001fdc, 0x001fdc,
                        0x001ff0, 0x001ff1, 0x001ff5, 0x001ff5, 0x001fff, 0x001fff, 0x002065, 0x002065, 0x002072, 0x002073, 0x00208f, 0x00208f, 0x00209d, 0x00209f, 0x0020c1, 0x0020cf, 0x0020f1,
                        0x0020ff, 0x00218c, 0x00218f, 0x002427, 0x00243f, 0x00244b, 0x00245f, 0x002b74, 0x002b75, 0x002b96, 0x002b96, 0x002cf4, 0x002cf8, 0x002d26, 0x002d26, 0x002d28, 0x002d2c,
                        0x002d2e, 0x002d2f, 0x002d68, 0x002d6e, 0x002d71, 0x002d7e, 0x002d97, 0x002d9f, 0x002da7, 0x002da7, 0x002daf, 0x002daf, 0x002db7, 0x002db7, 0x002dbf, 0x002dbf, 0x002dc7,
                        0x002dc7, 0x002dcf, 0x002dcf, 0x002dd7, 0x002dd7, 0x002ddf, 0x002ddf, 0x002e5e, 0x002e7f, 0x002e9a, 0x002e9a, 0x002ef4, 0x002eff, 0x002fd6, 0x002fef, 0x002ffc, 0x002fff,
                        0x003040, 0x003040, 0x003097, 0x003098, 0x003100, 0x003104, 0x003130, 0x003130, 0x00318f, 0x00318f, 0x0031e4, 0x0031ef, 0x00321f, 0x00321f, 0x00a48d, 0x00a48f, 0x00a4c7,
                        0x00a4cf, 0x00a62c, 0x00a63f, 0x00a6f8, 0x00a6ff, 0x00a7cb, 0x00a7cf, 0x00a7d2, 0x00a7d2, 0x00a7d4, 0x00a7d4, 0x00a7da, 0x00a7f1, 0x00a82d, 0x00a82f, 0x00a83a, 0x00a83f,
                        0x00a878, 0x00a87f, 0x00a8c6, 0x00a8cd, 0x00a8da, 0x00a8df, 0x00a954, 0x00a95e, 0x00a97d, 0x00a97f, 0x00a9ce, 0x00a9ce, 0x00a9da, 0x00a9dd, 0x00a9ff, 0x00a9ff, 0x00aa37,
                        0x00aa3f, 0x00aa4e, 0x00aa4f, 0x00aa5a, 0x00aa5b, 0x00aac3, 0x00aada, 0x00aaf7, 0x00ab00, 0x00ab07, 0x00ab08, 0x00ab0f, 0x00ab10, 0x00ab17, 0x00ab1f, 0x00ab27, 0x00ab27,
                        0x00ab2f, 0x00ab2f, 0x00ab6c, 0x00ab6f, 0x00abee, 0x00abef, 0x00abfa, 0x00abff, 0x00d7a4, 0x00d7af, 0x00d7c7, 0x00d7ca, 0x00d7fc, 0x00f8ff, 0x00fa6e, 0x00fa6f, 0x00fada,
                        0x00faff, 0x00fb07, 0x00fb12, 0x00fb18, 0x00fb1c, 0x00fb37, 0x00fb37, 0x00fb3d, 0x00fb3d, 0x00fb3f, 0x00fb3f, 0x00fb42, 0x00fb42, 0x00fb45, 0x00fb45, 0x00fbc3, 0x00fbd2,
                        0x00fd90, 0x00fd91, 0x00fdc8, 0x00fdce, 0x00fdd0, 0x00fdef, 0x00fe1a, 0x00fe1f, 0x00fe53, 0x00fe53, 0x00fe67, 0x00fe67, 0x00fe6c, 0x00fe6f, 0x00fe75, 0x00fe75, 0x00fefd,
                        0x00fefe, 0x00ff00, 0x00ff00, 0x00ffbf, 0x00ffc1, 0x00ffc8, 0x00ffc9, 0x00ffd0, 0x00ffd1, 0x00ffd8, 0x00ffd9, 0x00ffdd, 0x00ffdf, 0x00ffe7, 0x00ffe7, 0x00ffef, 0x00fff8,
                        0x00fffe, 0x00ffff, 0x01000c, 0x01000c, 0x010027, 0x010027, 0x01003b, 0x01003b, 0x01003e, 0x01003e, 0x01004e, 0x01004f, 0x01005e, 0x01007f, 0x0100fb, 0x0100ff, 0x010103,
                        0x010106, 0x010134, 0x010136, 0x01018f, 0x01018f, 0x01019d, 0x01019f, 0x0101a1, 0x0101cf, 0x0101fe, 0x01027f, 0x01029d, 0x01029f, 0x0102d1, 0x0102df, 0x0102fc, 0x0102ff,
                        0x010324, 0x01032c, 0x01034b, 0x01034f, 0x01037b, 0x01037f, 0x01039e, 0x01039e, 0x0103c4, 0x0103c7, 0x0103d6, 0x0103ff, 0x01049e, 0x01049f, 0x0104aa, 0x0104af, 0x0104d4,
                        0x0104d7, 0x0104fc, 0x0104ff, 0x010528, 0x01052f, 0x010564, 0x01056e, 0x01057b, 0x01057b, 0x01058b, 0x01058b, 0x010593, 0x010593, 0x010596, 0x010596, 0x0105a2, 0x0105a2,
                        0x0105b2, 0x0105b2, 0x0105ba, 0x0105ba, 0x0105bd, 0x0105ff, 0x010737, 0x01073f, 0x010756, 0x01075f, 0x010768, 0x01077f, 0x010786, 0x010786, 0x0107b1, 0x0107b1, 0x0107bb,
                        0x0107ff, 0x010806, 0x010807, 0x010809, 0x010809, 0x010836, 0x010836, 0x010839, 0x01083b, 0x01083d, 0x01083e, 0x010856, 0x010856, 0x01089f, 0x0108a6, 0x0108b0, 0x0108df,
                        0x0108f3, 0x0108f3, 0x0108f6, 0x0108fa, 0x01091c, 0x01091e, 0x01093a, 0x01093e, 0x010940, 0x01097f, 0x0109b8, 0x0109bb, 0x0109d0, 0x0109d1, 0x010a04, 0x010a04, 0x010a07,
                        0x010a0b, 0x010a14, 0x010a14, 0x010a18, 0x010a18, 0x010a36, 0x010a37, 0x010a3b, 0x010a3e, 0x010a49, 0x010a4f, 0x010a59, 0x010a5f, 0x010aa0, 0x010abf, 0x010ae7, 0x010aea,
                        0x010af7, 0x010aff, 0x010b36, 0x010b38, 0x010b56, 0x010b57, 0x010b73, 0x010b77, 0x010b92, 0x010b98, 0x010b9d, 0x010ba8, 0x010bb0, 0x010bff, 0x010c49, 0x010c7f, 0x010cb3,
                        0x010cbf, 0x010cf3, 0x010cf9, 0x010d28, 0x010d2f, 0x010d3a, 0x010e5f, 0x010e7f, 0x010e7f, 0x010eaa, 0x010eaa, 0x010eae, 0x010eaf, 0x010eb2, 0x010efc, 0x010f28, 0x010f2f,
                        0x010f5a, 0x010f6f, 0x010f8a, 0x010faf, 0x010fcc, 0x010fdf, 0x010ff7, 0x010fff, 0x01104e, 0x011051, 0x011076, 0x01107e, 0x0110c3, 0x0110cc, 0x0110ce, 0x0110cf, 0x0110e9,
                        0x0110ef, 0x0110fa, 0x0110ff, 0x011135, 0x011135, 0x011148, 0x01114f, 0x011177, 0x01117f, 0x0111e0, 0x0111e0, 0x0111f5, 0x0111ff, 0x011212, 0x011212, 0x011242, 0x01127f,
                        0x011287, 0x011287, 0x011289, 0x011289, 0x01128e, 0x01128e, 0x01129e, 0x01129e, 0x0112aa, 0x0112af, 0x0112eb, 0x0112ef, 0x0112fa, 0x0112ff, 0x011304, 0x011304, 0x01130d,
                        0x01130e, 0x011311, 0x011312, 0x011329, 0x011329, 0x011331, 0x011331, 0x011334, 0x011334, 0x01133a, 0x01133a, 0x011345, 0x011346, 0x011349, 0x01134a, 0x01134e, 0x01134f,
                        0x011351, 0x011356, 0x011358, 0x01135c, 0x011364, 0x011365, 0x01136d, 0x01136f, 0x011375, 0x0113ff, 0x01145c, 0x01145c, 0x011462, 0x01147f, 0x0114c8, 0x0114cf, 0x0114da,
                        0x01157f, 0x0115b6, 0x0115b7, 0x0115de, 0x0115ff, 0x011645, 0x01164f, 0x01165a, 0x01165f, 0x01166d, 0x01167f, 0x0116ba, 0x0116bf, 0x0116ca, 0x0116ff, 0x01171b, 0x01171c,
                        0x01172c, 0x01172f, 0x011747, 0x0117ff, 0x01183c, 0x01189f, 0x0118f3, 0x0118fe, 0x011907, 0x011908, 0x01190a, 0x01190b, 0x011914, 0x011914, 0x011917, 0x011917, 0x011936,
                        0x011936, 0x011939, 0x01193a, 0x011947, 0x01194f, 0x01195a, 0x01199f, 0x0119a8, 0x0119a9, 0x0119d8, 0x0119d9, 0x0119e5, 0x0119ff, 0x011a48, 0x011a4f, 0x011aa3, 0x011aaf,
                        0x011af9, 0x011aff, 0x011b0a, 0x011bff, 0x011c09, 0x011c09, 0x011c37, 0x011c37, 0x011c46, 0x011c4f, 0x011c6d, 0x011c6f, 0x011c90, 0x011c91, 0x011ca8, 0x011ca8, 0x011cb7,
                        0x011cff, 0x011d07, 0x011d07, 0x011d0a, 0x011d0a, 0x011d37, 0x011d39, 0x011d3b, 0x011d3b, 0x011d3e, 0x011d3e, 0x011d48, 0x011d4f, 0x011d5a, 0x011d5f, 0x011d66, 0x011d66,
                        0x011d69, 0x011d69, 0x011d8f, 0x011d8f, 0x011d92, 0x011d92, 0x011d99, 0x011d9f, 0x011daa, 0x011edf, 0x011ef9, 0x011eff, 0x011f11, 0x011f11, 0x011f3b, 0x011f3d, 0x011f5a,
                        0x011faf, 0x011fb1, 0x011fbf, 0x011ff2, 0x011ffe, 0x01239a, 0x0123ff, 0x01246f, 0x01246f, 0x012475, 0x01247f, 0x012544, 0x012f8f, 0x012ff3, 0x012fff, 0x013456, 0x0143ff,
                        0x014647, 0x0167ff, 0x016a39, 0x016a3f, 0x016a5f, 0x016a5f, 0x016a6a, 0x016a6d, 0x016abf, 0x016abf, 0x016aca, 0x016acf, 0x016aee, 0x016aef, 0x016af6, 0x016aff, 0x016b46,
                        0x016b4f, 0x016b5a, 0x016b5a, 0x016b62, 0x016b62, 0x016b78, 0x016b7c, 0x016b90, 0x016e3f, 0x016e9b, 0x016eff, 0x016f4b, 0x016f4e, 0x016f88, 0x016f8e, 0x016fa0, 0x016fdf,
                        0x016fe5, 0x016fef, 0x016ff2, 0x016fff, 0x0187f8, 0x0187ff, 0x018cd6, 0x018cff, 0x018d09, 0x01afef, 0x01aff4, 0x01aff4, 0x01affc, 0x01affc, 0x01afff, 0x01afff, 0x01b123,
                        0x01b131, 0x01b133, 0x01b14f, 0x01b153, 0x01b154, 0x01b156, 0x01b163, 0x01b168, 0x01b16f, 0x01b2fc, 0x01bbff, 0x01bc6b, 0x01bc6f, 0x01bc7d, 0x01bc7f, 0x01bc89, 0x01bc8f,
                        0x01bc9a, 0x01bc9b, 0x01bca4, 0x01ceff, 0x01cf2e, 0x01cf2f, 0x01cf47, 0x01cf4f, 0x01cfc4, 0x01cfff, 0x01d0f6, 0x01d0ff, 0x01d127, 0x01d128, 0x01d1eb, 0x01d1ff, 0x01d246,
                        0x01d2bf, 0x01d2d4, 0x01d2df, 0x01d2f4, 0x01d2ff, 0x01d357, 0x01d35f, 0x01d379, 0x01d3ff, 0x01d455, 0x01d455, 0x01d49d, 0x01d49d, 0x01d4a0, 0x01d4a1, 0x01d4a3, 0x01d4a4,
                        0x01d4a7, 0x01d4a8, 0x01d4ad, 0x01d4ad, 0x01d4ba, 0x01d4ba, 0x01d4bc, 0x01d4bc, 0x01d4c4, 0x01d4c4, 0x01d506, 0x01d506, 0x01d50b, 0x01d50c, 0x01d515, 0x01d515, 0x01d51d,
                        0x01d51d, 0x01d53a, 0x01d53a, 0x01d53f, 0x01d53f, 0x01d545, 0x01d545, 0x01d547, 0x01d549, 0x01d551, 0x01d551, 0x01d6a6, 0x01d6a7, 0x01d7cc, 0x01d7cd, 0x01da8c, 0x01da9a,
                        0x01daa0, 0x01daa0, 0x01dab0, 0x01deff, 0x01df1f, 0x01df24, 0x01df2b, 0x01dfff, 0x01e007, 0x01e007, 0x01e019, 0x01e01a, 0x01e022, 0x01e022, 0x01e025, 0x01e025, 0x01e02b,
                        0x01e02f, 0x01e06e, 0x01e08e, 0x01e090, 0x01e0ff, 0x01e12d, 0x01e12f, 0x01e13e, 0x01e13f, 0x01e14a, 0x01e14d, 0x01e150, 0x01e28f, 0x01e2af, 0x01e2bf, 0x01e2fa, 0x01e2fe,
                        0x01e300, 0x01e4cf, 0x01e4fa, 0x01e7df, 0x01e7e7, 0x01e7e7, 0x01e7ec, 0x01e7ec, 0x01e7ef, 0x01e7ef, 0x01e7ff, 0x01e7ff, 0x01e8c5, 0x01e8c6, 0x01e8d7, 0x01e8ff, 0x01e94c,
                        0x01e94f, 0x01e95a, 0x01e95d, 0x01e960, 0x01ec70, 0x01ecb5, 0x01ed00, 0x01ed3e, 0x01edff, 0x01ee04, 0x01ee04, 0x01ee20, 0x01ee20, 0x01ee23, 0x01ee23, 0x01ee25, 0x01ee26,
                        0x01ee28, 0x01ee28, 0x01ee33, 0x01ee33, 0x01ee38, 0x01ee38, 0x01ee3a, 0x01ee3a, 0x01ee3c, 0x01ee41, 0x01ee43, 0x01ee46, 0x01ee48, 0x01ee48, 0x01ee4a, 0x01ee4a, 0x01ee4c,
                        0x01ee4c, 0x01ee50, 0x01ee50, 0x01ee53, 0x01ee53, 0x01ee55, 0x01ee56, 0x01ee58, 0x01ee58, 0x01ee5a, 0x01ee5a, 0x01ee5c, 0x01ee5c, 0x01ee5e, 0x01ee5e, 0x01ee60, 0x01ee60,
                        0x01ee63, 0x01ee63, 0x01ee65, 0x01ee66, 0x01ee6b, 0x01ee6b, 0x01ee73, 0x01ee73, 0x01ee78, 0x01ee78, 0x01ee7d, 0x01ee7d, 0x01ee7f, 0x01ee7f, 0x01ee8a, 0x01ee8a, 0x01ee9c,
                        0x01eea0, 0x01eea4, 0x01eea4, 0x01eeaa, 0x01eeaa, 0x01eebc, 0x01eeef, 0x01eef2, 0x01efff, 0x01f02c, 0x01f02f, 0x01f094, 0x01f09f, 0x01f0af, 0x01f0b0, 0x01f0c0, 0x01f0c0,
                        0x01f0d0, 0x01f0d0, 0x01f0f6, 0x01f0ff, 0x01f1ae, 0x01f1e5, 0x01f203, 0x01f20f, 0x01f23c, 0x01f23f, 0x01f249, 0x01f24f, 0x01f252, 0x01f25f, 0x01f266, 0x01f2ff, 0x01f6d8,
                        0x01f6db, 0x01f6ed, 0x01f6ef, 0x01f6fd, 0x01f6ff, 0x01f777, 0x01f77a, 0x01f7da, 0x01f7df, 0x01f7ec, 0x01f7ef, 0x01f7f1, 0x01f7ff, 0x01f80c, 0x01f80f, 0x01f848, 0x01f84f,
                        0x01f85a, 0x01f85f, 0x01f888, 0x01f88f, 0x01f8ae, 0x01f8af, 0x01f8b2, 0x01f8ff, 0x01fa54, 0x01fa5f, 0x01fa6e, 0x01fa6f, 0x01fa7d, 0x01fa7f, 0x01fa89, 0x01fa8f, 0x01fabe,
                        0x01fabe, 0x01fac6, 0x01facd, 0x01fadc, 0x01fadf, 0x01fae9, 0x01faef, 0x01faf9, 0x01faff, 0x01fb93, 0x01fb93, 0x01fbcb, 0x01fbef, 0x01fbfa, 0x01ffff, 0x02a6e0, 0x02a6ff,
                        0x02b73a, 0x02b73f, 0x02b81e, 0x02b81f, 0x02cea2, 0x02ceaf, 0x02ebe1, 0x02f7ff, 0x02fa1e, 0x02ffff, 0x03134b, 0x03134f, 0x0323b0, 0x0e0000, 0x0e0002, 0x0e001f, 0x0e0080,
                        0x0e00ff, 0x0e01f0, 0x10ffff));
    }

    private static void populateBASIC_EMOJI() {
        CLASS_SET_ENCODINGS.put("Basic_Emoji", ClassSetContents.createUnicodePropertyOfStrings(
                        CodePointSet.createNoDedup(0x00231a, 0x00231b, 0x0023e9, 0x0023ec, 0x0023f0, 0x0023f0, 0x0023f3, 0x0023f3, 0x0025fd, 0x0025fe, 0x002614, 0x002615, 0x002648, 0x002653, 0x00267f,
                                        0x00267f, 0x002693, 0x002693, 0x0026a1, 0x0026a1, 0x0026aa, 0x0026ab, 0x0026bd, 0x0026be, 0x0026c4, 0x0026c5, 0x0026ce, 0x0026ce, 0x0026d4, 0x0026d4, 0x0026ea,
                                        0x0026ea, 0x0026f2, 0x0026f3, 0x0026f5, 0x0026f5, 0x0026fa, 0x0026fa, 0x0026fd, 0x0026fd, 0x002705, 0x002705, 0x00270a, 0x00270b, 0x002728, 0x002728, 0x00274c,
                                        0x00274c, 0x00274e, 0x00274e, 0x002753, 0x002755, 0x002757, 0x002757, 0x002795, 0x002797, 0x0027b0, 0x0027b0, 0x0027bf, 0x0027bf, 0x002b1b, 0x002b1c, 0x002b50,
                                        0x002b50, 0x002b55, 0x002b55, 0x01f004, 0x01f004, 0x01f0cf, 0x01f0cf, 0x01f18e, 0x01f18e, 0x01f191, 0x01f19a, 0x01f201, 0x01f201, 0x01f21a, 0x01f21a, 0x01f22f,
                                        0x01f22f, 0x01f232, 0x01f236, 0x01f238, 0x01f23a, 0x01f250, 0x01f251, 0x01f300, 0x01f320, 0x01f32d, 0x01f335, 0x01f337, 0x01f37c, 0x01f37e, 0x01f393, 0x01f3a0,
                                        0x01f3ca, 0x01f3cf, 0x01f3d3, 0x01f3e0, 0x01f3f0, 0x01f3f4, 0x01f3f4, 0x01f3f8, 0x01f43e, 0x01f440, 0x01f440, 0x01f442, 0x01f4fc, 0x01f4ff, 0x01f53d, 0x01f54b,
                                        0x01f54e, 0x01f550, 0x01f567, 0x01f57a, 0x01f57a, 0x01f595, 0x01f596, 0x01f5a4, 0x01f5a4, 0x01f5fb, 0x01f64f, 0x01f680, 0x01f6c5, 0x01f6cc, 0x01f6cc, 0x01f6d0,
                                        0x01f6d2, 0x01f6d5, 0x01f6d7, 0x01f6dc, 0x01f6df, 0x01f6eb, 0x01f6ec, 0x01f6f4, 0x01f6fc, 0x01f7e0, 0x01f7eb, 0x01f7f0, 0x01f7f0, 0x01f90c, 0x01f93a, 0x01f93c,
                                        0x01f945, 0x01f947, 0x01f9ff, 0x01fa70, 0x01fa7c, 0x01fa80, 0x01fa88, 0x01fa90, 0x01fabd, 0x01fabf, 0x01fac5, 0x01face, 0x01fadb, 0x01fae0, 0x01fae8, 0x01faf0,
                                        0x01faf8),
                        stringSet(
                                        "\u00a9\ufe0f",
                                        "\u00ae\ufe0f",
                                        "\u203c\ufe0f",
                                        "\u2049\ufe0f",
                                        "\u2122\ufe0f",
                                        "\u2139\ufe0f",
                                        "\u2194\ufe0f",
                                        "\u2195\ufe0f",
                                        "\u2196\ufe0f",
                                        "\u2197\ufe0f",
                                        "\u2198\ufe0f",
                                        "\u2199\ufe0f",
                                        "\u21a9\ufe0f",
                                        "\u21aa\ufe0f",
                                        "\u2328\ufe0f",
                                        "\u23cf\ufe0f",
                                        "\u23ed\ufe0f",
                                        "\u23ee\ufe0f",
                                        "\u23ef\ufe0f",
                                        "\u23f1\ufe0f",
                                        "\u23f2\ufe0f",
                                        "\u23f8\ufe0f",
                                        "\u23f9\ufe0f",
                                        "\u23fa\ufe0f",
                                        "\u24c2\ufe0f",
                                        "\u25aa\ufe0f",
                                        "\u25ab\ufe0f",
                                        "\u25b6\ufe0f",
                                        "\u25c0\ufe0f",
                                        "\u25fb\ufe0f",
                                        "\u25fc\ufe0f",
                                        "\u2600\ufe0f",
                                        "\u2601\ufe0f",
                                        "\u2602\ufe0f",
                                        "\u2603\ufe0f",
                                        "\u2604\ufe0f",
                                        "\u260e\ufe0f",
                                        "\u2611\ufe0f",
                                        "\u2618\ufe0f",
                                        "\u261d\ufe0f",
                                        "\u2620\ufe0f",
                                        "\u2622\ufe0f",
                                        "\u2623\ufe0f",
                                        "\u2626\ufe0f",
                                        "\u262a\ufe0f",
                                        "\u262e\ufe0f",
                                        "\u262f\ufe0f",
                                        "\u2638\ufe0f",
                                        "\u2639\ufe0f",
                                        "\u263a\ufe0f",
                                        "\u2640\ufe0f",
                                        "\u2642\ufe0f",
                                        "\u265f\ufe0f",
                                        "\u2660\ufe0f",
                                        "\u2663\ufe0f",
                                        "\u2665\ufe0f",
                                        "\u2666\ufe0f",
                                        "\u2668\ufe0f",
                                        "\u267b\ufe0f",
                                        "\u267e\ufe0f",
                                        "\u2692\ufe0f",
                                        "\u2694\ufe0f",
                                        "\u2695\ufe0f",
                                        "\u2696\ufe0f",
                                        "\u2697\ufe0f",
                                        "\u2699\ufe0f",
                                        "\u269b\ufe0f",
                                        "\u269c\ufe0f",
                                        "\u26a0\ufe0f",
                                        "\u26a7\ufe0f",
                                        "\u26b0\ufe0f",
                                        "\u26b1\ufe0f",
                                        "\u26c8\ufe0f",
                                        "\u26cf\ufe0f",
                                        "\u26d1\ufe0f",
                                        "\u26d3\ufe0f",
                                        "\u26e9\ufe0f",
                                        "\u26f0\ufe0f",
                                        "\u26f1\ufe0f",
                                        "\u26f4\ufe0f",
                                        "\u26f7\ufe0f",
                                        "\u26f8\ufe0f",
                                        "\u26f9\ufe0f",
                                        "\u2702\ufe0f",
                                        "\u2708\ufe0f",
                                        "\u2709\ufe0f",
                                        "\u270c\ufe0f",
                                        "\u270d\ufe0f",
                                        "\u270f\ufe0f",
                                        "\u2712\ufe0f",
                                        "\u2714\ufe0f",
                                        "\u2716\ufe0f",
                                        "\u271d\ufe0f",
                                        "\u2721\ufe0f",
                                        "\u2733\ufe0f",
                                        "\u2734\ufe0f",
                                        "\u2744\ufe0f",
                                        "\u2747\ufe0f",
                                        "\u2763\ufe0f",
                                        "\u2764\ufe0f",
                                        "\u27a1\ufe0f",
                                        "\u2934\ufe0f",
                                        "\u2935\ufe0f",
                                        "\u2b05\ufe0f",
                                        "\u2b06\ufe0f",
                                        "\u2b07\ufe0f",
                                        "\u3030\ufe0f",
                                        "\u303d\ufe0f",
                                        "\u3297\ufe0f",
                                        "\u3299\ufe0f",
                                        "\ud83c\udd70\ufe0f",
                                        "\ud83c\udd71\ufe0f",
                                        "\ud83c\udd7e\ufe0f",
                                        "\ud83c\udd7f\ufe0f",
                                        "\ud83c\ude02\ufe0f",
                                        "\ud83c\ude37\ufe0f",
                                        "\ud83c\udf21\ufe0f",
                                        "\ud83c\udf24\ufe0f",
                                        "\ud83c\udf25\ufe0f",
                                        "\ud83c\udf26\ufe0f",
                                        "\ud83c\udf27\ufe0f",
                                        "\ud83c\udf28\ufe0f",
                                        "\ud83c\udf29\ufe0f",
                                        "\ud83c\udf2a\ufe0f",
                                        "\ud83c\udf2b\ufe0f",
                                        "\ud83c\udf2c\ufe0f",
                                        "\ud83c\udf36\ufe0f",
                                        "\ud83c\udf7d\ufe0f",
                                        "\ud83c\udf96\ufe0f",
                                        "\ud83c\udf97\ufe0f",
                                        "\ud83c\udf99\ufe0f",
                                        "\ud83c\udf9a\ufe0f",
                                        "\ud83c\udf9b\ufe0f",
                                        "\ud83c\udf9e\ufe0f",
                                        "\ud83c\udf9f\ufe0f",
                                        "\ud83c\udfcb\ufe0f",
                                        "\ud83c\udfcc\ufe0f",
                                        "\ud83c\udfcd\ufe0f",
                                        "\ud83c\udfce\ufe0f",
                                        "\ud83c\udfd4\ufe0f",
                                        "\ud83c\udfd5\ufe0f",
                                        "\ud83c\udfd6\ufe0f",
                                        "\ud83c\udfd7\ufe0f",
                                        "\ud83c\udfd8\ufe0f",
                                        "\ud83c\udfd9\ufe0f",
                                        "\ud83c\udfda\ufe0f",
                                        "\ud83c\udfdb\ufe0f",
                                        "\ud83c\udfdc\ufe0f",
                                        "\ud83c\udfdd\ufe0f",
                                        "\ud83c\udfde\ufe0f",
                                        "\ud83c\udfdf\ufe0f",
                                        "\ud83c\udff3\ufe0f",
                                        "\ud83c\udff5\ufe0f",
                                        "\ud83c\udff7\ufe0f",
                                        "\ud83d\udc3f\ufe0f",
                                        "\ud83d\udc41\ufe0f",
                                        "\ud83d\udcfd\ufe0f",
                                        "\ud83d\udd49\ufe0f",
                                        "\ud83d\udd4a\ufe0f",
                                        "\ud83d\udd6f\ufe0f",
                                        "\ud83d\udd70\ufe0f",
                                        "\ud83d\udd73\ufe0f",
                                        "\ud83d\udd74\ufe0f",
                                        "\ud83d\udd75\ufe0f",
                                        "\ud83d\udd76\ufe0f",
                                        "\ud83d\udd77\ufe0f",
                                        "\ud83d\udd78\ufe0f",
                                        "\ud83d\udd79\ufe0f",
                                        "\ud83d\udd87\ufe0f",
                                        "\ud83d\udd8a\ufe0f",
                                        "\ud83d\udd8b\ufe0f",
                                        "\ud83d\udd8c\ufe0f",
                                        "\ud83d\udd8d\ufe0f",
                                        "\ud83d\udd90\ufe0f",
                                        "\ud83d\udda5\ufe0f",
                                        "\ud83d\udda8\ufe0f",
                                        "\ud83d\uddb1\ufe0f",
                                        "\ud83d\uddb2\ufe0f",
                                        "\ud83d\uddbc\ufe0f",
                                        "\ud83d\uddc2\ufe0f",
                                        "\ud83d\uddc3\ufe0f",
                                        "\ud83d\uddc4\ufe0f",
                                        "\ud83d\uddd1\ufe0f",
                                        "\ud83d\uddd2\ufe0f",
                                        "\ud83d\uddd3\ufe0f",
                                        "\ud83d\udddc\ufe0f",
                                        "\ud83d\udddd\ufe0f",
                                        "\ud83d\uddde\ufe0f",
                                        "\ud83d\udde1\ufe0f",
                                        "\ud83d\udde3\ufe0f",
                                        "\ud83d\udde8\ufe0f",
                                        "\ud83d\uddef\ufe0f",
                                        "\ud83d\uddf3\ufe0f",
                                        "\ud83d\uddfa\ufe0f",
                                        "\ud83d\udecb\ufe0f",
                                        "\ud83d\udecd\ufe0f",
                                        "\ud83d\udece\ufe0f",
                                        "\ud83d\udecf\ufe0f",
                                        "\ud83d\udee0\ufe0f",
                                        "\ud83d\udee1\ufe0f",
                                        "\ud83d\udee2\ufe0f",
                                        "\ud83d\udee3\ufe0f",
                                        "\ud83d\udee4\ufe0f",
                                        "\ud83d\udee5\ufe0f",
                                        "\ud83d\udee9\ufe0f",
                                        "\ud83d\udef0\ufe0f",
                                        "\ud83d\udef3\ufe0f")));
    }

    private static void populateEMOJI_KEYCAP_SEQUENCE() {
        CLASS_SET_ENCODINGS.put("Emoji_Keycap_Sequence", ClassSetContents.createUnicodePropertyOfStrings(
                        CodePointSet.getEmpty(), stringSet(
                                        "#\ufe0f\u20e3",
                                        "*\ufe0f\u20e3",
                                        "0\ufe0f\u20e3",
                                        "1\ufe0f\u20e3",
                                        "2\ufe0f\u20e3",
                                        "3\ufe0f\u20e3",
                                        "4\ufe0f\u20e3",
                                        "5\ufe0f\u20e3",
                                        "6\ufe0f\u20e3",
                                        "7\ufe0f\u20e3",
                                        "8\ufe0f\u20e3",
                                        "9\ufe0f\u20e3")));
    }

    private static void populateRGI_EMOJI_FLAG_SEQUENCE() {
        CLASS_SET_ENCODINGS.put("RGI_Emoji_Flag_Sequence", ClassSetContents.createUnicodePropertyOfStrings(
                        CodePointSet.getEmpty(), stringSet(
                                        "\ud83c\udde6\ud83c\udde8",
                                        "\ud83c\udde6\ud83c\udde9",
                                        "\ud83c\udde6\ud83c\uddea",
                                        "\ud83c\udde6\ud83c\uddeb",
                                        "\ud83c\udde6\ud83c\uddec",
                                        "\ud83c\udde6\ud83c\uddee",
                                        "\ud83c\udde6\ud83c\uddf1",
                                        "\ud83c\udde6\ud83c\uddf2",
                                        "\ud83c\udde6\ud83c\uddf4",
                                        "\ud83c\udde6\ud83c\uddf6",
                                        "\ud83c\udde6\ud83c\uddf7",
                                        "\ud83c\udde6\ud83c\uddf8",
                                        "\ud83c\udde6\ud83c\uddf9",
                                        "\ud83c\udde6\ud83c\uddfa",
                                        "\ud83c\udde6\ud83c\uddfc",
                                        "\ud83c\udde6\ud83c\uddfd",
                                        "\ud83c\udde6\ud83c\uddff",
                                        "\ud83c\udde7\ud83c\udde6",
                                        "\ud83c\udde7\ud83c\udde7",
                                        "\ud83c\udde7\ud83c\udde9",
                                        "\ud83c\udde7\ud83c\uddea",
                                        "\ud83c\udde7\ud83c\uddeb",
                                        "\ud83c\udde7\ud83c\uddec",
                                        "\ud83c\udde7\ud83c\udded",
                                        "\ud83c\udde7\ud83c\uddee",
                                        "\ud83c\udde7\ud83c\uddef",
                                        "\ud83c\udde7\ud83c\uddf1",
                                        "\ud83c\udde7\ud83c\uddf2",
                                        "\ud83c\udde7\ud83c\uddf3",
                                        "\ud83c\udde7\ud83c\uddf4",
                                        "\ud83c\udde7\ud83c\uddf6",
                                        "\ud83c\udde7\ud83c\uddf7",
                                        "\ud83c\udde7\ud83c\uddf8",
                                        "\ud83c\udde7\ud83c\uddf9",
                                        "\ud83c\udde7\ud83c\uddfb",
                                        "\ud83c\udde7\ud83c\uddfc",
                                        "\ud83c\udde7\ud83c\uddfe",
                                        "\ud83c\udde7\ud83c\uddff",
                                        "\ud83c\udde8\ud83c\udde6",
                                        "\ud83c\udde8\ud83c\udde8",
                                        "\ud83c\udde8\ud83c\udde9",
                                        "\ud83c\udde8\ud83c\uddeb",
                                        "\ud83c\udde8\ud83c\uddec",
                                        "\ud83c\udde8\ud83c\udded",
                                        "\ud83c\udde8\ud83c\uddee",
                                        "\ud83c\udde8\ud83c\uddf0",
                                        "\ud83c\udde8\ud83c\uddf1",
                                        "\ud83c\udde8\ud83c\uddf2",
                                        "\ud83c\udde8\ud83c\uddf3",
                                        "\ud83c\udde8\ud83c\uddf4",
                                        "\ud83c\udde8\ud83c\uddf5",
                                        "\ud83c\udde8\ud83c\uddf7",
                                        "\ud83c\udde8\ud83c\uddfa",
                                        "\ud83c\udde8\ud83c\uddfb",
                                        "\ud83c\udde8\ud83c\uddfc",
                                        "\ud83c\udde8\ud83c\uddfd",
                                        "\ud83c\udde8\ud83c\uddfe",
                                        "\ud83c\udde8\ud83c\uddff",
                                        "\ud83c\udde9\ud83c\uddea",
                                        "\ud83c\udde9\ud83c\uddec",
                                        "\ud83c\udde9\ud83c\uddef",
                                        "\ud83c\udde9\ud83c\uddf0",
                                        "\ud83c\udde9\ud83c\uddf2",
                                        "\ud83c\udde9\ud83c\uddf4",
                                        "\ud83c\udde9\ud83c\uddff",
                                        "\ud83c\uddea\ud83c\udde6",
                                        "\ud83c\uddea\ud83c\udde8",
                                        "\ud83c\uddea\ud83c\uddea",
                                        "\ud83c\uddea\ud83c\uddec",
                                        "\ud83c\uddea\ud83c\udded",
                                        "\ud83c\uddea\ud83c\uddf7",
                                        "\ud83c\uddea\ud83c\uddf8",
                                        "\ud83c\uddea\ud83c\uddf9",
                                        "\ud83c\uddea\ud83c\uddfa",
                                        "\ud83c\uddeb\ud83c\uddee",
                                        "\ud83c\uddeb\ud83c\uddef",
                                        "\ud83c\uddeb\ud83c\uddf0",
                                        "\ud83c\uddeb\ud83c\uddf2",
                                        "\ud83c\uddeb\ud83c\uddf4",
                                        "\ud83c\uddeb\ud83c\uddf7",
                                        "\ud83c\uddec\ud83c\udde6",
                                        "\ud83c\uddec\ud83c\udde7",
                                        "\ud83c\uddec\ud83c\udde9",
                                        "\ud83c\uddec\ud83c\uddea",
                                        "\ud83c\uddec\ud83c\uddeb",
                                        "\ud83c\uddec\ud83c\uddec",
                                        "\ud83c\uddec\ud83c\udded",
                                        "\ud83c\uddec\ud83c\uddee",
                                        "\ud83c\uddec\ud83c\uddf1",
                                        "\ud83c\uddec\ud83c\uddf2",
                                        "\ud83c\uddec\ud83c\uddf3",
                                        "\ud83c\uddec\ud83c\uddf5",
                                        "\ud83c\uddec\ud83c\uddf6",
                                        "\ud83c\uddec\ud83c\uddf7",
                                        "\ud83c\uddec\ud83c\uddf8",
                                        "\ud83c\uddec\ud83c\uddf9",
                                        "\ud83c\uddec\ud83c\uddfa",
                                        "\ud83c\uddec\ud83c\uddfc",
                                        "\ud83c\uddec\ud83c\uddfe",
                                        "\ud83c\udded\ud83c\uddf0",
                                        "\ud83c\udded\ud83c\uddf2",
                                        "\ud83c\udded\ud83c\uddf3",
                                        "\ud83c\udded\ud83c\uddf7",
                                        "\ud83c\udded\ud83c\uddf9",
                                        "\ud83c\udded\ud83c\uddfa",
                                        "\ud83c\uddee\ud83c\udde8",
                                        "\ud83c\uddee\ud83c\udde9",
                                        "\ud83c\uddee\ud83c\uddea",
                                        "\ud83c\uddee\ud83c\uddf1",
                                        "\ud83c\uddee\ud83c\uddf2",
                                        "\ud83c\uddee\ud83c\uddf3",
                                        "\ud83c\uddee\ud83c\uddf4",
                                        "\ud83c\uddee\ud83c\uddf6",
                                        "\ud83c\uddee\ud83c\uddf7",
                                        "\ud83c\uddee\ud83c\uddf8",
                                        "\ud83c\uddee\ud83c\uddf9",
                                        "\ud83c\uddef\ud83c\uddea",
                                        "\ud83c\uddef\ud83c\uddf2",
                                        "\ud83c\uddef\ud83c\uddf4",
                                        "\ud83c\uddef\ud83c\uddf5",
                                        "\ud83c\uddf0\ud83c\uddea",
                                        "\ud83c\uddf0\ud83c\uddec",
                                        "\ud83c\uddf0\ud83c\udded",
                                        "\ud83c\uddf0\ud83c\uddee",
                                        "\ud83c\uddf0\ud83c\uddf2",
                                        "\ud83c\uddf0\ud83c\uddf3",
                                        "\ud83c\uddf0\ud83c\uddf5",
                                        "\ud83c\uddf0\ud83c\uddf7",
                                        "\ud83c\uddf0\ud83c\uddfc",
                                        "\ud83c\uddf0\ud83c\uddfe",
                                        "\ud83c\uddf0\ud83c\uddff",
                                        "\ud83c\uddf1\ud83c\udde6",
                                        "\ud83c\uddf1\ud83c\udde7",
                                        "\ud83c\uddf1\ud83c\udde8",
                                        "\ud83c\uddf1\ud83c\uddee",
                                        "\ud83c\uddf1\ud83c\uddf0",
                                        "\ud83c\uddf1\ud83c\uddf7",
                                        "\ud83c\uddf1\ud83c\uddf8",
                                        "\ud83c\uddf1\ud83c\uddf9",
                                        "\ud83c\uddf1\ud83c\uddfa",
                                        "\ud83c\uddf1\ud83c\uddfb",
                                        "\ud83c\uddf1\ud83c\uddfe",
                                        "\ud83c\uddf2\ud83c\udde6",
                                        "\ud83c\uddf2\ud83c\udde8",
                                        "\ud83c\uddf2\ud83c\udde9",
                                        "\ud83c\uddf2\ud83c\uddea",
                                        "\ud83c\uddf2\ud83c\uddeb",
                                        "\ud83c\uddf2\ud83c\uddec",
                                        "\ud83c\uddf2\ud83c\udded",
                                        "\ud83c\uddf2\ud83c\uddf0",
                                        "\ud83c\uddf2\ud83c\uddf1",
                                        "\ud83c\uddf2\ud83c\uddf2",
                                        "\ud83c\uddf2\ud83c\uddf3",
                                        "\ud83c\uddf2\ud83c\uddf4",
                                        "\ud83c\uddf2\ud83c\uddf5",
                                        "\ud83c\uddf2\ud83c\uddf6",
                                        "\ud83c\uddf2\ud83c\uddf7",
                                        "\ud83c\uddf2\ud83c\uddf8",
                                        "\ud83c\uddf2\ud83c\uddf9",
                                        "\ud83c\uddf2\ud83c\uddfa",
                                        "\ud83c\uddf2\ud83c\uddfb",
                                        "\ud83c\uddf2\ud83c\uddfc",
                                        "\ud83c\uddf2\ud83c\uddfd",
                                        "\ud83c\uddf2\ud83c\uddfe",
                                        "\ud83c\uddf2\ud83c\uddff",
                                        "\ud83c\uddf3\ud83c\udde6",
                                        "\ud83c\uddf3\ud83c\udde8",
                                        "\ud83c\uddf3\ud83c\uddea",
                                        "\ud83c\uddf3\ud83c\uddeb",
                                        "\ud83c\uddf3\ud83c\uddec",
                                        "\ud83c\uddf3\ud83c\uddee",
                                        "\ud83c\uddf3\ud83c\uddf1",
                                        "\ud83c\uddf3\ud83c\uddf4",
                                        "\ud83c\uddf3\ud83c\uddf5",
                                        "\ud83c\uddf3\ud83c\uddf7",
                                        "\ud83c\uddf3\ud83c\uddfa",
                                        "\ud83c\uddf3\ud83c\uddff",
                                        "\ud83c\uddf4\ud83c\uddf2",
                                        "\ud83c\uddf5\ud83c\udde6",
                                        "\ud83c\uddf5\ud83c\uddea",
                                        "\ud83c\uddf5\ud83c\uddeb",
                                        "\ud83c\uddf5\ud83c\uddec",
                                        "\ud83c\uddf5\ud83c\udded",
                                        "\ud83c\uddf5\ud83c\uddf0",
                                        "\ud83c\uddf5\ud83c\uddf1",
                                        "\ud83c\uddf5\ud83c\uddf2",
                                        "\ud83c\uddf5\ud83c\uddf3",
                                        "\ud83c\uddf5\ud83c\uddf7",
                                        "\ud83c\uddf5\ud83c\uddf8",
                                        "\ud83c\uddf5\ud83c\uddf9",
                                        "\ud83c\uddf5\ud83c\uddfc",
                                        "\ud83c\uddf5\ud83c\uddfe",
                                        "\ud83c\uddf6\ud83c\udde6",
                                        "\ud83c\uddf7\ud83c\uddea",
                                        "\ud83c\uddf7\ud83c\uddf4",
                                        "\ud83c\uddf7\ud83c\uddf8",
                                        "\ud83c\uddf7\ud83c\uddfa",
                                        "\ud83c\uddf7\ud83c\uddfc",
                                        "\ud83c\uddf8\ud83c\udde6",
                                        "\ud83c\uddf8\ud83c\udde7",
                                        "\ud83c\uddf8\ud83c\udde8",
                                        "\ud83c\uddf8\ud83c\udde9",
                                        "\ud83c\uddf8\ud83c\uddea",
                                        "\ud83c\uddf8\ud83c\uddec",
                                        "\ud83c\uddf8\ud83c\udded",
                                        "\ud83c\uddf8\ud83c\uddee",
                                        "\ud83c\uddf8\ud83c\uddef",
                                        "\ud83c\uddf8\ud83c\uddf0",
                                        "\ud83c\uddf8\ud83c\uddf1",
                                        "\ud83c\uddf8\ud83c\uddf2",
                                        "\ud83c\uddf8\ud83c\uddf3",
                                        "\ud83c\uddf8\ud83c\uddf4",
                                        "\ud83c\uddf8\ud83c\uddf7",
                                        "\ud83c\uddf8\ud83c\uddf8",
                                        "\ud83c\uddf8\ud83c\uddf9",
                                        "\ud83c\uddf8\ud83c\uddfb",
                                        "\ud83c\uddf8\ud83c\uddfd",
                                        "\ud83c\uddf8\ud83c\uddfe",
                                        "\ud83c\uddf8\ud83c\uddff",
                                        "\ud83c\uddf9\ud83c\udde6",
                                        "\ud83c\uddf9\ud83c\udde8",
                                        "\ud83c\uddf9\ud83c\udde9",
                                        "\ud83c\uddf9\ud83c\uddeb",
                                        "\ud83c\uddf9\ud83c\uddec",
                                        "\ud83c\uddf9\ud83c\udded",
                                        "\ud83c\uddf9\ud83c\uddef",
                                        "\ud83c\uddf9\ud83c\uddf0",
                                        "\ud83c\uddf9\ud83c\uddf1",
                                        "\ud83c\uddf9\ud83c\uddf2",
                                        "\ud83c\uddf9\ud83c\uddf3",
                                        "\ud83c\uddf9\ud83c\uddf4",
                                        "\ud83c\uddf9\ud83c\uddf7",
                                        "\ud83c\uddf9\ud83c\uddf9",
                                        "\ud83c\uddf9\ud83c\uddfb",
                                        "\ud83c\uddf9\ud83c\uddfc",
                                        "\ud83c\uddf9\ud83c\uddff",
                                        "\ud83c\uddfa\ud83c\udde6",
                                        "\ud83c\uddfa\ud83c\uddec",
                                        "\ud83c\uddfa\ud83c\uddf2",
                                        "\ud83c\uddfa\ud83c\uddf3",
                                        "\ud83c\uddfa\ud83c\uddf8",
                                        "\ud83c\uddfa\ud83c\uddfe",
                                        "\ud83c\uddfa\ud83c\uddff",
                                        "\ud83c\uddfb\ud83c\udde6",
                                        "\ud83c\uddfb\ud83c\udde8",
                                        "\ud83c\uddfb\ud83c\uddea",
                                        "\ud83c\uddfb\ud83c\uddec",
                                        "\ud83c\uddfb\ud83c\uddee",
                                        "\ud83c\uddfb\ud83c\uddf3",
                                        "\ud83c\uddfb\ud83c\uddfa",
                                        "\ud83c\uddfc\ud83c\uddeb",
                                        "\ud83c\uddfc\ud83c\uddf8",
                                        "\ud83c\uddfd\ud83c\uddf0",
                                        "\ud83c\uddfe\ud83c\uddea",
                                        "\ud83c\uddfe\ud83c\uddf9",
                                        "\ud83c\uddff\ud83c\udde6",
                                        "\ud83c\uddff\ud83c\uddf2",
                                        "\ud83c\uddff\ud83c\uddfc")));
    }

    private static void populateRGI_EMOJI_MODIFIER_SEQUENCE() {
        CLASS_SET_ENCODINGS.put("RGI_Emoji_Modifier_Sequence", ClassSetContents.createUnicodePropertyOfStrings(
                        CodePointSet.getEmpty(), stringSet(
                                        "\u261d\ud83c\udffb",
                                        "\u261d\ud83c\udffc",
                                        "\u261d\ud83c\udffd",
                                        "\u261d\ud83c\udffe",
                                        "\u261d\ud83c\udfff",
                                        "\u26f9\ud83c\udffb",
                                        "\u26f9\ud83c\udffc",
                                        "\u26f9\ud83c\udffd",
                                        "\u26f9\ud83c\udffe",
                                        "\u26f9\ud83c\udfff",
                                        "\u270a\ud83c\udffb",
                                        "\u270a\ud83c\udffc",
                                        "\u270a\ud83c\udffd",
                                        "\u270a\ud83c\udffe",
                                        "\u270a\ud83c\udfff",
                                        "\u270b\ud83c\udffb",
                                        "\u270b\ud83c\udffc",
                                        "\u270b\ud83c\udffd",
                                        "\u270b\ud83c\udffe",
                                        "\u270b\ud83c\udfff",
                                        "\u270c\ud83c\udffb",
                                        "\u270c\ud83c\udffc",
                                        "\u270c\ud83c\udffd",
                                        "\u270c\ud83c\udffe",
                                        "\u270c\ud83c\udfff",
                                        "\u270d\ud83c\udffb",
                                        "\u270d\ud83c\udffc",
                                        "\u270d\ud83c\udffd",
                                        "\u270d\ud83c\udffe",
                                        "\u270d\ud83c\udfff",
                                        "\ud83c\udf85\ud83c\udffb",
                                        "\ud83c\udf85\ud83c\udffc",
                                        "\ud83c\udf85\ud83c\udffd",
                                        "\ud83c\udf85\ud83c\udffe",
                                        "\ud83c\udf85\ud83c\udfff",
                                        "\ud83c\udfc2\ud83c\udffb",
                                        "\ud83c\udfc2\ud83c\udffc",
                                        "\ud83c\udfc2\ud83c\udffd",
                                        "\ud83c\udfc2\ud83c\udffe",
                                        "\ud83c\udfc2\ud83c\udfff",
                                        "\ud83c\udfc3\ud83c\udffb",
                                        "\ud83c\udfc3\ud83c\udffc",
                                        "\ud83c\udfc3\ud83c\udffd",
                                        "\ud83c\udfc3\ud83c\udffe",
                                        "\ud83c\udfc3\ud83c\udfff",
                                        "\ud83c\udfc4\ud83c\udffb",
                                        "\ud83c\udfc4\ud83c\udffc",
                                        "\ud83c\udfc4\ud83c\udffd",
                                        "\ud83c\udfc4\ud83c\udffe",
                                        "\ud83c\udfc4\ud83c\udfff",
                                        "\ud83c\udfc7\ud83c\udffb",
                                        "\ud83c\udfc7\ud83c\udffc",
                                        "\ud83c\udfc7\ud83c\udffd",
                                        "\ud83c\udfc7\ud83c\udffe",
                                        "\ud83c\udfc7\ud83c\udfff",
                                        "\ud83c\udfca\ud83c\udffb",
                                        "\ud83c\udfca\ud83c\udffc",
                                        "\ud83c\udfca\ud83c\udffd",
                                        "\ud83c\udfca\ud83c\udffe",
                                        "\ud83c\udfca\ud83c\udfff",
                                        "\ud83c\udfcb\ud83c\udffb",
                                        "\ud83c\udfcb\ud83c\udffc",
                                        "\ud83c\udfcb\ud83c\udffd",
                                        "\ud83c\udfcb\ud83c\udffe",
                                        "\ud83c\udfcb\ud83c\udfff",
                                        "\ud83c\udfcc\ud83c\udffb",
                                        "\ud83c\udfcc\ud83c\udffc",
                                        "\ud83c\udfcc\ud83c\udffd",
                                        "\ud83c\udfcc\ud83c\udffe",
                                        "\ud83c\udfcc\ud83c\udfff",
                                        "\ud83d\udc42\ud83c\udffb",
                                        "\ud83d\udc42\ud83c\udffc",
                                        "\ud83d\udc42\ud83c\udffd",
                                        "\ud83d\udc42\ud83c\udffe",
                                        "\ud83d\udc42\ud83c\udfff",
                                        "\ud83d\udc43\ud83c\udffb",
                                        "\ud83d\udc43\ud83c\udffc",
                                        "\ud83d\udc43\ud83c\udffd",
                                        "\ud83d\udc43\ud83c\udffe",
                                        "\ud83d\udc43\ud83c\udfff",
                                        "\ud83d\udc46\ud83c\udffb",
                                        "\ud83d\udc46\ud83c\udffc",
                                        "\ud83d\udc46\ud83c\udffd",
                                        "\ud83d\udc46\ud83c\udffe",
                                        "\ud83d\udc46\ud83c\udfff",
                                        "\ud83d\udc47\ud83c\udffb",
                                        "\ud83d\udc47\ud83c\udffc",
                                        "\ud83d\udc47\ud83c\udffd",
                                        "\ud83d\udc47\ud83c\udffe",
                                        "\ud83d\udc47\ud83c\udfff",
                                        "\ud83d\udc48\ud83c\udffb",
                                        "\ud83d\udc48\ud83c\udffc",
                                        "\ud83d\udc48\ud83c\udffd",
                                        "\ud83d\udc48\ud83c\udffe",
                                        "\ud83d\udc48\ud83c\udfff",
                                        "\ud83d\udc49\ud83c\udffb",
                                        "\ud83d\udc49\ud83c\udffc",
                                        "\ud83d\udc49\ud83c\udffd",
                                        "\ud83d\udc49\ud83c\udffe",
                                        "\ud83d\udc49\ud83c\udfff",
                                        "\ud83d\udc4a\ud83c\udffb",
                                        "\ud83d\udc4a\ud83c\udffc",
                                        "\ud83d\udc4a\ud83c\udffd",
                                        "\ud83d\udc4a\ud83c\udffe",
                                        "\ud83d\udc4a\ud83c\udfff",
                                        "\ud83d\udc4b\ud83c\udffb",
                                        "\ud83d\udc4b\ud83c\udffc",
                                        "\ud83d\udc4b\ud83c\udffd",
                                        "\ud83d\udc4b\ud83c\udffe",
                                        "\ud83d\udc4b\ud83c\udfff",
                                        "\ud83d\udc4c\ud83c\udffb",
                                        "\ud83d\udc4c\ud83c\udffc",
                                        "\ud83d\udc4c\ud83c\udffd",
                                        "\ud83d\udc4c\ud83c\udffe",
                                        "\ud83d\udc4c\ud83c\udfff",
                                        "\ud83d\udc4d\ud83c\udffb",
                                        "\ud83d\udc4d\ud83c\udffc",
                                        "\ud83d\udc4d\ud83c\udffd",
                                        "\ud83d\udc4d\ud83c\udffe",
                                        "\ud83d\udc4d\ud83c\udfff",
                                        "\ud83d\udc4e\ud83c\udffb",
                                        "\ud83d\udc4e\ud83c\udffc",
                                        "\ud83d\udc4e\ud83c\udffd",
                                        "\ud83d\udc4e\ud83c\udffe",
                                        "\ud83d\udc4e\ud83c\udfff",
                                        "\ud83d\udc4f\ud83c\udffb",
                                        "\ud83d\udc4f\ud83c\udffc",
                                        "\ud83d\udc4f\ud83c\udffd",
                                        "\ud83d\udc4f\ud83c\udffe",
                                        "\ud83d\udc4f\ud83c\udfff",
                                        "\ud83d\udc50\ud83c\udffb",
                                        "\ud83d\udc50\ud83c\udffc",
                                        "\ud83d\udc50\ud83c\udffd",
                                        "\ud83d\udc50\ud83c\udffe",
                                        "\ud83d\udc50\ud83c\udfff",
                                        "\ud83d\udc66\ud83c\udffb",
                                        "\ud83d\udc66\ud83c\udffc",
                                        "\ud83d\udc66\ud83c\udffd",
                                        "\ud83d\udc66\ud83c\udffe",
                                        "\ud83d\udc66\ud83c\udfff",
                                        "\ud83d\udc67\ud83c\udffb",
                                        "\ud83d\udc67\ud83c\udffc",
                                        "\ud83d\udc67\ud83c\udffd",
                                        "\ud83d\udc67\ud83c\udffe",
                                        "\ud83d\udc67\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc6b\ud83c\udffb",
                                        "\ud83d\udc6b\ud83c\udffc",
                                        "\ud83d\udc6b\ud83c\udffd",
                                        "\ud83d\udc6b\ud83c\udffe",
                                        "\ud83d\udc6b\ud83c\udfff",
                                        "\ud83d\udc6c\ud83c\udffb",
                                        "\ud83d\udc6c\ud83c\udffc",
                                        "\ud83d\udc6c\ud83c\udffd",
                                        "\ud83d\udc6c\ud83c\udffe",
                                        "\ud83d\udc6c\ud83c\udfff",
                                        "\ud83d\udc6d\ud83c\udffb",
                                        "\ud83d\udc6d\ud83c\udffc",
                                        "\ud83d\udc6d\ud83c\udffd",
                                        "\ud83d\udc6d\ud83c\udffe",
                                        "\ud83d\udc6d\ud83c\udfff",
                                        "\ud83d\udc6e\ud83c\udffb",
                                        "\ud83d\udc6e\ud83c\udffc",
                                        "\ud83d\udc6e\ud83c\udffd",
                                        "\ud83d\udc6e\ud83c\udffe",
                                        "\ud83d\udc6e\ud83c\udfff",
                                        "\ud83d\udc70\ud83c\udffb",
                                        "\ud83d\udc70\ud83c\udffc",
                                        "\ud83d\udc70\ud83c\udffd",
                                        "\ud83d\udc70\ud83c\udffe",
                                        "\ud83d\udc70\ud83c\udfff",
                                        "\ud83d\udc71\ud83c\udffb",
                                        "\ud83d\udc71\ud83c\udffc",
                                        "\ud83d\udc71\ud83c\udffd",
                                        "\ud83d\udc71\ud83c\udffe",
                                        "\ud83d\udc71\ud83c\udfff",
                                        "\ud83d\udc72\ud83c\udffb",
                                        "\ud83d\udc72\ud83c\udffc",
                                        "\ud83d\udc72\ud83c\udffd",
                                        "\ud83d\udc72\ud83c\udffe",
                                        "\ud83d\udc72\ud83c\udfff",
                                        "\ud83d\udc73\ud83c\udffb",
                                        "\ud83d\udc73\ud83c\udffc",
                                        "\ud83d\udc73\ud83c\udffd",
                                        "\ud83d\udc73\ud83c\udffe",
                                        "\ud83d\udc73\ud83c\udfff",
                                        "\ud83d\udc74\ud83c\udffb",
                                        "\ud83d\udc74\ud83c\udffc",
                                        "\ud83d\udc74\ud83c\udffd",
                                        "\ud83d\udc74\ud83c\udffe",
                                        "\ud83d\udc74\ud83c\udfff",
                                        "\ud83d\udc75\ud83c\udffb",
                                        "\ud83d\udc75\ud83c\udffc",
                                        "\ud83d\udc75\ud83c\udffd",
                                        "\ud83d\udc75\ud83c\udffe",
                                        "\ud83d\udc75\ud83c\udfff",
                                        "\ud83d\udc76\ud83c\udffb",
                                        "\ud83d\udc76\ud83c\udffc",
                                        "\ud83d\udc76\ud83c\udffd",
                                        "\ud83d\udc76\ud83c\udffe",
                                        "\ud83d\udc76\ud83c\udfff",
                                        "\ud83d\udc77\ud83c\udffb",
                                        "\ud83d\udc77\ud83c\udffc",
                                        "\ud83d\udc77\ud83c\udffd",
                                        "\ud83d\udc77\ud83c\udffe",
                                        "\ud83d\udc77\ud83c\udfff",
                                        "\ud83d\udc78\ud83c\udffb",
                                        "\ud83d\udc78\ud83c\udffc",
                                        "\ud83d\udc78\ud83c\udffd",
                                        "\ud83d\udc78\ud83c\udffe",
                                        "\ud83d\udc78\ud83c\udfff",
                                        "\ud83d\udc7c\ud83c\udffb",
                                        "\ud83d\udc7c\ud83c\udffc",
                                        "\ud83d\udc7c\ud83c\udffd",
                                        "\ud83d\udc7c\ud83c\udffe",
                                        "\ud83d\udc7c\ud83c\udfff",
                                        "\ud83d\udc81\ud83c\udffb",
                                        "\ud83d\udc81\ud83c\udffc",
                                        "\ud83d\udc81\ud83c\udffd",
                                        "\ud83d\udc81\ud83c\udffe",
                                        "\ud83d\udc81\ud83c\udfff",
                                        "\ud83d\udc82\ud83c\udffb",
                                        "\ud83d\udc82\ud83c\udffc",
                                        "\ud83d\udc82\ud83c\udffd",
                                        "\ud83d\udc82\ud83c\udffe",
                                        "\ud83d\udc82\ud83c\udfff",
                                        "\ud83d\udc83\ud83c\udffb",
                                        "\ud83d\udc83\ud83c\udffc",
                                        "\ud83d\udc83\ud83c\udffd",
                                        "\ud83d\udc83\ud83c\udffe",
                                        "\ud83d\udc83\ud83c\udfff",
                                        "\ud83d\udc85\ud83c\udffb",
                                        "\ud83d\udc85\ud83c\udffc",
                                        "\ud83d\udc85\ud83c\udffd",
                                        "\ud83d\udc85\ud83c\udffe",
                                        "\ud83d\udc85\ud83c\udfff",
                                        "\ud83d\udc86\ud83c\udffb",
                                        "\ud83d\udc86\ud83c\udffc",
                                        "\ud83d\udc86\ud83c\udffd",
                                        "\ud83d\udc86\ud83c\udffe",
                                        "\ud83d\udc86\ud83c\udfff",
                                        "\ud83d\udc87\ud83c\udffb",
                                        "\ud83d\udc87\ud83c\udffc",
                                        "\ud83d\udc87\ud83c\udffd",
                                        "\ud83d\udc87\ud83c\udffe",
                                        "\ud83d\udc87\ud83c\udfff",
                                        "\ud83d\udc8f\ud83c\udffb",
                                        "\ud83d\udc8f\ud83c\udffc",
                                        "\ud83d\udc8f\ud83c\udffd",
                                        "\ud83d\udc8f\ud83c\udffe",
                                        "\ud83d\udc8f\ud83c\udfff",
                                        "\ud83d\udc91\ud83c\udffb",
                                        "\ud83d\udc91\ud83c\udffc",
                                        "\ud83d\udc91\ud83c\udffd",
                                        "\ud83d\udc91\ud83c\udffe",
                                        "\ud83d\udc91\ud83c\udfff",
                                        "\ud83d\udcaa\ud83c\udffb",
                                        "\ud83d\udcaa\ud83c\udffc",
                                        "\ud83d\udcaa\ud83c\udffd",
                                        "\ud83d\udcaa\ud83c\udffe",
                                        "\ud83d\udcaa\ud83c\udfff",
                                        "\ud83d\udd74\ud83c\udffb",
                                        "\ud83d\udd74\ud83c\udffc",
                                        "\ud83d\udd74\ud83c\udffd",
                                        "\ud83d\udd74\ud83c\udffe",
                                        "\ud83d\udd74\ud83c\udfff",
                                        "\ud83d\udd75\ud83c\udffb",
                                        "\ud83d\udd75\ud83c\udffc",
                                        "\ud83d\udd75\ud83c\udffd",
                                        "\ud83d\udd75\ud83c\udffe",
                                        "\ud83d\udd75\ud83c\udfff",
                                        "\ud83d\udd7a\ud83c\udffb",
                                        "\ud83d\udd7a\ud83c\udffc",
                                        "\ud83d\udd7a\ud83c\udffd",
                                        "\ud83d\udd7a\ud83c\udffe",
                                        "\ud83d\udd7a\ud83c\udfff",
                                        "\ud83d\udd90\ud83c\udffb",
                                        "\ud83d\udd90\ud83c\udffc",
                                        "\ud83d\udd90\ud83c\udffd",
                                        "\ud83d\udd90\ud83c\udffe",
                                        "\ud83d\udd90\ud83c\udfff",
                                        "\ud83d\udd95\ud83c\udffb",
                                        "\ud83d\udd95\ud83c\udffc",
                                        "\ud83d\udd95\ud83c\udffd",
                                        "\ud83d\udd95\ud83c\udffe",
                                        "\ud83d\udd95\ud83c\udfff",
                                        "\ud83d\udd96\ud83c\udffb",
                                        "\ud83d\udd96\ud83c\udffc",
                                        "\ud83d\udd96\ud83c\udffd",
                                        "\ud83d\udd96\ud83c\udffe",
                                        "\ud83d\udd96\ud83c\udfff",
                                        "\ud83d\ude45\ud83c\udffb",
                                        "\ud83d\ude45\ud83c\udffc",
                                        "\ud83d\ude45\ud83c\udffd",
                                        "\ud83d\ude45\ud83c\udffe",
                                        "\ud83d\ude45\ud83c\udfff",
                                        "\ud83d\ude46\ud83c\udffb",
                                        "\ud83d\ude46\ud83c\udffc",
                                        "\ud83d\ude46\ud83c\udffd",
                                        "\ud83d\ude46\ud83c\udffe",
                                        "\ud83d\ude46\ud83c\udfff",
                                        "\ud83d\ude47\ud83c\udffb",
                                        "\ud83d\ude47\ud83c\udffc",
                                        "\ud83d\ude47\ud83c\udffd",
                                        "\ud83d\ude47\ud83c\udffe",
                                        "\ud83d\ude47\ud83c\udfff",
                                        "\ud83d\ude4b\ud83c\udffb",
                                        "\ud83d\ude4b\ud83c\udffc",
                                        "\ud83d\ude4b\ud83c\udffd",
                                        "\ud83d\ude4b\ud83c\udffe",
                                        "\ud83d\ude4b\ud83c\udfff",
                                        "\ud83d\ude4c\ud83c\udffb",
                                        "\ud83d\ude4c\ud83c\udffc",
                                        "\ud83d\ude4c\ud83c\udffd",
                                        "\ud83d\ude4c\ud83c\udffe",
                                        "\ud83d\ude4c\ud83c\udfff",
                                        "\ud83d\ude4d\ud83c\udffb",
                                        "\ud83d\ude4d\ud83c\udffc",
                                        "\ud83d\ude4d\ud83c\udffd",
                                        "\ud83d\ude4d\ud83c\udffe",
                                        "\ud83d\ude4d\ud83c\udfff",
                                        "\ud83d\ude4e\ud83c\udffb",
                                        "\ud83d\ude4e\ud83c\udffc",
                                        "\ud83d\ude4e\ud83c\udffd",
                                        "\ud83d\ude4e\ud83c\udffe",
                                        "\ud83d\ude4e\ud83c\udfff",
                                        "\ud83d\ude4f\ud83c\udffb",
                                        "\ud83d\ude4f\ud83c\udffc",
                                        "\ud83d\ude4f\ud83c\udffd",
                                        "\ud83d\ude4f\ud83c\udffe",
                                        "\ud83d\ude4f\ud83c\udfff",
                                        "\ud83d\udea3\ud83c\udffb",
                                        "\ud83d\udea3\ud83c\udffc",
                                        "\ud83d\udea3\ud83c\udffd",
                                        "\ud83d\udea3\ud83c\udffe",
                                        "\ud83d\udea3\ud83c\udfff",
                                        "\ud83d\udeb4\ud83c\udffb",
                                        "\ud83d\udeb4\ud83c\udffc",
                                        "\ud83d\udeb4\ud83c\udffd",
                                        "\ud83d\udeb4\ud83c\udffe",
                                        "\ud83d\udeb4\ud83c\udfff",
                                        "\ud83d\udeb5\ud83c\udffb",
                                        "\ud83d\udeb5\ud83c\udffc",
                                        "\ud83d\udeb5\ud83c\udffd",
                                        "\ud83d\udeb5\ud83c\udffe",
                                        "\ud83d\udeb5\ud83c\udfff",
                                        "\ud83d\udeb6\ud83c\udffb",
                                        "\ud83d\udeb6\ud83c\udffc",
                                        "\ud83d\udeb6\ud83c\udffd",
                                        "\ud83d\udeb6\ud83c\udffe",
                                        "\ud83d\udeb6\ud83c\udfff",
                                        "\ud83d\udec0\ud83c\udffb",
                                        "\ud83d\udec0\ud83c\udffc",
                                        "\ud83d\udec0\ud83c\udffd",
                                        "\ud83d\udec0\ud83c\udffe",
                                        "\ud83d\udec0\ud83c\udfff",
                                        "\ud83d\udecc\ud83c\udffb",
                                        "\ud83d\udecc\ud83c\udffc",
                                        "\ud83d\udecc\ud83c\udffd",
                                        "\ud83d\udecc\ud83c\udffe",
                                        "\ud83d\udecc\ud83c\udfff",
                                        "\ud83e\udd0c\ud83c\udffb",
                                        "\ud83e\udd0c\ud83c\udffc",
                                        "\ud83e\udd0c\ud83c\udffd",
                                        "\ud83e\udd0c\ud83c\udffe",
                                        "\ud83e\udd0c\ud83c\udfff",
                                        "\ud83e\udd0f\ud83c\udffb",
                                        "\ud83e\udd0f\ud83c\udffc",
                                        "\ud83e\udd0f\ud83c\udffd",
                                        "\ud83e\udd0f\ud83c\udffe",
                                        "\ud83e\udd0f\ud83c\udfff",
                                        "\ud83e\udd18\ud83c\udffb",
                                        "\ud83e\udd18\ud83c\udffc",
                                        "\ud83e\udd18\ud83c\udffd",
                                        "\ud83e\udd18\ud83c\udffe",
                                        "\ud83e\udd18\ud83c\udfff",
                                        "\ud83e\udd19\ud83c\udffb",
                                        "\ud83e\udd19\ud83c\udffc",
                                        "\ud83e\udd19\ud83c\udffd",
                                        "\ud83e\udd19\ud83c\udffe",
                                        "\ud83e\udd19\ud83c\udfff",
                                        "\ud83e\udd1a\ud83c\udffb",
                                        "\ud83e\udd1a\ud83c\udffc",
                                        "\ud83e\udd1a\ud83c\udffd",
                                        "\ud83e\udd1a\ud83c\udffe",
                                        "\ud83e\udd1a\ud83c\udfff",
                                        "\ud83e\udd1b\ud83c\udffb",
                                        "\ud83e\udd1b\ud83c\udffc",
                                        "\ud83e\udd1b\ud83c\udffd",
                                        "\ud83e\udd1b\ud83c\udffe",
                                        "\ud83e\udd1b\ud83c\udfff",
                                        "\ud83e\udd1c\ud83c\udffb",
                                        "\ud83e\udd1c\ud83c\udffc",
                                        "\ud83e\udd1c\ud83c\udffd",
                                        "\ud83e\udd1c\ud83c\udffe",
                                        "\ud83e\udd1c\ud83c\udfff",
                                        "\ud83e\udd1d\ud83c\udffb",
                                        "\ud83e\udd1d\ud83c\udffc",
                                        "\ud83e\udd1d\ud83c\udffd",
                                        "\ud83e\udd1d\ud83c\udffe",
                                        "\ud83e\udd1d\ud83c\udfff",
                                        "\ud83e\udd1e\ud83c\udffb",
                                        "\ud83e\udd1e\ud83c\udffc",
                                        "\ud83e\udd1e\ud83c\udffd",
                                        "\ud83e\udd1e\ud83c\udffe",
                                        "\ud83e\udd1e\ud83c\udfff",
                                        "\ud83e\udd1f\ud83c\udffb",
                                        "\ud83e\udd1f\ud83c\udffc",
                                        "\ud83e\udd1f\ud83c\udffd",
                                        "\ud83e\udd1f\ud83c\udffe",
                                        "\ud83e\udd1f\ud83c\udfff",
                                        "\ud83e\udd26\ud83c\udffb",
                                        "\ud83e\udd26\ud83c\udffc",
                                        "\ud83e\udd26\ud83c\udffd",
                                        "\ud83e\udd26\ud83c\udffe",
                                        "\ud83e\udd26\ud83c\udfff",
                                        "\ud83e\udd30\ud83c\udffb",
                                        "\ud83e\udd30\ud83c\udffc",
                                        "\ud83e\udd30\ud83c\udffd",
                                        "\ud83e\udd30\ud83c\udffe",
                                        "\ud83e\udd30\ud83c\udfff",
                                        "\ud83e\udd31\ud83c\udffb",
                                        "\ud83e\udd31\ud83c\udffc",
                                        "\ud83e\udd31\ud83c\udffd",
                                        "\ud83e\udd31\ud83c\udffe",
                                        "\ud83e\udd31\ud83c\udfff",
                                        "\ud83e\udd32\ud83c\udffb",
                                        "\ud83e\udd32\ud83c\udffc",
                                        "\ud83e\udd32\ud83c\udffd",
                                        "\ud83e\udd32\ud83c\udffe",
                                        "\ud83e\udd32\ud83c\udfff",
                                        "\ud83e\udd33\ud83c\udffb",
                                        "\ud83e\udd33\ud83c\udffc",
                                        "\ud83e\udd33\ud83c\udffd",
                                        "\ud83e\udd33\ud83c\udffe",
                                        "\ud83e\udd33\ud83c\udfff",
                                        "\ud83e\udd34\ud83c\udffb",
                                        "\ud83e\udd34\ud83c\udffc",
                                        "\ud83e\udd34\ud83c\udffd",
                                        "\ud83e\udd34\ud83c\udffe",
                                        "\ud83e\udd34\ud83c\udfff",
                                        "\ud83e\udd35\ud83c\udffb",
                                        "\ud83e\udd35\ud83c\udffc",
                                        "\ud83e\udd35\ud83c\udffd",
                                        "\ud83e\udd35\ud83c\udffe",
                                        "\ud83e\udd35\ud83c\udfff",
                                        "\ud83e\udd36\ud83c\udffb",
                                        "\ud83e\udd36\ud83c\udffc",
                                        "\ud83e\udd36\ud83c\udffd",
                                        "\ud83e\udd36\ud83c\udffe",
                                        "\ud83e\udd36\ud83c\udfff",
                                        "\ud83e\udd37\ud83c\udffb",
                                        "\ud83e\udd37\ud83c\udffc",
                                        "\ud83e\udd37\ud83c\udffd",
                                        "\ud83e\udd37\ud83c\udffe",
                                        "\ud83e\udd37\ud83c\udfff",
                                        "\ud83e\udd38\ud83c\udffb",
                                        "\ud83e\udd38\ud83c\udffc",
                                        "\ud83e\udd38\ud83c\udffd",
                                        "\ud83e\udd38\ud83c\udffe",
                                        "\ud83e\udd38\ud83c\udfff",
                                        "\ud83e\udd39\ud83c\udffb",
                                        "\ud83e\udd39\ud83c\udffc",
                                        "\ud83e\udd39\ud83c\udffd",
                                        "\ud83e\udd39\ud83c\udffe",
                                        "\ud83e\udd39\ud83c\udfff",
                                        "\ud83e\udd3d\ud83c\udffb",
                                        "\ud83e\udd3d\ud83c\udffc",
                                        "\ud83e\udd3d\ud83c\udffd",
                                        "\ud83e\udd3d\ud83c\udffe",
                                        "\ud83e\udd3d\ud83c\udfff",
                                        "\ud83e\udd3e\ud83c\udffb",
                                        "\ud83e\udd3e\ud83c\udffc",
                                        "\ud83e\udd3e\ud83c\udffd",
                                        "\ud83e\udd3e\ud83c\udffe",
                                        "\ud83e\udd3e\ud83c\udfff",
                                        "\ud83e\udd77\ud83c\udffb",
                                        "\ud83e\udd77\ud83c\udffc",
                                        "\ud83e\udd77\ud83c\udffd",
                                        "\ud83e\udd77\ud83c\udffe",
                                        "\ud83e\udd77\ud83c\udfff",
                                        "\ud83e\uddb5\ud83c\udffb",
                                        "\ud83e\uddb5\ud83c\udffc",
                                        "\ud83e\uddb5\ud83c\udffd",
                                        "\ud83e\uddb5\ud83c\udffe",
                                        "\ud83e\uddb5\ud83c\udfff",
                                        "\ud83e\uddb6\ud83c\udffb",
                                        "\ud83e\uddb6\ud83c\udffc",
                                        "\ud83e\uddb6\ud83c\udffd",
                                        "\ud83e\uddb6\ud83c\udffe",
                                        "\ud83e\uddb6\ud83c\udfff",
                                        "\ud83e\uddb8\ud83c\udffb",
                                        "\ud83e\uddb8\ud83c\udffc",
                                        "\ud83e\uddb8\ud83c\udffd",
                                        "\ud83e\uddb8\ud83c\udffe",
                                        "\ud83e\uddb8\ud83c\udfff",
                                        "\ud83e\uddb9\ud83c\udffb",
                                        "\ud83e\uddb9\ud83c\udffc",
                                        "\ud83e\uddb9\ud83c\udffd",
                                        "\ud83e\uddb9\ud83c\udffe",
                                        "\ud83e\uddb9\ud83c\udfff",
                                        "\ud83e\uddbb\ud83c\udffb",
                                        "\ud83e\uddbb\ud83c\udffc",
                                        "\ud83e\uddbb\ud83c\udffd",
                                        "\ud83e\uddbb\ud83c\udffe",
                                        "\ud83e\uddbb\ud83c\udfff",
                                        "\ud83e\uddcd\ud83c\udffb",
                                        "\ud83e\uddcd\ud83c\udffc",
                                        "\ud83e\uddcd\ud83c\udffd",
                                        "\ud83e\uddcd\ud83c\udffe",
                                        "\ud83e\uddcd\ud83c\udfff",
                                        "\ud83e\uddce\ud83c\udffb",
                                        "\ud83e\uddce\ud83c\udffc",
                                        "\ud83e\uddce\ud83c\udffd",
                                        "\ud83e\uddce\ud83c\udffe",
                                        "\ud83e\uddce\ud83c\udfff",
                                        "\ud83e\uddcf\ud83c\udffb",
                                        "\ud83e\uddcf\ud83c\udffc",
                                        "\ud83e\uddcf\ud83c\udffd",
                                        "\ud83e\uddcf\ud83c\udffe",
                                        "\ud83e\uddcf\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd2\ud83c\udffb",
                                        "\ud83e\uddd2\ud83c\udffc",
                                        "\ud83e\uddd2\ud83c\udffd",
                                        "\ud83e\uddd2\ud83c\udffe",
                                        "\ud83e\uddd2\ud83c\udfff",
                                        "\ud83e\uddd3\ud83c\udffb",
                                        "\ud83e\uddd3\ud83c\udffc",
                                        "\ud83e\uddd3\ud83c\udffd",
                                        "\ud83e\uddd3\ud83c\udffe",
                                        "\ud83e\uddd3\ud83c\udfff",
                                        "\ud83e\uddd4\ud83c\udffb",
                                        "\ud83e\uddd4\ud83c\udffc",
                                        "\ud83e\uddd4\ud83c\udffd",
                                        "\ud83e\uddd4\ud83c\udffe",
                                        "\ud83e\uddd4\ud83c\udfff",
                                        "\ud83e\uddd5\ud83c\udffb",
                                        "\ud83e\uddd5\ud83c\udffc",
                                        "\ud83e\uddd5\ud83c\udffd",
                                        "\ud83e\uddd5\ud83c\udffe",
                                        "\ud83e\uddd5\ud83c\udfff",
                                        "\ud83e\uddd6\ud83c\udffb",
                                        "\ud83e\uddd6\ud83c\udffc",
                                        "\ud83e\uddd6\ud83c\udffd",
                                        "\ud83e\uddd6\ud83c\udffe",
                                        "\ud83e\uddd6\ud83c\udfff",
                                        "\ud83e\uddd7\ud83c\udffb",
                                        "\ud83e\uddd7\ud83c\udffc",
                                        "\ud83e\uddd7\ud83c\udffd",
                                        "\ud83e\uddd7\ud83c\udffe",
                                        "\ud83e\uddd7\ud83c\udfff",
                                        "\ud83e\uddd8\ud83c\udffb",
                                        "\ud83e\uddd8\ud83c\udffc",
                                        "\ud83e\uddd8\ud83c\udffd",
                                        "\ud83e\uddd8\ud83c\udffe",
                                        "\ud83e\uddd8\ud83c\udfff",
                                        "\ud83e\uddd9\ud83c\udffb",
                                        "\ud83e\uddd9\ud83c\udffc",
                                        "\ud83e\uddd9\ud83c\udffd",
                                        "\ud83e\uddd9\ud83c\udffe",
                                        "\ud83e\uddd9\ud83c\udfff",
                                        "\ud83e\uddda\ud83c\udffb",
                                        "\ud83e\uddda\ud83c\udffc",
                                        "\ud83e\uddda\ud83c\udffd",
                                        "\ud83e\uddda\ud83c\udffe",
                                        "\ud83e\uddda\ud83c\udfff",
                                        "\ud83e\udddb\ud83c\udffb",
                                        "\ud83e\udddb\ud83c\udffc",
                                        "\ud83e\udddb\ud83c\udffd",
                                        "\ud83e\udddb\ud83c\udffe",
                                        "\ud83e\udddb\ud83c\udfff",
                                        "\ud83e\udddc\ud83c\udffb",
                                        "\ud83e\udddc\ud83c\udffc",
                                        "\ud83e\udddc\ud83c\udffd",
                                        "\ud83e\udddc\ud83c\udffe",
                                        "\ud83e\udddc\ud83c\udfff",
                                        "\ud83e\udddd\ud83c\udffb",
                                        "\ud83e\udddd\ud83c\udffc",
                                        "\ud83e\udddd\ud83c\udffd",
                                        "\ud83e\udddd\ud83c\udffe",
                                        "\ud83e\udddd\ud83c\udfff",
                                        "\ud83e\udec3\ud83c\udffb",
                                        "\ud83e\udec3\ud83c\udffc",
                                        "\ud83e\udec3\ud83c\udffd",
                                        "\ud83e\udec3\ud83c\udffe",
                                        "\ud83e\udec3\ud83c\udfff",
                                        "\ud83e\udec4\ud83c\udffb",
                                        "\ud83e\udec4\ud83c\udffc",
                                        "\ud83e\udec4\ud83c\udffd",
                                        "\ud83e\udec4\ud83c\udffe",
                                        "\ud83e\udec4\ud83c\udfff",
                                        "\ud83e\udec5\ud83c\udffb",
                                        "\ud83e\udec5\ud83c\udffc",
                                        "\ud83e\udec5\ud83c\udffd",
                                        "\ud83e\udec5\ud83c\udffe",
                                        "\ud83e\udec5\ud83c\udfff",
                                        "\ud83e\udef0\ud83c\udffb",
                                        "\ud83e\udef0\ud83c\udffc",
                                        "\ud83e\udef0\ud83c\udffd",
                                        "\ud83e\udef0\ud83c\udffe",
                                        "\ud83e\udef0\ud83c\udfff",
                                        "\ud83e\udef1\ud83c\udffb",
                                        "\ud83e\udef1\ud83c\udffc",
                                        "\ud83e\udef1\ud83c\udffd",
                                        "\ud83e\udef1\ud83c\udffe",
                                        "\ud83e\udef1\ud83c\udfff",
                                        "\ud83e\udef2\ud83c\udffb",
                                        "\ud83e\udef2\ud83c\udffc",
                                        "\ud83e\udef2\ud83c\udffd",
                                        "\ud83e\udef2\ud83c\udffe",
                                        "\ud83e\udef2\ud83c\udfff",
                                        "\ud83e\udef3\ud83c\udffb",
                                        "\ud83e\udef3\ud83c\udffc",
                                        "\ud83e\udef3\ud83c\udffd",
                                        "\ud83e\udef3\ud83c\udffe",
                                        "\ud83e\udef3\ud83c\udfff",
                                        "\ud83e\udef4\ud83c\udffb",
                                        "\ud83e\udef4\ud83c\udffc",
                                        "\ud83e\udef4\ud83c\udffd",
                                        "\ud83e\udef4\ud83c\udffe",
                                        "\ud83e\udef4\ud83c\udfff",
                                        "\ud83e\udef5\ud83c\udffb",
                                        "\ud83e\udef5\ud83c\udffc",
                                        "\ud83e\udef5\ud83c\udffd",
                                        "\ud83e\udef5\ud83c\udffe",
                                        "\ud83e\udef5\ud83c\udfff",
                                        "\ud83e\udef6\ud83c\udffb",
                                        "\ud83e\udef6\ud83c\udffc",
                                        "\ud83e\udef6\ud83c\udffd",
                                        "\ud83e\udef6\ud83c\udffe",
                                        "\ud83e\udef6\ud83c\udfff",
                                        "\ud83e\udef7\ud83c\udffb",
                                        "\ud83e\udef7\ud83c\udffc",
                                        "\ud83e\udef7\ud83c\udffd",
                                        "\ud83e\udef7\ud83c\udffe",
                                        "\ud83e\udef7\ud83c\udfff",
                                        "\ud83e\udef8\ud83c\udffb",
                                        "\ud83e\udef8\ud83c\udffc",
                                        "\ud83e\udef8\ud83c\udffd",
                                        "\ud83e\udef8\ud83c\udffe",
                                        "\ud83e\udef8\ud83c\udfff")));
    }

    private static void populateRGI_EMOJI_TAG_SEQUENCE() {
        CLASS_SET_ENCODINGS.put("RGI_Emoji_Tag_Sequence", ClassSetContents.createUnicodePropertyOfStrings(
                        CodePointSet.getEmpty(), stringSet(
                                        "\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc65\udb40\udc6e\udb40\udc67\udb40\udc7f",
                                        "\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc73\udb40\udc63\udb40\udc74\udb40\udc7f",
                                        "\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f")));
    }

    private static void populateRGI_EMOJI_ZWJ_SEQUENCE() {
        CLASS_SET_ENCODINGS.put("RGI_Emoji_ZWJ_Sequence", ClassSetContents.createUnicodePropertyOfStrings(
                        CodePointSet.getEmpty(), stringSet(
                                        "\ud83d\udc68\u200d\u2764\ufe0f\u200d\ud83d\udc68",
                                        "\ud83d\udc68\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68",
                                        "\ud83d\udc68\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc66\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc67",
                                        "\ud83d\udc68\u200d\ud83d\udc67\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc67\u200d\ud83d\udc67",
                                        "\ud83d\udc68\u200d\ud83d\udc68\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc68\u200d\ud83d\udc66\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc68\u200d\ud83d\udc67",
                                        "\ud83d\udc68\u200d\ud83d\udc68\u200d\ud83d\udc67\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc68\u200d\ud83d\udc67\u200d\ud83d\udc67",
                                        "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc66\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67",
                                        "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66",
                                        "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc67",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\u200d\u2764\ufe0f\u200d\ud83d\udc68",
                                        "\ud83d\udc69\u200d\u2764\ufe0f\u200d\ud83d\udc69",
                                        "\ud83d\udc69\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68",
                                        "\ud83d\udc69\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69",
                                        "\ud83d\udc69\u200d\ud83d\udc66",
                                        "\ud83d\udc69\u200d\ud83d\udc66\u200d\ud83d\udc66",
                                        "\ud83d\udc69\u200d\ud83d\udc67",
                                        "\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66",
                                        "\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc67",
                                        "\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc66",
                                        "\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc66\u200d\ud83d\udc66",
                                        "\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc67",
                                        "\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66",
                                        "\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc67",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc68\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83d\udc69\ud83c\udfff",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc68\ud83c\udffe",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffb",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffc",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffd",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83d\udc69\ud83c\udffe",
                                        "\ud83e\uddd1\u200d\ud83e\udd1d\u200d\ud83e\uddd1",
                                        "\ud83e\uddd1\u200d\ud83e\uddd1\u200d\ud83e\uddd2",
                                        "\ud83e\uddd1\u200d\ud83e\uddd1\u200d\ud83e\uddd2\u200d\ud83e\uddd2",
                                        "\ud83e\uddd1\u200d\ud83e\uddd2",
                                        "\ud83e\uddd1\u200d\ud83e\uddd2\u200d\ud83e\uddd2",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83d\udc8b\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2764\ufe0f\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffb",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffc",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffd",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udffe",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\udd1d\u200d\ud83e\uddd1\ud83c\udfff",
                                        "\ud83e\udef1\ud83c\udffb\u200d\ud83e\udef2\ud83c\udffc",
                                        "\ud83e\udef1\ud83c\udffb\u200d\ud83e\udef2\ud83c\udffd",
                                        "\ud83e\udef1\ud83c\udffb\u200d\ud83e\udef2\ud83c\udffe",
                                        "\ud83e\udef1\ud83c\udffb\u200d\ud83e\udef2\ud83c\udfff",
                                        "\ud83e\udef1\ud83c\udffc\u200d\ud83e\udef2\ud83c\udffb",
                                        "\ud83e\udef1\ud83c\udffc\u200d\ud83e\udef2\ud83c\udffd",
                                        "\ud83e\udef1\ud83c\udffc\u200d\ud83e\udef2\ud83c\udffe",
                                        "\ud83e\udef1\ud83c\udffc\u200d\ud83e\udef2\ud83c\udfff",
                                        "\ud83e\udef1\ud83c\udffd\u200d\ud83e\udef2\ud83c\udffb",
                                        "\ud83e\udef1\ud83c\udffd\u200d\ud83e\udef2\ud83c\udffc",
                                        "\ud83e\udef1\ud83c\udffd\u200d\ud83e\udef2\ud83c\udffe",
                                        "\ud83e\udef1\ud83c\udffd\u200d\ud83e\udef2\ud83c\udfff",
                                        "\ud83e\udef1\ud83c\udffe\u200d\ud83e\udef2\ud83c\udffb",
                                        "\ud83e\udef1\ud83c\udffe\u200d\ud83e\udef2\ud83c\udffc",
                                        "\ud83e\udef1\ud83c\udffe\u200d\ud83e\udef2\ud83c\udffd",
                                        "\ud83e\udef1\ud83c\udffe\u200d\ud83e\udef2\ud83c\udfff",
                                        "\ud83e\udef1\ud83c\udfff\u200d\ud83e\udef2\ud83c\udffb",
                                        "\ud83e\udef1\ud83c\udfff\u200d\ud83e\udef2\ud83c\udffc",
                                        "\ud83e\udef1\ud83c\udfff\u200d\ud83e\udef2\ud83c\udffd",
                                        "\ud83e\udef1\ud83c\udfff\u200d\ud83e\udef2\ud83c\udffe",
                                        "\ud83c\udfc3\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffb\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffc\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffd\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffe\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udfff\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\u200d\u2695\ufe0f",
                                        "\ud83d\udc68\u200d\u2696\ufe0f",
                                        "\ud83d\udc68\u200d\u2708\ufe0f",
                                        "\ud83d\udc68\u200d\ud83c\udf3e",
                                        "\ud83d\udc68\u200d\ud83c\udf73",
                                        "\ud83d\udc68\u200d\ud83c\udf7c",
                                        "\ud83d\udc68\u200d\ud83c\udf93",
                                        "\ud83d\udc68\u200d\ud83c\udfa4",
                                        "\ud83d\udc68\u200d\ud83c\udfa8",
                                        "\ud83d\udc68\u200d\ud83c\udfeb",
                                        "\ud83d\udc68\u200d\ud83c\udfed",
                                        "\ud83d\udc68\u200d\ud83d\udcbb",
                                        "\ud83d\udc68\u200d\ud83d\udcbc",
                                        "\ud83d\udc68\u200d\ud83d\udd27",
                                        "\ud83d\udc68\u200d\ud83d\udd2c",
                                        "\ud83d\udc68\u200d\ud83d\ude80",
                                        "\ud83d\udc68\u200d\ud83d\ude92",
                                        "\ud83d\udc68\u200d\ud83e\uddaf",
                                        "\ud83d\udc68\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\u200d\ud83e\uddbc",
                                        "\ud83d\udc68\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\u200d\ud83e\uddbd",
                                        "\ud83d\udc68\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2695\ufe0f",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2696\ufe0f",
                                        "\ud83d\udc68\ud83c\udffb\u200d\u2708\ufe0f",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83c\udf3e",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83c\udf73",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83c\udf7c",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83c\udf93",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83c\udfa4",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83c\udfa8",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83c\udfeb",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83c\udfed",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83d\udcbb",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83d\udcbc",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83d\udd27",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83d\udd2c",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83d\ude80",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83d\ude92",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddaf",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddbc",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddbd",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2695\ufe0f",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2696\ufe0f",
                                        "\ud83d\udc68\ud83c\udffc\u200d\u2708\ufe0f",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83c\udf3e",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83c\udf73",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83c\udf7c",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83c\udf93",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83c\udfa4",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83c\udfa8",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83c\udfeb",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83c\udfed",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83d\udcbb",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83d\udcbc",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83d\udd27",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83d\udd2c",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83d\ude80",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83d\ude92",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddaf",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddbc",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddbd",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2695\ufe0f",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2696\ufe0f",
                                        "\ud83d\udc68\ud83c\udffd\u200d\u2708\ufe0f",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83c\udf3e",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83c\udf73",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83c\udf7c",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83c\udf93",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83c\udfa4",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83c\udfa8",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83c\udfeb",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83c\udfed",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83d\udcbb",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83d\udcbc",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83d\udd27",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83d\udd2c",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83d\ude80",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83d\ude92",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddaf",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddbc",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddbd",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2695\ufe0f",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2696\ufe0f",
                                        "\ud83d\udc68\ud83c\udffe\u200d\u2708\ufe0f",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83c\udf3e",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83c\udf73",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83c\udf7c",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83c\udf93",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83c\udfa4",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83c\udfa8",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83c\udfeb",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83c\udfed",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83d\udcbb",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83d\udcbc",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83d\udd27",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83d\udd2c",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83d\ude80",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83d\ude92",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddaf",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddbc",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddbd",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2695\ufe0f",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2696\ufe0f",
                                        "\ud83d\udc68\ud83c\udfff\u200d\u2708\ufe0f",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83c\udf3e",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83c\udf73",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83c\udf7c",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83c\udf93",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83c\udfa4",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83c\udfa8",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83c\udfeb",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83c\udfed",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83d\udcbb",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83d\udcbc",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83d\udd27",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83d\udd2c",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83d\ude80",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83d\ude92",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddaf",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddbc",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddbd",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\u200d\u2695\ufe0f",
                                        "\ud83d\udc69\u200d\u2696\ufe0f",
                                        "\ud83d\udc69\u200d\u2708\ufe0f",
                                        "\ud83d\udc69\u200d\ud83c\udf3e",
                                        "\ud83d\udc69\u200d\ud83c\udf73",
                                        "\ud83d\udc69\u200d\ud83c\udf7c",
                                        "\ud83d\udc69\u200d\ud83c\udf93",
                                        "\ud83d\udc69\u200d\ud83c\udfa4",
                                        "\ud83d\udc69\u200d\ud83c\udfa8",
                                        "\ud83d\udc69\u200d\ud83c\udfeb",
                                        "\ud83d\udc69\u200d\ud83c\udfed",
                                        "\ud83d\udc69\u200d\ud83d\udcbb",
                                        "\ud83d\udc69\u200d\ud83d\udcbc",
                                        "\ud83d\udc69\u200d\ud83d\udd27",
                                        "\ud83d\udc69\u200d\ud83d\udd2c",
                                        "\ud83d\udc69\u200d\ud83d\ude80",
                                        "\ud83d\udc69\u200d\ud83d\ude92",
                                        "\ud83d\udc69\u200d\ud83e\uddaf",
                                        "\ud83d\udc69\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\u200d\ud83e\uddbc",
                                        "\ud83d\udc69\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\u200d\ud83e\uddbd",
                                        "\ud83d\udc69\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2695\ufe0f",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2696\ufe0f",
                                        "\ud83d\udc69\ud83c\udffb\u200d\u2708\ufe0f",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83c\udf3e",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83c\udf73",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83c\udf7c",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83c\udf93",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83c\udfa4",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83c\udfa8",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83c\udfeb",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83c\udfed",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83d\udcbb",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83d\udcbc",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83d\udd27",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83d\udd2c",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83d\ude80",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83d\ude92",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddaf",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddbc",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddbd",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2695\ufe0f",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2696\ufe0f",
                                        "\ud83d\udc69\ud83c\udffc\u200d\u2708\ufe0f",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83c\udf3e",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83c\udf73",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83c\udf7c",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83c\udf93",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83c\udfa4",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83c\udfa8",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83c\udfeb",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83c\udfed",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83d\udcbb",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83d\udcbc",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83d\udd27",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83d\udd2c",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83d\ude80",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83d\ude92",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddaf",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddbc",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddbd",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2695\ufe0f",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2696\ufe0f",
                                        "\ud83d\udc69\ud83c\udffd\u200d\u2708\ufe0f",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83c\udf3e",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83c\udf73",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83c\udf7c",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83c\udf93",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83c\udfa4",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83c\udfa8",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83c\udfeb",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83c\udfed",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83d\udcbb",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83d\udcbc",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83d\udd27",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83d\udd2c",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83d\ude80",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83d\ude92",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddaf",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddbc",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddbd",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2695\ufe0f",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2696\ufe0f",
                                        "\ud83d\udc69\ud83c\udffe\u200d\u2708\ufe0f",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83c\udf3e",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83c\udf73",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83c\udf7c",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83c\udf93",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83c\udfa4",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83c\udfa8",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83c\udfeb",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83c\udfed",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83d\udcbb",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83d\udcbc",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83d\udd27",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83d\udd2c",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83d\ude80",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83d\ude92",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddaf",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddbc",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddbd",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2695\ufe0f",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2696\ufe0f",
                                        "\ud83d\udc69\ud83c\udfff\u200d\u2708\ufe0f",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83c\udf3e",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83c\udf73",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83c\udf7c",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83c\udf93",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83c\udfa4",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83c\udfa8",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83c\udfeb",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83c\udfed",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83d\udcbb",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83d\udcbc",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83d\udd27",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83d\udd2c",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83d\ude80",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83d\ude92",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddaf",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddbc",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddbd",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffb\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffc\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffd\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffe\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udfff\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffb\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffc\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffd\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffe\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udfff\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\u200d\u2695\ufe0f",
                                        "\ud83e\uddd1\u200d\u2696\ufe0f",
                                        "\ud83e\uddd1\u200d\u2708\ufe0f",
                                        "\ud83e\uddd1\u200d\ud83c\udf3e",
                                        "\ud83e\uddd1\u200d\ud83c\udf73",
                                        "\ud83e\uddd1\u200d\ud83c\udf7c",
                                        "\ud83e\uddd1\u200d\ud83c\udf84",
                                        "\ud83e\uddd1\u200d\ud83c\udf93",
                                        "\ud83e\uddd1\u200d\ud83c\udfa4",
                                        "\ud83e\uddd1\u200d\ud83c\udfa8",
                                        "\ud83e\uddd1\u200d\ud83c\udfeb",
                                        "\ud83e\uddd1\u200d\ud83c\udfed",
                                        "\ud83e\uddd1\u200d\ud83d\udcbb",
                                        "\ud83e\uddd1\u200d\ud83d\udcbc",
                                        "\ud83e\uddd1\u200d\ud83d\udd27",
                                        "\ud83e\uddd1\u200d\ud83d\udd2c",
                                        "\ud83e\uddd1\u200d\ud83d\ude80",
                                        "\ud83e\uddd1\u200d\ud83d\ude92",
                                        "\ud83e\uddd1\u200d\ud83e\uddaf",
                                        "\ud83e\uddd1\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\u200d\ud83e\uddbc",
                                        "\ud83e\uddd1\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\u200d\ud83e\uddbd",
                                        "\ud83e\uddd1\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2695\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2696\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\u2708\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udf3e",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udf73",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udf7c",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udf84",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udf93",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udfa4",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udfa8",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udfeb",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83c\udfed",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83d\udcbb",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83d\udcbc",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83d\udd27",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83d\udd2c",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83d\ude80",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83d\ude92",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddaf",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddbc",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddbd",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2695\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2696\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\u2708\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udf3e",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udf73",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udf7c",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udf84",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udf93",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udfa4",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udfa8",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udfeb",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83c\udfed",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83d\udcbb",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83d\udcbc",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83d\udd27",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83d\udd2c",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83d\ude80",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83d\ude92",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddaf",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddbc",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddbd",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2695\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2696\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\u2708\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udf3e",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udf73",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udf7c",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udf84",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udf93",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udfa4",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udfa8",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udfeb",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83c\udfed",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83d\udcbb",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83d\udcbc",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83d\udd27",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83d\udd2c",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83d\ude80",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83d\ude92",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddaf",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddbc",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddbd",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2695\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2696\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\u2708\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udf3e",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udf73",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udf7c",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udf84",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udf93",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udfa4",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udfa8",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udfeb",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83c\udfed",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83d\udcbb",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83d\udcbc",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83d\udd27",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83d\udd2c",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83d\ude80",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83d\ude92",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddaf",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddbc",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddbd",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2695\ufe0f",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2696\ufe0f",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\u2708\ufe0f",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udf3e",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udf73",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udf7c",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udf84",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udf93",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udfa4",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udfa8",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udfeb",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83c\udfed",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83d\udcbb",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83d\udcbc",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83d\udd27",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83d\udd2c",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83d\ude80",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83d\ude92",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddaf",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddaf\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddbc",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddbc\u200d\u27a1\ufe0f",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddbd",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddbd\u200d\u27a1\ufe0f",
                                        "\u26f9\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\u26f9\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\u26f9\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\u26f9\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\u26f9\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\u26f9\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\u26f9\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\u26f9\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\u26f9\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\u26f9\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\u26f9\ufe0f\u200d\u2640\ufe0f",
                                        "\u26f9\ufe0f\u200d\u2642\ufe0f",
                                        "\ud83c\udfc3\u200d\u2640\ufe0f",
                                        "\ud83c\udfc3\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\u200d\u2642\ufe0f",
                                        "\ud83c\udfc3\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffb\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffb\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffc\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffc\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffd\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffd\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffe\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83c\udfc3\ud83c\udffe\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83c\udfc3\ud83c\udfff\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc3\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83c\udfc3\ud83c\udfff\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83c\udfc4\u200d\u2640\ufe0f",
                                        "\ud83c\udfc4\u200d\u2642\ufe0f",
                                        "\ud83c\udfc4\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83c\udfc4\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83c\udfc4\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83c\udfc4\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83c\udfc4\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83c\udfc4\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83c\udfc4\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83c\udfc4\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83c\udfc4\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83c\udfc4\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83c\udfca\u200d\u2640\ufe0f",
                                        "\ud83c\udfca\u200d\u2642\ufe0f",
                                        "\ud83c\udfca\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83c\udfca\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83c\udfca\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83c\udfca\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83c\udfca\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83c\udfca\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83c\udfca\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83c\udfca\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83c\udfca\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83c\udfca\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83c\udfcb\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83c\udfcb\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83c\udfcb\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83c\udfcb\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83c\udfcb\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83c\udfcb\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83c\udfcb\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83c\udfcb\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83c\udfcb\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83c\udfcb\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83c\udfcb\ufe0f\u200d\u2640\ufe0f",
                                        "\ud83c\udfcb\ufe0f\u200d\u2642\ufe0f",
                                        "\ud83c\udfcc\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83c\udfcc\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83c\udfcc\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83c\udfcc\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83c\udfcc\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83c\udfcc\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83c\udfcc\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83c\udfcc\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83c\udfcc\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83c\udfcc\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83c\udfcc\ufe0f\u200d\u2640\ufe0f",
                                        "\ud83c\udfcc\ufe0f\u200d\u2642\ufe0f",
                                        "\ud83d\udc6e\u200d\u2640\ufe0f",
                                        "\ud83d\udc6e\u200d\u2642\ufe0f",
                                        "\ud83d\udc6e\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc6e\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc6e\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc6e\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc6e\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc6e\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc6e\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc6e\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc6e\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc6e\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udc6f\u200d\u2640\ufe0f",
                                        "\ud83d\udc6f\u200d\u2642\ufe0f",
                                        "\ud83d\udc70\u200d\u2640\ufe0f",
                                        "\ud83d\udc70\u200d\u2642\ufe0f",
                                        "\ud83d\udc70\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc70\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc70\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc70\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc70\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc70\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc70\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc70\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc70\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc70\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udc71\u200d\u2640\ufe0f",
                                        "\ud83d\udc71\u200d\u2642\ufe0f",
                                        "\ud83d\udc71\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc71\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc71\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc71\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc71\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc71\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc71\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc71\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc71\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc71\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udc73\u200d\u2640\ufe0f",
                                        "\ud83d\udc73\u200d\u2642\ufe0f",
                                        "\ud83d\udc73\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc73\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc73\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc73\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc73\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc73\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc73\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc73\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc73\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc73\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udc77\u200d\u2640\ufe0f",
                                        "\ud83d\udc77\u200d\u2642\ufe0f",
                                        "\ud83d\udc77\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc77\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc77\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc77\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc77\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc77\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc77\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc77\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc77\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc77\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udc81\u200d\u2640\ufe0f",
                                        "\ud83d\udc81\u200d\u2642\ufe0f",
                                        "\ud83d\udc81\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc81\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc81\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc81\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc81\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc81\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc81\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc81\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc81\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc81\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udc82\u200d\u2640\ufe0f",
                                        "\ud83d\udc82\u200d\u2642\ufe0f",
                                        "\ud83d\udc82\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc82\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc82\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc82\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc82\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc82\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc82\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc82\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc82\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc82\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udc86\u200d\u2640\ufe0f",
                                        "\ud83d\udc86\u200d\u2642\ufe0f",
                                        "\ud83d\udc86\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc86\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc86\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc86\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc86\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc86\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc86\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc86\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc86\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc86\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udc87\u200d\u2640\ufe0f",
                                        "\ud83d\udc87\u200d\u2642\ufe0f",
                                        "\ud83d\udc87\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udc87\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udc87\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udc87\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udc87\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udc87\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udc87\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udc87\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udc87\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udc87\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udd75\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udd75\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udd75\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udd75\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udd75\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udd75\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udd75\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udd75\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udd75\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udd75\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udd75\ufe0f\u200d\u2640\ufe0f",
                                        "\ud83d\udd75\ufe0f\u200d\u2642\ufe0f",
                                        "\ud83d\ude45\u200d\u2640\ufe0f",
                                        "\ud83d\ude45\u200d\u2642\ufe0f",
                                        "\ud83d\ude45\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\ude45\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\ude45\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\ude45\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\ude45\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\ude45\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\ude45\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\ude45\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\ude45\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\ude45\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\ude46\u200d\u2640\ufe0f",
                                        "\ud83d\ude46\u200d\u2642\ufe0f",
                                        "\ud83d\ude46\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\ude46\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\ude46\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\ude46\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\ude46\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\ude46\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\ude46\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\ude46\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\ude46\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\ude46\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\ude47\u200d\u2640\ufe0f",
                                        "\ud83d\ude47\u200d\u2642\ufe0f",
                                        "\ud83d\ude47\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\ude47\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\ude47\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\ude47\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\ude47\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\ude47\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\ude47\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\ude47\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\ude47\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\ude47\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\ude4b\u200d\u2640\ufe0f",
                                        "\ud83d\ude4b\u200d\u2642\ufe0f",
                                        "\ud83d\ude4b\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\ude4b\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\ude4b\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\ude4b\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\ude4b\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\ude4b\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\ude4b\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\ude4b\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\ude4b\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\ude4b\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\ude4d\u200d\u2640\ufe0f",
                                        "\ud83d\ude4d\u200d\u2642\ufe0f",
                                        "\ud83d\ude4d\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\ude4d\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\ude4d\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\ude4d\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\ude4d\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\ude4d\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\ude4d\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\ude4d\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\ude4d\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\ude4d\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\ude4e\u200d\u2640\ufe0f",
                                        "\ud83d\ude4e\u200d\u2642\ufe0f",
                                        "\ud83d\ude4e\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\ude4e\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\ude4e\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\ude4e\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\ude4e\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\ude4e\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\ude4e\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\ude4e\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\ude4e\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\ude4e\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udea3\u200d\u2640\ufe0f",
                                        "\ud83d\udea3\u200d\u2642\ufe0f",
                                        "\ud83d\udea3\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udea3\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udea3\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udea3\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udea3\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udea3\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udea3\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udea3\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udea3\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udea3\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udeb4\u200d\u2640\ufe0f",
                                        "\ud83d\udeb4\u200d\u2642\ufe0f",
                                        "\ud83d\udeb4\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udeb4\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udeb4\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udeb4\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udeb4\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udeb4\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udeb4\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udeb4\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udeb4\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udeb4\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udeb5\u200d\u2640\ufe0f",
                                        "\ud83d\udeb5\u200d\u2642\ufe0f",
                                        "\ud83d\udeb5\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udeb5\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udeb5\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udeb5\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udeb5\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udeb5\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udeb5\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udeb5\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udeb5\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udeb5\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udeb6\u200d\u2640\ufe0f",
                                        "\ud83d\udeb6\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\u200d\u2642\ufe0f",
                                        "\ud83d\udeb6\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffb\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffb\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffc\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffc\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffd\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffd\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffe\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83d\udeb6\ud83c\udffe\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83d\udeb6\ud83c\udfff\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83d\udeb6\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83d\udeb6\ud83c\udfff\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\udd26\u200d\u2640\ufe0f",
                                        "\ud83e\udd26\u200d\u2642\ufe0f",
                                        "\ud83e\udd26\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udd26\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udd26\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udd26\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udd26\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udd26\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udd26\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udd26\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udd26\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udd26\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udd35\u200d\u2640\ufe0f",
                                        "\ud83e\udd35\u200d\u2642\ufe0f",
                                        "\ud83e\udd35\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udd35\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udd35\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udd35\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udd35\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udd35\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udd35\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udd35\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udd35\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udd35\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udd37\u200d\u2640\ufe0f",
                                        "\ud83e\udd37\u200d\u2642\ufe0f",
                                        "\ud83e\udd37\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udd37\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udd37\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udd37\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udd37\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udd37\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udd37\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udd37\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udd37\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udd37\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udd38\u200d\u2640\ufe0f",
                                        "\ud83e\udd38\u200d\u2642\ufe0f",
                                        "\ud83e\udd38\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udd38\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udd38\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udd38\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udd38\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udd38\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udd38\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udd38\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udd38\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udd38\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udd39\u200d\u2640\ufe0f",
                                        "\ud83e\udd39\u200d\u2642\ufe0f",
                                        "\ud83e\udd39\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udd39\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udd39\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udd39\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udd39\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udd39\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udd39\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udd39\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udd39\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udd39\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udd3c\u200d\u2640\ufe0f",
                                        "\ud83e\udd3c\u200d\u2642\ufe0f",
                                        "\ud83e\udd3d\u200d\u2640\ufe0f",
                                        "\ud83e\udd3d\u200d\u2642\ufe0f",
                                        "\ud83e\udd3d\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udd3d\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udd3d\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udd3d\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udd3d\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udd3d\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udd3d\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udd3d\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udd3d\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udd3d\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udd3e\u200d\u2640\ufe0f",
                                        "\ud83e\udd3e\u200d\u2642\ufe0f",
                                        "\ud83e\udd3e\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udd3e\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udd3e\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udd3e\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udd3e\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udd3e\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udd3e\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udd3e\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udd3e\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udd3e\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddb8\u200d\u2640\ufe0f",
                                        "\ud83e\uddb8\u200d\u2642\ufe0f",
                                        "\ud83e\uddb8\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddb8\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddb8\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddb8\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddb8\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddb8\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddb8\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddb8\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddb8\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddb8\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddb9\u200d\u2640\ufe0f",
                                        "\ud83e\uddb9\u200d\u2642\ufe0f",
                                        "\ud83e\uddb9\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddb9\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddb9\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddb9\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddb9\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddb9\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddb9\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddb9\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddb9\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddb9\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddcd\u200d\u2640\ufe0f",
                                        "\ud83e\uddcd\u200d\u2642\ufe0f",
                                        "\ud83e\uddcd\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddcd\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddcd\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddcd\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddcd\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddcd\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddcd\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddcd\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddcd\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddcd\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddce\u200d\u2640\ufe0f",
                                        "\ud83e\uddce\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\u200d\u2642\ufe0f",
                                        "\ud83e\uddce\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddce\ud83c\udffb\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddce\ud83c\udffb\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddce\ud83c\udffc\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddce\ud83c\udffc\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddce\ud83c\udffd\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddce\ud83c\udffd\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddce\ud83c\udffe\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddce\ud83c\udffe\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddce\ud83c\udfff\u200d\u2640\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddce\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddce\ud83c\udfff\u200d\u2642\ufe0f\u200d\u27a1\ufe0f",
                                        "\ud83e\uddcf\u200d\u2640\ufe0f",
                                        "\ud83e\uddcf\u200d\u2642\ufe0f",
                                        "\ud83e\uddcf\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddcf\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddcf\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddcf\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddcf\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddcf\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddcf\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddcf\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddcf\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddcf\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddd4\u200d\u2640\ufe0f",
                                        "\ud83e\uddd4\u200d\u2642\ufe0f",
                                        "\ud83e\uddd4\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddd4\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddd4\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddd4\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddd4\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddd4\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddd4\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddd4\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddd4\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddd4\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddd6\u200d\u2640\ufe0f",
                                        "\ud83e\uddd6\u200d\u2642\ufe0f",
                                        "\ud83e\uddd6\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddd6\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddd6\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddd6\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddd6\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddd6\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddd6\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddd6\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddd6\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddd6\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddd7\u200d\u2640\ufe0f",
                                        "\ud83e\uddd7\u200d\u2642\ufe0f",
                                        "\ud83e\uddd7\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddd7\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddd7\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddd7\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddd7\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddd7\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddd7\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddd7\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddd7\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddd7\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddd8\u200d\u2640\ufe0f",
                                        "\ud83e\uddd8\u200d\u2642\ufe0f",
                                        "\ud83e\uddd8\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddd8\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddd8\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddd8\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddd8\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddd8\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddd8\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddd8\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddd8\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddd8\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddd9\u200d\u2640\ufe0f",
                                        "\ud83e\uddd9\u200d\u2642\ufe0f",
                                        "\ud83e\uddd9\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddd9\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddd9\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddd9\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddd9\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddd9\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddd9\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddd9\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddd9\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddd9\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddda\u200d\u2640\ufe0f",
                                        "\ud83e\uddda\u200d\u2642\ufe0f",
                                        "\ud83e\uddda\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\uddda\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\uddda\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\uddda\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\uddda\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\uddda\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\uddda\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\uddda\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\uddda\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\uddda\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udddb\u200d\u2640\ufe0f",
                                        "\ud83e\udddb\u200d\u2642\ufe0f",
                                        "\ud83e\udddb\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udddb\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udddb\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udddb\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udddb\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udddb\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udddb\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udddb\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udddb\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udddb\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udddc\u200d\u2640\ufe0f",
                                        "\ud83e\udddc\u200d\u2642\ufe0f",
                                        "\ud83e\udddc\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udddc\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udddc\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udddc\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udddc\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udddc\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udddc\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udddc\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udddc\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udddc\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\udddd\u200d\u2640\ufe0f",
                                        "\ud83e\udddd\u200d\u2642\ufe0f",
                                        "\ud83e\udddd\ud83c\udffb\u200d\u2640\ufe0f",
                                        "\ud83e\udddd\ud83c\udffb\u200d\u2642\ufe0f",
                                        "\ud83e\udddd\ud83c\udffc\u200d\u2640\ufe0f",
                                        "\ud83e\udddd\ud83c\udffc\u200d\u2642\ufe0f",
                                        "\ud83e\udddd\ud83c\udffd\u200d\u2640\ufe0f",
                                        "\ud83e\udddd\ud83c\udffd\u200d\u2642\ufe0f",
                                        "\ud83e\udddd\ud83c\udffe\u200d\u2640\ufe0f",
                                        "\ud83e\udddd\ud83c\udffe\u200d\u2642\ufe0f",
                                        "\ud83e\udddd\ud83c\udfff\u200d\u2640\ufe0f",
                                        "\ud83e\udddd\ud83c\udfff\u200d\u2642\ufe0f",
                                        "\ud83e\uddde\u200d\u2640\ufe0f",
                                        "\ud83e\uddde\u200d\u2642\ufe0f",
                                        "\ud83e\udddf\u200d\u2640\ufe0f",
                                        "\ud83e\udddf\u200d\u2642\ufe0f",
                                        "\ud83d\udc68\u200d\ud83e\uddb0",
                                        "\ud83d\udc68\u200d\ud83e\uddb1",
                                        "\ud83d\udc68\u200d\ud83e\uddb2",
                                        "\ud83d\udc68\u200d\ud83e\uddb3",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddb0",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddb1",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddb2",
                                        "\ud83d\udc68\ud83c\udffb\u200d\ud83e\uddb3",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddb0",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddb1",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddb2",
                                        "\ud83d\udc68\ud83c\udffc\u200d\ud83e\uddb3",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddb0",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddb1",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddb2",
                                        "\ud83d\udc68\ud83c\udffd\u200d\ud83e\uddb3",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddb0",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddb1",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddb2",
                                        "\ud83d\udc68\ud83c\udffe\u200d\ud83e\uddb3",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddb0",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddb1",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddb2",
                                        "\ud83d\udc68\ud83c\udfff\u200d\ud83e\uddb3",
                                        "\ud83d\udc69\u200d\ud83e\uddb0",
                                        "\ud83d\udc69\u200d\ud83e\uddb1",
                                        "\ud83d\udc69\u200d\ud83e\uddb2",
                                        "\ud83d\udc69\u200d\ud83e\uddb3",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddb0",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddb1",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddb2",
                                        "\ud83d\udc69\ud83c\udffb\u200d\ud83e\uddb3",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddb0",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddb1",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddb2",
                                        "\ud83d\udc69\ud83c\udffc\u200d\ud83e\uddb3",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddb0",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddb1",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddb2",
                                        "\ud83d\udc69\ud83c\udffd\u200d\ud83e\uddb3",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddb0",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddb1",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddb2",
                                        "\ud83d\udc69\ud83c\udffe\u200d\ud83e\uddb3",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddb0",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddb1",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddb2",
                                        "\ud83d\udc69\ud83c\udfff\u200d\ud83e\uddb3",
                                        "\ud83e\uddd1\u200d\ud83e\uddb0",
                                        "\ud83e\uddd1\u200d\ud83e\uddb1",
                                        "\ud83e\uddd1\u200d\ud83e\uddb2",
                                        "\ud83e\uddd1\u200d\ud83e\uddb3",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddb0",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddb1",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddb2",
                                        "\ud83e\uddd1\ud83c\udffb\u200d\ud83e\uddb3",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddb0",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddb1",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddb2",
                                        "\ud83e\uddd1\ud83c\udffc\u200d\ud83e\uddb3",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddb0",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddb1",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddb2",
                                        "\ud83e\uddd1\ud83c\udffd\u200d\ud83e\uddb3",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddb0",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddb1",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddb2",
                                        "\ud83e\uddd1\ud83c\udffe\u200d\ud83e\uddb3",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddb0",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddb1",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddb2",
                                        "\ud83e\uddd1\ud83c\udfff\u200d\ud83e\uddb3",
                                        "\u26d3\ufe0f\u200d\ud83d\udca5",
                                        "\u2764\ufe0f\u200d\ud83d\udd25",
                                        "\u2764\ufe0f\u200d\ud83e\ude79",
                                        "\ud83c\udf44\u200d\ud83d\udfeb",
                                        "\ud83c\udf4b\u200d\ud83d\udfe9",
                                        "\ud83c\udff3\ufe0f\u200d\u26a7\ufe0f",
                                        "\ud83c\udff3\ufe0f\u200d\ud83c\udf08",
                                        "\ud83c\udff4\u200d\u2620\ufe0f",
                                        "\ud83d\udc08\u200d\u2b1b",
                                        "\ud83d\udc15\u200d\ud83e\uddba",
                                        "\ud83d\udc26\u200d\u2b1b",
                                        "\ud83d\udc26\u200d\ud83d\udd25",
                                        "\ud83d\udc3b\u200d\u2744\ufe0f",
                                        "\ud83d\udc41\ufe0f\u200d\ud83d\udde8\ufe0f",
                                        "\ud83d\ude2e\u200d\ud83d\udca8",
                                        "\ud83d\ude35\u200d\ud83d\udcab",
                                        "\ud83d\ude36\u200d\ud83c\udf2b\ufe0f",
                                        "\ud83d\ude42\u200d\u2194\ufe0f",
                                        "\ud83d\ude42\u200d\u2195\ufe0f")));
    }

    static UnicodePropertyDataDiff populateUNICODE_15_1_0(UnicodePropertyData parent) {
        EconomicMap<String, CodePointSetDiff> properties = EconomicMap.create(29);
        EconomicMap<String, ClassSetContents> emoji = EconomicMap.create(0);
        properties.put("Alpha", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("Assigned", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("Gr_Base", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("IDC", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00200c, 0x00200d, 0x0030fb, 0x0030fb, 0x00ff65, 0x00ff65, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("IDS", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("IDSB", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ffc, 0x002ffd, 0x0031ef, 0x0031ef), CodePointSet.getEmpty()));
        properties.put("Ideo", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("OIDC", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00200c, 0x00200d, 0x0030fb, 0x0030fb, 0x00ff65, 0x00ff65), CodePointSet.getEmpty()));
        properties.put("STerm", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0017d4, 0x0017d5), CodePointSet.getEmpty()));
        properties.put("UIdeo", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("XIDC", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00200c, 0x00200d, 0x0030fb, 0x0030fb, 0x00ff65, 0x00ff65, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("XIDS", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("blk=CJK_Ext_I", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5f), CodePointSet.getEmpty()));
        properties.put("blk=NB", CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x02ebf0, 0x02ee5f)));
        properties.put("gc=C", CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef, 0x02ebf0, 0x02ee5d)));
        properties.put("gc=Cn", CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef, 0x02ebf0, 0x02ee5d)));
        properties.put("gc=L", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("gc=Lo", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("gc=S", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef), CodePointSet.getEmpty()));
        properties.put("gc=So", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef), CodePointSet.getEmpty()));
        properties.put("sc=Hani", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("sc=Zyyy", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef), CodePointSet.getEmpty()));
        properties.put("sc=Zzzz", CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef, 0x02ebf0, 0x02ee5d)));
        properties.put("scx=Hani", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("scx=Mlym", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001cf2, 0x001cf2), CodePointSet.getEmpty()));
        properties.put("scx=Shrd", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00a830, 0x00a835, 0x00a838, 0x00a838), CodePointSet.getEmpty()));
        properties.put("scx=Sinh", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001cf2, 0x001cf2), CodePointSet.getEmpty()));
        properties.put("scx=Zyyy", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef), CodePointSet.getEmpty()));
        properties.put("scx=Zzzz", CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x002ffc, 0x002fff, 0x0031ef, 0x0031ef, 0x02ebf0, 0x02ee5d)));
        EconomicMap<String, String> propAliases = EconomicMap.create(0);
        EconomicMap<String, String> gcAliases = EconomicMap.create(0);
        EconomicMap<String, String> scAliases = EconomicMap.create(0);
        EconomicMap<String, String> blkAliases = EconomicMap.create(2);
        blkAliases.put("CJK_Ext_I", "CJK_Ext_I");
        blkAliases.put("CJK_Unified_Ideographs_Extension_I", "CJK_Ext_I");
        return new UnicodePropertyDataDiff(parent, properties, emoji, propAliases, gcAliases, scAliases, blkAliases);
    }

    static UnicodePropertyDataDiff populateUNICODE_16_0_0(UnicodePropertyData parent) {
        EconomicMap<String, CodePointSetDiff> properties = EconomicMap.create(146);
        EconomicMap<String, ClassSetContents> emoji = EconomicMap.create(2);
        properties.put("Alpha", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x000363, 0x00036f, 0x000897, 0x000897, 0x001c89, 0x001c8a, 0x001dd3, 0x001de6, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d4a,
                                        0x010d65, 0x010d69, 0x010d69, 0x010d6f, 0x010d85, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390,
                                        0x0113b5, 0x0113b7, 0x0113c0, 0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113cd, 0x0113d1, 0x0113d1, 0x0113d3, 0x0113d3, 0x011bc0,
                                        0x011be0, 0x013460, 0x0143fa, 0x016100, 0x01612e, 0x016d40, 0x016d6c, 0x018cff, 0x018cff, 0x01e5d0, 0x01e5ed, 0x01e5f0, 0x01e5f0, 0x02ebf0, 0x02ee5d),
                        CodePointSet.getEmpty()));
        properties.put("Assigned",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x000897, 0x000897, 0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x001c89, 0x001c8a, 0x002427, 0x002429, 0x002ffc, 0x002fff,
                                        0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d40, 0x010d65, 0x010d69, 0x010d85, 0x010d8e, 0x010d8f,
                                        0x010ec2, 0x010ec4, 0x010efc, 0x010efc, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0, 0x0113c2, 0x0113c2,
                                        0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d5, 0x0113d7, 0x0113d8, 0x0113e1, 0x0113e2, 0x0116d0, 0x0116e3, 0x011bc0, 0x011be1, 0x011bf0, 0x011bf9,
                                        0x011f5a, 0x011f5a, 0x013460, 0x0143fa, 0x016100, 0x016139, 0x016d40, 0x016d79, 0x018cff, 0x018cff, 0x01cc00, 0x01ccf9, 0x01cd00, 0x01ceb3, 0x01e5d0, 0x01e5fa,
                                        0x01e5ff, 0x01e5ff, 0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc,
                                        0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("Bidi_M", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00226d, 0x00226d), CodePointSet.getEmpty()));
        properties.put("CI",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x000897, 0x000897, 0x010d4e, 0x010d4e, 0x010d69, 0x010d6d, 0x010d6f, 0x010d6f, 0x010efc, 0x010efc, 0x0113bb, 0x0113c0,
                                        0x0113ce, 0x0113ce, 0x0113d0, 0x0113d0, 0x0113d2, 0x0113d2, 0x0113e1, 0x0113e2, 0x011f5a, 0x011f5a, 0x01611e, 0x016129, 0x01612d, 0x01612f, 0x016d40, 0x016d42,
                                        0x016d6b, 0x016d6c, 0x01e5ee, 0x01e5ef), CodePointSet.createNoDedup(0x01171e, 0x01171e)));
        properties.put("CWCF", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c89, 0x00a7cb, 0x00a7cc, 0x00a7da, 0x00a7da, 0x00a7dc, 0x00a7dc, 0x010d50, 0x010d65),
                        CodePointSet.getEmpty()));
        properties.put("CWCM", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x00019b, 0x00019b, 0x000264, 0x000264, 0x001c89, 0x001c8a, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x010d50, 0x010d65, 0x010d70, 0x010d85),
                        CodePointSet.getEmpty()));
        properties.put("CWKCF", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x001c89, 0x001c89, 0x00a7cb, 0x00a7cc, 0x00a7da, 0x00a7da, 0x00a7dc, 0x00a7dc, 0x010d50, 0x010d65, 0x01ccd6, 0x01ccf9), CodePointSet.getEmpty()));
        properties.put("CWL", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c89, 0x00a7cb, 0x00a7cc, 0x00a7da, 0x00a7da, 0x00a7dc, 0x00a7dc, 0x010d50, 0x010d65),
                        CodePointSet.getEmpty()));
        properties.put("CWT", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x00019b, 0x00019b, 0x000264, 0x000264, 0x001c8a, 0x001c8a, 0x00a7cd, 0x00a7cd, 0x00a7db, 0x00a7db, 0x010d70, 0x010d85), CodePointSet.getEmpty()));
        properties.put("CWU", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x00019b, 0x00019b, 0x000264, 0x000264, 0x001c8a, 0x001c8a, 0x00a7cd, 0x00a7cd, 0x00a7db, 0x00a7db, 0x010d70, 0x010d85), CodePointSet.getEmpty()));
        properties.put("Cased", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c8a, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x010d50, 0x010d65, 0x010d70, 0x010d85),
                        CodePointSet.getEmpty()));
        properties.put("Dash", CodePointSetDiff.create(CodePointSet.createNoDedup(0x010d6e, 0x010d6e), CodePointSet.getEmpty()));
        properties.put("Dia",
                        CodePointSetDiff.create(
                                        CodePointSet.createNoDedup(0x000e3a, 0x000e3a, 0x001734, 0x001734, 0x001a60, 0x001a60, 0x001be6, 0x001be6, 0x001bf2, 0x001bf3, 0x00a806, 0x00a806, 0x00a82c,
                                                        0x00a82c, 0x010a38, 0x010a3a, 0x010a3f, 0x010a3f, 0x010d4e, 0x010d4e, 0x010d69, 0x010d6d, 0x01133b, 0x01133b, 0x0113ce, 0x0113d0, 0x0113d2,
                                                        0x0113d3, 0x0113e1, 0x0113e2, 0x011f41, 0x011f42, 0x011f5a, 0x011f5a, 0x01612f, 0x01612f, 0x016d6b, 0x016d6c, 0x01e5ee, 0x01e5ef),
                                        CodePointSet.getEmpty()));
        properties.put("EPres", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9),
                        CodePointSet.getEmpty()));
        properties.put("Emoji", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9),
                        CodePointSet.getEmpty()));
        properties.put("Ext", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000a71, 0x000a71, 0x000afb, 0x000afb, 0x010d4e, 0x010d4e, 0x010d6a, 0x010d6a, 0x010d6f, 0x010d6f, 0x011237, 0x011237,
                        0x0113d2, 0x0113d3, 0x01e5ef, 0x01e5ef), CodePointSet.getEmpty()));
        properties.put("Gr_Base", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x001c89, 0x001c8a, 0x002427, 0x002429, 0x002ffc, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x00a7cb,
                                        0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d40, 0x010d65, 0x010d6e, 0x010d85, 0x010d8e, 0x010d8f, 0x010ec2, 0x010ec4, 0x011380, 0x011389, 0x01138b,
                                        0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113b7, 0x0113b9, 0x0113ba, 0x0113ca, 0x0113ca, 0x0113cc, 0x0113cd, 0x0113d1, 0x0113d1, 0x0113d3,
                                        0x0113d5, 0x0113d7, 0x0113d8, 0x0116d0, 0x0116e3, 0x01171e, 0x01171e, 0x011bc0, 0x011be1, 0x011bf0, 0x011bf9, 0x013460, 0x0143fa, 0x016100, 0x01611d, 0x01612a,
                                        0x01612c, 0x016130, 0x016139, 0x016d40, 0x016d79, 0x018cff, 0x018cff, 0x01cc00, 0x01ccf9, 0x01cd00, 0x01ceb3, 0x01e5d0, 0x01e5ed, 0x01e5f0, 0x01e5fa, 0x01e5ff,
                                        0x01e5ff, 0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc, 0x01fadf,
                                        0x01fadf, 0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef, 0x02ebf0, 0x02ee5d),
                        CodePointSet.createNoDedup(0x000cc0, 0x000cc0, 0x000cc7, 0x000cc8, 0x000cca, 0x000ccb, 0x001715, 0x001715, 0x001734, 0x001734, 0x001b3b, 0x001b3b, 0x001b3d, 0x001b3d, 0x001b43,
                                        0x001b44, 0x001baa, 0x001baa, 0x001bf2, 0x001bf3, 0x00a953, 0x00a953, 0x00a9c0, 0x00a9c0, 0x0111c0, 0x0111c0, 0x011235, 0x011235, 0x01134d, 0x01134d, 0x0116b6,
                                        0x0116b6, 0x01193d, 0x01193d, 0x011f41, 0x011f41, 0x016ff0, 0x016ff1, 0x01d166, 0x01d166, 0x01d16d, 0x01d16d)));
        properties.put("Gr_Ext",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x000897, 0x000897, 0x000cc0, 0x000cc0, 0x000cc7, 0x000cc8, 0x000cca, 0x000ccb, 0x001715, 0x001715, 0x001734, 0x001734,
                                        0x001b3b, 0x001b3b, 0x001b3d, 0x001b3d, 0x001b43, 0x001b44, 0x001baa, 0x001baa, 0x001bf2, 0x001bf3, 0x00a953, 0x00a953, 0x00a9c0, 0x00a9c0, 0x010d69, 0x010d6d,
                                        0x010efc, 0x010efc, 0x0111c0, 0x0111c0, 0x011235, 0x011235, 0x01134d, 0x01134d, 0x0113b8, 0x0113b8, 0x0113bb, 0x0113c0, 0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5,
                                        0x0113c7, 0x0113c9, 0x0113ce, 0x0113d0, 0x0113d2, 0x0113d2, 0x0113e1, 0x0113e2, 0x0116b6, 0x0116b6, 0x01193d, 0x01193d, 0x011f41, 0x011f41, 0x011f5a, 0x011f5a,
                                        0x01611e, 0x016129, 0x01612d, 0x01612f, 0x016ff0, 0x016ff1, 0x01d166, 0x01d166, 0x01d16d, 0x01d16d, 0x01e5ee, 0x01e5ef),
                                        CodePointSet.createNoDedup(0x01171e, 0x01171e)));
        properties.put("IDC",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x000897, 0x000897, 0x001c89, 0x001c8a, 0x00200c, 0x00200d, 0x0030fb, 0x0030fb, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc,
                                        0x00ff65, 0x00ff65, 0x0105c0, 0x0105f3, 0x010d40, 0x010d65, 0x010d69, 0x010d6d, 0x010d6f, 0x010d85, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc, 0x011380, 0x011389,
                                        0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0, 0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d3,
                                        0x0113e1, 0x0113e2, 0x0116d0, 0x0116e3, 0x011bc0, 0x011be0, 0x011bf0, 0x011bf9, 0x011f5a, 0x011f5a, 0x013460, 0x0143fa, 0x016100, 0x016139, 0x016d40, 0x016d6c,
                                        0x016d70, 0x016d79, 0x018cff, 0x018cff, 0x01ccf0, 0x01ccf9, 0x01e5d0, 0x01e5fa, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("IDS",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c8a, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d4a, 0x010d65, 0x010d6f, 0x010d85,
                                        0x010ec2, 0x010ec4, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113b7, 0x0113d1, 0x0113d1, 0x0113d3, 0x0113d3,
                                        0x011bc0, 0x011be0, 0x013460, 0x0143fa, 0x016100, 0x01611d, 0x016d40, 0x016d6c, 0x018cff, 0x018cff, 0x01e5d0, 0x01e5ed, 0x01e5f0, 0x01e5f0, 0x02ebf0, 0x02ee5d),
                                        CodePointSet.getEmpty()));
        properties.put("IDSB", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ffc, 0x002ffd, 0x0031ef, 0x0031ef), CodePointSet.getEmpty()));
        properties.put("Ideo", CodePointSetDiff.create(CodePointSet.createNoDedup(0x018cff, 0x018cff, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("Lower", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c8a, 0x001c8a, 0x00a7cd, 0x00a7cd, 0x00a7db, 0x00a7db, 0x010d70, 0x010d85), CodePointSet.getEmpty()));
        properties.put("Math", CodePointSetDiff.create(CodePointSet.createNoDedup(0x010d8e, 0x010d8f), CodePointSet.getEmpty()));
        properties.put("OAlpha", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000363, 0x00036f, 0x000897, 0x000897, 0x001dd3, 0x001de6, 0x010d69, 0x010d69, 0x010efc, 0x010efc, 0x0113b8,
                        0x0113c0, 0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113cd, 0x01611e, 0x01612e), CodePointSet.getEmpty()));
        properties.put("OIDC", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00200c, 0x00200d, 0x0030fb, 0x0030fb, 0x00ff65, 0x00ff65), CodePointSet.getEmpty()));
        properties.put("STerm", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0017d4, 0x0017d5, 0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x002024, 0x002024, 0x002cf9, 0x002cfb, 0x00fe12,
                        0x00fe12, 0x00fe15, 0x00fe16, 0x0113d4, 0x0113d5, 0x016d6e, 0x016d6f), CodePointSet.getEmpty()));
        properties.put("Term", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x002024, 0x002024, 0x002cf9, 0x002cfb, 0x00fe12, 0x00fe12, 0x00fe15,
                        0x00fe16, 0x0113d4, 0x0113d5, 0x016d6e, 0x016d6f), CodePointSet.createNoDedup(0x000836, 0x000836)));
        properties.put("UIdeo", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("Upper", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c89, 0x00a7cb, 0x00a7cc, 0x00a7da, 0x00a7da, 0x00a7dc, 0x00a7dc, 0x010d50, 0x010d65),
                        CodePointSet.getEmpty()));
        properties.put("XIDC",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x000897, 0x000897, 0x001c89, 0x001c8a, 0x00200c, 0x00200d, 0x0030fb, 0x0030fb, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc,
                                        0x00ff65, 0x00ff65, 0x0105c0, 0x0105f3, 0x010d40, 0x010d65, 0x010d69, 0x010d6d, 0x010d6f, 0x010d85, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc, 0x011380, 0x011389,
                                        0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0, 0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d3,
                                        0x0113e1, 0x0113e2, 0x0116d0, 0x0116e3, 0x011bc0, 0x011be0, 0x011bf0, 0x011bf9, 0x011f5a, 0x011f5a, 0x013460, 0x0143fa, 0x016100, 0x016139, 0x016d40, 0x016d6c,
                                        0x016d70, 0x016d79, 0x018cff, 0x018cff, 0x01ccf0, 0x01ccf9, 0x01e5d0, 0x01e5fa, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("XIDS",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c8a, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d4a, 0x010d65, 0x010d6f, 0x010d85,
                                        0x010ec2, 0x010ec4, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113b7, 0x0113d1, 0x0113d1, 0x0113d3, 0x0113d3,
                                        0x011bc0, 0x011be0, 0x013460, 0x0143fa, 0x016100, 0x01611d, 0x016d40, 0x016d6c, 0x018cff, 0x018cff, 0x01e5d0, 0x01e5ed, 0x01e5f0, 0x01e5f0, 0x02ebf0, 0x02ee5d),
                                        CodePointSet.getEmpty()));
        properties.put("blk=CJK_Ext_I", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5f), CodePointSet.getEmpty()));
        properties.put("blk=Egyptian_Hieroglyphs_Ext_A", CodePointSetDiff.create(CodePointSet.createNoDedup(0x013460, 0x0143ff), CodePointSet.getEmpty()));
        properties.put("blk=Garay", CodePointSetDiff.create(CodePointSet.createNoDedup(0x010d40, 0x010d8f), CodePointSet.getEmpty()));
        properties.put("blk=Gurung_Khema", CodePointSetDiff.create(CodePointSet.createNoDedup(0x016100, 0x01613f), CodePointSet.getEmpty()));
        properties.put("blk=Kirat_Rai", CodePointSetDiff.create(CodePointSet.createNoDedup(0x016d40, 0x016d7f), CodePointSet.getEmpty()));
        properties.put("blk=Myanmar_Ext_C", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0116d0, 0x0116ff), CodePointSet.getEmpty()));
        properties.put("blk=NB", CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x0105c0, 0x0105ff, 0x010d40, 0x010d8f, 0x011380, 0x0113ff, 0x0116d0, 0x0116ff, 0x011bc0,
                        0x011bff, 0x013460, 0x0143ff, 0x016100, 0x01613f, 0x016d40, 0x016d7f, 0x01cc00, 0x01cebf, 0x01e5d0, 0x01e5ff, 0x02ebf0, 0x02ee5f)));
        properties.put("blk=Ol_Onal", CodePointSetDiff.create(CodePointSet.createNoDedup(0x01e5d0, 0x01e5ff), CodePointSet.getEmpty()));
        properties.put("blk=Sunuwar", CodePointSetDiff.create(CodePointSet.createNoDedup(0x011bc0, 0x011bff), CodePointSet.getEmpty()));
        properties.put("blk=Symbols_For_Legacy_Computing_Sup", CodePointSetDiff.create(CodePointSet.createNoDedup(0x01cc00, 0x01cebf), CodePointSet.getEmpty()));
        properties.put("blk=Todhri", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0105c0, 0x0105ff), CodePointSet.getEmpty()));
        properties.put("blk=Tulu_Tigalari", CodePointSetDiff.create(CodePointSet.createNoDedup(0x011380, 0x0113ff), CodePointSet.getEmpty()));
        properties.put("gc=C",
                        CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x000897, 0x000897, 0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x001c89, 0x001c8a, 0x002427, 0x002429,
                                        0x002ffc, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d40, 0x010d65, 0x010d69, 0x010d85,
                                        0x010d8e, 0x010d8f, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0,
                                        0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d5, 0x0113d7, 0x0113d8, 0x0113e1, 0x0113e2, 0x0116d0, 0x0116e3, 0x011bc0, 0x011be1,
                                        0x011bf0, 0x011bf9, 0x011f5a, 0x011f5a, 0x013460, 0x0143fa, 0x016100, 0x016139, 0x016d40, 0x016d79, 0x018cff, 0x018cff, 0x01cc00, 0x01ccf9, 0x01cd00, 0x01ceb3,
                                        0x01e5d0, 0x01e5fa, 0x01e5ff, 0x01e5ff, 0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6,
                                        0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef, 0x02ebf0, 0x02ee5d)));
        properties.put("gc=Cn",
                        CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x000897, 0x000897, 0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x001c89, 0x001c8a, 0x002427, 0x002429,
                                        0x002ffc, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d40, 0x010d65, 0x010d69, 0x010d85,
                                        0x010d8e, 0x010d8f, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0,
                                        0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d5, 0x0113d7, 0x0113d8, 0x0113e1, 0x0113e2, 0x0116d0, 0x0116e3, 0x011bc0, 0x011be1,
                                        0x011bf0, 0x011bf9, 0x011f5a, 0x011f5a, 0x013460, 0x0143fa, 0x016100, 0x016139, 0x016d40, 0x016d79, 0x018cff, 0x018cff, 0x01cc00, 0x01ccf9, 0x01cd00, 0x01ceb3,
                                        0x01e5d0, 0x01e5fa, 0x01e5ff, 0x01e5ff, 0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6,
                                        0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef, 0x02ebf0, 0x02ee5d)));
        properties.put("gc=L",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c8a, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d4a, 0x010d65, 0x010d6f, 0x010d85,
                                        0x010ec2, 0x010ec4, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113b7, 0x0113d1, 0x0113d1, 0x0113d3, 0x0113d3,
                                        0x011bc0, 0x011be0, 0x013460, 0x0143fa, 0x016100, 0x01611d, 0x016d40, 0x016d6c, 0x018cff, 0x018cff, 0x01e5d0, 0x01e5ed, 0x01e5f0, 0x01e5f0, 0x02ebf0, 0x02ee5d),
                                        CodePointSet.getEmpty()));
        properties.put("gc=LC", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c8a, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x010d50, 0x010d65, 0x010d70, 0x010d85),
                        CodePointSet.getEmpty()));
        properties.put("gc=Ll", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c8a, 0x001c8a, 0x00a7cd, 0x00a7cd, 0x00a7db, 0x00a7db, 0x010d70, 0x010d85), CodePointSet.getEmpty()));
        properties.put("gc=Lm", CodePointSetDiff.create(CodePointSet.createNoDedup(0x010d4e, 0x010d4e, 0x010d6f, 0x010d6f, 0x016d40, 0x016d42, 0x016d6b, 0x016d6c), CodePointSet.getEmpty()));
        properties.put("gc=Lo",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x0105c0, 0x0105f3, 0x010d4a, 0x010d4d, 0x010d4f, 0x010d4f, 0x010ec2, 0x010ec4, 0x011380, 0x011389, 0x01138b, 0x01138b,
                                        0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113b7, 0x0113d1, 0x0113d1, 0x0113d3, 0x0113d3, 0x011bc0, 0x011be0, 0x013460, 0x0143fa, 0x016100, 0x01611d,
                                        0x016d43, 0x016d6a, 0x018cff, 0x018cff, 0x01e5d0, 0x01e5ed, 0x01e5f0, 0x01e5f0, 0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("gc=Lu", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c89, 0x00a7cb, 0x00a7cc, 0x00a7da, 0x00a7da, 0x00a7dc, 0x00a7dc, 0x010d50, 0x010d65),
                        CodePointSet.getEmpty()));
        properties.put("gc=M",
                        CodePointSetDiff.create(
                                        CodePointSet.createNoDedup(0x000897, 0x000897, 0x010d69, 0x010d6d, 0x010efc, 0x010efc, 0x0113b8, 0x0113c0, 0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7,
                                                        0x0113ca, 0x0113cc, 0x0113d0, 0x0113d2, 0x0113d2, 0x0113e1, 0x0113e2, 0x011f5a, 0x011f5a, 0x01611e, 0x01612f, 0x01e5ee, 0x01e5ef),
                                        CodePointSet.getEmpty()));
        properties.put("gc=Mc", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0113b8, 0x0113ba, 0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113cd, 0x0113cf,
                        0x0113cf, 0x01171e, 0x01171e, 0x01612a, 0x01612c), CodePointSet.getEmpty()));
        properties.put("gc=Mn",
                        CodePointSetDiff.create(
                                        CodePointSet.createNoDedup(0x000897, 0x000897, 0x010d69, 0x010d6d, 0x010efc, 0x010efc, 0x0113bb, 0x0113c0, 0x0113ce, 0x0113ce, 0x0113d0, 0x0113d0, 0x0113d2,
                                                        0x0113d2, 0x0113e1, 0x0113e2, 0x011f5a, 0x011f5a, 0x01611e, 0x016129, 0x01612d, 0x01612f, 0x01e5ee, 0x01e5ef),
                                        CodePointSet.createNoDedup(0x01171e, 0x01171e)));
        properties.put("gc=N", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x010d40, 0x010d49, 0x0116d0, 0x0116e3, 0x011bf0, 0x011bf9, 0x016130, 0x016139, 0x016d70, 0x016d79, 0x01ccf0, 0x01ccf9, 0x01e5f1, 0x01e5fa),
                        CodePointSet.getEmpty()));
        properties.put("gc=Nd", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x010d40, 0x010d49, 0x0116d0, 0x0116e3, 0x011bf0, 0x011bf9, 0x016130, 0x016139, 0x016d70, 0x016d79, 0x01ccf0, 0x01ccf9, 0x01e5f1, 0x01e5fa),
                        CodePointSet.getEmpty()));
        properties.put("gc=P", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x010d6e, 0x010d6e, 0x0113d4, 0x0113d5, 0x0113d7, 0x0113d8, 0x011be1,
                        0x011be1, 0x016d6d, 0x016d6f, 0x01e5ff, 0x01e5ff), CodePointSet.getEmpty()));
        properties.put("gc=Pd", CodePointSetDiff.create(CodePointSet.createNoDedup(0x010d6e, 0x010d6e), CodePointSet.getEmpty()));
        properties.put("gc=Po", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x0113d4, 0x0113d5, 0x0113d7, 0x0113d8, 0x011be1, 0x011be1, 0x016d6d, 0x016d6f, 0x01e5ff, 0x01e5ff),
                        CodePointSet.getEmpty()));
        properties.put("gc=S",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x002427, 0x002429, 0x002ffc, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x010d8e, 0x010d8f, 0x01cc00, 0x01ccef,
                                        0x01cd00, 0x01ceb3, 0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc,
                                        0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef), CodePointSet.getEmpty()));
        properties.put("gc=Sm", CodePointSetDiff.create(CodePointSet.createNoDedup(0x010d8e, 0x010d8f), CodePointSet.getEmpty()));
        properties.put("gc=So",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x002427, 0x002429, 0x002ffc, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x01cc00, 0x01ccef, 0x01cd00, 0x01ceb3,
                                        0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf,
                                        0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef), CodePointSet.getEmpty()));
        properties.put("sc=Arab", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000897, 0x000897, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc), CodePointSet.getEmpty()));
        properties.put("sc=Bali", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f), CodePointSet.getEmpty()));
        properties.put("sc=Cyrl", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001c89, 0x001c8a), CodePointSet.getEmpty()));
        properties.put("sc=Egyp", CodePointSetDiff.create(CodePointSet.createNoDedup(0x013460, 0x0143fa), CodePointSet.getEmpty()));
        properties.put("sc=Gara", CodePointSetDiff.create(CodePointSet.createNoDedup(0x010d40, 0x010d65, 0x010d69, 0x010d85, 0x010d8e, 0x010d8f), CodePointSet.getEmpty()));
        properties.put("sc=Gukh", CodePointSetDiff.create(CodePointSet.createNoDedup(0x016100, 0x016139), CodePointSet.getEmpty()));
        properties.put("sc=Hani", CodePointSetDiff.create(CodePointSet.createNoDedup(0x02ebf0, 0x02ee5d), CodePointSet.getEmpty()));
        properties.put("sc=Kawi", CodePointSetDiff.create(CodePointSet.createNoDedup(0x011f5a, 0x011f5a), CodePointSet.getEmpty()));
        properties.put("sc=Kits", CodePointSetDiff.create(CodePointSet.createNoDedup(0x018cff, 0x018cff), CodePointSet.getEmpty()));
        properties.put("sc=Krai", CodePointSetDiff.create(CodePointSet.createNoDedup(0x016d40, 0x016d79), CodePointSet.getEmpty()));
        properties.put("sc=Latn", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc), CodePointSet.getEmpty()));
        properties.put("sc=Mymr", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0116d0, 0x0116e3), CodePointSet.getEmpty()));
        properties.put("sc=Onao", CodePointSetDiff.create(CodePointSet.createNoDedup(0x01e5d0, 0x01e5fa, 0x01e5ff, 0x01e5ff), CodePointSet.getEmpty()));
        properties.put("sc=Sunu", CodePointSetDiff.create(CodePointSet.createNoDedup(0x011bc0, 0x011be1, 0x011bf0, 0x011bf9), CodePointSet.getEmpty()));
        properties.put("sc=Todr", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0105c0, 0x0105f3), CodePointSet.getEmpty()));
        properties.put("sc=Tutg", CodePointSetDiff.create(CodePointSet.createNoDedup(0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0, 0x0113c2,
                        0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d5, 0x0113d7, 0x0113d8, 0x0113e1, 0x0113e2), CodePointSet.getEmpty()));
        properties.put("sc=Zyyy",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x002427, 0x002429, 0x002ffc, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x01cc00, 0x01ccf9, 0x01cd00, 0x01ceb3,
                                        0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf,
                                        0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef), CodePointSet.getEmpty()));
        properties.put("sc=Zzzz",
                        CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x000897, 0x000897, 0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x001c89, 0x001c8a, 0x002427, 0x002429,
                                        0x002ffc, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d40, 0x010d65, 0x010d69, 0x010d85,
                                        0x010d8e, 0x010d8f, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0,
                                        0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d5, 0x0113d7, 0x0113d8, 0x0113e1, 0x0113e2, 0x0116d0, 0x0116e3, 0x011bc0, 0x011be1,
                                        0x011bf0, 0x011bf9, 0x011f5a, 0x011f5a, 0x013460, 0x0143fa, 0x016100, 0x016139, 0x016d40, 0x016d79, 0x018cff, 0x018cff, 0x01cc00, 0x01ccf9, 0x01cd00, 0x01ceb3,
                                        0x01e5d0, 0x01e5fa, 0x01e5ff, 0x01e5ff, 0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6,
                                        0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef, 0x02ebf0, 0x02ee5d)));
        properties.put("scx=Adlm", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00204f, 0x00204f, 0x002e41, 0x002e41), CodePointSet.getEmpty()));
        properties.put("scx=Aghb", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000304, 0x000304, 0x000331, 0x000331, 0x00035e, 0x00035e), CodePointSet.getEmpty()));
        properties.put("scx=Arab", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000897, 0x000897, 0x00204f, 0x00204f, 0x002e41, 0x002e41, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc),
                        CodePointSet.getEmpty()));
        properties.put("scx=Armn", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000308, 0x000308), CodePointSet.getEmpty()));
        properties.put("scx=Avst", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x002e30, 0x002e31), CodePointSet.getEmpty()));
        properties.put("scx=Bali", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f), CodePointSet.getEmpty()));
        properties.put("scx=Beng", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0002bc, 0x0002bc), CodePointSet.getEmpty()));
        properties.put("scx=Bopo", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0002c7, 0x0002c7, 0x0002c9, 0x0002cb, 0x0002d9, 0x0002d9), CodePointSet.getEmpty()));
        properties.put("scx=Cari", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x00205a, 0x00205a, 0x00205d, 0x00205d, 0x002e31, 0x002e31), CodePointSet.getEmpty()));
        properties.put("scx=Cher", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000300, 0x000302, 0x000304, 0x000304, 0x00030b, 0x00030c, 0x000323, 0x000324, 0x000330, 0x000331),
                        CodePointSet.getEmpty()));
        properties.put("scx=Copt", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000300, 0x000300, 0x000304, 0x000305, 0x000307, 0x000307, 0x000374, 0x000375, 0x002e17, 0x002e17), CodePointSet.getEmpty()));
        properties.put("scx=Cyrl", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0002bc, 0x0002bc, 0x000300, 0x000302, 0x000304, 0x000304, 0x000306, 0x000306, 0x000308, 0x000308, 0x00030b,
                        0x00030b, 0x000311, 0x000311, 0x001c89, 0x001c8a), CodePointSet.getEmpty()));
        properties.put("scx=Deva", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0002bc, 0x0002bc), CodePointSet.getEmpty()));
        properties.put("scx=Dupl", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000307, 0x000308, 0x00030a, 0x00030a, 0x000323, 0x000324, 0x002e3c, 0x002e3c),
                        CodePointSet.getEmpty()));
        properties.put("scx=Egyp", CodePointSetDiff.create(CodePointSet.createNoDedup(0x013460, 0x0143fa), CodePointSet.getEmpty()));
        properties.put("scx=Elba", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000305, 0x000305), CodePointSet.getEmpty()));
        properties.put("scx=Ethi", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00030e, 0x00030e), CodePointSet.getEmpty()));
        properties.put("scx=Gara", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x00060c, 0x00060c, 0x00061b, 0x00061b, 0x00061f, 0x00061f, 0x010d40, 0x010d65, 0x010d69, 0x010d85, 0x010d8e, 0x010d8f), CodePointSet.getEmpty()));
        properties.put("scx=Geor", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000589, 0x000589, 0x00205a, 0x00205a, 0x002e31, 0x002e31), CodePointSet.getEmpty()));
        properties.put("scx=Glag", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000303, 0x000303, 0x000305, 0x000305, 0x000589, 0x000589, 0x0010fb, 0x0010fb, 0x00205a, 0x00205a), CodePointSet.getEmpty()));
        properties.put("scx=Gong", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7), CodePointSet.getEmpty()));
        properties.put("scx=Goth", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000304, 0x000305, 0x000308, 0x000308, 0x000331, 0x000331), CodePointSet.getEmpty()));
        properties.put("scx=Grek", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000300, 0x000301, 0x000304, 0x000304, 0x000306, 0x000306, 0x000308, 0x000308, 0x000313,
                        0x000313, 0x000374, 0x000374, 0x00205d, 0x00205d), CodePointSet.getEmpty()));
        properties.put("scx=Gukh", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000965, 0x000965, 0x016100, 0x016139), CodePointSet.getEmpty()));
        properties.put("scx=Hani", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x002ff0, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x02ebf0, 0x02ee5d),
                        CodePointSet.getEmpty()));
        properties.put("scx=Hebr", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000307, 0x000308), CodePointSet.getEmpty()));
        properties.put("scx=Hung", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00205a, 0x00205a, 0x00205d, 0x00205d, 0x002e31, 0x002e31, 0x002e41, 0x002e41), CodePointSet.getEmpty()));
        properties.put("scx=Kana", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000305, 0x000305, 0x000323, 0x000323), CodePointSet.getEmpty()));
        properties.put("scx=Kawi", CodePointSetDiff.create(CodePointSet.createNoDedup(0x011f5a, 0x011f5a), CodePointSet.getEmpty()));
        properties.put("scx=Kits", CodePointSetDiff.create(CodePointSet.createNoDedup(0x018cff, 0x018cff), CodePointSet.getEmpty()));
        properties.put("scx=Knda", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001cd3, 0x001cd3), CodePointSet.getEmpty()));
        properties.put("scx=Krai", CodePointSetDiff.create(CodePointSet.createNoDedup(0x016d40, 0x016d79), CodePointSet.getEmpty()));
        properties.put("scx=Kthi", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002e31, 0x002e31), CodePointSet.getEmpty()));
        properties.put("scx=Latn",
                        CodePointSetDiff.create(
                                        CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x0002bc, 0x0002bc, 0x0002c7, 0x0002c7, 0x0002c9, 0x0002cb, 0x0002cd, 0x0002cd, 0x0002d7, 0x0002d7, 0x0002d9,
                                                        0x0002d9, 0x000300, 0x00030e, 0x000310, 0x000311, 0x000313, 0x000313, 0x000320, 0x000320, 0x000323, 0x000325, 0x00032d, 0x00032e, 0x000330,
                                                        0x000331, 0x000358, 0x000358, 0x00035e, 0x00035e, 0x001df8, 0x001df8, 0x002e17, 0x002e17, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc),
                                        CodePointSet.getEmpty()));
        properties.put("scx=Lisu", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0002bc, 0x0002bc, 0x0002cd, 0x0002cd, 0x00300a, 0x00300b), CodePointSet.getEmpty()));
        properties.put("scx=Lyci", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00205a, 0x00205a), CodePointSet.getEmpty()));
        properties.put("scx=Lydi", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x002e31, 0x002e31), CodePointSet.getEmpty()));
        properties.put("scx=Mahj", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7), CodePointSet.getEmpty()));
        properties.put("scx=Mero", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00205d, 0x00205d), CodePointSet.getEmpty()));
        properties.put("scx=Mlym", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001cf2, 0x001cf2), CodePointSet.getEmpty()));
        properties.put("scx=Mong", CodePointSetDiff.create(CodePointSet.createNoDedup(0x003001, 0x003002, 0x003008, 0x00300b), CodePointSet.getEmpty()));
        properties.put("scx=Mymr", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0116d0, 0x0116e3), CodePointSet.getEmpty()));
        properties.put("scx=Onao", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000964, 0x000965, 0x01e5d0, 0x01e5fa, 0x01e5ff, 0x01e5ff), CodePointSet.getEmpty()));
        properties.put("scx=Orkh", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00205a, 0x00205a, 0x002e30, 0x002e30), CodePointSet.getEmpty()));
        properties.put("scx=Osge", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000301, 0x000301, 0x000304, 0x000304, 0x00030b, 0x00030b, 0x000358, 0x000358), CodePointSet.getEmpty()));
        properties.put("scx=Perm", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x000300, 0x000300, 0x000306, 0x000308, 0x000313, 0x000313), CodePointSet.getEmpty()));
        properties.put("scx=Phag", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00202f, 0x00202f, 0x003002, 0x003002), CodePointSet.getEmpty()));
        properties.put("scx=Runr", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0016eb, 0x0016ed), CodePointSet.getEmpty()));
        properties.put("scx=Samr", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002e31, 0x002e31), CodePointSet.getEmpty()));
        properties.put("scx=Shaw", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0000b7, 0x0000b7), CodePointSet.getEmpty()));
        properties.put("scx=Shrd", CodePointSetDiff.create(CodePointSet.createNoDedup(0x00a830, 0x00a835, 0x00a838, 0x00a838), CodePointSet.getEmpty()));
        properties.put("scx=Sinh", CodePointSetDiff.create(CodePointSet.createNoDedup(0x001cf2, 0x001cf2), CodePointSet.getEmpty()));
        properties.put("scx=Sunu", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000300, 0x000301, 0x000303, 0x000303, 0x00030d, 0x00030d, 0x000310, 0x000310, 0x00032d, 0x00032d, 0x000331,
                        0x000331, 0x011bc0, 0x011be1, 0x011bf0, 0x011bf9), CodePointSet.getEmpty()));
        properties.put("scx=Syrc", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x000303, 0x000304, 0x000307, 0x000308, 0x00030a, 0x00030a, 0x000320, 0x000320, 0x000323, 0x000325, 0x00032d, 0x00032e, 0x000330, 0x000330),
                        CodePointSet.getEmpty()));
        properties.put("scx=Tale", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000300, 0x000301, 0x000307, 0x000308, 0x00030c, 0x00030c), CodePointSet.getEmpty()));
        properties.put("scx=Tang", CodePointSetDiff.create(CodePointSet.createNoDedup(0x002ff0, 0x002fff, 0x0031ef, 0x0031ef), CodePointSet.getEmpty()));
        properties.put("scx=Tfng", CodePointSetDiff.create(CodePointSet.createNoDedup(0x000302, 0x000302, 0x000304, 0x000304, 0x000307, 0x000307, 0x000309, 0x000309), CodePointSet.getEmpty()));
        properties.put("scx=Thai", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0002bc, 0x0002bc, 0x0002d7, 0x0002d7, 0x000303, 0x000303, 0x000331, 0x000331), CodePointSet.getEmpty()));
        properties.put("scx=Tibt", CodePointSetDiff.create(CodePointSet.createNoDedup(0x003008, 0x00300b), CodePointSet.getEmpty()));
        properties.put("scx=Todr", CodePointSetDiff.create(
                        CodePointSet.createNoDedup(0x000301, 0x000301, 0x000304, 0x000304, 0x000307, 0x000307, 0x000311, 0x000311, 0x000313, 0x000313, 0x00035e, 0x00035e, 0x0105c0, 0x0105f3),
                        CodePointSet.getEmpty()));
        properties.put("scx=Toto", CodePointSetDiff.create(CodePointSet.createNoDedup(0x0002bc, 0x0002bc), CodePointSet.getEmpty()));
        properties.put("scx=Tutg",
                        CodePointSetDiff.create(CodePointSet.createNoDedup(0x000ce6, 0x000cef, 0x001cf2, 0x001cf2, 0x001cf4, 0x001cf4, 0x00a830, 0x00a835, 0x00a8f1, 0x00a8f1, 0x011380, 0x011389,
                                        0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0, 0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d5,
                                        0x0113d7, 0x0113d8, 0x0113e1, 0x0113e2), CodePointSet.getEmpty()));
        properties.put("scx=Zinh", CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x000300, 0x00030e, 0x000310, 0x000311, 0x000313, 0x000313, 0x000320, 0x000320, 0x000323,
                        0x000325, 0x00032d, 0x00032e, 0x000330, 0x000331, 0x000358, 0x000358, 0x00035e, 0x00035e)));
        properties.put("scx=Zyyy",
                        CodePointSetDiff.create(
                                        CodePointSet.createNoDedup(0x002427, 0x002429, 0x01cc00, 0x01ccf9, 0x01cd00, 0x01ceb3, 0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f,
                                                        0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef),
                                        CodePointSet.createNoDedup(0x0000b7, 0x0000b7, 0x0002bc, 0x0002bc, 0x0002c7, 0x0002c7, 0x0002c9, 0x0002cb, 0x0002cd, 0x0002cd, 0x0002d7, 0x0002d7, 0x0002d9,
                                                        0x0002d9, 0x000374, 0x000374, 0x0016eb, 0x0016ed, 0x00204f, 0x00204f, 0x00205a, 0x00205a, 0x00205d, 0x00205d, 0x002e17, 0x002e17, 0x002e30,
                                                        0x002e31, 0x002e3c, 0x002e3c, 0x002e41, 0x002e41, 0x002ff0, 0x002ffb)));
        properties.put("scx=Zzzz",
                        CodePointSetDiff.create(CodePointSet.getEmpty(), CodePointSet.createNoDedup(0x000897, 0x000897, 0x001b4e, 0x001b4f, 0x001b7f, 0x001b7f, 0x001c89, 0x001c8a, 0x002427, 0x002429,
                                        0x002ffc, 0x002fff, 0x0031e4, 0x0031e5, 0x0031ef, 0x0031ef, 0x00a7cb, 0x00a7cd, 0x00a7da, 0x00a7dc, 0x0105c0, 0x0105f3, 0x010d40, 0x010d65, 0x010d69, 0x010d85,
                                        0x010d8e, 0x010d8f, 0x010ec2, 0x010ec4, 0x010efc, 0x010efc, 0x011380, 0x011389, 0x01138b, 0x01138b, 0x01138e, 0x01138e, 0x011390, 0x0113b5, 0x0113b7, 0x0113c0,
                                        0x0113c2, 0x0113c2, 0x0113c5, 0x0113c5, 0x0113c7, 0x0113ca, 0x0113cc, 0x0113d5, 0x0113d7, 0x0113d8, 0x0113e1, 0x0113e2, 0x0116d0, 0x0116e3, 0x011bc0, 0x011be1,
                                        0x011bf0, 0x011bf9, 0x011f5a, 0x011f5a, 0x013460, 0x0143fa, 0x016100, 0x016139, 0x016d40, 0x016d79, 0x018cff, 0x018cff, 0x01cc00, 0x01ccf9, 0x01cd00, 0x01ceb3,
                                        0x01e5d0, 0x01e5fa, 0x01e5ff, 0x01e5ff, 0x01f8b2, 0x01f8bb, 0x01f8c0, 0x01f8c1, 0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6,
                                        0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9, 0x01fbcb, 0x01fbef, 0x02ebf0, 0x02ee5d)));
        EconomicMap<String, String> propAliases = EconomicMap.create(0);
        EconomicMap<String, String> gcAliases = EconomicMap.create(0);
        EconomicMap<String, String> scAliases = EconomicMap.create(14);
        scAliases.put("Gara", "Gara");
        scAliases.put("Garay", "Gara");
        scAliases.put("Gukh", "Gukh");
        scAliases.put("Gurung_Khema", "Gukh");
        scAliases.put("Kirat_Rai", "Krai");
        scAliases.put("Krai", "Krai");
        scAliases.put("Ol_Onal", "Onao");
        scAliases.put("Onao", "Onao");
        scAliases.put("Sunu", "Sunu");
        scAliases.put("Sunuwar", "Sunu");
        scAliases.put("Todhri", "Todr");
        scAliases.put("Todr", "Todr");
        scAliases.put("Tulu_Tigalari", "Tutg");
        scAliases.put("Tutg", "Tutg");
        EconomicMap<String, String> blkAliases = EconomicMap.create(15);
        blkAliases.put("CJK_Ext_I", "CJK_Ext_I");
        blkAliases.put("CJK_Unified_Ideographs_Extension_I", "CJK_Ext_I");
        blkAliases.put("Egyptian_Hieroglyphs_Ext_A", "Egyptian_Hieroglyphs_Ext_A");
        blkAliases.put("Egyptian_Hieroglyphs_Extended_A", "Egyptian_Hieroglyphs_Ext_A");
        blkAliases.put("Garay", "Garay");
        blkAliases.put("Gurung_Khema", "Gurung_Khema");
        blkAliases.put("Kirat_Rai", "Kirat_Rai");
        blkAliases.put("Myanmar_Ext_C", "Myanmar_Ext_C");
        blkAliases.put("Myanmar_Extended_C", "Myanmar_Ext_C");
        blkAliases.put("Ol_Onal", "Ol_Onal");
        blkAliases.put("Sunuwar", "Sunuwar");
        blkAliases.put("Symbols_For_Legacy_Computing_Sup", "Symbols_For_Legacy_Computing_Sup");
        blkAliases.put("Symbols_For_Legacy_Computing_Supplement", "Symbols_For_Legacy_Computing_Sup");
        blkAliases.put("Todhri", "Todhri");
        blkAliases.put("Tulu_Tigalari", "Tulu_Tigalari");
        emoji.put("Basic_Emoji", ClassSetContents.createUnicodePropertyOfStrings(
                        CodePointSet.createNoDedup(0x01fa89, 0x01fa89, 0x01fa8f, 0x01fa8f, 0x01fabe, 0x01fabe, 0x01fac6, 0x01fac6, 0x01fadc, 0x01fadc, 0x01fadf, 0x01fadf, 0x01fae9, 0x01fae9),
                        stringSet()));
        emoji.put("RGI_Emoji_Flag_Sequence", ClassSetContents.createUnicodePropertyOfStrings(
                        CodePointSet.getEmpty(), stringSet(
                                        "\ud83c\udde8\ud83c\uddf6")));
        return new UnicodePropertyDataDiff(parent, properties, emoji, propAliases, gcAliases, scAliases, blkAliases);
    }

    /* GENERATED CODE END - KEEP THIS MARKER FOR AUTOMATIC UPDATES */
}
