package com.wanderwildwood.yumecho.night

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the night service is doing, for the screen to show. */
object Night {
    enum class State { RESTING, ARMED, RECORDING }

    internal val current = MutableStateFlow(State.RESTING)
    val state: StateFlow<State> = current
}
