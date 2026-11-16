package xyz.wraithnet.phantom.ui

import android.view.View
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import xyz.wraithnet.phantom.R

/** Native portrait QR scanner, always with a visible Close control. */
class PhantomCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_phantom_capture)
        findViewById<View>(R.id.phantom_qr_close).setOnClickListener { finish() }
        return findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner).apply {
            viewFinder.setLaserVisibility(false)
        }
    }
}
