
import java.io.File
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

fun main() {
//    val root = File("docs/content/1.0.0/ab")
//    val root2 = File("docs/content/1.0.0")
//    val versions = listOf("2.0.0","3.0.0","4.0.0","5.0.0")
//    val languages = listOf("cd","ef","gi","hj","kl","mn","op","qr","st")
//    for(language in languages){
//        root.copyRecursively(File("docs/content/1.0.0/$language"), overwrite = true)
//    }
//    for(version in versions){
//        root2.copyRecursively(File("docs/content/$version"), overwrite = true)
//    }
//    fixFile(root)


    val root = File("../docs")
    val tries = 10
    val scores = mutableListOf<Long>()
    val engine = Engine(root, root.resolve("build/site"))
    repeat(tries) {
        val (_, duration) = measureTimedValue {
            engine.build()
        }
        println("Build attempt ${it + 1} completed in $duration")
        scores.add(duration.inWholeMilliseconds)
    }
    println("Average build time: ${scores.average().milliseconds}")
}

fun fixFile(file: File) {
    val regex = Regex("""^<<<\s+@/(?<path>.*?)(?<region>#.*?)?\[(.*?)]$""", RegexOption.MULTILINE)
//    val regex = Regex("""^:::\s*(a-zA-Z-)\s+([a-zA-Z]+)\n([\s\S]*?)\n:::$""", RegexOption.MULTILINE)
    if (file.isDirectory) {
        file.listFiles()?.forEach { fixFile(it) }
    }else{
        val content = file.readText()
        file.writeText(regex.replace(content){
            val path = it.groups["path"]?.value ?: ""
            val region = it.groups["region"]?.value ?: ""
            "<<< @/$path$region"
        })
    }
}

@OptIn(ExperimentalPathApi::class)
fun generateTestFiles(
    rootDir: Path,
    numberOfFiles: Int,
    languages: List<String>,
    versions: List<String>
) {
    rootDir.deleteRecursively()
    rootDir.createDirectories()
    val folderNames = listOf("examples", "api", "guides", "reference", "tutorials", "advanced", "basic", "samples", "notes")
    versions.forEach { version ->
        languages.forEach { language ->
            repeat(numberOfFiles) { index ->
                val depth = Random.nextInt(1, 4)
                var currentPath = rootDir.resolve(version).resolve(language)
                repeat(depth) {
                    currentPath = currentPath.resolve(folderNames.random())
                }

                currentPath.createDirectories()
                val file = currentPath.resolve("file_${index + 1}.md")
                file.writeText(generateMarkdownContent())
            }
        }
    }
}

private fun generateMarkdownContent(): String {
    val passes = Random.nextInt(6, 11)
    return buildString {
        repeat(passes) {
            if (Random.nextBoolean()) append(generateParagraph()) else append(generateCodeBlock())
            append("\n\n")
        }
    }
}

private fun generateParagraph(): String {
    val sentences = listOf(
        "Modern software systems often require careful planning and extensive testing to ensure maintainability and reliability over long periods of development.",
        "Documentation plays a crucial role in helping developers understand design decisions and implementation details across multiple modules.",
        "Large code bases benefit from clear naming conventions and a consistent approach to architecture and dependency management.",
        "Performance optimization should generally be based on measurement rather than assumptions because bottlenecks are often surprising.",
        "Many engineering teams rely on automated pipelines to verify correctness and prevent regressions during continuous integration.",
        "Readable code tends to reduce maintenance costs and improves collaboration between developers working on different features.",
        "Scalable applications often separate responsibilities into smaller components that are easier to test and extend."
    )

    val count = Random.nextInt(8, 16)

    return buildString {
        repeat(count) {
            append(sentences.random())
            append(' ')
        }
    }
}

private fun generateCodeBlock(): String {
    val lines = Random.nextInt(1, 100)
    return buildString {
        appendLine("```java")
        repeat(lines) {
            appendLine("public class TestClass$it {}")
        }
        append("```")
    }
}