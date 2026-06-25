import org.fusesource.jansi.AnsiConsole
import java.io.File
import kotlin.time.measureTimedValue

fun main(args: Array<String>) {
    System.setProperty("jansi.passthrough", "true")
    AnsiConsole.systemInstall()

    if (args.isEmpty()) {
        println("Usage: kodedocs-engine <root_dir> [output_dir]")
        return
    }

    val rootDir = File(args[0]).absoluteFile
    val outputDir = if (args.size > 1) File(args[1]).absoluteFile else rootDir.resolve("build/site").absoluteFile

    println("Building from ${rootDir.path} to ${outputDir.path}...")
    try {
        val (_, duration) = measureTimedValue {
            Engine().build(rootDir, outputDir)
        }
        println("Build completed in $duration")
    } catch (e: Exception) {
        System.err.println("Build failed: ${e.message}")
        e.printStackTrace()
    }
}