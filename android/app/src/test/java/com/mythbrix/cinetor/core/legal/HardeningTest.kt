package com.mythbrix.cinetor.core.legal

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.gateway.GatewayConfig
import com.mythbrix.cinetor.core.download.UrlVault
import com.mythbrix.cinetor.core.download.VaultedDownloadStore
import com.mythbrix.cinetor.core.download.DownloadEvent
import com.mythbrix.cinetor.core.download.DownloadKind
import com.mythbrix.cinetor.core.download.DownloadRecord
import com.mythbrix.cinetor.core.download.InMemoryDownloadStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** docs/requirements-hardening.md §1 + §2. */
class HardeningTest {

    private val repoRoot: File by lazy { TestFiles.sharedDir.parentFile!! }
    private val androidSrc: File by lazy { File(repoRoot, "android/app/src/main") }

    private fun kotlinSources(): List<File> =
        File(androidSrc, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ---------------------------------------------------------------------------------------- §1

    @Test
    fun ownership_noticeIsScatteredEncodedAndIntact() {
        assertTrue("integrity check must pass", Ownership.verify())
        val notice = Ownership.notice()
        assertEquals(
            "This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, " +
                "scrape or hack anything without Mythbrix's explicit permission.",
            notice,
        )
        // The plaintext must not exist as a single literal anywhere in the shipped Kotlin.
        val needle = "complete property of Mythbrix. Do not touch"
        val offenders = kotlinSources().filter { f ->
            f.readLines().any { l -> needle in l && !l.trimStart().startsWith("//") }
        }
        assertTrue("plaintext notice literal in $offenders", offenders.isEmpty())
        // ... and the fragments live in more than one file.
        val fragmentFiles = kotlinSources().filter { "OWNERSHIP_F" in it.readText() }
        assertTrue("fragments must be scattered, found ${fragmentFiles.size}", fragmentFiles.size >= 5)
        // LEGAL + the signing fingerprint the AI directive refers to are committed. The four repo-root
        // agent-instruction files are sandbox-write-protected → canonical text lives in docs/ (owner copies it).
        listOf("LEGAL.md", "SIGNING_FINGERPRINT", "docs/ai-ownership-directive.md").forEach {
            assertTrue("$it missing", File(repoRoot, it).isFile)
        }
    }

    @Test
    fun release_stripsLogcat() {
        val rules = File(repoRoot, "android/app/proguard-rules.pro").readText()
        assertTrue("android.util.Log must be stripped in release", "-assumenosideeffects class android.util.Log" in rules)
        // Nothing may call android.util.Log directly any more except the guarded DebugLog mirror.
        val direct = kotlinSources().filter { f ->
            f.name != "DebugLog.kt" && Regex("""(^|[^\w.])Log\.[vdiwe]\(""").containsMatchIn(f.readText())
        }
        assertTrue("direct Log calls in $direct", direct.isEmpty())
    }

    // ---------------------------------------------------------------------------------------- §2

    @Test
    fun uiStrings_carryNoGatewayBaseUrl() {
        val host = GatewayConfig.BASE_URL.removePrefix("https://")
        // String resources: the only user-visible XML copy.
        File(androidSrc, "res").walkTopDown()
            .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
            .forEach { f ->
                val t = f.readText()
                assertFalse("${f.name} contains the gateway base URL", host in t)
                assertFalse("${f.name} contains a http(s) locator", Regex("https?://(?!schemas\\.android\\.com)").containsMatchIn(t))
            }
        // Compose user-facing copy: no Text("https://…") / Text("…<gateway host>…") literals.
        val offenders = kotlinSources().filter { f ->
            Regex("""Text\(\s*"[^"]*(https?://|""" + Regex.escape(host) + """)""").containsMatchIn(f.readText())
        }
        assertTrue("gateway/base URL inside a UI string: $offenders", offenders.isEmpty())
        // The base URL constant itself is referenced only by the gateway layer, never by a UI file.
        val uiRefs = kotlinSources().filter {
            it.path.contains("/feature/") && "GatewayConfig.BASE_URL" in it.readText()
        }
        assertTrue("UI references the base URL constant: $uiRefs", uiRefs.isEmpty())
    }

    @Test
    fun userFacingErrors_areGenericAndLocatorFree() {
        listOf(
            UserErrors.PLAYBACK, UserErrors.DOWNLOAD, UserErrors.CATALOG,
            UserErrors.NOT_FOUND, UserErrors.GENERIC, UserErrors.OFFLINE,
        ).forEach { assertFalse(it, UserErrors.containsLocator(it)) }
        assertEquals("Playback failed. Try another quality.", UserErrors.PLAYBACK)

        // Real-world exception messages that embed the endpoint are never passed through.
        val leaky = listOf(
            IOException("Failed to connect to ${GatewayConfig.BASE_URL.removePrefix("https://")}/api/public/v1/home"),
            IllegalStateException("no source for https://cdn.example.net/1080.mp4?t=1"),
            IOException("Unable to resolve host \"antiblock-gateway.vercel.app\""),
            RuntimeException("GET /api/public/v1/stream/123 failed"),
            IOException("connect to 103.21.44.8:443 timed out"),
        )
        leaky.forEach { e ->
            val shown = UserErrors.safe(e, UserErrors.PLAYBACK)
            assertFalse("leak: $shown", UserErrors.containsLocator(shown))
            assertFalse("leak: $shown", shown.contains("vercel", true))
            assertFalse("leak: $shown", shown.contains("cdn", true))
        }
        // Short, human, locator-free text is still allowed through.
        assertEquals("No sources", UserErrors.safe(UserErrors.NoRouteException("No sources"), UserErrors.PLAYBACK))
        assertEquals(UserErrors.GENERIC, UserErrors.safe(null))
    }

    @Test
    fun debugLog_isOffByDefaultAndCollectsNothing() {
        DebugLog.setEnabled(false)
        assertFalse(DebugLog.enabled)
        var built = 0
        DebugLog.log("t") { built++; "https://secret/x" }
        assertEquals("message must not even be built while off", 0, built)
        assertTrue(DebugLog.snapshot().isEmpty())
        DebugLog.setEnabled(true)
        DebugLog.log("t") { "detail" }
        assertEquals(1, DebugLog.snapshot().size)
        // Turning it off wipes what was collected.
        DebugLog.setEnabled(false)
        assertTrue(DebugLog.snapshot().isEmpty())
    }

    @Test
    fun resumeMetadata_storesNoPlaintextUrl() = runTest {
        val vault = UrlVault("test-secret-key".toByteArray())
        val inner = InMemoryDownloadStore()
        val store = VaultedDownloadStore(inner, vault)
        val url = "https://cdn.example.net/movie-1080.mp4?token=abc"
        val record = DownloadRecord(
            id = "d1", itemId = "gw:1", title = "Movie", kind = DownloadKind.PROGRESSIVE,
            url = url, headers = "Referer=https://movieboxhd.net/", sidecarSubs = "en|https://cdn.example.net/en.srt",
        )
        store.upsert(record)
        // What actually sits in storage.
        val raw = inner.get("d1")!!
        assertNotEquals(url, raw.url)
        assertFalse(raw.url, UserErrors.containsLocator(raw.url))
        assertFalse(raw.headers, UserErrors.containsLocator(raw.headers))
        assertFalse(raw.sidecarSubs, UserErrors.containsLocator(raw.sidecarSubs))
        assertTrue(raw.url.startsWith(UrlVault.PREFIX))
        // Resume still works: the engine reads the plain values back.
        assertEquals(url, store.get("d1")!!.url)
        assertEquals("Referer=https://movieboxhd.net/", store.get("d1")!!.headers)
        // Rows written before the vault existed keep working.
        inner.upsert(record.copy(id = "legacy"))
        assertEquals(url, store.get("legacy")!!.url)
        // Events shown in the details sheet are scrubbed.
        store.addEvent(DownloadEvent(downloadId = "d1", time = 1L, type = "error", message = "GET $url failed"))
        assertEquals(UserErrors.DOWNLOAD, inner.events("d1").single().message)
    }
}
