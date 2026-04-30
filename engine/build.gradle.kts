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
    implementation(libs.bundles.kotlinxEcosystem)
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}