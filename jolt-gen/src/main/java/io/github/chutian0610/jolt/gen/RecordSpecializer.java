package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.tree.Node;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.lang.reflect.Method;

/**
 * The M5 record specialization: emits straight-line per-component bytecode for the generated
 * class's {@code parse}, {@code write} and {@code writeFields} — accessor invokes, field-name
 * constants baked into the body, direct Node-reader calls for scalars, cached child-binder
 * fields for nested record/POJO components, and construction via the plan's pre-resolved
 * {@code MethodHandle} spread over the arguments array. No plan-array lookup, no per-call
 * property-name derivation in the hot loop.
 *
 * <p>The emitted bodies mirror {@code GenBinderBase.parseRecord}/{@code writeFields} and
 * {@code Bind.toValue} instruction for instruction (same checks, same messages, same paths) —
 * the existing fixture gates enforce the zero-drift contract. Exception paths jump to
 * dedicated throw labels; every branch target carries an explicit stack-map frame
 * ({@code ByteCodeAppender} bodies get no frame computation from ByteBuddy). The
 * fail-on-unknown loop lives in a small private method ({@code jolt$checkUnknown}) so the
 * straight-line body's single join point has identical locals on both predecessors; with
 * fail-on-unknown off the check costs one branch.
 */
final class RecordSpecializer {

    private static final String NODE = internal(Node.class);
    private static final String NODE_GET_DESC = "(Ljava/lang/String;)Lio/github/chutian0610/jolt/tree/Node;";
    private static final String NODE_TYPE_DESC = "()Lio/github/chutian0610/jolt/tree/TypeTag;";
    private static final String TAG_INTERNAL = "io/github/chutian0610/jolt/tree/TypeTag";
    private static final String TAG_DESC = "Lio/github/chutian0610/jolt/tree/TypeTag;";
    private static final String WRITER = internal(JoltWriter.class);
    private static final String WRITER_START_DESC = "()Lio/github/chutian0610/jolt/JoltWriter;";
    private static final String WRITER_FIELD_NAME_DESC = "(Ljava/lang/String;)Lio/github/chutian0610/jolt/JoltWriter;";
    private static final String BIND_INTERNAL = internal(Bind.class);
    private static final String WRITE_TO_DESC =
            "(Ljava/lang/Object;Lio/github/chutian0610/jolt/JoltWriter;Ljava/lang/String;)V";
    private static final String TO_VALUE_DESC =
            "(Lio/github/chutian0610/jolt/tree/Node;Ljava/lang/reflect/Type;"
                    + "Ljava/lang/String;Lio/github/chutian0610/jolt/bind/BindConfig;)Ljava/lang/Object;";
    private static final String BIND_EXCEPTION = internal(JsonBindException.class);
    private static final String BIND_EXCEPTION_INIT_DESC = "(Ljava/lang/String;Ljava/lang/String;)V";
    private static final String BUILDER = "java/lang/StringBuilder";
    private static final String BUILDER_INIT_DESC = "(Ljava/lang/String;)V";
    private static final String BUILDER_APPEND_DESC = "(Ljava/lang/String;)Ljava/lang/StringBuilder;";
    private static final String BUILDER_APPEND_OBJ_DESC = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
    private static final String BUILDER_TOSTRING_DESC = "()Ljava/lang/String;";
    private static final String TYPED_BINDER = internal(TypedBinder.class);
    private static final String TYPED_BINDER_FIELD_DESC = "Lio/github/chutian0610/jolt/bind/TypedBinder;";
    private static final String PARSE_DESC =
            "(Lio/github/chutian0610/jolt/tree/Node;Ljava/lang/String;"
                    + "Lio/github/chutian0610/jolt/bind/BindConfig;)Ljava/lang/Object;";
    private static final String BASE_INTERNAL = internal(GenBinderBase.class);
    private static final String PLAN_INTERNAL = internal(GenPlan.class);
    private static final String PLAN_FIELD_DESC = "Lio/github/chutian0610/jolt/gen/GenPlan;";

    static final String NAME_SET_FIELD = "jolt$nameSet";
    static final String HANDLE_FIELD = "jolt$handle";
    static final String INIT_CHILDREN_METHOD = "jolt$initChildren";
    private static final String CHILD_FIELD_PREFIX = "jolt$child$";
    private static final String TYPE_FIELD_PREFIX = "jolt$type$";
    private static final String CHECK_UNKNOWN_METHOD = "jolt$checkUnknown";

    private RecordSpecializer() {}

    /** Adds the specialized fields and straight-line overrides to the generated class. */
    static DynamicType.Builder<?> apply(DynamicType.Builder<?> builder, String generatedName,
                                        GenPlan<?> plan) {
        String self = generatedName.replace('.', '/');
        builder = builder.defineField(NAME_SET_FIELD, java.util.Set.class, Visibility.PUBLIC);
        builder = builder.defineField(HANDLE_FIELD, java.lang.invoke.MethodHandle.class,
                Visibility.PUBLIC);
        for (int i = 0; i < plan.names.length; i++) {
            Class<?> raw = rawOf(plan.genericTypes[i]);
            switch (kindOf(raw)) {
                case CHILD -> builder = builder.defineField(CHILD_FIELD_PREFIX + i,
                        TypedBinder.class, Visibility.PUBLIC);
                case GENERIC -> builder = builder.defineField(TYPE_FIELD_PREFIX + i,
                        java.lang.reflect.Type.class, Visibility.PUBLIC);
                default -> { /* scalars bake no fields */ }
            }
        }
        return builder
                .defineMethod("parse", Object.class, Visibility.PUBLIC)
                .withParameters(Node.class, String.class, BindConfig.class)
                .intercept(new Implementation.Simple(new ParseBody(self, plan)))
                .defineMethod("write", void.class, Visibility.PUBLIC)
                .withParameters(Object.class, JoltWriter.class)
                .intercept(new Implementation.Simple(new WriteBody(plan, true)))
                .defineMethod("writeFields", void.class, Visibility.PUBLIC)
                .withParameters(Object.class, JoltWriter.class, String.class)
                .intercept(new Implementation.Simple(new WriteBody(plan, false)))
                .defineMethod(CHECK_UNKNOWN_METHOD, void.class, Visibility.PUBLIC)
                .withParameters(Node.class, java.util.Set.class, String.class)
                .intercept(new Implementation.Simple(new CheckUnknownBody(self)))
                .defineMethod(INIT_CHILDREN_METHOD, void.class, Visibility.PUBLIC)
                .intercept(new Implementation.Simple(new InitChildrenBody(self, plan)));
    }

    /**
     * Bakes the per-type plan data into the generated class's public fields (the name set,
     * the constructor handle, per-component generic types). Public fields cross the runtime
     * package freely — the generated class loads in ByteBuddy's ByteArrayClassLoader, a
     * different runtime package from jolt-gen despite the equal package name.
     */
    static void bakeFields(GenBinderBase binder, GenPlan<?> plan) {
        try {
            field(binder, NAME_SET_FIELD).set(binder, plan.nameSet);
            field(binder, HANDLE_FIELD).set(binder, plan.constructorHandle);
            for (int i = 0; i < plan.names.length; i++) {
                if (kindOf(rawOf(plan.genericTypes[i])) == Kind.GENERIC) {
                    field(binder, TYPE_FIELD_PREFIX + i).set(binder, plan.genericTypes[i]);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("generated binder could not bake its fields", e);
        }
    }

    /**
     * Runs {@code jolt$initChildren} on the instantiated binder — after {@code cascade} so the
     * child-binder fields resolve through the (already-registered) cache. Via reflection so
     * {@code GenBinderBase}, the fallback class, stays untouched.
     */
    static void initGeneratedBinder(GenBinderBase binder) {
        try {
            binder.getClass().getMethod(INIT_CHILDREN_METHOD).invoke(binder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("generated binder could not initialize its fields", e);
        }
    }

    private static java.lang.reflect.Field field(Object binder, String name)
            throws NoSuchFieldException {
        return binder.getClass().getDeclaredField(name);
    }

    /** The per-component parse strategy, resolved once at generation time. */
    private enum Kind {
        STRING, BOOLEAN, NUMBER, ENUM, CHILD, GENERIC
    }

    private static Kind kindOf(Class<?> raw) {
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

    private static boolean isNumber(Class<?> raw) {
        return raw == byte.class || raw == Byte.class || raw == short.class || raw == Short.class
                || raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == float.class || raw == Float.class || raw == double.class || raw == Double.class
                || raw == java.math.BigDecimal.class || raw == java.math.BigInteger.class;
    }

    private static Class<?> rawOf(java.lang.reflect.Type generic) {
        if (generic instanceof Class<?> c) {
            return c;
        }
        if (generic instanceof java.lang.reflect.ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        throw new IllegalStateException("unresolved component type " + generic);
    }

    private static String internal(Class<?> type) {
        return Type.getType(type).getInternalName();
    }

    /**
     * Straight-line parse: one pre-allocated arguments array, per-component inlined strict
     * reads, fail-on-unknown as one virtual call, construction via the spread handle.
     * Locals: 0 this | 1 node | 2 path | 3 config | 4 args | 5 field | 6 enum text
     * | 7 throwable | 8 enum value. The integral bound check works purely on the operand
     * stack — no long locals, so no long slots in any stack-map frame.
     */
    private static final class ParseBody implements ByteCodeAppender {

        private final String self;
        private final GenPlan<?> plan;
        private final String recordInternal;

        ParseBody(String self, GenPlan<?> plan) {
            this.self = self;
            this.plan = plan;
            this.recordInternal = internal(plan.type);
        }

        @Override
        public Size apply(MethodVisitor mv, Implementation.Context context,
                          MethodDescription instrumentedMethod) {
            FrameTracker slots = new FrameTracker(self, 9);
            slots.set(1, NODE);
            slots.set(2, "java/lang/String");
            slots.set(3, internal(BindConfig.class));
            slots.set(4, "[Ljava/lang/Object;");
            slots.set(5, NODE);

            emitArgsArray(mv);

            for (int i = 0; i < plan.names.length; i++) {
                emitComponent(mv, slots, i);
            }

            // fail-on-unknown: one branch when off, one virtual call when on — the loop lives
            // in jolt$checkUnknown so this join point has identical locals on both paths.
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal(BindConfig.class),
                    "failOnUnknown", "()Z", false);
            Label skipCheck = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, skipCheck);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, self, NAME_SET_FIELD, "Ljava/util/Set;");
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, CHECK_UNKNOWN_METHOD,
                    "(Lio/github/chutian0610/jolt/tree/Node;Ljava/util/Set;Ljava/lang/String;)V", false);
            mv.visitLabel(skipCheck);
            slots.emit(mv);

            // construction: pre-resolved handle spread over the args array
            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label handler = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
            mv.visitLabel(tryStart);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, self, HANDLE_FIELD,
                    "Ljava/lang/invoke/MethodHandle;");
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invoke",
                    "([Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitLabel(tryEnd);
            mv.visitTypeInsn(Opcodes.CHECKCAST, recordInternal);
            mv.visitInsn(Opcodes.ARETURN);

            mv.visitLabel(handler);
            // declare the ENTRY shape (slot 7 unwritten yet), then store the exception
            slots.emit(mv, "java/lang/Throwable");
            mv.visitVarInsn(Opcodes.ASTORE, 7);
            slots.set(7, "java/lang/Throwable");
            // invokeWithArguments semantics differ from newInstance in that the constructor's
            // own exception arrives unwrapped — the message uses it directly, matching the
            // base class's "…: " + getCause() shape.
            emitBindThrow(mv,
                    () -> emitConcat(mv, () -> {
                        mv.visitLdcInsn("record constructor rejected the bound values: ");
                        appendText(mv);
                        mv.visitVarInsn(Opcodes.ALOAD, 7);
                        appendObject(mv);
                    }),
                    () -> mv.visitVarInsn(Opcodes.ALOAD, 2));

            return new Size(16, 9);
        }

        private void emitArgsArray(MethodVisitor mv) {
            int n = plan.names.length;
            if (n <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + n);
            } else if (n <= Byte.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.BIPUSH, n);
            } else {
                mv.visitIntInsn(Opcodes.SIPUSH, n);
            }
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            mv.visitVarInsn(Opcodes.ASTORE, 4);
        }

        private void emitComponent(MethodVisitor mv, FrameTracker slots, int i) {
            // Node f = node.get(name)
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitLdcInsn(plan.names[i]);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "get", NODE_GET_DESC, false);
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            Class<?> raw = rawOf(plan.genericTypes[i]);
            String name = plan.names[i];
            switch (kindOf(raw)) {
                case STRING -> emitScalar(mv, slots, i, name, "STRING", "a string",
                        prefix -> {
                            mv.visitVarInsn(Opcodes.ALOAD, 5);
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asText",
                                    "()Ljava/lang/String;", false);
                        });
                case BOOLEAN -> emitScalar(mv, slots, i, name, "BOOLEAN", "a boolean",
                        prefix -> {
                            mv.visitVarInsn(Opcodes.ALOAD, 5);
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asBoolean", "()Z", false);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean",
                                    "valueOf", "(Z)Ljava/lang/Boolean;", false);
                        });
                case NUMBER -> emitScalar(mv, slots, i, name, "NUMBER", "a number",
                        prefix -> emitNumberRead(mv, slots, raw, name, prefix));
                case ENUM -> emitScalar(mv, slots, i, name, "STRING", "a string",
                        prefix -> emitEnumRead(mv, slots, raw, name, prefix));
                case CHILD -> emitChild(mv, slots, i, raw);
                case GENERIC -> emitGeneric(mv, slots, i);
            }
        }

        /**
         * The shared scalar skeleton: missing/null gate (primitives throw with the exact
         * missing-vs-null message; references store null), strict tag check with the
         * requireTag message, then the inlined read. {@code valueEmit} leaves the boxed value
         * on the stack.
         */
        private void emitScalar(MethodVisitor mv, FrameTracker slots, int i, String name,
                                String tagName, String wanted,
                                java.util.function.Consumer<Object[]> valueEmit) {
            boolean primitive = rawOf(plan.genericTypes[i]).isPrimitive();
            // every frame inside valueEmit must declare this two-entry stack prefix:
            // the emit runs with [args, index] already pushed for the AASTORE
            Object[] stackPrefix = {"[Ljava/lang/Object;", Opcodes.INTEGER};
            // shape at the gates: later slot writes (enum text/value) must not leak into the
            // frames of labels entered BEFORE the value is read
            Object[] gateShape = slots.snapshot();
            Label nullStore = new Label();
            Label missThrow = new Label();
            Label nullThrow = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitJumpInsn(Opcodes.IFNULL, primitive ? missThrow : nullStore);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type", NODE_TYPE_DESC, false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TAG_INTERNAL, "NULL", TAG_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, primitive ? nullThrow : nullStore);

            Label read = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type", NODE_TYPE_DESC, false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TAG_INTERNAL, tagName, TAG_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, read);
            // tag mismatch (fall-through): throw with the value's path
            emitBindThrow(mv,
                    () -> emitConcat(mv, () -> {
                        mv.visitLdcInsn("expected " + wanted + " but found ");
                        appendText(mv);
                        mv.visitVarInsn(Opcodes.ALOAD, 5);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type",
                                NODE_TYPE_DESC, false);
                        appendObject(mv);
                    }),
                    () -> emitValuePath(mv, name));
            mv.visitLabel(read);
            slots.emitFrom(mv, gateShape);

            mv.visitVarInsn(Opcodes.ALOAD, 4);
            pushIndex(mv, i);
            valueEmit.accept(stackPrefix);
            mv.visitInsn(Opcodes.AASTORE);
            Label done = new Label();
            mv.visitJumpInsn(Opcodes.GOTO, done);

            if (primitive) {
                mv.visitLabel(missThrow);
                slots.emitFrom(mv, gateShape);
                emitBindThrow(mv,
                        () -> mv.visitLdcInsn("missing value for primitive component "
                                + rawOf(plan.genericTypes[i]).getSimpleName()),
                        () -> emitValuePath(mv, name));
                mv.visitLabel(nullThrow);
                slots.emitFrom(mv, gateShape);
                emitBindThrow(mv,
                        () -> mv.visitLdcInsn("JSON null for primitive component "
                                + rawOf(plan.genericTypes[i]).getSimpleName()),
                        () -> emitValuePath(mv, name));
            } else {
                mv.visitLabel(nullStore);
                slots.emitFrom(mv, gateShape);
                if (kindOf(rawOf(plan.genericTypes[i])) == Kind.ENUM) {
                    // the value path wrote the text/value slots, this path did not — null pins
                    // the slot types so the done-join accepts both (null is assignable to any
                    // reference declaration)
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitVarInsn(Opcodes.ASTORE, 6);
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitVarInsn(Opcodes.ASTORE, 8);
                }
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                pushIndex(mv, i);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.AASTORE);
            }
            mv.visitLabel(done);
            slots.emit(mv);
        }

        /** The numeric family: per-width read with the exact Bind semantics. */
        private void emitNumberRead(MethodVisitor mv, FrameTracker slots, Class<?> raw,
                                    String name, Object[] stackPrefix) {
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
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                            "(F)Ljava/lang/Float;", false);
                }
                case "double", "java.lang.Double" -> {
                    asDecimal(mv);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                            "doubleValue", "()D", false);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                            "(D)Ljava/lang/Double;", false);
                }
                default -> emitIntegralRead(mv, slots, raw, name, stackPrefix);
            }
        }

        /** long/int/short/byte: asLong (ArithmeticException → "…a long"), bounds, narrowing.
         * The whole read works on the operand stack — no long local, so no long locals in any
         * stack-map frame. */
        private void emitIntegralRead(MethodVisitor mv, FrameTracker slots, Class<?> raw,
                                      String name, Object[] stackPrefix) {
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
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
                            "(B)Ljava/lang/Byte;", false);
                    boxed = "java/lang/Byte";
                }
                case "short", "java.lang.Short" -> {
                    mv.visitInsn(Opcodes.L2I);
                    mv.visitInsn(Opcodes.I2S);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
                            "(S)Ljava/lang/Short;", false);
                    boxed = "java/lang/Short";
                }
                case "int", "java.lang.Integer" -> {
                    mv.visitInsn(Opcodes.L2I);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false);
                    boxed = "java/lang/Integer";
                }
                default -> {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                            "(J)Ljava/lang/Long;", false);
                    boxed = "java/lang/Long";
                }
            }
            mv.visitJumpInsn(Opcodes.GOTO, uncaught);
            mv.visitLabel(handler);
            slots.emit(mv, "java/lang/ArithmeticException");
            mv.visitInsn(Opcodes.POP);
            emitBindThrow(mv, () -> mv.visitLdcInsn("number does not fit in a long"),
                    () -> emitValuePath(mv, name));
            mv.visitLabel(uncaught);
            slots.emit(mv, withStack(stackPrefix, boxed));
        }

        /** Strict tag + Enum.valueOf with the unknown-name handler (text kept in slot 6). */
        private void emitEnumRead(MethodVisitor mv, FrameTracker slots, Class<?> raw,
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
            // an exception handler enters with a CLEARED operand stack, exception on top only
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

        /** Nested record/POJO: cached child-binder field, monomorphic parse call. */
        private void emitChild(MethodVisitor mv, FrameTracker slots, int i, Class<?> raw) {
            Label nullStore = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitJumpInsn(Opcodes.IFNULL, nullStore);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type", NODE_TYPE_DESC, false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TAG_INTERNAL, "NULL", TAG_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, nullStore);
            if (raw.isRecord()) {
                // the reflective record branch requires an OBJECT node before parsing
                Label parse = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type", NODE_TYPE_DESC, false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TAG_INTERNAL, "OBJECT", TAG_DESC);
                mv.visitJumpInsn(Opcodes.IF_ACMPEQ, parse);
                emitBindThrow(mv,
                        () -> emitConcat(mv, () -> {
                            mv.visitLdcInsn("expected an object but found ");
                            appendText(mv);
                            mv.visitVarInsn(Opcodes.ALOAD, 5);
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type",
                                    NODE_TYPE_DESC, false);
                            appendObject(mv);
                        }),
                        () -> emitValuePath(mv, plan.names[i]));
                mv.visitLabel(parse);
                slots.emit(mv);
            }
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            pushIndex(mv, i);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, self, CHILD_FIELD_PREFIX + i,
                    TYPED_BINDER_FIELD_DESC);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            emitPath(mv, () -> mv.visitVarInsn(Opcodes.ALOAD, 2),
                    () -> mv.visitLdcInsn(plan.names[i]));
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, TYPED_BINDER, "parse", PARSE_DESC, true);
            mv.visitInsn(Opcodes.AASTORE);
            Label done = new Label();
            mv.visitJumpInsn(Opcodes.GOTO, done);
            mv.visitLabel(nullStore);
            slots.emit(mv);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            pushIndex(mv, i);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.AASTORE);
            mv.visitLabel(done);
            slots.emit(mv);
        }

        /** Collections, arrays, maps, Object: routed through Bind.toValue unchanged. */
        private void emitGeneric(MethodVisitor mv, FrameTracker slots, int i) {
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            pushIndex(mv, i);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, self, TYPE_FIELD_PREFIX + i,
                    "Ljava/lang/reflect/Type;");
            emitPath(mv, () -> mv.visitVarInsn(Opcodes.ALOAD, 2),
                    () -> mv.visitLdcInsn(plan.names[i]));
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, BIND_INTERNAL, "toValue", TO_VALUE_DESC, false);
            mv.visitInsn(Opcodes.AASTORE);
        }

        // --- shared emission helpers ---

        private static Object[] withStack(Object[] prefix, Object... top) {
            Object[] out = new Object[prefix.length + top.length];
            System.arraycopy(prefix, 0, out, 0, prefix.length);
            System.arraycopy(top, 0, out, prefix.length, top.length);
            return out;
        }

        private void pushIndex(MethodVisitor mv, int i) {
            if (i <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + i);
            } else if (i <= Byte.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.BIPUSH, i);
            } else {
                mv.visitIntInsn(Opcodes.SIPUSH, i);
            }
        }

        private void asDecimal(MethodVisitor mv) {
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asDecimal",
                    "()Ljava/math/BigDecimal;", false);
        }

        /** Two concatenated strings: base + "." + last (constant name or runtime key). */
        private void emitPath(MethodVisitor mv, Runnable loadBase, Runnable loadLast) {
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

        private void emitValuePath(MethodVisitor mv, String name) {
            emitPath(mv, () -> mv.visitVarInsn(Opcodes.ALOAD, 2),
                    () -> mv.visitLdcInsn(name));
        }

        /** new StringBuilder() + appends + toString, left on the stack. */
        private void emitConcat(MethodVisitor mv, Runnable appends) {
            mv.visitTypeInsn(Opcodes.NEW, BUILDER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BUILDER, "<init>", "()V", false);
            appends.run();
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "toString", BUILDER_TOSTRING_DESC,
                    false);
        }

        private void appendText(MethodVisitor mv) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_DESC,
                    false);
        }

        private void appendObject(MethodVisitor mv) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_OBJ_DESC,
                    false);
        }

        /**
         * NEW JsonBindException; DUP; message; path; INVOKESPECIAL — javac's construction
         * order: both arguments build on the stack above the receiver pair, no extra locals.
         */
        private void emitBindThrow(MethodVisitor mv, Runnable messageEmit, Runnable pathEmit) {
            mv.visitTypeInsn(Opcodes.NEW, BIND_EXCEPTION);
            mv.visitInsn(Opcodes.DUP);
            messageEmit.run();
            pathEmit.run();
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BIND_EXCEPTION, "<init>",
                    BIND_EXCEPTION_INIT_DESC, false);
            mv.visitInsn(Opcodes.ATHROW);
        }
    }

    /**
     * Straight-line write: accessor invoke, field-name constant, Bind.writeTo recursion.
     * Braces variant is the full-document {@code write} ("$" path base); the other is
     * {@code writeFields}. Locals: 0 this | 1 value | 2 writer | 3 path | 4 field value.
     */
    private static final class WriteBody implements ByteCodeAppender {

        private final GenPlan<?> plan;
        private final boolean braces;

        WriteBody(GenPlan<?> plan, boolean braces) {
            this.plan = plan;
            this.braces = braces;
        }

        @Override
        public Size apply(MethodVisitor mv, Implementation.Context context,
                          MethodDescription instrumentedMethod) {
            String recordInternal = internal(plan.type);
            if (braces) {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WRITER, "startObject",
                        WRITER_START_DESC, false);
                mv.visitInsn(Opcodes.POP);
            }
            for (int i = 0; i < plan.names.length; i++) {
                Method accessor = plan.accessors[i];
                String name = plan.names[i];
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitTypeInsn(Opcodes.CHECKCAST, recordInternal);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, recordInternal, accessor.getName(),
                        Type.getMethodDescriptor(accessor), false);
                Class<?> returnType = accessor.getReturnType();
                if (returnType.isPrimitive()) {
                    String boxed = switch (returnType.getName()) {
                        case "int" -> "java/lang/Integer";
                        case "long" -> "java/lang/Long";
                        case "double" -> "java/lang/Double";
                        case "float" -> "java/lang/Float";
                        case "boolean" -> "java/lang/Boolean";
                        case "byte" -> "java/lang/Byte";
                        case "short" -> "java/lang/Short";
                        default -> "java/lang/Character";
                    };
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, boxed, "valueOf",
                            "(" + Type.getType(returnType).getDescriptor() + ")L" + boxed + ";",
                            false);
                }
                mv.visitVarInsn(Opcodes.ASTORE, 4);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WRITER, "fieldName",
                        WRITER_FIELD_NAME_DESC, false);
                mv.visitInsn(Opcodes.POP);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                emitPath(mv, braces
                        ? () -> mv.visitLdcInsn("$")
                        : () -> mv.visitVarInsn(Opcodes.ALOAD, 3),
                        () -> mv.visitLdcInsn(name));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, BIND_INTERNAL, "writeTo", WRITE_TO_DESC,
                        false);
            }
            if (braces) {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WRITER, "endObject",
                        WRITER_START_DESC, false);
                mv.visitInsn(Opcodes.POP);
            }
            mv.visitInsn(Opcodes.RETURN);
            return new Size(12, braces ? 5 : 6);
        }

        private void emitPath(MethodVisitor mv, Runnable loadBase, Runnable loadLast) {
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
    }

    /** fail-on-unknown as an O(1) contains loop over the baked name set. */
    private static final class CheckUnknownBody implements ByteCodeAppender {

        private final String self;

        CheckUnknownBody(String self) {
            this.self = self;
        }

        @Override
        public Size apply(MethodVisitor mv, Implementation.Context context,
                          MethodDescription instrumentedMethod) {
            // locals: 0 this | 1 node | 2 names | 3 path | 4 iterator | 5 key
            Object[] slots = {self, NODE, "java/util/Set", "java/lang/String", Opcodes.TOP,
                    Opcodes.TOP};
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "keys", "()Ljava/util/List;", false);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                    "()Ljava/util/Iterator;", true);
            mv.visitVarInsn(Opcodes.ASTORE, 4);
            slots[4] = "java/util/Iterator";
            // pin the key slot's type before the loop head so the back edge and the exit
            // frames declare the same local shape
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            slots[5] = "java/lang/String";

            Label loopHead = new Label();
            Label loopEnd = new Label();
            mv.visitLabel(loopHead);
            mv.visitFrame(Opcodes.F_NEW, slots.length, slots.clone(), 0, new Object[0]);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z",
                    true);
            mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                    "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            slots[5] = "java/lang/String";
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains",
                    "(Ljava/lang/Object;)Z", true);
            mv.visitJumpInsn(Opcodes.IFNE, loopHead);

            mv.visitTypeInsn(Opcodes.NEW, BIND_EXCEPTION);
            mv.visitInsn(Opcodes.DUP);
            emitConcat(mv, () -> {
                mv.visitLdcInsn("unknown field '");
                appendText(mv);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                appendText(mv);
                mv.visitLdcInsn("'");
                appendText(mv);
            });
            // the unknown key's own path: path + "." + key (key is the runtime loop variable)
            mv.visitTypeInsn(Opcodes.NEW, BUILDER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BUILDER, "<init>", BUILDER_INIT_DESC, false);
            mv.visitLdcInsn(".");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_DESC,
                    false);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_DESC,
                    false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "toString", BUILDER_TOSTRING_DESC,
                    false);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BIND_EXCEPTION, "<init>",
                    BIND_EXCEPTION_INIT_DESC, false);
            mv.visitInsn(Opcodes.ATHROW);

            mv.visitLabel(loopEnd);
            mv.visitFrame(Opcodes.F_NEW, slots.length, slots.clone(), 0, new Object[0]);
            mv.visitInsn(Opcodes.RETURN);
            return new Size(10, 6);
        }

        private void emitConcat(MethodVisitor mv, Runnable appends) {
            mv.visitTypeInsn(Opcodes.NEW, BUILDER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BUILDER, "<init>", "()V", false);
            appends.run();
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "toString", BUILDER_TOSTRING_DESC,
                    false);
        }

        private void appendText(MethodVisitor mv) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "append", BUILDER_APPEND_DESC,
                    false);
        }
    }

    /**
     * One-time field baking, run after cascade: the name set and, per component, the generic
     * {@code Type} (for Bind.toValue routing) and the child binder (nested record/POJO).
     */
    private static final class InitChildrenBody implements ByteCodeAppender {

        private final String self;
        private final GenPlan<?> plan;

        InitChildrenBody(String self, GenPlan<?> plan) {
            this.self = self;
            this.plan = plan;
        }

        @Override
        public Size apply(MethodVisitor mv, Implementation.Context context,
                          MethodDescription instrumentedMethod) {
            // only child binders resolve here — every other baked value is a public field
            // injected via reflection (the generated class cannot read the base's
            // package-private plan across its runtime package)
            for (int i = 0; i < plan.names.length; i++) {
                Class<?> raw = rawOf(plan.genericTypes[i]);
                if (kindOf(raw) == Kind.CHILD) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitLdcInsn(Type.getType(raw));
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            internal(GeneratedBinders.class), "forType",
                            "(Ljava/lang/Class;)" + TYPED_BINDER_FIELD_DESC, false);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, self, CHILD_FIELD_PREFIX + i,
                            TYPED_BINDER_FIELD_DESC);
                }
            }
            mv.visitInsn(Opcodes.RETURN);
            return new Size(6, 1);
        }

        private void pushIndex(MethodVisitor mv, int i) {
            if (i <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + i);
            } else if (i <= Byte.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.BIPUSH, i);
            } else {
                mv.visitIntInsn(Opcodes.SIPUSH, i);
            }
        }
    }

    /** Per-slot stack-map frame bookkeeping for the straight-line parse body. */
    private static final class FrameTracker {

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
