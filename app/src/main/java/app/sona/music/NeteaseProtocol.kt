package app.sona.music

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Protocol constants documented by the MIT NeteaseCloudMusicApi project; see THIRD_PARTY_NOTICES.md.
object NeteaseProtocol {
    private const val publicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
    private val random = SecureRandom()
    private fun aes(text: String, key: String, cbc: Boolean): ByteArray {
        val cipher = Cipher.getInstance(if (cbc) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        if (cbc) cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec("0102030405060708".toByteArray()))
        else cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }
    fun weapi(text: String, secret: String = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)] }.joinToString("")): Map<String, String> {
        require(secret.length == 16 && secret.all { it.code < 128 })
        val stage = Base64.getEncoder().encodeToString(aes(text, "0CoJUm6Qyw8W8jud", true))
        val params = Base64.getEncoder().encodeToString(aes(stage, secret, true))
        val rsa = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))) as RSAPublicKey
        val enc = BigInteger(1, secret.reversed().toByteArray()).modPow(rsa.publicExponent, rsa.modulus).toString(16).padStart(256, '0')
        return mapOf("params" to params, "encSecKey" to enc)
    }
    fun eapi(path: String, text: String): Map<String, String> {
        val digest = MessageDigest.getInstance("MD5").digest("nobody${path}use${text}md5forencrypt".toByteArray()).hex()
        val payload = "$path-36cd479b6b5-$text-36cd479b6b5-$digest"
        return mapOf("params" to aes(payload, "e82ckenh8dichen8", false).hex().uppercase())
    }
    fun decodeEapi(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
