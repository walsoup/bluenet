package com.bluenet.multiplexer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

data class Frame(
    val type: FrameType,
    val streamId: Int,
    val payload: ByteArray = ByteArray(0)
) {
    fun writeTo(dos: DataOutputStream) {
        synchronized(dos) {
            dos.writeByte(MAGIC.toInt())
            dos.writeByte(type.code.toInt())
            dos.writeInt(streamId)
            dos.writeShort(payload.size)
            if (payload.isNotEmpty()) {
                dos.write(payload)
            }
            dos.flush()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Frame
        if (type != other.type) return false
        if (streamId != other.streamId) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + streamId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val MAGIC: Byte = 0xBE.toByte()
        const val HEADER_SIZE = 8 // 1(Magic) + 1(Type) + 4(StreamId) + 2(Length)

        fun readFrom(dis: DataInputStream): Frame? {
            val magic = dis.readByte()
            if (magic != MAGIC) {
                throw IOException("Invalid frame magic byte: $magic")
            }
            val typeByte = dis.readByte()
            val type = FrameType.fromCode(typeByte)
                ?: throw IOException("Unknown frame type: $typeByte")
            val streamId = dis.readInt()
            val length = dis.readUnsignedShort()
            val payload = ByteArray(length)
            if (length > 0) {
                dis.readFully(payload)
            }
            return Frame(type, streamId, payload)
        }
    }
}
