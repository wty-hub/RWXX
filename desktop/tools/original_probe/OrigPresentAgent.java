package io.github.rwx.probe;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Instruments {@code org.lwjgl.opengl.Display.update()} so every presented frame of the original desktop
 * build is timestamped, mirroring RWX's accepted-present trace.
 *
 * Usage: {@code -javaagent:orig-probe.jar -Drwx.orig.trace=<csv>}
 */
public final class OrigPresentAgent {
    private static final String TARGET_CLASS = "org/lwjgl/opengl/Display";
    private static final String ROOT_CLASS = "com/corrodinggames/librocket/scripts/Root";
    private static final String ENGINE_CLASS = "com/corrodinggames/rts/gameFramework/l";
    private static final String ENGINE_RUNNABLE_CLASS = "com/corrodinggames/rts/gameFramework/l$2";
    private static final String SLICK_CLASS = "com/corrodinggames/rts/java/i";
    private static final String TARGET_METHOD = "update";
    private static final String PROBE_CLASS = "io/github/rwx/probe/OrigPresentProbe";

    private OrigPresentAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        OrigPresentProbe.instrumentation = instrumentation;
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (!TARGET_CLASS.equals(className)) {
                    if ("com/corrodinggames/rts/java/u".equals(className) && OrigNormalInputProbe.ENABLED) {
                        ClassReader inputReader = new ClassReader(classfileBuffer);
                        ClassWriter inputWriter = new ClassWriter(inputReader, ClassWriter.COMPUTE_MAXS);
                        inputReader.accept(new ClassVisitor(Opcodes.ASM9, inputWriter) {
                            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                    String signature, String[] exceptions) {
                                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                                if (!"init".equals(name) && !"update".equals(name) && !"render".equals(name)) return next;
                                return new AdviceAdapter(Opcodes.ASM9, next, access, name, descriptor) {
                                    @Override protected void onMethodEnter() {
                                        loadThis(); visitMethodInsn(INVOKESTATIC, "io/github/rwx/probe/OrigNormalInputProbe",
                                            "capture", "(Ljava/lang/Object;)V", false);
                                    }
                                    @Override protected void onMethodExit(int opcode) {
                                        if (opcode == RETURN && "render".equals(name))
                                            visitMethodInsn(INVOKESTATIC, "io/github/rwx/probe/OrigNormalInputProbe",
                                                "rendered", "()V", false);
                                    }
                                };
                            }
                        }, ClassReader.EXPAND_FRAMES);
                        return inputWriter.toByteArray();
                    }
                    if (SLICK_CLASS.equals(className) || "com/corrodinggames/rts/java/i$2".equals(className)) {
                        // The box the map script raises on this replay is shown by the desktop UI layer, which
                        // logs it as "slick messageBox:..."/"slick queuing-messageBox:...". That class has two
                        // private String handlers (e/f); both are emptied here, which is the input-free
                        // equivalent of the player pressing OK. The engine's own showMessageBox is stubbed too.
                        ClassReader slickReader = new ClassReader(classfileBuffer);
                        ClassWriter slickWriter = new ClassWriter(slickReader, ClassWriter.COMPUTE_FRAMES) {
                            @Override
                            protected String getCommonSuperClass(String type1, String type2) {
                                return "java/lang/Object";
                            }
                        };
                        slickReader.accept(new ClassVisitor(Opcodes.ASM9, slickWriter) {
                            @Override
                            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                             String signature, String[] exceptions) {
                                // Located deterministically by dumping this class and following the string
                                // constants it logs: `java.i.a(String, String)` raises the box
                                // ("slick queuing-messageBox:") and `java.i$2.run()` shows it
                                // ("slick messageBox:"). Both are emptied, which is the input-free equivalent of
                                // the player pressing OK. Constructors are never touched.
                                boolean handler = (SLICK_CLASS.equals(className) && "a".equals(name)
                                        && "(Ljava/lang/String;Ljava/lang/String;)V".equals(descriptor))
                                        || ("com/corrodinggames/rts/java/i$2".equals(className)
                                            && "run".equals(name) && "()V".equals(descriptor));
                                MethodVisitor stub = super.visitMethod(access, name, descriptor, signature, exceptions);
                                if (handler) {
                                    stub.visitCode();
                                    stub.visitInsn(Opcodes.RETURN);
                                    stub.visitMaxs(0, 0);
                                    stub.visitEnd();
                                    return null;
                                }
                                return stub;
                            }
                        }, ClassReader.EXPAND_FRAMES);
                        System.err.println("[rwx-probe] desktop message handlers stubbed out");
                        return slickWriter.toByteArray();
                    }
                    if (ENGINE_CLASS.equals(className) || ENGINE_RUNNABLE_CLASS.equals(className)) {
                        // The opening screen of this replay is a map-script message box (logged as
                        // "slick messageBox:更改开局信息..."), not MissionEngine's introText, so suppressing a
                        // field cannot help. Message boxes are pure UI - the simulation and the recorded
                        // commands are unaffected - so this rewrites `showMessageBox(String, String)` into an
                        // empty method, which is the input-free equivalent of pressing its OK button.
                        ClassReader engineReader = new ClassReader(classfileBuffer);
                        ClassWriter engineWriter = new ClassWriter(engineReader, ClassWriter.COMPUTE_FRAMES) {
                            @Override
                            protected String getCommonSuperClass(String type1, String type2) {
                                return "java/lang/Object";
                            }
                        };
                        engineReader.accept(new ClassVisitor(Opcodes.ASM9, engineWriter) {
                            @Override
                            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                             String signature, String[] exceptions) {
                                if (("showMessageBox".equals(name)
                                        && "(Ljava/lang/String;Ljava/lang/String;)V".equals(descriptor))
                                        || ("run".equals(name) && "()V".equals(descriptor))) {
                                    MethodVisitor stub = super.visitMethod(access, name, descriptor, signature, exceptions);
                                    stub.visitCode();
                                    stub.visitInsn(Opcodes.RETURN);
                                    stub.visitMaxs(0, 0);
                                    stub.visitEnd();
                                    return null;
                                }
                                return super.visitMethod(access, name, descriptor, signature, exceptions);
                            }
                        }, ClassReader.EXPAND_FRAMES);
                        System.err.println("[rwx-probe] engine showMessageBox stubbed out");
                        return engineWriter.toByteArray();
                    }
                    if (ROOT_CLASS.equals(className)) {
                        // Capture the libRocket Root instance: it owns the replay panel, so from it the
                        // replay controller can be reached through instance fields (the controller is not
                        // stored in any static field - measured by scanning 980 loaded engine classes).
                        // Constructors are hooked too, because capturing only from instance methods produced
                        // no instance at all (they never ran); a transformer exception would have been
                        // swallowed by the JVM, so this path also prints proof that it was applied.
                        System.err.println("[rwx-probe] transforming Root");
                        try {
                        ClassReader rootReader = new ClassReader(classfileBuffer);
                        // COMPUTE_MAXS, not COMPUTE_FRAMES: frame computation resolves common superclasses
                        // through the agent's own loader, which cannot see the game's classes, so it threw and
                        // the JVM silently discarded the transform (measured: the transform ran and printed,
                        // yet no instance was ever captured). Recomputing only stack depth keeps the original
                        // StackMapTable valid.
                        ClassWriter rootWriter = new ClassWriter(rootReader, ClassWriter.COMPUTE_FRAMES) {
                            @Override
                            protected String getCommonSuperClass(String type1, String type2) {
                                // Frame computation must not load game classes: the agent's loader cannot see
                                // them, that throws, and the JVM turns a throwing transformer into a silent
                                // revert to the original bytes (measured: "LocalVariablesSorter only accepts
                                // expanded frames" was the visible half of this problem).
                                return "java/lang/Object";
                            }
                        };
                        rootReader.accept(new ClassVisitor(Opcodes.ASM9, rootWriter) {
                            @Override
                            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                             String signature, String[] exceptions) {
                                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                                // Native and abstract methods must be skipped: AdviceAdapter throws on them,
                                // and a transformer exception is swallowed by the JVM, which silently reverts
                                // to the original bytes (that is why the hook appeared installed but never ran).
                                if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
                                    return next;
                                }
                                return new AdviceAdapter(Opcodes.ASM9, next, access, name, descriptor) {
                                    @Override
                                    protected void onMethodEnter() {
                                        loadThis();
                                        visitMethodInsn(INVOKESTATIC, PROBE_CLASS, "onRootEnter",
                                            "(Ljava/lang/Object;)V", false);
                                    }
                                };
                            }
                        }, ClassReader.EXPAND_FRAMES);
                        return rootWriter.toByteArray();
                        } catch (Throwable rootFailure) {
                            // The JVM swallows transformer exceptions and silently keeps the original bytes,
                            // which is exactly why the capture hook looked installed but never ran.
                            System.err.println("[rwx-probe] Root transform failed: " + rootFailure);
                            return null;
                        }
                    }
                    return null;
                }
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                        // Both overloads matter: the original uses update() while setting up and
                        // update(boolean) in its steady-state loop, so hooking only update() captured the
                        // first seconds of a run and then stopped.
                        if (!TARGET_METHOD.equals(name)) {
                            return next;
                        }
                        return new AdviceAdapter(Opcodes.ASM9, next, access, name, descriptor) {
                            @Override
                            protected void onMethodEnter() {
                                // Tag every present with its overload descriptor so duplicate counting
                                // between update() and update(boolean) can be detected instead of assumed.
                                visitLdcInsn(descriptor);
                                visitMethodInsn(INVOKESTATIC, PROBE_CLASS, "onUpdate",
                                    "(Ljava/lang/String;)V", false);
                            }
                            @Override
                            protected void onMethodExit(int opcode) {
                                if (opcode != RETURN) return;
                                visitLdcInsn(descriptor);
                                visitMethodInsn(INVOKESTATIC, PROBE_CLASS, "onPresent",
                                    "(Ljava/lang/String;)V", false);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        }, true);
    }
}
