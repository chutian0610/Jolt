package io.github.chutian0610.jolt.gen;

import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.lang.reflect.Modifier;

import static io.github.chutian0610.jolt.gen.Emit.DIRECT_SUPPORT;
import static io.github.chutian0610.jolt.gen.Emit.GEN_BASE;
import static io.github.chutian0610.jolt.gen.Emit.GEN_BASE_PARSE_TOKENS_BODY_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_BIG_DECIMAL_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_NEXT_TOKEN_DESC;
import static io.github.chutian0610.jolt.gen.Emit.PARSER_TEXT_VALUE_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TOKEN_DESC;
import static io.github.chutian0610.jolt.gen.Emit.TOKEN_INTERNAL;
import static io.github.chutian0610.jolt.gen.Emit.TYPED_BINDER_FIELD_DESC;
import static io.github.chutian0610.jolt.gen.Emit.internal;
import static io.github.chutian0610.jolt.gen.Emit.kindOf;
import static io.github.chutian0610.jolt.gen.Emit.pushIndex;
import static io.github.chutian0610.jolt.gen.Emit.withStack;

/**
 * The M8 collection-loop inlining (#57, lever #2): the element loop and the element reads
 * emitted INTO the parent binder body — no per-value door call, no per-element plan switch,
 * record/POJO elements through the monomorphic child body (the reflective element bodies'
 * args {@code Object[]} — the M7 elimination's leftovers — gone). The decision is made per
 * component at generation time: a shape {@link #decompose} can decompose inlines; everything
 * else (untyped/natural elements, wildcards, non-String map keys, polymorphic, non-public or
 * non-capable element classes, primitive-component arrays — those keep the door by the #60
 * defect's contract) stays with the reflective {@code readValue}, which remains the one home
 * for delegated reads.
 *
 * <p>Semantics mirror {@code DirectBinder.readList/readMap/readArray} gate by gate: the same
 * tag messages, the same index/key path segments (map keys materialize — they are data), the
 * same pooled array scratch (grow at {@code buffer.length}, null-out, return), and element
 * values boxed Objects exactly as the door's reads produce them. There are NO in-loop
 * handlers: an element failure propagates to the enclosing component read's handler, which
 * recovers the cursor and the carrier in one place ({@code DirectSupport.recoverCollection}
 * — the door's drain-and-finally, mirrored for any nesting depth, #61). The element readers
 * deliberately mirror the POJO specializer's value-on-stack emitters (the M5/M7 parity
 * discipline keeps one emitter family per form; folding them is a post-M8 refactor).
 */
final class InlineCollections {

    private InlineCollections() {}

    /** Scratch cells per loop depth: a collection/map/buffer ref, an index/length int, and
     * a key/scratch-array ref. All width-1, so the cell index equals the slot offset. */
    static final int CELLS_PER_DEPTH = 3;

    /**
     * The emitting body's slot layout — the record and POJO bodies lay their locals out
     * differently, so the loops take them as parameters. {@code stackBase} is the operand
     * stack beneath everything the read produces (the POJO body's preloaded receiver); the
     * loops' frames all declare it, and error edges never return through them.
     */
    record Body(String self, int valueToken, int text, int carrier, int scratchSlot,
                Emit.FrameTracker slots) {

        int refA(int depth) {
            return scratchSlot + depth * CELLS_PER_DEPTH;
        }

        int intA(int depth) {
            return refA(depth) + 1;
        }

        int refB(int depth) {
            return refA(depth) + 2;
        }
    }

    /** A generation-time decomposition of one collection component. */
    sealed interface Shape permits Scalar, Enumeration, Child, ListOf, MapOf, ArrayOf {
    }

    /** A boxed scalar element ({@code List<Integer>}, {@code List<BigDecimal>}, …). */
    record Scalar(Class<?> raw) implements Shape {
    }

    /** An enum element (public — the emitted {@code Enum.valueOf} LDCs the class). */
    record Enumeration(Class<?> raw) implements Shape {
    }

    /** A record/POJO element with its own direct body (monomorphic child call in the loop). */
    record Child(Class<?> raw) implements Shape {
    }

    record ListOf(Shape element) implements Shape {
    }

    /** String keys only — a non-String key type keeps the door (its shared rejection). */
    record MapOf(Shape value) implements Shape {
    }

    /** Reference-component arrays ({@code String[]}, {@code Item[]}, {@code List<X>[]}). */
    record ArrayOf(Shape component, Class<?> componentClass) implements Shape {
    }

    /** The component's inline shape, or null when the whole value keeps the door. */
    static Shape decompose(java.lang.reflect.Type type) {
        Shape shape = elementOf(type);
        return shape instanceof ListOf || shape instanceof MapOf || shape instanceof ArrayOf
                ? shape
                : null;
    }

    /** The loop nesting this shape emits (a flat {@code List<X>} is depth 1). */
    static int depthOf(Shape shape) {
        if (shape instanceof ListOf list) {
            return 1 + depthOf(list.element());
        }
        if (shape instanceof MapOf map) {
            return 1 + depthOf(map.value());
        }
        if (shape instanceof ArrayOf array) {
            return 1 + depthOf(array.component());
        }
        return 0;
    }

    /** The single child class reachable anywhere in the shape, or null. */
    static Class<?> childOf(Shape shape) {
        if (shape instanceof Child child) {
            return child.raw();
        }
        if (shape instanceof ListOf list) {
            return childOf(list.element());
        }
        if (shape instanceof MapOf map) {
            return childOf(map.value());
        }
        if (shape instanceof ArrayOf array) {
            return childOf(array.component());
        }
        return null;
    }

    private static Shape elementOf(java.lang.reflect.Type type) {
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
            if (raw == java.util.List.class) {
                Shape element = elementOf(args[0]);
                return element == null ? null : new ListOf(element);
            }
            if (raw == java.util.Map.class && args[0] == String.class) {
                Shape value = elementOf(args[1]);
                return value == null ? null : new MapOf(value);
            }
            return null;
        }
        if (type instanceof java.lang.reflect.GenericArrayType genericArray) {
            return arrayOf(genericArray.getGenericComponentType());
        }
        if (!(type instanceof Class<?> raw)) {
            return null; // wildcards and type variables: the door raises its shared rejection
        }
        if (raw == Object.class) {
            return null; // natural mapping: the door
        }
        if (raw.isArray()) {
            return arrayOf(raw.getComponentType());
        }
        return switch (kindOf(raw)) {
            case STRING, BOOLEAN, NUMBER -> new Scalar(raw);
            case ENUM -> Modifier.isPublic(raw.getModifiers()) ? new Enumeration(raw) : null;
            case CHILD -> childShape(raw);
            case GENERIC -> null;
        };
    }

    private static Shape arrayOf(java.lang.reflect.Type component) {
        Shape shape = elementOf(component);
        if (shape == null || shape instanceof Scalar scalar && scalar.raw().isPrimitive()) {
            // primitive-component arrays keep the door: its #60 build is the contract
            // (and Array.newInstance LDCs / GETSTATICs the component class either way)
            return null;
        }
        Class<?> raw = component instanceof Class<?> c
                ? c
                : (Class<?>) ((java.lang.reflect.ParameterizedType) component).getRawType();
        return new ArrayOf(shape, raw);
    }

    private static Shape childShape(Class<?> raw) {
        if (!Modifier.isPublic(raw.getModifiers())) {
            return null; // the emitted child LDC would break from the generated runtime package
        }
        boolean capable = raw.isRecord()
                ? RecordSpecializer.isDirectCapable(raw)
                : PojoSpecializer.isDirectCapable(raw);
        return capable ? new Child(raw) : null;
    }

    // --- emission -----------------------------------------------------------------------

    /**
     * Emits the whole collection read. Precondition: the body's value-token slot holds this
     * value's first token; postcondition: the built collection sits on the operand stack
     * above {@code stackBase} (as its runtime class — the caller CHECKCASTs).
     */
    static void emit(MethodVisitor mv, Body body, Shape shape, int component,
                     Object[] stackBase) {
        emitLoop(mv, body, shape, component, 0, stackBase);
    }

    private static void emitLoop(MethodVisitor mv, Body body, Shape shape, int component,
                                 int depth, Object[] stackBase) {
        if (shape instanceof ListOf list) {
            emitList(mv, body, list.element(), component, depth, stackBase);
        } else if (shape instanceof MapOf map) {
            emitMap(mv, body, map.value(), component, depth, stackBase);
        } else {
            ArrayOf array = (ArrayOf) shape;
            emitArray(mv, body, array, component, depth, stackBase);
        }
    }

    private static void emitList(MethodVisitor mv, Body body, Shape element, int component,
                                 int depth, Object[] stackBase) {
        int ref = body.refA(depth);
        int idx = body.intA(depth);
        requireToken(mv, body, "START_ARRAY", "an array", stackBase);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, ref);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, idx);
        Label head = new Label();
        Label end = new Label();
        mv.visitLabel(head);
        body.slots().emit(mv, stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "nextToken",
                PARSER_NEXT_TOKEN_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, body.valueToken());
        mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "END_ARRAY", TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, end);
        mv.visitVarInsn(Opcodes.ALOAD, body.carrier());
        mv.visitVarInsn(Opcodes.ILOAD, idx);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "pushIndex",
                "(Ljava/lang/Object;I)V", false);
        emitElement(mv, body, element, component, depth + 1, stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, ref);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/ArrayList");
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add",
                "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, body.carrier());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "pop",
                "(Ljava/lang/Object;)V", false);
        mv.visitIincInsn(idx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, head);
        mv.visitLabel(end);
        body.slots().emit(mv, stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, ref);
    }

    private static void emitMap(MethodVisitor mv, Body body, Shape value, int component,
                                int depth, Object[] stackBase) {
        int ref = body.refA(depth);
        int key = body.refB(depth);
        requireToken(mv, body, "START_OBJECT", "an object", stackBase);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashMap");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, ref);
        Label head = new Label();
        Label end = new Label();
        mv.visitLabel(head);
        body.slots().emit(mv, stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "nextToken",
                PARSER_NEXT_TOKEN_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, body.valueToken());
        mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "END_OBJECT", TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, end);
        // the key materializes — map keys are data (the reflective readMap's contract)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "textValue",
                PARSER_TEXT_VALUE_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, key);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "nextToken",
                PARSER_NEXT_TOKEN_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, body.valueToken());
        mv.visitVarInsn(Opcodes.ALOAD, body.carrier());
        mv.visitVarInsn(Opcodes.ALOAD, key);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "pushName",
                "(Ljava/lang/Object;Ljava/lang/String;)V", false);
        emitElement(mv, body, value, component, depth + 1, stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, ref);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/LinkedHashMap");
        mv.visitInsn(Opcodes.SWAP);
        mv.visitVarInsn(Opcodes.ALOAD, key);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, body.carrier());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "pop",
                "(Ljava/lang/Object;)V", false);
        mv.visitJumpInsn(Opcodes.GOTO, head);
        mv.visitLabel(end);
        body.slots().emit(mv, stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, ref);
    }

    private static void emitArray(MethodVisitor mv, Body body, ArrayOf array, int component,
                                  int depth, Object[] stackBase) {
        int buffer = body.refA(depth);
        int len = body.intA(depth);
        int scratch = body.refB(depth);
        requireToken(mv, body, "START_ARRAY", "an array", stackBase);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "takeArrayScratch",
                "()[Ljava/lang/Object;", false);
        mv.visitVarInsn(Opcodes.ASTORE, buffer);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, len);
        Label head = new Label();
        Label end = new Label();
        mv.visitLabel(head);
        body.slots().emit(mv, stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "nextToken",
                PARSER_NEXT_TOKEN_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, body.valueToken());
        mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "END_ARRAY", TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, end);
        mv.visitVarInsn(Opcodes.ALOAD, body.carrier());
        mv.visitVarInsn(Opcodes.ILOAD, len);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "pushIndex",
                "(Ljava/lang/Object;I)V", false);
        // the pooled scratch grows exactly like the door's readArray:
        // if (len == buffer.length) { grown = new Object[len * 2]; copy; buffer = grown; }
        Label grown = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, len);
        mv.visitVarInsn(Opcodes.ALOAD, buffer);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, grown);
        mv.visitVarInsn(Opcodes.ILOAD, len);
        pushIndex(mv, 2);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitVarInsn(Opcodes.ASTORE, scratch);
        mv.visitVarInsn(Opcodes.ALOAD, buffer);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, scratch);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ILOAD, len);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, scratch);
        mv.visitVarInsn(Opcodes.ASTORE, buffer);
        mv.visitLabel(grown);
        body.slots().emit(mv, stackBase);
        emitElement(mv, body, array.component(), component, depth + 1, stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, buffer);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.SWAP);
        mv.visitVarInsn(Opcodes.ILOAD, len);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitVarInsn(Opcodes.ALOAD, body.carrier());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "pop",
                "(Ljava/lang/Object;)V", false);
        mv.visitIincInsn(len, 1);
        mv.visitJumpInsn(Opcodes.GOTO, head);
        mv.visitLabel(end);
        body.slots().emit(mv, stackBase);
        loadClassConstant(mv, array.componentClass());
        mv.visitVarInsn(Opcodes.ILOAD, len);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/reflect/Array", "newInstance",
                "(Ljava/lang/Class;I)Ljava/lang/Object;", false);
        mv.visitVarInsn(Opcodes.ASTORE, scratch);
        mv.visitVarInsn(Opcodes.ALOAD, buffer);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, scratch);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ILOAD, len);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        // release the element references into the pooled scratch, then return it — the
        // door's finally; an error edge leaks the buffer to the GC instead (the failure
        // path may allocate, ADR-0005)
        mv.visitVarInsn(Opcodes.ALOAD, buffer);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ILOAD, len);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "fill",
                "([Ljava/lang/Object;IILjava/lang/Object;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, buffer);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "returnArrayScratch",
                "([Ljava/lang/Object;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, scratch);
    }

    /** The component class as a constant — LDC for references, the wrapper's TYPE for
     * primitives (Array.newInstance takes either). */
    private static void loadClassConstant(MethodVisitor mv, Class<?> raw) {
        if (raw.isPrimitive()) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, internal(raw == int.class ? Integer.class
                            : raw == long.class ? Long.class
                            : raw == double.class ? Double.class
                            : raw == float.class ? Float.class
                            : raw == boolean.class ? Boolean.class
                            : raw == byte.class ? Byte.class
                            : raw == char.class ? Character.class
                            : Short.class),
                    "TYPE", "Ljava/lang/Class;");
        } else {
            mv.visitLdcInsn(Type.getType(raw));
        }
    }

    /**
     * One element value: the JSON-null gate (references bind null, collections too), then
     * the kind read with the value left ON THE STACK as an Object above {@code stackBase}.
     * Precondition/postcondition of the body's value-token slot: the element's own first
     * token (scalar tokens consumed by the read; container gates skip as the shared
     * rejection does). All branches converge on one {@code Object}-typed join.
     */
    private static void emitElement(MethodVisitor mv, Body body, Shape shape, int component,
                                    int depth, Object[] stackBase) {
        Label nullEdge = new Label();
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_NULL", TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, nullEdge);
        if (shape instanceof Scalar scalar) {
            emitScalar(mv, body, scalar.raw(), stackBase);
        } else if (shape instanceof Enumeration enumeration) {
            emitEnum(mv, body, enumeration.raw(), stackBase);
        } else if (shape instanceof Child child) {
            emitChild(mv, body, child.raw(), component, stackBase);
        } else {
            emitLoop(mv, body, shape, component, depth, stackBase);
        }
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(nullEdge);
        body.slots().emit(mv, stackBase);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitLabel(done);
        body.slots().emit(mv, withStack(stackBase, "java/lang/Object"));
    }

    /** The tag gate: the token must carry the expected tag, else the shared rejection. */
    private static void requireToken(MethodVisitor mv, Body body, String constant,
                                     String wanted, Object[] stackBase) {
        Label ok = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, constant, TOKEN_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, ok);
        raiseTagError(mv, body, wanted);
        mv.visitLabel(ok);
        body.slots().emit(mv, stackBase);
    }

    /** parser/token/wanted onto the shared tagError, then the throw. */
    private static void raiseTagError(MethodVisitor mv, Body body, String wanted) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
        mv.visitLdcInsn(wanted);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "tagError",
                "(Lio/github/chutian0610/jolt/JoltParser;"
                        + "Lio/github/chutian0610/jolt/Token;"
                        + "Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
        mv.visitInsn(Opcodes.ATHROW);
    }

    private static void requireNumber(MethodVisitor mv, Body body) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "requireNumber",
                "(Lio/github/chutian0610/jolt/JoltParser;"
                        + "Lio/github/chutian0610/jolt/Token;)V", false);
    }

    /** The boxed scalar read (list/map/buffer elements are always references). */
    private static void emitScalar(MethodVisitor mv, Body body, Class<?> raw,
                                   Object[] stackBase) {
        switch (raw.getName()) {
            case "java.lang.String" -> {
                requireToken(mv, body, "VALUE_STRING", "a string", stackBase);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "textValue",
                        PARSER_TEXT_VALUE_DESC, true);
            }
            case "java.lang.Boolean", "boolean" -> {
                Label trueEdge = new Label();
                Label falseEdge = new Label();
                Label ready = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_TRUE", TOKEN_DESC);
                mv.visitJumpInsn(Opcodes.IF_ACMPEQ, trueEdge);
                mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_INTERNAL, "VALUE_FALSE", TOKEN_DESC);
                mv.visitJumpInsn(Opcodes.IF_ACMPEQ, falseEdge);
                raiseTagError(mv, body, "a boolean");
                mv.visitLabel(trueEdge);
                body.slots().emit(mv, stackBase);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE",
                        "Ljava/lang/Boolean;");
                mv.visitJumpInsn(Opcodes.GOTO, ready);
                mv.visitLabel(falseEdge);
                body.slots().emit(mv, stackBase);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE",
                        "Ljava/lang/Boolean;");
                mv.visitLabel(ready);
                body.slots().emit(mv, withStack(stackBase, "java/lang/Boolean"));
            }
            case "java.math.BigDecimal" -> {
                requireNumber(mv, body);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                        PARSER_BIG_DECIMAL_DESC, true);
            }
            case "java.math.BigInteger" -> {
                requireNumber(mv, body);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                        PARSER_BIG_DECIMAL_DESC, true);
                Label tryStart = new Label();
                Label exact = new Label();
                Label lossy = new Label();
                Label done = new Label();
                mv.visitTryCatchBlock(tryStart, exact, lossy, "java/lang/ArithmeticException");
                mv.visitLabel(tryStart);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        "toBigIntegerExact", "()Ljava/math/BigInteger;", false);
                mv.visitLabel(exact);
                mv.visitJumpInsn(Opcodes.GOTO, done);
                mv.visitLabel(lossy);
                body.slots().emit(mv, "java/lang/ArithmeticException");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "notWholeNumber",
                        "()Ljava/lang/RuntimeException;", false);
                mv.visitInsn(Opcodes.ATHROW);
                mv.visitLabel(done);
                body.slots().emit(mv, withStack(stackBase, "java/math/BigInteger"));
            }
            case "java.lang.Float", "float" -> {
                requireNumber(mv, body);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                        PARSER_BIG_DECIMAL_DESC, true);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        "floatValue", "()F", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
            }
            case "java.lang.Double", "double" -> {
                requireNumber(mv, body);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "bigDecimalValue",
                        PARSER_BIG_DECIMAL_DESC, true);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        "doubleValue", "()D", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
            }
            default -> emitIntegral(mv, body, raw, stackBase);
        }
    }

    /** The integral read: the shared checked long read, then the all-stack bound dance
     * (each throw edge declares its own depth), boxed for the container. */
    private static void emitIntegral(MethodVisitor mv, Body body, Class<?> raw,
                                     Object[] stackBase) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, body.valueToken());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "readLongChecked",
                "(Lio/github/chutian0610/jolt/JoltParser;"
                        + "Lio/github/chutian0610/jolt/Token;)J", false);
        long bound = switch (raw.getName()) {
            case "byte", "java.lang.Byte" -> Byte.MAX_VALUE;
            case "short", "java.lang.Short" -> Short.MAX_VALUE;
            case "int", "java.lang.Integer" -> Integer.MAX_VALUE;
            default -> Long.MAX_VALUE;
        };
        if (bound != Long.MAX_VALUE) {
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
            body.slots().emit(mv, withStack(stackBase, Opcodes.LONG, Opcodes.LONG));
            mv.visitLdcInsn(raw.getSimpleName());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "boundError",
                    "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitLabel(overSecond);
            body.slots().emit(mv, withStack(stackBase, Opcodes.LONG));
            mv.visitLdcInsn(raw.getSimpleName());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "boundError",
                    "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitLabel(inRange);
            body.slots().emit(mv, withStack(stackBase, Opcodes.LONG));
            mv.visitInsn(Opcodes.L2I);
        }
        switch (raw.getName()) {
            case "byte", "java.lang.Byte" -> {
                mv.visitInsn(Opcodes.I2B);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
                        "(B)Ljava/lang/Byte;", false);
            }
            case "short", "java.lang.Short" -> {
                mv.visitInsn(Opcodes.I2S);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
                        "(S)Ljava/lang/Short;", false);
            }
            case "int", "java.lang.Integer" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            default -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false);
        }
    }

    /** The enum element: the text into the shared TEXT slot, then Enum.valueOf with the
     * unknown-name handler raising the shared message. */
    private static void emitEnum(MethodVisitor mv, Body body, Class<?> raw,
                                 Object[] stackBase) {
        requireToken(mv, body, "VALUE_STRING", "a string", stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARSER, "textValue",
                PARSER_TEXT_VALUE_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, body.text());
        Label tryStart = new Label();
        Label exact = new Label();
        Label lossy = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(tryStart, exact, lossy, "java/lang/IllegalArgumentException");
        mv.visitLabel(tryStart);
        mv.visitLdcInsn(Type.getType(raw));
        mv.visitVarInsn(Opcodes.ALOAD, body.text());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, internal(raw));
        mv.visitLabel(exact);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(lossy);
        // the handler's entry stack is CLEARED — no receiver base here
        body.slots().emit(mv, "java/lang/IllegalArgumentException");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, body.text());
        mv.visitLdcInsn(raw.getSimpleName());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DIRECT_SUPPORT, "unknownEnumName",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(done);
        body.slots().emit(mv, withStack(stackBase, internal(raw)));
    }

    /** The record/POJO element: the child's body on the SAME parser stream, through the
     * component's baked element-child binder (the monomorphic dispatch). */
    private static void emitChild(MethodVisitor mv, Body body, Class<?> raw, int component,
                                  Object[] stackBase) {
        requireToken(mv, body, "START_OBJECT", "an object", stackBase);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, body.self(),
                Emit.ELEMENT_CHILD_FIELD_PREFIX + component, TYPED_BINDER_FIELD_DESC);
        mv.visitTypeInsn(Opcodes.CHECKCAST, GEN_BASE);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GEN_BASE,
                RecordSpecializer.PARSE_TOKENS_BODY_METHOD,
                GEN_BASE_PARSE_TOKENS_BODY_DESC, false);
    }
}
