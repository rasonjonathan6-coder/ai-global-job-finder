package com.aiglobal.jobfinder.data.cv

import com.aiglobal.jobfinder.data.model.CvFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/**
 * Text extraction for legacy binary `.doc` (Word 97-2003) files.
 *
 * These files are OLE2 containers holding a `WordDocument` stream plus a table stream
 * (`0Table` or `1Table`). The text lives in the piece table inside the table stream:
 * a CLX that lists character ranges, each pointing at an offset in `WordDocument`.
 * We read exactly those ranges — nothing is reconstructed or guessed.
 */
class DocCvParser : CvParser {

    override val id: String = "ms-doc-binary"
    override val label: String = "Word 97-2003"

    override fun supports(fileType: CvFileType): Boolean = fileType == CvFileType.DOC

    override suspend fun parse(source: CvSource, fileType: CvFileType): CvParseResult =
        withContext(Dispatchers.IO) {
            val compound = source.openStream().use { CompoundFile.open(it) }
                ?: throw CvParseException.Corrupted()

            val wordDocument = compound.stream("WordDocument")
                ?: throw CvParseException.Corrupted()

            val flags = readUShort(wordDocument, 0x0A)
            if (flags and FLAG_ENCRYPTED != 0) throw CvParseException.Encrypted()

            val tableName = if (flags and FLAG_TABLE_STREAM != 0) "1Table" else "0Table"
            val table = compound.stream(tableName) ?: throw CvParseException.Corrupted()

            val text = extractText(wordDocument, table)
            val warnings = buildList {
                if (text.isBlank()) add("No text was found in the document body.")
            }
            CvParseResult(text = text, pageCount = null, warnings = warnings)
        }

    /** Walks the piece table and concatenates the character runs it describes. */
    private fun extractText(wordDocument: ByteArray, table: ByteArray): String {
        val fcClx = readInt(wordDocument, 0x01A2)
        val lcbClx = readInt(wordDocument, 0x01A6)
        if (fcClx <= 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) {
            throw CvParseException.Corrupted()
        }

        // Skip any Prc (0x01) property-modifier records to reach the Pcdt (0x02).
        var cursor = fcClx
        val end = fcClx + lcbClx
        while (cursor < end && table[cursor].toInt() == 0x01) {
            if (cursor + 3 > end) throw CvParseException.Corrupted()
            val cbGrpprl = readUShort(table, cursor + 1)
            cursor += 3 + cbGrpprl
        }
        if (cursor + 5 > end || table[cursor].toInt() != 0x02) throw CvParseException.Corrupted()

        val lcb = readInt(table, cursor + 1)
        val plcStart = cursor + 5
        if (lcb < 12 || plcStart + lcb > table.size) throw CvParseException.Corrupted()

        val pieceCount = (lcb - 4) / 12
        if (pieceCount <= 0) throw CvParseException.Corrupted()

        val cps = IntArray(pieceCount + 1) { readInt(table, plcStart + it * 4) }
        val pcdBase = plcStart + (pieceCount + 1) * 4

        val out = StringBuilder()
        for (i in 0 until pieceCount) {
            val characterCount = cps[i + 1] - cps[i]
            if (characterCount <= 0) continue

            val fcCompressed = readInt(table, pcdBase + i * 8 + 2)
            val compressed = fcCompressed and FLAG_COMPRESSED != 0
            val offset = if (compressed) fcCompressed and 0x3FFFFFFF shr 1 else fcCompressed and 0x3FFFFFFF
            val byteCount = if (compressed) characterCount else characterCount * 2
            if (offset < 0 || offset + byteCount > wordDocument.size) continue

            val charset = if (compressed) WINDOWS_1252 else Charsets.UTF_16LE
            out.append(String(wordDocument, offset, byteCount, charset))
        }

        return normalize(out.toString())
    }

    /** Word marks paragraphs and cells with control characters; turn them into newlines. */
    private fun normalize(raw: String): String = raw
        .replace('\r', '\n')
        .replace('\u0007', '\n')
        .replace('\u000B', '\n')
        .replace('\u000C', '\n')
        .replace('\u0000', ' ')
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) throw CvParseException.Corrupted()
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readUShort(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) throw CvParseException.Corrupted()
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private companion object {
        const val FLAG_TABLE_STREAM = 0x0200
        const val FLAG_ENCRYPTED = 0x0100
        const val FLAG_COMPRESSED = 0x40000000
        val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    }
}
