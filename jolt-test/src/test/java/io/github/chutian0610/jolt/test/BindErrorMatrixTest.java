package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.test.BindFixtures.Customer;
import io.github.chutian0610.jolt.test.BindFixtures.Order;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error-path matrix (T25): {fail-on-unknown} × {unknown field, missing reference,
 * missing/null primitive, type mismatch, unknown enum} — every failure a JsonBindException
 * carrying the JSON path.
 */
class BindErrorMatrixTest {

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void unknownFieldIgnoredByDefaultThrownWhenEnabled() {
        String doc = "{\"id\":\"o\",\"total\":1,\"ratio\":1.0,\"gift\":true,\"sneaky\":{\"deep\":[1,2]}}";
        Order ignored = Bind.parse(utf8(doc), Order.class);
        assertEquals("o", ignored.id());
        assertNull(ignored.customer());

        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8(doc), Order.class, BindConfig.strict().withFailOnUnknown()));
        assertEquals("$.sneaky", e.getPath());
        assertTrue(e.getMessage().contains("unknown field"), e.getMessage());
    }

    @Test
    void unknownFieldInNestedRecordThrowsWithFullPath() {
        String doc = "{\"id\":\"o\",\"customer\":{\"name\":\"a\",\"ghost\":1}}";
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8(doc), Order.class, BindConfig.strict().withFailOnUnknown()));
        assertEquals("$.customer.ghost", e.getPath());
    }

    @Test
    void missingReferenceReadsNullRegardlessOfSwitch() {
        assertEquals(null, Bind.parse(utf8("{\"id\":\"o\",\"total\":1,\"ratio\":1.0,\"gift\":true}"), Order.class).customer());
        assertEquals(null, Bind.parse(utf8("{\"id\":\"o\",\"total\":1,\"ratio\":1.0,\"gift\":true}"), Order.class,
                BindConfig.strict().withFailOnUnknown()).customer());
    }

    @Test
    void missingAndNullPrimitivesThrowWithPaths() {
        JsonBindException missing = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"id\":\"o\"}"), Order.class));
        assertEquals("$.total", missing.getPath());
        assertTrue(missing.getMessage().contains("primitive"), missing.getMessage());

        JsonBindException nullPrimitive = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"id\":\"o\",\"ratio\":1.0,\"gift\":true,\"total\":null}"), Order.class));
        assertEquals("$.total", nullPrimitive.getPath());
    }

    @Test
    void typeMismatchThrowsWithPath() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"id\":\"o\",\"ratio\":1.0,\"gift\":true,\"total\":\"not-a-number\"}"), Order.class));
        assertEquals("$.total", e.getPath());
        assertTrue(e.getMessage().contains("expected a number"), e.getMessage());
    }

    @Test
    void unknownEnumThrowsWithPath() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8(BindFixtures.ORDER_JSON.replace("ACTIVE", "GONE")), Order.class));
        assertEquals("$.status", e.getPath());
        assertTrue(e.getMessage().contains("GONE"), e.getMessage());
    }

    @Test
    void failOnUnknownAppliesToPojoSettersToo() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"label\":\"x\",\"mystery\":1}"),
                        BindFixtures.LabelPojo.class, BindConfig.strict().withFailOnUnknown()));
        assertEquals("$.mystery", e.getPath());
    }

    @Test
    void boxedMissingReadsNullWhilePrimitiveMissingThrows() {
        // boxed reference: missing reads null
        Customer withTier = Bind.parse(utf8("{\"name\":\"solo\",\"emails\":[\"a@x\"],\"total\":1}"),
                Customer.class);
        assertNull(withTier.tier());
        // primitive: missing throws, never a silent zero (Item.quantity is int)
        io.github.chutian0610.jolt.test.BindFixtures.Item item =
                Bind.parse(utf8("{\"sku\":\"s1\",\"quantity\":2}"), io.github.chutian0610.jolt.test.BindFixtures.Item.class);
        assertEquals("s1", item.sku());
        assertNull(item.price());
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"sku\":\"s2\",\"price\":1}"),
                        io.github.chutian0610.jolt.test.BindFixtures.Item.class));
        assertEquals("$.quantity", e.getPath());
    }
}
