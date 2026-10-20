plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    implementation(project(":jolt-core"))

    // T8 competitors: Jackson with the afterburner module (the M1 bar), dsl-json (stretch).
    jmh("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    jmh("com.fasterxml.jackson.module:jackson-module-afterburner:2.18.2")
    jmh("com.dslplatform:dsl-json:2.0.2")
}
