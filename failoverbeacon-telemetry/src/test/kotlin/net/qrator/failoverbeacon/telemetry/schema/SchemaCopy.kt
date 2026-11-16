package net.qrator.failoverbeacon.telemetry.schema

import java.io.File

/** The telemetry schema repository's files at a tag, copied into the test resources unedited. */
object SchemaCopy {
    val root: File = File(
        checkNotNull(
            SchemaCopy::class.java.classLoader?.getResource("telemetry-schema/VERSION")
        ).toURI()
    ).parentFile!!

    fun file(path: String) = File(root, path)

    fun text(path: String) = file(path).readText()

    fun validSamples(schema: String): List<File> =
        file("samples/v1/$schema/valid").listFiles()!!.filter {
            it.extension == "json"
        }.sortedBy { it.name }
}
