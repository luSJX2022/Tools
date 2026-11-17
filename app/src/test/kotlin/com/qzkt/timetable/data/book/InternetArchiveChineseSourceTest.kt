package com.qzkt.timetable.data.book

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetArchiveChineseSourceTest {

    @Test
    fun `Internet Archive source is selectable and categorized`() {
        val source = BookSources.byKey("internet-archive-zh")

        assertTrue(source is CategorizedBookSource)
        assertEquals("互联网档案馆（中文公版）", source.name)
    }

    @Test
    fun `search results include only explicitly open licensed works`() {
        val page = parseInternetArchiveSearchPage(
            """
            {
              "response": {
                "start": 0,
                "numFound": 3,
                "docs": [
                  {
                    "identifier": "open-book-1",
                    "title": "古籍",
                    "creator": ["作者甲"],
                    "rights": "Public Domain"
                  },
                  {
                    "identifier": "unknown-rights",
                    "title": "未标注版权",
                    "rights": "Copyright Not Evaluated"
                  }
                ]
              }
            }
            """.trimIndent(),
            page = 1,
            pageSize = 10,
        )

        assertEquals(1, page.books.size)
        assertEquals("古籍", page.books.single().name)
        assertEquals("作者甲", page.books.single().author)
        assertTrue(page.hasNext)
    }

    @Test
    fun `metadata selects a readable text edition only for open works`() {
        val metadata = JSONObject(
            """
            {
              "metadata": {
                "title": "古籍",
                "creator": "作者乙",
                "licenseurl": "https://creativecommons.org/licenses/by-sa/4.0/"
              },
              "files": [
                {"name":"scan.pdf","format":"PDF"},
                {"name":"book_djvu.txt","format":"DjVuTXT"}
              ]
            }
            """.trimIndent(),
        )

        val result = parseInternetArchiveMetadata(metadata)

        assertEquals("古籍", result.title)
        assertEquals("作者乙", result.author)
        assertEquals("book_djvu.txt", result.textFileName)
    }

    @Test
    fun `metadata without recognized open license is rejected`() {
        val metadata = JSONObject(
            """
            {
              "metadata": {"title":"版权未知","rights":"Copyright Not Evaluated"},
              "files": [{"name":"book.txt","format":"Text"}]
            }
            """.trimIndent(),
        )

        val error = runCatching { parseInternetArchiveMetadata(metadata) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("未标注为公版或开放授权"))
    }

    @Test
    fun `plain text extraction normalizes line endings and page breaks`() {
        val text = extractInternetArchiveText("第一行\r\n第二行\u000c第三行")

        assertEquals("第一行\n第二行\n第三行", text)
        assertFalse(text.contains('\u000c'))
    }
}
