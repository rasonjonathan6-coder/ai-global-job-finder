package com.aiglobal.jobfinder.data.cv

import com.aiglobal.jobfinder.data.model.CvFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import com.aiglobal.jobfinder.data.cv.analysis.CvTextNormalizer

/**
 * Plain text import. Useful when a user pastes or exports their CV as `.txt`, and the
 * fallback a user can always reach when a richer format fails to parse.
 */
class PlainTextCvParser : CvParser {

    override val id: String = "plain-text"
    override val label: String = "Plain text"

    override fun supports(fileType: CvFileType): Boolean = fileType == CvFileType.TXT

    override suspend fun parse(source: CvSource, fileType: CvFileType): CvParseResult =
        withContext(Dispatchers.IO) {
            val bytes = source.openStream().use { it.readBytes() }
            val text = CvTextNormalizer.normalize(decode(bytes))
            val warnings = buildList {
                if (text.isBlank()) add("This file is empty.")
            }
            CvParseResult(text = text, pageCount = null, warnings = warnings)
        }

    /** Handles the UTF-8 BOM and falls back to Latin-1 so nothing is silently dropped. */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, StandardCharsets.ISO_8859_1) else utf8
    }
}
