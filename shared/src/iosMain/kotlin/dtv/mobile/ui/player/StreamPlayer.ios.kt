package dtv.mobile.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.runtime.key
import dtv.mobile.util.AppLog
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetHTTPHeaderFieldsKey
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSKeyValueObservation
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
private class PlayerContainerView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
  private val playerLayer = AVPlayerLayer()
  private var player: AVPlayer? = null
  private var observers: List<NSKeyValueObservation> = emptyList()

  init {
    backgroundColor = UIColor.blackColor
    opaque = true
    layer.addSublayer(playerLayer)
  }

  override fun layoutSubviews() {
    super.layoutSubviews()
    playerLayer.frame = bounds
  }

  fun configure(
    url: String,
    zoomToFill: Boolean,
    onVideoAspectRatioChanged: (Float?) -> Unit,
    onError: (String) -> Unit,
  ) {
    playerLayer.videoGravity =
      if (zoomToFill) AVLayerVideoGravityResizeAspectFill else AVLayerVideoGravityResizeAspect

    val headers = buildMap {
      put(
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
      )
      val u = url.lowercase()
      when {
        "huya" in u -> put("Referer", "https://www.huya.com/")
        "bilibili" in u -> put("Referer", "https://live.bilibili.com/")
        "douyin" in u -> put("Referer", "https://live.douyin.com/")
        "douyu" in u -> put("Referer", "https://www.douyu.com/")
      }
    }

    val nsUrl = NSURL(string = url)
    val asset = AVURLAsset(uRL = nsUrl, options = mapOf(AVURLAssetHTTPHeaderFieldsKey to headers))
    val item = AVPlayerItem(asset = asset)

    val statusObs = item.observeKeyPath("status") { observed, _ ->
      if (observed.status == AVPlayerItemStatusFailed) {
        val msg = observed.error?.localizedDescription ?: "播放失败"
        AppLog.e("DTV-Player", "AVPlayer failed url=$url msg=$msg")
        onError(msg)
      }
    }
    val sizeObs = item.observeKeyPath("presentationSize") { observed, _ ->
      val w = observed.presentationSize.useContents { width }
      val h = observed.presentationSize.useContents { height }
      if (w > 0.0 && h > 0.0) {
        onVideoAspectRatioChanged(w.toFloat() / h.toFloat())
      }
    }
    observers = listOf(statusObs, sizeObs)

    val p = AVPlayer(playerItem = item)
    player = p
    playerLayer.player = p
    p.play()
  }

  fun setZoomToFill(zoomToFill: Boolean) {
    playerLayer.videoGravity =
      if (zoomToFill) AVLayerVideoGravityResizeAspectFill else AVLayerVideoGravityResizeAspect
  }

  fun release() {
    observers.forEach { it.invalidate() }
    observers = emptyList()
    player?.pause()
    playerLayer.player = null
    player = null
  }
}

@Composable
actual fun StreamPlayer(
  url: String,
  fullscreen: Boolean,
  liveMode: Boolean,
  zoomToFill: Boolean,
  onVideoAspectRatioChanged: (Float?) -> Unit,
  onError: (String) -> Unit,
  modifier: Modifier,
) {
  key(url) {
    UIKitView(
      factory = {
        PlayerContainerView().apply {
          configure(
            url = url,
            zoomToFill = zoomToFill,
            onVideoAspectRatioChanged = onVideoAspectRatioChanged,
            onError = onError,
          )
        }
      },
      modifier = modifier,
      update = { view -> view.setZoomToFill(zoomToFill) },
      onRelease = { view -> view.release() },
    )
  }
}
