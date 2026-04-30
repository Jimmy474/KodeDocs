import kotlinx.serialization.KSerializer
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

class Engine {
    private val parser = MarkDownParser()
    private val preProcessor = PreProcessor()

    @Serializable
    data class Page(
        val version: String,
        val language: String,
        val sourcePath: String,
        val contentPath: String,
        val title: String,
        val description: String?
    )

    @Serializable
    data class PageContent(
        val page: Page,
        val html: String,

        val metadata: Map<String, @Serializable(with = MetadataAnySerializer::class) Any>,
        val headings: List<MarkDownParser.Heading>
    )

    class MetadataAnySerializer(): KSerializer<Any>{
        override val descriptor: SerialDescriptor = String.serializer().descriptor
        override fun serialize(encoder: Encoder, value: Any) {
            when (value) {
                is String -> encoder.encodeString(value)
                is List<*> -> encoder.encodeSerializableValue(ListSerializer(String.serializer()), value.map { it.toString() })
                else -> throw IllegalArgumentException("Metadata must be a String or a List<String>")
            }
        }

        override fun deserialize(decoder: Decoder): Any {
            val value = decoder.decodeString()
            return when {
                value.startsWith("[") && value.endsWith("]") -> {
                    value.drop(1).dropLast(1).split(",").map { it.trim() }
                }
                else -> value
            }
        }

    }

    fun build(docsDir: File, outputDir: File) {
        if (!docsDir.exists()) throw IllegalArgumentException("Docs directory does not exist")
        if (!outputDir.exists()) outputDir.mkdirs()

        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val assetsDir = outputDir.resolve("assets")
        assetsDir.mkdirs()
        File("../assets").copyRecursively(assetsDir, overwrite = true)
        writeShell(outputDir)
        File(outputDir, "/assets/index.html").delete()

        val versions = docsDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        if (versions.isEmpty()) {
            println("No versions found in ${docsDir.path}")
            outputDir.resolve("manifest.json").writeText(emptyManifest())
            return
        }

        val pageContents = mutableListOf<PageContent>()
        val languagesByVersion = linkedMapOf<String, List<String>>()

        for (version in versions) {
            val versionDir = docsDir.resolve(version)
            val languages = versionDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
            languagesByVersion[version] = languages

            for (language in languages) {
                val langDir = versionDir.resolve(language)
                val mdFiles = langDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                    .sortedBy { it.relativeTo(langDir).invariantSeparatorsPath }
                    .toList()

                for (mdFile in mdFiles) {
                    val sourcePath = mdFile.relativeTo(langDir).invariantSeparatorsPath
                    val pagePath = sourcePath.removeSuffix(".md")
                    val preProcessed = preProcessor.process(mdFile)
                    val parsed = parser.parse(preProcessed.content)
                    val title = preProcessed.metadata["title"] as? String
                        ?: mdFile.nameWithoutExtension.toTitle()
                    val description = preProcessed.metadata["description"] as? String
                    val contentPath = "content/$version/$language/${pagePath}.json"

                    val page = Page(
                        version = version,
                        language = language,
                        sourcePath = sourcePath,
                        contentPath = contentPath,
                        title = title,
                        description = description
                    )
                    pageContents += PageContent(
                        page = page,
                        html = parsed.html,
                        metadata = preProcessed.metadata,
                        headings = parsed.headings
                    )
                }
            }
        }

        val highlightedHtml = highlightPages(outputDir, pageContents).associateBy { it.id }
        pageContents.forEach { content ->
            outputDir.resolve(content.page.contentPath).also { outFile ->
                outFile.parentFile.mkdirs()
                outFile.writeText(
                    pageJson(
                        title = content.page.title,
                        description = content.page.description,
                        html = highlightedHtml[content.page.pageKey()]?.html ?: content.html,
                        metadata = content.metadata,
                        headings = content.headings
                    )
                )
            }
        }

        outputDir.resolve("manifest.json").writeText(manifestJson(versions, languagesByVersion, pageContents.map { it.page }))
    }

    private fun writeShell(outputDir: File) {
        val assetShell = File("../assets/index.html")
        val shell = assetShell.readText()
            .replace("href=\"./style.css\"", "href=\"/assets/style.css\"")
            .replace("src=\"./script.js\"", "src=\"/assets/script.js\"")
            .replace("href=\"./index.html\"", "href=\"/\"")
        outputDir.resolve("index.html").writeText(shell)
    }

    private fun emptyManifest(): String = """
        {
          "defaultVersion": "",
          "defaultLanguage": "",
          "defaultPage": "index.md",
          "versions": [],
          "languages": [],
          "languagesByVersion": {},
          "pages": {},
          "trees": {}
        }
    """.trimIndent()

    private fun manifestJson(
        versions: List<String>,
        languagesByVersion: Map<String, List<String>>,
        pages: List<Page>
    ): String {
        val defaultVersion = when {
            "latest" in versions -> "latest"
            else -> versions.first()
        }
        val defaultLanguage = when {
            languagesByVersion[defaultVersion]?.contains("en") == true -> "en"
            else -> languagesByVersion[defaultVersion]?.firstOrNull()
                ?: languagesByVersion.values.flatten().firstOrNull()
                ?: ""
        }
        val defaultPage = pages.firstOrNull {
            it.version == defaultVersion && it.language == defaultLanguage && it.sourcePath == "index.md"
        }?.sourcePath ?: pages.firstOrNull {
            it.version == defaultVersion && it.language == defaultLanguage
        }?.sourcePath ?: pages.firstOrNull()?.sourcePath ?: "index.md"

        val allLanguages = languagesByVersion.values.flatten().distinct().sorted()
        val pageEntries = pages.joinToString(",\n") { page ->
            val key = page.pageKey()
            """
              ${key.jsonString()}: {
                "version": ${page.version.jsonString()},
                "language": ${page.language.jsonString()},
                "sourcePath": ${page.sourcePath.jsonString()},
                "contentPath": ${page.contentPath.jsonString()},
                "title": ${page.title.jsonString()},
                "description": ${page.description.jsonNullableString()}
              }""".trimIndent()
        }
        val treeEntries = pages
            .groupBy { "${it.version}/${it.language}" }
            .toSortedMap()
            .map { (key, pageGroup) ->
                "  ${key.jsonString()}: ${treeJson(pageGroup)}"
            }
            .joinToString(",\n")
        val langsByVersionJson = languagesByVersion.entries.joinToString(",\n") { (version, languages) ->
            "  ${version.jsonString()}: ${languages.jsonArray()}"
        }

        return """
            {
              "defaultVersion": ${defaultVersion.jsonString()},
              "defaultLanguage": ${defaultLanguage.jsonString()},
              "defaultPage": ${defaultPage.jsonString()},
              "versions": ${versions.jsonArray()},
              "languages": ${allLanguages.jsonArray()},
              "languagesByVersion": {
            $langsByVersionJson
              },
              "pages": {
            $pageEntries
              },
              "trees": {
            $treeEntries
              }
            }
        """.trimIndent()
    }

    private fun pageJson(
        title: String,
        description: String?,
        html: String,
        metadata: Map<String, Any>,
        headings: List<MarkDownParser.Heading>
    ): String = """
        {
          "title": ${title.jsonString()},
          "description": ${description.jsonNullableString()},
          "html": ${html.jsonString()},
          "metadata": ${metadataJson(metadata)},
          "headings": ${headingsJson(headings)}
        }
    """.trimIndent()

    private fun treeJson(pages: List<Page>): String {
        val root = NavNode("")
        pages.sortedBy { it.sourcePath }.forEach { page ->
            val parts = page.sourcePath.split("/")
            var current = root
            parts.dropLast(1).forEach { part ->
                current = current.children.getOrPut(part) { NavNode(part) }
            }
            current.pages += page
        }
        return navChildrenJson(root)
    }

    private fun navChildrenJson(node: NavNode): String {
        val groups = node.children.values.sortedBy { it.name }.map {
            """
              {
                "type": "group",
                "title": ${it.name.toTitle().jsonString()},
                "children": ${navChildrenJson(it)}
              }""".trimIndent()
        }
        val files = node.pages.sortedBy { it.sourcePath }.map {
            """
              {
                "type": "page",
                "title": ${it.title.jsonString()},
                "path": ${it.sourcePath.jsonString()}
              }""".trimIndent()
        }
        return (groups + files).joinToString(prefix = "[", postfix = "]", separator = ",\n")
    }

    private data class NavNode(
        val name: String,
        val children: MutableMap<String, NavNode> = linkedMapOf(),
        val pages: MutableList<Page> = mutableListOf()
    )

    private fun Page.pageKey(): String = "$version/$language/$sourcePath"

    @Serializable
    data class HighlightedPage(
        val id: String,
        val html: String
    )

    @Serializable
    data class Result(
        val pages: List<HighlightedPage>
    )

    private fun highlightPages(outputDir: File, pages: List<PageContent>): List<HighlightedPage> {
        if (pages.isEmpty()) return emptyList()

        val inputFile = outputDir.resolve(".shiki-input.json")
        val outputFile = outputDir.resolve(".shiki-output.json")
        val serviceFile = File("../tools/shiki-highlighter.mjs").canonicalFile

        inputFile.writeText(
            pages.joinToString(prefix = """{"pages":[""", postfix = "]}", separator = ",") { content ->
                """{"id": ${content.page.pageKey().jsonString()}, "html": ${content.html.jsonString()}}"""
            }
        )

        try {
            val process = ProcessBuilder("node", serviceFile.path, inputFile.path, outputFile.path)
                .directory(File("..").canonicalFile)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException(
                    "Shiki highlighter failed. Run npm install from the project root, then rebuild.\n$output"
                )
            }
            if (!outputFile.exists()) return emptyList()
            val result = Json.decodeFromString<Result>(outputFile.readText())
            return result.pages
        }catch (e: StackOverflowError) {
            e.printStackTrace()
            throw e
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    private fun metadataJson(metadata: Map<String, Any>): String {
        if (metadata.isEmpty()) return "{}"
        return metadata.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val jsonValue = when (value) {
                is List<*> -> value.filterIsInstance<String>().jsonArray()
                else -> value.toString().jsonString()
            }
            "${key.jsonString()}: $jsonValue"
        }
    }

    private fun headingsJson(headings: List<MarkDownParser.Heading>): String =
        headings.joinToString(prefix = "[", postfix = "]") { heading ->
            """{"level": ${heading.level}, "text": ${heading.text.jsonString()}, "id": ${heading.id.jsonString()}}"""
        }

    private fun String.toTitle(): String =
        replace("-", " ")
            .replace("_", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun String.jsonString(): String = buildString {
        append('"')
        this@jsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
        append('"')
    }

    private fun String?.jsonNullableString(): String = this?.jsonString() ?: "null"

    private fun List<String>.jsonArray(): String = joinToString(prefix = "[", postfix = "]") { it.jsonString() }

    private fun String.jsonUnescape(): String {
        val output = StringBuilder()
        var index = 0
        while (index < length) {
            val char = this[index]
            if (char != '\\' || index == lastIndex) {
                output.append(char)
                index++
                continue
            }

            when (val escaped = this[index + 1]) {
                '"' -> output.append('"')
                '\\' -> output.append('\\')
                '/' -> output.append('/')
                'b' -> output.append('\b')
                'f' -> output.append('\u000C')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> {
                    val code = substring(index + 2, index + 6).toInt(16)
                    output.append(code.toChar())
                    index += 4
                }
                else -> output.append(escaped)
            }
            index += 2
        }
        return output.toString()
    }
}
