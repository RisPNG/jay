package com.bnyro.clock.util

interface RingingSession {
    val soundEnabled: Boolean
    val vibrate: Boolean
    val timeoutMillis: Long
    val isAlarm: Boolean

    fun onSelected()
    fun onSuppressed()
}

object RingingCoordinator {
    private val sessions = mutableListOf<RingingSession>()
    var selected: RingingSession? = null
        private set

    fun start(session: RingingSession) {
        if (session !in sessions) sessions.add(session)
        selectRingingSession()
    }

    fun stop(vararg ending: RingingSession) {
        sessions.removeAll(ending.toSet())
        selectRingingSession()
    }

    private fun selectRingingSession() {
        val next = sessions.maxWithOrNull(
            compareBy<RingingSession> { it.soundEnabled }
                .thenBy { it.vibrate }
                .thenBy { it.timeoutMillis }
                .thenBy { it.isAlarm }
        )
        if (next === selected) return
        val previous = selected
        selected = next
        previous?.onSuppressed()
        next?.onSelected()
    }
}
