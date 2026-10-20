package com.dshremote.app

import com.dshremote.app.files.renderOfficePdf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveOfficeTest {
    @Test
    fun convertsAWorkspaceDocxToPdf() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val root = liveTestWorkspace()
        val file = File(root, "docs/office-preview-test.docx")
        writeDocx(file)
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, root)
        try {
            val blob = client.renderOfficePdf(target, session.id, "docs/office-preview-test.docx")
            assertTrue(blob.bytes.size > 100, "PDF response too small: ${blob.bytes.size}")
            assertTrue(
                blob.bytes.take(4).toByteArray().decodeToString() == "%PDF",
                "response is not a PDF",
            )
        } finally {
            runCatching { client.archive(target, session.id) }
            file.delete()
        }
        Unit
    }

    private fun writeDocx(file: File) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.encodeToByteArray())
                zip.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            )
            entry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            )
            entry(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>DSH Office preview test</w:t></w:r></w:p></w:body></w:document>""",
            )
        }
    }
}
