package dtv.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.ContentScale
import dtv.mobile.util.AppLog
import dtv.mobile.util.toNSData
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCache
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

private val imageClient: HttpClient by lazy { HttpClient(Darwin) }
private val imageCache: NSCache by lazy {
  NSCache().apply { countLimit = 200u }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NetworkImage(
  url: String?,
  contentDescription: String?,
  modifier: Modifier,
  contentScale: ContentScale,
) {
  var image by remember(url) { mutableStateOf<UIImage?>(null) }

  LaunchedEffect(url) {
    val target = url?.trim().orEmpty()
    if (target.isEmpty()) {
      image = null
      return@LaunchedEffect
    }
    @Suppress("UNCHECKED_CAST")
    val cached = imageCache.objectForKey(target) as? UIImage
    if (cached != null) {
      image = cached
      return@LaunchedEffect
    }
    val bytes = runCatching { imageClient.get(target).readBytes() }
      .onFailure { AppLog.w("DTV-Image", "load failed url=$target: ${it.message}") }
      .getOrNull()
    if (bytes != null) {
      val uiImage = UIImage(data = bytes.toNSData())
      if (uiImage != null) {
        imageCache.setObject(uiImage, forKey = target)
        image = uiImage
      }
    }
  }

  val targetContentMode = when (contentScale) {
    ContentScale.Crop -> UIViewContentMode.UIViewContentModeScaleAspectFill
    ContentScale.Fit, ContentScale.Inside -> UIViewContentMode.UIViewContentModeScaleAspectFit
    ContentScale.FillBounds -> UIViewContentMode.UIViewContentModeScaleToFill
    else -> UIViewContentMode.UIViewContentModeScaleAspectFill
  }

  UIKitView(
    factory = {
      UIImageView().apply {
        this.contentMode = targetContentMode
        clipsToBounds = true
      }
    },
    modifier = modifier,
    update = { imageView ->
      imageView.image = image
      imageView.contentMode = targetContentMode
    },
  )
}
