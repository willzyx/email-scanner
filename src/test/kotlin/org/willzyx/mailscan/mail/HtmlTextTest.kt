package org.willzyx.mailscan.mail

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTextTest {
    @Test
    fun `paragraphs and line breaks become lines`() {
        assertEquals(listOf("One", "Two", "Three"), lines("<p>One</p><p>Two<br>Three</p>"))
    }

    @Test
    fun `text in inline tags stays on its line`() {
        assertEquals(
            listOf("Hello big world!"),
            lines("""<p>Hello <b>big</b> <a href="https://x.example">world</a>!</p>"""),
        )
    }

    @Test
    fun `a line break in the markup is a space`() {
        assertEquals(listOf("Hello world"), lines("<p>Hello\n    world</p>"))
    }

    @Test
    fun `text right after a block starts a new line`() {
        assertEquals(listOf("In a block", "after it"), lines("<div>In a block</div>after it"))
    }

    @Test
    fun `list items and table cells become lines`() {
        assertEquals(listOf("Milk", "Bread"), lines("<ul><li>Milk<li>Bread</ul>"))
        assertEquals(
            listOf("Item", "Price", "Kettle", "20 €"),
            lines("<table><tr><th>Item</th><th>Price</th></tr><tr><td>Kettle</td><td>20 €</td></tr></table>"),
        )
    }

    @Test
    fun `character references are decoded`() {
        assertEquals(listOf("Tom & Jerry <3 €5 —"), lines("<p>Tom &amp; Jerry &lt;3 &euro;5 &#8212;</p>"))
    }

    @Test
    fun `the head, scripts and styles are left out`() {
        val html =
            """
            <!DOCTYPE html>
            <html><head><title>Window title</title><style>.btn { color: red }</style></head>
            <body>
            <script>dataLayer.push({event: "open"})</script>
            <style>p { margin: 0 }</style>
            <p>What the reader sees</p>
            </body></html>
            """.trimIndent()
        assertEquals(listOf("What the reader sees"), lines(html))
    }

    @Test
    fun `text without any tags is kept`() {
        assertEquals(listOf("Just text"), lines("Just text"))
    }

    @Test
    fun `a hidden element is left out with everything inside it`() {
        // The unclosed paragraphs end with the hidden block, as in a browser.
        assertEquals(
            listOf("visible"),
            lines("""<div style="display: none"><p>secret<p>more <b>secrets</b></div><p>visible</p>"""),
        )
    }

    @Test
    fun `each way of hiding is recognised`() {
        val hidden =
            listOf(
                """<span hidden>""",
                """<span style="display:none">""",
                """<span style="visibility:hidden">""",
                """<span style="font-size:0">""",
                """<span style="font-size:0px">""",
                """<span style="font-size: 0.0em; color: red">""",
                """<span style="color:#fff;font-size:0 !important">""",
                """<span STYLE="DISPLAY : NONE">""",
            )
        for (tag in hidden) {
            assertEquals(listOf("before after"), lines("<p>before $tag for the model only</span>after</p>"), tag)
        }
    }

    @Test
    fun `small or styled text is not hidden`() {
        val shown =
            listOf(
                """<span style="font-size:0.9em">""",
                """<span style="font-size:10px">""",
                """<span style="display:inline-block">""",
                """<span style="visibility:visible">""",
                """<span class="hidden">""",
            )
        for (tag in shown) {
            assertEquals(listOf("before shown after"), lines("<p>before ${tag}shown</span> after</p>"), tag)
        }
    }

    @Test
    fun `a hidden body leaves no text`() {
        assertEquals(emptyList(), lines("""<html><body style="display:none"><p>for the model only</p></body></html>"""))
    }
}

/** The lines of text [html] turns into, without the empty ones and the spaces around each. */
private fun lines(html: String): List<String> = htmlToText(html).lines().map { it.trim() }.filter { it.isNotEmpty() }
