package com.qzkt.timetable.yxy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class YxySession(
    val deviceId: String,
    val phone: String,
    val ymId: String,
    val token: String,
    val schoolCode: String,
)

internal class YxySecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("yxy_secure_session", Context.MODE_PRIVATE)

    fun load(): YxySession? {
        val encrypted = preferences.getString(SESSION_KEY, null) ?: return null
        val packed = Base64.decode(encrypted, Base64.NO_WRAP)
        require(packed.size > IV_LENGTH) { "本地易校园会话数据损坏，请重新登录" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, packed, 0, IV_LENGTH))
        val json = JSONObject(String(cipher.doFinal(packed, IV_LENGTH, packed.size - IV_LENGTH), Charsets.UTF_8))
        return YxySession(
            deviceId = json.getString("deviceId"),
            phone = json.getString("phone"),
            ymId = json.getString("ymId"),
            token = json.getString("token"),
            schoolCode = json.getString("schoolCode"),
        )
    }

    fun save(session: YxySession) {
        val json = JSONObject()
            .put("deviceId", session.deviceId)
            .put("phone", session.phone)
            .put("ymId", session.ymId)
            .put("token", session.token)
            .put("schoolCode", session.schoolCode)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        check(preferences.edit().putString(SESSION_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "无法保存易校园登录状态"
        }
    }

    fun clear() {
        check(preferences.edit().remove(SESSION_KEY).commit()) { "无法清除易校园登录状态" }
    }

    fun clearAndDeleteKey() {
        clear()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val SESSION_KEY = "encrypted_session"
        const val KEY_ALIAS = "tools_yxy_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}

internal fun clearYxyPluginData(context: Context) {
    YxySecureStore(context.applicationContext).clearAndDeleteKey()
}
