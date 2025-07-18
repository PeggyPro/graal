/*
 * Copyright (c) 2012, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.nodes.test;

import java.util.Set;

import org.junit.Test;

import jdk.graal.compiler.core.common.type.ArithmeticOpTable;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.test.GraalTest;
import jdk.graal.compiler.util.EconomicHashSet;
import jdk.vm.ci.meta.JavaConstant;

/**
 * This class tests that integer stamps are created correctly for constants.
 */
public class IntegerStampFoldTest extends GraalTest {

    private static final int VALUE_LIMIT = 24;

    public IntegerStampFoldTest() {
    }

    @Test
    public void test() {
        Set<IntegerStamp> unique = new EconomicHashSet<>();
        for (long a = -VALUE_LIMIT; a <= VALUE_LIMIT; a++) {
            IntegerStamp constantA32 = IntegerStamp.create(32, a, a);
            IntegerStamp constantA64 = IntegerStamp.create(64, a, a);

            for (long b = a + 1; b <= VALUE_LIMIT; b++) {
                IntegerStamp constantB32 = IntegerStamp.create(32, b, b);
                IntegerStamp constantB64 = IntegerStamp.create(64, b, b);
                IntegerStamp stamp32 = (IntegerStamp) constantA32.meet(constantB32);
                IntegerStamp stamp64 = (IntegerStamp) constantA64.meet(constantB64);

                for (long c = -VALUE_LIMIT; c <= VALUE_LIMIT; c++) {
                    for (long d = c; d <= VALUE_LIMIT; d++) {
                        IntegerStamp test32 = IntegerStamp.create(32, c, d).join(stamp32);
                        if (unique.add(test32)) {
                            testStamp(test32);
                        }
                        IntegerStamp test64 = IntegerStamp.create(64, c, d).join(stamp64);
                        if (unique.add(test64)) {
                            testStamp(test64);
                        }
                    }
                }
            }
        }
    }

    private static void testStamp(IntegerStamp stamp) {
        if (stamp.isEmpty()) {
            return;
        }
        for (ArithmeticOpTable.BinaryOp<?> op : IntegerStamp.OPS.getBinaryOps()) {
            if (op != null) {
                testFoldStamp(op, stamp);
            }
        }

        for (ArithmeticOpTable.UnaryOp<?> op : IntegerStamp.OPS.getUnaryOps()) {
            if (op != null) {
                testFoldStamp(op, stamp);
            }
        }

        for (ArithmeticOpTable.ShiftOp<?> op : IntegerStamp.OPS.getShiftOps()) {
            if (op != null) {
                testFoldStamp(op, stamp);
            }
        }
    }

    private static void testFoldStamp(ArithmeticOpTable.BinaryOp<?> op, IntegerStamp stamp) {
        for (long a = -VALUE_LIMIT; a <= VALUE_LIMIT; a++) {
            IntegerStamp constant = IntegerStamp.create(stamp.getBits(), a, a);
            IntegerStamp foldedStamp = (IntegerStamp) op.foldStamp(stamp, constant);
            assertTrue(!foldedStamp.isEmpty(), "%s %s %s is empty", stamp, op, constant);

            if (a == 0 && (op.equals(IntegerStamp.OPS.getDiv()) || op.equals(IntegerStamp.OPS.getRem()))) {
                // Can't constant fold div/rem by 0.
                continue;
            }
            /*
             * The folded stamp must contain the result of op(x, a) for all x in the original stamp.
             * Don't test all x, but at least the stamp's lower and upper bounds.
             */
            JavaConstant aConstant = JavaConstant.forPrimitiveInt(stamp.getBits(), a);
            JavaConstant lower = JavaConstant.forPrimitiveInt(stamp.getBits(), stamp.lowerBound());
            JavaConstant lowerFolded = (JavaConstant) op.foldConstant(lower, aConstant);
            assertTrue(foldedStamp.contains(lowerFolded.asLong()), "%s %s %s = %s, should be contained in %s", lower, op, aConstant, lowerFolded, foldedStamp);
            JavaConstant upper = JavaConstant.forPrimitiveInt(stamp.getBits(), stamp.upperBound());
            JavaConstant upperFolded = (JavaConstant) op.foldConstant(upper, aConstant);
            assertTrue(foldedStamp.contains(upperFolded.asLong()), "%s %s %s = %s, should be contained in %s", upper, op, aConstant, upperFolded, foldedStamp);
        }
    }

    private static void testFoldStamp(ArithmeticOpTable.UnaryOp<?> op, IntegerStamp stamp) {
        IntegerStamp foldedStamp = (IntegerStamp) op.foldStamp(stamp);
        assertTrue(!foldedStamp.isEmpty(), "%s %s is empty", op, stamp);

        JavaConstant lower = JavaConstant.forPrimitiveInt(stamp.getBits(), stamp.lowerBound());
        JavaConstant lowerFolded = (JavaConstant) op.foldConstant(lower);
        assertTrue(foldedStamp.contains(lowerFolded.asLong()), "%s %s = %s, should be contained in %s", op, lower, lowerFolded, foldedStamp);
        JavaConstant upper = JavaConstant.forPrimitiveInt(stamp.getBits(), stamp.upperBound());
        JavaConstant upperFolded = (JavaConstant) op.foldConstant(upper);
        assertTrue(foldedStamp.contains(upperFolded.asLong()), "%s %s = %s, should be contained in %s", op, upper, upperFolded, foldedStamp);
    }

    private static void testFoldStamp(ArithmeticOpTable.ShiftOp<?> op, IntegerStamp stamp) {
        for (long a = -VALUE_LIMIT; a <= VALUE_LIMIT; a++) {
            IntegerStamp constant = IntegerStamp.create(32, a, a);
            IntegerStamp foldedStamp = (IntegerStamp) op.foldStamp(stamp, constant);
            assertTrue(!foldedStamp.isEmpty(), "%s %s is empty", op, stamp);

            JavaConstant lower = JavaConstant.forPrimitiveInt(stamp.getBits(), stamp.lowerBound());
            JavaConstant lowerFolded = (JavaConstant) op.foldConstant(lower, JavaConstant.forInt((int) a));
            assertTrue(foldedStamp.contains(lowerFolded.asLong()), "%s %s %s = %s, should be contained in %s", lower, op, a, lowerFolded, foldedStamp);
            JavaConstant upper = JavaConstant.forPrimitiveInt(stamp.getBits(), stamp.upperBound());
            JavaConstant upperFolded = (JavaConstant) op.foldConstant(upper, JavaConstant.forInt((int) a));
            assertTrue(foldedStamp.contains(upperFolded.asLong()), "%s %s %s = %s, should be contained in %s", upper, op, a, upperFolded, foldedStamp);
        }
    }
}
