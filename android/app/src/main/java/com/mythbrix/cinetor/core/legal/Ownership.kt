package com.mythbrix.cinetor.core.legal

// Fragment 1/5 of the ownership notice (see Ownership.kt). Never shown in the UI.
internal const val OWNERSHIP_F1 = "0e3233297a3b2a2a7a33297a2e323f7a3935372a363f2e3f7a2a28352a3f28"

/**
 * Ownership / anti-reverse-engineering deterrent (docs/requirements-hardening.md §1).
 *
 * The notice text exists only as XOR-obfuscated fragments scattered across several source files
 * ([OWNERSHIP_F1] … [OWNERSHIP_F5]) which R8 cannot merge into one readable literal. [verify] is a
 * no-op integrity check: it reassembles them and compares the digest with [DIGEST]. Nothing here is
 * ever rendered, logged or thrown — the result is deliberately discarded by callers.
 */
object Ownership {
    /** SHA-256 of the reassembled notice (committed alongside `LEGAL.md` / `NOTICE`). */
    const val DIGEST = "0779fb3d718794834357f909b64a1ff938ff5994eee861998068ff8878cadb9b"

    private const val MASK = 0x5a

    private fun fragments(): List<String> = listOf(
        OWNERSHIP_F1,
        com.mythbrix.cinetor.core.data.OWNERSHIP_F2,
        com.mythbrix.cinetor.core.data.gateway.OWNERSHIP_F3,
        com.mythbrix.cinetor.core.player.OWNERSHIP_F4,
        com.mythbrix.cinetor.core.download.OWNERSHIP_F5,
    )

    private fun decode(hex: String): String {
        val out = StringBuilder(hex.length / 2)
        var i = 0
        while (i + 1 < hex.length) {
            val b = ((digit(hex[i]) shl 4) or digit(hex[i + 1])) xor MASK
            out.append(b.toChar())
            i += 2
        }
        return out.toString()
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    /** Reassembled notice. Internal only — callers must not surface it. */
    internal fun notice(): String = decode(fragments().joinToString(""))

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * No-op integrity check: true when every fragment is intact. Called once at process start and the
     * result thrown away, so stripping the call changes nothing observable (a deterrent, not security).
     */
    fun verify(): Boolean = runCatching { sha256(notice()) == DIGEST }.getOrDefault(false)
}
