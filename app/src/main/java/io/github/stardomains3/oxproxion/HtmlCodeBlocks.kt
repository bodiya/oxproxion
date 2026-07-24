package io.github.stardomains3.oxproxion

/**
 * Extracts fenced code blocks containing HTML from raw markdown so they can be
 * rendered in a WebView.
 */
object HtmlCodeBlocks {

    data class CodeBlock(val language: String, val code: String)

    /**
     * Code blocks relevant to an HTML preview: the renderable HTML blocks plus any
     * standalone CSS/JS blocks from the same response that belong with them.
     */
    data class PreviewBlocks(
        val htmlBlocks: List<String>,
        val cssBlocks: List<String>,
        val jsBlocks: List<String>
    )

    private val HTML_LANGUAGES = setOf("html", "htm", "xhtml", "svg")
    private val CSS_LANGUAGES = setOf("css")
    private val JS_LANGUAGES = setOf("js", "javascript")

    /**
     * Extracts the fenced code blocks in [markdown] relevant to an HTML preview.
     * HTML blocks are those tagged with an HTML language, or untagged blocks whose
     * content starts like an HTML document or SVG element.
     */
    fun extract(markdown: String): PreviewBlocks {
        val blocks = extractFencedBlocks(markdown)
        return PreviewBlocks(
            htmlBlocks = blocks.filter { isHtml(it) }.map { it.code },
            cssBlocks = blocks.filter { it.language in CSS_LANGUAGES }.map { it.code },
            jsBlocks = blocks.filter { it.language in JS_LANGUAGES }.map { it.code }
        )
    }

    /**
     * Builds the document to preview from an HTML block, inlining any separate
     * CSS/JS blocks the model produced alongside it: styles go before </head>
     * (falling back to </body>, </html>, or the end of the document) and scripts
     * before </body> (falling back to </html> or the end) so they run after the
     * markup they reference.
     */
    fun buildPreviewDocument(html: String, cssBlocks: List<String>, jsBlocks: List<String>): String {
        var doc = html
        if (cssBlocks.isNotEmpty()) {
            val styleTag = "<style>\n${cssBlocks.joinToString("\n\n")}\n</style>\n"
            doc = insertBefore(doc, Regex("(?i)</head>"), styleTag)
                ?: insertBefore(doc, Regex("(?i)</body>"), styleTag)
                ?: insertBefore(doc, Regex("(?i)</html>"), styleTag)
                ?: (doc + "\n" + styleTag)
        }
        if (jsBlocks.isNotEmpty()) {
            val scriptTags = jsBlocks.joinToString("") { "<script>\n$it\n</script>\n" }
            doc = insertBefore(doc, Regex("(?i)</body>"), scriptTags)
                ?: insertBefore(doc, Regex("(?i)</html>"), scriptTags)
                ?: (doc + "\n" + scriptTags)
        }
        return doc
    }

    private fun insertBefore(doc: String, anchor: Regex, insertion: String): String? {
        val match = anchor.find(doc) ?: return null
        return doc.replaceRange(match.range.first, match.range.first, insertion)
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
