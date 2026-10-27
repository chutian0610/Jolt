package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.tree.Node;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Shared emission infrastructure for the M5 specializers: descriptor constants, the
 * per-component kind classification, the stack-map frame tracker, and the straight-line value
 * emitters (numeric widths, enums) that both parse directions inline. Every emitted branch
 * target carries an explicit frame — ByteBuddy computes none for raw appender bodies.
 */
final class Emit {

    static final String NODE = internal(Node.class);
    static final String NODE_GET_DESC = "(Ljava/lang/String;)Lio/github/chutian0610/jolt/tree/Node;";
    static final String NODE_TYPE_DESC = "()Lio/github/chutian0610/jolt/tree/TypeTag;";
    static final String TAG_INTERNAL = "io/github/chutian0610/jolt/tree/TypeTag";
    static final String TAG_DESC = "Lio/github/chutian0610/jolt/tree/TypeTag;";
    static final String WRITER = internal(JoltWriter.class);
    static final String WRITER_START_DESC = "()Lio/github/chutian0610/jolt/JoltWriter;";
    static final String WRITER_FIELD_NAME_DESC = "(Ljava/lang/String;)Lio/github/chutian0610/jolt/JoltWriter;";
    static final String BIND_INTERNAL = internal(Bind.class);
    static final String WRITE_TO_DESC =
            "(Ljava/lang/Object;Lio/github/chutian0610/jolt/JoltWriter;Ljava/lang/String;)V";
    static final String TO_VALUE_DESC =
            "(Lio/github/chutian0610/jolt/tree/Node;Ljava/lang/reflect/Type;"
                    + "Ljava/lang/String;Lio/github/chutian0610/jolt/bind/BindConfig;)Ljava/lang/Object;";
    static final String BIND_EXCEPTION = internal(JsonBindException.class);
    static final String BIND_EXCEPTION_INIT_DESC = "(Ljava/lang/String;Ljava/lang/String;)V";
    static final String BUILDER = "java/lang/StringBuilder";
    static final String BUILDER_INIT_DESC = "(Ljava/lang/String;)V";
    static final String BUILDER_APPEND_DESC = "(Ljava/lang/String;)Ljava/lang/StringBuilder;";
    static final String BUILDER_APPEND_OBJ_DESC = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
    static final String BUILDER_TOSTRING_DESC = "()Ljava/lang/String;";
    static final String TYPED_BINDER_FIELD_DESC = "Lio/github/chutian0610/jolt/bind/TypedBinder;";
    static final String GENERATED_INTERNAL = internal(GeneratedBinders.class);

    static final String CHILD_FIELD_PREFIX = "jolt$child$";
    static final String TYPE_FIELD_PREFIX = "jolt$type$";

    private Emit() {}

    /** The per-component parse strategy, resolved once at generation time. */
    enum Kind {
        STRING, BOOLEAN, NUMBER, ENUM, CHILD, GENERIC
    }

    static Kind kindOf(Class<?> raw) {
        if (raw == String.class) {
            return Kind.STRING;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return Kind.BOOLEAN;
        }
        if (raw.isEnum()) {
            return Kind.ENUM;
        }
        if (isNumber(raw)) {
            return Kind.NUMBER;
        }
        if (GeneratedBinders.isBindable(raw)) {
            return Kind.CHILD;
        }
        return Kind.GENERIC;
    }

    static boolean isNumber(Class<?> raw) {
        return raw == byte.class || raw == Byte.class || raw == short.class || raw == Short.class
                || raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == float.class || raw == Float.class || raw == double.class || raw == Double.class
                || raw == java.math.BigDecimal.class || raw == java.math.BigInteger.class;
    }

    static Class<?> rawOf(java.lang.reflect.Type generic) {
        if (generic instanceof Class<?> c) {
            return c;
        }
        if (generic instanceof java.lang.reflect.ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        throw new IllegalStateException("unresolved component type " + generic);
    }

    static String internal(Class<?> type) {
        return Type.getType(type).getInternalName();
    }

    /** Appends operand-stack entries to a frame's stack declaration. */
    static Object[] withStack(Object[] prefix, Object... top) {
        Object[] out = new Object[prefix.length + top.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(top, 0, out, prefix.length, top.length);
        return out;
    }

    static void pushIndex(MethodVisitor mv, int i) {
        if (i <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + i);
        } else if (i <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, i);
        } else {
            mv.visitIntInsn(Opcodes.SIPUSH, i);
        }
    }

    static void asDecimal(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asDecimal",
                "()Ljava/math/BigDecimal;", false);
    }

    /** Two concatenated strings: base + "." + last (constant name or runtime key). */
    static void emitPath(MethodVisitor mv, Runnable loadBase, Runnable loadLast) {
        mv.visitTypeInsn(Opcodes.NEW, BUILDER);
        mv.visitInsn(Opcodes.DUP);
        loadBase.run();
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BUILDER, "<init>", BUILDER_INIT_DESC, false);
        mv.visitLdcInsn(".");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_DESC,
                false);
        loadLast.run();
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_DESC,
                false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "toString", BUILDER_TOSTRING_DESC,
                false);
    }

    static void emitValuePath(MethodVisitor mv, String name) {
        emitPath(mv, () -> mv.visitVarInsn(Opcodes.ALOAD, 2),
                () -> mv.visitLdcInsn(name));
    }

    /** new StringBuilder() + appends + toString, left on the stack. */
    static void emitConcat(MethodVisitor mv, Runnable appends) {
        mv.visitTypeInsn(Opcodes.NEW, BUILDER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BUILDER, "<init>", "()V", false);
        appends.run();
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "toString", BUILDER_TOSTRING_DESC,
                false);
    }

    static void appendText(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_DESC,
                false);
    }

    static void appendObject(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_OBJ_DESC,
                false);
    }

    /**
     * NEW JsonBindException; DUP; message; path; INVOKESPECIAL — javac's construction
     * order: both arguments build on the stack above the receiver pair, no extra locals.
     */
    static void emitBindThrow(MethodVisitor mv, Runnable messageEmit, Runnable pathEmit) {
        mv.visitTypeInsn(Opcodes.NEW, BIND_EXCEPTION);
        mv.visitInsn(Opcodes.DUP);
        messageEmit.run();
        pathEmit.run();
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BIND_EXCEPTION, "<init>",
                BIND_EXCEPTION_INIT_DESC, false);
        mv.visitInsn(Opcodes.ATHROW);
    }

    /** The numeric family: per-width read with the exact Bind semantics. With
     * {@code keepPrimitive} the value stays an unboxed primitive on the stack (direct setter
     * invoke for primitive parameters); otherwise it is boxed for the args array. */
    static void emitNumberRead(MethodVisitor mv, FrameTracker slots, Class<?> raw,
                               String name, Object[] stackPrefix, boolean keepPrimitive) {
        switch (raw.getName()) {
            case "java.math.BigDecimal" -> asDecimal(mv);
            case "java.math.BigInteger" -> {
                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label handler = new Label();
                Label boxed = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/ArithmeticException");
                mv.visitLabel(tryStart);
                asDecimal(mv);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        "toBigIntegerExact", "()Ljava/math/BigInteger;", false);
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(Opcodes.GOTO, boxed);
                mv.visitLabel(handler);
                // an exception handler enters with a CLEARED operand stack, exception on top only
                slots.emit(mv, "java/lang/ArithmeticException");
                mv.visitInsn(Opcodes.POP);
                emitBindThrow(mv, () -> mv.visitLdcInsn("number is not a whole number"),
                        () -> emitValuePath(mv, name));
                mv.visitLabel(boxed);
                slots.emit(mv, withStack(stackPrefix, "java/math/BigInteger"));
            }
            case "float", "java.lang.Float" -> {
                asDecimal(mv);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        "floatValue", "()F", false);
                if (!keepPrimitive) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                            "(F)Ljava/lang/Float;", false);
                }
            }
            case "double", "java.lang.Double" -> {
                asDecimal(mv);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        "doubleValue", "()D", false);
                if (!keepPrimitive) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                            "(D)Ljava/lang/Double;", false);
                }
            }
            default -> emitIntegralRead(mv, slots, raw, name, stackPrefix, keepPrimitive);
        }
    }

    /** long/int/short/byte: asLong (ArithmeticException → "…a long"), bounds, narrowing.
     * The whole read works on the operand stack — no long locals, so no long locals in any
     * stack-map frame. */
    private static void emitIntegralRead(MethodVisitor mv, FrameTracker slots, Class<?> raw,
                                         String name, Object[] stackPrefix, boolean keepPrimitive) {
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label uncaught = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/ArithmeticException");
        mv.visitLabel(tryStart);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asLong", "()J", false);
        mv.visitLabel(tryEnd);

        long bound = switch (raw.getName()) {
            case "byte", "java.lang.Byte" -> Byte.MAX_VALUE;
            case "short", "java.lang.Short" -> Short.MAX_VALUE;
            case "int", "java.lang.Integer" -> Integer.MAX_VALUE;
            default -> Long.MAX_VALUE;
        };
        if (bound != Long.MAX_VALUE) {
            Label inRange = new Label();
            Label overFirst = new Label();
            Label overSecond = new Label();
            // stack: [v] — three copies while comparing; each throw target declares the
            // stack ITS edge leaves (first: two longs, second: one)
            mv.visitInsn(Opcodes.DUP2);
            mv.visitInsn(Opcodes.DUP2);
            mv.visitLdcInsn(bound);
            mv.visitInsn(Opcodes.LCMP);
            mv.visitJumpInsn(Opcodes.IFGT, overFirst);
            mv.visitLdcInsn(-bound - 1);
            mv.visitInsn(Opcodes.LCMP);
            mv.visitJumpInsn(Opcodes.IFLT, overSecond);
            mv.visitJumpInsn(Opcodes.GOTO, inRange);
            mv.visitLabel(overFirst);
            // ASM's frame API: one LONG entry per value (it expands to two slots itself)
            slots.emit(mv, withStack(stackPrefix, Opcodes.LONG, Opcodes.LONG));
            emitBindThrow(mv,
                    () -> mv.visitLdcInsn("number does not fit in " + raw.getSimpleName()),
                    () -> emitValuePath(mv, name));
            mv.visitLabel(overSecond);
            slots.emit(mv, withStack(stackPrefix, Opcodes.LONG));
            emitBindThrow(mv,
                    () -> mv.visitLdcInsn("number does not fit in " + raw.getSimpleName()),
                    () -> emitValuePath(mv, name));
            mv.visitLabel(inRange);
            slots.emit(mv, withStack(stackPrefix, Opcodes.LONG));
        }

        String boxed;
        switch (raw.getName()) {
            case "byte", "java.lang.Byte" -> {
                mv.visitInsn(Opcodes.L2I);
                mv.visitInsn(Opcodes.I2B);
                if (keepPrimitive) {
                    boxed = null;
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
                            "(B)Ljava/lang/Byte;", false);
                    boxed = "java/lang/Byte";
                }
            }
            case "short", "java.lang.Short" -> {
                mv.visitInsn(Opcodes.L2I);
                mv.visitInsn(Opcodes.I2S);
                if (keepPrimitive) {
                    boxed = null;
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
                            "(S)Ljava/lang/Short;", false);
                    boxed = "java/lang/Short";
                }
            }
            case "int", "java.lang.Integer" -> {
                mv.visitInsn(Opcodes.L2I);
                if (keepPrimitive) {
                    boxed = null;
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false);
                    boxed = "java/lang/Integer";
                }
            }
            default -> {
                if (keepPrimitive) {
                    boxed = null;
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                            "(J)Ljava/lang/Long;", false);
                    boxed = "java/lang/Long";
                }
            }
        }
        Object stackTop = keepPrimitive
                ? (raw == long.class || raw == Long.class
                        ? Opcodes.LONG
                        : (raw == float.class || raw == Float.class
                                ? Opcodes.FLOAT
                                : (raw == double.class || raw == Double.class
                                        ? Opcodes.DOUBLE
                                        : Opcodes.INTEGER)))
                : boxed;
        mv.visitJumpInsn(Opcodes.GOTO, uncaught);
        mv.visitLabel(handler);
        slots.emit(mv, "java/lang/ArithmeticException");
        mv.visitInsn(Opcodes.POP);
        emitBindThrow(mv, () -> mv.visitLdcInsn("number does not fit in a long"),
                () -> emitValuePath(mv, name));
        mv.visitLabel(uncaught);
        slots.emit(mv, withStack(stackPrefix, stackTop));
    }

    /** Strict tag + Enum.valueOf with the unknown-name handler (text kept in slot 6). */
    static void emitEnumRead(MethodVisitor mv, FrameTracker slots, Class<?> raw,
                             String name, Object[] stackPrefix) {
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asText", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 6);
        slots.set(6, "java/lang/String");
        // the exception enters the handler BEFORE the value slot is written — its frame
        // must declare the pre-assignment shape
        Object[] handlerShape = slots.snapshot();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label boxed = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/IllegalArgumentException");
        mv.visitLabel(tryStart);
        mv.visitLdcInsn(Type.getType(raw));
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, internal(raw));
        mv.visitLabel(tryEnd);
        mv.visitVarInsn(Opcodes.ASTORE, 8);
        slots.set(8, internal(raw));
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitJumpInsn(Opcodes.GOTO, boxed);
        mv.visitLabel(handler);
        slots.emitFrom(mv, handlerShape, "java/lang/IllegalArgumentException");
        mv.visitInsn(Opcodes.POP);
        emitBindThrow(mv,
                () -> emitConcat(mv, () -> {
                    mv.visitLdcInsn("unknown enum name '");
                    appendText(mv);
                    mv.visitVarInsn(Opcodes.ALOAD, 6);
                    appendText(mv);
                    mv.visitLdcInsn("' for " + raw.getSimpleName());
                    appendText(mv);
                }),
                () -> emitValuePath(mv, name));
        mv.visitLabel(boxed);
        slots.emit(mv, withStack(stackPrefix, internal(raw)));
    }

    /** Per-slot stack-map frame bookkeeping for the straight-line parse bodies. */
    static final class FrameTracker {

        private final Object[] slots;

        FrameTracker(String self, int size) {
            slots = new Object[size];
            slots[0] = self;
            // ASM's frame API requires explicit TOP markers for undefined slots (null breaks it)
            java.util.Arrays.fill(slots, 1, size, Opcodes.TOP);
        }

        void set(int slot, Object type) {
            slots[slot] = type;
        }

        void emit(MethodVisitor mv, Object... stack) {
            mv.visitFrame(Opcodes.F_NEW, slots.length, slots.clone(), stack.length, stack);
        }

        /** The locals shape at this point of emission (for labels entered before later slots are written). */
        Object[] snapshot() {
            return slots.clone();
        }

        void emitFrom(MethodVisitor mv, Object[] shape, Object... stack) {
            mv.visitFrame(Opcodes.F_NEW, shape.length, shape.clone(), stack.length, stack);
        }
    }
}
