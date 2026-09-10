package dtv.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.interop.toImageBitmap
import dtv.mobile.util.AppLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toNSData
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.UIKit.UIImage

private const val QR_PIXEL_SIZE = 512.0

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrCodeImage(
  data: String,
  modifier: Modifier,
) {
  val bitmap: ImageBitmap? = remember(data) {
    try {
      val filter = CIFilter.filterWithName("CIQRCodeGenerator")
        ?: return@remember null
      filter.setValue(data.encodeToByteArray().toNSData(), forKey = "inputMessage")
      filter.setValue("M", forKey = "inputCorrectionLevel")
      val raw: CIImage = filter.outputImage ?: return@remember null

      val width = raw.extent.useContents { size.width }
      val height = raw.extent.useContents { size.height }
      if (width <= 0.0 || height <= 0.0) return@remember null

      val scale = minOf(QR_PIXEL_SIZE / width, QR_PIXEL_SIZE / height)
      val scaled = raw.imageByApplyingTransform(CGAffineTransformMakeScale(scale, scale))

      val ciContext = CIContext.contextWithOptions(null)
      val cgImage = ciContext.createCGImage(scaled, fromRect = scaled.extent)
        ?: return@remember null
      UIImage.imageWithCGImage(cgImage).toImageBitmap()
    } catch (e: Throwable) {
      AppLog.e("DTV-QR", "generate qr failed", e)
      null
    }
  }

  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = "二维码",
      modifier = modifier,
    )
  }
}
