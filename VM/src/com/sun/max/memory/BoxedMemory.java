/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.memory;

import com.sun.max.annotate.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.hosted.*;

/**
 * Memory access using wrapped Word types.
 *
 * @author Bernd Mathiske
 */
@HOSTED_ONLY
public final class BoxedMemory {

    static {
        // Ensure the native code is loaded
        Prototype.loadHostedLibrary();
    }

    private BoxedMemory() {
    }

    private static native long nativeAllocate(long size);

    public static Pointer allocate(Size size) {
        final Boxed box = (Boxed) size;
        return BoxedPointer.from(nativeAllocate(box.value()));
    }

    private static native long nativeReallocate(long block, long size);

    public static Pointer reallocate(Pointer block, Size size) {
        final Boxed blockBox = (Boxed) block;
        final Boxed sizeBox = (Boxed) size;
        return BoxedPointer.from(nativeReallocate(blockBox.value(), sizeBox.value()));
    }

    private static native int nativeDeallocate(long pointer);

    public static int deallocate(Address block) {
        final Boxed box = (Boxed) block;
        return nativeDeallocate(box.value());
    }

    private static native void nativeWriteBytes(byte[] fromArray, int startIndex, int numberOfBytes, long toPointer);

    public static void writeBytes(byte[] fromArray, int startIndex, int numberOfBytes, Pointer toPointer) {
        nativeWriteBytes(fromArray, startIndex, numberOfBytes, toPointer.toLong());
    }

}
