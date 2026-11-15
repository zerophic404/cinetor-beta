package com.mythbrix.cinetor.core.download

import com.mythbrix.cinetor.core.legal.UserErrors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Keyed obfuscation for the resume metadata that must survive a restart but must not sit in storage
 * as plaintext (docs/requirements-hardening.md §2): source URL, request headers and sidecar subtitle
 * URLs. Deterrent-level by design (the key lives in app-private prefs, no DRM is claimed) — the goal
 * is that a dumped / exported Room database contains no readable endpoint.
 */
class UrlVault(private val secret: ByteArray) {
    private fun xor(bytes: ByteArray): ByteArray =
        ByteArray(bytes.size) { i -> (bytes[i].toInt() xor secret[i % secret.size].toInt()).toByte() }

    fun seal(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return PREFIX + hex(xor(plain.toByteArray(Charsets.UTF_8)))
    }

    fun open(sealed: String): String {
        if (!sealed.startsWith(PREFIX)) return sealed // legacy row written before the vault existed
        return runCatching { String(xor(unhex(sealed.removePrefix(PREFIX))), Charsets.UTF_8) }.getOrDefault("")
    }

    companion object {
        const val PREFIX = "v1~"

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        private fun unhex(s: String) = ByteArray(s.length / 2) { i ->
            ((digit(s[i * 2]) shl 4) or digit(s[i * 2 + 1])).toByte()
        }

        private fun digit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> 0
        }

        /** Per-install random key, kept in app-private prefs (never in shared / external storage). */
        fun forApp(context: android.content.Context): UrlVault {
            val prefs = context.applicationContext.getSharedPreferences("cinetor_dl_vault", android.content.Context.MODE_PRIVATE)
            val existing = prefs.getString("k", null)
            val key = existing ?: hex(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
                .also { prefs.edit().putString("k", it).apply() }
            return UrlVault(unhex(key))
        }
    }
}

/**
 * Wraps a [DownloadStore] so the locator fields are sealed on the way in and opened on the way out.
 * Everything above this decorator keeps working with plain values; nothing below it ever sees them.
 */
class VaultedDownloadStore(private val delegate: DownloadStore, private val vault: UrlVault) : DownloadStore {

    private fun seal(r: DownloadRecord) = r.copy(
        url = vault.seal(r.url),
        headers = vault.seal(r.headers),
        sidecarSubs = vault.seal(r.sidecarSubs),
    )

    private fun open(r: DownloadRecord) = r.copy(
        url = vault.open(r.url),
        headers = vault.open(r.headers),
        sidecarSubs = vault.open(r.sidecarSubs),
    )

    override fun observe(): Flow<List<DownloadRecord>> = delegate.observe().map { list -> list.map(::open) }
    override suspend fun all(): List<DownloadRecord> = delegate.all().map(::open)
    override suspend fun get(id: String): DownloadRecord? = delegate.get(id)?.let(::open)
    override suspend fun upsert(record: DownloadRecord) = delegate.upsert(seal(record))
    override suspend fun delete(id: String) = delegate.delete(id)
    override suspend fun chunks(id: String) = delegate.chunks(id)
    override suspend fun saveChunks(id: String, chunks: List<ChunkState>) = delegate.saveChunks(id, chunks)

    /** Events are shown in the details sheet → keep them generic, never a locator. */
    override suspend fun addEvent(event: DownloadEvent) = delegate.addEvent(
        if (UserErrors.containsLocator(event.message)) event.copy(message = UserErrors.DOWNLOAD) else event,
    )

    override suspend fun events(id: String, limit: Int) = delegate.events(id, limit)
}
