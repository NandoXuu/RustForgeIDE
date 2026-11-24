package com.ndx.rustforge.ide

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ndx.rustforge.ide.ui.IdeApp

private val RustDark = darkColorScheme(
    primary = Color(0xFFFFB59D),
    onPrimary = Color(0xFF5C1900),
    primaryContainer = Color(0xFF842500),
    secondary = Color(0xFF7DD3C0),
    tertiary = Color(0xFFB7C9FF),
    background = Color(0xFF111114),
    surface = Color(0xFF17171B),
    surfaceVariant = Color(0xFF26262C),
    onSurfaceVariant = Color(0xFFC6C6CF)
)

private val RustLight = lightColorScheme(
    primary = Color(0xFF9A3412),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFF006B5B),
    tertiary = Color(0xFF3B5BA9)
)

@Composable
fun RustForgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> RustDark
        else -> RustLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RustForgeTheme {
                IdeApp()
            }
        }
    }
}
