package xyz.wraithnet.phantom.ui.home

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val leanback = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    return leanback || (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
internal fun Modifier.tvFocusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    if (!isTelevision()) return this
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }.then(
        if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
    )
}
