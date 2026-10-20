package io.github.chutian0610.jolt.test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Shared corpus helpers: directory access, cached manifest, and fail-loud case classification. */
final class CorpusSupport {

    private static Properties manifestCache;

    private CorpusSupport() {}

    static File corpusDir() {
        File dir = new File(System.getProperty("jolt.jsontestsuite.dir", ""));
        if (!dir.isDirectory()) {
            throw new IllegalStateException("JSONTestSuite corpus not found at " + dir);
        }
        return dir;
    }

    static synchronized Properties manifest() {
        if (manifestCache == null) {
            Properties props = new Properties();
            try (InputStream in = CorpusSupport.class
                    .getResourceAsStream("/jsontestsuite/i-manifest.properties")) {
                if (in == null) {
                    throw new IllegalStateException("i-manifest.properties missing from test resources");
                }
                props.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read i-manifest", e);
            }
            manifestCache = props;
        }
        return manifestCache;
    }

    /**
     * Whether a corpus case must be accepted: y_ always, n_ never, i_ exactly as the manifest
     * records. A mistyped or missing manifest value fails loudly — never silently rejects.
     */
    static boolean expectAccept(String caseName) {
        switch (caseName.substring(0, 2)) {
            case "y_":
                return true;
            case "n_":
                return false;
            default: {
                String recorded = manifest().getProperty(caseName);
                if (!"accept".equals(recorded) && !"reject".equals(recorded)) {
                    throw new IllegalStateException(
                            "Unknown manifest value '" + recorded + "' for " + caseName);
                }
                return "accept".equals(recorded);
            }
        }
    }
}
