package io.github.chutian0610.jolt.bind;

/**
 * The binding failure: a runtime exception carrying the JSON path of the value that failed to
 * map (the $.user.tags[2] shape) — distinct from {@code JsonParseException}, which reports
 * syntax. Parse errors surface from the core unchanged; bind errors are data-mapping errors.
 */
public class JsonBindException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String path;

    /** A binding failure at the document root. */
    public JsonBindException(String message) {
        this(message, "$");
    }

    /** A binding failure at {@code path}. */
    public JsonBindException(String message, String path) {
        super(message + " at " + path);
        this.path = path;
    }

    /** The JSON path of the failing value, {@code $}-rooted. */
    public String getPath() {
        return path;
    }
}
