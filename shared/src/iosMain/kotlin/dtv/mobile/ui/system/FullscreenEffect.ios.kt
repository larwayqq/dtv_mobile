package dtv.mobile.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dtv.mobile.util.AppLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSNumber
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIInterfaceOrientation
import platform.UIKit.UIInterfaceOrientationMaskLandscapeRight
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

@OptIn(ExperimentalForeignApi::class)
private fun isIosAtLeast(major: Int): Boolean {
  val v = cValue<NSOperatingSystemVersion> {
    majorVersion = major.toLong()
    minorVersion = 0
    patchVersion = 0
  }
  return NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(v)
}

@OptIn(ExperimentalForeignApi::class)
private fun forceOrientation(landscape: Boolean) {
  // iOS 16+: geometry update API on the active window scene.
  if (isIosAtLeast(16)) {
    runCatching {
      val scene = UIApplication.sharedApplication.connectedScenes
        .mapNotNull { it as? UIWindowScene }
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
      if (scene != null) {
        val mask = if (landscape) UIInterfaceOrientationMaskLandscapeRight else UIInterfaceOrientationMaskPortrait
        val prefs = UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask)
        scene.requestGeometryUpdateWithPreferences(prefs, null)
      }
    }.onFailure { AppLog.w("DTV-Fullscreen", "geometry update failed: ${it.message}") }
  }

  // Legacy path (and still commonly honored on iOS 16/17 for simple apps).
  runCatching {
    val deviceOrientation = if (landscape) UIDeviceOrientation.LandscapeRight else UIDeviceOrientation.Portrait
    val interfaceOrientation = if (landscape) UIInterfaceOrientation.LandscapeRight else UIInterfaceOrientation.Portrait
    UIDevice.currentDevice.setValue(NSNumber(long = deviceOrientation.value.toLong()), forKey = "orientation")
    UIApplication.sharedApplication.setValue(NSNumber(long = interfaceOrientation.value.toLong()), forKey = "statusBarOrientation")
  }.onFailure { AppLog.w("DTV-Fullscreen", "setValue orientation failed: ${it.message}") }
}

@Composable
actual fun FullscreenEffect(
  enabled: Boolean,
  lockLandscape: Boolean,
  exitToPortrait: Boolean,
) {
  DisposableEffect(enabled, lockLandscape, exitToPortrait) {
    if (enabled && lockLandscape) {
      forceOrientation(landscape = true)
    } else if (enabled && exitToPortrait) {
      forceOrientation(landscape = false)
    }
    onDispose { }
  }
}
