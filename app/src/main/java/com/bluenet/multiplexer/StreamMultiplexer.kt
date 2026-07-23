package com.bluenet.multiplexer

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StreamMultiplexer(
    inputStream: InputStream,
    outputStream: OutputStream,
    private val onFrameReceived: (Frame) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val dis = DataInputStream(inputStream)
    private val dos = DataOutputStream(outputStream)

    private val isRunning = AtomicBoolean(true)
    private val nextStreamId = AtomicInteger(1)
    private var readerThread: Thread? = null

    fun start() {
        readerThread = Thread({ readLoop() }, "MultiplexerReaderThread").apply { start() }
    }

    fun generateStreamId(): Int {
        return nextStreamId.getAndIncrement()
    }

    fun sendFrame(frame: Frame) {
        if (!isRunning.get()) return
        try {
            val optimizedFrame = frame.compressIfBeneficial()
            optimizedFrame.writeTo(dos)
        } catch (e: IOException) {
            Log.e(TAG, "Error sending frame streamId=${frame.streamId}", e)
            close()
            onError(e)
        }
    }

    private fun readLoop() {
        try {
            while (isRunning.get()) {
                val frame = Frame.readFrom(dis) ?: break
                if (frame.type == FrameType.COMPRESSED_DATA) {
                    val decompressed = frame.decompressPayload()
                    val normalizedFrame = Frame(FrameType.DATA, frame.streamId, decompressed)
                    onFrameReceived(normalizedFrame)
                } else {
                    onFrameReceived(frame)
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error reading from L2CAP stream", e)
                close()
                onError(e)
            }
        }
    }

    fun close() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                dis.close()
            } catch (_: IOException) {}
            try {
                dos.close()
            } catch (_: IOException) {}
            readerThread?.interrupt()
            readerThread = null
            Log.d(TAG, "StreamMultiplexer closed")
        }
    }

    companion object {
        private const val TAG = "StreamMultiplexer"
    }
}
