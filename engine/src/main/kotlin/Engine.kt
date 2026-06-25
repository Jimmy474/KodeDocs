
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class Engine {

    private val log: Logger = LoggerFactory.getLogger(this::class.java)

    private val json = Json {
        prettyPrint = false
    }
    private val pageEntries = linkedMapOf<String, ManifestPageEntry>()
    private val standalonePageEntries = linkedMapOf<String, ManifestPageEntry>()
    private var lastVersions: List<String> = emptyList()
    private var lastLanguagesByVersion: Map<String, List<String>> = emptyMap()

    @Serializable
    data class ManifestPageEntry(
        val version: String,
        val language: String,
        val sourcePath: String,
        val contentPath: String,
        val title: String,
        val description: String?
    ) {
        fun pageKey(): String = "$version/$language/$sourcePath"
    }

    data class PageContent(
        val manifestPageEntry: ManifestPageEntry,
        val sourceFile: File,
        val html: String,
        val metadata: Map<String, List<String>>,
        val headings: List<MarkDownParser.Heading>
    )

    @Serializable
    data class PageMeta(
        val title: String,
        val description: String?,
        val metadata: Map<String, List<String>>,
        val headings: List<MarkDownParser.Heading>
    )

    @Serializable
    data class NavItem(
        val text: String,
        val link: String
    )

    @Serializable
    data class BrandConfig(
        val name: String,
        val logoText: String? = null,
        val logoImage: String? = null
    )

    @Serializable
    data class SiteConfig(
        val brand: BrandConfig? = null,
        val nav: List<NavItem> = emptyList(),
        val languageAliases: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class Manifest(
        val site: SiteConfig? = null,
        val defaultVersion: String,
        val defaultLanguage: String,
        val defaultPage: String,
        val versions: List<String>,
        val languages: List<String>,
        val languagesByVersion: Map<String, List<String>>,
        val pages: Map<String, ManifestPageEntry>,
        val standalonePages: Map<String, ManifestPageEntry> = emptyMap(),
        val trees: Map<String, List<TreeNode>>
    )

    @Synchronized
    fun build(rootDir: File, outputDir: File) {
        if (!rootDir.exists()) throw IllegalArgumentException("Root directory does not exist at path: ${rootDir.path}")

        val contentDir = rootDir.resolve("content")
        val pagesDir = rootDir.resolve("pages")
        val siteConfig = loadSiteConfig(rootDir)

        if (!outputDir.exists()) outputDir.mkdirs()

        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val outputAssetsDir = outputDir.resolve("assets")
        outputAssetsDir.mkdirs()

        buildAssets(rootDir, outputDir)
        writeShell(outputDir)

        if (!contentDir.exists()) {
            log.info("No content directory found in ${rootDir.path}")
            outputDir.resolve("manifest.json").writeText(emptyManifest())
            return
        }

        val versions = contentDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        if (versions.isEmpty()) {
            log.info("No versions found in ${contentDir.path}")
            outputDir.resolve("manifest.json").writeText(emptyManifest())
            return
        }

        val languagesByVersion = linkedMapOf<String, List<String>>()
        val pageInputs = mutableListOf<Triple<String, String, File>>()
        pageEntries.clear()
        standalonePageEntries.clear()

        for (version in versions) {
            val versionDir = contentDir.resolve(version)
            val languages = versionDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
            languagesByVersion[version] = languages

            for (language in languages) {
                val langDir = versionDir.resolve(language)
                val mdFiles = langDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                    .sortedBy { it.relativeTo(langDir).invariantSeparatorsPath }
                    .toList()

                mdFiles.forEach { mdFile -> pageInputs += Triple(version, language, mdFile) }
            }
        }

        val completedPages = AtomicInteger(0)
        val parseTotalPages = pageInputs.size.coerceAtLeast(1)
        val cores = Runtime.getRuntime().availableProcessors()
        val chunkSize = maxOf(1, pageInputs.size / cores)

        val pageContents = runBlocking {
            pageInputs.chunked(chunkSize).map { chunk ->
                async(Dispatchers.Default) {
                    chunk.map { (version, language, mdFile) ->
                        val langDir = contentDir.resolve(version).resolve(language)
                        val content = buildPageContent(rootDir, version, language, langDir, mdFile, siteConfig?.languageAliases)
                        val completed = completedPages.incrementAndGet()
                        printProgress(completed, parseTotalPages, "Parsing : ${content.manifestPageEntry.sourcePath}")

                        content
                    }
                }
            }.awaitAll().flatten()
        }.sortedBy { it.manifestPageEntry.pageKey() }
        println()

        TreeSitterHighlighter.clearMemory()

        val totalPages = pageContents.size.coerceAtLeast(1)
        pageContents.forEachIndexed { i, content ->
            pageEntries[content.manifestPageEntry.pageKey()] = content.manifestPageEntry
            writePage(outputDir, content)
            printProgress(i + 1, totalPages, "Built : ${content.manifestPageEntry.contentPath}")
        }
        println("\n")

        if (pagesDir.exists()) {
            val standaloneMdFiles = pagesDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                .sortedBy { it.relativeTo(pagesDir).invariantSeparatorsPath }
                .toList()

            standaloneMdFiles.forEach { mdFile ->
                val content = buildStandalonePageContent(rootDir, pagesDir, mdFile, siteConfig?.languageAliases)
                standalonePageEntries[content.manifestPageEntry.sourcePath] = content.manifestPageEntry
                writePage(outputDir, content)
            }
        }

        lastVersions = versions
        lastLanguagesByVersion = languagesByVersion
        updateManifestAndWrite(outputDir, siteConfig)
    }

    fun loadSiteConfig(rootDir: File): SiteConfig? {
        val configFile = File(rootDir, "config.json")
        return if (configFile.exists()) {
            runCatching { json.decodeFromString<SiteConfig>(configFile.readText()) }.getOrNull()
        } else null
    }

    fun buildAssets(rootDir: File, outputDir: File) {
        val assetsDir = rootDir.resolve("assets")
        val outputAssetsDir = outputDir.resolve("assets")
        if (!outputAssetsDir.exists()) outputAssetsDir.mkdirs()

        if (assetsDir.exists()) {
            assetsDir.copyRecursively(outputAssetsDir, overwrite = true)
        }
        writeBundledAssets(outputAssetsDir)
        File(outputAssetsDir, "index.html").delete()
    }

    private fun resolvePageKey(mdFile: File, contentDir: File, pagesDir: File): String? {
        return when {
            isInside(contentDir, mdFile) -> {
                val relativePath = mdFile.relativeTo(contentDir).invariantSeparatorsPath
                val parts = relativePath.split("/")
                if (parts.size >= 3) {
                    val version = parts[0]
                    val language = parts[1]
                    val sourcePath = parts.drop(2).joinToString("/")
                    "$version/$language/$sourcePath"
                } else null
            }
            isInside(pagesDir, mdFile) -> {
                mdFile.relativeTo(pagesDir).invariantSeparatorsPath
            }
            else -> null
        }
    }

    private fun updateManifestAndWrite(outputDir: File, siteConfig: SiteConfig?) {
        writeManifest(outputDir, siteConfig)
    }

    fun buildSinglePage(rootDir: File, outputDir: File, mdFile: File) {
        val contentDir = rootDir.resolve("content")
        val pagesDir = rootDir.resolve("pages")
        val siteConfig = loadSiteConfig(rootDir)

        if (!mdFile.exists()) {
            val removedKey = resolvePageKey(mdFile, contentDir, pagesDir)

            removedKey?.let {
                if (isInside(contentDir, mdFile)) {
                    pageEntries.remove(it)
                } else {
                    standalonePageEntries.remove(it)
                }
                updateManifestAndWrite(outputDir, siteConfig)
            }
            return
        }

        val content = buildPageInDir(rootDir, contentDir, pagesDir, mdFile, siteConfig)

        content?.let {
            if (it.manifestPageEntry.version != "standalone") {
                pageEntries[it.manifestPageEntry.pageKey()] = it.manifestPageEntry
            } else {
                standalonePageEntries[it.manifestPageEntry.sourcePath] = it.manifestPageEntry
            }
            writePage(outputDir, it)
            updateManifestAndWrite(outputDir, siteConfig)
        }
    }

    private fun buildPageInDir(
        rootDir: File,
        contentDir: File,
        pagesDir: File,
        mdFile: File,
        siteConfig: SiteConfig?
    ): PageContent? {
        return when {
            isInside(contentDir, mdFile) -> {
                val relativePath = mdFile.relativeTo(contentDir).invariantSeparatorsPath
                val parts = relativePath.split("/")
                if (parts.size >= 3) {
                    val version = parts[0]
                    val language = parts[1]
                    val langDir = contentDir.resolve(version).resolve(language)
                    buildPageContent(rootDir, version, language, langDir, mdFile, siteConfig?.languageAliases)
                } else null
            }
            isInside(pagesDir, mdFile) -> {
                buildStandalonePageContent(rootDir, pagesDir, mdFile, siteConfig?.languageAliases)
            }
            else -> null
        }
    }

    private fun writeBundledAssets(outputAssetsDir: File) {
        val assets = listOf("style.css", "script.js")
        assets.forEach { asset ->
            val content = this::class.java.getResourceAsStream("/$asset")?.bufferedReader()?.use { it.readText() }
            if (content != null) {
                outputAssetsDir.resolve(asset).writeText(content)
            } else {
                log.error("Bundled asset $asset not found in resources")
            }
        }

        val fontsDir = outputAssetsDir.resolve("fonts")
        val fonts = listOf("InterVariable.woff2", "InterVariable-Italic.woff2", "JetBrainsMono-Regular.woff2")
        fonts.forEach { font ->
            val fontBytes = this::class.java.getResourceAsStream("/fonts/$font")?.use { it.readBytes() }
            if (fontBytes != null) {
                fontsDir.mkdirs()
                fontsDir.resolve(font).writeBytes(fontBytes)
            } else {
                log.warn("Bundled font /fonts/$font not found in resources")
            }
        }
    }

    private fun buildPageContent(
        rootDir: File,
        version: String,
        language: String,
        langDir: File,
        mdFile: File,
        langAliases: Map<String, String>? = null,
    ): PageContent {
        val sourcePath = mdFile.relativeTo(langDir).invariantSeparatorsPath
        val pagePath = sourcePath.removeSuffix(".md")
        val preProcessedContent = PreProcessor.process(rootDir,mdFile)
        val highlightedMarkdown = TreeSitterHighlighter.highlightMarkdown(preProcessedContent, langAliases)
        val parsed = MarkDownParser.parse(highlightedMarkdown)
        val title = parsed.metadata["title"]?.get(0) ?: mdFile.nameWithoutExtension.toTitle()
        val description = parsed.metadata["description"]?.get(0) ?: ""
        val contentPath = "content/$version/$language/${pagePath}.html"
        val manifestPageEntry = ManifestPageEntry(version, language, sourcePath, contentPath, title, description)

        return PageContent(manifestPageEntry, mdFile, parsed.html, parsed.metadata, parsed.headings)
    }

    private fun buildStandalonePageContent(
        rootDir: File,
        pagesDir: File,
        mdFile: File,
        langAliases: Map<String, String>? = null,
    ): PageContent {
        val relativePath = mdFile.relativeTo(pagesDir).invariantSeparatorsPath
        val pagePath = relativePath.removeSuffix(".md")
        val preProcessedContent = PreProcessor.process(rootDir, mdFile)
        val highlightedMarkdown = TreeSitterHighlighter.highlightMarkdown(preProcessedContent, langAliases)
        val parsed = MarkDownParser.parse(highlightedMarkdown)
        val title = parsed.metadata["title"]?.firstOrNull() ?: mdFile.nameWithoutExtension.toTitle()
        val description = parsed.metadata["description"]?.firstOrNull()
        val contentPath = "pages/$pagePath.html"
        val manifestPageEntry = ManifestPageEntry("standalone", "standalone", relativePath, contentPath, title, description)

        return PageContent(manifestPageEntry, mdFile, parsed.html, parsed.metadata, parsed.headings)
    }

    private fun writePage(outputDir: File, content: PageContent) {
        outputDir.resolve(content.manifestPageEntry.contentPath).also { outFile ->
            outFile.parentFile.mkdirs()
            outFile.writeText(renderPageHtml(content))
        }
    }

    private fun writeManifest(outputDir: File, siteConfig: SiteConfig?) {
        outputDir.resolve("manifest.json").writeText(
            manifestJson(lastVersions, lastLanguagesByVersion, pageEntries.values.toList(), standalonePageEntries, siteConfig)
        )
    }

    private fun renderPageHtml(content: PageContent): String {
        val meta = PageMeta(
            title = content.manifestPageEntry.title,
            description = content.manifestPageEntry.description,
            metadata = content.metadata,
            headings = content.headings
        )
        val authors = content.metadata["authors"].orEmpty()
        val metaJson = json.encodeToString(meta).replace("</", "<\\/")
        return """
            <script type="application/json" data-page-meta>$metaJson</script>
            ${if (authors.isNotEmpty()) """
                <section class="main-authors" aria-label="Page authors">
                    <div class="authors">${authors.joinToString("") { renderAuthor(it) }}</div>
                </section>
            """.trimIndent() else ""}
            <h1>${content.manifestPageEntry.title.escapeHtml()}</h1>
            ${content.manifestPageEntry.description?.takeIf { it.isNotBlank() }?.let { "<p class=\"lead\">${it.escapeHtml()}</p>" }.orEmpty()}
            ${content.html}
        """.trimIndent()
    }

    private fun renderAuthor(author: String): String {
        val username = author.replace(Regex("""[^a-zA-Z0-9_-]"""), "")
        if (username.isBlank()) return ""
        val escaped = username.escapeHtml()
        return """
            <a href="https://github.com/$escaped" target="_blank" rel="noreferrer" class="author" title="$escaped">
                <img src="https://github.com/$escaped.png" alt="$escaped">
            </a>
        """.trimIndent()
    }

    private fun writeShell(outputDir: File) {
        val shell = this::class.java.getResourceAsStream("/index.html")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("index.html not found in resources")

        val processedShell = shell
            .replace("href=\"./style.css\"", "href=\"/assets/style.css\"")
            .replace("src=\"./script.js\"", "src=\"/assets/script.js\"")
            .replace("href=\"./index.html\"", "href=\"/\"")
        outputDir.resolve("index.html").writeText(processedShell)
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
        manifestPageEntries: List<ManifestPageEntry>,
        standalonePageEntries: Map<String, ManifestPageEntry>,
        siteConfig: SiteConfig?
    ): String {
        val defaultVersion = when {
            "latest" in versions -> "latest"
            versions.isNotEmpty() -> versions.first()
            else -> ""
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
            site = siteConfig,
            defaultVersion = defaultVersion,
            defaultLanguage = defaultLanguage,
            defaultPage = defaultPage,
            versions = versions,
            languages = allLanguages,
            languagesByVersion = languagesByVersion,
            pages = manifestPageEntries.associateBy { it.pageKey() },
            standalonePages = standalonePageEntries,
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
    ) {
        enum class TreeNodeType {
            Group, Page
        }
    }

    data class NavNode(
        val name: String,
        val children: MutableMap<String, NavNode> = linkedMapOf(),
        val manifestPageEntries: MutableList<ManifestPageEntry> = mutableListOf()
    )

    private fun String.toTitle(): String =
        replace("-", " ")
            .replace("_", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

}
