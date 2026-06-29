
import java.io.File
import kotlin.time.measureTimedValue

fun main(args: Array<String>) {

    if (args.isEmpty()) {
        println("Usage: kodedocs-engine <root_dir> [output_dir]")
        return
    }

    val rootDir = File(args[0]).absoluteFile
    val outputDir = if (args.size > 1) File(args[1]).absoluteFile else rootDir.resolve("build/site").absoluteFile

    println("Building from ${rootDir.path} to ${outputDir.path}")
    try {
        val engine = Engine(rootDir, outputDir)
        val (_, duration) = measureTimedValue {
            engine.build()
        }
        println("Build completed in $duration")
    } catch (e: Exception) {
        System.err.println("Build failed: ${e.message}")
        e.printStackTrace()
    }
}