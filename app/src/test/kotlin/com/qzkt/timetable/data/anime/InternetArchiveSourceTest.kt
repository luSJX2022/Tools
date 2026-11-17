package com.qzkt.timetable.data.anime

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetArchiveSourceTest {
    @Test
    fun acceptsOnlyExplicitPublicDomainMarkOrCc0Licenses() {
        assertTrue(
            hasPublicDomainLicense(
                "http://creativecommons.org/publicdomain/mark/1.0/",
                "https://creativecommons.org/publicdomain/zero/1.0/",
            ),
        )
        assertFalse(hasPublicDomainLicense("https://creativecommons.org/licenses/by/4.0/"))
        assertFalse(hasPublicDomainLicense(null, ""))
    }

    @Test
    fun choosesOnlyPubliclyAccessibleMp4OrWebmFiles() {
        val files = JSONArray(
            """[
                {"name":"secret.mp4","private":"true"},
                {"name":"captions.vtt"},
                {"name":"film.webm"},
                {"name":"film.mp4"}
            ]""",
        )

        assertEquals("film.mp4", selectPublicDomainVideoFile(files)?.optString("name"))
        assertNull(selectPublicDomainVideoFile(JSONArray("""[{"name":"captions.vtt"}]""")))
    }

    @Test
    fun buildsEncodedArchiveDownloadUrlAndRejectsTraversal() {
        assertEquals(
            "https://archive.org/download/public-film/film%20file.mp4",
            archiveDownloadUrl("public-film", "film file.mp4"),
        )
        try {
            archiveDownloadUrl("public-film", "../private.mp4")
            throw AssertionError("Expected traversal path rejection")
        } catch (_: IllegalArgumentException) {
            // Expected: filenames from the API cannot escape the archive item path.
        }
    }
}
