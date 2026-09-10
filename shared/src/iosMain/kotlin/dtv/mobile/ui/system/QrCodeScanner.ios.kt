package dtv.mobile.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dtv.mobile.util.AppLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.UIKit.UIModalPresentationFullScreen
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
private class QrScannerViewController(
  private val onFinished: (String?) -> Unit,
) : UIViewController(nibName = null, bundle = null), AVCaptureMetadataOutputObjectsDelegateProtocol {

  private var captureSession: AVCaptureSession? = null
  private var previewLayer: AVCaptureVideoPreviewLayer? = null
  private var closeButton: UIButton? = null
  private var didFinish = false

  override fun viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = UIColor.blackColor

    val session = AVCaptureSession()
    captureSession = session

    val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
    if (device == null) {
      AppLog.w("DTV-QrScan", "no video device")
      finishWith(null)
      return
    }

    val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
    if (input == null) {
      finishWith(null)
      return
    }
    if (session.canAddInput(input)) {
      session.addInput(input)
    }

    val output = AVCaptureMetadataOutput()
    if (session.canAddOutput(output)) {
      session.addOutput(output)
      output.setMetadataObjectsDelegate(this, queue = dispatch_get_main_queue())
      output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
    }

    val preview = AVCaptureVideoPreviewLayer(session = session)
    preview.videoGravity = AVLayerVideoGravityResizeAspectFill
    preview.frame = view.bounds
    view.layer.addSublayer(preview)
    previewLayer = preview

    val btn = UIButton.buttonWithType(UIButtonTypeSystem) as UIButton
    btn.setTitle("关闭", forState = UIControlStateNormal)
    btn.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
    btn.addTarget(this, action = NSSelectorFromString("closeTapped"), forControlEvents = UIControlEventTouchUpInside)
    view.addSubview(btn)
    closeButton = btn

    session.startRunning()
  }

  override fun viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    previewLayer?.frame = view.bounds
    closeButton?.setFrame(CGRectMake(16.0, 44.0, 80.0, 44.0))
  }

  override fun viewWillDisappear(animated: Boolean) {
    super.viewWillDisappear(animated)
    captureSession?.stopRunning()
  }

  @ObjCAction
  fun closeTapped() {
    finishWith(null)
  }

  private fun finishWith(result: String?) {
    if (didFinish) return
    didFinish = true
    captureSession?.stopRunning()
    onFinished(result)
    dismissViewControllerAnimated(true, completion = null)
  }

  override fun captureOutput(
    output: AVCaptureOutput?,
    didOutputMetadataObjects: List<*>?,
    fromConnection: AVCaptureConnection?,
  ) {
    val metadataObj = didOutputMetadataObjects
      ?.firstOrNull() as? AVMetadataMachineReadableCodeObject
      ?: return
    if (metadataObj.type == AVMetadataObjectTypeQRCode) {
      val text = metadataObj.stringValue ?: return
      finishWith(text)
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
  val scene = UIApplication.sharedApplication.connectedScenes
    .mapNotNull { it as? UIWindowScene }
    .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
  var controller = scene?.keyWindow?.rootViewController ?: return null
  while (controller.presentedViewController != null) {
    controller = controller.presentedViewController!!
  }
  return controller
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberQrCodeScanLauncher(
  onResult: (String?) -> Unit,
): () -> Unit {
  val currentCallback = rememberUpdatedState(onResult)
  return remember {
    launcher@{
      // Creating AVCaptureDeviceInput makes the system present the camera
      // permission dialog automatically on first use (requestAccessForMediaType
      // bindings are unavailable on the Xcode 16 SDK).
      val presenter = topViewController()
      if (presenter == null) {
        currentCallback.value(null)
        return@launcher
      }
      val scanner = QrScannerViewController(onFinished = currentCallback.value)
      scanner.modalPresentationStyle = UIModalPresentationFullScreen
      presenter.presentViewController(scanner, animated = true, completion = null)
    }
  }
}
