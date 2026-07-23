package com.bluenet.host

import android.util.Log
import com.bluenet.multiplexer.Frame
import com.bluenet.multiplexer.FrameType
import com.bluenet.multiplexer.StreamMultiplexer
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class HostProxyManager(
    private val multiplexer: StreamMultiplexer
) {
    private val tcpSockets = ConcurrentHashMap<Int, Socket>()
    private val udpSockets = ConcurrentHashMap<Int, DatagramSocket>()
    private val executor = Executors.newCachedThreadPool()

    fun handleFrame(frame: Frame) {
        when (frame.type) {
            FrameType.CONNECT_TCP -> handleConnectTcp(frame)
            FrameType.CONNECT_UDP -> handleConnectUdp(frame)
            FrameType.DATA, FrameType.COMPRESSED_DATA -> handleData(frame)
            FrameType.CLOSE -> handleClose(frame)
            FrameType.KEEPALIVE -> {
                // Heartbeat response
                multiplexer.sendFrame(Frame(FrameType.KEEPALIVE, frame.streamId))
            }
        }
    }

    private fun handleConnectTcp(frame: Frame) {
        val streamId = frame.streamId
        if (frame.payload.size < 6) return

        val buffer = ByteBuffer.wrap(frame.payload)
        val ipBytes = ByteArray(4)
        buffer.get(ipBytes)
        val targetIp = InetAddress.getByAddress(ipBytes)
        val targetPort = buffer.short.toInt() and 0xFFFF

        executor.execute {
            try {
                Log.d(TAG, "Host TCP connect target $targetIp:$targetPort for stream $streamId")
                val socket = Socket(targetIp, targetPort).apply {
                    receiveBufferSize = 65536
                    sendBufferSize = 65536
                    tcpNoDelay = true
                }
                tcpSockets[streamId] = socket

                // Start forward thread: Socket -> L2CAP
                val inputStream = socket.getInputStream()
                val readBuf = ByteArray(16384)
                var bytesRead: Int
                while (inputStream.read(readBuf).also { bytesRead = it } != -1) {
                    val data = readBuf.copyOf(bytesRead)
                    multiplexer.sendFrame(Frame(FrameType.DATA, streamId, data))
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP relay failed for stream $streamId ($targetIp:$targetPort)", e)
            } finally {
                closeTcpStream(streamId)
            }
        }
    }

    private fun handleConnectUdp(frame: Frame) {
        val streamId = frame.streamId
        if (frame.payload.size < 6) return

        val buffer = ByteBuffer.wrap(frame.payload)
        val ipBytes = ByteArray(4)
        buffer.get(ipBytes)
        val targetIp = InetAddress.getByAddress(ipBytes)
        val targetPort = buffer.short.toInt() and 0xFFFF

        executor.execute {
            try {
                Log.d(TAG, "Host UDP connect target $targetIp:$targetPort for stream $streamId")
                val udpSocket = DatagramSocket()
                udpSockets[streamId] = udpSocket

                // Receiving UDP response from remote server -> L2CAP
                val recvBuf = ByteArray(4096)
                while (!udpSocket.isClosed) {
                    val packet = DatagramPacket(recvBuf, recvBuf.size)
                    udpSocket.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    multiplexer.sendFrame(Frame(FrameType.DATA, streamId, data))
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP relay error stream $streamId", e)
            } finally {
                closeUdpStream(streamId)
            }
        }
    }

    private fun handleData(frame: Frame) {
        val streamId = frame.streamId

        // Check if TCP
        val tcpSocket = tcpSockets[streamId]
        if (tcpSocket != null && !tcpSocket.isClosed) {
            executor.execute {
                try {
                    tcpSocket.getOutputStream().write(frame.payload)
                    tcpSocket.getOutputStream().flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to TCP target stream $streamId", e)
                    closeTcpStream(streamId)
                }
            }
            return
        }

        // Check if UDP
        val udpSocket = udpSockets[streamId]
        if (udpSocket != null && !udpSocket.isClosed) {
            executor.execute {
                try {
                    val packet = DatagramPacket(frame.payload, frame.payload.size)
                    udpSocket.send(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending UDP packet stream $streamId", e)
                    closeUdpStream(streamId)
                }
            }
        }
    }

    private fun handleClose(frame: Frame) {
        closeTcpStream(frame.streamId)
        closeUdpStream(frame.streamId)
    }

    private fun closeTcpStream(streamId: Int) {
        val socket = tcpSockets.remove(streamId)
        if (socket != null && !socket.isClosed) {
            try { socket.close() } catch (_: Exception) {}
            multiplexer.sendFrame(Frame(FrameType.CLOSE, streamId))
        }
    }

    private fun closeUdpStream(streamId: Int) {
        val socket = udpSockets.remove(streamId)
        if (socket != null && !socket.isClosed) {
            try { socket.close() } catch (_: Exception) {}
            multiplexer.sendFrame(Frame(FrameType.CLOSE, streamId))
        }
    }

    fun closeAll() {
        tcpSockets.keys.forEach { closeTcpStream(it) }
        udpSockets.keys.forEach { closeUdpStream(it) }
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "HostProxyManager"
    }
}
