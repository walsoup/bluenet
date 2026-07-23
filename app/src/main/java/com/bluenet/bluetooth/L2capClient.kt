package com.bluenet.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException

class L2capClient(
    private val device: BluetoothDevice,
    private val psm: Int,
    private val onConnected: (BluetoothSocket) -> Unit,
    private val onError: (String) -> Unit
) {
    private var socket: BluetoothSocket? = null
    private var connectionThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        connectionThread = Thread({
            var connectedSocket: BluetoothSocket? = null

            // Attempt 1: L2CAP CoC (API 29+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && psm > 1) {
                try {
                    Log.d(TAG, "Attempting L2CAP connection to ${device.address} on PSM $psm")
                    val l2capSocket = device.createInsecureL2capChannel(psm)
                    l2capSocket.connect()
                    connectedSocket = l2capSocket
                    Log.d(TAG, "L2CAP socket connected successfully to ${device.address}")
                } catch (e: Exception) {
                    Log.w(TAG, "L2CAP CoC connection failed, attempting RFCOMM fallback", e)
                    connectedSocket = null
                }
            }

            // Attempt 2: RFCOMM Fallback socket
            if (connectedSocket == null) {
                try {
                    Log.d(TAG, "Attempting RFCOMM connection to ${device.address} via UUID ${L2capServer.SERVICE_UUID}")
                    val rfcommSocket = device.createInsecureRfcommSocketToServiceRecord(L2capServer.SERVICE_UUID)
                    rfcommSocket.connect()
                    connectedSocket = rfcommSocket
                    Log.d(TAG, "RFCOMM socket connected successfully to ${device.address}")
                } catch (e: Exception) {
                    Log.e(TAG, "RFCOMM fallback connection also failed", e)
                    onError("Connection failed (L2CAP & RFCOMM): ${e.localizedMessage}")
                    disconnect()
                    return@Thread
                }
            }

            socket = connectedSocket
            onConnected(connectedSocket)
        }, "L2capClientConnectThread").apply { start() }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing client L2CAP socket", e)
        }
        socket = null
        connectionThread?.interrupt()
        connectionThread = null
    }

    companion object {
        private const val TAG = "L2capClient"
    }
}
