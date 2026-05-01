import {readFile, writeFile} from "node:fs/promises";
import {createHighlighter, bundledLanguages} from "shiki";
import {
    transformerNotationDiff,
    transformerNotationHighlight,
    transformerNotationWordHighlight,
    transformerNotationFocus,
    transformerNotationErrorLevel,
    transformerRenderWhitespace,
    transformerRenderIndentGuides,
    transformerMetaHighlight,
    transformerMetaWordHighlight,
    transformerRemoveComments,
} from "@shikijs/transformers";

const [, , inputPath, outputPath] = process.argv;

if (!inputPath || !outputPath) {
    console.error("Usage: node tools/shiki-highlighter.mjs <input.json> <output.json>");
    process.exit(1);
}

const input = JSON.parse(await readFile(inputPath, "utf8"));
const pages = Array.isArray(input.pages) ? input.pages : [];
const languages = collectLanguages(pages);
const highlighter = await createHighlighter({
    themes: ["github-light", "github-dark"],
    langs: languages
});

const highlightedPages = pages.map((page) => ({
    id: page.id,
    html: highlightHtml(page.html || "", highlighter)
}));

await writeFile(outputPath, JSON.stringify({pages: highlightedPages}), "utf8");

function collectLanguages(pages) {
    const found = new Set();

    for (const page of pages) {
        for (const block of codeBlocks(page.html || "")) {
            const lang = normalizeLanguage(block.language);
            if (lang && bundledLanguages[lang]) found.add(lang);
        }
    }

    return [...found].sort();
}

function highlightHtml(html, highlighter) {
    return html.replace(codeBlockRegex(), (blockHtml) => {
        const [block] = [...codeBlocks(blockHtml)];
        if (!block) return blockHtml;

        const lang = normalizeLanguage(block.language);
        if (!lang || !bundledLanguages[lang]) return blockHtml;

        return highlighter.codeToHtml(decodeHtml(block.code), {
            lang,
            themes: {
                light: "github-light",
                dark: "github-dark"
            },
            defaultColor: false,
            transformers: [
                transformerNotationDiff(),
                transformerNotationHighlight(),
                transformerNotationWordHighlight(),
                transformerNotationFocus(),
                transformerNotationErrorLevel(),
                transformerRenderWhitespace(),
                transformerRenderIndentGuides(), // doesnt work yet
                transformerMetaHighlight(), // doesnt work yet
                transformerMetaWordHighlight(),
            ]
        });
    });
}

function* codeBlocks(html) {
    const regex = codeBlockRegex();
    let match;

    while ((match = regex.exec(html)) !== null) {
        const codeAttributes = match[2] || "";
        const languageMatch = codeAttributes.match(/\blanguage-([a-zA-Z0-9_+#.-]+)/);

        yield {
            language: languageMatch?.[1] || "text",
            code: match[3] || ""
        };
    }
}

function codeBlockRegex() {
    return /<pre([^>]*)>\s*<code([^>]*)>([\s\S]*?)<\/code>\s*<\/pre>/g;
}

function normalizeLanguage(language) {
    const normalized = String(language || "text").toLowerCase();
    const aliases = {
        batch: "bat",
        csharp: "c#",
        js: "javascript",
        kt: "kotlin",
        md: "markdown",
        ps1: "powershell",
        py: "python",
        rb: "ruby",
        sh: "shellscript",
        shell: "shellscript",
        text: "",
        ts: "typescript",
        yml: "yaml"
    };

    return aliases[normalized] || normalized;
}

function decodeHtml(value) {
    return value
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&quot;/g, "\"")
        .replace(/&#39;/g, "'")
        .replace(/&#x27;/g, "'")
        .replace(/&amp;/g, "&");
}
