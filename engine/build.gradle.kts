plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

group = "com.jimmy"
version = "1.0.0-SNAPSHOT-1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(libs.flexmark)
    implementation(libs.jsoup)
    implementation(libs.jna)
    implementation(libs.jnaPlatform)
    implementation(files(rootProject.file("libs/jtreesitter-0.26.1.jar")))
    implementation(libs.kruezberg.treesitter.language.pack){
        exclude("io.github.tree-sitter", "jtreesitter")
    }
    implementation(libs.logback)
    implementation(libs.jansi)

    implementation(libs.bundles.kotlinxEcosystem)
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
