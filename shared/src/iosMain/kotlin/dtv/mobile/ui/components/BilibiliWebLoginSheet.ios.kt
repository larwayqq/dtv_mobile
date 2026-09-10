package dtv.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import dtv.mobile.util.AppLog
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSObject
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.WKWebsiteDataStore
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.dispatch.dispatch_async
import platform.dispatch.dispatch_get_main_queue

private const val BILIBILI_LOGIN_URL = "https://passport.bilibili.com/login"
private const val IOS_UA_WITH_SUFFIX =
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
    "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1 DTV-Mobile"

private fun hasRequiredBilibiliCookies(cookieHeader: String?): Boolean {
  val raw = cookieHeader?.lowercase().orEmpty()
  return raw.contains("sessdata=") && raw.contains("bili_jct=")
}

@OptIn(ExperimentalForeignApi::class)
private fun probeCookies(onResult: (String?) -> Unit) {
  WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { cookies ->
    val header = (cookies as? List<NSHTTPCookie>)
      ?.filter { it.domain.contains("bilibili") }
      ?.joinToString("; ") { "${it.name}=${it.value}" }
      ?.takeIf { it.isNotBlank() }
    val merged = if (hasRequiredBilibiliCookies(header)) header else null
    dispatch_async(dispatch_get_main_queue()) { onResult(merged) }
  }
}

private class BilibiliNavigationDelegate(
  private val onPageFinished: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
  override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
    onPageFinished()
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalForeignApi::class)
@Composable
actual fun BilibiliWebLoginSheet(
  onDismissRequest: () -> Unit,
  onCookieCaptured: (cookieHeader: String) -> Unit,
) {
  var status by remember { mutableStateOf("请在网页登录 B站 账号") }
  var lastCookie by remember { mutableStateOf<String?>(null) }

  var webViewRef by remember { mutableStateOf<WKWebView?>(null) }
  val navigationDelegate = remember {
    BilibiliNavigationDelegate(
      onPageFinished = {
        probeCookies { merged ->
          if (merged != null && merged != lastCookie) {
            lastCookie = merged
            status = "已获取登录 Cookie"
            onCookieCaptured(merged)
            onDismissRequest()
          }
        }
      },
    )
  }

  fun probeManually() {
    probeCookies { merged ->
      if (merged != null) {
        lastCookie = merged
        status = "已获取登录 Cookie"
        onCookieCaptured(merged)
        onDismissRequest()
      } else {
        status = "尚未检测到登录状态，请先完成网页登录"
      }
    }
  }

  ModalBottomSheet(onDismissRequest = onDismissRequest) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text("B站网页登录", style = MaterialTheme.typography.titleMedium)
      Text(
        status,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      )

      UIKitView(
        factory = { container ->
          val config = WKWebViewConfiguration()
          val webView = WKWebView(frame = container.bounds, configuration = config).apply {
            customUserAgent = IOS_UA_WITH_SUFFIX
            navigationDelegate = navigationDelegate
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
          }
          runCatching {
            webView.loadRequest(NSURLRequest.requestWithURL(NSURL(string = BILIBILI_LOGIN_URL)))
          }.onFailure { AppLog.w("DTV-BiliLogin", "load failed: ${it.message}") }
          webViewRef = webView
          webView
        },
        modifier = Modifier
          .fillMaxWidth()
          .height(520.dp),
        onRelease = { webView ->
          webView.stopLoading()
          webView.navigationDelegate = null
        },
      )

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onDismissRequest) { Text("关闭") }
        TextButton(onClick = { probeManually() }) { Text("完成") }
      }
      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}
