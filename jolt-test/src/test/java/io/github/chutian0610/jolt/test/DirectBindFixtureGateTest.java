package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.bind.fixtures.BindFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The direct-bind fixture gate: the canonical M3 fixture documents (the graph the corpus
 * gates live on) through BOTH config arms — values equal, writes byte-identical, error
 * matrix cells byte-identical. The M6-T2 slice covers the record spectra (Scalars' boxed
 * nullables and exact decimals included); collection components land with M6-T3.
 */
class DirectBindFixtureGateTest {

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void scalarsFixtureTwoArmValueParityAndByteIdenticalWrite() {
        BindFixtures.Scalars tree = Bind.parse(utf8(BindFixtures.SCALARS_JSON),
                BindFixtures.Scalars.class, BindConfig.defaults());
        BindFixtures.Scalars direct = Bind.parse(utf8(BindFixtures.SCALARS_JSON),
                BindFixtures.Scalars.class, BindConfig.defaults().withDirect());
        assertEquals(tree, direct);
        assertEquals(Bind.write(tree), Bind.write(direct));
        assertEquals(BindFixtures.SCALARS_JSON, Bind.write(direct));
    }

    @Test
    void itemFixtureTwoArmParity() {
        // Item's BigDecimal component round-trips the verbatim decimal through both arms
        String doc = "{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50}";
        BindFixtures.Item tree = Bind.parse(utf8(doc), BindFixtures.Item.class,
                BindConfig.defaults());
        BindFixtures.Item direct = Bind.parse(utf8(doc), BindFixtures.Item.class,
                BindConfig.defaults().withDirect());
        assertEquals(tree, direct);
        assertEquals(Bind.write(tree), Bind.write(direct));
        assertEquals(doc, Bind.write(direct));
    }

    @Test
    void scalarsErrorCellsTwoArms() {
        BindConfig direct = BindConfig.defaults().withFailOnUnknown().withDirect();
        BindConfig tree = BindConfig.defaults().withFailOnUnknown();

        JsonBindException unknownTree = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1,\"sneaky\":9}"),
                        BindFixtures.Scalars.class, tree));
        JsonBindException unknownDirect = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1,\"sneaky\":9}"),
                        BindFixtures.Scalars.class, direct));
        assertEquals(unknownTree.getPath(), unknownDirect.getPath());
        assertEquals(unknownTree.getMessage(), unknownDirect.getMessage());

        JsonBindException widthTree = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"b\":129,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1}"),
                        BindFixtures.Scalars.class, tree));
        JsonBindException widthDirect = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"b\":129,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1}"),
                        BindFixtures.Scalars.class, direct));
        assertEquals(widthTree.getPath(), widthDirect.getPath());
        assertEquals(widthTree.getMessage(), widthDirect.getMessage());
    }

    @Test
    void orderFixtureTwoArmParityAndByteIdenticalWrite() {
        // the FULL M3 graph through both arms: nested record, list of records, map, enum,
        // boxed null, exact decimals — the direct path's flagship gate
        BindFixtures.Order tree = Bind.parse(utf8(BindFixtures.ORDER_JSON),
                BindFixtures.Order.class, BindConfig.defaults());
        BindFixtures.Order direct = Bind.parse(utf8(BindFixtures.ORDER_JSON),
                BindFixtures.Order.class, BindConfig.defaults().withDirect());
        assertEquals(tree, direct);
        assertEquals(Bind.write(tree), Bind.write(direct));
        assertEquals(BindFixtures.ORDER_JSON, Bind.write(direct));
    }

    @Test
    void orderFixtureErrorCellsTwoArms() {
        BindConfig tree = BindConfig.defaults().withFailOnUnknown();
        BindConfig direct = BindConfig.defaults().withFailOnUnknown().withDirect();

        // an unknown field at the top level (same runtime type + message + path on both arms)
        String withUnknown = "{\"id\":\"o-1\",\"customer\":{\"name\":\"ann\","
                + "\"emails\":[\"a@x\"],\"tier\":2},\"items\":[],\"status\":\"ACTIVE\","
                + "\"labels\":{},\"note\":null,\"total\":1,\"sequence\":1,\"ratio\":1.0,"
                + "\"gift\":false,\"sneaky\":9}";
        RuntimeException unknownTree = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(withUnknown), BindFixtures.Order.class, tree));
        RuntimeException unknownDirect = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(withUnknown), BindFixtures.Order.class, direct));
        assertEquals(unknownTree.getClass(), unknownDirect.getClass());
        assertEquals(unknownTree.getMessage(), unknownDirect.getMessage());

        // a type mismatch inside the nested record: $.customer.name
        String doc = "{\"id\":\"o-1\",\"customer\":{\"name\":true,"
                + "\"emails\":[\"a@x\"],\"tier\":2},\"items\":[],\"status\":\"ACTIVE\"}";
        JsonBindException mismatchTree = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8(doc), BindFixtures.Order.class, tree));
        JsonBindException mismatchDirect = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8(doc), BindFixtures.Order.class, direct));
        assertEquals(mismatchTree.getMessage(), mismatchDirect.getMessage());
        assertEquals(mismatchTree.getPath(), mismatchDirect.getPath());
    }

    @Test
    void untypedFixtureTwoArmParity() {
        String doc = "{\"any\":[1,\"s\",true,null,2.5,9007199254740993],"
                + "\"dynamic\":{\"k\":{\"n\":[9.5]}}}";
        BindFixtures.Untyped tree = Bind.parse(utf8(doc), BindFixtures.Untyped.class,
                BindConfig.defaults());
        BindFixtures.Untyped direct = Bind.parse(utf8(doc), BindFixtures.Untyped.class,
                BindConfig.defaults().withDirect());
        assertEquals(tree.any(), direct.any());
        assertEquals(tree.dynamic(), direct.dynamic());
        assertEquals(Bind.write(tree), Bind.write(direct));
    }
}
