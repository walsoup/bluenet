package com.bluenet.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.bluenet.multiplexer.Frame
import com.bluenet.multiplexer.StreamMultiplexer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class L2capChannelPool(
    private val onFrameReceived: (Frame) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val multiplexers = CopyOnWriteArrayList<StreamMultiplexer>()
    private val sendIndex = AtomicInteger(0)
    private val globalStreamId = AtomicInteger(1)

    fun addChannel(socket: BluetoothSocket) {
        lateinit var mp: StreamMultiplexer
        mp = StreamMultiplexer(
            inputStream = socket.inputStream,
            outputStream = socket.outputStream,
            onFrameReceived = onFrameReceived,
            onError = { ex ->
                Log.e(TAG, "Channel error, removing socket", ex)
                multiplexers.remove(mp)
                if (multiplexers.isEmpty()) {
                    onError(ex)
                }
            }
        )
        multiplexers.add(mp)
        mp.start()
        Log.d(TAG, "L2CAP Channel pool expanded. Active channels: ${multiplexers.size}")
    }

    fun generateStreamId(): Int {
        return globalStreamId.getAndIncrement()
    }

    fun sendFrame(frame: Frame) {
        val poolSize = multiplexers.size
        if (poolSize == 0) return

        val index = Math.abs(sendIndex.getAndIncrement()) % poolSize
        try {
            multiplexers[index].sendFrame(frame)
        } catch (e: Exception) {
            // Fallback to first available channel
            multiplexers.firstOrNull()?.sendFrame(frame)
        }
    }

    fun close() {
        multiplexers.forEach { it.close() }
        multiplexers.clear()
        Log.d(TAG, "L2capChannelPool closed")
    }

    val activeChannelCount: Int
        get() = multiplexers.size

    companion object {
        private const val TAG = "L2capChannelPool"
    }
}
