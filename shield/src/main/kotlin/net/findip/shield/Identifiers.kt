package net.findip.shield

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

/** Session and visitor IDs (spec §3): a prefix and 22 URL-safe characters, 128 bits from SecureRandom. */
internal object Identifiers {
    private val random = SecureRandom()

    fun make(prefix: String): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Where the visitor ID lives between launches. */
internal interface IdentifierStore {
    fun load(account: String): String?
    fun save(value: String, account: String)
    fun delete(account: String)
}

/** App-private SharedPreferences: cleared on uninstall. Not encrypted: the ID is random and holds nothing. */
internal class SharedPreferencesStore(context: Context) : IdentifierStore {
    private val preferences = context.applicationContext.getSharedPreferences("net.findip.shield", Context.MODE_PRIVATE)
    override fun load(account: String): String? = preferences.getString("visitor:$account", null)
    override fun save(value: String, account: String) = preferences.edit().putString("visitor:$account", value).apply()
    override fun delete(account: String) = preferences.edit().remove("visitor:$account").apply()
}

/** For tests, and for apps that want nothing kept between launches. */
internal class MemoryStore : IdentifierStore {
    private val values = HashMap<String, String>()
    @Synchronized override fun load(account: String): String? = values[account]
    @Synchronized override fun save(value: String, account: String) { values[account] = value }
    @Synchronized override fun delete(account: String) { values.remove(account) }
}
