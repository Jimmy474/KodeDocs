
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.random.Random

fun main() {
//    val temp = TreeSitterHighlighter()
//
//    println(temp.highlightMarkdown("""
//        hello
//
//        ```java
//        public class TestClass {}
//        ```
//
//        ```java
//        public class TestClass0 {}
//        public class TestClass1 {}
//        public class TestClass2 {}
//        ```
//
//        world
//    """.trimIndent()))

    val languages = listOf("en", "fr", "es", "de", "it", "ja", "ko", "zh")
    val versions = buildList { repeat(15){ add("${it+1}.0.0") } }
    generateTestFiles(Path.of("docs"), 40, languages, versions)
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