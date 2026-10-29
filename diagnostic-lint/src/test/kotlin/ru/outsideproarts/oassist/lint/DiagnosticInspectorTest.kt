package ru.outsideproarts.oassist.lint

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticInspectorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reportsUnmatchedCallsAndResults() {
        val archive = temporary.newFile("report.zip")
        writeArchive(archive, """
            {"kind":"TOOL_CALL","operationId":"run","attributes":{"call_id":"a","arguments_valid":"true"}}
            {"kind":"TOOL_RESULT","operationId":"run","attributes":{"call_id":"a"}}
            {"kind":"TOOL_CALL","operationId":"run","attributes":{"call_id":"b","arguments_valid":"true"}}
            {"kind":"TOOL_RESULT","operationId":"run","attributes":{"call_id":"c"}}
        """.trimIndent())
        val output = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(output, true, "UTF-8"))
            main(arrayOf(archive.absolutePath))
        } finally {
            System.setOut(original)
        }
        val text = output.toString("UTF-8")
        assertTrue(text.contains("(run, b)=1"))
        assertTrue(text.contains("(run, c)"))
        assertTrue(!text.contains("(run, a)"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalEntry() {
        val archive = temporary.newFile("traversal.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../data"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        main(arrayOf(archive.absolutePath))
    }

    private fun writeArchive(file: File, events: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("manifest.json" to "{\"version\":1}", "events.jsonl" to events).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
