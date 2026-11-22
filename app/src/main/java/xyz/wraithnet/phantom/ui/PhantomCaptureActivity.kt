package xyz.wraithnet.phantom.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import android.os.Bundle
import android.view.View
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import xyz.wraithnet.phantom.R

/**
 * QR camera activity keeps the orientation from its launching screen.
 * Phones normally open portrait; landscape tablets must NOT rotate portrait.
 * The capture remains a separate activity for camera permissions/result handling.
 */
class PhantomCaptureActivity : CaptureActivity() {
    companion object {
        const val EXTRA_CAPTURE_ORIENTATION =
            "xyz.wraithnet.phantom.QR_CAPTURE_ORIENTATION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val desired = intent?.getIntExtra(
            EXTRA_CAPTURE_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        if (desired == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            desired == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            // Set before ZXing configures its camera preview.
            requestedOrientation = desired
        }
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Device-only diagnostics used to catch unwanted portrait transitions
        // during tablet landscape scanning. No QR contents are logged.
        Log.i("PhantomQrCapture",
            "requested=" + requestedOrientation +
                " actual=" + resources.configuration.orientation +
                " landscape=" +
                (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE))
    }

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_phantom_capture)
        findViewById<View>(R.id.phantom_qr_close).setOnClickListener { finish() }
        return findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner).apply {
            // ZXing still decodes; our branded overlay owns all guides and labels.
            viewFinder.visibility = View.GONE
            statusView.visibility = View.GONE
        }
    }
}
