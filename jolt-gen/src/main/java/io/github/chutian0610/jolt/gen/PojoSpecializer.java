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

import static io.github.chutian0610.jolt.gen.Emit.BIND_INTERNAL;
import static io.github.chutian0610.jolt.gen.Emit.CHILD_FIELD_PREFIX;
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
        return builder
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
