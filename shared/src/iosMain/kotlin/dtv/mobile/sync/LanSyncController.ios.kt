package dtv.mobile.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dtv.mobile.util.AppLog
import dtv.mobile.util.toByteArray
import dtv.mobile.util.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import platform.Foundation.NSData
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Network.NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT
import platform.Network.nw_advertise_descriptor_create_bonjour_service
import platform.Network.nw_advertise_descriptor_set_txt_record
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_copy_endpoint
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_start
import platform.Network.nw_endpoint_copy_address_string
import platform.Network.nw_endpoint_get_type
import platform.Network.nw_endpoint_type_address
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create_with_port
import platform.Network.nw_listener_set_advertise_descriptor
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_connection_t
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_t
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.memset
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.uname
import platform.posix.utsname
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val NSD_SERVICE_TYPE = "_dtv-lan-sync._tcp."
private const val MAX_REQUEST_BYTES = 64 * 1024

/** htons() is not exposed by Kotlin/Native's platform.posix on iOS. */
private fun htonsPort(port: Int): UShort =
  (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()

private val serverJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

private fun buildManifest(payload: LanSyncPayload): LanSyncManifest {
  val entries = payload.entries
  val summary = LanSyncSummary(
    followedStreamers = countJsonArray(entries["followedStreamers"]),
    followFolders = countJsonArray(entries["followFolders"]),
    followListOrder = countJsonArray(entries["followListOrder"]),
    customCategories = countJsonArray(entries["dtv_custom_categories_v1"]),
    totalBytes = entries.entries.sumOf { (k, v) -> k.length + v.length },
  )
  return LanSyncManifest(
    kind = payload.kind,
    version = payload.version,
    exportedAt = payload.exportedAt,
    source = payload.source,
    summary = summary,
  )
}

private fun countJsonArray(raw: String?): Int {
  if (raw.isNullOrBlank()) return 0
  return runCatching {
    (serverJson.parseToJsonElement(raw) as? JsonArray)?.size ?: 0
  }.getOrElse { 0 }
}

private fun isPrivateIPv4(ip: String): Boolean {
  val parts = ip.split(".")
  if (parts.size != 4) return false
  val nums = parts.map { it.toIntOrNull() ?: return false }
  if (nums.any { it !in 0..255 }) return false
  val b0 = nums[0]
  val b1 = nums[1]
  if (b0 == 10) return true
  if (b0 == 172 && b1 in 16..31) return true
  if (b0 == 192 && b1 == 168) return true
  if (b0 == 169 && b1 == 254) return true
  return false
}

private fun stripPort(addressOrHost: String): String {
  // IPv6 form [::1]:1234
  if (addressOrHost.startsWith("[")) {
    return addressOrHost.substringAfter("[").substringBefore("]")
  }
  return addressOrHost.substringBefore(":")
}

/** Formats an IPv4 address held in network byte order (sin_addr.s_addr). */
private fun formatIPv4(sAddr: UInt): String {
  val a = (sAddr and 0xFFu).toInt()
  val b = ((sAddr shr 8) and 0xFFu).toInt()
  val c = ((sAddr shr 16) and 0xFFu).toInt()
  val d = ((sAddr shr 24) and 0xFFu).toInt()
  return "$a.$b.$c.$d"
}

@OptIn(ExperimentalForeignApi::class)
private fun localIPv4Addresses(): List<String> {
  // A UDP connect() sends no packets; the kernel only selects the source
  // address that would route toward the target. getifaddrs is not exposed
  // by Kotlin/Native's platform.posix on iOS.
  return memScoped {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return@memScoped emptyList()
    try {
      val remote = alloc<sockaddr_in>()
      memset(remote.ptr, 0, sizeOf<sockaddr_in>().convert())
      remote.sin_len = sizeOf<sockaddr_in>().toUByte()
      remote.sin_family = AF_INET.toUByte()
      remote.sin_port = htonsPort(53)
      // 8.8.8.8 in network byte order; every byte is 0x08 so host order
      // does not matter for this particular constant.
      remote.sin_addr.s_addr = 0x08080808u
      if (connect(fd, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        return@memScoped emptyList()
      }
      val local = alloc<sockaddr_in>()
      memset(local.ptr, 0, sizeOf<sockaddr_in>().convert())
      val lenVar = alloc<UIntVar>()
      lenVar.value = sizeOf<sockaddr_in>().convert()
      if (getsockname(fd, local.ptr.reinterpret(), lenVar.ptr.reinterpret()) != 0) {
        return@memScoped emptyList()
      }
      val ip = formatIPv4(local.sin_addr.s_addr)
      if (isPrivateIPv4(ip)) listOf(ip) else emptyList()
    } finally {
      close(fd)
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun deviceModel(): String {
  return memScoped {
    val u = alloc<utsname>()
    if (uname(u.ptr) == 0) u.machine.toKString() else "ios"
  }
}

private fun sanitizeModel(raw: String): String =
  raw.lowercase()
    .map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-' }
    .joinToString("")

private fun buildTxtRecord(token: String): ByteArray {
  val parts = listOf(
    "kind" to LAN_SYNC_KIND,
    "ver" to LAN_SYNC_VERSION.toString(),
    "path" to LAN_SYNC_HTTP_PATH,
    "token" to token,
  )
  val out = ArrayList<Byte>(160)
  for ((k, v) in parts) {
    val chunk = "$k=$v".encodeToByteArray()
    if (chunk.size > 255) continue
    out.add(chunk.size.toByte())
    chunk.forEach { out.add(it) }
  }
  return out.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun ipv4FromSockaddrData(data: NSData): String? = memScoped {
  val length = data.length.toLong()
  if (length < 8L) return@memScoped null
  val raw = data.bytes ?: return@memScoped null
  val saPtr = raw.reinterpret<platform.posix.sockaddr>()
  if (saPtr.pointed.sa_family.toInt() != AF_INET) return@memScoped null
  val sin = saPtr.reinterpret<sockaddr_in>().pointed
  formatIPv4(sin.sin_addr.s_addr)
}

@OptIn(ExperimentalForeignApi::class)
private class IosLanServer(
  private val payload: LanSyncPayload,
  private val token: String,
) {
  private var listener: nw_listener_t? = null
  private val payloadBytes = serverJson.encodeToString(LanSyncPayload.serializer(), payload).encodeToByteArray()
  private val manifestBytes = serverJson.encodeToString(LanSyncManifest.serializer(), buildManifest(payload)).encodeToByteArray()

  @OptIn(BetaInteropApi::class)
  fun start(): LanSyncServerInfo {
    val parameters = nw_parameters_create_secure_tcp(null, null)
    val l = nw_listener_create_with_port(LAN_SYNC_FIXED_PORT.toString(), parameters)
      ?: error("无法监听端口 ${LAN_SYNC_FIXED_PORT}")
    listener = l

    val model = sanitizeModel(deviceModel().ifBlank { "ios" })
    val instanceName = "dtv-sync-$model-${LAN_SYNC_FIXED_PORT}"
    val descriptor = nw_advertise_descriptor_create_bonjour_service(instanceName, NSD_SERVICE_TYPE, null)
    if (descriptor != null) {
      val txt = buildTxtRecord(token)
      txt.usePinned { pinned ->
        nw_advertise_descriptor_set_txt_record(descriptor, pinned.addressOf(0), txt.size.toULong())
      }
      nw_listener_set_advertise_descriptor(l, descriptor)
    }

    nw_listener_set_queue(l, dispatch_get_main_queue())
    nw_listener_set_state_changed_handler(l) { state, error ->
      if (state == nw_listener_state_failed) {
        AppLog.w("DTV-LanSync", "listener failed state=$state error=$error")
      }
    }
    nw_listener_set_new_connection_handler(l) { conn ->
      if (conn != null) handleConnection(conn)
    }
    nw_listener_start(l)
    val hosts = listOf("127.0.0.1", "localhost") + localIPv4Addresses()
    return LanSyncServerInfo(port = LAN_SYNC_FIXED_PORT, hosts = hosts.distinct(), token = token)
  }

  fun stop() {
    listener?.let { nw_listener_cancel(it) }
    listener = null
  }

  private fun remoteIp(connection: nw_connection_t): String? {
    val endpoint = nw_connection_copy_endpoint(connection) ?: return null
    val type = nw_endpoint_get_type(endpoint).toInt()
    if (type == nw_endpoint_type_address.toInt()) {
      val cstr = nw_endpoint_copy_address_string(endpoint) ?: return null
      val ip = stripPort(cstr.toKString())
      if (ip.isNotBlank()) return ip
    }
    return null
  }

  private fun handleConnection(connection: nw_connection_t) {
    nw_connection_set_queue(connection, dispatch_get_main_queue())
    nw_connection_start(connection)
    val accumulator = ArrayList<Byte>(512)
    receiveLoop(connection, accumulator)
  }

  private fun receiveLoop(connection: nw_connection_t, accumulator: ArrayList<Byte>) {
    nw_connection_receive(connection, 1u.convert(), MAX_REQUEST_BYTES.toUInt()) { content, _, isComplete, recvError ->
      if (recvError != null) {
        nw_connection_cancel(connection)
        return@nw_connection_receive
      }
      if (content != null) {
        runCatching {
          val nsData = content as? NSData
          if (nsData != null) {
            val bytes = nsData.toByteArray()
            bytes.forEach { accumulator.add(it) }
          }
        }
      }

      val raw = accumulator.toByteArray()
      val headerEnd = findHeaderEnd(raw)
      if (headerEnd >= 0) {
        respond(connection, raw.copyOfRange(0, headerEnd))
        return@nw_connection_receive
      }
      if (isComplete || accumulator.size >= MAX_REQUEST_BYTES) {
        sendSimple(connection, 400, "Bad Request")
        return@nw_connection_receive
      }
      receiveLoop(connection, accumulator)
    }
  }

  private fun findHeaderEnd(raw: ByteArray): Int {
    for (i in 0..raw.size - 4) {
      if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() &&
        raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()
      ) {
        return i
      }
    }
    return -1
  }

  private fun respond(connection: nw_connection_t, headerBytes: ByteArray) {
    val headerText = headerBytes.decodeToString()
    val lines = headerText.split("\r\n")
    val requestLine = lines.firstOrNull().orEmpty()
    val parts = requestLine.split(" ")
    if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) {
      sendSimple(connection, 405, "Method Not Allowed")
      return
    }
    val target = parts[1]
    val path = target.substringBefore("?")
    val query = target.substringAfter("?", "")
    val params = query.split("&").filter { it.isNotBlank() }
      .associate {
        val eq = it.indexOf("=")
        if (eq >= 0) it.substring(0, eq) to it.substring(eq + 1) else it to ""
      }

    if (params["token"] != token) {
      sendSimple(connection, 401, "Unauthorized.")
      return
    }
    val ip = remoteIp(connection)
    if (ip == null || (!isPrivateIPv4(ip) && ip != "::1" && ip != "127.0.0.1")) {
      sendSimple(connection, 403, "Forbidden: non-private network.")
      return
    }

    when (path) {
      LAN_SYNC_HTTP_PATH -> sendJson(connection, manifestBytes)
      LAN_SYNC_PAYLOAD_PATH -> sendJson(connection, payloadBytes)
      else -> sendSimple(connection, 404, "Not Found")
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun sendJson(connection: nw_connection_t, body: ByteArray) {
    val response = buildHttpResponse(200, "OK", "application/json; charset=utf-8", body)
    sendResponseBytes(connection, response)
  }

  private fun sendSimple(connection: nw_connection_t, status: Int, text: String) {
    val body = text.encodeToByteArray()
    val response = buildHttpResponse(status, text, "text/plain; charset=utf-8", body)
    sendResponseBytes(connection, response)
  }

  private fun buildHttpResponse(status: Int, reason: String, contentType: String, body: ByteArray): ByteArray {
    val header = buildString {
      append("HTTP/1.1 $status $reason\r\n")
      append("Content-Type: $contentType\r\n")
      append("Content-Length: ${body.size}\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }.encodeToByteArray()
    return header + body
  }

  private fun sendResponseBytes(connection: nw_connection_t, bytes: ByteArray) {
    // NSData owns a copy of the bytes and toll-free bridges to dispatch_data_t,
    // so Network.framework can read it asynchronously after this call returns.
    @Suppress("USELESS_CAST")
    val data = bytes.toNSData() as platform.darwin.dispatch_data_t
    nw_connection_send(
      connection,
      data,
      NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT,
      true,
    ) { sendError ->
      if (sendError != null) {
        AppLog.w("DTV-LanSync", "send failed")
      }
      nw_connection_cancel(connection)
    }
  }
}

private object IosLanSyncRuntime {
  private var server: IosLanServer? = null
  private var info: LanSyncServerInfo? = null

  fun start(payload: LanSyncPayload): LanSyncServerInfo {
    stop()
    val s = IosLanServer(payload = payload, token = LAN_SYNC_DEFAULT_TOKEN)
    val i = s.start()
    server = s
    info = i
    return i
  }

  fun stop() {
    server?.stop()
    server = null
    info = null
  }

  fun status(): LanSyncServerInfo? = info
}

@OptIn(ExperimentalForeignApi::class)
private class IosLanSyncController : LanSyncController {

  override suspend fun discoverPeers(timeoutMs: Long): List<LanSyncDiscoveredPeer> =
    withContext(Dispatchers.Main) { discoverSuspend(timeoutMs.coerceIn(300, 5000)) }

  override suspend fun startServer(payload: LanSyncPayload): LanSyncServerInfo =
    withContext(Dispatchers.Main) {
      IosLanSyncRuntime.start(payload)
    }

  override suspend fun stopServer() = withContext(Dispatchers.Main) {
    IosLanSyncRuntime.stop()
  }

  override suspend fun serverStatus(): LanSyncServerInfo? = withContext(Dispatchers.Main) {
    IosLanSyncRuntime.status()
  }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class DtvServiceBrowser : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {
  private val peers = LinkedHashMap<String, LanSyncDiscoveredPeer>()
  private val resolving = LinkedHashMap<String, NSNetService>()
  private var browser: NSNetServiceBrowser? = null
  private var onComplete: ((List<LanSyncDiscoveredPeer>) -> Unit)? = null
  private var finished = false

  fun start(timeoutMs: Long, done: (List<LanSyncDiscoveredPeer>) -> Unit) {
    onComplete = done
    val b = NSNetServiceBrowser()
    browser = b
    b.delegate = this
    b.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
    b.searchForServicesOfType(NSD_SERVICE_TYPE, inDomain = "")

    dispatch_after(
      dispatch_time(DISPATCH_TIME_NOW, timeoutMs * 1_000_000L),
      dispatch_get_main_queue(),
    ) {
      finishDiscovery()
    }
  }

  fun cancel() {
    resolving.values.forEach { runCatching { it.stop() } }
    resolving.clear()
    runCatching { browser?.stop() }
    runCatching {
      browser?.removeFromRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
    }
    browser = null
    if (!finished) {
      finished = true
      onComplete?.invoke(peers.values.sortedBy { it.name })
      onComplete = null
    }
  }

  fun finishDiscovery() {
    cancel()
  }

  override fun netServiceBrowser(
    browser: NSNetServiceBrowser,
    didFindService: NSNetService,
    moreComing: Boolean,
  ) {
    val name = didFindService.name ?: return
    if (resolving.containsKey(name) || peers.containsKey(name)) return
    resolving[name] = didFindService
    didFindService.delegate = this
    didFindService.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
    didFindService.resolveWithTimeout(2.0)
  }

  override fun netServiceDidResolveAddress(sender: NSNetService) {
    val name = sender.name ?: return
    val port = sender.port.toInt()
    if (port <= 0) return

    val txt = runCatching { sender.TXTRecordData() }.getOrNull()
    val attrs: Map<*, *> = if (txt != null) {
      runCatching { NSNetService.dictionaryFromTXTRecordData(txt) }.getOrNull() ?: emptyMap()
    } else {
      emptyMap()
    }
    fun attr(key: String): String? {
      val data = attrs[key] as? NSData ?: return null
      // Bonjour TXT record values are UTF-8 bytes.
      return runCatching { data.toByteArray().decodeToString() }.getOrNull()
    }
    val kind = attr("kind")
    val path = attr("path")
    if (kind != LAN_SYNC_KIND || path != LAN_SYNC_HTTP_PATH) {
      resolving.remove(name)
      return
    }
    val token = attr("token")

    val ip = (sender.addresses as? List<*>)
      ?.asSequence()
      ?.mapNotNull { it as? NSData }
      ?.mapNotNull { ipv4FromSockaddrData(it) }
      ?.firstOrNull { isPrivateIPv4(it) }
    if (ip == null) return

    val peer = LanSyncDiscoveredPeer(
      name = name,
      host = ip,
      port = port,
      token = token,
      baseUrl = "http://$ip:$port",
    )
    peers[name] = peer
    resolving.remove(name)
    runCatching { sender.stop() }
  }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun discoverSuspend(timeoutMs: Long): List<LanSyncDiscoveredPeer> =
  suspendCancellableCoroutine { cont ->
    val browserDelegate = DtvServiceBrowser()
    browserDelegate.start(timeoutMs = timeoutMs) { peers ->
      if (cont.isActive) cont.resume(peers)
    }
    cont.invokeOnCancellation {
      runCatching { browserDelegate.cancel() }
    }
  }

@Composable
actual fun rememberLanSyncController(): LanSyncController {
  return remember { IosLanSyncController() }
}
