package dtv.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dtv.mobile.util.Diagnostics

@Composable
fun DiagnosticOverlay(modifier: Modifier = Modifier) {
  val crash = Diagnostics.lastCrashReport
  val errors = Diagnostics.recentErrors
  var showCrash by remember { mutableStateOf(false) }
  var showErrors by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxWidth()) {
    if (crash != null) {
      Banner(
        backgroundColor = Color(0xFFB3261E),
        text = "检测到上次崩溃，点此查看/反馈",
        onClick = { showCrash = true },
        onClose = { Diagnostics.clearCrash() },
      )
    }
    if (errors.isNotEmpty()) {
      val last = errors.last()
      Banner(
        backgroundColor = Color(0xFF8A6D00),
        text = "诊断: ${last.where} - ${last.message.take(80)}",
        onClick = { showErrors = true },
        onClose = { Diagnostics.clearErrors() },
      )
    }
  }

  if (showCrash && crash != null) {
    InfoDialog(
      title = "崩溃信息（可截图反馈）",
      body = crash,
      onDismiss = { showCrash = false },
      onClear = {
        Diagnostics.clearCrash()
        showCrash = false
      },
      clearText = "不再提示",
    )
  }

  if (showErrors && errors.isNotEmpty()) {
    InfoDialog(
      title = "最近的加载错误（${errors.size}）",
      body = errors.asReversed().joinToString("\n\n") { "[${it.where}] ${it.message}" },
      onDismiss = { showErrors = false },
      onClear = {
        Diagnostics.clearErrors()
        showErrors = false
      },
      clearText = "清空",
    )
  }
}

@Composable
private fun Banner(
  backgroundColor: Color,
  text: String,
  onClick: () -> Unit,
  onClose: () -> Unit,
) {
  Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
    Surface(
      color = backgroundColor,
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(Icons.Default.BugReport, contentDescription = null, tint = Color.White)
        Text(
          text = text,
          color = Color.White,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          modifier = Modifier.weight(1f),
        )
        Icon(
          Icons.Default.Close,
          contentDescription = "关闭",
          tint = Color.White,
          modifier = Modifier.clickable(onClick = onClose),
        )
      }
    }
  }
}

@Composable
private fun InfoDialog(
  title: String,
  body: String,
  onDismiss: () -> Unit,
  onClear: () -> Unit,
  clearText: String,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(
        modifier = Modifier
          .heightIn(max = 440.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        Text(
          text = body,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("关闭") }
    },
    dismissButton = {
      TextButton(onClick = onClear) { Text(clearText) }
    },
  )
}
