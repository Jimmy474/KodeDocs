package com.jimmy.app

import java.io.File
import Engine
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

fun main(args: Array<String>) = runBlocking {
    val engine = Engine()

    val docsDir = File("../docs").absoluteFile
    val assetsDir = File("../assets").absoluteFile
    val outputDir = File("../build/site").absoluteFile
    val projectRoot = File("..").absoluteFile

    println("Working dir: ${File(".").absolutePath}")
    println("Docs dir: ${docsDir.path} exists: ${docsDir.exists()}")

    // Initial build
    engine.build(docsDir, outputDir)

    val server = PreviewServer(outputDir)
    server.start()

    val watcher = DirectoryWatcher.builder()
        .paths(listOf(docsDir.toPath(), assetsDir.toPath(), projectRoot.toPath().resolve("app/src/main/resources")))
        .listener { event ->
            if (!event.path().toString().contains("build")) {
                println("Change detected in ${event.path()}. Rebuilding...")
                try {
                    engine.build(docsDir, outputDir)
                    launch {
                        server.reload()
                    }
                } catch (e: Exception) {
                    System.err.println("Build failed: ${e.message}")
                }
            }
        }
        .build()

    println("Watching for changes in ${docsDir.path} and resources...")
    watcher.watch()
}