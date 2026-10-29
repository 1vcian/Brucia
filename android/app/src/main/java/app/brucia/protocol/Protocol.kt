package app.brucia.protocol

/** Companion wire protocol constants. Keep in sync with proto/PROTOCOL.md. */
const val PROTO_VERSION = 0

object Kind {
    const val HELLO = 1
    const val CAPS = 2
    const val CMD = 3
    const val ACK = 4
    const val ERR = 5
    const val EVENT = 6
    const val DATA = 7
    const val SUB = 8
    const val PING = 9
    const val PONG = 10
}
