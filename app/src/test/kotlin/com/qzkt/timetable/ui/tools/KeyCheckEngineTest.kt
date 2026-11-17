package com.qzkt.timetable.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class KeyCheckEngineTest {
    private val privateKey = Base64.getDecoder().decode(
        "dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo=",
    )
    private val expectedPublicKey = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo="

    @Test
    fun `private key derives RFC7748 public key and report excludes secret`() {
        val secret = Base64.getEncoder().encodeToString(privateKey)
        val report = KeyCheckEngine.analyze("[Interface]\nPrivateKey = $secret")

        assertEquals(1, report.validCount)
        assertEquals(0, report.invalidCount)
        assertTrue(report.findings.single().fingerprint != null)
        assertFalse(report.toRedactedJson().contains(secret))
        assertFalse(report.toText().contains(secret))
    }

    @Test
    fun `public key can match a SHA256 fingerprint prefix`() {
        val privateReport = KeyCheckEngine.analyze("PrivateKey = ${Base64.getEncoder().encodeToString(privateKey)}")
        val fingerprint = requireNotNull(privateReport.findings.single().fingerprint)
        val publicReport = KeyCheckEngine.analyze(
            "PublicKey = $expectedPublicKey",
            fingerprint.take(12),
        )

        assertEquals(KeyCheckStatus.VALID, publicReport.findings.single().status)
        assertTrue(publicReport.findings.single().details.contains("匹配"))
    }

    @Test
    fun `incorrect baseline is reported without invalidating key encoding`() {
        val report = KeyCheckEngine.analyze("PublicKey = $expectedPublicKey", "00000000")

        assertEquals(1, report.warningCount)
        assertEquals(KeyCheckStatus.WARNING, report.findings.single().status)
    }

    @Test
    fun `base64 fingerprint and QR query keys are supported`() {
        val publicBytes = Base64.getDecoder().decode(expectedPublicKey)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicBytes)
        val base64Fingerprint = Base64.getEncoder().encodeToString(digest)
        val report = KeyCheckEngine.analyze(
            "https://example.invalid/peer?PublicKey=$expectedPublicKey",
            "SHA-256:$base64Fingerprint",
        )

        assertEquals(1, report.validCount)
        assertTrue(report.findings.single().details.contains("匹配"))
    }

    @Test
    fun `configuration endpoint validation remains offline`() {
        val valid = KeyCheckEngine.analyze(
            "[Peer]\nPublicKey = $expectedPublicKey\nEndpoint = 203.0.113.42:51820",
        )
        val invalid = KeyCheckEngine.analyze(
            "[Peer]\nPublicKey = $expectedPublicKey\nEndpoint = 999.0.0.1:70000",
        )

        assertTrue(valid.findings.any { it.name == "Endpoint" && it.status == KeyCheckStatus.VALID })
        assertTrue(invalid.findings.any { it.name == "Endpoint" && it.status == KeyCheckStatus.INVALID })
    }

    @Test
    fun `malformed public key and low order point are invalid`() {
        val malformed = KeyCheckEngine.analyze("PublicKey = AAA=")
        val lowOrder = Base64.getEncoder().encodeToString(ByteArray(32))
        val lowOrderReport = KeyCheckEngine.analyze("PublicKey = $lowOrder")

        assertEquals(1, malformed.invalidCount)
        assertEquals(1, lowOrderReport.invalidCount)
    }

    @Test
    fun `matching private and public keys in one section are reported`() {
        val secret = Base64.getEncoder().encodeToString(privateKey)
        val report = KeyCheckEngine.analyze(
            "[Interface]\nPrivateKey = $secret\nPublicKey = $expectedPublicKey",
        )

        assertEquals(3, report.validCount)
        assertTrue(report.findings.any { it.name == "同段公私钥匹配" && it.status == KeyCheckStatus.VALID })
    }

    @Test
    fun `generated keys are valid and correspond to each other`() {
        val generated = KeyCheckEngine.generateKeys()
        val report = KeyCheckEngine.analyze(
            "[Interface]\nPrivateKey = ${generated.privateKey}\nPublicKey = ${generated.publicKey}",
        )

        assertNotNull(generated.privateKey)
        assertEquals(3, report.validCount)
        assertEquals(0, report.invalidCount)
    }
}
