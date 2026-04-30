object HtmlTemplates {
    fun wrap(
        content: String,
        versions: List<String> = emptyList(),
        currentVersion: String = "latest",
        languages: List<String> = emptyList(),
        currentLanguage: String = "en",
        sidebar: String = "",
        basePath: String = "",
        metadata: Map<String, Any> = emptyMap(),
        headings: List<MarkDownParser.Heading> = emptyList()
    ): String {
        val title = metadata["title"] as? String ?: "KodeDocs"
        val versionOptions = versions.joinToString("\n") {
            "<option value=\"$it\" ${if (it == currentVersion) "selected" else ""}>$it</option>"
        }
        val langOptions = languages.joinToString("\n") {
            "<option value=\"$it\" ${if (it == currentLanguage) "selected" else ""}>$it</option>"
        }

        val assetPath = if (basePath.isEmpty()) "assets" else "$basePath/assets"

        @Suppress("UNCHECKED_CAST")
        val authors = metadata["authors"] as? List<String> ?: emptyList()
        val authorHtml = if (authors.isNotEmpty()) {
            """
                <div class="metadata-section">
                    <h3>Authors</h3>
                    <div class="authors">
                        ${authors.map{ Regex("[^a-zA-Z0-9_]").replace(it,"") }.joinToString("\n") { username ->
                            """
                            <a href="https://github.com/$username" target="_blank" class="author">
                                <img src="https://github.com/$username.png" alt="$username" />
                            </a>
                            """.trimIndent()
                        }}
                    </div>
                </div>
            """.trimIndent()
        } else ""

        val description = metadata["description"] as? String
        val descriptionHtml = if (description != null) "<p>$description</p>" else ""

        val tocHtml = if (headings.isNotEmpty()) {
            """
            <div class="toc-section">
                <h3>On this page</h3>
                <ul>
                    ${headings.filter { it.level in 2..3 }.joinToString("\n") { heading ->
                        "<li class=\"toc-level-${heading.level}\"><a href=\"#${heading.id}\">${heading.text}</a></li>"
                    }}
                </ul>
            </div>
            """.trimIndent()
        } else ""

        return """
            <!DOCTYPE html>
            <html class="dark">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$title</title>
                <link rel="stylesheet" href="$assetPath/style.css">
            </head>
            <body>
                <header class="site-header">
                    <button class="icon-button nav-toggle" type="button" aria-label="Open navigation" aria-expanded="false" data-panel-toggle="nav">
                        <span class="menu-icon"><span></span></span>
                    </button>
                    <a class="brand" href="$basePath/">
                        <span class="brand-mark">K</span>
                        <span class="brand-text">KodeDocs</span>
                    </a>
                    <div class="controls">
                        <label class="select-shell version-select" aria-label="Version">
                            <select id="versionSelect" data-base="$basePath/">
                                $versionOptions
                            </select>
                        </label>
                        <label class="select-shell lang-select" aria-label="Language">
                            <select id="langSelect">
                                $langOptions
                            </select>
                        </label>
                        <button class="icon-button" type="button" aria-label="Toggle theme" onclick="toggleTheme()">
                            <span class="theme-icon"></span>
                        </button>
                        <button class="icon-button toc-toggle" type="button" aria-label="Open table of contents" aria-expanded="false" data-panel-toggle="toc">
                            <span class="toc-icon"><span></span></span>
                        </button>
                    </div>
                </header>
                <div class="main-container">
                    <nav class="sidebar" data-panel="nav">
                        $sidebar
                    </nav>
                    <main class="docs-content vp-doc">
                        <h1>$title</h1>
                        $descriptionHtml
                        $content
                    </main>
                    <aside class="right-sidebar" data-panel="toc">
                        $tocHtml
                        <div class="divider"></div>
                        $authorHtml
                    </aside>
                </div>
                <div class="mobile-backdrop" aria-hidden="true"></div>
                <script src="$assetPath/script.js"></script>
                <script>
                    const socket = new WebSocket('ws://' + window.location.host + '/livereload');
                    socket.onmessage = function(event) {
                        if (event.data === 'reload') {
                            console.log('Reloading...');
                            window.location.reload();
                        }
                    };
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
