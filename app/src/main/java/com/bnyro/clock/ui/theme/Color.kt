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

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
