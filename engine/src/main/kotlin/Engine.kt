
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class Engine {
    private val json = Json{
        prettyPrint = true
    }
    private val parser = MarkDownParser()
    private val preProcessor = PreProcessor()

    @Serializable
    data class ManifestPageEntry(
        val version: String,
        val language: String,
        val sourcePath: String,
        val contentPath: String,
        val title: String,
        val description: String?
    ){
        fun pageKey(): String = "$version/$language/$sourcePath"

    }

    @Serializable
    data class PageContent(
        val manifestPageEntry: ManifestPageEntry,
        val html: String,
        val metadata: Map<String, List<String>>,
        val headings: List<MarkDownParser.Heading>
    )

    @Serializable
    data class Page(
        val title: String,
        val description: String?,
        val html: String,
        val metadata: Map<String, List<String>>,
        val headings: List<MarkDownParser.Heading>
    )

    @Serializable
    data class Manifest(
        val defaultVersion: String,
        val defaultLanguage: String,
        val defaultPage: String,
        val versions: List<String>,
        val languages: List<String>,
        val languagesByVersion: Map<String, List<String>>,
        val pages: Map<String, ManifestPageEntry>,
        val trees: Map<String, List<TreeNode>>
    )

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
                    val preProcessedContent = preProcessor.process(mdFile)
                    val parsed = parser.parse(preProcessedContent)
                    val title = parsed.metadata["title"]?.get(0) ?: mdFile.nameWithoutExtension.toTitle()
                    val description = parsed.metadata["description"]?.get(0) ?: ""
                    val contentPath = "content/$version/$language/${pagePath}.json"

                    val manifestPageEntry = ManifestPageEntry(
                        version = version,
                        language = language,
                        sourcePath = sourcePath,
                        contentPath = contentPath,
                        title = title,
                        description = description
                    )
                    pageContents += PageContent(
                        manifestPageEntry = manifestPageEntry,
                        html = parsed.html,
                        metadata = parsed.metadata,
                        headings = parsed.headings
                    )
                }
            }
        }

        val highlightedHtml = highlightPages(outputDir, pageContents).associateBy { it.id }
        pageContents.forEach { content ->
            outputDir.resolve(content.manifestPageEntry.contentPath).also { outFile ->
                outFile.parentFile.mkdirs()
                outFile.writeText(
                    json.encodeToString(Page(
                        title = content.manifestPageEntry.title,
                        description = content.manifestPageEntry.description,
                        html = highlightedHtml[content.manifestPageEntry.pageKey()]?.html ?: content.html,
                        metadata = content.metadata,
                        headings = content.headings
                    ))
                )
            }
        }

        outputDir.resolve("manifest.json").writeText(manifestJson(versions, languagesByVersion, pageContents.map { it.manifestPageEntry }))
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
        manifestPageEntries: List<ManifestPageEntry>
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
        val defaultPage = manifestPageEntries.firstOrNull {
            it.version == defaultVersion && it.language == defaultLanguage && it.sourcePath == "index.md"
        }?.sourcePath ?: manifestPageEntries.firstOrNull {
            it.version == defaultVersion && it.language == defaultLanguage
        }?.sourcePath ?: manifestPageEntries.firstOrNull()?.sourcePath ?: "index.md"

        val allLanguages = languagesByVersion.values.flatten().distinct().sorted()

        val manifest = Manifest(
            defaultVersion = defaultVersion,
            defaultLanguage = defaultLanguage,
            defaultPage = defaultPage,
            versions = versions,
            languages = allLanguages,
            languagesByVersion = languagesByVersion,
            pages = manifestPageEntries.associateBy { it.pageKey() },
            trees = manifestPageEntries.groupBy { "${it.version}/${it.language}" }.mapValues { treeJson(it.value) }
        )

        return json.encodeToString(manifest)
    }

    private fun treeJson(manifestPageEntries: List<ManifestPageEntry>): List<TreeNode> {
        val root = NavNode("")
        manifestPageEntries.sortedBy { it.sourcePath }.forEach { page ->
            val parts = page.sourcePath.split("/")
            var current = root
            parts.dropLast(1).forEach { part ->
                current = current.children.getOrPut(part) { NavNode(part) }
            }
            current.manifestPageEntries += page
        }
        return navChildrenJson(root)
    }

    private fun navChildrenJson(node: NavNode): List<TreeNode> {
        val groups = node.children.values.sortedBy { it.name }.map {
            TreeNode(TreeNode.TreeNodeType.Group, it.name.toTitle(),navChildrenJson(it))
        }
        val files = node.manifestPageEntries.sortedBy { it.sourcePath }.map {
            TreeNode(TreeNode.TreeNodeType.Page, it.title, path = it.sourcePath)
        }
        return (groups + files)
    }

    @Serializable
    data class TreeNode(
        val type: TreeNodeType,
        val title: String,
        val children: List<TreeNode> = emptyList(),
        val path: String = ""
    ){
        enum class TreeNodeType {
            Group, Page
        }
    }

    data class NavNode(
        val name: String,
        val children: MutableMap<String, NavNode> = linkedMapOf(),
        val manifestPageEntries: MutableList<ManifestPageEntry> = mutableListOf()
    )

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
                """{"id": ${content.manifestPageEntry.pageKey().jsonString()}, "html": ${content.html.jsonString()}}"""
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
            val result = json.decodeFromString<Result>(outputFile.readText())
            return result.pages
        }catch (e: StackOverflowError) {
            e.printStackTrace()
            throw e
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
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
}
