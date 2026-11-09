package io.github.chutian0610.jolt;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stateless entry points into the Jolt parser.
 */
public final class Jolt {

    private Jolt() {}

    /**
     * The UTF-8 parser factory: the one seam by which an optional engine (jolt-simd's
     * {@code SimdUtf8Parser}) replaces the core scalar engine on the {@link JoltParser}-typed
     * UTF-8 entry points ({@code parse}, {@code tryParse}, the InputStream adapters). Default
     * null — the core scalar engine, at zero cost beyond one null-checked field read per
     * engine acquisition. Explicit install (the Bind GENERATED-registry precedent), last
     * install wins. The concrete reuse entries ({@link #utf8Parser(byte[])}) stay on the
     * scalar engine: their contract is the concrete type's {@code reset}, which a
     * factory-produced engine cannot honor through this interface.
     */
    public interface Utf8ParserFactory {

        /** A UTF-8 engine over the given document under the given engine knobs. */
        JoltParser create(byte[] json, int maxDepth, int features);
    }

    private static volatile Utf8ParserFactory utf8ParserFactory;

    /** Installs (or replaces) the UTF-8 parser factory. Not thread-safe with in-flight parses. */
    public static void installUtf8ParserFactory(Utf8ParserFactory factory) {
        utf8ParserFactory = factory;
    }

    /** Removes the installed factory; the UTF-8 entries return to the core scalar engine. */
    public static void uninstallUtf8ParserFactory() {
        utf8ParserFactory = null;
    }

    /** The dispatch point: the installed factory, else the core scalar engine. */
    static JoltParser newUtf8Engine(byte[] json, int maxDepth, int features) {
        Utf8ParserFactory factory = utf8ParserFactory;
        return factory != null ? factory.create(json, maxDepth, features)
                : new Utf8Parser(json, maxDepth, features);
    }

    /** Begins parsing a UTF-8 JSON document held in {@code json} with the default depth limit. */
    public static JoltParser parse(byte[] json) {
        return newUtf8Engine(json, JoltParser.DEFAULT_MAX_DEPTH, 0);
    }

    /** Begins parsing a UTF-8 document with an explicit nesting depth limit. */
    public static JoltParser parse(byte[] json, int maxDepth) {
        return newUtf8Engine(json, maxDepth, 0);
    }

    /** Begins parsing a JSON document held in a {@link String} (UTF-16 engine, no transcoding). */
    public static JoltParser parse(String json) {
        return new Utf16Parser(json);
    }

    /** Begins parsing a {@link String} document with an explicit nesting depth limit. */
    public static JoltParser parse(String json, int maxDepth) {
        return new Utf16Parser(json, maxDepth);
    }

    /** Begins parsing a UTF-8 document under an explicit configuration. */
    public static JoltParser parse(byte[] json, JoltConfig config) {
        return newUtf8Engine(json, config.maxDepth, config.features);
    }

    /** Begins parsing a {@link String} document under an explicit configuration. */
    public static JoltParser parse(String json, JoltConfig config) {
        return new Utf16Parser(json, config);
    }

    /** Begins writing a JSON document into a reusable UTF-8 buffer. */
    public static JoltWriter write() {
        return new JoltWriter();
    }

    /** A concrete UTF-8 engine instance for zero-allocation reuse across messages via
     * {@link Utf8Parser#reset(byte[])}. */
    public static Utf8Parser utf8Parser(byte[] json) {
        return new Utf8Parser(json);
    }

    /** A concrete UTF-8 engine instance under an explicit configuration. */
    public static Utf8Parser utf8Parser(byte[] json, JoltConfig config) {
        return new Utf8Parser(json, config);
    }

    /** A concrete UTF-16 engine instance for reuse across messages via
     * {@link Utf16Parser#reset(String)}. */
    public static Utf16Parser utf16Parser(String json) {
        return new Utf16Parser(json);
    }

    /** A concrete UTF-16 engine instance under an explicit configuration. */
    public static Utf16Parser utf16Parser(String json, JoltConfig config) {
        return new Utf16Parser(json, config);
    }

    /** Parses from an InputStream by buffering it internally (the adapter path — the byte[]
     * fast path stays zero-copy). */
    public static JoltParser parse(InputStream in) throws IOException {
        return newUtf8Engine(in.readAllBytes(), JoltParser.DEFAULT_MAX_DEPTH, 0);
    }

    /** Parses from an InputStream under an explicit configuration. */
    public static JoltParser parse(InputStream in, JoltConfig config) throws IOException {
        return newUtf8Engine(in.readAllBytes(), config.maxDepth, config.features);
    }

    /** Validates a UTF-8 document without exceptions: shared success sentinel, or diagnostics. */
    public static ParseResult tryParse(byte[] json) {
        return newUtf8Engine(json, JoltParser.DEFAULT_MAX_DEPTH, 0).drainOrFailure();
    }

    /** Validates a UTF-8 document under a configuration, without exceptions. */
    public static ParseResult tryParse(byte[] json, JoltConfig config) {
        return newUtf8Engine(json, config.maxDepth, config.features).drainOrFailure();
    }

    /** Validates a String document without exceptions: shared success sentinel, or diagnostics. */
    public static ParseResult tryParse(String json) {
        return new Utf16Parser(json).drainOrFailure();
    }

    /** Validates a String document under a configuration, without exceptions. */
    public static ParseResult tryParse(String json, JoltConfig config) {
        return new Utf16Parser(json, config).drainOrFailure();
    }
}
