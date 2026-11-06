// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import helium314.keyboard.latin.R

/** An IME cannot show the runtime permission dialog itself, so it starts this transparent activity. */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Toast.makeText(this, R.string.sv_voice_permission_granted, Toast.LENGTH_SHORT).show()
        } else if (!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            // "don't ask again" or blocked by policy: the only way left is the app settings page
            Toast.makeText(this, R.string.sv_voice_permission_settings, Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
        finish()
    }
}
