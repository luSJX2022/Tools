package com.qzkt.timetable.data.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ParcelStoreTest {

    @Test
    fun `parcel draft trims values and supplies a default name`() {
        val draft = validateParcelDraft(
            name = "  ",
            trackingNumber = "  SF123456789  ",
            carrier = " 顺丰 ",
        )

        assertEquals("我的包裹", draft.name)
        assertEquals("SF123456789", draft.trackingNumber)
        assertEquals("顺丰", draft.carrier)
    }

    @Test
    fun `parcel draft rejects missing or invalid length waybill numbers`() {
        listOf("", "123", "A".repeat(51)).forEach { trackingNumber ->
            val result = runCatching { validateParcelDraft("", trackingNumber, "") }
            assertEquals(IllegalArgumentException::class.java, result.exceptionOrNull()?.javaClass)
        }
    }

    @Test
    fun `parcel labels are limited to forty characters`() {
        val draft = validateParcelDraft("包裹".repeat(30), "SF123456", "")

        assertEquals(40, draft.name.length)
    }
}
