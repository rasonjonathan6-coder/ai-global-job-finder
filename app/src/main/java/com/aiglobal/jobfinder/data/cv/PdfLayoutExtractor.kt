package com.aiglobal.jobfinder.data.cv

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException

/**
 * Recovers text from a PDF page in *reading order*, not PDFBox's default top-to-bottom
 * order.
 *
 * The default [PDFTextStripper] sorts glyphs by vertical position only and then writes each
 * row, so on a two-column or sidebar layout it interleaves the columns: the CV header line
 * "Toamasina, Madagascar" comes out glued to a right-hand-column "Experience", and the
 * section headings that every extractor relies on are destroyed. That is what made a real CV
 * look like it had no name, no education and two skills — the text was there, but the
 * structure was gone.
 *
 * This extractor collects the raw glyph positions that PDFBox already computes and runs an
 * XY-cut over them: it recursively splits the page at the widest empty vertical or
 * horizontal seam until no seam is wide enough, then emits the glyphs of each leaf block
 * left-to-right and top-to-bottom. A full-width header therefore stays above the two columns
 * it introduces, and each column is read on its own. The glyphs themselves are never altered,
 * so accents and encoding survive exactly as before.
 */
internal object PdfLayoutExtractor {

    /** A glyph's empty-seam tests, in points. */
    private const val MIN_COLUMN_GAP = 8f

    /** Fallback row gap when no glyph size is available. */
    private const val MIN_ROW_GAP_FLOOR = 1.5f

    /**
     * Extracts [pageIndex] (0-based) as ordered text. Returns an empty string when the page
     * carries no glyphs (a scanned image or a genuinely blank page), which the caller already
     * knows how to report honestly.
     */
    fun extract(document: PDDocument, pageIndex: Int): String {
        val glyphs = toGlyphs(collectGlyphs(document, pageIndex))
        if (glyphs.isEmpty()) return ""
        return render(orderedLines(glyphs))
    }

    // ------------------------------------------------------------------ glyph capture

    /**
     * Every glyph PDFBox emitted for the page, including the space glyphs a text operator
     * draws explicitly. Overriding [PDFTextStripper.processTextPosition] without calling
     * `super` keeps PDFBox from reordering or dropping anything before we see it.
     */
    private fun collectGlyphs(document: PDDocument, pageIndex: Int): List<TextPosition> {
        val collected = ArrayList<TextPosition>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                if (text.unicode.isNotEmpty()) collected += text
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(document)
        return collected
    }

    // ------------------------------------------------------------------ line grouping

    private data class Glyph(
        val unicode: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val size: Float,
    ) {
        val right: Float get() = x + width
    }

    private data class Line(val glyphs: List<Glyph>, val baseline: Float, val top: Float, val bottom: Float) {
        val minX: Float get() = glyphs.first().x
        val maxX: Float get() = glyphs.last().right
    }

    private fun toGlyphs(positions: List<TextPosition>): List<Glyph> = positions.map {
        Glyph(it.unicode, it.xDirAdj, it.yDirAdj, it.widthDirAdj, it.fontSizeInPt)
    }

    /**
     * Groups glyphs into visual lines. Two glyphs belong to the same line when their baselines
     * are within half a line height — PDFBox splits a single visual line across many text
     * fragments, and they must be rejoined before anything reads them.
     */
    private fun groupIntoLines(glyphs: List<Glyph>): List<Line> {
        val sorted = glyphs.sortedWith(compareBy({ it.y }, { it.x }))
        val lines = ArrayList<MutableList<Glyph>>()
        var currentY = Float.NaN
        var tolerance = 2f
        for (glyph in sorted) {
            if (lines.isEmpty() || kotlin.math.abs(glyph.y - currentY) > tolerance) {
                lines += mutableListOf(glyph)
                currentY = glyph.y
                tolerance = (glyph.size * 0.5f).coerceAtLeast(2f)
            } else {
                lines.last() += glyph
                // Track the running mean so a slightly sloping baseline still stays one line.
                val count = lines.last().size
                currentY = currentY + (glyph.y - currentY) / count
            }
        }
        return lines.map { group ->
            val ordered = group.sortedBy { it.x }
            val baseline = ordered.map { it.y }.average().toFloat()
            val height = ordered.maxOf { it.size }.coerceAtLeast(1f)
            Line(ordered, baseline, baseline - height, baseline + height)
        }
    }

    // ------------------------------------------------------------------ XY-cut

    /**
     * Orders the glyphs into reading order via a recursive XY-cut. At each step the widest
     * qualifying vertical seam (between columns) and horizontal seam (between blocks) are
     * compared and the more emphatic is cut first; a leaf block is emitted as its lines.
     */
    private fun orderedLines(glyphs: List<Glyph>): List<Line> {
        if (glyphs.isEmpty()) return emptyList()
        val lines = groupIntoLines(glyphs)
        if (lines.size <= 1) return lines

        val lineHeight = median(lines.map { it.bottom - it.top }).coerceAtLeast(4f)
        val minRowGap = (lineHeight * MIN_ROW_GAP_FLOOR).coerceAtLeast(4f)

        val xGap = widestSeam(glyphs.map { it.x to it.right })
        val yGap = widestSeam(lines.map { it.top to it.bottom })

        val cutX = xGap != null && xGap.second >= MIN_COLUMN_GAP
        val cutY = yGap != null && yGap.second >= minRowGap

        return when {
            cutX && (!cutY || xGap!!.second >= yGap!!.second) -> {
                val split = xGap!!.first
                val (left, right) = glyphs.partition { it.x + it.width / 2f <= split }
                orderedLines(left) + orderedLines(right)
            }

            cutY -> {
                val split = yGap!!.first
                val (top, bottom) = glyphs.partition { (it.y) <= split }
                orderedLines(top) + orderedLines(bottom)
            }

            else -> lines
        }
    }

    /**
     * The widest empty strip along one axis, as `(cutPosition, width)`. Inputs are the
     * occupied intervals on that axis; `null` when everything overlaps.
     */
    private fun widestSeam(intervals: List<Pair<Float, Float>>): Pair<Float, Float>? {
        if (intervals.size < 2) return null
        val sorted = intervals.sortedBy { it.first }
        var best: Pair<Float, Float>? = null
        var reach = sorted.first().second
        for ((start, end) in sorted.drop(1)) {
            val gap = start - reach
            if (gap > (best?.second ?: 0f)) {
                best = (reach + start) / 2f to gap
            }
            if (end > reach) reach = end
        }
        return best
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Renders ordered lines to plain text. Glyphs drawn without an explicit space get one
     * inferred from the horizontal gap, so "MaintenanceTechnician" from separate text objects
     * becomes "Maintenance Technician".
     */
    private fun render(lines: List<Line>): String = lines.joinToString("\n") { line ->
        val builder = StringBuilder()
        var previous: Glyph? = null
        for (glyph in line.glyphs) {
            val prev = previous
            if (prev != null && glyph.unicode != " " && prev.unicode != " ") {
                val gap = glyph.x - prev.right
                if (gap > glyph.size * 0.25f) builder.append(' ')
            }
            builder.append(glyph.unicode)
            previous = glyph
        }
        builder.toString().trim()
    }

    /** Convenience for `withContext(Dispatchers.IO)` callers that only have a document. */
    @Throws(IOException::class)
    fun text(document: PDDocument, pageIndex: Int): String = extract(document, pageIndex)
}
