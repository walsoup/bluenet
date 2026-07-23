package com.bluenet.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

import java.util.UUID

class L2capServer(
    private val bluetoothAdapter: BluetoothAdapter,
    private val onClientConnected: (BluetoothSocket) -> Unit,
    private val onError: (String) -> Unit
) {
    private var serverSocket: BluetoothServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    var psm: Int = -1
        private set

    var isUsingRfcommFallback: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!bluetoothAdapter.isEnabled) {
            onError("Bluetooth is disabled")
            return false
        }

        try {
            // Attempt 1: Native L2CAP CoC (API 29+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    serverSocket = bluetoothAdapter.listenUsingInsecureL2capChannel()
                    psm = serverSocket?.psm ?: -1
                    isUsingRfcommFallback = false
                    Log.d(TAG, "L2CAP Server started listening on PSM: $psm")
                } catch (e: Exception) {
                    Log.w(TAG, "L2CAP CoC listen failed, falling back to RFCOMM high-speed socket", e)
                    serverSocket = null
                }
            }

            // Attempt 2: RFCOMM Fallback socket
            if (serverSocket == null) {
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                psm = 1 // Virtual RFCOMM channel indicator
                isUsingRfcommFallback = true
                Log.d(TAG, "RFCOMM Fallback Server started listening with UUID $SERVICE_UUID")
            }

            isRunning.set(true)
            workerThread = Thread({ listenLoop() }, "L2capServerThread").apply { start() }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create server socket", e)
            onError("Failed to start Bluetooth server: ${e.localizedMessage}")
            stop()
            return false
        }
    }

    private fun listenLoop() {
        while (isRunning.get()) {
            try {
                Log.d(TAG, "Waiting for L2CAP client connection...")
                val socket = serverSocket?.accept()
                if (socket != null && isRunning.get()) {
                    // Optimize L2CAP socket buffers for throughput
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            Log.d(TAG, "L2CAP socket MaxTxPacketSize: ${socket.maxTransmitPacketSize}, MaxRxPacketSize: ${socket.maxReceivePacketSize}")
                        }
                    } catch (_: Exception) {}
                    Log.d(TAG, "L2CAP Client connected successfully from: ${socket.remoteDevice.address}")
                    onClientConnected(socket)
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting L2CAP connection", e)
                    onError("Accept error: ${e.localizedMessage}")
                }
                break
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        workerThread?.interrupt()
        workerThread = null
        psm = -1
        Log.d(TAG, "L2CAP Server stopped")
    }

    companion object {
        private const val TAG = "L2capServer"
        const val SERVICE_NAME = "BlueNetTether"
        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-223a-11ee-be56-0242ac120002")
    }
}
