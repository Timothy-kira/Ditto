package kira.ditto.agentmode.adb

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

internal class AdbKey(
    private val keyStore: AdbKeyStore,
    private val name: String,
) {
    private val encryptionKey: Key = getOrCreateEncryptionKey()
        ?: error("Failed to create ADB encryption key.")
    private val privateKey: RSAPrivateKey = getOrCreatePrivateKey()
    private val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

    val adbPublicKey: ByteArray by lazy { publicKey.adbEncoded(name) }

    val sslContext: SSLContext by lazy {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(arrayOf(tlsKeyManager()), arrayOf(trustAll()), SecureRandom())
        sslContext
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(Padding)
        return cipher.doFinal(data)
    }

    private fun getOrCreateEncryptionKey(): Key? {
        val store = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        store.getKey(EncryptionAlias, null)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            EncryptionAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
            .apply { init(spec) }
            .generateKey()
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        val aad = ByteArray(16).also { "adbkey".toByteArray().copyInto(it) }
        keyStore.get()?.let { ciphertext ->
            runCatching {
                val plaintext = decrypt(ciphertext, aad)
                return KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            }
        }
        val generated = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)) }
            .generateKeyPair()
            .private as RSAPrivateKey
        encrypt(generated.encoded, aad)?.let { keyStore.put(it) }
        return generated
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray? {
        val cipher = Cipher.getInstance(AesGcm)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        val ciphertext = ByteArray(IvSize + plaintext.size + TagSize)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IvSize)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IvSize)
        return ciphertext
    }

    private fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AesGcm)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey,
            GCMParameterSpec(8 * TagSize, ciphertext, 0, IvSize),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IvSize, ciphertext.size - IvSize)
    }

    private fun tlsKeyManager(): X509ExtendedKeyManager {
        ensureTlsCertificate()
        val store = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        val certificate = store.getCertificate(TlsAlias) as X509Certificate
        val tlsPrivateKey = store.getKey(TlsAlias, null) as PrivateKey
        return object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyTypes: Array<out String>,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = if (keyTypes.contains("RSA")) TlsAlias else null

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == TlsAlias) arrayOf(certificate) else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == TlsAlias) tlsPrivateKey else null

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = null
        }
    }

    private fun ensureTlsCertificate() {
        val store = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        if (store.containsAlias(TlsAlias)) return
        val spec = KeyGenParameterSpec.Builder(
            TlsAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=00"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(0))
            .setCertificateNotAfter(Date(2_461_449_600_000L))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, AndroidKeyStore)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun trustAll(): X509ExtendedTrustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val EncryptionAlias = "_aether_adbkey_enc_"
        const val TlsAlias = "_aether_adb_tls_"
        const val AesGcm = "AES/GCM/NoPadding"
        const val IvSize = 12
        const val TagSize = 16
        val Padding = byteArrayOf(
            0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14,
        )
    }
}

internal interface AdbKeyStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

internal class PreferenceAdbKeyStore(
    private val preferences: SharedPreferences,
) : AdbKeyStore {
    override fun put(bytes: ByteArray) {
        preferences.edit { putString(PrefKey, Base64.encodeToString(bytes, Base64.NO_WRAP)) }
    }

    override fun get(): ByteArray? {
        val encoded = preferences.getString(PrefKey, null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    private companion object {
        const val PrefKey = "adbkey"
    }
}

private const val AndroidPubkeyModulusSize = 256
private const val AndroidPubkeyModulusSizeWords = AndroidPubkeyModulusSize / 4
private const val RsaPublicKeySize = 524

private fun BigInteger.toAdbEncoded(): IntArray {
    val encoded = IntArray(AndroidPubkeyModulusSizeWords)
    val r32 = BigInteger.ZERO.setBit(32)
    var tmp = this
    for (index in 0 until AndroidPubkeyModulusSizeWords) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        encoded[index] = out[1].toInt()
    }
    return encoded
}

private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(AndroidPubkeyModulusSize * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)
    val buffer = ByteBuffer.allocate(RsaPublicKeySize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(AndroidPubkeyModulusSizeWords)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())
    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    return base64Bytes + nameBytes
}
