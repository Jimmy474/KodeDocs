
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

class Engine(
    private val rootDir: File,
    private val outputDir: File,
) {
    val siteConfig: SiteConfig
    val parser: MarkDownParser

    init {
        if (!rootDir.exists()) throw IllegalArgumentException("Root directory does not exist at path: ${rootDir.path}")
        siteConfig = loadSiteConfig(rootDir) ?: SiteConfig()
        parser = MarkDownParser(siteConfig)
    }

    companion object{
        val AUTHOR_REGEX = Regex("""[^a-zA-Z0-9_-]""")
    }

    private val log: Logger = LoggerFactory.getLogger("Engine")

    private val json = Json {
        prettyPrint = false
    }

    sealed interface PageInput {
        val mdFile: File

        data class Versioned(
            val version: String,
            val language: String,
            val langDir: File,
            override val mdFile: File
        ) : PageInput

        data class Standalone(
            val pagesDir: File,
            override val mdFile: File
        ) : PageInput
    }

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
    fun build() {
        val contentDir = rootDir.resolve("content")
        val pagesDir = rootDir.resolve("pages")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        } else{
            outputDir.deleteRecursively()
            outputDir.mkdirs()
        }

        val outputAssetsDir = outputDir.resolve("assets")
        outputAssetsDir.mkdirs()

        buildAssets(rootDir, outputDir)
        writeShell(outputDir)

        if (!contentDir.exists()) {
            log.info("No content directory found in ${rootDir.path}")
            outputDir.resolve("manifest.json").writeText(emptyManifest())
            return
        }

        val pageInputs = mutableListOf<PageInput>()
        val versions = mutableListOf<String>()
        val languagesByVersion = linkedMapOf<String, List<String>>()

        if (contentDir.exists()) {
            val foundVersions = contentDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
            versions.addAll(foundVersions)

            for (version in versions) {
                val versionDir = contentDir.resolve(version)
                val languages = versionDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
                languagesByVersion[version] = languages

                for (language in languages) {
                    val langDir = versionDir.resolve(language)
                    langDir.walkTopDown()
                        .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                        .forEach { mdFile ->
                            pageInputs += PageInput.Versioned(version, language, langDir, mdFile)
                        }
                }
            }
        }

        if (pagesDir.exists()) {
            pagesDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                .forEach { mdFile ->
                    pageInputs += PageInput.Standalone(pagesDir, mdFile)
                }
        }

        if (pageInputs.isEmpty()) {
            log.info("No markdown files found to process.")
            outputDir.resolve("manifest.json").writeText(emptyManifest())
            return
        }

        log.info("Building ${pageInputs.size} pages...")
        val pageContents = runBlocking {
            pageInputs.map { input ->
                async(Dispatchers.Default) {
                    val content = when (input) {
                        is PageInput.Versioned -> buildPageContent(rootDir, input.version, input.language, input.langDir, input.mdFile)
                        is PageInput.Standalone -> buildStandalonePageContent(rootDir, input.pagesDir, input.mdFile)
                    }

                    withContext(Dispatchers.IO) {
                        writePage(outputDir, content)
                    }

                    content
                }
            }.awaitAll()
        }

        TreeSitterHighlighter.clearMemory()

        val versionedEntries = mutableMapOf<String, ManifestPageEntry>()
        val standaloneEntries = mutableMapOf<String, ManifestPageEntry>()

        pageContents.forEach { content ->
            val entry = content.manifestPageEntry
            if (entry.version == "standalone") {
                standaloneEntries[entry.sourcePath] = entry
            } else {
                versionedEntries[entry.pageKey()] = entry
            }
        }

        val manifestContent = manifestJson(
            versions = versions,
            languagesByVersion = languagesByVersion,
            manifestPageEntries = versionedEntries.values.toList(),
            standalonePageEntries = standaloneEntries,
            siteConfig = siteConfig
        )

        outputDir.resolve("manifest.json").writeText(manifestContent)
    }

    fun loadSiteConfig(rootDir: File): SiteConfig? {
        val configFile = File(rootDir, "config.json")
        return if (configFile.exists()) {
            runCatching { Json.decodeFromString<SiteConfig>(configFile.readText()) }.getOrNull()
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

    private fun loadExistingManifest(outputDir: File): Manifest? {
        val manifestFile = File(outputDir, "manifest.json")
        return if (manifestFile.exists()) {
            runCatching { json.decodeFromString<Manifest>(manifestFile.readText()) }
                .onFailure { log.error("Failed to parse existing manifest", it) }
                .getOrNull()
        } else null
    }

    private fun writeUpdatedManifest(
        outputDir: File,
        baseManifest: Manifest,
        updatedPages: Map<String, ManifestPageEntry>,
        updatedStandalone: Map<String, ManifestPageEntry>,
    ) {
        val updatedTrees = updatedPages.values
            .groupBy { "${it.version}/${it.language}" }
            .mapValues { treeJson(it.value) }

        val newManifest = baseManifest.copy(
            site = siteConfig,
            pages = updatedPages,
            standalonePages = updatedStandalone,
            trees = updatedTrees
        )

        outputDir.resolve("manifest.json").writeText(json.encodeToString(newManifest))
    }

    fun buildSinglePage(mdFile: File) {
        val contentDir = rootDir.resolve("content")
        val pagesDir = rootDir.resolve("pages")

        val currentManifest = loadExistingManifest(outputDir) ?: run {
            log.warn("No existing manifest found. Falling back to full build.")
            build()
            return
        }

        val mutablePages = currentManifest.pages.toMutableMap()
        val mutableStandalone = currentManifest.standalonePages.toMutableMap()

        if (!mdFile.exists()) {
            val removedKey = resolvePageKey(mdFile, contentDir, pagesDir)
            removedKey?.let { key ->
                if (isInside(contentDir, mdFile)) {
                    val removedEntry = mutablePages.remove(key)
                    removedEntry?.let { outputDir.resolve(it.contentPath).delete() }
                } else {
                    val removedEntry = mutableStandalone.remove(key)
                    removedEntry?.let { outputDir.resolve(it.contentPath).delete() }
                }
                writeUpdatedManifest(outputDir, currentManifest, mutablePages, mutableStandalone)
            }
            return
        }

        val content = buildPageInDir(rootDir, contentDir, pagesDir, mdFile)

        content?.let {
            if (it.manifestPageEntry.version != "standalone") {
                mutablePages[it.manifestPageEntry.pageKey()] = it.manifestPageEntry
            } else {
                mutableStandalone[it.manifestPageEntry.sourcePath] = it.manifestPageEntry
            }

            writePage(outputDir, it)
            writeUpdatedManifest(outputDir, currentManifest, mutablePages, mutableStandalone)
        }
    }

    private fun buildPageInDir(rootDir: File, contentDir: File, pagesDir: File, mdFile: File): PageContent? {
        return when {
            isInside(contentDir, mdFile) -> {
                val relativePath = mdFile.relativeTo(contentDir).invariantSeparatorsPath
                val parts = relativePath.split("/")
                if (parts.size >= 3) {
                    val version = parts[0]
                    val language = parts[1]
                    val langDir = contentDir.resolve(version).resolve(language)
                    buildPageContent(rootDir, version, language, langDir, mdFile)
                } else null
            }
            isInside(pagesDir, mdFile) -> {
                buildStandalonePageContent(rootDir, pagesDir, mdFile)
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
    ): PageContent {
        val sourcePath = mdFile.relativeTo(langDir).invariantSeparatorsPath
        val pagePath = sourcePath.removeSuffix(".md")
        val preProcessedContent = PreProcessor.process(rootDir,mdFile)
        val parsed = parser.parse(preProcessedContent)
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
    ): PageContent {
        val relativePath = mdFile.relativeTo(pagesDir).invariantSeparatorsPath
        val pagePath = relativePath.removeSuffix(".md")
        val preProcessedContent = PreProcessor.process(rootDir, mdFile)
        val parsed = parser.parse(preProcessedContent)
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
        val username = author.replace(AUTHOR_REGEX, "")
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
}
