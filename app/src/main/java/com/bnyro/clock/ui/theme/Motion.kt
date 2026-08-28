package com.bnyro.clock.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * The weight a card takes on as it arrives in a list, and gives up as it leaves.
 */
val ItemFade: FiniteAnimationSpec<Float> =
    tween(durationMillis = 120, easing = FastOutSlowInEasing)

/**
 * The travel of a card the list moves aside to make room for another, or to close the gap one left.
 */
val ItemSlide: FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = 220, easing = FastOutSlowInEasing)
