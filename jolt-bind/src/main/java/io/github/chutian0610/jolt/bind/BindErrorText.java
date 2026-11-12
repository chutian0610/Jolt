package io.github.chutian0610.jolt.bind;

/**
 * The binding error text, one home: every message template the bind layer raises — the tree
 * path, the direct read, and the generated code of both grades — lives here, so a wording
 * change lands once and the gates' byte-identical message assertions check confirmation, not
 * drift. Two forms, one source: the {@code OPEN}/{@code MIDDLE}/{@code CLOSE} fragment
 * constants are compile-time strings (javac inlines them into the emitters' LDC instructions
 * — the emitted bytecode is unchanged), and the methods assemble the same fragments for
 * runtime callers. {@link BindErrorTextTest} pins that the fragments assemble to the methods'
 * output.
 *
 * <p>Nothing here throws or allocates beyond the message itself: the failure path may
 * allocate (ADR-0005), and every caller sits in a failure branch.
 */
public final class BindErrorText {

    // --- the wanted 文案 (the tag-rejection's target phrase) ---

    public static final String A_STRING = "a string";
    public static final String A_BOOLEAN = "a boolean";
    public static final String A_NUMBER = "a number";
    public static final String AN_OBJECT = "an object";
    public static final String AN_ARRAY = "an array";

    // --- message fragments (compile-time strings; the emitters bake them as LDC) ---

    public static final String EXPECTED_OPEN = "expected ";
    public static final String EXPECTED_MIDDLE = " but found ";
    public static final String UNKNOWN_FIELD_OPEN = "unknown field '";
    public static final String QUOTE = "'";
    public static final String SETTER_REJECTED_OPEN = "setter rejected the bound value: ";
    public static final String CONSTRUCTOR_REJECTED_OPEN =
            "record constructor rejected the bound values: ";
    public static final String BOUND_OPEN = "number does not fit in ";
    public static final String LONG_OVERFLOW = "number does not fit in a long";
    public static final String NOT_WHOLE_NUMBER = "number is not a whole number";
    public static final String MAP_KEYS_OPEN = "map keys must be String, got ";
    public static final String UNSUPPORTED_GENERIC_OPEN = "unsupported generic type ";
    public static final String UNSUPPORTED_COMPONENT_OPEN = "unsupported component type ";
    public static final String POLYMORPHIC_OPEN = "polymorphic binding is not supported for ";
    public static final String CANNOT_READ_OPEN = "cannot read property ";
    public static final String CANNOT_INSTANTIATE_OPEN = "cannot instantiate ";
    public static final String MISSING_OPEN = "missing value for primitive component ";
    public static final String NULL_FOR_OPEN = "JSON null for primitive component ";
    public static final String UNKNOWN_ENUM_OPEN = "unknown enum name '";
    public static final String UNKNOWN_ENUM_MIDDLE = "' for ";

    private BindErrorText() {}

    // --- the templates (runtime assembly from the same fragments) ---

    /** The tag rejection: the target phrase, then what the document actually held. */
    public static String expectedButFound(String wanted, String found) {
        return EXPECTED_OPEN + wanted + EXPECTED_MIDDLE + found;
    }

    public static String unknownField(String key) {
        return UNKNOWN_FIELD_OPEN + key + QUOTE;
    }

    public static String setterRejected(Throwable cause) {
        return SETTER_REJECTED_OPEN + cause;
    }

    public static String constructorRejected(Throwable cause) {
        return CONSTRUCTOR_REJECTED_OPEN + cause;
    }

    /** The width overflow, suffix from the component's getSimpleName (byte vs Byte differ). */
    public static String boundError(String simpleName) {
        return BOUND_OPEN + simpleName;
    }

    public static String mapKeysMustBeString(String actual) {
        return MAP_KEYS_OPEN + actual;
    }

    public static String unsupportedGenericType(String typeName) {
        return UNSUPPORTED_GENERIC_OPEN + typeName;
    }

    public static String unsupportedComponentType(Object target) {
        return UNSUPPORTED_COMPONENT_OPEN + target;
    }

    public static String polymorphicUnsupported(String simpleName) {
        return POLYMORPHIC_OPEN + simpleName;
    }

    public static String cannotReadProperty(String name) {
        return CANNOT_READ_OPEN + name;
    }

    public static String cannotInstantiate(String typeName) {
        return CANNOT_INSTANTIATE_OPEN + typeName;
    }

    public static String missingValueForPrimitive(String simpleName) {
        return MISSING_OPEN + simpleName;
    }

    public static String nullForPrimitive(String simpleName) {
        return NULL_FOR_OPEN + simpleName;
    }

    public static String unknownEnumName(String text, String simpleName) {
        return UNKNOWN_ENUM_OPEN + text + UNKNOWN_ENUM_MIDDLE + simpleName;
    }
}
