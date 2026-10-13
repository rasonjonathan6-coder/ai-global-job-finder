package com.aiglobal.jobfinder.data.cv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Minimal reader for the Compound File Binary (OLE2) container used by `.doc` files.
 *
 * Only what text extraction needs is implemented: the FAT, the mini FAT, the directory
 * and stream reads. Everything is bounds-checked against the real file length so a
 * malformed container cannot make us allocate or seek outside the buffer.
 */
internal class CompoundFile(private val data: ByteArray) {

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val fatSectors: Int
    private val firstDirectorySector: Int
    private val miniStreamCutoff: Int
    private val firstMiniFatSector: Int
    private val miniFatSectorCount: Int
    private val difat: IntArray

    private val fat: IntArray
    private val miniFat: IntArray
    val entries: List<DirectoryEntry>

    init {
        if (data.size < HEADER_SIZE) throw CvParseException.Corrupted()
        for (i in SIGNATURE.indices) {
            if (data[i] != SIGNATURE[i]) throw CvParseException.Corrupted()
        }

        sectorSize = 1 shl readUShort(0x1E)
        miniSectorSize = 1 shl readUShort(0x20)
        if (sectorSize < 128 || sectorSize > 65536) throw CvParseException.Corrupted()
        if (miniSectorSize < 16 || miniSectorSize > sectorSize) throw CvParseException.Corrupted()

        fatSectors = readInt(0x2C)
        firstDirectorySector = readInt(0x30)
        miniStreamCutoff = readInt(0x38).let { if (it <= 0) 4096 else it }
        firstMiniFatSector = readInt(0x3C)
        miniFatSectorCount = readInt(0x40)

        val difatSectorCount = readInt(0x48)
        val difatSectors = IntArray(difatSectorCount.coerceIn(0, MAX_DIFAT_SECTORS))
        val firstDifatSector = readInt(0x44)
        var sector = firstDifatSector
        var index = 0
        while (sector in 0 until sectorCount() && index < difatSectors.size) {
            val base = sectorOffset(sector)
            for (i in 0 until sectorSize / 4 - 1) {
                if (index >= difatSectors.size) break
                difatSectors[index++] = readInt(base + i * 4)
            }
            sector = readInt(base + sectorSize - 4)
        }

        difat = IntArray(109 + difatSectors.size)
        for (i in 0 until 109) difat[i] = readInt(0x4C + i * 4)
        difatSectors.copyInto(difat, 109)

        fat = readSectorChain(fatSectors.coerceAtMost(MAX_FAT_SECTORS), difat)
        miniFat = readSectorChain(miniFatSectorCount.coerceAtMost(MAX_FAT_SECTORS), buildMiniFatStart())
        entries = readDirectory()
    }

    private fun buildMiniFatStart(): IntArray = IntArray(1) { firstMiniFatSector }

    private fun sectorCount(): Int = (data.size / sectorSize) - 1

    private fun sectorOffset(sector: Int): Int {
        val offset = (sector + 1) * sectorSize
        if (sector < 0 || offset + sectorSize > data.size) throw CvParseException.Corrupted()
        return offset
    }

    /** Follows a FAT chain of [count] sectors, seeded from [startSectors]. */
    private fun readSectorChain(count: Int, startSectors: IntArray): IntArray {
        if (count <= 0) return IntArray(0)
        val out = IntArray(count)
        var written = 0
        for (seed in startSectors) {
            if (written >= count) break
            var sector = seed
            var guard = 0
            while (sector in 0 until sectorCount() && written < count && guard++ <= sectorCount()) {
                out[written++] = sector
                sector = if (sector < fatSize()) readInt(sectorOffsetOfFatEntry(sector)) else FREE_SECTOR
            }
        }
        return if (written == count) out else out.copyOf(written)
    }

    // The FAT itself is read before `fat` exists; these two helpers read directly from
    // the DIFAT-provided sector list stored in `difat`.
    private fun fatSize(): Int = difat.size
    private fun sectorOffsetOfFatEntry(index: Int): Int {
        val perSector = sectorSize / 4
        val fatSector = index / perSector
        val entryIndex = index % perSector
        if (fatSector >= difat.size) throw CvParseException.Corrupted()
        val fatSectorId = difat[fatSector]
        return sectorOffset(fatSectorId) + entryIndex * 4
    }

    private fun readDirectory(): List<DirectoryEntry> {
        val out = mutableListOf<DirectoryEntry>()
        var sector = firstDirectorySector
        var guard = 0
        while (sector in 0 until sectorCount() && guard++ <= sectorCount()) {
            val base = sectorOffset(sector)
            for (i in 0 until sectorSize / 128) {
                val offset = base + i * 128
                val nameLength = readUShort(offset + 64)
                val type = data[offset + 66].toInt()
                if (type == 0 || nameLength == 0 || nameLength > 64) continue
                val name = readUtf16(offset, (nameLength / 2) - 1)
                val startSector = readInt(offset + 116)
                val size = readInt(offset + 120).toLong() and 0xFFFFFFFFL
                out += DirectoryEntry(name, type, startSector, size)
            }
            sector = if (sector < fat.size) fat[sector] else FREE_SECTOR
        }
        return out
    }

    fun stream(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.name == name && it.type == TYPE_STREAM } ?: return null
        return readStream(entry.startSector, entry.size)
    }

    /** Reads a stream, transparently using the mini stream for small streams. */
    private fun readStream(startSector: Int, size: Long): ByteArray {
        if (size <= 0) return ByteArray(0)
        val length = size.coerceAtMost(MAX_STREAM_BYTES).toInt()
        return if (size < miniStreamCutoff) readMiniStream(startSector, length) else readRegularStream(startSector, length)
    }

    private fun readRegularStream(startSector: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var sector = startSector
        var written = 0
        var guard = 0
        while (written < length && sector in 0 until sectorCount() && guard++ <= sectorCount()) {
            val base = sectorOffset(sector)
            val take = minOf(sectorSize, length - written)
            System.arraycopy(data, base, out, written, take)
            written += take
            sector = if (sector < fat.size) fat[sector] else FREE_SECTOR
        }
        return if (written == length) out else out.copyOf(written)
    }

    private fun readMiniStream(startSector: Int, length: Int): ByteArray {
        val root = entries.firstOrNull { it.type == TYPE_ROOT } ?: return ByteArray(0)
        val miniStream = readRegularStream(root.startSector, root.size.coerceAtMost(MAX_STREAM_BYTES).toInt())
        val out = ByteArray(length)
        var sector = startSector
        var written = 0
        var guard = 0
        while (written < length && sector in 0 until miniFat.size && guard++ <= miniFat.size) {
            val offset = sector * miniSectorSize
            if (offset + miniSectorSize > miniStream.size) break
            val take = minOf(miniSectorSize, length - written)
            System.arraycopy(miniStream, offset, out, written, take)
            written += take
            sector = miniFat[sector]
        }
        return if (written == length) out else out.copyOf(written)
    }

    private fun readInt(offset: Int): Int {
        if (offset + 4 > data.size) throw CvParseException.Corrupted()
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readUShort(offset: Int): Int {
        if (offset + 2 > data.size) throw CvParseException.Corrupted()
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUtf16(offset: Int, charCount: Int): String {
        if (charCount <= 0) return ""
        val bytes = ByteArray(charCount * 2)
        val available = minOf(bytes.size, data.size - offset)
        if (available <= 0) return ""
        System.arraycopy(data, offset, bytes, 0, available)
        return String(bytes, Charsets.UTF_16LE)
    }

    data class DirectoryEntry(
        val name: String,
        val type: Int,
        val startSector: Int,
        val size: Long,
    )

    companion object {
        private val SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        private const val HEADER_SIZE = 512
        private const val TYPE_STREAM = 2
        private const val TYPE_ROOT = 5
        private const val FREE_SECTOR = -2
        private const val MAX_DIFAT_SECTORS = 4096
        private const val MAX_FAT_SECTORS = 1 shl 20
        private const val MAX_STREAM_BYTES = 32L * 1024 * 1024

        /** Reads and validates the container. Returns null when [input] is not a CFB file. */
        fun open(input: InputStream): CompoundFile? {
            val bytes = ByteArrayOutputStream().also { buffer ->
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = try {
                        input.read(chunk)
                    } catch (e: IOException) {
                        throw CvParseException.Unreadable(e)
                    }
                    if (read < 0) break
                    total += read
                    if (total > MAX_STREAM_BYTES) throw CvParseException.Corrupted()
                    buffer.write(chunk, 0, read)
                }
            }.toByteArray()

            if (bytes.size < HEADER_SIZE) return null
            for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return null
            return CompoundFile(bytes)
        }
    }
}
