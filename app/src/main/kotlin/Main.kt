package com.jimmy.app

import Engine
import io.methvin.watcher.DirectoryWatcher
import isInside
import kotlinx.coroutines.*
import org.fusesource.jansi.AnsiConsole
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

fun main(args: Array<String>) = runBlocking {
    System.setProperty("jansi.passthrough", "true")
    AnsiConsole.systemInstall()

    val log = LoggerFactory.getLogger("Main")

    if (args.isEmpty()) {
        println("Usage: kodedocs <root_dir> [output_dir] [--host <host>] [--port <port>]")
        return@runBlocking
    }

    val rootDir = File(args[0]).absoluteFile
    var outputDir = rootDir.resolve("build/site").absoluteFile
    var host = "0.0.0.0"
    var port = 8080

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--host" -> {
                if (i + 1 < args.size) {
                    host = args[i + 1]
                    i++
                }
            }
            "--port" -> {
                if (i + 1 < args.size) {
                    port = args[i + 1].toIntOrNull() ?: 8080
                    i++
                }
            }
            else -> {
                if (i == 1 && !args[i].startsWith("--")) {
                    outputDir = File(args[i]).absoluteFile
                }
            }
        }
        i++
    }

    val engine = Engine()

    val (_, duration) = measureTimedValue {
        engine.build(rootDir, outputDir)
    }
    log.info("Build completed in $duration")

    val server = PreviewServer(outputDir, host, port)
    server.start()

    var reloadJob: Job? = null
    var lastRebuildTime = 0L

    val watcher = DirectoryWatcher.builder()
        .paths(listOf(rootDir.toPath(), outputDir.toPath()))
        .listener { event ->
            val changedFile = event.path().toFile().absoluteFile
            val fileName = changedFile.name

            if (fileName.endsWith("~") || fileName.endsWith(".tmp") || fileName.endsWith(".swp") || fileName.startsWith(".")) return@listener

            if (isInside(outputDir, changedFile)) {
                reloadJob?.cancel()
                reloadJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(50.milliseconds)
                    server.reload()
                }
                return@listener
            }

            val now = System.currentTimeMillis()
            if (now - lastRebuildTime < 300) {
                return@listener
            }

            if (!changedFile.path.contains("${File.separator}build${File.separator}")) {
                log.info("Change detected in ${changedFile.path} , Updating...")
                lastRebuildTime = now

                try {
                    val rootDirAbsolute = rootDir.absoluteFile
                    val changedFileAbsolute = changedFile.absoluteFile

                    when {
                        isInside(rootDirAbsolute.resolve("assets"), changedFileAbsolute) -> {
                            engine.buildAssets(rootDir, outputDir)
                        }
                        isInside(rootDirAbsolute.resolve("content"), changedFileAbsolute) ||
                                isInside(rootDirAbsolute.resolve("pages"), changedFileAbsolute) -> {
                            if (changedFile.extension.lowercase() == "md") {
                                engine.buildSinglePage(rootDir, outputDir, changedFile)
                            } else {
                                engine.build(rootDir, outputDir)
                            }
                        }
                        else -> {
                            engine.build(rootDir, outputDir)
                        }
                    }
                } catch (e: Exception) {
                    log.error("Build failed", e)
                }
            }
        }
        .build()

    log.info("Watching for changes in : ${rootDir.path}")
    watcher.watch()
}
