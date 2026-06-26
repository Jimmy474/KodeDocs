import java.io.File

fun isInside(parent: File, child: File): Boolean {
    val selfPath = runCatching { child.canonicalFile.toPath() }.getOrElse { child.absoluteFile.toPath() }
    val parentPath = runCatching { parent.canonicalFile.toPath() }.getOrElse { parent.absoluteFile.toPath() }
    return selfPath.startsWith(parentPath)
}

fun String.escapeHtml(): String {
    val builder = java.lang.StringBuilder(length + 16)
    for (char in this) {
        when (char) {
            '&' -> builder.append("&amp;")
            '<' -> builder.append("&lt;")
            '>' -> builder.append("&gt;")
            '"' -> builder.append("&quot;")
            '\'' -> builder.append("&#39;")
            else -> builder.append(char)
        }
    }
    return builder.toString()
}