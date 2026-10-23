package io.github.chutian0610.jolt.bind.fixtures;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * THE binding fixture graph — one home for the record shapes every bind unit test and gate
 * binds against (the architecture review's C2: same-name/different-shape twins deleted).
 * Canonical documents live here too, byte-matching {@code Bind.write} output.
 */
public final class BindFixtures {

    public enum Status { ACTIVE, ARCHIVED }

    public record Item(String sku, int quantity, BigDecimal price) {}

    public record Customer(String name, List<String> emails, Integer tier) {}

    public record Order(String id, Customer customer, List<Item> items, Status status,
                        Map<String, String> labels, Integer note, long total,
                        BigInteger sequence, double ratio, boolean gift) {}

    public record Scalars(byte b, short s, int i, long l, float f, double d,
                          boolean flag, String text, BigDecimal dec, BigInteger big,
                          Integer boxedNullable) {}

    public record Untyped(List<Object> any, Map<String, Object> dynamic) {}

    public static class LabelPojo {
        private String label;

        public LabelPojo() {}

        public void setLabel(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** The canonical compact form of the order fixture (matches Bind.write output shape). */
    public static final String ORDER_JSON = "{\"id\":\"o-1\",\"customer\":"
            + "{\"name\":\"ann\",\"emails\":[\"a@x\",\"b@x\"],\"tier\":2},"
            + "\"items\":[{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50},"
            + "{\"sku\":\"s2\",\"quantity\":1,\"price\":92233720368547758079}],"
            + "\"status\":\"ACTIVE\",\"labels\":{\"src\":\"mq\"},\"note\":null,"
            + "\"total\":9007199254740993,\"sequence\":123456789012345678901234567890,"
            + "\"ratio\":2.5,\"gift\":false}";

    /** The scalar-fixture document (canonical compact form). */
    public static final String SCALARS_JSON = "{\"b\":-128,\"s\":-32768,\"i\":-2147483648,"
            + "\"l\":-9223372036854775808,\"f\":1.5,\"d\":2.5,\"flag\":true,"
            + "\"text\":\"a\\\"b\\\\c\\u0001\",\"dec\":0.1000000000000000000001,"
            + "\"big\":123456789012345678901234567890123456789,\"boxedNullable\":null}";

    private BindFixtures() {}
}
