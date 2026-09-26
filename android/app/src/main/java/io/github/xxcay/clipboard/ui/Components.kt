@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.xxcay.clipboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.xxcay.clipboard.ClipItem
import io.github.xxcay.clipboard.R
import java.io.File

/** The orange app logo tile. */
@Composable
fun Logo(size: Dp = 48.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(10.dp, RoundedCornerShape(size * 0.3f), ambientColor = Palette.Orange, spotColor = Palette.Orange)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Palette.Gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_logo), null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}

fun iconFor(item: ClipItem): ImageVector {
    if (item.isUrl) return Icons.Rounded.Link
    if (!item.isFile) return Icons.AutoMirrored.Rounded.Notes
    val mime = item.file!!.mime
    val ext = item.file.name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> Icons.Rounded.Image
        mime.startsWith("video/") -> Icons.Rounded.Movie
        mime.startsWith("audio/") -> Icons.Rounded.MusicNote
        ext in setOf("ppt", "pptx", "odp", "key") -> Icons.Rounded.Slideshow
        ext in setOf("zip", "rar", "7z") -> Icons.Rounded.Archive
        else -> Icons.Rounded.Description
    }
}

/** Square tile: a photo preview when available, otherwise a type icon. */
@Composable
fun ItemTile(item: ClipItem, file: File?, size: Dp = 52.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Palette.Soft),
        contentAlignment = Alignment.Center,
    ) {
        if (item.isImage && file != null) {
            AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(iconFor(item), null, tint = Palette.Orange, modifier = Modifier.size(size * 0.46f))
        }
    }
}

/** White rounded card with a hairline border. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Palette.Line),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SectionLabel, modifier = modifier.padding(start = 4.dp, bottom = 10.dp))
}

/** Title + description on the left, a switch on the right. */
@Composable
fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.Orange,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFEDE6E1),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** A pill-shaped filter chip. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Palette.Orange else Color(0xFFF5EFEA),
        contentColor = if (selected) Color.White else Palette.Ink,
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Big orange gradient button. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = modifier
            .height(56.dp)
            .shadow(if (enabled) 12.dp else 0.dp, RoundedCornerShape(18.dp), ambientColor = Palette.Orange, spotColor = Palette.Orange),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(if (enabled) Palette.Gradient else androidx.compose.ui.graphics.SolidColor(Color(0xFFF1D9C8))),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

/** Row in the "quick access" section. */
@Composable
fun ActionRow(icon: ImageVector, title: String, description: String, trailing: String? = null, onClick: (() -> Unit)?) {
    val content: @Composable RowScope.() -> Unit = {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Soft),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Palette.Orange, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Text(trailing, color = Palette.Ok, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
