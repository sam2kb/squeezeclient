/*
 * This file is part of Squeeze Client, an Android client for the LMS music server.
 * Copyright (c) 2026 Danny Baumann
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 *
 */

package de.maniac103.squeezeclient.service.localplayer

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import kotlin.math.min

/** Number of bytes a block start scan may read from a stream before giving up. */
private const val MAX_BLOCK_START_SCAN_BYTES = 4 * 1024 * 1024

/**
 * The knowledge about a media container that is needed to play a stream which starts in the
 * middle of a file. The server streams a file as it is, so such a stream does not contain the
 * file's start; whether that matters depends on the container:
 *
 * - MP3 and ADTS AAC describe every frame on its own, so a stream can start anywhere in them;
 * - FLAC and Ogg keep everything a decoder needs to set itself up (the FLAC metadata blocks, the
 *   Ogg identification and setup headers) at the start of the file only, so a stream that
 *   resumes inside them cannot be decoded at all.
 *
 * [StreamPrefixCachingDataReader] uses this knowledge to remember the start of a stream that
 * begins at the file's start, and to replay it in front of a stream that resumes inside the file.
 */
@UnstableApi
interface StreamContainer {
    /** The number of bytes of a stream start that [isStreamStart] needs. */
    val startProbeSize: Int

    /** Whether [probe] is the start of a stream that begins at the file's start. */
    fun isStreamStart(probe: ByteArray): Boolean

    /** Reads the stream start beginning with [probe]; those bytes are needed to decode the file. */
    fun readStreamStart(reader: DataReader, probe: ByteArray): ByteArray

    /** Reduces a captured stream start to the bytes a decoder needs. */
    fun reduceStreamStart(streamStart: ByteArray): ByteArray = streamStart

    /**
     * Reads the bytes a resumed stream continues with. Such a stream starts at an arbitrary byte
     * offset and therefore usually inside a block, so the remainder of that block has to be
     * skipped before the bytes of the stream start can be replayed in front of the stream.
     */
    fun readResumeStart(reader: DataReader, probe: ByteArray): ByteArray

    /** The duration of the file [streamStart] belongs to, or null when it does not state one. */
    fun streamDurationSeconds(streamStart: ByteArray): Double? = null
}

/**
 * A FLAC stream: the `fLaC` magic followed by metadata blocks, of which the `STREAMINFO` block
 * describes the audio that follows. A stream resumed inside the file has neither.
 */
@UnstableApi
object FlacStreamContainer : StreamContainer {
    /** The `fLaC` magic. */
    override val startProbeSize = 4

    override fun isStreamStart(probe: ByteArray) = probe.size >= startProbeSize &&
        probe[0] == 'f'.code.toByte() &&
        probe[1] == 'L'.code.toByte() &&
        probe[2] == 'a'.code.toByte() &&
        probe[3] == 'C'.code.toByte()

    override fun readStreamStart(reader: DataReader, probe: ByteArray): ByteArray {
        val metadataBlocks = mutableListOf<ByteArray>()
        var isLastBlock = false
        while (!isLastBlock) {
            val blockHeader = reader.readExactly(METADATA_BLOCK_HEADER_SIZE)
            isLastBlock = (blockHeader[0].toInt() and 0x80) != 0
            val blockLength = (blockHeader[1].toUByte().toInt() shl 16) or
                (blockHeader[2].toUByte().toInt() shl 8) or
                (blockHeader[3].toUByte().toInt())
            metadataBlocks += blockHeader + reader.readExactly(blockLength)
        }

        val streamStart = ByteArray(startProbeSize + metadataBlocks.sumOf { it.size })
        probe.copyInto(streamStart)
        var offset = startProbeSize
        metadataBlocks.forEach { block ->
            block.copyInto(streamStart, offset)
            offset += block.size
        }
        return streamStart
    }

    override fun reduceStreamStart(streamStart: ByteArray): ByteArray {
        var offset = startProbeSize
        var previousHeaderOffset = -1
        while (offset + METADATA_BLOCK_HEADER_SIZE <= streamStart.size) {
            val headerOffset = offset
            val isLastBlock = (streamStart[headerOffset].toInt() and 0x80) != 0
            val blockType = streamStart[headerOffset].toInt() and 0x7F
            val blockLength = (streamStart[headerOffset + 1].toUByte().toInt() shl 16) or
                (streamStart[headerOffset + 2].toUByte().toInt() shl 8) or
                (streamStart[headerOffset + 3].toUByte().toInt())
            if (headerOffset + METADATA_BLOCK_HEADER_SIZE + blockLength > streamStart.size) {
                return streamStart // Malformed or incomplete, keep it as it is
            }
            if (isLastBlock) {
                if (blockType != PADDING_BLOCK_TYPE || previousHeaderOffset < 0) {
                    return streamStart
                }
                // Padding only reserves room in the file, drop it and mark the block before as
                // the last one.
                val trimmed = streamStart.copyOf(headerOffset)
                trimmed[previousHeaderOffset] =
                    (trimmed[previousHeaderOffset].toInt() or 0x80).toByte()
                return trimmed
            }
            previousHeaderOffset = headerOffset
            offset = headerOffset + METADATA_BLOCK_HEADER_SIZE + blockLength
        }
        return streamStart
    }

    override fun readResumeStart(reader: DataReader, probe: ByteArray) = findBlockStart(
        reader,
        probe,
        FRAME_HEADER_START_SIZE,
        { bytes -> bytes.startsWithFrameHeaderStart() }
    )

    override fun streamDurationSeconds(streamStart: ByteArray): Double? {
        val streamInfoOffset = startProbeSize + METADATA_BLOCK_HEADER_SIZE
        if (!isStreamStart(streamStart) ||
            streamStart.size < streamInfoOffset + STREAMINFO_SIZE ||
            (streamStart[startProbeSize].toInt() and 0x7F) != STREAMINFO_BLOCK_TYPE
        ) {
            return null
        }
        // STREAMINFO: min/max block size (2 + 2 bytes), min/max frame size (3 + 3 bytes), then 8
        // bytes holding the sample rate (20 bits), the channel count (3), the sample size (5) and
        // the total number of samples (36).
        val sampleRate = (streamStart[streamInfoOffset + 10].toUByte().toInt() shl 12) or
            (streamStart[streamInfoOffset + 11].toUByte().toInt() shl 4) or
            (streamStart[streamInfoOffset + 12].toUByte().toInt() shr 4)
        val totalSamples = ((streamStart[streamInfoOffset + 13].toLong() and 0x0F) shl 32) or
            ((streamStart[streamInfoOffset + 14].toLong() and 0xFF) shl 24) or
            ((streamStart[streamInfoOffset + 15].toLong() and 0xFF) shl 16) or
            ((streamStart[streamInfoOffset + 16].toLong() and 0xFF) shl 8) or
            (streamStart[streamInfoOffset + 17].toLong() and 0xFF)
        if (sampleRate <= 0 || totalSamples <= 0) {
            return null
        }
        return totalSamples.toDouble() / sampleRate
    }

    /** Whether the bytes start a FLAC frame header, i.e. the block that follows the metadata. */
    private fun ByteArray.startsWithFrameHeaderStart(): Boolean {
        if (size < FRAME_HEADER_START_SIZE) {
            return false
        }
        if (this[0] != 0xFF.toByte()) {
            return false
        }
        if ((this[1].toInt() and 0xFE) != 0xF8) {
            return false
        }
        // Third frame header byte: block size code in the high nibble, sample rate code in the
        // low nibble. Both have reserved values which don't occur in real streams, so use them to
        // reject false sync codes inside frame data.
        val blockAndSampleRate = this[2].toUByte().toInt()
        return (blockAndSampleRate and 0xF0) != 0 && (blockAndSampleRate and 0x0F) <= 0x0B
    }

    /** Size of a metadata block header: the last block flag and type, and the block length. */
    private const val METADATA_BLOCK_HEADER_SIZE = 4

    /** FLAC metadata block types this container cares about. */
    private const val STREAMINFO_BLOCK_TYPE = 0
    private const val PADDING_BLOCK_TYPE = 1

    /** Size of the STREAMINFO block's data. */
    private const val STREAMINFO_SIZE = 34

    /** Number of bytes needed to identify a FLAC frame header start. */
    private const val FRAME_HEADER_START_SIZE = 3
}

/**
 * An Ogg stream: pages holding packets, of which the first ones set up the codec (the
 * identification and setup headers of e.g. Vorbis). A stream resumed inside the file has those
 * pages behind it, and the codec cannot be set up from any other page.
 */
@UnstableApi
object OggStreamContainer : StreamContainer {
    /** `OggS`, the version and the header flags. */
    override val startProbeSize = 6

    override fun isStreamStart(probe: ByteArray) =
        probe.isPageStart() && (probe[5].toInt() and BEGINNING_OF_STREAM_FLAG) != 0

    override fun readStreamStart(reader: DataReader, probe: ByteArray): ByteArray {
        val pages = mutableListOf<ByteArray>()
        var totalSize = 0
        var completedPackets = 0
        var headerPackets = UNKNOWN_CODEC_HEADER_PACKETS
        var isFirstPage = true
        while (totalSize < MAX_STREAM_START_SIZE && pages.size < MAX_STREAM_START_PAGES) {
            val pageProbe = if (isFirstPage) probe else reader.readExactly(startProbeSize)
            if (!pageProbe.isPageStart()) {
                throw IOException("Ogg page header not found")
            }
            val page = readPage(reader, pageProbe)
            pages += page.bytes
            totalSize += page.bytes.size
            completedPackets += page.completedPackets
            if (isFirstPage) {
                headerPackets = headerPacketCount(page.bytes)
                isFirstPage = false
            }
            if (completedPackets >= headerPackets) {
                break
            }
        }

        val streamStart = ByteArray(totalSize)
        var offset = 0
        pages.forEach { page ->
            page.copyInto(streamStart, offset)
            offset += page.size
        }
        return streamStart
    }

    override fun readResumeStart(reader: DataReader, probe: ByteArray) = findBlockStart(
        reader,
        probe,
        startProbeSize,
        // A page that continues a packet starts in the middle of data we do not have, so wait
        // for the page that starts the next packet.
        { bytes -> bytes.isPageStart() && (bytes[5].toInt() and CONTINUED_PACKET_FLAG) == 0 }
    )

    private class OggPage(val bytes: ByteArray, val completedPackets: Int)

    /** Reads the page [probe] is the start of, including its segment table and its data. */
    private fun readPage(reader: DataReader, probe: ByteArray): OggPage {
        val header = probe + reader.readExactly(PAGE_HEADER_SIZE - probe.size)
        val segmentCount = header[PAGE_SEGMENT_COUNT_OFFSET].toUByte().toInt()
        val segmentTable = reader.readExactly(segmentCount)
        val data = reader.readExactly(segmentTable.sumOf { it.toUByte().toInt() })
        return OggPage(
            header + segmentTable + data,
            // A segment shorter than the maximum size ends a packet
            segmentTable.count { it.toUByte().toInt() < MAX_SEGMENT_SIZE }
        )
    }

    /**
     * The number of packets at the start of the stream that set up the codec. The first packet
     * names the codec; streams whose codec is unknown are kept to the identification packet, as
     * more would add audio data that must not be replayed in front of a resumed stream.
     */
    private fun headerPacketCount(page: ByteArray): Int {
        val dataOffset = PAGE_HEADER_SIZE + page[PAGE_SEGMENT_COUNT_OFFSET].toUByte().toInt()
        if (page.size < dataOffset + MAX_CODEC_SIGNATURE_SIZE) {
            return UNKNOWN_CODEC_HEADER_PACKETS
        }
        val signature = page.copyOfRange(dataOffset, dataOffset + MAX_CODEC_SIGNATURE_SIZE)
        return when {
            // Vorbis identification packets are prefixed with their packet type
            signature[0] == 1.toByte() &&
                signature.copyOfRange(1, 1 + VORBIS_NAME.size).contentEquals(VORBIS_NAME) -> 3

            signature.copyOfRange(0, OPUS_NAME.size).contentEquals(OPUS_NAME) -> 2

            signature.copyOfRange(0, SPEEX_NAME.size).contentEquals(SPEEX_NAME) -> 2

            else -> UNKNOWN_CODEC_HEADER_PACKETS
        }
    }

    /** Whether the bytes are the start of a page header of a valid Ogg stream. */
    private fun ByteArray.isPageStart(): Boolean {
        if (size < startProbeSize) {
            return false
        }
        if (this[0] != 'O'.code.toByte() ||
            this[1] != 'g'.code.toByte() ||
            this[2] != 'g'.code.toByte() ||
            this[3] != 'S'.code.toByte()
        ) {
            return false
        }
        // Version 0 is the only Ogg version there is, reserved flag bits are never set
        return this[4] == 0.toByte() && (this[5].toInt() and RESERVED_HEADER_FLAGS) == 0
    }

    private val VORBIS_NAME = "vorbis".toByteArray()
    private val OPUS_NAME = "OpusHead".toByteArray()
    private val SPEEX_NAME = "Speex   ".toByteArray()

    /** Number of bytes that name a codec at most. */
    private const val MAX_CODEC_SIGNATURE_SIZE = 8

    /** Page header size without its segment table. */
    private const val PAGE_HEADER_SIZE = 27

    /** Position of the segment count in a page header. */
    private const val PAGE_SEGMENT_COUNT_OFFSET = 26

    /** A segment never is longer than this, a shorter one ends its packet. */
    private const val MAX_SEGMENT_SIZE = 255

    /** At most this many bytes are remembered as a stream start. */
    private const val MAX_STREAM_START_SIZE = 64 * 1024

    /** … and at most this many pages. */
    private const val MAX_STREAM_START_PAGES = 8

    /** Packets to keep when the codec does not name itself usefully. */
    private const val UNKNOWN_CODEC_HEADER_PACKETS = 1

    private const val CONTINUED_PACKET_FLAG = 0x01
    private const val BEGINNING_OF_STREAM_FLAG = 0x02
    private const val RESERVED_HEADER_FLAGS = 0xF8
}

/**
 * Returns [length] bytes read from the stream, failing when the stream ends before that.
 */
internal fun DataReader.readExactly(length: Int): ByteArray {
    val data = ByteArray(length)
    var position = 0
    while (position < length) {
        val bytesRead = read(data, position, data.size - position)
        check(bytesRead != C.RESULT_END_OF_INPUT) {
            "Not enough data could be read: $position < $length"
        }
        position += bytesRead
    }
    return data
}

/**
 * Returns the first bytes of the stream that are a block start of [blockStartSize] bytes for
 * which [isBlockStart] holds, reading further bytes from the stream as needed. [probe] is what
 * was read from the stream before.
 */
private fun findBlockStart(
    reader: DataReader,
    probe: ByteArray,
    blockStartSize: Int,
    isBlockStart: (ByteArray) -> Boolean
): ByteArray {
    // Candidates that the bytes read so far contain completely
    val probeBytesOutsideOfWindow = probe.size - blockStartSize
    if (probeBytesOutsideOfWindow > 0) {
        val matchingIndex = (0 until probeBytesOutsideOfWindow).firstOrNull {
            isBlockStart(probe.copyOfRange(it, it + blockStartSize))
        }
        if (matchingIndex != null) {
            return probe.copyOfRange(matchingIndex, probe.size)
        }
    }

    // Candidates that end with the last byte read so far, and those that follow
    val window = ByteArray(blockStartSize)
    val probeBytesWithinWindow = min(probe.size, blockStartSize)
    probe.copyInto(
        window,
        destinationOffset = blockStartSize - probeBytesWithinWindow,
        startIndex = probe.size - probeBytesWithinWindow
    )

    var scannedBytes = 0
    while (!isBlockStart(window) && scannedBytes++ < MAX_BLOCK_START_SCAN_BYTES) {
        // The window is small, so calling System.arraycopy (which is a native method) will
        // likely perform worse than this reimplementation
        (0 until window.size - 1).forEach { window[it] = window[it + 1] }
        check(reader.read(window, window.size - 1, 1) == 1) {
            "Unexpected end of stream while searching for a block start"
        }
    }
    if (isBlockStart(window)) {
        return window
    }
    throw IOException("No block start found within $scannedBytes bytes")
}
