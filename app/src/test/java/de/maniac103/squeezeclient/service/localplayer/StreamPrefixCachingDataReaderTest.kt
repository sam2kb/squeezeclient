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
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests reading streams that start at the beginning of a file and streams that resume in the
 * middle of one, as it happens when the buffered part of a stream ran dry and the loader
 * re-requests the stream (e.g. after resuming a paused sync group).
 */
class StreamPrefixCachingDataReaderTest {
    private class ByteArrayDataReader(private val data: ByteArray, position: Int = 0) : DataReader {
        private var readPosition = position

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (readPosition >= data.size) {
                return C.RESULT_END_OF_INPUT
            }
            val bytesToCopy = min(length, data.size - readPosition)
            data.copyInto(buffer, offset, readPosition, readPosition + bytesToCopy)
            readPosition += bytesToCopy
            return bytesToCopy
        }
    }

    private fun readAll(reader: DataReader): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val bytesRead = reader.read(buffer, 0, buffer.size)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                return result.toByteArray()
            }
            result.write(buffer, 0, bytesRead)
        }
    }

    private fun flacReader(stream: ByteArray, cache: StreamStartCache, position: Int = 0) =
        StreamPrefixCachingDataReader(
            ByteArrayDataReader(stream, position),
            FlacStreamContainer,
            cache
        )

    private fun oggReader(bytes: ByteArray, cache: StreamStartCache, position: Int = 0) =
        StreamPrefixCachingDataReader(
            ByteArrayDataReader(bytes, position),
            OggStreamContainer,
            cache
        )

    private fun frame(sampleRateAndBlockSize: Int, payloadByte: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xF8.toByte(), sampleRateAndBlockSize.toByte()) +
            ByteArray(TEST_FRAME_SIZE - 3) { payloadByte.toByte() }

    private fun isFrameSync(data: ByteArray, index: Int): Boolean {
        if (index + 3 > data.size) {
            return false
        }
        if (data[index] != 0xFF.toByte()) {
            return false
        }
        if ((data[index + 1].toInt() and 0xFE) != 0xF8) {
            return false
        }
        val third = data[index + 2].toInt() and 0xFF
        return (third and 0xF0) != 0 && (third and 0x0F) <= 0x0B
    }

    /** Builds a minimal FLAC stream: magic, one STREAMINFO block and four frames. */
    private fun flacStream(): ByteArray {
        val streamInfo = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) +
            ByteArray(STREAMINFO_BLOCK_SIZE) { 0x42 }
        val frames = frame(0x36, 0x11) +
            frame(0x36, 0x22) +
            frame(0x36, 0x33) +
            frame(0x36, 0x44)
        return MAGIC + streamInfo + frames
    }

    /** Builds a minimal FLAC stream whose metadata ends with a padding block. */
    private fun flacStreamWithPadding(): ByteArray {
        val streamInfo = byteArrayOf(0x00, 0x00, 0x00, 0x22) +
            ByteArray(STREAMINFO_BLOCK_SIZE) { 0x42 }
        val padding = byteArrayOf(0x81.toByte(), 0x00, 0x00, 0x10) + ByteArray(16)
        return MAGIC + streamInfo + padding + frame(0x36, 0x11)
    }

    /** Builds an Ogg page holding one segment per packet. */
    private fun oggPage(
        headerType: Int,
        granulePosition: Long,
        sequenceNumber: Int,
        vararg packets: ByteArray
    ): ByteArray {
        val page = ByteArray(OGG_PAGE_HEADER_SIZE + packets.size + packets.sumOf { it.size })
        "OggS".toByteArray().copyInto(page)
        page[4] = 0 // version
        page[5] = headerType.toByte()
        repeat(8) { page[6 + it] = (granulePosition shr (8 * (7 - it))).toByte() }
        repeat(4) { page[14 + it] = (STREAM_SERIAL shr (8 * (3 - it))).toByte() }
        repeat(4) { page[18 + it] = (sequenceNumber shr (8 * (3 - it))).toByte() }
        page[OGG_PAGE_SEGMENT_COUNT_OFFSET] = packets.size.toByte()
        packets.forEachIndexed { index, packet ->
            page[OGG_PAGE_HEADER_SIZE + index] = packet.size.toByte()
        }
        var offset = OGG_PAGE_HEADER_SIZE + packets.size
        packets.forEach { packet ->
            packet.copyInto(page, offset)
            offset += packet.size
        }
        return page
    }

    private fun vorbisPacket(packetType: Int, payloadSize: Int): ByteArray =
        byteArrayOf(packetType.toByte()) + "vorbis".toByteArray() + ByteArray(payloadSize)

    private fun audioPage(headerType: Int, sequenceNumber: Int, payloadByte: Int) = oggPage(
        headerType,
        granulePosition = sequenceNumber * 1000L,
        sequenceNumber = sequenceNumber,
        ByteArray(AUDIO_PAGE_SIZE) { payloadByte.toByte() }
    )

    private class OggTestStream(val pages: List<ByteArray>) {
        val bytes = pages.fold(ByteArray(0)) { stream, page -> stream + page }

        /** The offset the page at [index] starts at. */
        fun offsetOfPage(index: Int) = pages.take(index).sumOf { it.size }

        fun fromPage(index: Int) = bytes.copyOfRange(offsetOfPage(index), bytes.size)
    }

    /**
     * Builds a minimal Ogg Vorbis stream: the three codec setup pages, then three audio pages;
     * the second audio page optionally continues a packet.
     */
    private fun oggStream(secondAudioPageHeaderType: Int = 0) = OggTestStream(
        listOf(
            oggPage(BEGINNING_OF_STREAM, 0, 0, vorbisPacket(1, 23)),
            oggPage(0, 0, 1, vorbisPacket(3, 16)),
            oggPage(0, 0, 2, vorbisPacket(5, 20)),
            audioPage(0, 3, 0x11),
            audioPage(secondAudioPageHeaderType, 4, 0x22),
            audioPage(0, 5, 0x33)
        )
    )

    @Test
    fun readingFullFlacStreamCachesMetadata() {
        val stream = flacStream()
        val cache = StreamStartCache()

        assertArrayEquals(stream, readAll(flacReader(stream, cache)))
        assertEquals(MAGIC.size + 4 + STREAMINFO_BLOCK_SIZE, cache.streamStartBytes!!.size)
    }

    @Test
    fun readingFullFlacStreamDropsPaddingBlock() {
        val stream = flacStreamWithPadding()
        val frames = stream.copyOfRange(PADDED_STREAM_FRAME_OFFSET, stream.size)
        val cache = StreamStartCache()

        val read = readAll(flacReader(stream, cache))

        // The padding block only reserves room in the file, so it is dropped; the block before
        // it has to be marked as the last one instead.
        val expectedStart = stream.copyOfRange(0, MAGIC.size + 4 + STREAMINFO_BLOCK_SIZE)
        expectedStart[4] = (expectedStart[4].toInt() or 0x80).toByte()
        assertArrayEquals(expectedStart, cache.streamStartBytes!!)
        assertArrayEquals(expectedStart + frames, read)
    }

    @Test
    fun resumedFlacStreamContinuesAtNextFrameSync() {
        val stream = flacStream()
        val cache = StreamStartCache()
        readAll(flacReader(stream, cache))
        val metadataSize = cache.streamStartBytes!!.size

        // Resume in the middle of the payload of the second frame (magic + block header +
        // STREAMINFO + one frame + 5 bytes of the second frame's payload).
        val resumeOffset = MAGIC.size + 4 + STREAMINFO_BLOCK_SIZE + TEST_FRAME_SIZE + 5
        val resumed = readAll(flacReader(stream, cache, resumeOffset))

        // The cached metadata is replayed, but the data after it must not contain the mid-frame
        // bytes that followed the resume point; it has to start at the next frame sync.
        assertArrayEquals(
            stream.copyOfRange(0, metadataSize),
            resumed.copyOfRange(0, metadataSize)
        )
        val firstSync = (metadataSize..resumed.size - 3).firstOrNull { isFrameSync(resumed, it) }
        assertEquals(
            "Data after the replayed metadata must start at a frame sync",
            metadataSize,
            firstSync ?: -1
        )

        // Everything from the sync onwards must be unchanged stream data. The skipped part of
        // the frame containing the resume position is (intentionally) dropped, so the emitted
        // stream continues at the frame containing the sync.
        assertArrayEquals(
            stream.copyOfRange(resumeOffset + 8, stream.size),
            resumed.copyOfRange(metadataSize, resumed.size)
        )
    }

    @Test
    fun resumedStreamWithoutStoredStartFails() {
        val stream = flacStream()
        val reader = flacReader(stream, StreamStartCache(), stream.size - 10)

        assertThrows(IOException::class.java) { readAll(reader) }
    }

    @Test
    fun readingFullOggStreamCachesCodecSetupPages() {
        val stream = oggStream()
        val cache = StreamStartCache()

        assertArrayEquals(stream.bytes, readAll(oggReader(stream.bytes, cache)))
        // A Vorbis stream sets up its codec in its first three pages, so a stream that resumes
        // later in the file needs exactly those.
        assertArrayEquals(
            stream.bytes.copyOfRange(0, stream.offsetOfPage(3)),
            cache.streamStartBytes!!
        )
    }

    @Test
    fun resumedOggStreamContinuesAtNextPacketPage() {
        val stream = oggStream()
        val cache = StreamStartCache()
        readAll(oggReader(stream.bytes, cache))
        val setupSize = cache.streamStartBytes!!.size

        // Resume in the middle of the first audio page
        val resumed = readAll(oggReader(stream.bytes, cache, stream.offsetOfPage(3) + 10))

        assertArrayEquals(
            stream.bytes.copyOfRange(0, setupSize),
            resumed.copyOfRange(0, setupSize)
        )
        // The page the resume position is in is dropped, as it starts in the middle of a packet
        assertArrayEquals(
            stream.fromPage(4),
            resumed.copyOfRange(setupSize, resumed.size)
        )
    }

    @Test
    fun resumedOggStreamSkipsPagesContinuingAPacket() {
        val stream = oggStream(secondAudioPageHeaderType = CONTINUED_PACKET)
        val cache = StreamStartCache()
        readAll(oggReader(stream.bytes, cache))
        val setupSize = cache.streamStartBytes!!.size

        val resumed = readAll(oggReader(stream.bytes, cache, stream.offsetOfPage(3) + 10))

        // The page after the resumed one continues a packet, so the stream continues with the
        // page after that one.
        assertArrayEquals(
            stream.fromPage(5),
            resumed.copyOfRange(setupSize, resumed.size)
        )
    }

    @Test
    fun oggStreamOfUnknownCodecKeepsItsFirstPage() {
        val stream = OggTestStream(
            listOf(
                oggPage(BEGINNING_OF_STREAM, 0, 0, ByteArray(20) { 0x55 }),
                audioPage(0, 1, 0x11)
            )
        )
        val cache = StreamStartCache()

        readAll(oggReader(stream.bytes, cache))

        // Without knowing the codec, only the page holding its identification is kept
        assertArrayEquals(
            stream.bytes.copyOfRange(0, stream.offsetOfPage(1)),
            cache.streamStartBytes!!
        )
    }

    companion object {
        private val MAGIC = "fLaC".toByteArray()
        private const val STREAMINFO_BLOCK_SIZE = 34
        private const val TEST_FRAME_SIZE = 13
        private const val PADDED_STREAM_FRAME_OFFSET = 4 + 4 + STREAMINFO_BLOCK_SIZE + 4 + 16

        private const val OGG_PAGE_HEADER_SIZE = 27
        private const val OGG_PAGE_SEGMENT_COUNT_OFFSET = 26
        private const val AUDIO_PAGE_SIZE = 50
        private const val STREAM_SERIAL = 0x12345678
        private const val BEGINNING_OF_STREAM = 0x02
        private const val CONTINUED_PACKET = 0x01
    }
}
