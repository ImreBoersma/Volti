package nl.imreboersma.volti.ui.theme

import androidx.compose.ui.graphics.Color

// The app icon's palette (design/volti_icon_master.svg).
val Ink = Color(0xFF0F0F0F)
val BauhausBlue = Color(0xFF2568BA)
val BauhausRed = Color(0xFFC83823)
val BauhausRedShadow = Color(0xFF9D2918)
val Cream = Color(0xFFEFE8D8)

// Derived for the light theme: a lighter paper for cards and fields, and a muted ink for secondary text.
val CreamLight = Color(0xFFF7F3EA)
val InkMuted = Color(0xFF4A4741)
val CreamDim = Color(0xFFDCD3C0)

// Lines and borders: at least 3:1 against the background, the bar for controls that aren't text.
val CreamLine = Color(0xFF8A8378)
val InkLine = Color(0xFF6B665E)

/** Pages are white paper, whatever the theme. */
val Paper = Color(0xFFFFFFFF)

// Derived for the dark theme: on near-black, the icon's blue and red are too dark for text (about 3.4:1), so
// lighter versions of the same hues, and raised surfaces just above the ink.
val BlueLight = Color(0xFF8DB3E6)
val RedLight = Color(0xFFF08A76)
val InkRaised = Color(0xFF1B1A19)
val InkHigh = Color(0xFF2A2826)
val CreamMuted = Color(0xFFC9C1B0)
