package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// NexChat Pro — Emerald & Deep Slate Palette
val NexEmeraldPrimary = Color(0xFF00A884)
val NexEmeraldDark = Color(0xFF008069)
val NexEmeraldBright = Color(0xFF25D366)
val NexReadBlue = Color(0xFF53BDEB)
val NexDangerRed = Color(0xFFF15C6D)
val NexWarningAmber = Color(0xFFF59E0B)

// Dark Theme Surfaces
val DarkBackground = Color(0xFF0B141A)
val DarkSurface = Color(0xFF111B21)
val DarkSurfaceVariant = Color(0xFF202C33)
val DarkOutgoingBubble = Color(0xFF005C4B)
val DarkIncomingBubble = Color(0xFF202C33)
val DarkTextPrimary = Color(0xFFE9EDEF)
val DarkTextSecondary = Color(0xFF8696A0)

// Light Theme Surfaces
val LightBackground = Color(0xFFF0F2F5)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE9EDEF)
val LightChatCanvas = Color(0xFFEFEAE2)
val LightOutgoingBubble = Color(0xFFD9FDD3)
val LightIncomingBubble = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF111B21)
val LightTextSecondary = Color(0xFF667781)

// Per-Chat Selectable Wallpaper Themes
data class ChatWallpaperTheme(
    val id: String,
    val label: String,
    val darkCanvas: Color,
    val lightCanvas: Color,
    val darkOutgoing: Color,
    val lightOutgoing: Color,
    val accent: Color
)

val ChatThemes = listOf(
    ChatWallpaperTheme(
        id = "emerald",
        label = "NexChat Emerald",
        darkCanvas = Color(0xFF0B141A),
        lightCanvas = Color(0xFFEFEAE2),
        darkOutgoing = Color(0xFF005C4B),
        lightOutgoing = Color(0xFFD9FDD3),
        accent = Color(0xFF00A884)
    ),
    ChatWallpaperTheme(
        id = "midnight",
        label = "Midnight Indigo",
        darkCanvas = Color(0xFF0D1326),
        lightCanvas = Color(0xFFEEF2FF),
        darkOutgoing = Color(0xFF312E81),
        lightOutgoing = Color(0xFFE0E7FF),
        accent = Color(0xFF6366F1)
    ),
    ChatWallpaperTheme(
        id = "amber",
        label = "Sunset Amber",
        darkCanvas = Color(0xFF1C140D),
        lightCanvas = Color(0xFFFFFBEB),
        darkOutgoing = Color(0xFF78350F),
        lightOutgoing = Color(0xFFFEF3C7),
        accent = Color(0xFFD97706)
    ),
    ChatWallpaperTheme(
        id = "rose",
        label = "Rose Quartz",
        darkCanvas = Color(0xFF1F0E17),
        lightCanvas = Color(0xFFFFF1F2),
        darkOutgoing = Color(0xFF831843),
        lightOutgoing = Color(0xFFFCE7F3),
        accent = Color(0xFFE11D48)
    ),
    ChatWallpaperTheme(
        id = "mono",
        label = "Minimal Slate",
        darkCanvas = Color(0xFF121212),
        lightCanvas = Color(0xFFF3F4F6),
        darkOutgoing = Color(0xFF27272A),
        lightOutgoing = Color(0xFFE5E7EB),
        accent = Color(0xFF52525B)
    )
)

fun getChatWallpaperTheme(themeId: String): ChatWallpaperTheme {
    return ChatThemes.firstOrNull { it.id == themeId } ?: ChatThemes.first()
}
