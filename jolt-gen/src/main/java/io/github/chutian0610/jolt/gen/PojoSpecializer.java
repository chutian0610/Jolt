package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.BindConfig;
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

import static io.github.chutian0610.jolt.gen.Emit.BIND_EXCEPTION;
import static io.github.chutian0610.jolt.gen.Emit.BIND_INTERNAL;
import static io.github.chutian0610.jolt.gen.Emit.CHILD_FIELD_PREFIX;
import static io.github.chutian0610.jolt.gen.Emit.DIRECT_SUPPORT;
import static io.github.chutian0610.jolt.gen.Emit.GEN_BASE;
import static io.github.chutian0610.jolt.gen.Emit.GEN_BASE_PARSE_TOKENS_BODY_DESC;
import static io.github.chutian0610.jolt.gen.Emit.NESTED_BODY_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_BIG_DECIMAL_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_FIELD_NAME_EQUALS_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_NEXT_TOKEN_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_TEXT_VALUE_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TOKEN_DESC;
import static io.github.chutian0610.jolt.gen.Emit.pushIndex;
import static io.github.chutian0610.jolt.gen.Emit.withStack;
import static io.github.chutian0610.jolt.gen.Emit.TOKEN_INTERNAL;
import static io.github.chutian0610.jolt.gen.Emit.NODE;
import static io.github.chutian0610.jolt.gen.Emit.NODE_GET_DESC;
import static io.github.chutian0610.jolt.gen.Emit.NODE_TYPE_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TAG_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TAG_INTERNAL;
import static io.github.chutian0610.jolt.gen.Emit.TO_VALUE_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TYPED_BINDER_FIELD_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TYPE_FIELD_PREFIX;
import static io.github.chutian0610.jolt.gen.Emit.emitBindThrow;
import static io.github.chutian0610.jolt.gen.Emit.emitConcat;
import static io.github.chutian0610.jolt.gen.Emit.emitEnumRead;
import static io.github.chutian0610.jolt.gen.Emit.emitNumberRead;
import static io.github.chutian0610.jolt.gen.Emit.emitPath;
import static io.github.chutian0610.jolt.gen.Emit.emitValuePath;
import static io.github.chutian0610.jolt.gen.Emit.internal;
import static io.github.chutian0610.jolt.gen.Emit.kindOf;
import static io.github.chutian0610.jolt.gen.Emit.rawOf;
import static io.github.chutian0610.jolt.gen.Emit.appendObject;
import static io.github.chutian0610.jolt.gen.Emit.appendText;

/**
 * The M5 POJO specialization: the same straight-line treatment as
 * {@link RecordSpecializer} for setter/getter targets — direct invokes with baked property
 * names, inlined strict scalar reads, cached child-binder fields, and the O(1) baked name
 * set for fail-on-unknown.
 *
 * <p>Semantics mirror {@code GenBinderBase.parsePojo}/{@code writeFields} exactly: a missing
 * key skips the setter (all component kinds), JSON null binds null for reference parameters
 * and throws for primitive ones, and the direct invokes are wrapped so a throwing setter
 * surfaces as "setter rejected the bound value: &lt;cause&gt;" and a throwing getter as
 * "cannot read property" — each catch covers ONLY the invoke (the value is emitted before
 * the try region), so binding errors raised while producing the value keep their own paths
 * and messages. Construction failure surfaces as "cannot instantiate".
 */
final class PojoSpecializer {

    private PojoSpecializer() {}

    /**
     * The POJO direct-read capability: a public concrete class with a public no-arg
     * constructor whose setter parameter types are all emittable (the shared kind check —
     * public reference component types only; the emitted bytecode names them). Records are
     * the record specializer's targets, never POJO-capable.
     */
    static boolean isDirectCapable(Class<?> type) {
        if (type.isRecord() || !java.lang.reflect.Modifier.isPublic(type.getModifiers())
                || type.isInterface()
                || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        try {
            type.getConstructor();
        } catch (NoSuchMethodException noPublicNoArg) {
            return false;
        }
        for (Method setter : type.getMethods()) {
            if (setter.getName().startsWith("set") && setter.getReturnType() == void.class
                    && setter.getParameterCount() == 1
                    && java.lang.reflect.Modifier.isPublic(setter.getModifiers())
                    && !RecordSpecializer.kindEmittable(setter.getParameterTypes()[0])) {
                return false;
            }
        }
        return true;
    }

    /** The component type the i-th setter binds (the full generic form for Bind.toValue). */
    private static java.lang.reflect.Type componentType(GenPlan<?> plan, int i) {
        return plan.setters.get(i).getGenericParameterTypes()[0];
    }

    /** setX → x — the JSON key, derived once at generation time and baked as a constant. */
    private static String setterKey(Method setter) {
        String mn = setter.getName();
        return Character.toLowerCase(mn.charAt(3)) + mn.substring(4);
    }

    /** getX/isX → x/boolean-flag property — derived once at generation time. */
    static String propertyName(String accessorName) {
        int prefix = accessorName.startsWith("is") ? 2 : 3;
        if (accessorName.length() <= prefix) {
            return accessorName;
        }
        return Character.toLowerCase(accessorName.charAt(prefix))
                + accessorName.substring(prefix + 1);
    }

    /** Adds the specialized fields and straight-line overrides to the generated class. */
    static DynamicType.Builder<?> apply(DynamicType.Builder<?> builder, String generatedName,
                                        GenPlan<?> plan) {
        String self = generatedName.replace('.', '/');
        builder = builder.defineField(RecordSpecializer.NAME_SET_FIELD, java.util.Set.class,
                Visibility.PUBLIC);
        for (int i = 0; i < plan.setters.size(); i++) {
            Class<?> raw = rawOf(componentType(plan, i));
            switch (kindOf(raw)) {
                case CHILD -> builder = builder.defineField(CHILD_FIELD_PREFIX + i,
                        TypedBinder.class, Visibility.PUBLIC);
                case GENERIC -> builder = builder.defineField(TYPE_FIELD_PREFIX + i,
                        java.lang.reflect.Type.class, Visibility.PUBLIC);
                default -> { /* scalars bake no fields */ }
            }
        }
        builder = builder
                .defineMethod("parse", Object.class, Visibility.PUBLIC)
                .withParameters(Node.class, String.class, BindConfig.class)
                .intercept(new Implementation.Simple(new ParseBody(self, plan)))
                .defineMethod("write", void.class, Visibility.PUBLIC)
                .withParameters(Object.class, JoltWriter.class)
                .intercept(new Implementation.Simple(new WriteBody(self, plan, true)))
                .defineMethod("writeFields", void.class, Visibility.PUBLIC)
                .withParameters(Object.class, JoltWriter.class, String.class)
                .intercept(new Implementation.Simple(new WriteBody(self, plan, false)))
                .defineMethod(CheckUnknownBody.METHOD, void.class, Visibility.PUBLIC)
                .withParameters(Node.class, java.util.Set.class, String.class)
                .intercept(new Implementation.Simple(new CheckUnknownBody(self)))
                .defineMethod(RecordSpecializer.INIT_CHILDREN_METHOD, void.class, Visibility.PUBLIC)
                .intercept(new Implementation.Simple(new InitChildrenBody(self, plan)));
        if (isDirectCapable(plan.type)) {
            // the direct forms: the document root and the nested body (the caller consumed
            // this value's START_OBJECT), plus the capability flag the facade consults
            builder = builder
                    .defineMethod(RecordSpecializer.PARSE_TOKENS_METHOD, Object.class,
                            Visibility.PUBLIC)
                    .withParameters(io.github.chutian0610.jolt.JoltParser.class, BindConfig.class)
                    .intercept(new Implementation.Simple(
                            new ParseTokensBody(self, plan, false)))
                    .defineMethod(RecordSpecializer.PARSE_TOKENS_BODY_METHOD, Object.class,
                            Visibility.PUBLIC)
                    .withParameters(io.github.chutian0610.jolt.JoltParser.class, BindConfig.class)
                    .intercept(new Implementation.Simple(
                            new ParseTokensBody(self, plan, true)))
                    .defineMethod(RecordSpecializer.HAS_DIRECT_READ_METHOD, boolean.class,
                            Visibility.PUBLIC)
                    .intercept(new Implementation.Simple(
                            (mv, context, method) -> {
                                mv.visitInsn(Opcodes.ICONST_1);
                                mv.visitInsn(Opcodes.IRETURN);
                                return new ByteCodeAppender.Size(1, 0);
                            }));
        }
        return builder;
    }

    /** Bakes the name set and the per-component generic Types (no constructor handle needed —
     * the parse body constructs with NEW + INVOKESPECIAL on the verified public no-arg one). */
    static void bakeFields(GenBinderBase binder, GenPlan<?> plan) {
        try {
            field(binder, RecordSpecializer.NAME_SET_FIELD).set(binder, plan.nameSet);
            for (int i = 0; i < plan.setters.size(); i++) {
                if (kindOf(rawOf(componentType(plan, i))) == Emit.Kind.GENERIC) {
                    field(binder, TYPE_FIELD_PREFIX + i).set(binder, componentType(plan, i));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("generated binder could not bake its fields", e);
        }
    }

    private static java.lang.reflect.Field field(Object binder, String name)
            throws NoSuchFieldException {
        return binder.getClass().getDeclaredField(name);
    }

    /**
     * Straight-line POJO parse: NEW + INVOKESPECIAL construction, per-setter inlined strict
     * reads (missing key skips the setter), fail-on-unknown as one virtual call.
     * Locals: 0 this | 1 node | 2 path | 3 config | 4 instance | 5 field | 6 enum text
     * | 7 wrapped exception | 8 enum value.
     */
    private static final class ParseBody implements ByteCodeAppender {

        private final String self;
        private final GenPlan<?> plan;
        private final String pojoInternal;

        ParseBody(String self, GenPlan<?> plan) {
            this.self = self;
            this.plan = plan;
            this.pojoInternal = internal(plan.type);
        }

        @Override
        public Size apply(MethodVisitor mv, Implementation.Context context,
                          MethodDescription instrumentedMethod) {
            Emit.FrameTracker slots = new Emit.FrameTracker(self, 9);
            slots.set(1, NODE);
            slots.set(2, "java/lang/String");
            slots.set(3, internal(BindConfig.class));

            // construction: the verified public no-arg constructor, invoked directly. The
            // catch frame declares the PRE-construction shape (slot 4 still unwritten).
            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label ctorHandler = new Label();
            Label constructed = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, ctorHandler, "java/lang/RuntimeException");
            // the constructor may throw before the instance slot is written — the handler
            // frame declares the pre-construction shape
            Object[] ctorHandlerShape = slots.snapshot();
            mv.visitLabel(tryStart);
            mv.visitTypeInsn(Opcodes.NEW, pojoInternal);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, pojoInternal, "<init>", "()V", false);
            mv.visitLabel(tryEnd);
            mv.visitVarInsn(Opcodes.ASTORE, 4);
            slots.set(4, pojoInternal);
            mv.visitJumpInsn(Opcodes.GOTO, constructed);
            mv.visitLabel(ctorHandler);
            // a direct invoke surfaces the constructor's own exception (no
            // InvocationTargetException wrapper) — discard it and wrap like the reflective path
            slots.emitFrom(mv, ctorHandlerShape, "java/lang/RuntimeException");
            mv.visitInsn(Opcodes.POP);
            emitBindThrow(mv,
                    () -> mv.visitLdcInsn("cannot instantiate " + plan.type.getName()),
                    () -> mv.visitVarInsn(Opcodes.ALOAD, 2));
            mv.visitLabel(constructed);
            slots.emit(mv);

            for (int i = 0; i < plan.setters.size(); i++) {
                emitComponent(mv, slots, i);
            }

            // fail-on-unknown: one branch when off, one virtual call when on
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal(BindConfig.class),
                    "failOnUnknown", "()Z", false);
            Label skipCheck = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, skipCheck);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, self, RecordSpecializer.NAME_SET_FIELD,
                    "Ljava/util/Set;");
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, CheckUnknownBody.METHOD,
                    CheckUnknownBody.DESCRIPTOR, false);
            mv.visitLabel(skipCheck);
            slots.emit(mv);

            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitInsn(Opcodes.ARETURN);
            return new Size(16, 9);
        }

        private void emitComponent(MethodVisitor mv, Emit.FrameTracker slots, int i) {
            Method setter = plan.setters.get(i);
            String name = setterKey(setter);
            // Node f = node.get(key)
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitLdcInsn(name);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "get", NODE_GET_DESC, false);
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            slots.set(5, NODE);
            Class<?> raw = rawOf(componentType(plan, i));
            boolean paramPrimitive = setter.getParameterTypes()[0].isPrimitive();
            switch (kindOf(raw)) {
                case STRING -> emitScalar(mv, slots, i, setter, name, "STRING", "a string",
                        () -> {
                            mv.visitVarInsn(Opcodes.ALOAD, 5);
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asText",
                                    "()Ljava/lang/String;", false);
                        });
                case BOOLEAN -> emitScalar(mv, slots, i, setter, name, "BOOLEAN", "a boolean",
                        paramPrimitive
                                ? () -> {
                                    mv.visitVarInsn(Opcodes.ALOAD, 5);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asBoolean",
                                            "()Z", false);
                                }
                                : () -> {
                                    mv.visitVarInsn(Opcodes.ALOAD, 5);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "asBoolean",
                                            "()Z", false);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean",
                                            "valueOf", "(Z)Ljava/lang/Boolean;", false);
                                });
                // the receiver is preloaded beneath the value — every frame inside the
                // numeric read declares it as the stack base
                case NUMBER -> emitScalar(mv, slots, i, setter, name, "NUMBER", "a number",
                        () -> emitNumberRead(mv, slots, raw, name,
                                new Object[]{pojoInternal}, paramPrimitive));
                case ENUM -> emitScalar(mv, slots, i, setter, name, "STRING", "a string",
                        () -> emitEnumRead(mv, slots, raw, name, new Object[0]));
                case CHILD -> emitChild(mv, slots, i, setter, raw);
                case GENERIC -> emitGeneric(mv, slots, i, setter);
            }
        }

        /**
         * The POJO scalar skeleton: missing key skips the setter entirely; JSON null throws
         * for primitive parameters and binds null for reference ones; then the strict tag
         * check and the inlined read (outside the invoke's try region, so binding errors keep
         * their own messages), and the guarded setter invoke.
         */
        private void emitScalar(MethodVisitor mv, Emit.FrameTracker slots, int i, Method setter,
                                String name, String tagName, String wanted,
                                Runnable valueEmit) {
            boolean primitive = rawOf(componentType(plan, i)).isPrimitive();
            boolean isEnum = kindOf(rawOf(componentType(plan, i))) == Emit.Kind.ENUM;
            Object[] gateShape = slots.snapshot();
            String setterDesc = "(" + Type.getType(setter.getParameterTypes()[0]).getDescriptor()
                    + ")V";

            // the skip block exists only for the enum pin; other kinds fall straight to end
            // (two labels with no instruction between them would put two frames on one
            // offset, which is illegal)
            Label nullPath = new Label();
            Label end = new Label();
            Label skip = isEnum ? new Label() : end;
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitJumpInsn(Opcodes.IFNULL, skip);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type", NODE_TYPE_DESC, false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TAG_INTERNAL, "NULL", TAG_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, nullPath);

            Label read = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type", NODE_TYPE_DESC, false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TAG_INTERNAL, tagName, TAG_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, read);
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

            // the receiver is preloaded below the value: primitive parameters land directly
            // on the stack (no boxing round-trip, no swap across category-two values)
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            valueEmit.run();
            invokeSetterOnStackTop(mv, slots, setter, name, setterDesc);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(nullPath);
            slots.emitFrom(mv, gateShape);
            if (primitive) {
                emitBindThrow(mv,
                        () -> mv.visitLdcInsn("JSON null for primitive component "
                                + rawOf(componentType(plan, i)).getSimpleName()),
                        () -> emitValuePath(mv, name));
            } else {
                pinValueSlotsIfEnum(mv, i);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitInsn(Opcodes.ACONST_NULL);
                invokeSetterOnStackTop(mv, slots, setter, name, setterDesc);
                mv.visitJumpInsn(Opcodes.GOTO, end);
            }

            if (isEnum) {
                // the guarded invoke's frames declare the entry shape — no slot7 leak; only
                // the enum text/value slots need their own pin block
                mv.visitLabel(skip);
                slots.emitFrom(mv, gateShape);
                pinValueSlotsIfEnum(mv, i);
            }

            mv.visitLabel(end);
            slots.emit(mv);
        }

        /** Nested record/POJO parameter: cached child-binder field, monomorphic parse call. */
        private void emitChild(MethodVisitor mv, Emit.FrameTracker slots, int i, Method setter,
                               Class<?> raw) {
            Object[] gateShape = slots.snapshot();
            String setterDesc = "(" + Type.getType(setter.getParameterTypes()[0]).getDescriptor()
                    + ")V";
            Label nullPath = new Label();
            Label end = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitJumpInsn(Opcodes.IFNULL, end);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NODE, "type", NODE_TYPE_DESC, false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TAG_INTERNAL, "NULL", TAG_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, nullPath);
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
                        () -> emitValuePath(mv, setterKey(setter)));
                mv.visitLabel(parse);
                slots.emit(mv);
            }
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, self, CHILD_FIELD_PREFIX + i,
                    TYPED_BINDER_FIELD_DESC);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            emitPath(mv, () -> mv.visitVarInsn(Opcodes.ALOAD, 2),
                    () -> mv.visitLdcInsn(setterKey(setter)));
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "io/github/chutian0610/jolt/bind/TypedBinder",
                    "parse",
                    "(Lio/github/chutian0610/jolt/tree/Node;Ljava/lang/String;"
                            + "Lio/github/chutian0610/jolt/bind/BindConfig;)Ljava/lang/Object;",
                    true);
            invokeSetterOnStackTop(mv, slots, setter, setterKey(setter), setterDesc);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(nullPath);
            slots.emitFrom(mv, gateShape);
            mv.visitInsn(Opcodes.ACONST_NULL);
            invokeSetterOnStackTop(mv, slots, setter, setterKey(setter), setterDesc);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(end);
            slots.emit(mv);
        }

        /** List/Map/array/Object parameters: routed through Bind.toValue unchanged. */
        private void emitGeneric(MethodVisitor mv, Emit.FrameTracker slots, int i, Method setter) {
            String setterDesc = "(" + Type.getType(setter.getParameterTypes()[0]).getDescriptor()
                    + ")V";
            Label end = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitJumpInsn(Opcodes.IFNULL, end);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, self, TYPE_FIELD_PREFIX + i,
                    "Ljava/lang/reflect/Type;");
            emitPath(mv, () -> mv.visitVarInsn(Opcodes.ALOAD, 2),
                    () -> mv.visitLdcInsn(setterKey(setter)));
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, BIND_INTERNAL, "toValue", TO_VALUE_DESC,
                    false);
            invokeSetterOnStackTop(mv, slots, setter, setterKey(setter), setterDesc);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(end);
            slots.emit(mv);
        }

        /**
         * Invokes the setter with [receiver, value] ALREADY ON THE STACK: the try region
         * covers only the INVOKEVIRTUAL, so a throwing setter wraps as
         * "setter rejected the bound value: &lt;cause&gt;" while binding errors raised while
         * producing the value were never inside the region.
         */
        private void invokeSetterOnStackTop(MethodVisitor mv, Emit.FrameTracker slots,
                                            Method setter, String name, String setterDesc) {
            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label handler = new Label();
            Label done = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/RuntimeException");
            // both the handler and the join declare the shape the invoke was ENTERED with:
            // the handler stores the exception only to print it and ends in ATHROW, so its
            // slot write terminates there and must not leak into any later frame
            Object[] entryShape = slots.snapshot();
            mv.visitLabel(tryStart);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, pojoInternal, setter.getName(), setterDesc,
                    false);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, done);
            mv.visitLabel(handler);
            // exception entry clears the operand stack: [exception] only
            slots.emitFrom(mv, entryShape, "java/lang/RuntimeException");
            mv.visitVarInsn(Opcodes.ASTORE, 7);
            emitBindThrow(mv,
                    () -> emitConcat(mv, () -> {
                        mv.visitLdcInsn("setter rejected the bound value: ");
                        appendText(mv);
                        mv.visitVarInsn(Opcodes.ALOAD, 7);
                        appendObject(mv);
                    }),
                    () -> emitValuePath(mv, name));
            mv.visitLabel(done);
            slots.emitFrom(mv, entryShape);
        }

        private void pinValueSlotsIfEnum(MethodVisitor mv, int i) {
            if (kindOf(rawOf(componentType(plan, i))) == Emit.Kind.ENUM) {
                // the value path wrote the text/value slots, this path did not — null pins
                // the slot types so the end-join accepts both
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, 6);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, 8);
            }
        }
    }

    /**
     * The POJO direct read — the generated class's token-consuming form, root and nested
     * body variants. The default instance is constructed ONCE at entry (the POJO shape:
     * absent properties stay default; a non-object root binds the default instance and
     * returns without draining), then the field loop runs inline ({@code fieldNameEquals}
     * matching against the baked setter keys), values are produced straight onto the
     * operand stack with the receiver preloaded beneath them (primitives unboxed — no
     * boxing bridge), and each setter invoke is GUARDED: its try region covers only the
     * INVOKEVIRTUAL, so a throwing setter surfaces as the shared
     * {@code DirectSupport.setterRejected} while binding errors raised while producing the
     * value keep their own messages (the M5-T2 discipline). The declaration-order sweep is
     * the shared {@code DirectSupport.sweepPojoErrors}.
     *
     * <p>Locals (compact frame cells): 0 this | 1 parser | 2 config | 3 errors
     * | 4 unknownKey | 5 token | 6 valueToken | 7 throwable | 8 setterThrowable | 9 text
     * | 10 instance. Every branch target carries an explicit stack-map frame; all locals
     * except the instance are default-initialized up front (the construction handler
     * declares the pre-construction shape).
     */
    private static final class ParseTokensBody implements ByteCodeAppender {

        private static final int ERRORS = 3;
        private static final int UNKNOWN = 4;
        private static final int TOKEN = 5;
        private static final int VALUE_TOKEN = 6;
        private static final int THROWABLE = 7;
        private static final int SETTER_THROWABLE = 8;
        private static final int TEXT = 9;
        private static final int INSTANCE = 10;

        private final String self;
        private final GenPlan<?> plan;
        private final String pojoInternal;
        private final boolean nested;

        /** {@code nested} selects the body form: the caller consumed this value's
         * START_OBJECT and owns the path position and the drain. */
        ParseTokensBody(String self, GenPlan<?> plan, boolean nested) {
            this.self = self;
            this.plan = plan;
            this.pojoInternal = internal(plan.type);
            this.nested = nested;
        }

        @Override
        public Size apply(MethodVisitor mv, Implementation.Context context,
                          MethodDescription instrumentedMethod) {
            int n = plan.setters.size();
            Object[] frameLocals = new Object[INSTANCE + 1];
            frameLocals[1] = PARSER;
            frameLocals[2] = internal(BindConfig.class);
            frameLocals[ERRORS] = "[Ljava/lang/Object;";
            frameLocals[UNKNOWN] = "java/lang/String";
            frameLocals[TOKEN] = TOKEN_INTERNAL;
            frameLocals[VALUE_TOKEN] = TOKEN_INTERNAL;
            frameLocals[THROWABLE] = "java/lang/Throwable";
            frameLocals[SETTER_THROWABLE] = "java/lang/Throwable";
            frameLocals[TEXT] = "java/lang/String";
            // the instance cell starts TOP (the construction handler declares the
            // pre-construction shape); the type lands on it only after the
            // pre-construction snapshot below — the tracker holds the array by
            // reference, so order matters
            frameLocals[INSTANCE] = Opcodes.TOP;
            Emit.FrameTracker slots = new Emit.FrameTracker(self, frameLocals);

            Label loopHead = new Label();
            Label sweep = new Label();
            Label drainHead = new Label();
            Label drained = new Label();

            // entry: the root form resets the shared path; both read the root token and
            // construct the default instance (the POJO construction is unconditional —
            // absent properties stay default)
            if (!nested) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "resetPath", "()V",
                        false);
            }
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "nextToken",
                    PARSER_NEXT_TOKEN_DESC, true);
            mv.visitVarInsn(Opcodes.ASTORE, TOKEN);
            // uniform locals: every non-instance slot is default-initialized up front, so
            // every frame after this point declares the same locals array
            nullStore(mv, ERRORS);
            nullStore(mv, UNKNOWN);
            nullStore(mv, VALUE_TOKEN);
            nullStore(mv, THROWABLE);
            nullStore(mv, SETTER_THROWABLE);
            nullStore(mv, TEXT);
            // the construction handler declares the PRE-construction shape (the instance
            // cell still TOP); after the snapshot the cell becomes the instance's type
            Object[] ctorHandlerShape = slots.snapshot();
            slots.set(INSTANCE, pojoInternal);
            Label ctorStart = new Label();
            Label ctorEnd = new Label();
            Label ctorHandler = new Label();
            Label ctorDone = new Label();
            mv.visitTryCatchBlock(ctorStart, ctorEnd, ctorHandler, "java/lang/RuntimeException");
            mv.visitLabel(ctorStart);
            mv.visitTypeInsn(Opcodes.NEW, pojoInternal);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, pojoInternal, "<init>", "()V", false);
            mv.visitLabel(ctorEnd);
            mv.visitVarInsn(Opcodes.ASTORE, INSTANCE);
            mv.visitJumpInsn(Opcodes.GOTO, ctorDone);
            mv.visitLabel(ctorHandler);
            // a direct invoke surfaces the constructor's own exception (no
            // InvocationTargetException wrapper) — wrap like the reflective path
            slots.emitFrom(mv, ctorHandlerShape, "java/lang/RuntimeException");
            mv.visitLdcInsn(plan.type.getName());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "cannotInstantiate",
                    "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitLabel(ctorDone);
            slots.emit(mv);

            // non-object root: the default instance, returned without draining (the
            // reflective arm's shape)
            mv.visitVarInsn(Opcodes.ALOAD, TOKEN);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "START_OBJECT", TOKEN_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, loopHead);
            mv.visitVarInsn(Opcodes.ALOAD, INSTANCE);
            mv.visitInsn(Opcodes.ARETURN);

            // the field loop
            mv.visitLabel(loopHead);
            slots.emit(mv);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "nextToken",
                    PARSER_NEXT_TOKEN_DESC, true);
            mv.visitVarInsn(Opcodes.ASTORE, TOKEN);
            mv.visitVarInsn(Opcodes.ALOAD, TOKEN);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "END_OBJECT", TOKEN_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, sweep);

            // the inline match chain against the baked setter keys
            Label[] matched = new Label[n];
            for (int i = 0; i < n; i++) {
                matched[i] = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitLdcInsn(setterKey(plan.setters.get(i)));
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "fieldNameEquals",
                        PARSER_FIELD_NAME_EQUALS_DESC, true);
                mv.visitJumpInsn(Opcodes.IFNE, matched[i]);
            }
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, UNKNOWN);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "skipOrNoteUnknown",
                    "(Lio/github/chutian0610/jolt/JoltParser;"
                            + "Lio/github/chutian0610/jolt/bind/BindConfig;"
                            + "Ljava/lang/String;)Ljava/lang/String;", false);
            mv.visitVarInsn(Opcodes.ASTORE, UNKNOWN);
            mv.visitJumpInsn(Opcodes.GOTO, loopHead);

            for (int i = 0; i < n; i++) {
                emitComponent(mv, slots, i, matched[i], n, loopHead);
            }

            // the shared declaration-order sweep, then the drain (root form only: the
            // loop-exit token is END_OBJECT only on the object-root path)
            mv.visitLabel(sweep);
            slots.emit(mv);
            mv.visitVarInsn(Opcodes.ALOAD, ERRORS);
            mv.visitVarInsn(Opcodes.ALOAD, UNKNOWN);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "sweepPojoErrors",
                    "([Ljava/lang/Object;Ljava/lang/String;)V", false);
            if (nested) {
                // the body form hands the instance back — the caller owns the cursor and
                // the drain
                mv.visitVarInsn(Opcodes.ALOAD, INSTANCE);
                mv.visitInsn(Opcodes.ARETURN);
            } else {
                // the root form's drain runs exactly when the root was an object (the
                // loop-exit token); tail syntax errors surface unchanged
                mv.visitVarInsn(Opcodes.ALOAD, TOKEN);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "END_OBJECT", TOKEN_DESC);
                mv.visitJumpInsn(Opcodes.IF_ACMPEQ, drainHead);
                mv.visitVarInsn(Opcodes.ALOAD, INSTANCE);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(drainHead);
                slots.emit(mv);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "nextToken",
                        PARSER_NEXT_TOKEN_DESC, true);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "EOF", TOKEN_DESC);
                mv.visitJumpInsn(Opcodes.IF_ACMPEQ, drained);
                mv.visitJumpInsn(Opcodes.GOTO, drainHead);

                mv.visitLabel(drained);
                slots.emit(mv);
                mv.visitVarInsn(Opcodes.ALOAD, INSTANCE);
                mv.visitInsn(Opcodes.ARETURN);
            }

            return new Size(10, INSTANCE + 1);
        }

        /** One matched property: path push, the guarded value read + setter invoke, the
         * shared slot note on failure, the path pop — then back to the field loop. */
        private void emitComponent(MethodVisitor mv, Emit.FrameTracker slots, int i,
                                   Label matched, int n, Label loopHead) {
            mv.visitLabel(matched);
            slots.emit(mv);

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label join = new Label();
            Label handler = new Label();
            mv.visitLdcInsn(setterKey(plan.setters.get(i)));
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "pushName",
                    "(Ljava/lang/String;)V", false);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "nextToken",
                    PARSER_NEXT_TOKEN_DESC, true);
            mv.visitVarInsn(Opcodes.ASTORE, VALUE_TOKEN);

            mv.visitTryCatchBlock(tryStart, tryEnd, handler, BIND_EXCEPTION);
            mv.visitLabel(tryStart);
            emitValueIntoSetter(mv, slots, i, join, tryEnd);
            mv.visitLabel(tryEnd);
            slots.emit(mv);
            mv.visitJumpInsn(Opcodes.GOTO, join);
            mv.visitLabel(handler);
            slots.emit(mv, BIND_EXCEPTION);
            mv.visitVarInsn(Opcodes.ASTORE, THROWABLE);
            mv.visitVarInsn(Opcodes.ALOAD, ERRORS);
            pushIndex(mv, i);
            mv.visitVarInsn(Opcodes.ALOAD, THROWABLE);
            pushIndex(mv, n);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "noteError",
                    "([Ljava/lang/Object;ILio/github/chutian0610/jolt/bind/JsonBindException;"
                            + "I)[Ljava/lang/Object;", false);
            mv.visitVarInsn(Opcodes.ASTORE, ERRORS);

            mv.visitLabel(join);
            slots.emit(mv);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "pop", "()V", false);
            mv.visitJumpInsn(Opcodes.GOTO, loopHead);
        }

        /** The receiver preloaded beneath the value, the JSON-null gate (primitive
         * parameters raise the shared message, reference ones bind setter(null)), the
         * kind-specific read producing the value on the stack, and the guarded invoke. */
        private void emitValueIntoSetter(MethodVisitor mv, Emit.FrameTracker slots, int i,
                                         Label join, Label tryEnd) {
            Method setter = plan.setters.get(i);
            String name = setterKey(setter);
            Class<?> raw = rawOf(componentType(plan, i));
            boolean primParam = setter.getParameterTypes()[0].isPrimitive();
            // the receiver sits UNDER everything this read produces — every frame inside
            // the read declares it as the stack base (the M5 stackPrefix discipline)
            Object[] stackBase = {pojoInternal};
            Label nullEdge = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, INSTANCE);
            mv.visitVarInsn(Opcodes.ALOAD, VALUE_TOKEN);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_NULL", TOKEN_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, nullEdge);
            switch (kindOf(raw)) {
                case STRING -> {
                    requireToken(mv, slots, "VALUE_STRING", "a string", stackBase);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "textValue",
                            PARSER_TEXT_VALUE_DESC, true);
                }
                case BOOLEAN -> {
                    Label trueEdge = new Label();
                    Label falseEdge = new Label();
                    Label valueReady = new Label();
                    mv.visitVarInsn(Opcodes.ALOAD, VALUE_TOKEN);
                    mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_TRUE",
                            TOKEN_DESC);
                    mv.visitJumpInsn(Opcodes.IF_ACMPEQ, trueEdge);
                    mv.visitVarInsn(Opcodes.ALOAD, VALUE_TOKEN);
                    mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_FALSE",
                            TOKEN_DESC);
                    mv.visitJumpInsn(Opcodes.IF_ACMPEQ, falseEdge);
                    raiseTagError(mv, "a boolean");
                    mv.visitLabel(trueEdge);
                    slots.emit(mv, stackBase);
                    emitBoolean(mv, true, primParam);
                    mv.visitJumpInsn(Opcodes.GOTO, valueReady);
                    mv.visitLabel(falseEdge);
                    slots.emit(mv, stackBase);
                    emitBoolean(mv, false, primParam);
                    mv.visitLabel(valueReady);
                    // the join carries the receiver AND the value (the parameter's kind)
                    slots.emit(mv, withStack(stackBase,
                            primParam ? Opcodes.INTEGER : "java/lang/Boolean"));
                }
                case NUMBER -> emitNumberValue(mv, slots, raw, name, primParam, stackBase);
                case ENUM -> emitEnumValue(mv, slots, raw, name, stackBase);
                case CHILD -> emitChildValue(mv, slots, i, raw, stackBase);
                case GENERIC -> emitGenericValue(mv, slots, i, raw, stackBase);
                default -> throw new IllegalStateException(
                        "setter parameter " + name + " is not a direct-read shape");
            }
            invokeSetter(mv, slots, setter, name);
            mv.visitJumpInsn(Opcodes.GOTO, tryEnd);

            mv.visitLabel(nullEdge);
            slots.emit(mv, stackBase);
            if (primParam) {
                mv.visitLdcInsn(raw.getSimpleName());
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "nullForPrimitive",
                        "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
                mv.visitInsn(Opcodes.ATHROW);
                return;
            }
            mv.visitInsn(Opcodes.ACONST_NULL);
            invokeSetter(mv, slots, setter, name);
            // the null path joins the value path explicitly — falling straight into the
            // caller's tryEnd would put the guarded invoke's done frame and the tryEnd
            // frame on ONE offset (the M5-T2 zero-gap rule)
            mv.visitJumpInsn(Opcodes.GOTO, tryEnd);
        }

        private void emitBoolean(MethodVisitor mv, boolean value, boolean primParam) {
            if (primParam) {
                mv.visitInsn(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            } else {
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean",
                        value ? "TRUE" : "FALSE", "Ljava/lang/Boolean;");
            }
        }

        /** The tag gate: the token must carry the expected tag, else the shared rejection.
         * The ok join carries the caller's stack base (the preloaded receiver). */
        private void requireToken(MethodVisitor mv, Emit.FrameTracker slots, String constant,
                                  String wanted, Object[] stackBase) {
            Label ok = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, VALUE_TOKEN);
            mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, constant, TOKEN_DESC);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, ok);
            raiseTagError(mv, wanted);
            mv.visitLabel(ok);
            slots.emit(mv, stackBase);
        }

        /** parser/token/wanted onto the shared tagError, then the throw. */
        private void raiseTagError(MethodVisitor mv, String wanted) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, VALUE_TOKEN);
            mv.visitLdcInsn(wanted);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "tagError",
                    "(Lio/github/chutian0610/jolt/JoltParser;"
                            + "Lio/github/chutian0610/jolt/Token;"
                            + "Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
            mv.visitInsn(Opcodes.ATHROW);
        }

        /** The guarded invoke: [receiver, value] already on the stack; the try region
         * covers ONLY the INVOKEVIRTUAL — a throwing setter raises the shared rejection. */
        private void invokeSetter(MethodVisitor mv, Emit.FrameTracker slots, Method setter,
                                  String name) {
            String setterDesc = "("
                    + Type.getType(setter.getParameterTypes()[0]).getDescriptor() + ")V";
            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label handler = new Label();
            Label done = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/RuntimeException");
            Object[] entryShape = slots.snapshot();
            mv.visitLabel(tryStart);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, pojoInternal, setter.getName(),
                    setterDesc, false);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, done);
            mv.visitLabel(handler);
            slots.emitFrom(mv, entryShape, "java/lang/RuntimeException");
            mv.visitVarInsn(Opcodes.ASTORE, SETTER_THROWABLE);
            mv.visitVarInsn(Opcodes.ALOAD, SETTER_THROWABLE);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "setterRejected",
                    "(Ljava/lang/Throwable;)Lio/github/chutian0610/jolt/bind/JsonBindException;",
                    false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitLabel(done);
            slots.emitFrom(mv, entryShape);
        }

        /** The enum read: the text into the shared TEXT slot, then Enum.valueOf with the
         * unknown-name handler raising the shared message. The value stays on the stack. */
        private void emitEnumValue(MethodVisitor mv, Emit.FrameTracker slots, Class<?> raw,
                                   String name, Object[] stackBase) {
            requireToken(mv, slots, "VALUE_STRING", "a string", stackBase);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "textValue",
                    PARSER_TEXT_VALUE_DESC, true);
            mv.visitVarInsn(Opcodes.ASTORE, TEXT);
            Label tryStart = new Label();
            Label exact = new Label();
            Label lossy = new Label();
            Label done = new Label();
            mv.visitTryCatchBlock(tryStart, exact, lossy, "java/lang/IllegalArgumentException");
            mv.visitLabel(tryStart);
            mv.visitLdcInsn(Type.getType(raw));
            mv.visitVarInsn(Opcodes.ALOAD, TEXT);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                    "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, internal(raw));
            mv.visitLabel(exact);
            mv.visitJumpInsn(Opcodes.GOTO, done);
            mv.visitLabel(lossy);
            // the handler's entry stack is CLEARED — no receiver base here
            slots.emit(mv, "java/lang/IllegalArgumentException");
            mv.visitInsn(Opcodes.POP);
            mv.visitVarInsn(Opcodes.ALOAD, TEXT);
            mv.visitLdcInsn(raw.getSimpleName());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "unknownEnumName",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/RuntimeException;",
                    false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitLabel(done);
            // the join carries the receiver AND the value
            slots.emit(mv, withStack(stackBase, internal(raw)));
        }

        /** The nested record/POJO value: the child's body on the SAME parser stream — the
         * generation-time dispatch (monomorphic body call, or the reflective door). */
        private void emitChildValue(MethodVisitor mv, Emit.FrameTracker slots, int i,
                                    Class<?> raw, Object[] stackBase) {
            requireToken(mv, slots, "START_OBJECT", "an object", stackBase);
            if (raw.isRecord() ? RecordSpecializer.isDirectCapable(raw)
                    : isDirectCapable(raw)) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, self, CHILD_FIELD_PREFIX + i,
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
        }

        /** Collections/maps/untyped: the shared Plan-driven read through the door — ONE
         * call for the whole value; the element Type is the parent's baked field. */
        private void emitGenericValue(MethodVisitor mv, Emit.FrameTracker slots, int i,
                                      Class<?> raw, Object[] stackBase) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, VALUE_TOKEN);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, self, TYPE_FIELD_PREFIX + i,
                    "Ljava/lang/reflect/Type;");
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "readValue",
                    "(Lio/github/chutian0610/jolt/JoltParser;"
                            + "Lio/github/chutian0610/jolt/Token;"
                            + "Ljava/lang/reflect/Type;"
                            + "Lio/github/chutian0610/jolt/bind/BindConfig;"
                            + ")Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, internal(raw));
        }

        /** The numeric family with the value LEFT ON THE STACK (the setter parameter's
         * own kind — primitive stays unboxed). */
        private void emitNumberValue(MethodVisitor mv, Emit.FrameTracker slots, Class<?> raw,
                                     String name, boolean primParam, Object[] stackBase) {
            switch (raw.getName()) {
                case "float", "java.lang.Float" -> {
                    requireNumber(mv);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                            PARSER_BIG_DECIMAL_DESC, true);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                            "floatValue", "()F", false);
                    if (!primParam) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                                "(F)Ljava/lang/Float;", false);
                    }
                }
                case "double", "java.lang.Double" -> {
                    requireNumber(mv);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                            PARSER_BIG_DECIMAL_DESC, true);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                            "doubleValue", "()D", false);
                    if (!primParam) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                                "(D)Ljava/lang/Double;", false);
                    }
                }
                case "java.math.BigDecimal" -> {
                    requireNumber(mv);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                            PARSER_BIG_DECIMAL_DESC, true);
                }
                case "java.math.BigInteger" -> {
                    requireNumber(mv);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                            PARSER_BIG_DECIMAL_DESC, true);
                    Label tryStart = new Label();
                    Label exact = new Label();
                    Label lossy = new Label();
                    Label done = new Label();
                    mv.visitTryCatchBlock(tryStart, exact, lossy,
                            "java/lang/ArithmeticException");
                    mv.visitLabel(tryStart);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                            "toBigIntegerExact", "()Ljava/math/BigInteger;", false);
                    mv.visitLabel(exact);
                    mv.visitJumpInsn(Opcodes.GOTO, done);
                    mv.visitLabel(lossy);
                    // the handler's entry stack is CLEARED — no receiver base here
                    slots.emit(mv, "java/lang/ArithmeticException");
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "notWholeNumber",
                            "()Ljava/lang/RuntimeException;", false);
                    mv.visitInsn(Opcodes.ATHROW);
                    mv.visitLabel(done);
                    // the join carries the receiver AND the value
                    slots.emit(mv, withStack(stackBase, "java/math/BigInteger"));
                }
                default -> emitIntegralValue(mv, slots, raw, name, primParam, stackBase);
            }
        }

        /** requireNumber(parser, token) through the shared helper. */
        private void requireNumber(MethodVisitor mv) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, VALUE_TOKEN);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "requireNumber",
                    "(Lio/github/chutian0610/jolt/JoltParser;"
                            + "Lio/github/chutian0610/jolt/Token;)V", false);
        }

        /** The integral read with the value left on the stack: the shared checked long
         * read, then the all-stack bound dance (each throw edge declares its own depth). */
        private void emitIntegralValue(MethodVisitor mv, Emit.FrameTracker slots, Class<?> raw,
                                       String name, boolean primParam, Object[] stackBase) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, VALUE_TOKEN);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "readLongChecked",
                    "(Lio/github/chutian0610/jolt/JoltParser;"
                            + "Lio/github/chutian0610/jolt/Token;)J", false);
            long bound = switch (raw.getName()) {
                case "byte", "java.lang.Byte" -> Byte.MAX_VALUE;
                case "short", "java.lang.Short" -> Short.MAX_VALUE;
                case "int", "java.lang.Integer" -> Integer.MAX_VALUE;
                default -> Long.MAX_VALUE;
            };
            if (bound == Long.MAX_VALUE) {
                if (!primParam) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                            "(J)Ljava/lang/Long;", false);
                }
                return;
            }
            Label overFirst = new Label();
            Label overSecond = new Label();
            Label inRange = new Label();
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
            slots.emit(mv, withStack(stackBase, Opcodes.LONG, Opcodes.LONG));
            mv.visitLdcInsn(raw.getSimpleName());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "boundError",
                    "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitLabel(overSecond);
            slots.emit(mv, withStack(stackBase, Opcodes.LONG));
            mv.visitLdcInsn(raw.getSimpleName());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "boundError",
                    "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitLabel(inRange);
            slots.emit(mv, withStack(stackBase, Opcodes.LONG));
            mv.visitInsn(Opcodes.L2I);
            switch (raw.getName()) {
                case "byte", "java.lang.Byte" -> {
                    mv.visitInsn(Opcodes.I2B);
                    if (!primParam) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
                                "(B)Ljava/lang/Byte;", false);
                    }
                }
                case "short", "java.lang.Short" -> {
                    mv.visitInsn(Opcodes.I2S);
                    if (!primParam) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
                                "(S)Ljava/lang/Short;", false);
                    }
                }
                default -> {
                    if (!primParam) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer",
                                "valueOf", "(I)Ljava/lang/Integer;", false);
                    }
                }
            }
        }

        private static void nullStore(MethodVisitor mv, int slot) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, slot);
        }
    }

    /**
     * Straight-line POJO write: getter invoke with the baked property name, wrapped in the
     * reflective-path catch ("cannot read property" — no cause, the base class prints none),
     * then Bind.writeTo recursion. Braces variant is the full-document {@code write}; the
     * other is {@code writeFields}. Locals: 0 this | 1 value | 2 writer | 3 path | 4 field.
     */
    private static final class WriteBody implements ByteCodeAppender {

        private final String self;
        private final GenPlan<?> plan;
        private final boolean braces;

        WriteBody(String self, GenPlan<?> plan, boolean braces) {
            this.self = self;
            this.plan = plan;
            this.braces = braces;
        }

        @Override
        public Size apply(MethodVisitor mv, Implementation.Context context,
                          MethodDescription instrumentedMethod) {
            String pojoInternal = internal(plan.type);
            int locals = 5; // 0 this | 1 value | 2 writer | 3 path (writeFields) | 4 field value
            if (braces) {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Emit.WRITER, "startObject",
                        Emit.WRITER_START_DESC, false);
                mv.visitInsn(Opcodes.POP);
            }
            Emit.FrameTracker slots = new Emit.FrameTracker(self, locals);
            slots.set(1, "java/lang/Object"); // the parameter's declared type — CHECKCAST
            // narrows the operand stack only, never the local slot
            slots.set(2, Emit.WRITER);
            if (!braces) {
                slots.set(3, "java/lang/String");
            }
            for (int i = 0; i < plan.accessors.length; i++) {
                Method getter = plan.accessors[i];
                String name = propertyName(getter.getName());
                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label handler = new Label();
                Label next = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/RuntimeException");
                // the exception may arrive BEFORE the value slot is rewritten — its frame
                // declares the shape the try region was entered with
                Object[] handlerShape = slots.snapshot();
                mv.visitLabel(tryStart);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitTypeInsn(Opcodes.CHECKCAST, pojoInternal);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, pojoInternal, getter.getName(),
                        Type.getMethodDescriptor(getter), false);
                Class<?> returnType = getter.getReturnType();
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
                    slots.set(4, boxed);
                } else {
                    slots.set(4, Emit.internal(returnType));
                }
                mv.visitVarInsn(Opcodes.ASTORE, 4);
                mv.visitLabel(tryEnd);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Emit.WRITER, "fieldName",
                        Emit.WRITER_FIELD_NAME_DESC, false);
                mv.visitInsn(Opcodes.POP);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                emitPath(mv, braces
                        ? () -> mv.visitLdcInsn("$")
                        : () -> mv.visitVarInsn(Opcodes.ALOAD, 3),
                        () -> mv.visitLdcInsn(name));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, BIND_INTERNAL, "writeTo",
                        Emit.WRITE_TO_DESC, false);
                mv.visitJumpInsn(Opcodes.GOTO, next);
                mv.visitLabel(handler);
                // exception entry clears the operand stack: [exception] only
                slots.emitFrom(mv, handlerShape, "java/lang/RuntimeException");
                mv.visitInsn(Opcodes.POP);
                emitBindThrow(mv,
                        () -> mv.visitLdcInsn("cannot read property " + name),
                        () -> {
                            if (braces) {
                                mv.visitLdcInsn("$");
                            } else {
                                mv.visitVarInsn(Opcodes.ALOAD, 3);
                            }
                        });
                mv.visitLabel(next);
                slots.emit(mv);
            }
            if (braces) {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Emit.WRITER, "endObject",
                        Emit.WRITER_START_DESC, false);
                mv.visitInsn(Opcodes.POP);
            }
            mv.visitInsn(Opcodes.RETURN);
            return new Size(16, 5);
        }
    }

    /** One-time baking: child binders for nested record/POJO setter parameters. */
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
            for (int i = 0; i < plan.setters.size(); i++) {
                Class<?> raw = rawOf(componentType(plan, i));
                if (kindOf(raw) == Emit.Kind.CHILD) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitLdcInsn(Type.getType(raw));
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, Emit.GENERATED_INTERNAL, "forType",
                            "(Ljava/lang/Class;)" + TYPED_BINDER_FIELD_DESC, false);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, self, CHILD_FIELD_PREFIX + i,
                            TYPED_BINDER_FIELD_DESC);
                }
            }
            mv.visitInsn(Opcodes.RETURN);
            return new Size(6, 1);
        }
    }
}
