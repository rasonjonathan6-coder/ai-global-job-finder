package com.aiglobal.jobfinder.data.cv

import com.aiglobal.jobfinder.data.model.CvFileType
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
 */
class PdfCvParser : CvParser {

    override val id: String = "pdfbox-android"
    override val label: String = "PDFBox (local)"

    override fun supports(fileType: CvFileType): Boolean = fileType == CvFileType.PDF

    override suspend fun parse(source: CvSource, fileType: CvFileType): CvParseResult =
        withContext(Dispatchers.IO) {
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

                val text = try {
                    PDFTextStripper().apply { sortByPosition = true }.getText(doc)
                } catch (e: IOException) {
                    throw CvParseException.Corrupted(e)
                }

                val warnings = buildList {
                    if (text.isBlank()) {
                        add("This PDF has no text layer. It is probably a scan or a photo.")
                    }
                }

                CvParseResult(text = text, pageCount = pageCount, warnings = warnings)
            }
        }
}
