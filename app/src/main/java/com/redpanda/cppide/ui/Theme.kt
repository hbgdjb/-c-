package com.redpanda.cppide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 小熊猫C++ 的红色主色调
val RedPandaRed = Color(0xFFC0392B)
val RedPandaRedDark = Color(0xFF8E2A1F)
val RedPandaRedLight = Color(0xFFE57373)
val ConsoleBg = Color(0xFF1E1E1E)
val ConsoleFg = Color(0xFFD4D4D4)
/** 控制台里的编译错误行颜色（可点击跳转） */
val ConsoleDiagFg = Color(0xFFFF8A80)

private val LightColors = lightColorScheme(
    primary = RedPandaRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD4),
    onPrimaryContainer = RedPandaRedDark,
    secondary = Color(0xFF775652),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
    surfaceVariant = Color(0xFFF5DDD9),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = RedPandaRedLight,
    onPrimary = Color(0xFF5F150C),
    primaryContainer = RedPandaRedDark,
    onPrimaryContainer = Color(0xFFFFDAD4),
    background = Color(0xFF201A19),
    surface = Color(0xFF201A19),
    surfaceVariant = Color(0xFF534341),
    error = Color(0xFFFFB4AB)
)

@Composable
fun RedPandaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
