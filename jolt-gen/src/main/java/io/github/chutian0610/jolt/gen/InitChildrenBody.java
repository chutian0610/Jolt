package io.github.chutian0610.jolt.gen;

import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;

import static io.github.chutian0610.jolt.gen.Emit.CHILD_FIELD_PREFIX;
import static io.github.chutian0610.jolt.gen.Emit.ELEMENT_CHILD_FIELD_PREFIX;
import static io.github.chutian0610.jolt.gen.Emit.GENERATED_INTERNAL;
import static io.github.chutian0610.jolt.gen.Emit.TYPED_BINDER_FIELD_DESC;
import static io.github.chutian0610.jolt.gen.Emit.kindOf;

/**
 * One-time child-binder baking, shared verbatim by both specializers (it WAS two hand-kept
 * copies differing only in where the component raws come from — record components vs POJO
 * setter parameters — which the caller now precomputes): for every CHILD-kind component,
 * resolve its binder and store it in the baked field; the reference namespace resolves its
 * children within itself, and the M8 inline loops' element children ride along.
 */
final class InitChildrenBody implements ByteCodeAppender {

    private final String self;
    private final Class<?>[] componentRaws;
    private final boolean referenceForm;
    private final InlineCollections.Shape[] inline;

    InitChildrenBody(String self, Class<?>[] componentRaws, boolean referenceForm,
                     InlineCollections.Shape[] inline) {
        this.self = self;
        this.componentRaws = componentRaws;
        this.referenceForm = referenceForm;
        this.inline = inline;
    }

    @Override
    public Size apply(MethodVisitor mv, net.bytebuddy.implementation.Implementation.Context context,
                      net.bytebuddy.description.method.MethodDescription instrumentedMethod) {
        for (int i = 0; i < componentRaws.length; i++) {
            Class<?> raw = componentRaws[i];
            if (kindOf(raw) == Emit.Kind.CHILD) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitLdcInsn(Type.getType(raw));
                // the reference namespace resolves its children within itself — an
                // M8-off parent must not pick up an M8-form child through the main cache
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, GENERATED_INTERNAL,
                        referenceForm ? "referenceForType" : "forType",
                        "(Ljava/lang/Class;)" + TYPED_BINDER_FIELD_DESC, false);
                mv.visitFieldInsn(Opcodes.PUTFIELD, self, CHILD_FIELD_PREFIX + i,
                        TYPED_BINDER_FIELD_DESC);
            } else if (inline[i] != null && InlineCollections.childOf(inline[i]) != null) {
                // the inlined loop's element child (the M8 form only — shapes are null
                // in the reference form)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitLdcInsn(Type.getType(InlineCollections.childOf(inline[i])));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, GENERATED_INTERNAL,
                        "forType", "(Ljava/lang/Class;)" + TYPED_BINDER_FIELD_DESC, false);
                mv.visitFieldInsn(Opcodes.PUTFIELD, self,
                        ELEMENT_CHILD_FIELD_PREFIX + i, TYPED_BINDER_FIELD_DESC);
            }
        }
        mv.visitInsn(Opcodes.RETURN);
        return new Size(6, 1);
    }
}
