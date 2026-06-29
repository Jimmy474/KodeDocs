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
    implementation(libs.xberg.treesitter.language.pack)
    implementation(libs.logback)

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

tasks.register<JavaExec>("runPerfTest") {
    group = "application"
    description = "Runs the standalone performance test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("TempKt")
}