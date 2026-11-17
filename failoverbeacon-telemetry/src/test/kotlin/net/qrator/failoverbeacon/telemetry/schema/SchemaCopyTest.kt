package net.qrator.failoverbeacon.telemetry.schema

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.telemetry.BuildConfig

class SchemaCopyTest {
    @Test
    fun `the copy is the tagged files, unedited`() {
        val listed = SchemaCopy.text("SHA256SUMS").lines().filter { it.isNotBlank() }.associate {
            val (hash, path) = it.split("  ", limit = 2)
            path to hash
        }
        val files = SchemaCopy.root.walk()
            .filter { it.isFile && it.name != "SHA256SUMS" }
            .map { it.relativeTo(SchemaCopy.root).invariantSeparatorsPath }
            .toSet()

        assertEquals(listed.keys, files)
        listed.forEach { (path, hash) -> assertEquals(hash, sha256(path), path) }
    }

    @Test
    fun `the copy is the version the uploads report`() {
        assertEquals(SchemaCopy.text("VERSION").trim(), BuildConfig.SCHEMA_VERSION)
    }

    private fun sha256(path: String): String = MessageDigest.getInstance("SHA-256")
        .digest(SchemaCopy.file(path).readBytes())
        .joinToString("") { "%02x".format(it) }
}
