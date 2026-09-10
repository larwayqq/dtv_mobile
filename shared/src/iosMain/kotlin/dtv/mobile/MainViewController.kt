package dtv.mobile

import androidx.compose.ui.window.ComposeUIViewController
import dtv.mobile.repo.ios.IOSDtvRepository
import dtv.mobile.state.SubscriptionStoreIos
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
  App(
    repo = IOSDtvRepository(),
    subscriptionStore = SubscriptionStoreIos(),
  )
}
