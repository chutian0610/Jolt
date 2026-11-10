package io.github.chutian0610.jolt.gen;

import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.lang.reflect.Method;

import static io.github.chutian0610.jolt.gen.Emit.CHILD_FIELD_PREFIX;
import static io.github.chutian0610.jolt.gen.Emit.DIRECT_SUPPORT;
import static io.github.chutian0610.jolt.gen.Emit.GEN_BASE;
import static io.github.chutian0610.jolt.gen.Emit.GEN_BASE_PARSE_TOKENS_BODY_DESC;
import static io.github.chutian0610.jolt.gen.Emit.Kind;
import static io.github.chutian0610.jolt.gen.Emit.NESTED_BODY_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_BIG_DECIMAL_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_TEXT_VALUE_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TOKEN_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TOKEN_INTERNAL;
import static io.github.chutian0610.jolt.gen.Emit.TYPED_BINDER_FIELD_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TYPE_FIELD_PREFIX;
import static io.github.chutian0610.jolt.gen.Emit.internal;
import static io.github.chutian0610.jolt.gen.Emit.kindOf;
import static io.github.chutian0610.jolt.gen.Emit.withStack;

/**
 * The shared emitter for one strict value read inside the generated direct read's body
 * (M7/M8): the JSON-null gate, the tag gates, the kind dispatch (string, boolean, the
 * numeric family with the all-stack bound dance, enum, nested child, generic/inline), and
 * the delivery of the value to its consumer. This is the single transcription of what were
 * three near-identical families in {@code RecordSpecializer}, {@code PojoSpecializer}, and
 * {@code InlineCollections}; their only genuine differences live in the
 * {@link ValueTarget} adapter — where the value lands, the frame's stack base, and whether
 * the boolean/enum/BigInteger joins carry a value frame.
 *
 * <p>Byte-identity discipline: every emit call here is a verbatim transcription of the
 * original families at their original program points — the module adds no labels, frames,
 * or reordering of its own. Where the families diverged (the slot target stores and jumps;
 * the setter/element targets leave the value on the stack under a join frame), the
 * divergence is the adapter's, selected at exactly those points.
 *
 * <p>Zero upward callbacks: the adapter is sealed data + terminal delivery; the module owns
 * all emission order (the M5/M7 frame-discipline lesson — the interleave points stay visible
 * in the callers, which emit their own preamble/sweep/construction around this call).
 */
final class DirectBodies {

    private DirectBodies() {}

    /** The body's slot layout as data — the two bodies' layouts differ (record
     * VALUE_TOKEN=7/TEXT=10, POJO VALUE_TOKEN=6/TEXT=9), so the callers carry the numbers. */
    record ReadContext(Emit.FrameTracker slots, String self, int valueToken, int text,
                       int carrierSlot, int scratchSlot) {
        ReadContext {
            if (slots == null || self == null) {
                throw new IllegalStateException("direct-read context is incomplete");
            }
        }
    }

    /** Where one read's value lands, and how the frames around it are declared. */
    sealed interface ValueTarget permits Slot, Setter, Element {

        /** The component's runtime type (the delivery kind). */
        Class<?> raw();

        /** The operand-stack base every frame inside the read must declare ([] for the
         * slot target; the preloaded receiver for a setter; the loop's base for elements). */
        Object[] stackBase();

        /** Whether the boolean/enum/BigInteger reads converge on a value-carrying join
         * frame (setter/element targets) or deliver per-edge without one (slot target). */
        boolean joins();

        /** Whether the scalar delivery is always boxed (container elements), regardless of
         * the component's own primitive-ness. */
        boolean boxes();

        /** The value is on the stack in its delivery form: consume it and end the read. */
        void deliver(MethodVisitor mv, ReadContext cx);

        /** The JSON-null edge for a reference component (the frame is already emitted):
         * bind null / raise. Only reached when the raw kind is not primitive. */
        void deliverNull(MethodVisitor mv, ReadContext cx);

        /** The CHILD-kind dispatch decision, owned by the caller: a record parent consults
         * the record predicate (false for POJO children — they bind through the door), a
         * POJO parent dispatches per the child's own shape. NOT one rule: the monomorphic
         * arm calls the child's ROOT-form body, the door binds it nested. */
        boolean childCapable();
    }

    /** A body's typed local: the value is stored and control jumps the read's try end. */
    record Slot(int slot, Class<?> raw, Label join, Label tryEnd,
                boolean childCapable) implements ValueTarget {

        @Override
        public Object[] stackBase() {
            return new Object[0];
        }

        @Override
        public boolean joins() {
            return false;
        }

        @Override
        public boolean boxes() {
            return false;
        }

        @Override
        public void deliver(MethodVisitor mv, ReadContext cx) {
            store(mv, slot, raw);
            mv.visitJumpInsn(Opcodes.GOTO, tryEnd);
        }

        @Override
        public void deliverNull(MethodVisitor mv, ReadContext cx) {
            mv.visitJumpInsn(Opcodes.GOTO, join);
        }
    }

    /** A POJO setter: the receiver sits under the value; the guarded invoke consumes both. */
    record Setter(Method setter, String name, Class<?> raw, String pojoInternal,
                  int instance, int setterThrowable, Label tryEnd, Object[] stackBase,
                  boolean childCapable) implements ValueTarget {

        @Override
        public Object[] stackBase() {
            return stackBase;
        }

        @Override
        public boolean joins() {
            return true;
        }

        @Override
        public boolean boxes() {
            return false;
        }

        @Override
        public boolean childCapable() {
            return childCapable;
        }

        @Override
        public void deliver(MethodVisitor mv, ReadContext cx) {
            invokeSetter(mv, cx, this);
            mv.visitJumpInsn(Opcodes.GOTO, tryEnd);
        }

        @Override
        public void deliverNull(MethodVisitor mv, ReadContext cx) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            invokeSetter(mv, cx, this);
            // the null path joins the value path explicitly — falling straight into the
            // caller's tryEnd would put the guarded invoke's done frame and the tryEnd
            // frame on ONE offset (the M5-T2 zero-gap rule)
            mv.visitJumpInsn(Opcodes.GOTO, tryEnd);
        }
    }

    /** An inlined collection's element: the boxed value stays on the stack; the loop's
     * shared {@code done} join (declared by the caller) catches every branch. */
    record Element(Object[] stackBase, Class<?> raw, Label exit) implements ValueTarget {

        @Override
        public boolean joins() {
            return true;
        }

        @Override
        public boolean boxes() {
            return true;
        }

        @Override
        public boolean childCapable() {
            return false; // the element child branch keeps its own emitter
        }

        @Override
        public void deliver(MethodVisitor mv, ReadContext cx) {
            mv.visitJumpInsn(Opcodes.GOTO, exit);
        }

        @Override
        public void deliverNull(MethodVisitor mv, ReadContext cx) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            // control falls to the caller's done join, which declares the Object stack
        }
    }

    /**
     * One component's complete strict read. Precondition: the body's value-token slot holds
     * this value's first token (and, for the setter target, the receiver sits on the stack
     * beneath everything). Postcondition: control has left through the target's delivery.
     */
    static void emitValue(MethodVisitor mv, ReadContext cx, int component,
                          InlineCollections.Shape inline, ValueTarget t) {
        Class<?> raw = t.raw();
        boolean primitive = raw.isPrimitive();
        Label nullEdge = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, cx.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_NULL", TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, nullEdge);
        switch (kindOf(raw)) {
            case STRING -> {
                requireToken(mv, cx, t, "VALUE_STRING", "a string");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "textValue",
                        PARSER_TEXT_VALUE_DESC, true);
                t.deliver(mv, cx);
            }
            case BOOLEAN -> emitBoolean(mv, cx, t, raw);
            case NUMBER -> emitNumber(mv, cx, t, raw);
            case ENUM -> emitEnum(mv, cx, t, raw);
            case CHILD -> emitChild(mv, cx, component, t, raw);
            case GENERIC -> emitGeneric(mv, cx, component, inline, t, raw);
            default -> throw new IllegalStateException(
                    "component " + component + " is not a direct-read shape");
        }

        mv.visitLabel(nullEdge);
        frames(mv, cx, t);
        if (primitive) {
            mv.visitLdcInsn(raw.getSimpleName());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "nullForPrimitive",
                    "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
            mv.visitInsn(Opcodes.ATHROW);
        } else {
            t.deliverNull(mv, cx);
        }
    }

    // --- the kind reads ---------------------------------------------------------------

    private static void emitBoolean(MethodVisitor mv, ReadContext cx, ValueTarget t,
                                    Class<?> raw) {
        Label trueEdge = new Label();
        Label falseEdge = new Label();
        Label valueReady = t.joins() ? new Label() : null;
        mv.visitVarInsn(Opcodes.ALOAD, cx.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_TRUE", TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, trueEdge);
        mv.visitVarInsn(Opcodes.ALOAD, cx.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_FALSE", TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, falseEdge);
        raiseTagError(mv, cx, "a boolean");
        mv.visitLabel(trueEdge);
        frames(mv, cx, t);
        pushBoolean(mv, t, raw, true);
        if (t.joins()) {
            mv.visitJumpInsn(Opcodes.GOTO, valueReady);
        } else {
            t.deliver(mv, cx);
        }
        mv.visitLabel(falseEdge);
        frames(mv, cx, t);
        pushBoolean(mv, t, raw, false);
        if (t.joins()) {
            mv.visitLabel(valueReady);
            frames(mv, cx, t, valueFrameType(raw));
        }
        t.deliver(mv, cx);
    }

    private static void emitEnum(MethodVisitor mv, ReadContext cx, ValueTarget t,
                                 Class<?> raw) {
        requireToken(mv, cx, t, "VALUE_STRING", "a string");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "textValue",
                PARSER_TEXT_VALUE_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, cx.text());
        Label tryStart = new Label();
        Label exact = new Label();
        Label lossy = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(tryStart, exact, lossy, "java/lang/IllegalArgumentException");
        mv.visitLabel(tryStart);
        mv.visitLdcInsn(Type.getType(raw));
        mv.visitVarInsn(Opcodes.ALOAD, cx.text());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, internal(raw));
        mv.visitLabel(exact);
        if (t.joins()) {
            mv.visitJumpInsn(Opcodes.GOTO, done);
        } else {
            t.deliver(mv, cx);
        }
        mv.visitLabel(lossy);
        // the handler's entry stack is CLEARED (the locals stay declared) — no receiver
        // base here
        cx.slots().emit(mv, "java/lang/IllegalArgumentException");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, cx.text());
        mv.visitLdcInsn(raw.getSimpleName());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "unknownEnumName",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/RuntimeException;",
                false);
        mv.visitInsn(Opcodes.ATHROW);
        if (t.joins()) {
            mv.visitLabel(done);
            frames(mv, cx, t, valueFrameType(raw));
            t.deliver(mv, cx);
        }
    }

    private static void emitChild(MethodVisitor mv, ReadContext cx, int component,
                                  ValueTarget t, Class<?> raw) {
        // the capability decision belongs to the caller: a record parent consults the
        // record predicate (false for POJO children — they bind through the door), a POJO
        // parent dispatches per the child's own shape. NOT one rule (the three-grade
        // matrix catches the difference: the monomorphic arm calls the child's ROOT-form
        // body, the door binds it nested).
        requireToken(mv, cx, t, "START_OBJECT", "an object");
        if (t.childCapable()) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, cx.self(), CHILD_FIELD_PREFIX + component,
                    TYPED_BINDER_FIELD_DESC);
            mv.visitTypeInsn(Opcodes.CHECKCAST, GEN_BASE);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GEN_BASE,
                    RecordSpecializer.PARSE_TOKENS_BODY_METHOD,
                    GEN_BASE_PARSE_TOKENS_BODY_DESC, false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitLdcInsn(Type.getType(raw));
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "nestedBody",
                    NESTED_BODY_DESC, false);
        }
        mv.visitTypeInsn(Opcodes.CHECKCAST, internal(raw));
        t.deliver(mv, cx);
    }

    private static void emitGeneric(MethodVisitor mv, ReadContext cx, int component,
                                    InlineCollections.Shape inline, ValueTarget t,
                                    Class<?> raw) {
        if (inline != null) {
            InlineCollections.emit(mv, new InlineCollections.Body(cx.self(), cx.valueToken(),
                    cx.text(), cx.carrierSlot(), cx.scratchSlot(), cx.slots()),
                    inline, component, t.stackBase());
            mv.visitTypeInsn(Opcodes.CHECKCAST, internal(raw));
            t.deliver(mv, cx);
            return;
        }
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, cx.valueToken());
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, cx.self(), TYPE_FIELD_PREFIX + component,
                "Ljava/lang/reflect/Type;");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "readValue",
                "(Lio/github/chutian0610/jolt/JoltParser;"
                        + "Lio/github/chutian0610/jolt/Token;"
                        + "Ljava/lang/reflect/Type;"
                        + "Lio/github/chutian0610/jolt/bind/BindConfig;"
                        + ")Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, internal(raw));
        t.deliver(mv, cx);
    }

    /** The numeric family: the decimal reads share the requireNumber + bigDecimalValue
     * prefix; the integral family runs the shared checked long read and the M5 all-stack
     * bound dance (each throw edge declares the stack ITS edge leaves). */
    private static void emitNumber(MethodVisitor mv, ReadContext cx, ValueTarget t,
                                   Class<?> raw) {
        boolean integral = raw == byte.class || raw == short.class || raw == int.class
                || raw == long.class || raw == Byte.class || raw == Short.class
                || raw == Integer.class || raw == Long.class;
        if (!integral) {
            requireNumber(mv, cx);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                    PARSER_BIG_DECIMAL_DESC, true);
        }
        switch (raw.getName()) {
            case "float", "java.lang.Float" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        "floatValue", "()F", false);
                boxIfNeeded(mv, t, raw);
                t.deliver(mv, cx);
            }
            case "double", "java.lang.Double" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        "doubleValue", "()D", false);
                boxIfNeeded(mv, t, raw);
                t.deliver(mv, cx);
            }
            case "java.math.BigDecimal" -> t.deliver(mv, cx);
            case "java.math.BigInteger" -> emitBigInteger(mv, cx, t, raw);
            default -> emitIntegral(mv, cx, t, raw);
        }
    }

    private static void emitBigInteger(MethodVisitor mv, ReadContext cx, ValueTarget t,
                                       Class<?> raw) {
        Label tryStart = new Label();
        Label exact = new Label();
        Label lossy = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(tryStart, exact, lossy, "java/lang/ArithmeticException");
        mv.visitLabel(tryStart);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                "toBigIntegerExact", "()Ljava/math/BigInteger;", false);
        mv.visitLabel(exact);
        if (t.joins()) {
            mv.visitJumpInsn(Opcodes.GOTO, done);
        } else {
            t.deliver(mv, cx);
        }
        mv.visitLabel(lossy);
        // the handler's entry stack is CLEARED (the locals stay declared) — no receiver
        // base here
        cx.slots().emit(mv, "java/lang/ArithmeticException");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "notWholeNumber",
                "()Ljava/lang/RuntimeException;", false);
        mv.visitInsn(Opcodes.ATHROW);
        if (t.joins()) {
            mv.visitLabel(done);
            frames(mv, cx, t, internal(raw));
            t.deliver(mv, cx);
        }
    }

    /** byte/short/int/long: the shared checked long read, then the all-stack bound dance
     * (no long locals — the two throw edges declare their own stack depths), the narrowing
     * conversion, and the delivery. */
    private static void emitIntegral(MethodVisitor mv, ReadContext cx, ValueTarget t,
                                     Class<?> raw) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, cx.valueToken());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "readLongChecked",
                "(Lio/github/chutian0610/jolt/JoltParser;"
                        + "Lio/github/chutian0610/jolt/Token;)J", false);
        long bound = boundOf(raw);
        if (bound == Long.MAX_VALUE) {
            boxIfNeeded(mv, t, raw);
            t.deliver(mv, cx);
            return;
        }
        Label overFirst = new Label();
        Label overSecond = new Label();
        Label inRange = new Label();
        // stack: [v] — three copies while comparing; each throw edge declares the stack
        // ITS edge leaves (first: two longs, second: one)
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
        frames(mv, cx, t, Opcodes.LONG, Opcodes.LONG);
        mv.visitLdcInsn(raw.getSimpleName());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "boundError",
                "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(overSecond);
        frames(mv, cx, t, Opcodes.LONG);
        mv.visitLdcInsn(raw.getSimpleName());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "boundError",
                "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(inRange);
        frames(mv, cx, t, Opcodes.LONG);
        mv.visitInsn(Opcodes.L2I);
        switch (raw.getName()) {
            case "byte", "java.lang.Byte" -> mv.visitInsn(Opcodes.I2B);
            case "short", "java.lang.Short" -> mv.visitInsn(Opcodes.I2S);
            default -> { }
        }
        boxIfNeeded(mv, t, raw);
        t.deliver(mv, cx);
    }

    // --- the shared scaffolding ---------------------------------------------------------

    /** The width-bound table, one copy (was five across the emitter families + the doors). */
    private static long boundOf(Class<?> raw) {
        return switch (raw.getName()) {
            case "byte", "java.lang.Byte" -> Byte.MAX_VALUE;
            case "short", "java.lang.Short" -> Short.MAX_VALUE;
            case "int", "java.lang.Integer" -> Integer.MAX_VALUE;
            default -> Long.MAX_VALUE;
        };
    }

    /**
     * Boxes the on-stack converted primitive when the delivery kind takes a reference:
     * container elements always; slot/setter targets exactly when the component itself is
     * a boxed type. Reference kinds pass through untouched.
     */
    private static void boxIfNeeded(MethodVisitor mv, ValueTarget t, Class<?> raw) {
        // the numeric conversions leave a PRIMITIVE on the stack; box it exactly when the
        // delivery takes a reference (elements always; slot/setter targets for boxed types)
        boolean primitiveOnStack = raw.isPrimitive() || raw == Byte.class || raw == Short.class
                || raw == Integer.class || raw == Long.class || raw == Float.class
                || raw == Double.class;
        if (!primitiveOnStack) {
            return;
        }
        if (!t.boxes() && raw.isPrimitive()) {
            return;
        }
        switch (raw.getName()) {
            case "byte", "java.lang.Byte" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            case "short", "java.lang.Short" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            case "long", "java.lang.Long" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            case "float", "java.lang.Float" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            case "double", "java.lang.Double" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            default -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer",
                    "valueOf", "(I)Ljava/lang/Integer;", false);
        }
    }

    private static void requireToken(MethodVisitor mv, ReadContext cx, ValueTarget t,
                                     String constant, String wanted) {
        Label ok = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, cx.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, constant, TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, ok);
        raiseTagError(mv, cx, wanted);
        mv.visitLabel(ok);
        frames(mv, cx, t);
    }

    private static void raiseTagError(MethodVisitor mv, ReadContext cx, String wanted) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, cx.valueToken());
        mv.visitLdcInsn(wanted);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "tagError",
                "(Lio/github/chutian0610/jolt/JoltParser;"
                        + "Lio/github/chutian0610/jolt/Token;"
                        + "Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
        mv.visitInsn(Opcodes.ATHROW);
    }

    private static void requireNumber(MethodVisitor mv, ReadContext cx) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, cx.valueToken());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "requireNumber",
                "(Lio/github/chutian0610/jolt/JoltParser;"
                        + "Lio/github/chutian0610/jolt/Token;)V", false);
    }

    private static void pushBoolean(MethodVisitor mv, ValueTarget t, Class<?> raw,
                                    boolean value) {
        if (!t.boxes() && raw.isPrimitive()) {
            mv.visitInsn(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean",
                    value ? "TRUE" : "FALSE", "Ljava/lang/Boolean;");
        }
    }

    /** The join frame's stack top: a primitive boolean carries INTEGER; everything else
     * carries its own internal name (boxed boolean → java/lang/Boolean, enum → its class,
     * BigInteger → java/math/BigInteger). */
    private static Object valueFrameType(Class<?> raw) {
        return raw == boolean.class ? Opcodes.INTEGER : internal(raw);
    }

    private static void store(MethodVisitor mv, int slot, Class<?> raw) {
        switch (raw.getName()) {
            case "boolean", "byte", "char", "short", "int" ->
                    mv.visitVarInsn(Opcodes.ISTORE, slot);
            case "long" -> mv.visitVarInsn(Opcodes.LSTORE, slot);
            case "float" -> mv.visitVarInsn(Opcodes.FSTORE, slot);
            case "double" -> mv.visitVarInsn(Opcodes.DSTORE, slot);
            default -> mv.visitVarInsn(Opcodes.ASTORE, slot);
        }
    }

    /** The guarded invoke: [receiver, value] already on the stack; the try region covers
     * ONLY the INVOKEVIRTUAL — a throwing setter raises the shared rejection. */
    private static void invokeSetter(MethodVisitor mv, ReadContext cx, Setter t) {
        Method setter = t.setter();
        String setterDesc = "("
                + Type.getType(setter.getParameterTypes()[0]).getDescriptor() + ")V";
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/RuntimeException");
        Object[] entryShape = cx.slots().snapshot();
        mv.visitLabel(tryStart);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, t.pojoInternal(), setter.getName(),
                setterDesc, false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(handler);
        cx.slots().emitFrom(mv, entryShape, "java/lang/RuntimeException");
        mv.visitVarInsn(Opcodes.ASTORE, t.setterThrowable());
        mv.visitVarInsn(Opcodes.ALOAD, t.setterThrowable());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "setterRejected",
                "(Ljava/lang/Throwable;)Lio/github/chutian0610/jolt/bind/JsonBindException;",
                false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(done);
        cx.slots().emitFrom(mv, entryShape);
    }

    private static void frames(MethodVisitor mv, ReadContext cx, ValueTarget t,
                               Object... stack) {
        if (t.stackBase().length == 0) {
            cx.slots().emit(mv, stack);
        } else {
            cx.slots().emit(mv, withStack(t.stackBase(), stack));
        }
    }
}
