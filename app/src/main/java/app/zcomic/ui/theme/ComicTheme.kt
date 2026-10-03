package app.zcomic.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val comicColors = darkColorScheme(
    primary = Color(0xFFFF9A45), onPrimary = Color(0xFF241307),
    primaryContainer = Color(0xFF49301C), onPrimaryContainer = Color(0xFFFFDCC1),
    secondary = Color(0xFF91B8D5), onSecondary = Color(0xFF102431),
    secondaryContainer = Color(0xFF293A47), onSecondaryContainer = Color(0xFFD0E8FA),
    tertiary = Color(0xFFE8C76B), onTertiary = Color(0xFF302707),
    tertiaryContainer = Color(0xFF443C25), onTertiaryContainer = Color(0xFFF6E5AA),
    background = Color(0xFF111216), onBackground = Color(0xFFF0F0F2),
    surface = Color(0xFF17181D), onSurface = Color(0xFFF0F0F2),
    surfaceVariant = Color(0xFF25262C), onSurfaceVariant = Color(0xFFADB0BA),
    surfaceTint = Color(0xFFFF9A45),
    surfaceDim = Color(0xFF111216), surfaceBright = Color(0xFF33343B),
    surfaceContainerLowest = Color(0xFF0D0E12), surfaceContainerLow = Color(0xFF191A1F),
    surfaceContainer = Color(0xFF202127), surfaceContainerHigh = Color(0xFF282930),
    surfaceContainerHighest = Color(0xFF303139),
    outline = Color(0xFF747782), outlineVariant = Color(0xFF373940),
    error = Color(0xFFFF8D8D), onError = Color(0xFF3B1010),
    errorContainer = Color(0xFF502323), onErrorContainer = Color(0xFFFFDADA),
    inverseSurface = Color(0xFFE5E5EA), inverseOnSurface = Color(0xFF25262C),
    inversePrimary = Color(0xFF9A501C), scrim = Color.Black
)

@Composable
fun ComicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = comicColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(16.dp)
        ),
        content = content
    )
}
