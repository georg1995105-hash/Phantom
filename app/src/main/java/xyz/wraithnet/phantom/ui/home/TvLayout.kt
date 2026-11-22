package xyz.wraithnet.phantom.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
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
