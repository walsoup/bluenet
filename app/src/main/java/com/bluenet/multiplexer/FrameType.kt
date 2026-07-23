package com.bluenet.multiplexer

enum class FrameType(val code: Byte) {
    CONNECT_TCP(0x01),
    DATA(0x02),
    CLOSE(0x03),
    CONNECT_UDP(0x04),
    KEEPALIVE(0x05);

    companion object {
        fun fromCode(code: Byte): FrameType? {
            return values().firstOrNull { it.code == code }
        }
    }
}
