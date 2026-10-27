plugins {
    id("me.champeau.jmh") version "0.7.2"
}

// The CLI properties (-Pjmh.include / -Pjmh.fork / -Pjmh.warmupIterations / -Pjmh.iterations)
// only reach the plugin through this block — without it the include filter silently does
// nothing and every benchmark runs.
jmh {
    val include = findProperty("jmh.include")
    if (include != null) {
        includes.set(include.toString().split(","))
    }
    findProperty("jmh.fork")?.let { fork.set(it.toString().toInt()) }
    findProperty("jmh.warmupIterations")?.let { warmupIterations.set(it.toString().toInt()) }
    findProperty("jmh.iterations")?.let { iterations.set(it.toString().toInt()) }
}

dependencies {
    implementation(project(":jolt-core"))
    implementation(project(":jolt-tree"))
    implementation(project(":jolt-bind"))
    implementation(project(":jolt-gen"))

    // T8 competitors: Jackson with the afterburner module (the M1 bar), dsl-json (stretch).
    jmh("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    jmh("com.fasterxml.jackson.module:jackson-module-afterburner:2.18.2")
    jmh("com.dslplatform:dsl-json:2.0.2")
}
