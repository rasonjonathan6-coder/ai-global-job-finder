package com.aiglobal.jobfinder.data.cv

import android.content.Context
import com.aiglobal.jobfinder.data.model.CvFileType
import java.io.InputStream

/**
 * A CV document to parse. Kept as a stream factory rather than a Uri so parsers can be
 * tested on the JVM and so the same abstraction works for any future import source.
 */
interface CvSource {
    val displayName: String
    val sizeBytes: Long
    fun openStream(): InputStream
}

/** Plain in-memory source, used by tests and by anything that already holds bytes. */
class ByteCvSource(
    override val displayName: String,
    private val bytes: ByteArray,
) : CvSource {
    override val sizeBytes: Long get() = bytes.size.toLong()
    override fun openStream(): InputStream = bytes.inputStream()
}

/** How much text a single page contributed, so the UI can show where extraction stalled. */
data class CvPageText(
    /** 1-based page number as the reader sees it. */
    val number: Int,
    val characterCount: Int,
) {
    val isEmpty: Boolean get() = characterCount == 0
}

/** Text extracted from a document, plus anything worth telling the user about it. */
data class CvParseResult(
    val text: String,
    val pageCount: Int? = null,
    val warnings: List<String> = emptyList(),
    /** Per-page character counts; empty when the format has no page concept. */
    val pages: List<CvPageText> = emptyList(),
    /**
     * True when the document has pages, but they carry no text while holding drawable
     * content (raster images). PDFBox cannot read pixels as text — this lets the UI say so
     * honestly instead of blaming a corrupt file.
     */
    val imagesOnly: Boolean = false,
) {
    val characterCount: Int get() = text.length

    /** Non-whitespace characters actually recovered; the honest measure of "how much text". */
    val nonWhitespaceCount: Int get() = text.count { !it.isWhitespace() }

    /** Enough text to feed an analyser a real profile. */
    val hasUsableText: Boolean get() = nonWhitespaceCount >= MIN_USABLE_CHARACTERS

    /** Some text, but too little to be a whole CV. Distinct from "nothing at all". */
    val isPartial: Boolean
        get() = !hasUsableText && nonWhitespaceCount > 0

    /** True as soon as any non-whitespace character was recovered. */
    val hasAnythingExtractable: Boolean get() = nonWhitespaceCount > 0

    companion object {
        /** Below this, there is not enough text to analyse a profile from. */
        const val MIN_USABLE_CHARACTERS = 60
    }
}

/**
 * Why a document could not be turned into text. Each case maps to a specific,
 * actionable message in the UI; none of them leak document content.
 */
sealed class CvParseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The document is password protected or otherwise unreadable. */
    class Encrypted(cause: Throwable? = null) :
        CvParseException("This document is password protected.", cause)

    /** The bytes are not a valid document of the declared type. */
    class Corrupted(cause: Throwable? = null) :
        CvParseException("This file appears to be damaged or incomplete.", cause)

    /** Parsed fine, but contains no text (e.g. a scanned image-only PDF). */
    class NoText(cause: Throwable? = null) :
        CvParseException("No readable text was found in this document.", cause)

    /** The file could not be read from storage. */
    class Unreadable(cause: Throwable? = null) :
        CvParseException("The file could not be read.", cause)

    /** No parser is registered for this format. */
    class Unsupported(val fileType: CvFileType?) :
        CvParseException("This file format is not supported.")
}

/**
 * Turns a document into text. Implementations must be side-effect free, must not log
 * document content, and must never return fabricated text.
 */
interface CvParser {
    /** Stable identifier, persisted with the analysis so results can be traced. */
    val id: String

    /** Human label shown in the UI. */
    val label: String

    fun supports(fileType: CvFileType): Boolean

    /**
     * @throws CvParseException when the document cannot be converted to text.
     */
    suspend fun parse(source: CvSource, fileType: CvFileType): CvParseResult
}

/** Dispatches to the first registered parser that supports the format. */
class CvParserRegistry(private val parsers: List<CvParser>) {

    fun parserFor(fileType: CvFileType): CvParser? = parsers.firstOrNull { it.supports(fileType) }

    suspend fun parse(source: CvSource, fileType: CvFileType): CvParseResult {
        val parser = parserFor(fileType) ?: throw CvParseException.Unsupported(fileType)
        return parser.parse(source, fileType)
    }

    /** Default registry: every format the picker accepts has a real implementation. */
    companion object {
        fun default(context: Context): CvParserRegistry = CvParserRegistry(
            listOf(PdfCvParser(context), DocxCvParser(), DocCvParser(), PlainTextCvParser()),
        )
    }
}
