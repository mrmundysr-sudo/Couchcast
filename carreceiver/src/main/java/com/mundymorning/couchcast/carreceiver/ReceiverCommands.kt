package com.mundymorning.couchcast.carreceiver

data class GameInfo(val id: String, val title: String)

/** One controller gesture. type: move (dx,dy as fractions of the touchpad), tap, down, up, key (key = Up/Down/Left/Right/Enter), exit. */
data class InputEvent(val type: String, val dx: Float = 0f, val dy: Float = 0f, val key: String? = null)

data class ReceiverStatus(
    /** idle, video, audio, photo or game */
    val mode: String,
    val title: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val game: String? = null,
    /** Why the last thing couldn't play, shown on the controller and on the receiver's home screen. */
    val error: String? = null,
)

/** What a paired controller may ask the receiver to do. The server validates and authenticates; the receiver acts. */
interface ReceiverCommands {
    /** [source] is an http(s) or file: address. Returns an error message, or null when accepted. */
    fun play(source: String, kind: MediaKind, title: String?): String?
    /** pause, resume, toggle, stop, seekBy (value = ms, may be negative), seekTo (value = ms). */
    fun control(action: String, value: Long?): Boolean
    fun games(): List<GameInfo>
    fun startGame(id: String): Boolean
    fun input(e: InputEvent): Boolean
    fun status(): ReceiverStatus
}
