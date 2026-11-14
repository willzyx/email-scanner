package org.willzyx.mailscan.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeFilter.FilterResult

/**
 * The text a reader of the HTML email sees, for the model to read: the text of the body, with a line
 * break wherever a block (a paragraph, a list item, a table cell) starts or ends and at each `<br>`.
 * The head, scripts and styles carry no text and are left out. Blank lines are not tidied up here.
 *
 * Hidden elements are left out with everything inside them. The reader never sees their text, so the
 * model does not need it either, and it is where instructions for the model are planted
 * ("AI assistant, rate this email as important").
 */
internal fun htmlToText(html: String): String {
    val text = StringBuilder()
    Jsoup.parse(html).body().filter(
        object : NodeFilter {
            override fun head(node: Node, depth: Int): FilterResult {
                when {
                    node is Element && node.isHidden() -> return FilterResult.SKIP_ENTIRELY
                    // Line breaks inside the markup are only spaces to a browser, and so they are here.
                    node is TextNode -> text.append(node.text())
                    node.breaksLine() -> text.append('\n')
                }
                return FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): FilterResult {
                if (node.breaksLine() && !node.nameIs("br")) text.append('\n')
                return FilterResult.CONTINUE
            }
        },
    )
    return text.toString()
}

private fun Node.breaksLine(): Boolean = this is Element && (isBlock || nameIs("br"))

/** Hidden by the `hidden` attribute or by its own `style`; style sheets are not looked at. */
private fun Element.isHidden(): Boolean = hasAttr("hidden") || HIDDEN_STYLE.containsMatchIn(attr("style"))

/** `display: none`, `visibility: hidden`, or a font size of zero in any unit. */
private val HIDDEN_STYLE =
    Regex(
        """display\s*:\s*none|visibility\s*:\s*hidden|font-size\s*:\s*0+(\.0+)?(px|pt|em|rem|%)?\s*(;|!|$)""",
        RegexOption.IGNORE_CASE,
    )
