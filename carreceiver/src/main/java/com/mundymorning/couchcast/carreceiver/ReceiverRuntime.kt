package com.mundymorning.couchcast.carreceiver

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared between the background service (which runs the server) and the screen (which shows the code and address). */
object ReceiverRuntime {
    val pairing = Pairing()
    val running = MutableStateFlow(false)
    val port = MutableStateFlow(0)
}
