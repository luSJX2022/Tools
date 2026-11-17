package com.qzkt.timetable.data.anime

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoubanSynopsisClientTest {
    @Test
    fun matchesExactTitleAndYear() {
        val suggestions = JSONArray(
            """[
                {"id":"100","title":"沙丘","year":"1984","type":"movie"},
                {"id":"200","title":"沙丘","year":"2021","type":"movie"}
            ]""",
        )

        assertEquals("200", matchDoubanSubject(suggestions, "沙丘", "2021")?.id)
    }

    @Test
    fun rejectsDifferentTitleOrYear() {
        val suggestions = JSONArray(
            """[
                {"id":"100","title":"沙丘2","year":"2024","type":"movie"},
                {"id":"200","title":"沙丘","year":"2021","type":"movie"}
            ]""",
        )

        assertNull(matchDoubanSubject(suggestions, "沙丘", "2024"))
        assertNull(matchDoubanSubject(suggestions, "沙丘·救世主", "2021"))
    }

    @Test
    fun matchesTitleWithoutYearWhenItIsUnambiguous() {
        val suggestions = JSONArray(
            """[{"id":"100","title":"肖申克的救赎","year":"1994","type":"movie"}]""",
        )

        assertEquals("100", matchDoubanSubject(suggestions, "肖申克的救赎", "")?.id)
    }
}
