plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.directory.watcher)
    implementation(libs.bundles.ktorEcosystem)
    implementation(libs.bundles.kotlinxEcosystem)
}

application {
    mainClass = "com.jimmy.app.MainKt"
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}