package io.github.chutian0610.jolt.gen;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import static io.github.chutian0610.jolt.gen.Emit.BIND_EXCEPTION;
import static io.github.chutian0610.jolt.gen.Emit.BIND_EXCEPTION_INIT_DESC;
import static io.github.chutian0610.jolt.gen.Emit.BUILDER;
import static io.github.chutian0610.jolt.gen.Emit.BUILDER_APPEND_DESC;
import static io.github.chutian0610.jolt.gen.Emit.BUILDER_INIT_DESC;
import static io.github.chutian0610.jolt.gen.Emit.BUILDER_TOSTRING_DESC;
import static io.github.chutian0610.jolt.gen.Emit.NODE;

/**
 * fail-on-unknown as an O(1) contains loop over the baked name set — shared by the record
 * and POJO parse bodies. A separate method so the straight-line body's single join point has
 * identical locals on both predecessors; with fail-on-unknown off the check costs one branch.
 */
final class CheckUnknownBody implements ByteCodeAppender {

    static final String METHOD = "jolt$checkUnknown";
    static final String DESCRIPTOR =
            "(Lio/github/chutian0610/jolt/tree/Node;Ljava/util/Set;Ljava/lang/String;)V";

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
        Emit.emitConcat(mv, () -> {
            mv.visitLdcInsn("unknown field '");
            Emit.appendText(mv);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            Emit.appendText(mv);
            mv.visitLdcInsn("'");
            Emit.appendText(mv);
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
}
