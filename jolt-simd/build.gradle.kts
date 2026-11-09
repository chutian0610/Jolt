// jolt-simd: the optional Vector-API engine (M9-T2, spec #62) — parked after its honest
// negative result (#65). Never a dependency of jolt-core/tree/bind/gen; whoever adds this
// module adds the incubator flag with it (--add-modules jdk.incubator.vector). Its parity
// gates live in jolt-test, beside the corpus machinery.
plugins {
    `java-library`
}

dependencies {
    api(project(":jolt-core"))
}

// The Vector API is an incubator module: compilation needs it on the module path.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}
