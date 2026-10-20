package com.aiglobal.jobfinder.data.cv

import android.content.Context
import com.aiglobal.jobfinder.data.model.CvFileType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import com.aiglobal.jobfinder.data.cv.analysis.CvTextNormalizer

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

                // Extract page by page. Every page is attempted and every page is counted,
                // so a single unhealthy page can never silently swallow the rest of the
                // document — the failure the user actually saw (only the title recovered).
                val perPage = ArrayList<String>(pageCount)
                val pageTexts = ArrayList<CvPageText>(pageCount)
                for (index in 0 until pageCount) {
                    val pageText = try {
                        // XY-cut reading order, not PDFBox's row-by-row order: a sidebar or
                        // two-column CV would otherwise be interleaved and lose its headings.
                        // Guarded: the layout pass must never recover *less* than PDFBox's
                        // default order, so a pathological page still yields its text.
                        recoverPageText(doc, index)
                    } catch (e: IOException) {
                        throw CvParseException.Corrupted(e)
                    } catch (e: RuntimeException) {
                        // A missing glyph list or font metric surfaces here on odd documents.
                        throw CvParseException.Corrupted(e)
                    }
                    perPage += pageText
                    pageTexts += CvPageText(
                        number = index + 1,
                        characterCount = pageText.count { !it.isWhitespace() },
                    )
                }

                // Join the pages with a blank line so the last word of one page is never
                // glued to the first of the next, then normalise once over the whole text.
                val text = CvTextNormalizer.normalize(perPage.joinToString("\n\n"))

                val recovered = text.count { !it.isWhitespace() }
                val blankPages = pageTexts.count { it.isEmpty }
                val carriesDrawing = doc.anyPageHasDrawableContent()
                val imagesOnly = recovered == 0 && carriesDrawing

                val warnings = buildList {
                    when {
                        imagesOnly -> add(
                            "This PDF has no text layer — every page is an image. " +
                                "Reading it needs OCR, which this app does not do. Export or " +
                                "scan the CV with a text layer, or paste it as a .txt file.",
                        )

                        recovered == 0 -> add(
                            "This PDF has no text layer. It is empty, or its pages hold only " +
                                "drawings rather than selectable text.",
                        )

                        recovered < CvParseResult.MIN_USABLE_CHARACTERS -> add(
                            "Only a fragment of text was recovered ($recovered characters). The " +
                                "document probably has an incomplete text layer; the rest may be " +
                                "an image. A version with selectable text would analyse fully.",
                        )

                        blankPages > 0 -> add(
                            "$blankPages of $pageCount pages had no text. If part of your CV is a " +
                                "picture, retype or re-export it as text.",
                        )
                    }
                }

                CvParseResult(
                    text = text,
                    pageCount = pageCount,
                    warnings = warnings,
                    pages = pageTexts,
                    imagesOnly = imagesOnly,
                )
            }
        }

    /**
     * Recovers one page, preferring the XY-cut reading order but never returning less text
     * than PDFBox's default order. The default pass is only computed when the layout pass
     * looks thin, so the common case stays single-pass and fast.
     */
    private fun recoverPageText(document: PDDocument, pageIndex: Int): String {
        val layout = PdfLayoutExtractor.text(document, pageIndex)
        val layoutCount = layout.count { !it.isWhitespace() }
        // A healthy page is already well above the fragment threshold; no need to second-guess.
        if (layoutCount >= CvParseResult.MIN_USABLE_CHARACTERS) return layout

        val plain = PDFTextStripper().apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }.getText(document)

        return if (plain.count { !it.isWhitespace() } > layoutCount) plain else layout
    }

    /**
     * True when any page holds a raster image, a reasonable proxy for "this is a scan".
     * Deliberately cheap: it reads only the resource dictionaries, never the pixels, so it
     * cannot itself become the slow or fragile part of parsing.
     */
    private fun PDDocument.anyPageHasDrawableContent(): Boolean {
        for (index in 0 until numberOfPages) {
            val page: PDPage = try {
                getPage(index)
            } catch (_: RuntimeException) {
                continue
            }
            val resources = page.resources ?: continue
            for (name in resources.xObjectNames) {
                val isImage = try {
                    resources.isImageXObject(name)
                } catch (_: Exception) {
                    false
                }
                if (isImage) return true
            }
        }
        return false
    }

    private fun ensureResourceLoader() {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }
}
