package xyz.wraithnet.phantom.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun isTelevision(): Boolean {
    val context = LocalContext.current
    val tvHardware = remember(context) {
        val pm = context.packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            // AOSP TV boxes may declare neither leanback nor television uiMode.
            // A box without a touch screen still needs D-pad-friendly controls.
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    return tvHardware || (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
}

/** A declared camera feature is not sufficient: many TV boxes advertise a camera
 * feature without an attached/available camera device.
 */
internal fun hasQrCamera(context: Context): Boolean {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return false
    return runCatching {
        context.getSystemService(CameraManager::class.java)?.cameraIdList?.isNotEmpty() == true
    }.getOrDefault(false)
}

@Composable
internal fun Modifier.tvFocusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    if (!isTelevision()) return this
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }.then(
        if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
    )
}

/**
 * A dedicated TV rail replaces the touch-centric bottom NavigationBar on TV.
 * Three stable D-pad targets. Servers are now on Home without an extra tab.
 * Full owner actions, ping, favorites and JSON remain accessible in the grid.
 */
@Composable
internal fun TvSideNavigation(
    current: Destination,
    select: (Destination) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.width(184.dp).fillMaxHeight()
            .padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("PHANTOM", fontSize = 17.sp, fontWeight = FontWeight.Bold,
            color = c.onSurface, modifier = Modifier.padding(start = 12.dp))
        Text("VPN · Android TV", fontSize = 10.sp, color = c.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        Destination.entries.filter { it != Destination.SERVERS }.forEach { destination ->
            val active = current == destination
            val shape = RoundedCornerShape(13.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clip(shape)
                    .background(if (active) c.primaryContainer else Color.Transparent)
                    .tvFocusRing(shape)
                    .clickable { select(destination) }
                    .semantics { contentDescription = "Вкладка ${destination.title}" }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(destination.icon, contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (active) c.primary else c.onSurfaceVariant)
                Text(destination.title,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) c.onSurface else c.onSurfaceVariant,
                    softWrap = true,
                    maxLines = 3)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("OK · Выбрать", fontSize = 11.sp, color = c.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp))
        Text("BACK · Назад", fontSize = 11.sp, color = c.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp))
    }
}
