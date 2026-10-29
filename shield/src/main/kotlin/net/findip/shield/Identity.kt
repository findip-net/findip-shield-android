package net.findip.shield

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** identify() inputs. Raw values stay on the device (spec §5). */
internal data class IdentityInput(
    val userId: String? = null,
    val email: String? = null,
    val plan: String? = null,
    val salt: String? = null,
    val custom: Map<String, Any?>? = null,
)

/** Hashing of identity, matching the web SDK's `sdk/src/core/identify.ts` (identity.json). */
internal object Identity {
    /** RSA-OAEP-2048 with SHA-256 carries at most 190 bytes; longer values are hashed only. */
    const val MAX_ENCRYPTABLE_BYTES = 190

    private fun clean(value: String?): String = value?.trim() ?: ""

    /** Trimmed and lower-cased; valid only with something on both sides of the last `@`. */
    fun normalizeEmail(raw: String?): String {
        val email = clean(raw).lowercase()
        val at = email.lastIndexOf('@')
        return if (at > 0 && at < email.length - 1) email else ""
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The customer_context fields for an identity, before encryption. */
    fun context(input: IdentityInput): MutableMap<String, Any> {
        val context = LinkedHashMap<String, Any>()
        val userId = clean(input.userId)
        val email = normalizeEmail(input.email)
        val plan = clean(input.plan)
        val salt = clean(input.salt)
        val prefix = if (salt.isEmpty()) "" else "$salt:"
        if (plan.isNotEmpty()) context["plan"] = plan
        if (email.isNotEmpty()) context["email_domain"] = email.substring(email.lastIndexOf('@') + 1)
        if (userId.isNotEmpty()) context["user_id_hash"] = sha256Hex(prefix + userId)
        if (email.isNotEmpty()) context["email_hash"] = sha256Hex(prefix + email)
        return context
    }

    /** The values to encrypt, keyed by the customer_context field they go into. */
    fun encryptable(input: IdentityInput): List<Pair<String, String>> = buildList {
        val userId = clean(input.userId)
        val email = normalizeEmail(input.email)
        if (userId.isNotEmpty()) add("user_id_enc" to userId)
        if (email.isNotEmpty()) add("email_enc" to email)
    }
}

/** The site's identity public key, "fk1.<key id>.<base64 SPKI DER>". */
internal class IdentityKey private constructor(val keyId: String, private val key: PublicKey) {
    /**
     * RSA-OAEP with SHA-256 for the digest **and MGF1**, as WebCrypto and the
     * dashboard's Node decryption use. The explicit OAEPParameterSpec matters:
     * several providers default MGF1 to SHA-1, and the dashboard then cannot decrypt.
     */
    fun encrypt(value: String): String? {
        val plain = value.toByteArray(Charsets.UTF_8)
        if (plain.size > Identity.MAX_ENCRYPTABLE_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, OAEP_SHA256)
            "fk1.$keyId.${Base64.getEncoder().encodeToString(cipher.doFinal(plain))}"
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        val OAEP_SHA256: OAEPParameterSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        private val pattern = Regex("^fk1\\.(ik_[a-f0-9]{16})\\.([A-Za-z0-9+/]+={0,2})$")

        fun parse(formatted: String): IdentityKey? {
            val match = pattern.matchEntire(formatted.trim()) ?: return null
            return try {
                val spki = Base64.getDecoder().decode(match.groupValues[2])
                val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
                IdentityKey(match.groupValues[1], key)
            } catch (e: Exception) {
                null
            }
        }
    }
}
