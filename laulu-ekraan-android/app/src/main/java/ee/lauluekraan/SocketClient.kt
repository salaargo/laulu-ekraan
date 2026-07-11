package ee.lauluekraan

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

class SocketClient(
    private val serverUrl: String,
    private val onViewerCount: (Int) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var socket: Socket? = null

    var roomId: String? = null
        private set

    fun connect(onConnected: () -> Unit) {
        val options = IO.Options().apply {
            forceNew = true
            reconnection = true
            transports = arrayOf("websocket", "polling")
        }
        socket = IO.socket(URI.create(serverUrl), options)
        socket?.on(Socket.EVENT_CONNECT) { onConnected() }
        socket?.on(Socket.EVENT_DISCONNECT) { onDisconnected() }
        socket?.on("viewer-count") { args ->
            val count = when (val value = args.getOrNull(0)) {
                is Int -> value
                is Double -> value.toInt()
                else -> 0
            }
            onViewerCount(count)
        }
        socket?.connect()
    }

    fun createRoom(onRoomCreated: (String) -> Unit) {
        socket?.emit("create-room", Ack { args ->
            val data = args[0] as JSONObject
            roomId = data.getString("roomId")
            onRoomCreated(roomId!!)
        })
    }

    fun sendFrame(imageDataUrl: String) {
        val id = roomId ?: return
        val payload = JSONObject().apply {
            put("roomId", id)
            put("image", imageDataUrl)
        }
        socket?.emit("frame", payload)
    }

    fun stopSharing() {
        val id = roomId ?: return
        val payload = JSONObject().apply { put("roomId", id) }
        socket?.emit("stop-sharing", payload)
        roomId = null
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
