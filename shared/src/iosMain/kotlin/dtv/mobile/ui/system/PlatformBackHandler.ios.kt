package dtv.mobile.ui.system

import androidx.compose.runtime.Composable

// iOS has no global system back gesture that app code can intercept;
// in-screen back buttons are provided by the shared UI.
@Composable
actual fun PlatformBackHandler(
  enabled: Boolean,
  onBack: () -> Unit,
) = Unit
