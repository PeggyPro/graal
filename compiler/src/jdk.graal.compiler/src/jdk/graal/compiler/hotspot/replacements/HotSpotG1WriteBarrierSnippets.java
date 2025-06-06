/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_METAACCESS;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF_NO_VZERO;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl.NO_LOCATIONS;

import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.meta.HotSpotRegistersProvider;
import jdk.graal.compiler.hotspot.nodes.HotSpotCompressionNode;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.gc.G1ArrayRangePostWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.G1ArrayRangePreWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.G1PostWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.G1PreWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.G1ReferentFieldReadBarrierNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.ReplacementsUtil;
import jdk.graal.compiler.replacements.SnippetCounter.Group;
import jdk.graal.compiler.replacements.SnippetCounter.Group.Factory;
import jdk.graal.compiler.replacements.SnippetTemplate.AbstractTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.gc.G1WriteBarrierSnippets;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.JavaKind;

public final class HotSpotG1WriteBarrierSnippets extends G1WriteBarrierSnippets {
    public static final HotSpotForeignCallDescriptor G1WBPRECALL = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, KILLED_PRE_WRITE_BARRIER_STUB_LOCATIONS, "write_barrier_pre",
                    void.class, Object.class);
    public static final HotSpotForeignCallDescriptor G1WBPOSTCALL = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, KILLED_POST_WRITE_BARRIER_STUB_LOCATIONS, "write_barrier_post",
                    void.class, Word.class);
    public static final HotSpotForeignCallDescriptor VALIDATE_OBJECT = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS, "validate_object", boolean.class, Word.class,
                    Word.class);

    public static final LocationIdentity CARD_TABLE_BASE_LOCATION = NamedLocationIdentity.mutable("GC-Card-Table-Base");

    private final Register threadRegister;

    public HotSpotG1WriteBarrierSnippets(HotSpotRegistersProvider registers) {
        this.threadRegister = registers.getThreadRegister();
    }

    @Override
    protected Word getThread() {
        return HotSpotReplacementsUtil.registerAsWord(threadRegister);
    }

    @Override
    protected int wordSize() {
        return HotSpotReplacementsUtil.wordSize();
    }

    @Override
    protected long objectArrayIndexScale() {
        return ReplacementsUtil.arrayIndexScale(INJECTED_METAACCESS, JavaKind.Object);
    }

    @Override
    protected int satbQueueMarkingActiveOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueMarkingActiveOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int satbQueueBufferOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueBufferOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int satbQueueIndexOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueIndexOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int cardQueueBufferOffset() {
        return HotSpotReplacementsUtil.g1CardQueueBufferOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int cardQueueIndexOffset() {
        return HotSpotReplacementsUtil.g1CardQueueIndexOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected byte dirtyCardValue() {
        return HotSpotReplacementsUtil.dirtyCardValue(INJECTED_VMCONFIG);
    }

    @Override
    protected byte youngCardValue() {
        return HotSpotReplacementsUtil.g1YoungCardValue(INJECTED_VMCONFIG);
    }

    @Override
    public byte cleanCardValue() {
        return HotSpotReplacementsUtil.cleanCardValue(INJECTED_VMCONFIG);
    }

    @Override
    protected boolean supportsLowLatencyBarriers() {
        return HotSpotReplacementsUtil.supportsG1LowLatencyBarriers(INJECTED_VMCONFIG);
    }

    @Override
    protected Word cardTableBase() {
        // Low-latency barriers rely on thread-local card tables
        if (supportsLowLatencyBarriers()) {
            return getThread().readWord(HotSpotReplacementsUtil.g1CardTableBaseOffset(INJECTED_VMCONFIG), CARD_TABLE_BASE_LOCATION);
        }
        return Word.unsigned(HotSpotReplacementsUtil.cardTableStart(INJECTED_VMCONFIG));
    }

    @Override
    protected UnsignedWord cardTableOffset(Pointer oop) {
        int cardTableShift = HotSpotReplacementsUtil.cardTableShift(INJECTED_VMCONFIG);
        return oop.unsignedShiftRight(cardTableShift);
    }

    @Override
    protected int logOfHeapRegionGrainBytes() {
        return HotSpotReplacementsUtil.logOfHeapRegionGrainBytes(INJECTED_VMCONFIG);
    }

    @Override
    protected ForeignCallDescriptor preWriteBarrierCallDescriptor() {
        return G1WBPRECALL;
    }

    @Override
    protected ForeignCallDescriptor postWriteBarrierCallDescriptor() {
        return G1WBPOSTCALL;
    }

    @Override
    protected boolean verifyOops() {
        return HotSpotReplacementsUtil.verifyOops(INJECTED_VMCONFIG);
    }

    @Override
    protected boolean verifyBarrier() {
        return ReplacementsUtil.REPLACEMENTS_ASSERTIONS_ENABLED || HotSpotReplacementsUtil.verifyBeforeOrAfterGC(INJECTED_VMCONFIG);
    }

    @Override
    protected long gcTotalCollectionsAddress() {
        return HotSpotReplacementsUtil.gcTotalCollectionsAddress(INJECTED_VMCONFIG);
    }

    @Override
    protected ForeignCallDescriptor verifyOopCallDescriptor() {
        return HotSpotForeignCallsProviderImpl.VERIFY_OOP;
    }

    @Override
    protected ForeignCallDescriptor validateObjectCallDescriptor() {
        return VALIDATE_OBJECT;
    }

    @Override
    protected ForeignCallDescriptor printfCallDescriptor() {
        return Log.LOG_PRINTF;
    }

    public static class Templates extends AbstractTemplates {
        private final SnippetInfo g1PreWriteBarrier;
        private final SnippetInfo g1ReferentReadBarrier;
        private final SnippetInfo g1PostWriteBarrier;
        private final SnippetInfo g1ArrayRangePreWriteBarrier;
        private final SnippetInfo g1ArrayRangePostWriteBarrier;

        private final G1WriteBarrierLowerer lowerer;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Group.Factory factory, HotSpotProviders providers, GraalHotSpotVMConfig config) {
            super(options, providers);
            this.lowerer = new HotspotG1WriteBarrierLowerer(config, factory);

            HotSpotG1WriteBarrierSnippets receiver = new HotSpotG1WriteBarrierSnippets(providers.getRegisters());
            g1PreWriteBarrier = snippet(providers,
                            G1WriteBarrierSnippets.class,
                            "g1PreWriteBarrier",
                            null,
                            receiver,
                            SATB_QUEUE_LOG_LOCATION,
                            SATB_QUEUE_MARKING_ACTIVE_LOCATION,
                            SATB_QUEUE_INDEX_LOCATION,
                            SATB_QUEUE_BUFFER_LOCATION);
            g1ReferentReadBarrier = snippet(providers,
                            G1WriteBarrierSnippets.class,
                            "g1ReferentReadBarrier",
                            null,
                            receiver,
                            SATB_QUEUE_LOG_LOCATION,
                            SATB_QUEUE_MARKING_ACTIVE_LOCATION,
                            SATB_QUEUE_INDEX_LOCATION,
                            SATB_QUEUE_BUFFER_LOCATION);

            if (Assertions.assertionsEnabled() && config.verifyBeforeGC) {
                g1PostWriteBarrier = snippet(providers,
                                G1WriteBarrierSnippets.class,
                                "g1PostWriteBarrier",
                                null,
                                receiver,
                                GC_CARD_LOCATION,
                                CARD_TABLE_BASE_LOCATION,
                                CARD_QUEUE_LOG_LOCATION,
                                CARD_QUEUE_INDEX_LOCATION,
                                CARD_QUEUE_BUFFER_LOCATION,
                                getClassComponentTypeLocation(providers.getMetaAccess()));
            } else {
                g1PostWriteBarrier = snippet(providers,
                                G1WriteBarrierSnippets.class,
                                "g1PostWriteBarrier",
                                null,
                                receiver,
                                GC_CARD_LOCATION,
                                CARD_TABLE_BASE_LOCATION,
                                CARD_QUEUE_LOG_LOCATION,
                                CARD_QUEUE_INDEX_LOCATION,
                                CARD_QUEUE_BUFFER_LOCATION);
            }

            g1ArrayRangePreWriteBarrier = snippet(providers,
                            G1WriteBarrierSnippets.class,
                            "g1ArrayRangePreWriteBarrier",
                            null,
                            receiver,
                            SATB_QUEUE_LOG_LOCATION,
                            SATB_QUEUE_MARKING_ACTIVE_LOCATION,
                            SATB_QUEUE_INDEX_LOCATION,
                            SATB_QUEUE_BUFFER_LOCATION);
            g1ArrayRangePostWriteBarrier = snippet(providers,
                            G1WriteBarrierSnippets.class,
                            "g1ArrayRangePostWriteBarrier",
                            null,
                            receiver,
                            GC_CARD_LOCATION,
                            CARD_TABLE_BASE_LOCATION,
                            CARD_QUEUE_LOG_LOCATION,
                            CARD_QUEUE_INDEX_LOCATION,
                            CARD_QUEUE_BUFFER_LOCATION);
        }

        public void lower(G1PreWriteBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, g1PreWriteBarrier, barrier, tool);
        }

        public void lower(G1ReferentFieldReadBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, g1ReferentReadBarrier, barrier, tool);
        }

        public void lower(G1PostWriteBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, g1PostWriteBarrier, barrier, tool);
        }

        public void lower(G1ArrayRangePreWriteBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, g1ArrayRangePreWriteBarrier, barrier, tool);
        }

        public void lower(G1ArrayRangePostWriteBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, g1ArrayRangePostWriteBarrier, barrier, tool);
        }
    }

    static final class HotspotG1WriteBarrierLowerer extends G1WriteBarrierLowerer {
        private final CompressEncoding oopEncoding;

        HotspotG1WriteBarrierLowerer(GraalHotSpotVMConfig config, Factory factory) {
            super(factory);
            oopEncoding = config.useCompressedOops ? config.getOopEncoding() : null;
        }

        @Override
        public ValueNode uncompress(ValueNode expected) {
            assert oopEncoding != null;
            return HotSpotCompressionNode.uncompress(expected.graph(), expected, oopEncoding);
        }
    }
}
