package io.github.wailantirajoh.armrest.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.wailantirajoh.armrest.core.DeviceCredentials
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** deviceId + kunci ECDSA P-256 di Android Keystore. Kunci privat tidak pernah keluar dari Keystore. */
class KeystoreCredentials(context: Context) : DeviceCredentials {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    override val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    override val deviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override val publicKeyDer: ByteArray
        get() = entry().certificate.publicKey.encoded

    override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey())
        update(payload)
        sign()
    }

    private fun privateKey(): PrivateKey = entry().privateKey

    private fun entry(): KeyStore.PrivateKeyEntry {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
        return keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "armrest-device"
        const val KEY_DEVICE_ID = "deviceId"
    }
}
