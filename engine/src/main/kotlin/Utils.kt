import java.io.File

fun isInside(parent: File, child: File): Boolean {
    val selfPath = runCatching { child.canonicalFile.toPath() }.getOrElse { child.absoluteFile.toPath() }
    val parentPath = runCatching { parent.canonicalFile.toPath() }.getOrElse { parent.absoluteFile.toPath() }
    return selfPath.startsWith(parentPath)
}

fun printProgress(completed: Int, total: Int, label: String) {
    val total = if (total <= 0) 1 else total
    val fraction = completed.toFloat() / total
    val percentage = (fraction * 100f).coerceIn(0f, 100f)

    val reset = "\u001b[0m"

    val barWidth = 50
    val filledLength = (fraction * barWidth).toInt().coerceIn(0, barWidth)
    val coloredBar = "\u001b[92m${"█".repeat(filledLength)}\u001b[90m${"░".repeat(barWidth - filledLength)}${reset}"

    val singleLineOutput = "\r\u001b[2K\u001b[94mProgress${reset}: \u001b[96m${"%.2f%%".format(percentage)}${reset} ┃$coloredBar┃ \u001b[93m($completed/$total)${reset} | \u001b[92m$label${reset}"

    print(singleLineOutput)
}
