package net.findip.shield

/**
 * The on-device privacy boundary for `custom` context (spec §4, context.json),
 * ported from the web SDK's `sdk/src/utils/safe.ts`. Ingest applies the same
 * rules again; nothing that fails them ever leaves the device.
 */
internal object ContextSanitizer {
    const val MAX_KEYS = 20
    const val MAX_KEY_LENGTH = 64
    const val MAX_STRING_LENGTH = 256

    private val email = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    private val phone = Regex("^[+]?[\\d\\s().-]{7,}$")
    private val card = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val sensitiveKeys = listOf("password", "passwd", "card", "cvv", "cvc", "ssn", "social", "secret")

    fun containsSensitivePattern(value: String): Boolean =
        ('@' in value && email.containsMatchIn(value)) || phone.containsMatchIn(value) || card.containsMatchIn(value)

    fun isSensitiveKey(name: String): Boolean {
        val lower = name.lowercase()
        return sensitiveKeys.any { it in lower }
    }

    /**
     * At most 20 keys, the first in sorted key order (maps carry no reliable
     * insertion order across platforms), sensitive keys and values dropped.
     * Values are kept as String, Long/Int, Double or Boolean.
     */
    fun sanitize(input: Map<String, Any?>): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        for (key in input.keys.sorted()) {
            if (result.size >= MAX_KEYS) break
            if (key.length > MAX_KEY_LENGTH || isSensitiveKey(key)) continue
            when (val value = input[key]) {
                is Boolean -> result[key] = value
                is Int, is Long, is Short, is Byte -> result[key] = (value as Number).toLong()
                is Double -> if (value.isFinite()) result[key] = value
                is Float -> if (value.isFinite()) result[key] = value.toDouble()
                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.isEmpty() || trimmed.length > MAX_STRING_LENGTH) continue
                    if (containsSensitivePattern(trimmed)) continue
                    result[key] = trimmed
                }
                else -> Unit // nested objects, lists and nulls never leave the device
            }
        }
        return result
    }
}
