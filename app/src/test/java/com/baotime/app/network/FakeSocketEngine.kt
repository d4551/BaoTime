package com.baotime.app.network

/** In-memory SocketEngine stand-in; connect/disconnect drive the listener synchronously. */
class FakeSocketEngine(val listener: SocketEngineListener) : SocketEngine {
    var connected = false
    var closed = false

    override fun connect() {
        connected = true
        listener.onConnect()
    }

    override fun disconnect() {
        connected = false
        listener.onDisconnect()
    }

    override fun close() {
        connected = false
        closed = true
    }
}