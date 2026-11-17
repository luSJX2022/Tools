package com.qzkt.timetable.ui.tools

import com.qzkt.timetable.data.PluginLog
import org.bouncycastle.math.ec.rfc7748.X25519
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URLDecoder
import java.util.Base64
import java.nio.charset.StandardCharsets

internal enum class KeyCheckStatus { VALID, WARNING, INVALID }

internal data class KeyCheckFinding(
    val name: String,
    val status: KeyCheckStatus,
    val fingerprint: String?,
    val details: String,
)

internal data class KeyCheckReport(
    val findings: List<KeyCheckFinding>,
    val validCount: Int,
    val warningCount: Int,
    val invalidCount: Int,
) {
    fun toRedactedJson(): String {
        fun quote(value: String): String = buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }
        return buildString {
            append("{\"valid\":").append(validCount)
            append(",\"warning\":").append(warningCount)
            append(",\"invalid\":").append(invalidCount)
            append(",\"findings\":[")
            findings.forEachIndexed { index, finding ->
                if (index > 0) append(',')
                append("{\"name\":").append(quote(finding.name))
                append(",\"status\":").append(quote(finding.status.name.lowercase()))
                append(",\"fingerprint\":")
                    .append(finding.fingerprint?.let(::quote) ?: "null")
                append(",\"details\":").append(quote(finding.details)).append('}')
            }
            append("]}")
        }
    }

    fun toText(): String = buildString {
        appendLine("KeyCheck 离线验证报告")
        appendLine("有效：$validCount · 提醒：$warningCount · 无效：$invalidCount")
        findings.forEach { finding ->
            appendLine()
            append("${finding.status.label} · ${finding.name}: ${finding.details}")
            finding.fingerprint?.let { appendLine("\nSHA-256: $it") }
        }
        appendLine()
        appendLine("报告不包含粘贴的原始密钥。")
    }

    private val KeyCheckStatus.label: String
        get() = when (this) {
            KeyCheckStatus.VALID -> "有效"
            KeyCheckStatus.WARNING -> "提醒"
            KeyCheckStatus.INVALID -> "无效"
        }
}

internal data class GeneratedWireGuardKeys(
    val privateKey: String,
    val publicKey: String,
)

internal object KeyCheckEngine {
    private data class KeyEntry(
        val name: String,
        val value: String,
        val section: Int,
        val kind: Kind,
    )

    private enum class Kind { PRIVATE, PUBLIC, PRESHARED }

    fun analyze(input: String, baseline: String = ""): KeyCheckReport {
        PluginLog.i("P2P 密钥验证", "开始检查：输入 " + input.length + " 字符，基线 " + baseline.length + " 字符")
        val entries = parseEntries(input)
        if (entries.isEmpty()) {
            val finding = KeyCheckFinding(
                name = "输入",
                status = KeyCheckStatus.WARNING,
                fingerprint = null,
                details = "没有找到 WireGuard 密钥字段；可粘贴 .conf 配置或 Base64 密钥。",
            )
            val metadata = analyzeNetworkSettings(input)
            return countReport(listOf(finding) + metadata)
        }

        val findings = mutableListOf<KeyCheckFinding>()
        val publicKeysBySection = mutableMapOf<Int, ByteArray>()
        val privateDerivedBySection = mutableMapOf<Int, ByteArray>()
        val baselineCheck = parseBaseline(baseline)
        entries.forEach { entry ->
            val decoded = decodeKey(entry.value)
            if (decoded == null) {
                findings += KeyCheckFinding(
                    entry.name,
                    KeyCheckStatus.INVALID,
                    null,
                    "不是有效的 32 字节 WireGuard Base64 密钥。",
                )
                return@forEach
            }
            when (entry.kind) {
                Kind.PRIVATE -> {
                    val derived = derivePublicKey(decoded)
                    if (derived == null) {
                        findings += KeyCheckFinding(
                            entry.name,
                            KeyCheckStatus.INVALID,
                            null,
                            "无法从私钥计算有效公钥。",
                        )
                    } else {
                        privateDerivedBySection[entry.section] = derived
                        val baselineMatch = baselineCheck?.matches(derived)
                        findings += KeyCheckFinding(
                            entry.name,
                            if (baselineMatch == false) KeyCheckStatus.WARNING else KeyCheckStatus.VALID,
                            fingerprint(derived),
                            when (baselineMatch) {
                                false -> "私钥格式有效，但其派生公钥与填写的已知公钥/指纹不匹配。"
                                true -> "私钥格式有效，派生公钥与已知公钥/指纹匹配；处理仅在本机进行。"
                                null -> "私钥格式有效；已在本机派生对应公钥，未上传或保存密钥。"
                            },
                        )
                    }
                    decoded.fill(0)
                }
                Kind.PUBLIC -> {
                    if (!isUsablePublicKey(decoded)) {
                        findings += KeyCheckFinding(
                            entry.name,
                            KeyCheckStatus.INVALID,
                            fingerprint(decoded),
                            "公钥格式正确，但曲线点无效或属于低阶点。",
                        )
                    } else {
                        publicKeysBySection[entry.section] = decoded
                        val baselineMatch = baselineCheck?.matches(decoded)
                        val status = when (baselineMatch) {
                            true -> KeyCheckStatus.VALID
                            false -> KeyCheckStatus.WARNING
                            null -> KeyCheckStatus.VALID
                        }
                        val detail = when (baselineMatch) {
                            true -> "公钥有效，且与填写的已知公钥/指纹匹配。"
                            false -> "公钥有效，但与填写的已知公钥/指纹不匹配。"
                            null -> "公钥格式及曲线点有效。"
                        }
                        findings += KeyCheckFinding(entry.name, status, fingerprint(decoded), detail)
                    }
                }
                Kind.PRESHARED -> {
                    findings += KeyCheckFinding(
                        entry.name,
                        KeyCheckStatus.VALID,
                        null,
                        "预共享密钥为有效的 32 字节 Base64；为避免泄露，不生成其指纹。",
                    )
                    decoded.fill(0)
                }
            }
        }

        privateDerivedBySection.forEach { (section, derived) ->
            val configuredPublic = publicKeysBySection[section] ?: return@forEach
            val matches = MessageDigest.isEqual(derived, configuredPublic)
            findings += KeyCheckFinding(
                name = "同段公私钥匹配",
                status = if (matches) KeyCheckStatus.VALID else KeyCheckStatus.INVALID,
                fingerprint = fingerprint(derived),
                details = if (matches) "私钥派生公钥与配置中的公钥相同。"
                else "私钥派生公钥与同一配置段中的公钥不一致。",
            )
        }

        val duplicates = findings
            .filter {
                it.fingerprint != null &&
                    (it.name == "PublicKey" || it.name.startsWith("密钥 "))
            }
            .groupBy { it.fingerprint }
            .filterValues { it.size > 1 }
        duplicates.forEach { (fingerprint, repeated) ->
            findings += KeyCheckFinding(
                "重复公钥",
                KeyCheckStatus.WARNING,
                fingerprint,
                "检测到 ${repeated.size} 个相同公钥条目。",
            )
        }
        findings += analyzeNetworkSettings(input)
        return countReport(findings)
    }

    fun generateKeys(random: SecureRandom = SecureRandom()): GeneratedWireGuardKeys {
        val privateBytes = ByteArray(X25519.SCALAR_SIZE)
        val publicBytes = ByteArray(X25519.POINT_SIZE)
        X25519.generatePrivateKey(random, privateBytes)
        X25519.generatePublicKey(privateBytes, 0, publicBytes, 0)
        return try {
            GeneratedWireGuardKeys(
                privateKey = Base64.getEncoder().encodeToString(privateBytes),
                publicKey = Base64.getEncoder().encodeToString(publicBytes),
            )
        } finally {
            privateBytes.fill(0)
            publicBytes.fill(0)
        }
    }

    private fun parseEntries(input: String): List<KeyEntry> {
        val entries = mutableListOf<KeyEntry>()
        var section = 0
        var unlabelledIndex = 0
        input.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.trimStart().startsWith("{")) {
                JSON_KEY.findAll(line).forEach matches@ { match ->
                    val key = match.groupValues[1].lowercase()
                    val kind = key.toKeyKind() ?: return@matches
                    entries += KeyEntry(key.toDisplayName(), match.groupValues[2], section, kind)
                }
                return@forEach
            }
            val query = line.substringAfter('?', missingDelimiterValue = "")
                .substringBefore('#')
            if (query.isNotBlank()) {
                query.split('&').forEach parts@ { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@parts
                    val key = decodeQueryPart(part.substring(0, separator)).lowercase()
                    val value = decodeQueryPart(part.substring(separator + 1))
                    val kind = key.toKeyKind()
                    if (kind != null) {
                        entries += KeyEntry(key.toDisplayName(), value, section, kind)
                    }
                }
                return@forEach
            }
            val configLine = line.substringBefore('#').substringBefore(';').trim()
            if (configLine.startsWith("[") && configLine.endsWith("]")) {
                section++
                return@forEach
            }
            val separator = configLine.indexOf('=')
            if (separator >= 0) {
                val key = configLine.substring(0, separator).trim().trim('"', '\'').lowercase()
                val value = configLine.substring(separator + 1).trim().trim('"', '\'', ',', '}')
                val kind = key.toKeyKind()
                if (kind != null) {
                    entries += KeyEntry(key.toDisplayName(), value, section, kind)
                }
            } else if (configLine.all { it.isLetterOrDigit() || it in "+/=_-" } && configLine.length >= 40) {
                unlabelledIndex++
                entries += KeyEntry("密钥 $unlabelledIndex", configLine, section, Kind.PUBLIC)
            }
        }
        return entries
    }

    private fun String.toKeyKind(): Kind? = when (trim().trim('"', '\'').lowercase()) {
        "privatekey", "private_key" -> Kind.PRIVATE
        "publickey", "public_key", "key" -> Kind.PUBLIC
        "presharedkey", "preshared_key", "psk" -> Kind.PRESHARED
        else -> null
    }

    private fun decodeQueryPart(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

    private val JSON_KEY = Regex(
        """"?(privatekey|private_key|publickey|public_key|presharedkey|preshared_key)"?\s*:\s*"([^"]+)"""",
        RegexOption.IGNORE_CASE,
    )

    private fun analyzeNetworkSettings(input: String): List<KeyCheckFinding> {
        val findings = mutableListOf<KeyCheckFinding>()
        input.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (name) {
                "endpoint" -> {
                    val valid = isValidEndpoint(value)
                    findings += KeyCheckFinding(
                        "Endpoint",
                        if (valid) KeyCheckStatus.VALID else KeyCheckStatus.INVALID,
                        null,
                        if (valid) "主机名/IP 与端口格式有效；未尝试连接。"
                        else "端点格式无效；应为主机名或 IP 地址加 1–65535 端口。",
                    )
                }
                "listenport" -> {
                    val valid = value.toIntOrNull() in 1..65535
                    findings += KeyCheckFinding(
                        "ListenPort",
                        if (valid) KeyCheckStatus.VALID else KeyCheckStatus.INVALID,
                        null,
                        if (valid) "端口号有效。" else "端口必须为 1–65535 的整数。",
                    )
                }
            }
        }
        return findings
    }

    private fun isValidEndpoint(value: String): Boolean {
        val portSeparator = value.lastIndexOf(':')
        if (portSeparator <= 0 || portSeparator == value.lastIndex) return false
        val host = value.substring(0, portSeparator).removeSurrounding("[", "]")
        val port = value.substring(portSeparator + 1).toIntOrNull() ?: return false
        if (port !in 1..65535 || host.isBlank()) return false
        if (host.contains(':')) {
            if (!value.substring(0, portSeparator).startsWith("[") ||
                !value.substring(0, portSeparator).endsWith("]")
            ) return false
            if (!host.matches(Regex("[0-9a-fA-F:.%]+"))) return false
            return runCatching { InetAddress.getByName(host) }.isSuccess
        }
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }
        return host.length <= 253 &&
            host.split('.').all { label ->
                label.length in 1..63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }
    }

    private fun countReport(findings: List<KeyCheckFinding>): KeyCheckReport =
        KeyCheckReport(
            findings = findings,
            validCount = findings.count { it.status == KeyCheckStatus.VALID },
            warningCount = findings.count { it.status == KeyCheckStatus.WARNING },
            invalidCount = findings.count { it.status == KeyCheckStatus.INVALID },
        )

    private fun String.toDisplayName(): String = when (this.lowercase()) {
        "privatekey", "private_key" -> "PrivateKey"
        "publickey", "public_key", "key" -> "PublicKey"
        else -> "PresharedKey"
    }

    private fun decodeKey(value: String): ByteArray? {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || compact.length > 128) return null
        return runCatching { Base64.getDecoder().decode(compact) }
            .getOrNull()
            ?.takeIf { it.size == X25519.SCALAR_SIZE }
    }

    private fun derivePublicKey(privateBytes: ByteArray): ByteArray? =
        ByteArray(X25519.POINT_SIZE).also { public ->
            runCatching { X25519.generatePublicKey(privateBytes, 0, public, 0) }
                .onFailure {
                    public.fill(0)
                    return null
                }
        }

    private fun isUsablePublicKey(publicKey: ByteArray): Boolean {
        val probePrivate = ByteArray(X25519.SCALAR_SIZE)
        val shared = ByteArray(X25519.POINT_SIZE)
        return try {
            SecureRandom().nextBytes(probePrivate)
            X25519.calculateAgreement(probePrivate, 0, publicKey, 0, shared, 0)
        } catch (_: IllegalStateException) {
            false
        } finally {
            probePrivate.fill(0)
            shared.fill(0)
        }
    }

    private fun fingerprint(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(publicKey)
            .joinToString("") { "%02x".format(it) }

    private data class Baseline(
        val publicKey: ByteArray?,
        val fingerprintPrefix: String?,
        val fingerprintBytes: ByteArray?,
    ) {
        fun matches(candidate: ByteArray): Boolean {
            publicKey?.let { return MessageDigest.isEqual(it, candidate) }
            val digest = MessageDigest.getInstance("SHA-256").digest(candidate)
            fingerprintBytes?.let { return MessageDigest.isEqual(it, digest) }
            val prefix = fingerprintPrefix ?: return false
            val actualHex = digest
                .joinToString("") { "%02x".format(it) }
            return actualHex.startsWith(prefix, ignoreCase = true)
        }
    }

    private fun parseBaseline(value: String): Baseline? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val explicitFingerprint = trimmed.startsWith("SHA-256:", ignoreCase = true) ||
            trimmed.startsWith("SHA256:", ignoreCase = true)
        val normalized = if (explicitFingerprint) trimmed.substringAfter(':').trim() else trimmed
        if (!explicitFingerprint) {
            decodeKey(normalized)?.let { return Baseline(it, null, null) }
        }
        val hex = normalized.replace(":", "").replace("-", "").lowercase()
        if (hex.length in 8..64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            return Baseline(null, hex, null)
        }
        runCatching { Base64.getDecoder().decode(normalized) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
            ?.let { return Baseline(null, null, it) }
        if (normalized.length in 8..44 && normalized.all { it.isLetterOrDigit() || it in "+/=_-" }) {
            return Baseline(null, null, null)
        }
        return Baseline(null, null, null)
    }
}
