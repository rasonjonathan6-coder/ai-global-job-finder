package com.aiglobal.jobfinder.data.cv

import android.content.Context
import com.aiglobal.jobfinder.data.model.CvFileType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * PDF text extraction via PDFBox-Android. Runs on the IO dispatcher because a large CV
 * can take a moment. Only the text layer is read — scanned image PDFs legitimately have
 * none, and that is reported as [CvParseException.NoText] rather than guessed at.
 *
 * PDFBox loads its font metrics, glyph lists and CMaps from the application's assets, and
 * refuses to run until [PDFBoxResourceLoader] has been pointed at them. That initialisation
 * is required before any PDFBox call, so it happens here, once, lazily: an uninitialised
 * loader made the very first text extraction abort the whole process on a real device.
 */
class PdfCvParser(private val context: Context) : CvParser {

    override val id: String = "pdfbox-android"
    override val label: String = "PDFBox (local)"

    override fun supports(fileType: CvFileType): Boolean = fileType == CvFileType.PDF

    override suspend fun parse(source: CvSource, fileType: CvFileType): CvParseResult =
        withContext(Dispatchers.IO) {
            ensureResourceLoader()

            val document = try {
                source.openStream().use { PDDocument.load(it) }
            } catch (e: InvalidPasswordException) {
                throw CvParseException.Encrypted(e)
            } catch (e: IOException) {
                throw CvParseException.Corrupted(e)
            } catch (e: RuntimeException) {
                // PDFBox throws a variety of unchecked errors on malformed files.
                throw CvParseException.Corrupted(e)
            }

            document.use { doc ->
                val pageCount = doc.numberOfPages
                if (pageCount == 0) throw CvParseException.Corrupted()

                val rawText = try {
                    PDFTextStripper().apply {
                        sortByPosition = true
                        // Explicit defaults, so a layout is not glued together by PDFBox's
                        // own guesses before we ever see the text.
                        lineSeparator = "\n"
                        wordSeparator = " "
                    }.getText(doc)
                } catch (e: IOException) {
                    throw CvParseException.Corrupted(e)
                } catch (e: RuntimeException) {
                    // A missing glyph list or font metric surfaces here on odd documents.
                    throw CvParseException.Corrupted(e)
                }

                val text = CvTextNormalizer.normalize(rawText)

                val warnings = buildList {
                    if (text.count { !it.isWhitespace() } < CvParseResult.MIN_USABLE_CHARACTERS) {
                        add("This PDF has no text layer. It is probably a scan or a photo.")
                    }
                }

                CvParseResult(text = text, pageCount = pageCount, warnings = warnings)
            }
        }

    private fun ensureResourceLoader() {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }
}
