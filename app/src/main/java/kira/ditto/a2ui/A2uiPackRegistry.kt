package kira.ditto.a2ui

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.MessageDigest

const val A2uiBasicPackId = "aether.ui.m3e.basic"
const val A2uiMapPackId = "aether.ui.map"
const val A2uiHostRendererBasicId = "m3e.basic"
const val A2uiHostRendererMapId = "map"

/** Embedded Ed25519 public key (32 raw bytes, hex) for signed renderer packs. */
const val A2uiPackSigningPublicKeyHex =
    "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

object A2uiPackRegistry {
    val builtinPacks = setOf(A2uiBasicPackId)
    val hostRendererIds = setOf(A2uiHostRendererBasicId, A2uiHostRendererMapId)

    fun isAllowedPackSource(url: String): Boolean {
        val value = url.trim().lowercase()
        if (value.isBlank()) return false
        return value.contains("github.com/timothy-kira/") ||
            value.contains("raw.githubusercontent.com/timothy-kira/") ||
            value.contains("cdn.jsdelivr.net/gh/timothy-kira/") ||
            value.contains("gcore.jsdelivr.net/gh/timothy-kira/") ||
            value.contains("fastly.jsdelivr.net/gh/timothy-kira/")
    }

    fun hostRendererForPack(packId: String): String = when (packId) {
        A2uiMapPackId -> A2uiHostRendererMapId
        else -> A2uiHostRendererBasicId
    }

    fun verifyPack(
        publicKeyHex: String = A2uiPackSigningPublicKeyHex,
        message: ByteArray,
        signatureHex: String,
    ): Boolean {
        val signature = decodeHex(signatureHex) ?: return false
        val publicKey = decodeHex(publicKeyHex) ?: return false
        if (signature.size != 64 || publicKey.size != 32) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        return verifyEd25519(publicKey, digest, signature) ||
            verifyEd25519(publicKey, message, signature)
    }
}

fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    return runCatching {
        val sdk = runCatching { android.os.Build.VERSION.SDK_INT }.getOrDefault(0)
        if (sdk < 33 && !jvmHasEd25519()) return@runCatching false
        val encoded = encodeEd25519X509(publicKey)
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val key = keyFactory.generatePublic(X509EncodedKeySpec(encoded))
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(key)
        sig.update(message)
        sig.verify(signature)
    }.getOrDefault(false)
}

private fun jvmHasEd25519(): Boolean =
    runCatching { Signature.getInstance("Ed25519") }.isSuccess

private fun encodeEd25519X509(raw: ByteArray): ByteArray {
    val prefix = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    return prefix + raw
}

fun decodeHex(value: String): ByteArray? {
    val hex = value.trim().removePrefix("0x")
    if (hex.length < 2 || hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (index in out.indices) {
        val parsed = hex.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
        out[index] = parsed.toByte()
    }
    return out
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte)
    }
