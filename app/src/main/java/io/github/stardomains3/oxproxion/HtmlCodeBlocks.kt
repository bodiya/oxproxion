package io.github.stardomains3.oxproxion

/**
 * Extracts fenced code blocks containing HTML from raw markdown so they can be
 * rendered in a WebView.
 */
object HtmlCodeBlocks {

    data class CodeBlock(val language: String, val code: String)

    private val HTML_LANGUAGES = setOf("html", "htm", "xhtml", "svg")

    /**
     * Returns the contents of every fenced code block in [markdown] that holds HTML:
     * blocks tagged with an HTML language, or untagged blocks whose content starts
     * like an HTML document or SVG element.
     */
    fun extractHtmlBlocks(markdown: String): List<String> {
        return extractFencedBlocks(markdown)
            .filter { isHtml(it) }
            .map { it.code }
    }

    private fun extractFencedBlocks(markdown: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        val lines = markdown.lines()
        val openFence = Regex("""^[ \t]{0,3}(`{3,}|~{3,})[ \t]*([^`\s]*).*$""")
        var i = 0
        while (i < lines.size) {
            val match = openFence.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val fence = match.groupValues[1]
            val language = match.groupValues[2].lowercase()
            val fenceChar = fence[0]
            val closeFence = Regex("""^[ \t]{0,3}$fenceChar{${fence.length},}[ \t]*$""")
            val content = StringBuilder()
            var j = i + 1
            while (j < lines.size && !closeFence.matches(lines[j])) {
                if (content.isNotEmpty()) content.append('\n')
                content.append(lines[j])
                j++
            }
            val code = content.toString()
            if (code.isNotBlank()) {
                blocks.add(CodeBlock(language, code))
            }
            // Skip past the closing fence when present; otherwise the block ran to the end
            i = if (j < lines.size) j + 1 else j
        }
        return blocks
    }

    private fun isHtml(block: CodeBlock): Boolean {
        if (block.language in HTML_LANGUAGES) return true
        if (block.language.isNotEmpty()) return false
        val start = block.code.trimStart().lowercase()
        return start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("<svg")
    }
}
