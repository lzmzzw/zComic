package app.zcomic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
internal fun Header(title: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(content = action)
    }
}

@Composable
internal fun Cover(url: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp))
    }
}

@Composable
internal fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(top = 85.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            modifier = Modifier.padding(top = 5.dp))
    }
}
