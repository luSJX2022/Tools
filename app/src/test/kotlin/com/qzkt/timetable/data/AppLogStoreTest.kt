package com.qzkt.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogStoreTest {
    @Test
    fun redactSensitiveValuesWithoutChangingOtherText() {
        assertEquals(
            "token=[REDACTED] cookie: [REDACTED] path=/home",
            AppLogStore.redact("token=abc123 cookie: session-value path=/home"),
        )
    }

    @Test
    fun leavesMessagesWithoutSensitiveFieldsUnchanged() {
        val message = "应用进程启动"
        assertEquals(message, AppLogStore.redact(message))
    }

    @Test
    fun redactsCredentialFieldsInUrlAndAuthorizationHeader() {
        assertEquals(
            "https://example.test/?access_token=[REDACTED]&page=2\nAuthorization: [REDACTED]",
            AppLogStore.redact(
                "https://example.test/?access_token=secret&page=2\nAuthorization: Bearer secret",
            ),
        )
    }
}
