package net.findip.shield

import android.content.Context
import java.io.File
import java.io.IOException
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

/**
 * A file per site key in `noBackupFilesDir`: cleared on uninstall, and left out of
 * Auto Backup and device-to-device transfer, so a restored phone never carries
 * another device's visitor ID. The directory is resolved on first use, off the main thread.
 *
 * A visitor ID that 1.0.0 kept in SharedPreferences moves here on first read.
 */
internal class NoBackupFileStore(
    resolveDirectory: () -> File,
    private val legacy: IdentifierStore? = null,
) : IdentifierStore {
    private val directory by lazy(resolveDirectory)

    private fun file(account: String) = File(directory, "visitor-" + account.filter { it.isLetterOrDigit() || it == '_' })

    @Synchronized
    override fun load(account: String): String? {
        val file = file(account)
        if (file.exists()) return try { file.readText().trim().takeIf { it.isNotEmpty() } } catch (e: IOException) { null }
        val moved = legacy?.load(account) ?: return null
        save(moved, account)
        legacy.delete(account)
        return moved
    }

    @Synchronized
    override fun save(value: String, account: String) {
        try {
            directory.mkdirs()
            val target = file(account)
            val temp = File(directory, target.name + ".tmp")
            temp.writeText(value)
            if (!temp.renameTo(target)) temp.delete()
        } catch (e: IOException) {
            // Nothing kept: the next launch makes a new visitor ID.
        }
    }

    @Synchronized
    override fun delete(account: String) {
        file(account).delete()
        legacy?.delete(account)
    }
}

/** Where 1.0.0 kept the visitor ID; read only to move it to [NoBackupFileStore]. */
internal class SharedPreferencesStore(context: Context) : IdentifierStore {
    private val preferences by lazy { context.applicationContext.getSharedPreferences("net.findip.shield", Context.MODE_PRIVATE) }
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
