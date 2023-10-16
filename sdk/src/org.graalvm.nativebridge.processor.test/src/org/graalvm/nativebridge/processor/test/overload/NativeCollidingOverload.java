/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.nativebridge.processor.test.overload;

import org.graalvm.nativebridge.ByReference;
import org.graalvm.nativebridge.GenerateHotSpotToNativeBridge;
import org.graalvm.nativebridge.NativeIsolate;
import org.graalvm.nativebridge.NativeObject;
import org.graalvm.nativebridge.processor.test.ExpectError;
import org.graalvm.nativebridge.processor.test.Service;
import org.graalvm.nativebridge.processor.test.TestJNIConfig;
import org.graalvm.nativeimage.c.function.CEntryPoint;

import java.time.Duration;

@GenerateHotSpotToNativeBridge(jniConfig = TestJNIConfig.class, include = CEntryPoint.NotIncludedAutomatically.class)
abstract class NativeCollidingOverload extends NativeObject implements CollidingOverload {

    NativeCollidingOverload(NativeIsolate isolate, long handle) {
        super(isolate, handle);
    }

    @Override
    @ExpectError("The method generated for `public abstract void colliding(java.time.Duration[] duration)` conflicts with a generated method for an overloaded method `public abstract void colliding(Duration duration)`. " +
                    "To resolve this, make `public abstract void colliding(java.time.Duration[] duration)` final within the `NativeCollidingOverload` and delegate its functionality to a new abstract method. " +
                    "This new method should have a unique name, the same signature, and be annotated with `@ReceiverMethod`. For more details, please refer to the `ReceiverMethod` JavaDoc.")
    public abstract void colliding(Duration[] duration);

    @Override
    public abstract void byReferenceColliding(@ByReference(HSService.class) Service service);

    @Override
    @ExpectError("The method generated for `public abstract void byReferenceColliding(NonCollidingOverload service)` conflicts with a generated method for an overloaded method `public abstract void byReferenceColliding(Service service)`. " +
                    "To resolve this, make `public abstract void byReferenceColliding(NonCollidingOverload service)` final within the `NativeCollidingOverload` and delegate its functionality to a new abstract method. " +
                    "This new method should have a unique name, the same signature, and be annotated with `@ReceiverMethod`. For more details, please refer to the `ReceiverMethod` JavaDoc.")
    public abstract void byReferenceColliding(@ByReference(HSNonCollidingOverload.class) NonCollidingOverload service);
}