/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.compiler.c1x;

import java.util.*;

import com.sun.c1x.*;
import com.sun.c1x.ci.*;
import com.sun.c1x.ri.*;
import com.sun.c1x.xir.*;
import com.sun.max.asm.*;
import com.sun.max.asm.amd64.*;
import com.sun.max.program.option.*;
import com.sun.max.program.option.OptionSet.*;
import com.sun.max.util.*;
import com.sun.max.vm.*;
import com.sun.max.vm.prototype.JavaPrototype;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.b.c.d.e.amd64.target.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;

/**
 * @author Ben L. Titzer
 */
public class C1XCompilerScheme extends AbstractVMScheme implements RuntimeCompilerScheme {

    private MaxRiRuntime c1xRuntime;
    private C1XCompiler compiler;
    private RiXirGenerator xirGenerator;

    public static final Option<Integer> OptLevel;

    public C1XCompilerScheme(VMConfiguration vmConfiguration) {
        super(vmConfiguration);
    }

    static {
        OptLevel = new Option<Integer>("c1x-optlevel", 0, OptionTypes.INT_TYPE, "Set the overall optimization level of C1X (-1 to use default settings)") {
            @Override
            public void setValue(Integer value) {
                C1XOptions.setOptimizationLevel(value);
            }
        };
    }

    public static void addOptions(OptionSet options) {
        // add all the fields from C1XOptions as options
        options.addFieldOptions(C1XOptions.class, "XX");
        // add a special option "c1x-optlevel" which adjusts the optimization level
        options.addOption(OptLevel, Syntax.REQUIRES_EQUALS);

    }

    @Override
    public void initialize(MaxineVM.Phase phase) {
        if (phase == MaxineVM.Phase.BOOTSTRAPPING) {
            // create the RiRuntime object passed to C1X
            c1xRuntime = MaxRiRuntime.globalRuntime;
            CiTarget c1xTarget = createTarget(c1xRuntime, vmConfiguration());
            xirGenerator = new MaxXirGenerator(vmConfiguration(), c1xTarget);
            compiler = new C1XCompiler(c1xRuntime, c1xTarget, xirGenerator);
        }
        if (phase == MaxineVM.Phase.COMPILING) {
            if (MaxineVM.isHosted()) {
                // can only refer to JavaPrototype while bootstrapping.
                JavaPrototype.javaPrototype().loadPackage("com.sun.c1x", true);
            }
        }
    }

    public static CiTarget createTarget(RiRuntime runtime, VMConfiguration configuration) {
        // create the Target object passed to C1X
        InstructionSet isa = configuration.platform().processorKind.instructionSet;
        CiArchitecture arch = CiArchitecture.findArchitecture(isa.name().toLowerCase());
        TargetABI targetABI = configuration.targetABIsScheme().optimizedJavaABI();

        // get the unallocatable registers
        Set<String> unallocatable = new HashSet<String>();
        RegisterRoleAssignment roles = targetABI.registerRoleAssignment();
        markUnallocatable(unallocatable, roles, VMRegister.Role.SAFEPOINT_LATCH);
        markUnallocatable(unallocatable, roles, VMRegister.Role.CPU_STACK_POINTER);
        markUnallocatable(unallocatable, roles, VMRegister.Role.CPU_FRAME_POINTER);
        markUnallocatable(unallocatable, roles, VMRegister.Role.ABI_SCRATCH);
        markUnallocatable(unallocatable, roles, VMRegister.Role.LITERAL_BASE_POINTER);

        AMD64GeneralRegister64 stackPointer = (AMD64GeneralRegister64) targetABI.stackPointer();
        AMD64GeneralRegister64 scratchPointer = (AMD64GeneralRegister64) targetABI.scratchRegister();
        CiRegister stackRegister = null;
        CiRegister scratchRegister = null;

        CiRegister[] registerReferenceMapTemplate = new CiRegister[AMD64GeneralRegister64.ENUMERATOR.length()];
        // configure the allocatable registers
        List<CiRegister> allocatable = new ArrayList<CiRegister>(arch.registers.length);
        int index = 0;
        for (AMD64GeneralRegister64 reg : AMD64GeneralRegister64.ENUMERATOR) {
            for (CiRegister r : arch.registers) {

                if (r.name.toLowerCase().equals(reg.name().toLowerCase())) {
                    if (!unallocatable.contains(r.name.toLowerCase()) && r != runtime.threadRegister()) {
                        allocatable.add(r);
                        registerReferenceMapTemplate[index] = r;
                        break;
                    }

                    if (reg == stackPointer) {
                        stackRegister = r;
                    }

                    if (reg == scratchPointer) {
                        scratchRegister = r;
                    }
                }

            }
            index++;
        }

        assert stackRegister != null;

        for (AMD64XMMRegister reg : AMD64XMMRegister.ENUMERATOR) {
            for (CiRegister r : arch.registers) {
                if (!unallocatable.contains(r.name.toLowerCase()) && r != runtime.threadRegister() && r.name.toLowerCase().equals(reg.name().toLowerCase())) {
                    allocatable.add(r);
                    break;
                }
            }
        }

        CiRegister[] allocRegs = allocatable.toArray(new CiRegister[allocatable.size()]);

        // TODO (tw): Initialize target differently
        CiTarget target = new CiTarget(arch, stackRegister, scratchRegister, allocRegs, allocRegs, registerReferenceMapTemplate, configuration.platform.pageSize, true);
        target.stackAlignment = targetABI.stackFrameAlignment();
        return target;
    }

    private static void markUnallocatable(Set<String> unallocatable, RegisterRoleAssignment roles, VMRegister.Role register) {
        Symbol intReg = roles.integerRegisterActingAs(register);
        if (intReg != null) {
            unallocatable.add(intReg.name().toLowerCase());
        }
        Symbol floatReg = roles.floatingPointRegisterActingAs(register);
        if (floatReg != null) {
            unallocatable.add(floatReg.name().toLowerCase());
        }
    }

    public final TargetMethod compile(ClassMethodActor classMethodActor) {
        RiMethod method = c1xRuntime.getRiMethod(classMethodActor);
        CiTargetMethod compiledMethod = compiler.compileMethod(method, xirGenerator).targetMethod();
        if (compiledMethod != null) {
            return new C1XTargetMethod(this, classMethodActor, compiledMethod);
        }
        throw FatalError.unexpected("bailout"); // compilation failed
    }

    public boolean walkFrame(StackFrameWalker stackFrameWalker, boolean isTopFrame, TargetMethod targetMethod, TargetMethod lastJavaCallee, StackFrameWalker.Purpose purpose, Object context) {
        return BcdeTargetAMD64Compiler.walkFrameHelper(stackFrameWalker, isTopFrame, targetMethod, lastJavaCallee, purpose, context);
    }
}
