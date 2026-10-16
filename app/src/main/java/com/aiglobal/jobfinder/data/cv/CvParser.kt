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

/** Text extracted from a document, plus anything worth telling the user about it. */
data class CvParseResult(
    val text: String,
    val pageCount: Int? = null,
    val warnings: List<String> = emptyList(),
) {
    val characterCount: Int get() = text.length
    val hasUsableText: Boolean get() = text.count { !it.isWhitespace() } >= MIN_USABLE_CHARACTERS

    companion object {
        /** Below this, there is nothing worth sending to an analyser. */
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
