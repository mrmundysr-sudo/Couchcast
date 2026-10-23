package com.mundymorning.couchcast.install

import com.mundymorning.couchcast.roku.RokuApp

/** What we learned from live probes of the Roku. Nothing here is assumed or remembered. */
enum class Reachability { NETWORK_DOWN, ROKU_UNREACHABLE, ECP_BLOCKED, DEV_MODE_OFF, INSTALLER_READY }

enum class ReceiverState { NONE, CURRENT, OUTDATED, OTHER_DEV_APP, UNKNOWN }

enum class Step { PICK, CHECKING, PROBLEM, DEV_MODE, RESTART, CREDENTIALS, INSTALLING, VERIFYING, DONE }

object InstallPlanner {
    /** The Roku keeps exactly one sideloaded channel, always with app id "dev". */
    const val DEV_APP_ID = "dev"

    fun classify(apps: List<RokuApp>?, pkg: ReceiverPackage): ReceiverState {
        if (apps == null) return ReceiverState.UNKNOWN
        val dev = apps.firstOrNull { it.id == DEV_APP_ID } ?: return ReceiverState.NONE
        if (dev.name != pkg.title) return ReceiverState.OTHER_DEV_APP
        val installed = ReceiverPackage.parseVersion(dev.version) ?: return ReceiverState.OUTDATED
        return if (ReceiverPackage.compare(installed, pkg.version) >= 0) ReceiverState.CURRENT else ReceiverState.OUTDATED
    }

    fun decide(reach: Reachability, restartPending: Boolean, receiver: ReceiverState): Step = when (reach) {
        Reachability.NETWORK_DOWN, Reachability.ECP_BLOCKED -> Step.PROBLEM
        Reachability.ROKU_UNREACHABLE -> if (restartPending) Step.RESTART else Step.PROBLEM
        Reachability.DEV_MODE_OFF -> if (restartPending) Step.RESTART else Step.DEV_MODE
        Reachability.INSTALLER_READY -> if (receiver == ReceiverState.CURRENT) Step.DONE else Step.CREDENTIALS
    }
}
