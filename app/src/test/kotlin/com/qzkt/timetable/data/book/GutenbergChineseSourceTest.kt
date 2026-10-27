package com.qzkt.timetable.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GutenbergChineseSourceTest {

    @Test
    fun `Wikisource is available as an online book source`() {
        assertTrue(BookSources.all.any { it.key == "wikisource-zh" })
    }

    @Test
    fun `Wikisource section responses become navigable reader chapters`() {
        val chapters = parseWikisourceSections(
            org.json.JSONObject(
                """
                {
                  "parse": {
                    "sections": [
                      {"index":"0","line":"序"},
                      {"index":"1","line":"第一回"},
                      {"index":"2","line":"第二回"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
            "https://zh.wikisource.org/wiki/西游记",
        )

        assertEquals(listOf("序", "第一回", "第二回"), chapters.map { it.title })
        assertTrue(chapters[1].url.endsWith("?section=1"))
    }

    @Test
    fun `Wikisource reader removes navigation and references from text`() {
        val text = extractWikisourceText(
            """
            <div class="mw-parser-output">
              <p>第一回　灵根育孕源流出，</p>
              <p>心性修持大道生。</p>
              <span class="mw-editsection">编辑</span>
              <ol class="references"><li>脚注内容</li></ol>
            </div>
            """.trimIndent(),
        )

        assertTrue(text.contains("灵根育孕源流出"))
        assertTrue(text.contains("心性修持大道生"))
        assertFalse(text.contains("编辑"))
        assertFalse(text.contains("脚注内容"))
    }

    @Test
    fun `retired default book source migrates to the working Gutenberg source`() {
        assertEquals("gutenberg-zh", normalizeBookSourceKey("kunnu8"))
        assertEquals("quanben5", normalizeBookSourceKey("quanben5"))
    }

    @Test
    fun `book records map to stable source URLs and preserve pagination`() {
        val page = parseGutenbergBookPage(
            """
            {
              "next": "https://gutendex.com/books/?languages=zh&page=2",
              "results": [
                {
                  "id": 23962,
                  "title": "西游记",
                  "authors": [{"name": "吴承恩"}],
                  "formats": {
                    "image/jpeg": "https://example.org/cover.jpg",
                    "text/html; charset=utf-8": "https://www.gutenberg.org/ebooks/23962.html.images"
                  }
                },
                {
                  "id": 23963,
                  "title": "no html edition",
                  "authors": [],
                  "formats": {"text/plain; charset=utf-8": "https://example.org/book.txt"}
                }
              ]
            }
            """.trimIndent(),
        )

        assertTrue(page.hasNext)
        assertEquals(1, page.books.size)
        assertEquals(
            OnlineBook(
                sourceKey = "gutenberg-zh",
                bookUrl = "https://www.gutenberg.org/ebooks/23962.html.images",
                name = "西游记",
                author = "吴承恩",
                cover = "https://example.org/cover.jpg",
            ),
            page.books.single(),
        )
    }

    @Test
    fun `book record without a next page is terminal`() {
        val page = parseGutenbergBookPage("""{"next":null,"results":[]}""")

        assertFalse(page.hasNext)
        assertTrue(page.books.isEmpty())
    }

    @Test
    fun `HTML extraction removes Gutenberg boilerplate and keeps book text`() {
        val html = """
            <html><body>
              <div id="pg-header">Gutenberg header</div>
              <div id="pg-main-content">
                <h1>西游记</h1>
                <p>第一回　灵根育孕源流出，<br>心性修持大道生。</p>
              </div>
              <div id="pg-footer">Gutenberg license footer</div>
            </body></html>
        """.trimIndent()

        val text = extractGutenbergText(html)

        assertTrue(text.contains("西游记"))
        assertTrue(text.contains("灵根育孕源流出"))
        assertTrue(text.contains("心性修持大道生"))
        assertFalse(text.contains("Gutenberg header"))
        assertFalse(text.contains("Gutenberg license footer"))
    }
}
