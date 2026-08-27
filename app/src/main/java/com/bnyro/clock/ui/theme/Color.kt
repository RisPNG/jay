package com.bnyro.clock.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * [ColorScheme.primary] at the weight a control takes when it sits behind the one in focus.
 */
val ColorScheme.primaryFade: Color get() = primary.copy(alpha = 0.3f)

/**
 * [ColorScheme.primary] at the weight a control takes when it carries no focus of its own.
 */
val ColorScheme.primarySoft: Color get() = primary.copy(alpha = 0.6f)
