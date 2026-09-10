package dtv.mobile.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.runtime.key
import dtv.mobile.util.AppLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
private class PlayerContainerView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
  private val playerLayer = AVPlayerLayer()
  private var player: AVPlayer? = null
  private var pollTimer: NSTimer? = null
  private var aspectReported = false
  private var configuredUrl: String? = null
  private var onErrorCallback: ((String) -> Unit)? = null
  private var onAspectCallback: ((Float?) -> Unit)? = null

  init {
    backgroundColor = UIColor.blackColor
    opaque = true
    layer.addSublayer(playerLayer)
  }

  override fun layoutSubviews() {
    super.layoutSubviews()
    playerLayer.frame = bounds
  }

  // The standard Kotlin/Native bindings do not expose the NSKeyValueObserving
  // protocol, so item status / presentation size are polled with a timer
  // instead of addObserver(forKeyPath:).
  private fun startPolling(item: AVPlayerItem) {
    pollTimer?.invalidate()
    aspectReported = false
    pollTimer = NSTimer.scheduledTimerWithTimeInterval(0.3, repeats = true) { _ ->
      if (item.status == AVPlayerItemStatusFailed) {
        val msg = item.error?.localizedDescription ?: "播放失败"
        AppLog.e("DTV-Player", "AVPlayer failed url=$configuredUrl msg=$msg")
        onErrorCallback?.invoke(msg)
        pollTimer?.invalidate()
        pollTimer = null
        return@scheduledTimerWithTimeInterval
      }
      if (!aspectReported) {
        item.presentationSize.useContents {
          if (width > 0.0 && height > 0.0) {
            aspectReported = true
            onAspectCallback?.invoke(width.toFloat() / height.toFloat())
          }
        }
      }
    }
  }

  fun configure(
    url: String,
    zoomToFill: Boolean,
    onVideoAspectRatioChanged: (Float?) -> Unit,
    onError: (String) -> Unit,
  ) {
    configuredUrl = url
    onErrorCallback = onError
    onAspectCallback = onVideoAspectRatioChanged

    playerLayer.videoGravity =
      if (zoomToFill) AVLayerVideoGravityResizeAspectFill else AVLayerVideoGravityResizeAspect

    val headers = buildMap<Any?, Any?> {
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
    val asset = AVURLAsset(
      uRL = nsUrl,
      options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers),
    )
    val item = AVPlayerItem(asset = asset)

    val p = AVPlayer(playerItem = item)
    player = p
    playerLayer.player = p
    p.play()
    startPolling(item)
  }

  fun setZoomToFill(zoomToFill: Boolean) {
    playerLayer.videoGravity =
      if (zoomToFill) AVLayerVideoGravityResizeAspectFill else AVLayerVideoGravityResizeAspect
  }

  fun release() {
    pollTimer?.invalidate()
    pollTimer = null
    player?.pause()
    playerLayer.player = null
    player = null
  }
}

@OptIn(ExperimentalForeignApi::class)
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
