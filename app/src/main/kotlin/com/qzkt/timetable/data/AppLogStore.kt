package com.qzkt.timetable.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Small bounded, private app log. It never reads Android's global logcat. */
class AppLogStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "logs/app.log")

    @Synchronized
    fun record(level: String, tag: String, message: String) {
        val line = buildString {
            append(TIME_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault())))
            append(" ")
            append(level.uppercase().take(5))
            append("/")
            append(tag.replace(NEWLINE, "_").take(40))
            append(": ")
            append(redact(message).take(MAX_ENTRY_CHARS))
            append('\n')
        }
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("无法创建应用日志目录")
            }
        }
        val lineBytes = line.toByteArray(Charsets.UTF_8)
        if (file.exists() && file.length() + lineBytes.size > MAX_FILE_BYTES) {
            val existing = file.readBytes()
            var start = (existing.size - MAX_RETAINED_BYTES).coerceAtLeast(0)
            while (start < existing.size && existing[start] != '\n'.code.toByte()) start++
            file.writeBytes(existing.copyOfRange((start + 1).coerceAtMost(existing.size), existing.size))
        }
        file.appendBytes(lineBytes)
    }

    @Synchronized
    fun read(): String = if (file.exists()) file.readText(Charsets.UTF_8) else ""

    @Synchronized
    fun clear() {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("无法清除应用日志")
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 256 * 1024L
        private const val MAX_RETAINED_BYTES = 128 * 1024
        private const val MAX_ENTRY_CHARS = 16 * 1024
        private val NEWLINE = Regex("[\\r\\n]")
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val SECRET_PATTERN = Regex(
            """(?i)\b(password|passwd|pwd|token|access_token|refresh_token|cookie|authorization|session[_-]?cookie|session[_-]?id|api[_-]?key|client[_-]?secret|secret|jwt)\b(\s*[:=]\s*)(Bearer\s+[^&\r\n,;]+|[^\s&;,]+)""",
        )

        fun redact(message: String): String =
            SECRET_PATTERN.replace(message) { match ->
                match.groupValues[1] + match.groupValues[2] + "[REDACTED]"
            }
    }
}
