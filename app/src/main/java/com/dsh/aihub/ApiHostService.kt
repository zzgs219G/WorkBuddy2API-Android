package com.dsh.aihub

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApiHostService : Service() {
    /**
     * 有界工作线程池：原来用 newCachedThreadPool 无上限，突发的慢连接会瞬间堆出大量线程
     * （每线程约 1MB 栈），常驻服务被 LMK 优先回收——与保活目标直接冲突。
     * 上限 16 线程 + 64 队列已远超单机自用场景的实际并发，溢出时直接拒掉连接而不是拖垮进程。
     */
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        2, 16, 60L, TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(64),
        Executors.defaultThreadFactory(),
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )
    private val maintenance = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var server: ServerSocket? = null
    // 常驻部分唤醒锁：Cached App Freezer 会跳过持有 wakelock 的进程（官方豁免行为），
    // 没有它，APP 退后台后会被 MIUI/原生冻结器冻结，本地反代端口仍在监听但进程不调度、请求全部挂起。
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, notification("正在启动原生 API…"))
        scheduleWatchdog()
        workers.execute { serve() }
        maintenance.scheduleWithFixedDelay({ runMaintenanceTick() }, 5, 60, TimeUnit.SECONDS)
        // 悬浮窗：可选加固项，提升进程到 VISIBLE 档，降低内存吃紧时被 LMK 回收的概率。
        // 未授权/未开启时内部自行跳过，不会影响服务本体。
        FloatingWindowKeeper.show(this, PORT)
    }

    /**
     * 看门狗：定时用 AlarmManager 检查服务是否还活着。
     * 一加/OPPO 的 ColorOS 会在锁屏、长时间待机或内存回收时直接干掉前台服务，
     * 且会无视 START_STICKY 不重启——只有 AlarmManager 的 setExactAndAllowWhileIdle
     * 能在 Doze 下把进程叫醒。每次唤醒时若服务已不在，就重新拉起来。
     * 用 15 分钟间隔：更频繁会被 ColorOS 判定为异常唤醒而拦截，反而更不可靠。
     */
    private fun scheduleWatchdog() {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = watchdogIntent()
            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            // Android 12+ 即使用户授权过，也可能事后在设置里关掉"闹钟与提醒"，此时
            // setExactAndAllowWhileIdle 直接抛 SecurityException。先预检再决定用哪种，
            // 避免"精确失败→静默退化"，也便于在日志里暴露这一降级（Doze 下非精确可能延迟数小时）。
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (canExact) {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
            } else {
                Log.w(TAG, "exact alarm not permitted; watchdog falls back to inexact (may be delayed in Doze)")
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            lastWatchdogArmAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "watchdog scheduled in ${WATCHDOG_INTERVAL_MS / 60_000} min (exact=$canExact)")
        }.onFailure { Log.w(TAG, "watchdog schedule failed", it) }
    }

    private fun watchdogIntent(): PendingIntent = PendingIntent.getService(
        this, WATCHDOG_REQUEST,
        Intent(this, ApiHostService::class.java).setAction(ACTION_WATCHDOG),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * 用户从最近任务里划掉 App。ColorOS 默认会在这一刻连带杀掉前台服务；
     * stopWithTask="false" 只能挡住"随任务栈销毁"，挡不住系统主动清理。
     *
     * Android 12+ 禁止后台启动前台服务（ForegroundServiceStartNotAllowedException），
     * 这里直接调 startForegroundService 很可能被拦，"只打日志"等于划掉后永不恢复。
     * 因此失败时立刻挂一个 5 秒后的精确闹钟——闹钟唤醒属于系统回调，不受该限制，
     * 能拿到一次宝贵的"前台服务启动配额"把服务拉回来。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户主动停止过就不要再拉起，否则会出现"关了又自己开"。
        if (!shouldRun(applicationContext)) {
            Log.i(TAG, "task removed but user stopped service, skip restart")
            super.onTaskRemoved(rootIntent)
            return
        }
        Log.i(TAG, "task removed, restarting service")
        val restart = Intent(applicationContext, ApiHostService::class.java)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart) else startService(restart)
        }.isSuccess
        if (!started) {
            // 被后台启动限制拦截：退化为精确闹钟，稍后由系统唤起服务。
            Log.w(TAG, "direct restart blocked, falling back to alarm")
            runCatching {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getService(
                    this, WATCHDOG_REQUEST + 1,
                    Intent(this, ApiHostService::class.java).setAction(ACTION_WATCHDOG),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val at = SystemClock.elapsedRealtime() + 5_000L
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            }.onFailure { Log.w(TAG, "fallback alarm failed", it) }
        }
        scheduleWatchdog()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 获取部分唤醒锁（不计数模式，服务停止时统一释放）。
     *
     * 带 30 分钟超时而不是无限期持有：长时间死锁会被 ROM 的省电策略判为异常耗电目标，
     * 反而优先清理，与保活初衷相反。
     *
     * 续期来源是**看门狗（AlarmManager）而不是 maintenance tick** —— 后者是
     * ScheduledExecutor，在 Doze 下没有 CPU 时间片、根本不会调度，靠它续期等于不续。
     * 见 WAKELOCK_TIMEOUT_MS 的注释。
     */
    private fun acquireWakeLock() {
        // 已持有且未过期就跳过；带超时的锁过期后 isHeld 会变回 false，下次 tick 自动续上。
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:wakelock").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            Log.i(TAG, "wake lock acquired (freeze exemption active)")
        }.onFailure { Log.w(TAG, "wake lock acquire failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNow()
            return START_NOT_STICKY
        }
        // 只要服务被启动（无论来自用户点按、开机自启还是看门狗兜底），就记为"用户希望它运行",
        // 这样进程被杀后系统/闹钟再次拉起时，onTaskRemoved 的前置校验才会放行。
        runCatching { markShouldRun(applicationContext, true) }
        // 被看门狗闹钟唤醒时，服务可能刚被系统重建：确认前台通知与唤醒锁都在。
        runCatching {
            if (intent?.action == ACTION_WATCHDOG) Log.i(TAG, "watchdog fired, ensuring foreground")
            acquireWakeLock()
            startForeground(NOTIFICATION_ID, notification(if (running) "原生 API 运行中 · 端口 $PORT" else "正在启动原生 API…"))
        }.onFailure { Log.w(TAG, "foreground ensure failed", it) }
        // 每次进入都续期看门狗，防止闹钟一次性触发后再无兜底。
        scheduleWatchdog()
        // 记录本次启动时刻：maintenance tick 用它与当前时间比较，
        // 判断"闹钟是否已经错过"（Doze 期间闹钟可能被丢弃而不触发）。
        lastWatchdogArmAt = SystemClock.elapsedRealtime()
        return START_STICKY
    }

    /**
     * 决定监听地址：默认**只绑回环**（127.0.0.1）。
     *
     * 为什么要改掉原来的 0.0.0.0：
     *   绑 0.0.0.0 会让同一 WiFi 下的任何设备都能访问这个 API。虽然每个请求都要过
     *   API key 校验，但把攻击面从「本机进程」扩大到「同网段所有人」并不划算——
     *   本机之外没有任何合法调用方（客户端就是手机上的其他 App）。局域网嗅探、
     *   key 爆破、误配转发都会因此变成一个真实风险，而收益为零。
     *
     * 需要跨设备调用时，在设置里显式打开「允许局域网访问」（settings.lanAccess=true）。
     * 这是一个 opt-in，默认关闭。
     */
    private fun resolveBindHost(): String {
        // 设置值统一以字符串存储（NativeStore 的 settings 表 value 列是 TEXT），
        // 且 UI 侧写的是 "1"/"0"，所以这里按字符串比对而不是 optBoolean。
        val allowLan = runCatching {
            NativeCore.settings(this).optString(SETTING_LAN_ACCESS, "0") in LAN_ON
        }.getOrDefault(false)
        return if (allowLan) LAN_ANY else LOOPBACK
    }

    private fun serve() {
        try {
            val bindHost = resolveBindHost()
            val socket = ServerSocket(PORT, 32, InetAddress.getByName(bindHost))
            server = socket
            running = true
            val scope = if (bindHost == LOOPBACK) "仅本机" else "本机与局域网"
            notifyState("原生 API 运行中 · $bindHost:$PORT（$scope）")
            Log.i(TAG, "listening on $bindHost:$PORT (scope=$scope)")
            while (!socket.isClosed) {
                val client = socket.accept()
                // 线程池/队列已满时必须单独接住：否则拒绝异常会冒泡到外层 catch，
                // 直接终止整个 accept 循环（服务表面在跑，实际再收不到任何请求）。
                runCatching {
                    workers.execute {
                        client.use { socket ->
                            runCatching { handle(socket) }.onFailure { Log.w(TAG, "request failed", it) }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "worker pool saturated, dropping connection", it)
                    runCatching { client.close() }
                }
            }
        } catch (e: Exception) {
            if (running) notifyState("API 异常：${e.message}")
        } finally {
            running = false
        }
    }

    /**
     * 从请求头提取会话标识，供会话粘性选号使用。
     *
     * 按优先级取：
     *   1. X-Conversation-ID —— 官方 CLI 用它，最准
     *   2. X-Session-ID / Session-Id —— 常见客户端约定
     *   3. Authorization 的 key 前缀 —— 同一 API key 视为同一会话的粗粒度兜底
     *
     * 都取不到返回 null，退化为普通加权选号（行为与引入粘性前一致）。
     */
    private fun sessionKeyFor(headers: Map<String, String>): String? {
        headers["x-conversation-id"]?.takeIf { it.isNotBlank() }?.let { return it }
        headers["x-session-id"]?.takeIf { it.isNotBlank() }?.let { return it }
        headers["session-id"]?.takeIf { it.isNotBlank() }?.let { return it }
        // 兜底：按 API key 分组。注意这是「每个客户端固定一个账号」而非真正的会话级，
        // 粒度更粗，但比完全没有粘性好——至少同一个客户端的连续对话不会跳号。
        headers["authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }?.let {
            return "key:" + Integer.toHexString(it.hashCode())
        }
        return null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 300_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1].substringBefore('?')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        if (method == "OPTIONS") {
            writeHeaders(output, 204, "text/plain", 0)
            return
        }
        val declaredLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (declaredLength < 0 || declaredLength > MAX_BODY_BYTES) {
            json(output, 413, apiError("请求体过大", "invalid_request_error"))
            return
        }
        val bodyBytes = ByteArray(declaredLength.toInt())
        var offset = 0
        while (offset < bodyBytes.size) {
            val count = input.read(bodyBytes, offset, bodyBytes.size - offset)
            if (count < 0) throw EOFException("请求体提前结束")
            offset += count
        }
        val bodyText = String(bodyBytes, StandardCharsets.UTF_8)
        val startedAt = System.currentTimeMillis()

        // 入站可信性检查（Host / Origin）。
        //
        // 认证密钥挡不住 DNS rebinding：恶意网页可以让自己的域名解析到 127.0.0.1，
        // 从而"合法地"带着 Origin 调用本接口。Host/Origin 是唯一不依赖密钥的判据。
        // 具体判定规则与理由见 RequestGuard 的类注释。
        //
        // 放在鉴权之前：被拒绝的跨站请求不应该消耗一次密钥校验，
        // 也不该被记成"用户 Key 配错"。
        run {
            // 严格模式（默认）：校验 Host 与 Origin。
            // 可在设置里关闭 —— 某些 Electron 客户端（如 Cherry Studio 的内置
            // 网页面板）会用 file:// 或自身域名作为 Origin，严格校验会拒绝它们。
            // 关闭后只跳过 Origin 检查，Host 仍校验（rebinding 主要靠 Host 拦）。
            val relaxed = NativeCore.settings(this).optString("relax_origin", "0") in setOf("1", "true")
            val verdict = if (relaxed) {
                RequestGuard.checkHost(headers["host"].orEmpty(), LanAddress.list().map { it.address })
            } else {
                RequestGuard.check(headers, LanAddress.list().map { it.address })
            }
            if (verdict is RequestGuard.Verdict.Deny) {
                Log.w(TAG, "rejected ${method} ${path}: ${verdict.reason}")
                json(output, 403, apiError(verdict.reason, "forbidden_origin"))
                return
            }
        }

        val selectedApp = authorizedApp(headers)
        try {
            when {
                method == "GET" && path == "/health" -> json(output, 200, JSONObject().put("status", "ok").put("native", true)
                    .put("account", NativeCore.loadAccount(this)?.uid ?: JSONObject.NULL))
                selectedApp == null -> {
                    // 鉴权失败的请求同样留痕：否则客户端 Key 配错或应用被停用时，
                    // 记录页会永远空白，用户完全无法从记录里看到"请求其实来过并被拒绝"。
                    if (path.startsWith("/v1/")) {
                        logRejected(method, path, startedAt, "无效或缺失 API Key（应用不存在、已停用或 Key 不匹配）", null)
                    }
                    json(output, 401, apiError("无效或缺失 API Key", "authentication_error"))
                }
                method == "GET" && path == "/v1/models" -> {
                    val region = AccountRegion.fromStrict(selectedApp.optString("region"))
                    val started = System.currentTimeMillis()
                    val models = NativeCore.models(this, region)
                    Log.i(TAG, "GET /v1/models ${region.id} -> ${models.length()} models in ${System.currentTimeMillis() - started}ms")
                    json(output, 200, JSONObject().put("object", "list").put("region", region.id)
                        .put("data", models))
                }
                method == "POST" && path == "/v1/messages/count_tokens" -> {
                    val payload = parseBody(bodyText)
                    json(output, 200, JSONObject().put("input_tokens", ProtocolAdapters.estimateAnthropicTokens(payload)))
                }
                method == "POST" && path == "/v1/chat/completions" -> {
                    val payload = parseBody(bodyText)
                    if ((payload.optJSONArray("messages")?.length() ?: 0) == 0)
                        throw ClientError("messages is required")
                    proxy(output, socket, payload, Protocol.CHAT, payload.optBoolean("stream", false), selectedApp, headers)
                }
                method == "POST" && path == "/v1/messages" -> {
                    val payload = parseBody(bodyText)
                    proxy(output, socket, ProtocolAdapters.anthropicToChat(payload), Protocol.ANTHROPIC,
                        if (payload.has("stream")) payload.optBoolean("stream") else true, selectedApp, headers)
                }
                method == "POST" && path == "/v1/responses" -> {
                    val payload = parseBody(bodyText)
                    proxy(output, socket, ProtocolAdapters.responsesToChat(payload), Protocol.RESPONSES,
                        if (payload.has("stream")) payload.optBoolean("stream") else true, selectedApp, headers)
                }
                else -> json(output, 404, apiError("未找到端点", "not_found"))
            }
        } catch (e: ClientError) {
            val message = e.message ?: "无效请求"
            // 参数校验发生在转发之前，鉴权已通过，可归因到具体应用，同样需要留痕。
            logRejected(method, path, startedAt, message, selectedApp)
            json(output, 400, apiError(message, "invalid_request_error"))
        } catch (e: JSONException) {
            logRejected(method, path, startedAt, "bad json", selectedApp)
            json(output, 400, apiError("bad json", "invalid_request_error"))
        } catch (e: IOException) {
            // 走到这里说明 proxy 已经无法自救（换号重试也失败）。
            // 记录里留下分类与建议，而不是只留一句异常文本。
            val diagnosis = FailureDiagnosis.fromNetwork(e)
            Log.w(TAG, "gateway IO failure ${diagnosis.kind.wire}: ${diagnosis.summary} ｜ ${diagnosis.action}")
            json(output, 503, apiError("${diagnosis.summary} ｜ ${diagnosis.action}", "upstream_error"))
        } catch (e: Exception) {
            Log.e(TAG, "gateway failure", e)
            json(output, 500, apiError("内部网关错误", "api_error"))
        }
    }

    /**
     * @param reqHeaders 原始请求头，用于提取会话标识做粘性选号。
     *   单独传而不是从别处读，是因为 proxy 运行在独立线程、拿不到 handle 的局部变量。
     */
    private fun proxy(
        output: OutputStream,
        socket: Socket,
        chatBody: JSONObject,
        protocol: Protocol,
        stream: Boolean,
        app: JSONObject,
        reqHeaders: Map<String, String> = emptyMap(),
    ) {
        // 提示词注入 + 第三方客户端指纹清洗（移植自 1.2.7 的 a.I2 / a.C0045h2.Y）。
        // 必须放在 proxy 最前面：所有协议适配器（anthropic/responses → chat）出来的
        // 都是同一份 requestBody，只在这里处理一次就不会漏，也不会重复注入。
        //
        // 注入前先检查降级是否已跨天过期：降级只应持续到触发当天，
        // 过期而未复位会让自定义提示词永久失效（详见 restorePromptIfExpired 注释）。
        runCatching { restorePromptIfExpired(this) }
            .onFailure { Log.w(TAG, "restore prompt state failed", it) }
        runCatching { PromptInjection.apply(chatBody, NativeCore.settings(this)) }
            .onFailure { Log.w(TAG, "prompt pipeline failed; sending original body", it) }
        val model = chatBody.optString("model", "auto")
        val startedAt = System.currentTimeMillis()
        val inputSummary = inputText(chatBody)
        val collector = ChatStreamCollector(model)
        val converter: NativeStreamConverter? = when (protocol) {
            Protocol.ANTHROPIC -> AnthropicStreamConverter(model)
            Protocol.RESPONSES -> ResponsesStreamConverter(model)
            else -> null
        }
        val region = AccountRegion.fromStrict(app.optString("region"))
        var account: NativeCore.Account? = null
        var call: okhttp3.Call? = null
        var response: Response? = null
        var headersSent = false
        var writingClient = false
        try {
            // 选号与 token 刷新同样可能失败（无可用账号 / 凭证过期），必须放进 try 内，
            // 否则异常会直接冒泡成 503，使用记录里完全看不到这次调用。
            //
            // 换号重试（对齐上游 app.py:279-303）：仅对 429/502/503 换一次健康账号，
            // 不递归。顺序严格遵守上游设计——
            //   ① 先冷却刚失败的账号（否则 healthy_count 会把它又算进来）
            //   ② 再数健康账号，<1 则放弃
            //   ③ 换号后必须把 account 指向**实际使用的账号**，usage 记账才归因正确
            // CODE_REVIEW_TODO.md:190-193 明确记录过这个顺序的由来（早期版本只重试不冷却，
            // 结果重试又选中同一个号，等于没重试）。
            var attempt = 0
            while (true) {
                // 会话粘性：拿到本次请求的会话标识，让同一会话固定同一账号。
                // 没有会话头时传 null，退化为普通加权选号（行为与之前一致）。
                val upstream = NativeCore.upstreamRequest(this, chatBody, region, sessionKeyFor(reqHeaders))
                account = upstream.account
                call = NativeCore.http.newCall(upstream.request)
                // 网络层异常单独兜一层：连接被重置/DNS 抖动/复用到死连接时，
                // call.execute() 会直接抛 IOException，绕开下面的 HTTP 状态判断，
                // 于是整个请求就地失败——这是"上游经常断开"最直接的来源。
                // 处理方式与 HTTP 可重试状态一致：冷却当前账号、换一个健康账号重试一次。
                response = try {
                    call.execute()
                } catch (e: IOException) {
                    NativeCore.store(this).markAccountFailure(account.key, NativeCore.cooldownFor(503))
                    if (attempt == 0 && !socket.isClosed) {
                        attempt++
                        val healthy = NativeCore.store(this).countHealthy(region.id)
                        Log.w(TAG, "network error on ${account.uid} (${e.message}); retrying with another account (healthy=$healthy)")
                        if (healthy >= 1) continue
                    }
                    throw e
                }
                if (response.isSuccessful) break

                val raw = response.body?.string().orEmpty()
                val code = response.code
                // 失败必须落痕：此前这里直接 return，429 打完一个标记都不留，
                // 下一个请求照样选中同一个已被限流的账号，形成限流风暴。
                // 取当前 streak（连续失败次数）：markAccountFailure 会把 failure_count+1，
                // 所以这里先读旧值再加 1，作为「这是连续第几次」传给退避计算。
                // 冷却中的探测不翻倍由 streak 冻结保证：成功时 failure_count 清零
                // （markAccountSuccess:228），所以 streak 只在失败链里增长。
                val streak = (NativeCore.store(this).getAccount(account.key)
                    ?.optInt("failure_count") ?: 0) + 1
                NativeCore.store(this).markAccountFailure(
                    account.key,
                    NativeCore.cooldownFor(code, resetAtSeconds = null, streak = streak),
                )
                if (code in RETRYABLE_STATUS && attempt == 0 && !socket.isClosed) {
                    attempt++
                    response.close()
                    val healthy = NativeCore.store(this).countHealthy(region.id)
                    Log.w(TAG, "upstream $code on ${account.uid}; retrying with another account (healthy=$healthy)")
                    if (healthy < 1) {
                        logUsage(protocol, model, startedAt, "error",
                            FailureDiagnosis.toRecordLine(FailureDiagnosis.fromHttp(code, raw)),
                            null, inputSummary, "", "", app, account)
                        json(output, code, safeUpstreamError(raw, code))
                        return
                    }
                    continue
                }
                val errorBody = safeUpstreamError(raw, code)
                // 内容被拦 → 当天切降级提示词（移植自 1.2.7 的 useDegradedPrompt 机制）。
                // 判定口径刻意放宽：上游对这类拦截的措辞不稳定（content blocked /
                // 内容安全 / 11128），逐字匹配必然漏。宁可多触发一次降级（代价只是
                // 当天用一句通用提示词），也不要漏掉导致用户整天不可用。
                maybeDegradePrompt(this@ApiHostService, code, raw)
                // 失败原因落到记录里，而不是只留 "HTTP $code"。
                // 这样「记录」页能看到分类与建议动作，用户不必翻 logcat 才知道
                // 是额度用完（换号）还是上游改版（等更新）。
                val diagnosis = FailureDiagnosis.fromHttp(code, raw)
                Log.w(TAG, "upstream failure ${diagnosis.kind.wire}: ${diagnosis.summary}")
                logUsage(protocol, model, startedAt, "error", FailureDiagnosis.toRecordLine(diagnosis),
                    null, inputSummary, "", "", app, account)
                json(output, code, errorBody)
                return
            }
            // 请求成功：清掉失败计数，让权重里的成功率因子恢复（对齐上游 mark_success）。
            account?.let { NativeCore.store(this).markAccountSuccess(it.key) }
            val successResponse = response ?: throw IOException("上游响应为空")
            val source = successResponse.body?.source() ?: throw IOException("上游响应为空")
            // Prefetch the first data event before committing HTTP 200, preserving upstream error semantics.
            val first = nextData(source)
            if (stream) {
                writeSseHeaders(output)
                headersSent = true
                var sawDone = false
                fun emit(data: String) {
                    collector.feed(data)
                    if (data == "[DONE]") sawDone = true
                    val event = when (protocol) {
                        Protocol.CHAT -> ProtocolAdapters.sanitizeChatData(data)?.let { "data: $it\n\n" }.orEmpty()
                        else -> converter?.feed(data).orEmpty()
                    }
                    if (event.isNotEmpty()) {
                        writingClient = true
                        writeUtf8(output, event)
                        writingClient = false
                    }
                }
                if (first != null) emit(first)
                while (true) emit(nextData(source) ?: break)
                val tail = when (protocol) {
                    Protocol.CHAT -> if (!sawDone) "data: [DONE]\n\n" else ""
                    else -> converter?.finish().orEmpty()
                }
                if (tail.isNotEmpty()) {
                    writingClient = true
                    writeUtf8(output, tail)
                    writingClient = false
                }
                logUsage(protocol, model, startedAt, "ok", "", collector.usage, inputSummary,
                    collector.outputText(), collector.reasoningText(), app, account)
            } else {
                fun collect(data: String) {
                    collector.feed(data)
                    converter?.feed(data)
                }
                if (first != null) collect(first)
                while (true) collect(nextData(source) ?: break)
                converter?.finish()
                val result = converter?.nonStreamResponse() ?: collector.response()
                json(output, 200, result)
                logUsage(protocol, model, startedAt, "ok", "", collector.usage, inputSummary,
                    collector.outputText(), collector.reasoningText(), app, account)
            }
        } catch (e: Exception) {
            val disconnected = headersSent && (writingClient || socket.isClosed || isDisconnect(e))
            logUsage(protocol, model, startedAt, if (disconnected) "aborted" else "error",
                if (disconnected) "client disconnected" else (e.message ?: e.javaClass.simpleName), collector.usage,
                inputSummary, collector.outputText(), collector.reasoningText(), app, account)
            if (!headersSent) throw if (e is IOException) e else IOException(e)
            if (!disconnected) runCatching { writeUtf8(output, streamError(protocol, e.message ?: "上游流异常")) }
        } finally {
            if (socket.isClosed) call?.cancel()
            response?.close()
        }
    }

    private fun nextData(source: BufferedSource): String? {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: return null
            if (!line.startsWith("data:")) continue
            return line.substring(5).trim()
        }
        return null
    }

    private fun authorizedApp(headers: Map<String, String>): JSONObject? {
        val auth = headers["authorization"].orEmpty()
        val bearer = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
        val key = bearer.ifBlank { headers["x-api-key"].orEmpty() }
        if (key.isBlank()) return null
        return NativeCore.authenticateApp(this, key)
    }

    private fun parseBody(text: String): JSONObject = try {
        JSONObject(text.ifBlank { "{}" })
    } catch (_: JSONException) {
        throw ClientError("bad json")
    }

    private fun safeUpstreamError(raw: String, status: Int): JSONObject {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        if (parsed?.has("error") == true) return parsed
        val message = parsed?.optString("message")?.takeIf { it.isNotBlank() }
            ?: raw.take(500).takeIf { it.isNotBlank() }
            ?: "上游返回 HTTP $status"
        return apiError(message, "upstream_error")
    }

    private fun streamError(protocol: Protocol, message: String): String {
        val error = apiError(message, "upstream_error")
        return when (protocol) {
            Protocol.ANTHROPIC -> "event: error\ndata: ${JSONObject().put("type", "error").put("error", error.getJSONObject("error"))}\n\n"
            else -> "data: $error\n\ndata: [DONE]\n\n"
        }
    }

    private fun inputText(body: JSONObject): String {
        val result = StringBuilder()
        val messages = body.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (result.isNotEmpty()) result.append('\n')
            result.append(message.optString("role", "user")).append(": ")
            when (val content = message.opt("content")) {
                is String -> result.append(content)
                is JSONArray -> for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> result.append(part.optString("text"))
                        "image_url" -> result.append("[image]")
                    }
                }
            }
        }
        return result.toString()
    }

    /**
     * 记录未进入转发链路的请求（鉴权失败、参数错误等）。
     * 这类请求过去完全不落库，是"记录页不显示调用记录"的主要原因之一。
     */
    private fun logRejected(method: String, path: String, startedAt: Long, message: String, app: JSONObject?) {
        val protocol = when (path) {
            "/v1/messages", "/v1/messages/count_tokens" -> Protocol.ANTHROPIC
            "/v1/responses" -> Protocol.RESPONSES
            else -> Protocol.CHAT
        }
        val record = JSONObject()
            .put("protocol", protocol.wireName)
            .put("model", "")
            .put("status", "error")
            .put("error", message)
            .put("latency_ms", System.currentTimeMillis() - startedAt)
            .put("input_content", "").put("output_content", "").put("reasoning_content", "")
            .put("input_tokens", 0).put("output_tokens", 0).put("total_tokens", 0)
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("account_uid", "")
            .put("account_region", app?.let { AccountRegion.fromStrict(it.optString("region")).id } ?: "")
            .put("app_name", app?.optString("name") ?: "")
        Log.i(TAG, "rejected $method $path: $message")
        runCatching { NativeCore.logUsage(this, record) }
            .onFailure { Log.w(TAG, "NativeStore usage log failed", it) }
    }

    private fun logUsage(
        protocol: Protocol, model: String, startedAt: Long, status: String, error: String,
        usage: JSONObject?, input: String, output: String, reasoning: String, app: JSONObject,
        account: NativeCore.Account?
    ) {
        val inputTokens = usage?.optLong("prompt_tokens", usage.optLong("input_tokens", 0)) ?: 0L
        val outputTokens = usage?.optLong("completion_tokens", usage.optLong("output_tokens", 0)) ?: 0L
        val credits = upstreamCredits(usage)
        val record = JSONObject().put("protocol", protocol.wireName).put("model", model).put("status", status)
            .put("error", error).put("latency_ms", System.currentTimeMillis() - startedAt)
            .put("input_content", input).put("output_content", output).put("reasoning_content", reasoning)
            .put("input_tokens", inputTokens).put("output_tokens", outputTokens)
            .put("total_tokens", usage?.optLong("total_tokens", inputTokens + outputTokens) ?: inputTokens + outputTokens)
            .put("credits", credits)
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("account_uid", account?.uid ?: "")
            .put("account_region", account?.region?.id ?: app.optString("region").ifBlank { "domestic" })
            .put("app_name", app.optString("name"))
        // 把消耗记入积分账本（负数），与签到/奖励的所得分列，
        // 这样"积分详情"里能看到完整的收支两条线，而不只是总额。
        // 只在拿到确定的消耗额（>0）且归属到具体账号时才记。
        if (credits > 0 && account != null) {
            runCatching { NativeCore.recordCredit(this, account.key, "consume", -credits, model) }
        }
        // 原始 usage 一并打点：真机核对上游积分字段名时只需看 logcat，不必改代码重编。
        if (usage != null) Log.i(TAG, "upstream usage=$usage")
        Log.i(TAG, "usage=$record")
        runCatching { NativeCore.logUsage(this, record) }
            .onFailure { Log.w(TAG, "NativeStore usage log failed", it) }
    }

    /**
     * 解析上游 usage 中本次请求真实消耗的积分。
     *
     * 字段名以实测为准：腾讯 /v2/chat/completions 的积分字段是 `credit`（单数），
     * 与 Python 版 `credits=usage.get("credit") or 0` 完全一致；其余候选名用于兼容上游改版。
     * 缺失或非有限值（NaN/Infinity）时回落 0.0——NaN 会被 SQLite 写成 NULL，
     * 命中 usage_logs.credits 的 NOT NULL 约束，使整条记录写入失败。
     */
    private fun upstreamCredits(usage: JSONObject?): Double {
        if (usage == null) return 0.0
        for (key in CREDIT_KEYS) {
            val value = usage.optDouble(key, Double.NaN)
            if (value.isFinite() && value >= 0.0) return value
        }
        return 0.0
    }

    private fun json(output: OutputStream, status: Int, data: JSONObject) {
        val bytes = data.toString().toByteArray(StandardCharsets.UTF_8)
        writeHeaders(output, status, "application/json; charset=utf-8", bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun writeSseHeaders(output: OutputStream) {
        val text = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nX-Accel-Buffering: no\r\nConnection: close\r\n" +
            corsHeaders() + "\r\n"
        writeUtf8(output, text)
    }

    private fun writeHeaders(output: OutputStream, status: Int, contentType: String, length: Int) {
        val text = "HTTP/1.1 $status ${reason(status)}\r\nContent-Type: $contentType\r\nContent-Length: $length\r\n" +
            "Connection: close\r\n" + corsHeaders() +
            "Access-Control-Allow-Headers: Authorization, X-Api-Key, Content-Type, Anthropic-Version\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\r\n"
        writeUtf8(output, text)
    }

    /**
     * CORS 响应头。
     *
     * 原来这里固定是 `Access-Control-Allow-Origin: *` —— 那等于告诉浏览器
     * 「任何站点都可以读这个接口的响应」，与 RequestGuard 的 Origin 校验自相矛盾：
     * 服务端拒绝了跨站请求，却又在头里声明允许跨站，两者只能有一个是真的。
     *
     * 现在只回显**同源**地址（回环或本机局域网 IP）；跨站请求已经在
     * RequestGuard 里被 403 拒绝，走不到这里。
     *
     * 注意这不影响命令行/桌面客户端：它们不做 CORS 检查，读不读这个头都一样。
     */
    private fun corsHeaders(): String {
        val lan = LanAddress.list()
        // 回环永远允许：本机其它 App（含浏览器里的本地调试页）是主要调用方之一。
        val allowed = buildList {
            add("http://127.0.0.1:$PORT")
            add("http://localhost:$PORT")
            lan.forEach { add("http://${it.address}:$PORT") }
        }
        // 无 Origin 的请求不需要 CORS 头；给一个保守的单值即可，
        // 避免把一整串地址塞进响应头。
        return "Access-Control-Allow-Origin: ${allowed.first()}\r\n"
    }

    private fun writeUtf8(output: OutputStream, text: String) {
        output.write(text.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size <= MAX_HEADER_LINE) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        if (bytes.size > MAX_HEADER_LINE) throw ClientError("HTTP header line too long")
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun apiError(message: String, type: String): JSONObject = JSONObject().put("error",
        JSONObject().put("message", message).put("type", type).put("code", type))

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(StandardCharsets.UTF_8)
        val right = b.toByteArray(StandardCharsets.UTF_8)
        var diff = left.size xor right.size
        val length = maxOf(left.size, right.size)
        for (i in 0 until length) diff = diff or ((if (i < left.size) left[i].toInt() else 0) xor (if (i < right.size) right[i].toInt() else 0))
        return diff == 0
    }

    private fun isDisconnect(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase(Locale.US)
        return message.contains("broken pipe") || message.contains("connection reset") || message.contains("socket closed")
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"
        503 -> "Service Unavailable"; 504 -> "Gateway Timeout"; else -> "Error"
    }

    private fun notification(text: String): Notification {
        // 指向清单里真正注册的启动页。
        //
        // 原来指向 MainActivity，但它已从 AndroidManifest 移除（第 51-53 行注释），
        // 系统解析不到该组件 —— 点击常驻通知不会有任何反应（部分 ROM 直接抛
        // ActivityNotFoundException）。服务通知是用户回到界面的主要入口，必须可达。
        val open = PendingIntent.getActivity(this, 0, Intent(this, MiuixHostActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ApiHostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("WorkBuddy2API Native")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止", stop)
            .setOngoing(true)
            // CATEGORY_SERVICE：告诉系统这是长期运行的服务类通知，配合前台服务可降低被清理概率。
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_DEFAULT)
            // Android 12+ 前台服务通知默认可延迟 10 秒显示；IMMEDIATE 让它立刻出现，
            // 避免"服务已在跑但通知栏迟迟看不到"而被部分 ROM 判定为隐形后台服务。
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            // 已存在时不覆盖：用户手动调整过的渠道设置（如重要性/声音）必须尊重，
            // 反复 createNotificationChannel 虽不会重置用户设置，但这里提前返回语义更清晰。
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                // 用 DEFAULT 而不是 LOW：ColorOS 会把低重要性通知视作可降级，进而弱化对应前台服务的优先级；
                // 开发者文档也建议前台服务用不低于 DEFAULT，避免被系统折叠成静默通知。
                val channel = NotificationChannel(CHANNEL_ID, "本地原生 API", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "常驻运行本地 API 网关，供其它应用调用"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            } else if (existing.importance == NotificationManager.IMPORTANCE_NONE) {
                // 渠道被关闭 = 前台服务通知不可见，系统可能据此弱化甚至清理服务，
                // 这正是"通知权限明明开了却还是被杀"的常见原因。不擅自改回用户设置，
                // 只记录日志，由界面侧引导用户重新打开（见 ui/KeepAliveScreen）。
                Log.w(TAG, "notification channel disabled by user; foreground service may be deprioritized")
            }
        }
    }

    private fun notifyState(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("message", text).putExtra("running", running))
    }

    private fun runMaintenanceTick() {
        runCatching {
            // 冻结自检：服务一旦被冻结，本循环也会停摆；解冻后（例如新请求唤醒进程）第一件事
            // 就是确保唤醒锁重新持有——系统在冻结/回收时可能释放 wakelock，丢失即再次暴露在
            // Cached App Freezer 之下，60 秒内必须补上。带超时的锁过期后 isHeld 变 false，这里自动续期。
            acquireWakeLock()
            // 看门狗补种：Doze 下 AlarmManager 的闹钟可能被直接丢弃（而不是延后）。
            // 一旦丢弃，进程被杀后就再没有东西能把它拉起来——这是"用一段时间后
            // 彻底调不通"的根因之一。这里每分钟核对一次上钟时刻，超过一个周期
            // 仍未重新上钟就补上。成本极低（一次 AlarmManager 调用），收益是把
            // 闹钟链从"单发"变成"自愈"。
            val armAge = SystemClock.elapsedRealtime() - lastWatchdogArmAt
            if (lastWatchdogArmAt == 0L || armAge > WATCHDOG_INTERVAL_MS) {
                Log.i(TAG, "watchdog arm stale (age=${armAge / 60_000}min), re-arming")
                scheduleWatchdog()
            }
            val settings = NativeCore.settings(this)
            runAutomaticCheckin(settings)
            runAutomaticCredits(settings)
            runAutomaticModels(settings)
            runAutomaticKeepalive(settings)
            // ⚠️ 数据库清理【不放在这里】。
            //
            // 它内部要做三件重活：删记录（持锁）、扫数据库统计空闲页（持锁）、
            // 遍历目录统计各目录体积（大量 stat 系统调用）。
            // 全程占用 NativeStore 的全局 synchronized(lock) —— 而主线程读设置
            // （settings / accounts）也走同一把锁。之前就出现过：
            // 用户进入应用后 5 秒撞上本 tick，点「设置」时主线程一直等锁，
            // 画面停在原页面约 3 秒才跳转，表现为"设置页卡顿"。
            // 改为独立线程 + 每天最多一次，且不阻塞维护主流程。
            scheduleUsageCleanup(settings)
            prewarmModels()
        }.onFailure { Log.w(TAG, "maintenance tick failed", it) }
    }

    /**
     * 每日自动清理过期使用记录。
     *
     * 过去清理只有界面上的两个手动按钮，绝大多数用户不会去点，导致记录无限累积——
     * 这是"装完才 1MB、用一阵子变成近 200MB"的直接原因。这里改为每天自动执行一次：
     * 按 settings.usage_retention_days（默认 30 天）删除过期记录，并在确实删到东西时
     * VACUUM 回收文件空间（否则 SQLite 只标记空闲页，系统里看到的体积不会变小）。
     */
    /**
     * 把数据库清理排到独立线程延后执行。
     *
     * 为什么必须延后且独立：
     *   cleanupUsage / storageInfo / storageBreakdown 都会申请 NativeStore 的全局锁，
     *   而主线程读取设置与账号列表也走同一把锁。若在维护 tick 中同步执行，
     *   用户此时点开任何需要读库的页面（典型是设置页，它要读 settings）都会被阻塞，
     *   直到清理结束 —— 实测约 3 秒。
     *
     *   延后 30 秒再跑，是为了避开用户刚打开应用、最可能操作界面的那段时间窗口。
     *   清理本身不是时效性任务，晚 30 秒没有任何影响。
     */
    private fun scheduleUsageCleanup(settings: JSONObject) {
        val prefs = getSharedPreferences("native_service", MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.getString("last_usage_cleanup", null) == today) return
        // 先占位，避免 60 秒后的下一次 tick 重复排队
        prefs.edit().putString("last_usage_cleanup", today).apply()
        workers.execute {
            runCatching { Thread.sleep(30_000) }
            runCatching { runAutomaticUsageCleanup(settings) }
                .onFailure { Log.w(TAG, "deferred usage cleanup failed", it) }
        }
    }

    private fun runAutomaticUsageCleanup(settings: JSONObject) {
        val days = settings.optString("usage_retention_days", "30").toIntOrNull()?.coerceIn(1, 3650) ?: 30
        // 日期判断已上移到 scheduleUsageCleanup（它负责"每天一次"的占位），
        // 这里只做实际清理，避免两处判断产生不一致。
        runCatching {
            val removed = NativeCore.cleanupUsage(this, days)
            // VACUUM 会重写整个库并长时间持有数据库锁，期间新记录写入会被阻塞，
            // 因此只在真的删掉记录、且空闲空间可观时才做，避免每天无谓地重写一次数据库。
            if (removed > 0) {
                val free = runCatching { NativeCore.storageInfo(this).optLong("free_bytes") }.getOrDefault(0L)
                if (free > VACUUM_MIN_FREE_BYTES) {
                    workers.execute { runCatching { NativeCore.vacuum(this) } }
                    Log.i(TAG, "usage vacuum scheduled, free=${free / 1024}KB")
                }
            }
            Log.i(TAG, "usage cleanup done: removed=$removed retention=${days}d")
        }.onFailure { Log.w(TAG, "usage cleanup failed", it) }
        // WebView 磁盘缓存同样需要定期回收：登录页资源（JS/图片/字体）体量可观，
        // 且 WebView 自身不设容量上限。只在缓存体积确实可观时才动，避免每次白跑一遍扫描。
        runCatching {
            val breakdown = NativeCore.storageBreakdown(this)
            val cacheBytes = breakdown.optLong("webview") + breakdown.optLong("cache")
            if (cacheBytes > WEBVIEW_CACHE_CLEAN_THRESHOLD) {
                NativeCore.clearWebViewCache(this)
                Log.i(TAG, "webview cache cleared, was=${cacheBytes / 1024}KB")
            }
        }.onFailure { Log.w(TAG, "webview cache cleanup failed", it) }
    }

    /** 模型目录过期即后台预热，保证客户端 /v1/models 永远命中缓存，请求路径零网络等待。 */
    private fun prewarmModels() {
        runCatching {
            val accounts = NativeCore.listAccounts(this)
            val regions = (0 until accounts.length()).mapNotNull {
                accounts.optJSONObject(it)?.takeIf { item -> item.optBoolean("enabled", true) }
                    ?.let { item -> AccountRegion.from(item.optString("region")) }
            }.distinct()
            regions.forEach { NativeCore.prewarmModels(this, it) }
        }.onFailure { Log.w(TAG, "model prewarm failed", it) }
    }

    private fun runAutomaticCheckin(settings: JSONObject) {
        val now = java.util.Calendar.getInstance()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val hours = settings.optString("checkin_hours", "9,21").split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..23 }.distinct().sorted()
        val slotHour = hours.lastOrNull { now.get(java.util.Calendar.HOUR_OF_DAY) >= it } ?: return
        val slot = "%s:%02d".format(Locale.US, today, slotHour)
        val prefs = getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        val accounts = NativeCore.listAccounts(this)
        for (i in 0 until accounts.length()) {
            val account = accounts.getJSONObject(i)
            if (!account.optBoolean("enabled", true) || account.optString("last_checkin_date") == today ||
                AccountRegion.from(account.optString("region")) == AccountRegion.INTERNATIONAL) continue
            val accountKey = account.optString("account_key")
            val prefKey = "checkin_attempt_${NativeStore.sha256(accountKey).take(24)}"
            if (prefs.getString(prefKey, "") == slot) continue
            prefs.edit().putString(prefKey, slot).apply()
            runCatching { NativeCore.checkIn(this, accountKey) }
                .onSuccess { Log.i(TAG, "automatic check-in succeeded: ${account.optString("region")}") }
                .onFailure { Log.w(TAG, "automatic check-in failed: ${account.optString("region")}", it) }
        }
    }

    private fun runAutomaticCredits(settings: JSONObject) {
        val intervalMs = settings.optLong("credit_refresh_min", 30).coerceIn(1, 1440) * 60_000L
        val prefs = getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("credits_last_attempt_ms", 0L) < intervalMs) return
        prefs.edit().putLong("credits_last_attempt_ms", now).apply()
        runCatching { NativeCore.refreshAllCredits(this) }
            .onFailure { Log.w(TAG, "automatic credits refresh failed", it) }
    }

    private fun runAutomaticModels(settings: JSONObject) {
        val hour = settings.optInt("model_refresh_hour", 6).coerceIn(0, 23)
        val now = java.util.Calendar.getInstance()
        if (now.get(java.util.Calendar.HOUR_OF_DAY) < hour) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        val accounts = NativeCore.listAccounts(this)
        val regions = (0 until accounts.length()).mapNotNull {
            accounts.optJSONObject(it)?.takeIf { item -> item.optBoolean("enabled", true) }
                ?.let { item -> AccountRegion.from(item.optString("region")) }
        }.distinct()
        regions.forEach { region ->
            val successKey = "models_success_${region.id}"
            val attemptKey = "models_attempt_${region.id}"
            if (prefs.getString(successKey, "") == today) return@forEach
            val nowMs = System.currentTimeMillis()
            if (nowMs - prefs.getLong(attemptKey, 0L) < 30 * 60_000L) return@forEach
            prefs.edit().putLong(attemptKey, nowMs).apply()
            runCatching { NativeCore.refreshModels(this, region) }
                .onSuccess { prefs.edit().putString(successKey, today).apply() }
                .onFailure { Log.w(TAG, "automatic models refresh failed: ${region.id}", it) }
        }
    }

    /**
     * 每日 token 保活（对齐上游 scheduler.do_keepalive）。
     *
     * 为什么要单独做这件事：token 只在「即将过期时才刷新」的被动策略下，长时间闲置的
     * 账号会一路滑到 refreshToken 也过期，届时只能重新登录。上游的做法是每天强制刷一次，
     * 让 refreshToken 持续滚动续期。
     *
     * 失败处理同样对齐上游：记录失败次数，**连续 3 次**才禁用账号（见 NativeStore
     * .recordRefreshFailure）。单次失败（网络抖动、上游瞬时不可达）不应该被当成账号失效。
     */
    private fun runAutomaticKeepalive(settings: JSONObject) {
        val hour = settings.optInt("keepalive_hour", 4).coerceIn(0, 23)
        val now = java.util.Calendar.getInstance()
        if (now.get(java.util.Calendar.HOUR_OF_DAY) < hour) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        if (prefs.getString("keepalive_done", "") == today) return
        val accounts = NativeCore.listAccounts(this)
        if (accounts.length() == 0) return
        var attempted = 0
        for (i in 0 until accounts.length()) {
            val item = accounts.optJSONObject(i) ?: continue
            if (!item.optBoolean("enabled", true)) continue
            val key = item.optString("account_key")
            if (key.isBlank()) continue
            attempted++
            runCatching { NativeCore.refreshToken(this, key) }
                .onSuccess { Log.i(TAG, "keepalive refreshed token: ${item.optString("uid")}") }
                .onFailure { Log.w(TAG, "keepalive failed for ${item.optString("uid")}", it) }
        }
        // 只有在确实尝试过刷新时才记今日已做，避免「一个账号都没启用」也被标记完成。
        if (attempted > 0) prefs.edit().putString("keepalive_done", today).apply()
    }

    private fun stopNow() {
        running = false
        markShouldRun(applicationContext, false)
        cancelWatchdog()
        runCatching { server?.close() }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("running", false).putExtra("message", "本地 API 已停止"))
    }

    /** 用户主动停止后必须撤掉看门狗，否则 15 分钟后会被闹钟莫名拉起来（"关了又自己开了"）。 */
    private fun cancelWatchdog() {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(watchdogIntent())
            Log.i(TAG, "watchdog cancelled")
        }.onFailure { Log.w(TAG, "watchdog cancel failed", it) }
    }

    override fun onDestroy() {
        running = false
        runCatching { FloatingWindowKeeper.hide(this) }
        runCatching { server?.close() }
        releaseWakeLock()
        workers.shutdownNow()
        maintenance.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private enum class Protocol(val wireName: String) { CHAT("chat"), ANTHROPIC("anthropic"), RESPONSES("responses") }
    private class ClientError(message: String) : RuntimeException(message)

    companion object {
        const val PORT = 8788
        /** 默认监听地址：只绑回环，本机之外的设备无法连接。 */
        private const val LOOPBACK = "127.0.0.1"
        /** 开启「允许局域网访问」后的监听地址。 */
        private const val LAN_ANY = "0.0.0.0"
        /** 设置键：是否允许局域网访问（默认关闭，需用户在设置里显式打开）。 */
        private const val SETTING_LAN_ACCESS = "lanAccess"
        /** 视为「已开启」的字符串取值。 */
        private val LAN_ON = setOf("1", "true")
        const val ACTION_STOP = "com.dsh.aihub.STOP"
        const val ACTION_STATE = "com.dsh.aihub.STATE"
        /** 看门狗闹钟动作：被它唤醒时只需确认前台服务与唤醒锁仍在。 */
        const val ACTION_WATCHDOG = "com.dsh.aihub.WATCHDOG"
        /**
         * 看门狗轮询间隔 9 分钟。
         *
         * 为什么是 9 分钟而不是原来的 15 分钟：
         *   Doze 对 setExactAndAllowWhileIdle 的限流下限就是 9 分钟——比这更密会被系统
         *   直接降级为普通闹钟（Doze 下可能延迟数小时），比这更疏则会拉长「服务已死但
         *   还没被拉起」的空窗。9 分钟正好卡在系统允许的最密值上，是兜底延迟的理论下界。
         */
        private const val WATCHDOG_INTERVAL_MS = 9 * 60_000L

        /**
         * 唤醒锁超时 30 分钟，**必须大于 WATCHDOG_INTERVAL_MS**。
         *
         * 原实现是 10 分钟，注释假设「超时后由 60 秒一次的 maintenance tick 自动续期」，
         * 但那恰恰在最需要续期的场景下不成立：Doze 期间 ScheduledExecutor 没有 CPU 时间片，
         * tick 根本不调度，锁一旦过期就没有任何人来续——进程随即失去 Cached App Freezer
         * 豁免资格并被冻结，表现为「息屏一段时间后 127.0.0.1 调不通」。
         *
         * 改为 30 分钟（= 看门狗间隔的 3 倍多）后，即使某次续期被系统延迟，锁的剩余有效期
         * 仍然覆盖到下一次看门狗唤醒；而看门狗走 AlarmManager，是唯一能在 Doze 下唤醒 CPU
         * 的机制，所以它才是真正可靠的续期来源。
         */
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60_000L
        /** 空闲空间超过此值才值得做 VACUUM：小于 2MB 时重写整库的代价超过回收收益。 */
        private const val VACUUM_MIN_FREE_BYTES = 2L * 1024 * 1024
        /** cache 类目录超过此值才清理 WebView 缓存，避免每天无谓地扫描+清理。 */
        private const val WEBVIEW_CACHE_CLEAN_THRESHOLD = 8L * 1024 * 1024
        private const val WATCHDOG_REQUEST = 8789
        /**
         * 上次给看门狗上闹钟的时刻（elapsedRealtime）。
         *
         * 为什么需要它：Doze 下 setExactAndAllowWhileIdle 的闹钟可能被系统丢弃而不是
         * 延后触发。表现为"服务明明还在跑，但看门狗再也不响了"——此时若进程随后被
         * 杀，就再没有任何东西把它拉起来。maintenance tick 每分钟检查一次这个时间戳，
         * 一旦发现超过一个周期还没重新上钟，就地补上。
         */
        @Volatile private var lastWatchdogArmAt = 0L
        @Volatile var running = false

        /** 用户意图：true = 用户希望服务处于运行状态。
         *  持久化它有两个用途：① 开机自启时判断该不该拉起（避免用户明明关过又被拉起）；
         *  ② onTaskRemoved 重启前校验，避免"关了又自己开"。 */
        private const val PREFS = "native_service"
        private const val KEY_SHOULD_RUN = "should_run"

        /** 记录/读取"用户是否希望服务运行"。启动服务时置 true，用户主动停止时置 false。 */
        fun markShouldRun(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOULD_RUN, value).apply()
        }

        fun shouldRun(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, false)

        /**
         * 通知渠道是否被用户关闭。前台服务通知不可见时，系统可能弱化甚至清理服务——
         * 这是"通知权限明明开着却仍被杀/被折叠"的常见盲区，界面侧据此给出重新开启的引导。
         */
        fun isChannelDisabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val channel = context.getSystemService(Context.NOTIFICATION_SERVICE)
                ?.let { it as? android.app.NotificationManager }
                ?.getNotificationChannel(CHANNEL_ID) ?: return false
            return channel.importance == android.app.NotificationManager.IMPORTANCE_NONE
        }
        private const val CHANNEL_ID = "workbuddy_native_api"
        private const val NOTIFICATION_ID = 8788
        private const val MAX_BODY_BYTES = 10L * 1024L * 1024L
        private const val MAX_HEADER_LINE = 16 * 1024
        private const val TAG = "ApiHostService"

        /**
         * 内容被拦后触发降级提示词（移植自 1.2.7 的 useDegradedPrompt）。
         *
         * 设计要点：
         *   1) 降级只持续【到当天午夜】——1.2.7 的原语义。跨天后自动恢复自定义提示词，
         *      否则一次误伤会让用户永久用着那句通用英文词，再也回不来。
         *   2) 把 use_degraded_prompt 存进 settings 表而不是内存变量：
         *      服务进程会被系统重启，存内存等于每次重启就悄悄"恢复"了。
         */
        private const val DEGRADE_PREF = "degraded_prompt_date"

        /**
         * 把降级状态复位（降级只应持续到触发当天）。
         *
         * 为什么必须有这个函数：
         *   maybeDegradePrompt 会把 use_degraded_prompt 置为 true，语义是
         *   「到当天午夜为止」。但此前【没有任何地方把它改回 false】，
         *   于是只要历史上触发过一次内容拦截，降级提示词就会永久生效 ——
         *   用户的自定义提示词从此再也注入不进去，表现为"提示词注入没效果"。
         *
         * 检查时机放在每次请求进入注入管线之前（见 proxy）：这样不需要依赖
         * 服务重启或定时任务，跨天后发出的第一条请求就会立刻恢复自定义提示词。
         */
        private fun restorePromptIfExpired(ctx: Context) {
            val prefs = ctx.getSharedPreferences("native", Context.MODE_PRIVATE)
            val markedDate = prefs.getString(DEGRADE_PREF, null) ?: return
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            if (markedDate == today) return
            // 已跨天：清掉降级标记与开关
            prefs.edit().remove(DEGRADE_PREF).apply()
            NativeCore.saveSettings(ctx, JSONObject().put("use_degraded_prompt", false))
            Log.i(TAG, "degraded prompt expired (marked $markedDate) -> restored custom prompt")
        }

        private fun maybeDegradePrompt(ctx: Context, status: Int, raw: String) {
            if (status !in setOf(400, 403, 422)) return
            val text = raw.lowercase(java.util.Locale.US)
            val blocked = BLOCK_MARKERS.any { text.contains(it) }
            if (!blocked) return
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val prefs = ctx.getSharedPreferences("native", Context.MODE_PRIVATE)
            if (prefs.getString(DEGRADE_PREF, null) == today) return
            prefs.edit().putString(DEGRADE_PREF, today).apply()
            NativeCore.saveSettings(ctx, JSONObject().put("use_degraded_prompt", true))
            Log.i(TAG, "content blocked (likely system fingerprint false positive) -> degraded prompt until midnight")
        }

        /** 上游内容拦截的常见措辞（1.2.7 只匹配一句英文，这里按实测扩充）。 */
        private val BLOCK_MARKERS = listOf(
            "content blocked", "content_blocked", "content filter", "content_filter",
            "content policy", "safety", "risk control", "risk_control",
            "内容安全", "内容不合规", "敏感", "违规", "拦截"
        )

        /**
         * 允许换号重试的上游状态码。
         *
         * 对齐上游 app.py:279-303——只有这三个表示「这个账号此刻不可用/被限流」，
         * 换号才有意义。400/401/404 等是请求本身或凭据的问题，换号重试只会让
         * 另一个健康账号也无谓挨一次错误请求。
         */
        private val RETRYABLE_STATUS = setOf(429, 502, 503)

        /**
         * 上游积分字段候选名（按优先级）。
         * `credit` 是腾讯 /v2/chat/completions 的实测字段名（Python 版同源解析），其余为上游改版预留。
         */
        private val CREDIT_KEYS = listOf("credit", "credits", "credit_cost", "credit_used", "used_credit", "total_credit", "cost", "points")
    }
}
