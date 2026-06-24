package com.jimmy.app

import Engine
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

fun main(args: Array<String>) = runBlocking {
    val log = LoggerFactory.getLogger(this::class.java)

    if (args.isEmpty()) {
        println("Usage: kodedocs <root_dir> [output_dir]")
        return@runBlocking
    }

    val rootDir = File(args[0]).absoluteFile
    val outputDir = if (args.size > 1) File(args[1]).absoluteFile else rootDir.resolve("build/site").absoluteFile

    val engine = Engine()

    val (_, duration) = measureTimedValue {
        engine.build(rootDir, outputDir)
    }
    log.info("Build completed in $duration")

    val server = PreviewServer(outputDir)
    server.start()

    var reloadJob: Job? = null
    var lastRebuildTime = 0L

    val watcher = DirectoryWatcher.builder()
        .paths(listOf(rootDir.toPath(), outputDir.toPath()))
        .listener { event ->
            val changedFile = event.path().toFile().absoluteFile
            val fileName = changedFile.name

            if (fileName.endsWith("~") || fileName.endsWith(".tmp") || fileName.endsWith(".swp") || fileName.startsWith(".")) return@listener

            if (changedFile.isInside(outputDir)) {
                reloadJob?.cancel()
                reloadJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(50.milliseconds)
                    server.reload()
                }
                return@listener
            }

            val now = System.currentTimeMillis()
            if (now - lastRebuildTime < 500) {
                return@listener
            }

            if (!changedFile.path.contains("${File.separator}build${File.separator}")) {
                log.info("Change detected in ${changedFile.path}. Building...")
                lastRebuildTime = now

                try {
                    // Full build on any change for simplicity in CLI
                    engine.build(rootDir, outputDir)
                } catch (e: Exception) {
                    log.error("Build failed: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        .build()

    log.info("Watching for changes in ${rootDir.path}...")
    watcher.watch()
}

private fun File.isInside(parent: File): Boolean {
    val selfPath = runCatching { canonicalFile.toPath() }.getOrElse { absoluteFile.toPath() }
    val parentPath = runCatching { parent.canonicalFile.toPath() }.getOrElse { parent.absoluteFile.toPath() }
    return selfPath.startsWith(parentPath)
}
