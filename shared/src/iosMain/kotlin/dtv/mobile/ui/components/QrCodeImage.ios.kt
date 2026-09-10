package dtv.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import dtv.mobile.util.AppLog
import dtv.mobile.util.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeGenerator
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

private const val QR_PIXEL_SIZE = 512.0

@OptIn(ExperimentalForeignApi::class)
private fun generateQrImage(data: String): UIImage? {
  return try {
    val filter = CIQRCodeGenerator()
    filter.inputMessage = data.encodeToByteArray().toNSData()
    filter.inputCorrectionLevel = "M"
    val raw: CIImage = filter.outputImage ?: return null

    val width = raw.extent.useContents { size.width }
    val height = raw.extent.useContents { size.height }
    if (width <= 0.0 || height <= 0.0) return null

    val scale = minOf(QR_PIXEL_SIZE / width, QR_PIXEL_SIZE / height)
    val scaled = raw.imageByApplyingTransform(CGAffineTransformMakeScale(scale, scale))

    val ciContext = CIContext.contextWithOptions(null)
    val cgImage = ciContext?.createCGImage(scaled, fromRect = scaled.extent)
      ?: return null
    UIImage.imageWithCGImage(cgImage)
  } catch (e: Throwable) {
    AppLog.e("DTV-QR", "generate qr failed", e)
    null
  }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrCodeImage(
  data: String,
  modifier: Modifier,
) {
  val image = remember(data) { generateQrImage(data) }

  UIKitView(
    factory = {
      UIImageView().apply {
        contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
        clipsToBounds = true
        this.image = image
      }
    },
    modifier = modifier,
    update = { imageView -> imageView.image = image },
  )
}
