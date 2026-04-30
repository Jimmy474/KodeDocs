plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":utils"))
    implementation(project(":engine"))

    implementation(libs.directory.watcher)
    implementation(libs.bundles.ktorEcosystem)
    implementation(libs.bundles.kotlinxEcosystem)
}

application {
    mainClass = "com.jimmy.app.MainKt"
}
