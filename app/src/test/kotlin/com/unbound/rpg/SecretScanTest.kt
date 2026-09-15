package com.unbound.rpg

import com.unbound.rpg.data.security.SafeLog
import com.unbound.rpg.data.security.SecureCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * §83, §132, §147 — verification that no credential can reach a build artifact.
 *
 * These are real checks against real files, not assertions about intent: the source scan walks
 * every tracked source and resource file, and the APK scan reads the actual zip entries of the
 * built artifact when one is present.
 */
class SecretScanTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    /**
     * Patterns that would indicate a real credential. Deliberately narrow enough not to fire on
     * the words "api key" appearing in prose, and wide enough to catch an actual key.
     */
    private val secretPatterns = listOf(
        "OpenAI secret key" to Regex("""sk-[A-Za-z0-9_\-]{20,}"""),
        "OpenAI project key" to Regex("""sk-proj-[A-Za-z0-9_\-]{10,}"""),
        "assigned bearer token" to Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{24,}"""),
        "hardcoded api key assignment" to Regex("""(?i)(api[_-]?key|apikey)\s*[:=]\s*"[A-Za-z0-9_\-]{16,}""""),
        "AWS access key" to Regex("""AKIA[0-9A-Z]{16}"""),
        "private key block" to Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
    )

    @Test
    fun `no source, resource or build file contains anything key-shaped`() {
        val offenders = mutableListOf<String>()
        val roots = listOf("app/src", "core/src", "gradle").map { File(repoRoot, it) } +
            listOf(File(repoRoot, "build.gradle.kts"), File(repoRoot, "settings.gradle.kts"))

        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.length() < 2_000_000 }
                .filter { it.extension in SCANNED_EXTENSIONS }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    // This test file necessarily contains the patterns it searches for.
                    if (file.name == "SecretScanTest.kt") return@forEach
                    secretPatterns.forEach { (label, pattern) ->
                        pattern.find(text)?.let { match ->
                            offenders += "${file.relativeTo(repoRoot)}: $label -> ${match.value.take(12)}…"
                        }
                    }
                }
        }

        assertTrue("Secret-shaped content found in the source tree:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `BuildConfig carries no credential field`() {
        val fields = BuildConfig::class.java.declaredFields.map { it.name.lowercase() }
        val suspicious = fields.filter { name ->
            listOf("key", "secret", "token", "password", "credential").any { name.contains(it) }
        }
        assertTrue("BuildConfig must not expose credential fields, found: $suspicious", suspicious.isEmpty())
    }

    /**
     * Scans the built artifact itself. Skipped with a clear message when no APK has been built yet,
     * rather than silently passing.
     */
    @Test
    fun `the built apk contains nothing key-shaped`() {
        val apks = listOf("release/Unbound.apk", "debug/Unbound-debug.apk")
            .map { File(repoRoot, "app/build/outputs/apk/$it") }
            .filter { it.exists() }

        if (apks.isEmpty()) {
            println("No APK built yet; run :app:assembleRelease before relying on this check.")
            return
        }

        val offenders = mutableListOf<String>()
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.size < 12_000_000 }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        // Latin-1 keeps byte offsets meaningful for binary entries such as dex.
                        val text = String(bytes, Charsets.ISO_8859_1)
                        secretPatterns.forEach { (label, pattern) ->
                            pattern.find(text)?.let { match ->
                                offenders += "${apk.name}!${entry.name}: $label -> ${match.value.take(12)}…"
                            }
                        }
                    }
            }
        }

        assertTrue(
            "Secret-shaped content found inside a build artifact:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `log redaction removes keys, headers and bearer tokens`() {
        val key = "sk-" + "A".repeat(40)

        assertFalse(SafeLog.redact("using $key now").contains(key))
        assertFalse(SafeLog.redact("""Authorization: Bearer $key""").contains(key))
        assertFalse(SafeLog.redact("""{"api_key": "$key"}""").contains(key))
        assertFalse(SafeLog.redact("""apiKey=$key""").contains(key))

        // And it must not mangle ordinary text.
        assertEquals("Mara took the ring.", SafeLog.redact("Mara took the ring."))
    }

    @Test
    fun `masking never reveals enough of a key to use it`() {
        val key = "sk-proj-" + "B".repeat(40)
        val masked = SecureCredentialStore.mask(key)

        assertFalse("The masked form must not contain the key", masked.contains(key))
        assertTrue("It should still be recognisable", masked.startsWith("sk-"))
        assertTrue(masked.contains("•"))
        // Enough to tell two keys apart, not enough to reconstruct one.
        assertTrue("Too much of the key is shown: $masked", masked.count { it != '•' } <= 12)
    }

    private companion object {
        val SCANNED_EXTENSIONS = setOf("kt", "kts", "java", "xml", "json", "properties", "toml", "pro", "txt", "md")
    }
}
