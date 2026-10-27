package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.gen.GeneratedBinders;
import io.github.chutian0610.jolt.tree.Tree;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Generated-binder error semantics (T32 parameterization): the GENERATED binder must face the
 * same error contract as the reflective one — same paths, same exception type.
 */
class GeneratedBinderErrorMatrixTest {

    public record Item(String sku, int quantity, java.math.BigDecimal price) {}

    private static final String DOC =
            "{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50}";
    private static final String DOC_WITH_UNKNOWN =
            "{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50,\"sneaky\":1}";

    private static Item parse(String json, BindConfig config) {
        TypedBinder<Item> binder = GeneratedBinders.forType(Item.class);
        // The generated path is the thing under test — a reflective fallback would silently
        // gut this gate.
        assertTrue(binder.getClass().getName().startsWith("io.github.chutian0610.jolt.gen.Gen$"),
                "expected a GENERATED binder, got reflective fallback: "
                        + binder.getClass().getName());
        return binder.parse(
                Tree.parse(json.getBytes(StandardCharsets.UTF_8)), "$", config);
    }


    @Test
    void unknownFieldIgnoredByDefaultThrownWhenEnabled() {
        Item ignored = parse(DOC, BindConfig.defaults());
        assertEquals("s1", ignored.sku());

        JsonBindException e = assertThrows(JsonBindException.class,
                () -> parse(DOC_WITH_UNKNOWN, BindConfig.defaults().withFailOnUnknown()));
        assertEquals("$.sneaky", e.getPath());
        assertTrue(e.getMessage().contains("unknown field"), e.getMessage());
    }

    @Test
    void typeMismatchThrowsWithGeneratedPath() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> parse("{\"sku\":true,\"quantity\":2,\"price\":1.50}", BindConfig.defaults()));
        assertEquals("$.sku", e.getPath());
    }

    @Test
    void missingPrimitiveThrowsWithGeneratedPath() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> parse("{\"sku\":\"s1\",\"price\":1.50}", BindConfig.defaults()));
        assertEquals("$.quantity", e.getPath());
    }

}
