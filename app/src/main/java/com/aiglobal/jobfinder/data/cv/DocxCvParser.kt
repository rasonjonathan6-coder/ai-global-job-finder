package com.aiglobal.jobfinder.data.cv

import com.aiglobal.jobfinder.data.model.CvFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import com.aiglobal.jobfinder.data.cv.analysis.CvTextNormalizer

/**
 * Text extraction for the OOXML `.docx` container. A .docx is a zip of XML parts; the
 * visible text lives in `w:t` runs inside `w:p` paragraphs of the document and header
 * parts. We read only those parts and never execute or render anything from the archive.
 */
class DocxCvParser : CvParser {

    override val id: String = "ooxml-docx"
    override val label: String = "Word (OOXML)"

    override fun supports(fileType: CvFileType): Boolean = fileType == CvFileType.DOCX

    override suspend fun parse(source: CvSource, fileType: CvFileType): CvParseResult =
        withContext(Dispatchers.IO) {
            val parts = readTextParts(source)
            if (parts.isEmpty()) {
                throw CvParseException.Corrupted()
            }
            val text = CvTextNormalizer.normalize(parts.joinToString("\n"))
            val warnings = buildList {
                if (text.isBlank()) {
                    add("No text was found in the document body.")
                }
            }
            CvParseResult(text = text, pageCount = null, warnings = warnings)
        }

    /** Reads the XML parts that can contain visible text, ignoring the rest of the archive. */
    private fun readTextParts(source: CvSource): List<String> {
        val interesting = Regex("^word/(document|header\\d*|footer\\d*)\\.xml$")
        val out = mutableListOf<String>()
        var decompressed = 0L

        try {
            ZipInputStream(source.openStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && interesting.matches(entry.name)) {
                        val bytes = readCapped(zip) { decompressed += it }
                        if (bytes.isEmpty()) throw CvParseException.Corrupted()
                        out += extractParagraphs(bytes)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: IOException) {
            throw CvParseException.Corrupted(e)
        }

        return out.filter { it.isNotBlank() }
    }

    /**
     * Reads at most [MAX_PART_BYTES] per part and [MAX_TOTAL_BYTES] overall so a crafted
     * archive cannot expand without bound while we hold it in memory.
     */
    private fun readCapped(input: InputStream, onBytes: (Int) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_PART_BYTES) throw CvParseException.Corrupted()
            onBytes(read)
            if (total > MAX_TOTAL_BYTES) throw CvParseException.Corrupted()
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Pulls visible text out of one OOXML part. Paragraph and break elements become
     * newlines so the analyser sees the same section boundaries a human would.
     */
    private fun extractParagraphs(xml: ByteArray): String {
        val builder = StringBuilder()
        try {
            val parser = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
            }.newPullParser()
            parser.setInput(xml.inputStream(), null)

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "p" -> if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
                        "br", "cr" -> builder.append('\n')
                        "tab" -> builder.append(' ')
                    }
                    XmlPullParser.TEXT -> builder.append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.name == "p") builder.append('\n')
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            throw CvParseException.Corrupted(e)
        }
        return builder.toString().replace(Regex("[ \\t]+"), " ").trim()
    }

    private companion object {
        const val MAX_PART_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
    }
}
