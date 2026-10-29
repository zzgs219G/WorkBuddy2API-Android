package com.dsh.aihub

import kotlin.random.Random
import android.content.Context
import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Network facade backed by [NativeStore]. No Python or Web runtime is involved. */
object NativeCore {
    const val BACKEND = "https://copilot.tencent.com"
    const val INTERNATIONAL_BACKEND = "https://www.codebuddy.ai"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // ---- 连接稳定性（针对"经常断开"的调优）----
        // 1) 失败自动重试：覆盖连接被重置/复用陈旧 keep-alive 连接这类
        //    瞬时故障。此前为默认值，移动网络切换（Wi-Fi↔蜂窝）时很容易直接失败。
        .retryOnConnectionFailure(true)
        // 2) 保活探测：每 30 秒发一次 ping，及时回收已被对端关闭的死连接。
        //    不设它的话，连接池里可能长期留着"看起来活着、实际已断"的连接，
        //    下次请求复用它就会立刻失败——这正是"经常断开"的典型成因。
        .pingInterval(30, TimeUnit.SECONDS)
        // 3) 连接池调大并延长保活：默认只有 5 个空闲连接 / 5 分钟，
        //    本项目会并发访问 copilot.tencent.com 与 www.workbuddy.cn 两个域，
        //    池子太小会导致频繁重建连接（每次都要完整 TLS 握手）。
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        // 4) 显式协议与密码套件交给平台默认即可，不锁定，避免旧设备握手失败。
        .build()

    /**
     * 进程内模型目录缓存。请求路径（GET /v1/models）只读它，绝不发起网络请求——
     * 历史问题：modelsForRegion 在缓存过期时同步拉上游，客户端（Cherry Studio 等）拉一次模型
     * 要干等几十秒。现在过期数据也立刻返回（stale-while-revalidate），刷新交给后台线程。
     */
    private class ModelSnapshot(val payload: String, val expiresAt: Long)
    private val modelSnapshots = ConcurrentHashMap<String, ModelSnapshot>()
    private val modelRefreshing = ConcurrentHashMap<String, AtomicBoolean>()
    private val modelRefreshPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "model-refresh").apply { isDaemon = true } }
    @Volatile private var bundledInternational: JSONObject? = null

    private fun snapshotModels(region: AccountRegion): JSONArray? = modelSnapshots[region.id]?.let { JSONArray(it.payload) }

    private fun rememberModels(region: AccountRegion, models: JSONArray, expiresAt: Long) {
        modelSnapshots[region.id] = ModelSnapshot(models.toString(), expiresAt)
    }

    /** 后台刷新（single-flight）：同一 region 同时最多一次上游拉取，绝不阻塞调用方。 */
    private fun scheduleModelRefresh(context: Context, region: AccountRegion) {
        val flag = modelRefreshing.computeIfAbsent(region.id) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return
        val app = context.applicationContext
        runCatching {
            modelRefreshPool.execute {
                try { refreshModels(app, region) }
                catch (e: Exception) { Log.w(TAG, "background model refresh failed: ${region.id}", e) }
                finally { flag.set(false) }
            }
        }.onFailure { flag.set(false) }
    }

    /** 维护循环调用：缓存缺失或已过期时后台预热，保证请求路径永不触发网络。 */
    fun prewarmModels(context: Context, region: AccountRegion) {
        val nowSec = System.currentTimeMillis() / 1000
        val fresh = modelSnapshots[region.id]?.let { it.expiresAt > nowSec }
            ?: (store(context).getModelCache(allowExpired = false, region = region.id) != null)
        if (!fresh) scheduleModelRefresh(context, region)
    }

    data class Account(val root: JSONObject) {
        val auth: JSONObject get() = root.optJSONObject("auth") ?: root
        val profile: JSONObject get() = root.optJSONObject("account") ?: auth.optJSONObject("account") ?: JSONObject()
        val uid: String get() = profile.optString("uid")
        val nickname: String get() = profile.optString("nickname", uid)
        val region: AccountRegion get() = AccountRegion.infer(root.optString("region"), auth.optString("domain"))
        val key: String get() = "${region.id}:$uid"
        val backend: String get() = region.backend
    }

    data class UpstreamCall(val request: Request, val account: Account)

    fun store(context: Context): NativeStore = NativeStore.get(context)

    /** Compatibility API: returns the account selected by the native account pool. */
    fun loadAccount(context: Context): Account? {
        migrateLegacy(context)
        val selected = store(context).selectAccount(markUsed = false) ?: return null
        return store(context).getAccountRoot(selected.optString("account_key"))?.let(::Account)
    }

    fun loadAccount(context: Context, accountKey: String): Account? = store(context).getAccountRoot(accountKey)?.let(::Account)

    fun saveAccount(context: Context, root: JSONObject, requestedRegion: AccountRegion? = null): Account {
        val inferred = requestedRegion ?: AccountRegion.infer(root.optString("region"), (root.optJSONObject("auth") ?: root).optString("domain"))
        root.put("region", inferred.id)
        val account = Account(root)
        require(account.auth.optString("accessToken").isNotBlank() && account.uid.isNotBlank()) {
            "auth 文件缺少 accessToken 或 account.uid"
        }
        val key = store(context).upsertAccount(root, inferred.id)
        // 回填导出时附带的本地配置。
        //
        // 只在字段确实存在时写，避免「导入一个不带 local 的旧文件」把已有备注清空。
        root.optJSONObject("local")?.let { local ->
            if (local.has("remark")) store(context).setAccountRemark(key, local.optString("remark"))
            if (local.has("priority")) store(context).setAccountPriority(key, local.optInt("priority"))
            if (local.has("enabled")) store(context).setAccountEnabled(key, local.optBoolean("enabled", true))
        }
        context.getSharedPreferences("native", Context.MODE_PRIVATE).edit().remove("account").apply()
        return account
    }

    fun importAccounts(context: Context, documents: JSONArray): JSONObject {
        val imported = JSONArray(); val rejected = JSONArray()
        for (i in 0 until documents.length()) {
            val root = when (val item = documents.opt(i)) {
                is JSONObject -> item
                is String -> runCatching { JSONObject(item) }.getOrNull()
                else -> null
            }
            if (root == null) { rejected.put(JSONObject().put("index", i).put("error", "无效 JSON")); continue }
            runCatching { saveAccount(context, root) }
                .onSuccess { imported.put(it.uid) }
                .onFailure { rejected.put(JSONObject().put("index", i).put("error", it.message ?: "导入失败")) }
        }
        return JSONObject().put("imported", imported).put("rejected", rejected)
    }

    fun listAccounts(context: Context): JSONArray = store(context).listAccounts()

    /**
     * 导出单个账号的 auth 文件内容。
     * 输出格式与「导入 auth 文件」完全对称：导出的 JSON 直接导回即可用，
     * 因此换机迁移、备份都可以靠「导出 → 导入」完成，无需重新扫码登录。
     * 会补写 region 字段——原始 auth_json 里不含它（region 存在数据库列中），
     * 带上之后导入侧无需推断就能落到正确的国内版/国际版。
     * 注意返回值含 accessToken / refreshToken 等凭证，调用方应提示用户妥善保管。
     */
    fun exportAccount(context: Context, accountKey: String): String? {
        val region = accountKey.substringBefore(':', "")
        val root = store(context).getAccountRoot(accountKey) ?: return null
        if (region.isNotBlank() && !root.has("region")) root.put("region", region)
        // 附带本地配置。
        //
        // 原先导出的是纯 auth_json，本地设置（备注、优先级、启用状态）全部丢失，
        // 换机迁移后所有账号又变回无名的短 UID、优先级归零 —— 用户得重新配一遍。
        //
        // 放在单独的 local 对象里而不是平铺到 root：root 是上游 auth 文件的原样
        // 结构，往里塞自定义键会污染它（某些导入方会做严格字段校验）。
        val meta = store(context).getAccount(accountKey)
        if (meta != null) {
            root.put(
                "local",
                JSONObject()
                    .put("remark", meta.optString("remark"))
                    .put("priority", meta.optInt("priority"))
                    .put("enabled", meta.optBoolean("enabled", true))
            )
        }
        return root.toString(2)
    }

    /**
     * 导出全部账号。单个账号导出为对象，多个账号导出为数组——
     * 这两种形态导入侧都能识别（importAuth 会先试 JSONObject，失败再按数组处理）。
     * 每个条目都走 exportAccount，保证 region 等字段的补写逻辑完全一致。
     */
    fun exportAllAccounts(context: Context): String {
        val keys = store(context).accountKeys()
        if (keys.size == 1) return exportAccount(context, keys[0]) ?: "{}"
        val array = JSONArray()
        keys.forEach { key ->
            runCatching { JSONObject(exportAccount(context, key).orEmpty()) }
                .getOrNull()?.let { array.put(it) }
        }
        return array.toString(2)
    }

    fun setAccountEnabled(context: Context, accountKey: String, enabled: Boolean): Boolean = store(context).setAccountEnabled(accountKey, enabled)
    fun setAccountPriority(context: Context, accountKey: String, priority: Int): Boolean = store(context).setAccountPriority(accountKey, priority)

    /** 设置用户自定义备注（与上游返回的 nickname 分开存储，重登不会覆盖）。 */
    fun setAccountRemark(context: Context, accountKey: String, remark: String): Boolean =
        store(context).setAccountRemark(accountKey, remark)
    fun deleteAccount(context: Context, accountKey: String): Boolean = store(context).deleteAccount(accountKey)
    fun selectAccount(context: Context): JSONObject? = store(context).selectAccount()

    /** Compatibility API. The key is now an encrypted key belonging to the default app. */
    fun apiKey(context: Context): String {
        val prefs = context.getSharedPreferences("native", Context.MODE_PRIVATE)
        val legacy = prefs.getString("api_key", null)
        val app = store(context).ensureDefaultApp(legacy)
        val key = app.optString("key")
        if (key.isNotBlank()) prefs.edit().remove("api_key").apply()
        return key
    }

    fun createApp(context: Context, name: String, note: String = "", region: AccountRegion, customKey: String? = null): JSONObject =
        store(context).createApp(name, note, region.id, customKey)
    fun updateApp(context: Context, appId: Long, name: String, note: String, region: AccountRegion, replacementKey: String? = null): JSONObject =
        store(context).updateApp(appId, name, note, region.id, replacementKey)
    fun listApps(context: Context): JSONArray {
        apiKey(context)
        return store(context).listApps()
    }
    fun getAppKey(context: Context, appId: Long): String? = store(context).getAppKey(appId)
    fun setAppEnabled(context: Context, appId: Long, enabled: Boolean): Boolean = store(context).setAppEnabled(appId, enabled)
    fun toggleApp(context: Context, appId: Long): Boolean? = store(context).toggleApp(appId)
    fun deleteApp(context: Context, appId: Long): Boolean = store(context).deleteApp(appId)
    fun authenticateApp(context: Context, key: String): JSONObject? = store(context).authenticateApp(key)

    @Synchronized
    fun headers(context: Context): Map<String, String> = accountForRequest(context).let { headersFor(it) }

    /**
     * @param sessionKey 会话标识。非空时走会话粘性选号（同一会话固定同一账号），
     *   空则退化为普通加权选号。见 [NativeStore.selectAccountForSession]。
     */
    // 注意：这里**故意不加 @Synchronized**。
    //
    // 原实现是全局锁，虽然能防止同一账号并发刷新，但代价是所有账号的请求
    // 全被串行化 —— 账号 A 刷新时要走一次完整网络往返（可能数秒），期间
    // 账号 B、C 的请求全部排队。多账号场景下这是明显的性能塌陷。
    //
    // 并发安全由两处保证：
    //   ① NativeStore 内部所有操作都在 synchronized(lock) 内
    //   ② 刷新走 refreshWithLock()，按账号粒度加锁 + 双重检查
    //
    // 未加锁的风险点已逐个核对：
    //   · migrateLegacy 幂等（跑第二次直接 return）
    //   · loadAccount / saveAccount 走 NativeStore，自身同步
    //   · selectAccountForSession 内部 synchronized
    private fun accountForRequest(
        context: Context,
        region: AccountRegion? = null,
        sessionKey: String? = null,
    ): Account {
        migrateLegacy(context)
        val selected = store(context).selectAccountForSession(sessionKey, region?.id) ?: run {
            // 选不到账号有两种原因，处理方式完全不同：一个都没登录 vs. 全部在冷却。
            // 改动前两者共用一句「没有可用的账号」，用户无法判断该去重新登录还是该等。
            val all = store(context).listAccounts()
            val total = (0 until all.length())
                .count { all.optJSONObject(it)?.optInt("enabled", 1) == 1 }
            val cooling = total - store(context).countHealthy(region?.id)
            val diagnosis = FailureDiagnosis.fromNoAccount(total, cooling)
            throw IOException("${diagnosis.summary} ｜ ${diagnosis.action}")
        }
        var account = loadAccount(context, selected.getString("account_key")) ?: throw IOException("账号认证数据缺失")
        val expiresAt = normalizedExpiryMillis(account.auth)
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - refreshLeadMs(account)) {
            account = refreshWithLock(context, account)
        }
        return account
    }

    private fun headersFor(account: Account): MutableMap<String, String> {
        val auth = account.auth; val profile = account.profile
        val enterprise = profile.optString("enterpriseId", auth.optString("enterpriseId"))
        // UA 与产品识别头统一由 ClientIdentity 提供：此前这里硬编码
        // "Workbuddy2API-Android/1.0"，导致同一账号上传出的身份前后不一致
        // （拉模型是浏览器、发对话是第三方网关），是最直接的可识别特征。
        return mutableMapOf(
            "Content-Type" to "application/json", "Accept" to "application/json",
            "Authorization" to "Bearer ${auth.optString("accessToken")}", "X-User-Id" to account.uid,
            "X-Enterprise-Id" to enterprise, "X-Tenant-Id" to enterprise,
            "X-Domain" to auth.optString("domain", account.region.defaultDomain).ifBlank { account.region.defaultDomain },
            "User-Agent" to ClientIdentity.CLI_UA
        ).apply {
            putAll(ClientIdentity.pluginHeaders(account.region))
            // X-Private-Data 官方只在非国内站发（按 endpoint 判断），
            // 故这里跟着 region 走，不给国内站凭空多出一个官方没有的头。
            if (account.region == AccountRegion.INTERNATIONAL) {
                put("X-Private-Data", ClientIdentity.privateDataHeader(enterprise))
            }
        }
    }

    /**
     * 主对话请求的完整头集合 = 基础身份头 + 会话/追踪/意图头。
     *
     * 为什么不把会话头直接塞进 headersFor：官方 buildConversationHeaders 是
     * 【会话作用域】的函数，只有真正的对话请求才会调用它。token 刷新、拉模型目录、
     * 签到这些走的是插件握手/配置链路，官方【不会】给它们挂对话会话 ID。
     *
     * 如果我们无脑给所有出站请求都加会话头，反而制造新的一致性破绽：
     * 一个「刷新 token 时也带着对话会话 ID」的客户端，比不带会话头更可疑。
     *
     * 会话 ID 按账号分桶持久化（SessionTracker），换账号必然换会话——
     * 两个账号共享同一个 X-Conversation-ID 是会被上游直接关联的硬伤。
     */
    // 同样不加 @Synchronized：函数体内没有共享可变状态，
    // SessionTracker 自身保证并发安全。挂在对话热路径上的全局锁
    // 会把所有并发的对话请求串行化。
    private fun headersForConversation(context: Context, account: Account): MutableMap<String, String> {
        val session = SessionTracker.sessionFor(context, account.key)
        val messageId = SessionTracker.newMessageId()
        // 本轮请求 ID：官方在一次 user send 开始时生成、该轮所有请求复用。
        // 反代这里一个请求就是一轮，故按请求生成一个独立 id。
        val turnRequestId = SessionTracker.newMessageId()
        return headersFor(account).apply {
            putAll(ClientIdentity.conversationHeaders(session.conversationId, messageId, turnRequestId))
            // 官方 CLI 的对话请求是 OpenAI Node SDK 发出的，SDK 会自动注入
            // X-Stainless-* 指纹头；缺了这组头，「自称 CLI 却无 SDK 指纹」本身就是破绽。
            putAll(ClientIdentity.stainlessHeaders())
            // trace 头族：国际出口的渠道校验会看它，国内出口不看。
            // 只为国际版补，避免给国内版凭空加上官方客户端在抓包里也没出现过的组合。
            if (account.region == AccountRegion.INTERNATIONAL) {
                putAll(ClientIdentity.traceHeaders())
            }
        }
    }

    fun refreshToken(context: Context, accountKey: String): Account {
        val account = loadAccount(context, accountKey) ?: throw IOException("账号不存在：$accountKey")
        return refreshToken(context, account)
    }

    /**
     * 刷新提前量的抖动缓存：accountKey → 本次进程内固定的提前毫秒数。
     *
     * 为什么要抖动：多账号通常同时登录、同时过期。固定提前 60 秒的话，
     * 它们的刷新请求会挤在同一秒打向上游 —— 在上游看来是明显的机器行为。
     *
     * 为什么按账号固定而非每次随机：随机抖动会让同一账号的提前量在每次
     * 求值时跳变，导致「刚判过不需要刷新，下一秒又判需要」。按账号固定后，
     * 判定可复现，也让多账号各自落在不同的时间点。
     */
    private val refreshLeadCache = ConcurrentHashMap<String, Long>()

    /** 基础提前量：60 秒。抖动范围 ±15%，即 51s ~ 69s。 */
    private fun refreshLeadMs(account: Account): Long {
        return refreshLeadCache.getOrPut(account.key) {
            val base = 60_000L
            val jitter = (base * 0.15 * (Random.nextDouble() * 2 - 1)).toLong()
            (base + jitter).coerceIn(30_000L, 120_000L)
        }
    }

    /**
     * 同一账号同时只允许一个刷新在跑。
     *
     * 为什么必须加锁：OAuth 的 refresh token 通常是一次性的 ——
     * 两个请求同时拿着同一个 RT 去刷新，第一个成功换到新 RT，
     * 第二个用已作废的 RT 就会拿到 invalid_grant。上游很可能因此
     * 把账号判为异常。
     *
     * 为什么按账号加锁而不是全局 @Synchronized：
     * 原实现在 accountForRequest 上挂 @Synchronized，那是**全局**串行 ——
     * 账号 A 刷新时（一次网络往返，可能数秒），账号 B、C 的请求全被堵住。
     * 按账号加锁后，不同账号可以并行刷新，只有同一账号才会排队。
     *
     * 拿到锁之后必须重新读一次账号：等锁期间别的线程可能已经刷新完了，
     * 此时直接用它刷好的新 token，不必再刷一次。
     */
    private fun refreshWithLock(context: Context, account: Account): Account {
        val lock = refreshLocks.getOrPut(account.key) { Any() }
        synchronized(lock) {
            // 双重检查：等锁期间可能已被别的线程刷新
            val current = loadAccount(context, account.key) ?: account
            val exp = normalizedExpiryMillis(current.auth)
            if (exp > 0 && System.currentTimeMillis() < exp - refreshLeadMs(current)) {
                return current   // 已被刷新，直接用
            }
            return refreshToken(context, current)
        }
    }

    /** 每个账号一把锁。用 ConcurrentHashMap 保证并发创建安全。 */
    private val refreshLocks = ConcurrentHashMap<String, Any>()

    private fun refreshToken(context: Context, account: Account): Account {
        val refresh = account.auth.optString("refreshToken")
        if (refresh.isBlank()) throw IOException("账号 ${account.uid} 缺少 refreshToken")
        // token 刷新同样是插件行为，UA 必须与主链路一致（此前此处继承 headersFor 的自曝 UA）。
        val h = headersFor(account).apply { this["X-Refresh-Token"] = refresh; this["X-Auth-Refresh-Source"] = "plugin" }
        return try {
            val payload = executeJson(request("${account.backend}/v2/plugin/auth/token/refresh", "POST", JSONObject(), h))
            val fresh = unwrap(payload)
            if (fresh.optString("accessToken").isBlank()) throw IOException("刷新登录响应缺少 accessToken")
            if (!fresh.has("refreshToken")) fresh.put("refreshToken", refresh)
            if (!fresh.has("domain")) fresh.put("domain", account.auth.optString("domain"))
            if (!fresh.has("expiresAt") && fresh.optLong("expiresIn") > 0) fresh.put("expiresAt", System.currentTimeMillis() + fresh.optLong("expiresIn") * 1000L)
            account.root.put("auth", fresh)
            store(context).clearAccountCooldown(account.key)
            // 刷新成功即视为账号健康：失败计数必须归零，否则一次网络抖动会永久压低
            // 该账号的选号权重（权重里成功率因子为 1/(1+failure_count)）。
            store(context).markAccountSuccess(account.key)
            saveAccount(context, account.root, account.region)
        } catch (e: Exception) {
            // 对齐上游 scheduler.do_keepalive：单次刷新失败只计数，连续失败达阈值才真正处罚。
            // 此前是「失败立刻冷却 60s」，一次网络抖动就把健康账号打进冷却池。
            store(context).recordRefreshFailure(account.key)
            throw e
        }
    }

    fun models(context: Context): JSONArray {
        val merged = linkedMapOf<String, JSONObject>()
        var lastError: Throwable? = null
        val availableRegions = enabledRegions(context)
        if (availableRegions.isEmpty()) throw IOException("尚未导入可用的 WorkBuddy 账号")
        for (region in availableRegions) {
            runCatching { models(context, region) }
                .onSuccess { mergeModels(merged, it, region) }
                .onFailure { lastError = it }
        }
        if (merged.isEmpty()) throw lastError ?: IOException("模型目录为空")
        return JSONArray().apply { merged.values.forEach(::put) }
    }

    /** Force-refresh every enabled region and expose partial failures to the UI. */
    fun refreshAllModels(context: Context): JSONObject {
        val regions = enabledRegions(context)
        if (regions.isEmpty()) throw IOException("尚未导入可用的 WorkBuddy 账号")
        val merged = linkedMapOf<String, JSONObject>()
        val failures = JSONArray()
        for (region in regions) {
            runCatching { refreshModels(context, region) }
                .onSuccess { mergeModels(merged, it, region) }
                .onFailure {
                    failures.put(JSONObject().put("region", region.id).put("region_label", region.label)
                        .put("message", it.message ?: "刷新失败"))
                    Log.w(TAG, "refresh models failed: ${region.id}", it)
                }
        }
        if (merged.isEmpty()) {
            val message = (0 until failures.length()).joinToString("；") {
                val item = failures.getJSONObject(it); "${item.optString("region_label")}：${item.optString("message")}"
            }
            throw IOException(message.ifBlank { "模型目录为空" })
        }
        return JSONObject().put("models", JSONArray().apply { merged.values.forEach(::put) }).put("failures", failures)
    }

    private fun enabledRegions(context: Context): List<AccountRegion> {
        val accounts = listAccounts(context)
        return (0 until accounts.length()).mapNotNull {
            accounts.optJSONObject(it)?.takeIf { item -> item.optBoolean("enabled", true) }
                ?.let { item -> AccountRegion.from(item.optString("region")) }
        }.distinct()
    }

    private fun mergeModels(target: LinkedHashMap<String, JSONObject>, models: JSONArray, region: AccountRegion) {
        for (i in 0 until models.length()) {
            val model = JSONObject(models.getJSONObject(i).toString())
            val id = model.optString("id")
            if (id.isBlank()) continue
            val existing = target[id]
            if (existing == null) {
                model.put("regions", JSONArray().put(region.id)); target[id] = model
            } else {
                val regions = existing.optJSONArray("regions") ?: JSONArray().also { existing.put("regions", it) }
                if ((0 until regions.length()).none { regions.optString(it) == region.id }) regions.put(region.id)
            }
        }
    }

    fun models(context: Context, region: AccountRegion): JSONArray = modelsForRegion(context, region)

    private fun modelsForRegion(context: Context, region: AccountRegion): JSONArray {
        val nowSec = System.currentTimeMillis() / 1000
        // 1) 内存缓存：命中即返回（毫秒级）。过期也先返回旧数据，再后台刷新，绝不阻塞客户端。
        modelSnapshots[region.id]?.let { snapshot ->
            if (snapshot.expiresAt <= nowSec) scheduleModelRefresh(context, region)
            return JSONArray(snapshot.payload)
        }
        // 2) DB 缓存：无论是否过期都直接返回（stale-while-revalidate），刷新交给后台。
        val cached = store(context).getModelCache(allowExpired = true, region = region.id)
        val stored = cached?.optJSONArray("models")
        if (stored != null && stored.length() > 0) {
            val expiresAt = cached.optDouble("expires_at", 0.0).toLong()
            rememberModels(region, stored, expiresAt)
            if (expiresAt <= nowSec) scheduleModelRefresh(context, region)
            return stored
        }
        // 3) 全新安装（无任何缓存）也不能让客户端干等：国际版立即返回内置官方目录，
        //    国内版限时等待一次真实拉取（最多 4 秒），超时返回空目录而非一直挂着。
        scheduleModelRefresh(context, region)
        if (region == AccountRegion.INTERNATIONAL) return bundledInternationalModels(context, region)
        return awaitFirstModels(context, region)
    }

    /** 无任何缓存时的国内版兜底：限时等待一次真实拉取；超时返回空目录而不是让客户端一直等。 */
    private fun awaitFirstModels(context: Context, region: AccountRegion): JSONArray {
        val app = context.applicationContext
        return runCatching {
            modelRefreshPool.submit<JSONArray> { refreshModels(app, region) }.get(4, TimeUnit.SECONDS)
        }.getOrElse {
            Log.w(TAG, "first model fetch for ${region.id} unavailable; returning empty", it)
            JSONArray()
        }
    }

    /** 内置官方目录（随 APK 打包）：国际版上游拉不到时也能秒回完整目录（含 auto 倍率）。 */
    private fun bundledInternationalModels(context: Context, region: AccountRegion): JSONArray = synchronized(this) {
        val payload = bundledInternational ?: loadBundledInternationalCatalog(context).also { bundledInternational = it }
        val models = catalogToModels(payload, region, payload)
        val ttl = store(context).getSettings().optLong("model_ttl_min", 60).coerceIn(1, 1440) * 60
        store(context).saveModelCache(models, "bundled", ttl, region.id)
        rememberModels(region, models, System.currentTimeMillis() / 1000 + ttl)
        models
    }

    fun modelsCached(context: Context): JSONArray {
        val merged = JSONArray()
        AccountRegion.entries.forEach { region ->
            (snapshotModels(region) ?: store(context).getModelCache(true, region.id)?.optJSONArray("models"))?.let { models ->
                for (i in 0 until models.length()) merged.put(models.getJSONObject(i))
            }
        }
        return merged
    }

    fun refreshModels(context: Context, region: AccountRegion): JSONArray {
        val selected = accountForRequest(context, region)
        val baseHeaders = headersFor(selected)
        val modelHeaders = baseHeaders.toMutableMap().apply {
            // 官方 CLI 不以浏览器身份拉模型目录：product.json 的 platform 就是 "CLI"，
            // 走的是插件链路。此前这里伪装成浏览器（Origin/Referer/浏览器 UA），
            // 造成同一账号「拉模型像网页、发对话像插件」的身份撕裂，
            // 而这正是账号被判异常时最容易被关联的特征。
            // 现统一保持 CLI 身份，headersFor 已带上完整产品识别头。
            this["Accept"] = "application/json"
        }
        val payload = try {
            executeJson(request("${region.backend}/console/enterprises/personal/models", "GET", null, modelHeaders))
        } catch (error: IOException) {
            if (region == AccountRegion.INTERNATIONAL) {
                Log.w(TAG, "international model endpoint unavailable; using bundled official CLI catalog", error)
                loadBundledInternationalCatalog(context)
            } else throw error
        }
        val ordered = catalogToModels(unwrap(payload), region, payload)
        val ttl = store(context).getSettings().optLong("model_ttl_min", 60).coerceIn(1, 1440) * 60
        store(context).saveModelCache(ordered, "dynamic", ttl, region.id)
        rememberModels(region, ordered, System.currentTimeMillis() / 1000 + ttl)
        return ordered
    }

    /** 把上游/内置目录（product.json 结构）转换成对外暴露的 OpenAI 风格模型条目，并统一处理 auto 重命名与倍率。 */
    private fun catalogToModels(data: JSONObject, region: AccountRegion, payload: JSONObject): JSONArray {
        // 国际版实测可用模型白名单（2026-09-13 真实账号逐个调用验证）。
        // 只有这些模型对当前账号/地域实际可用，其余 13 个均返回 11102 service info not found。
        // 使用「对外暴露名」：international 的 default-model -> auto，其余保持原样。
        val VERIFIED_AVAILABLE: Set<String> = setOf(
            "auto", "fast-model", "balanced-model", "primary-model", "deep-model",
            "gpt-5.5", "gpt-5.4", "gpt-5.3-codex", "gemini-3.1-pro", "kimi-k2.5",
            "gemini-3.0-pro-image", "gemini-3.1-flash-image", "gemini-2.5-flash-image",
            "gpt-5.6-terra", "gpt-5.6-luna", "glm-5.3", "glm-5.2", "hy3",
            "kimi-k3", "kimi-k2.6", "minimax-m3", "gemini-3.5-flash", "deepseek-v4.1-flash", "gpt-5.6-sol"
        )
        val all = data.optJSONArray("models")
            ?: data.optJSONObject("result")?.optJSONArray("models")
            ?: data.optJSONObject("catalog")?.optJSONArray("models")
            ?: throw IOException("${region.label}模型响应缺少 models，字段：${data.keys().asSequence().joinToString()}")
        // 官方目录（以及部分上游响应）只用 agents.cli 标记「推荐」模型，并不代表目录里其余模型不可用
        // ——国际版 product.json 定义了 35 个模型，其中只有 17 个进了 cli 白名单。旧实现直接用白名单裁掉
        // 目录，这就是「国际版模型拉取不全」的根因；现在改为全量暴露，把推荐集合作为标记交给 UI。
        val recommended = linkedSetOf<String>()
        val agents = data.optJSONArray("agents") ?: JSONArray()
        for (i in 0 until agents.length()) {
            val agent = agents.optJSONObject(i) ?: continue
            if (agent.optString("name").equals("cli", true)) {
                val ids = agent.optJSONArray("models") ?: JSONArray()
                for (j in 0 until ids.length()) recommended += when (val entry = ids.opt(j)) {
                    is JSONObject -> entry.optString("id", entry.optString("modelId"))
                    else -> entry?.toString().orEmpty()
                }
            }
        }
        // 内置目录把推荐清单同时放在顶层，兼容读取。
        payload.optJSONArray("cli_recommended")?.let { arr ->
            for (j in 0 until arr.length()) arr.opt(j)?.toString()?.takeIf { it.isNotBlank() }?.let(recommended::add)
        }
        if (all.length() == 0) throw IOException("${region.label}模型接口未返回模型目录")
        val catalogSource = payload.optString("source")
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val model = all.optJSONObject(i) ?: continue
            val upstreamId = model.optString("id", model.optString("modelId"))
            if (upstreamId.isBlank() || model.optBoolean("disabled")) continue
            // 国际版上游同时接受 default-model 与 auto（实测 /v2/chat/completions 两者均返回 200），统一暴露
            // 成 auto：既与国内版调用习惯一致，又不会因为改名丢掉官方 credits 倍率。
            val id = if (region == AccountRegion.INTERNATIONAL && upstreamId == "default-model") "auto" else upstreamId
            // 仅暴露实测可用的模型（international 使用对外名，domestic 使用原版上游名）
            if (region == AccountRegion.INTERNATIONAL && id !in VERIFIED_AVAILABLE) continue
            val entry = modelEntry(model, id)
            if (recommended.isNotEmpty()) entry.put("cli_recommended", recommended.contains(upstreamId))
            if (id != upstreamId) entry.put("upstream_model_id", upstreamId)
            if (region == AccountRegion.INTERNATIONAL && catalogSource.isNotBlank()) {
                entry.put("catalog_source", "国际版官方目录 · $catalogSource")
            }
            out.put(entry)
        }
        if (out.length() == 0) throw IOException("${region.label}模型接口未返回有效模型（目录 ${all.length()}）")
        // 注意：org.json 的 JSONArray.put(index, value) 是「替换 index 处元素」而不是「插入」。旧实现用
        // out.put(0, auto) 补 auto，恰好把目录首位的 Auto（国际版即 default-model，带 x0.79 倍率）顶掉，
        // 这正是「auto 不显示倍率」的根因。现在改为先查找、确实缺失才追加，并把 auto 重排到列表首位。
        if ((0 until out.length()).none { out.optJSONObject(it)?.optString("id") == "auto" }) {
            out.put(JSONObject().put("id", "auto").put("object", "model").put("created", 1700000000)
                .put("owned_by", "workbuddy").put("name", "Auto")
                .put("reasoning", JSONObject().put("supportsReasoning", true).put("onlyReasoning", true)))
        }
        val ordered = JSONArray()
        (0 until out.length()).mapNotNull { out.optJSONObject(it) }.firstOrNull { it.optString("id") == "auto" }
            ?.let(ordered::put)
        (0 until out.length()).forEach { index ->
            val item = out.optJSONObject(index) ?: return@forEach
            if (item.optString("id") != "auto") ordered.put(item)
        }
        return ordered
    }

    private fun loadBundledInternationalCatalog(context: Context): JSONObject {
        return runCatching {
            context.assets.open("codebuddy-international-models.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrElse { throw IOException("国际版模型接口不可用，且内置官方目录读取失败：${it.message}", it) }
    }

    private fun modelEntry(model: JSONObject, id: String = model.optString("id", model.optString("modelId"))): JSONObject {
        val images = model.optBoolean("supportsImages") && !model.optBoolean("disabledMultimodal")
        val reasoning = model.optJSONObject("reasoning") ?: JSONObject()
        if (!reasoning.has("supportsReasoning")) reasoning.put("supportsReasoning", model.optBoolean("supportsReasoning"))
        if (!reasoning.has("onlyReasoning")) reasoning.put("onlyReasoning", model.optBoolean("onlyReasoning"))
        val inputs = JSONArray().put("text").apply { if (images) put("image") }
        val entry = JSONObject().put("id", id).put("object", "model").put("created", 1700000000)
            .put("owned_by", "workbuddy").put("name", model.optString("name", id))
            .put("context_length", model.optLong("maxInputTokens")).put("max_output_tokens", model.optLong("maxOutputTokens"))
            .put("supports_images", images).put("supports_image", images).put("supports_tools", model.optBoolean("supportsToolCall"))
            .put("vision", images).put("image", images).put("modality", if (images) "multimodal" else "text")
            .put("modalities", inputs).put("input_modalities", inputs).put("output_modalities", JSONArray().put("text"))
            .put("reasoning", reasoning)
        // 成本倍率（credits）：上游给的是 "x0.79 credits" / "x0.05" 这类文本，也有直接给数字的。
        // 解析成功才写入字段——UI 靠「字段是否存在」区分「上游没标注倍率」与「0 倍率＝免费」。
        parseCredits(model.opt("credits"))?.let { entry.put("credits", it) }
        model.optString("vendor").takeIf { it.isNotBlank() }?.let { entry.put("vendor", it) }
        return entry
    }

    /** 解析上游成本倍率（credits）：支持 "x0.79 credits" / "x0.05" 字符串与数值；无法识别返回 null。 */
    private fun parseCredits(raw: Any?): Double? {
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is Number) return raw.toDouble().takeIf { it.isFinite() }
        val token = raw.toString().trim().removePrefix("x").removePrefix("X").trim()
            .split(' ').firstOrNull().orEmpty()
        return token.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * 单账号签到。
     *
     * 与 WorkBuddy 1.2.7 的语义对齐（a.C0045h2 中的 daily-checkin 实现）：
     *   1) 签到【前】先记下当前剩余额度（credits_before）；
     *   2) 调 /v2/billing/meter/daily-checkin；
     *   3) 签到【后】重新查询额度（credits_remaining / credits_total）；
     *   4) 把前后差值与总额度一并返回。
     *
     * 为什么要做第 1 步与第 3 步：
     *   签到接口本身【不返回积分】，它只负责"打卡"这一动作。积分是否到账、
     *   到账多少，只能通过对比签到前后的额度快照才能看出来。原实现只调了签到
     *   接口、既不记快照也不复查，界面因此永远显示不出"今天签到得了多少分"，
     *   看起来就像"签到了但没拿到积分"。
     */
    fun checkIn(context: Context, accountKey: String): JSONObject {
        val account = loadAccount(context, accountKey) ?: throw IOException("账号不存在：$accountKey")
        if (account.region == AccountRegion.INTERNATIONAL) {
            return JSONObject(refreshCredits(context, account.key).toString())
                .put("nickname", account.nickname).put("ok", true)
                .put("checkin_supported", false).put("credits_refreshed", true)
                .put("message", "国际版额度由上游自动发放，已刷新当前额度")
        }

        // ① 签到前快照。
        //    优先用本地缓存（省一次请求）；若从未刷新过额度（缓存为空），
        //    则主动查一次 —— 否则 gained 永远算不出来，界面会一直显示
        //    "缺少签到前快照"，那对用户是无意义的噪声。
        val before = readCachedCredits(context, account.key)
            ?: runCatching { refreshCredits(context, account.key).optDouble("remain") }.getOrNull()

        val h = headersForFresh(context, account).apply { this["User-Agent"] = ClientIdentity.BROWSER_UA }
        val data = executeJson(
            request("${account.backend}/v2/billing/meter/daily-checkin", "POST", JSONObject(), h),
            allowHttpError = true
        )
        val msg = data.optString("msg", data.optString("message"))
        val already = msg.contains("已签到") || msg.contains("already checked", true) || msg.contains("already signed", true)
        val ok = data.optInt("code", -1) == 0 || already
        if (!ok) throw IOException(if (msg.isBlank()) "签到失败" else msg)

        store(context).setCheckinDate(account.key, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        store(context).clearAccountCooldown(account.key)

        // ② 签到后复查额度：这一步同时把最新额度写回账号缓存，界面无需再手动刷新。
        // 注意字段名是 remain / total（refreshCredits → fetchCredits 的返回约定），
        // 不是 credits_remaining —— 后者是账号列表接口里的字段，两者容易混。
        val after = runCatching { refreshCredits(context, account.key) }.getOrNull()
        val remaining = after?.optDouble("remain")
        val total = after?.optDouble("total")
        val gained = if (before != null && remaining != null) remaining - before else null

        // 记入积分账本。
        //
        // 行为变更（2026-10-29）：**签到成功就记一条**，即使差额是 0 或算不出来。
        //
        // 原逻辑是 `if (gained != null && gained != 0.0)` 才写 —— 后果是：
        //   · 上游次日到账、或积分按批次发放时，差值是 0 → 不记账
        //   · 签到后那次 refreshCredits 失败 → gained 为 null → 不记账
        // 两种情况下界面显示「今日已签」，但「今日收支」里查无此项，
        // 用户看到的就是「签到了但没计入收支」。
        //
        // 现在的语义是「记录签到这个事件及其可得的收益」：
        //   · 算得出非零差额 → 记真实数额（与原来一致）
        //   · 算出来是 0 或算不出 → 记 0，note 里说明情况
        // 记 0 不会让收支数字虚高（求和加 0 无影响），但让「今天签到过」
        // 这件事在账本里可见 —— 这比精确数额更重要。
        val note = when {
            already -> "补记签到变动"
            gained == null -> "每日签到（到账数额未确认）"
            gained == 0.0 -> "每日签到（上游未即时到账）"
            else -> "每日签到"
        }
        store(context).recordCredit(account.key, "checkin", gained ?: 0.0, note)

        return JSONObject()
            .put("uid", account.uid).put("account_key", account.key)
            .put("region", account.region.id).put("region_label", account.region.label)
            .put("nickname", account.nickname)
            .put("ok", true).put("already", already).put("checkin_supported", true)
            .put("credits_before", before ?: JSONObject.NULL)
            .put("credits_remaining", remaining ?: JSONObject.NULL)
            .put("credits_total", total ?: JSONObject.NULL)
            .put("credits_gained", gained ?: JSONObject.NULL)
            .put("message", buildCheckinMessage(already, before, remaining, gained))
    }

    /**
     * 拼签到结果文案。
     *
     * 四种情况，核心是【不谎报】：
     *   · 到账   → 明确写出 +N 与当前剩余
     *   · 未变化 → 说明签到额可能随后续活跃行为计分（用户最常见的困惑）
     *   · 减少   → 如实显示负数（可能是其它请求消耗，不是签到扣的）
     *   · 查不到 → 说明查询失败并给出下一步，而不是显示 0 分
     */
    private fun buildCheckinMessage(
        already: Boolean,
        before: Double?,
        remaining: Double?,
        gained: Double?
    ): String {
        val head = if (already) "今日已签到" else "签到成功"
        val remainText = remaining?.let { "%.1f".format(it) }
        return when {
            gained != null && gained > 0 ->
                "$head，本次 +%.1f 积分（当前剩余 $remainText）".format(gained)
            gained != null && gained == 0.0 ->
                "$head，本次未增加积分（当前剩余 $remainText）。签到本身的奖励可能随后续活跃行为计分。"
            gained != null ->
                "$head（当前剩余 $remainText）。额度比签到前少 %.1f，通常是期间其它请求的消耗。".format(-gained)
            else ->
                "$head（额度查询失败，请稍后在账号页手动刷新）"
        }
    }

    /** 读取本地缓存的剩余额度；未刷新过则返回 null。 */
    /**
     * 读取本地缓存的剩余额度；未刷新过则返回 null。
     *
     * 注意取值路径：账号 JSON 由 NativeStore.accountJson 用 rowJson 平铺生成，
     * credits_remaining 就在【根对象上】。
     * 早先误取 account.profile（即 root.account），而账号表没有嵌套的 account 子对象，
     * 于是永远读到 null，签到结果恒定显示"缺少签到前快照"。
     */
    private fun readCachedCredits(context: Context, accountKey: String): Double? {
        val account = loadAccount(context, accountKey) ?: return null
        val root = account.root
        if (root.isNull("credits_remaining")) return null
        val value = root.optDouble("credits_remaining", Double.NaN)
        return if (value.isFinite()) value else null
    }

    fun checkInAll(context: Context): JSONObject {
        val accounts = listAccounts(context)
        if (accounts.length() == 0) throw IOException("尚未导入 WorkBuddy 账号")
        val results = JSONArray()
        for (i in 0 until accounts.length()) {
            val item = accounts.getJSONObject(i)
            val uid = item.getString("uid")
            val accountKey = item.optString("account_key", uid)
            val nickname = item.optString("nickname", uid)
            if (!item.optBoolean("enabled", true)) {
                results.put(JSONObject().put("uid", uid).put("account_key", accountKey).put("region", item.optString("region")).put("region_label", item.optString("region_label")).put("nickname", nickname).put("ok", false)
                    .put("skipped", true).put("checkin_supported", AccountRegion.from(item.optString("region")) != AccountRegion.INTERNATIONAL)
                    .put("message", "账号已停用，已跳过"))
                continue
            }
            if (AccountRegion.from(item.optString("region")) == AccountRegion.INTERNATIONAL) {
                // 国际版没有"签到"这个动作（额度由上游自动发放），但此前是硬跳过，
                // 结果是用户点了「一键签到」后只看到"已跳过"，不知道额度到底多少。
                // 改为顺手刷新一次额度：这样至少能在结果里确认它的当前状态，
                // 与「额度自动发放」的语义一致，也不浪费这次点击。
                val refreshed = runCatching { refreshCredits(context, accountKey) }
                results.put(
                    refreshed.fold(
                        onSuccess = { r ->
                            JSONObject()
                                .put("uid", uid).put("account_key", accountKey)
                                .put("region", item.optString("region"))
                                .put("region_label", item.optString("region_label"))
                                .put("nickname", nickname)
                                .put("ok", true).put("skipped", true).put("checkin_supported", false)
                                .put("credits_remaining", r.optDouble("remain"))
                                .put("credits_total", r.optDouble("total"))
                                .put("message", "国际版无签到概念（额度自动发放），已刷新额度：剩余 %.1f / 总计 %.1f"
                                    .format(r.optDouble("remain"), r.optDouble("total")))
                        },
                        onFailure = { e ->
                            JSONObject()
                                .put("uid", uid).put("account_key", accountKey)
                                .put("region", item.optString("region"))
                                .put("region_label", item.optString("region_label"))
                                .put("nickname", nickname)
                                .put("ok", false).put("skipped", true).put("checkin_supported", false)
                                .put("message", "国际版无签到概念；刷新额度失败：${e.message ?: "未知错误"}")
                        }
                    )
                )
                continue
            }
            runCatching { checkIn(context, accountKey) }
                .onSuccess { results.put(it) }
                .onFailure { results.put(JSONObject().put("uid", uid).put("account_key", accountKey).put("region", item.optString("region")).put("region_label", item.optString("region_label")).put("nickname", nickname)
                    .put("ok", false).put("checkin_supported", true).put("message", it.message ?: "签到失败")) }
        }
        return JSONObject().put("results", results)
    }

    fun creditsSummary(context: Context): JSONObject {
        val accounts = listAccounts(context)
        var remain = 0.0; var total = 0.0; var known = 0
        for (i in 0 until accounts.length()) {
            val item = accounts.getJSONObject(i)
            if (!item.isNull("credits_remaining")) {
                remain += item.optDouble("credits_remaining")
                total += item.optDouble("credits_total")
                known++
            }
        }
        return JSONObject().put("accounts", accounts.length()).put("known", known)
            .put("remain", remain).put("total", total)
    }

    /** 全池今日收支（见 NativeStore.creditFlowToday）。 */
    fun creditFlowToday(context: Context): JSONObject = store(context).creditFlowToday()

    /** 各账号运行态快照（见 NativeStore.accountHealth）。 */
    fun accountHealth(context: Context): JSONArray = store(context).accountHealth()

    fun refreshAllCredits(context: Context): JSONObject {
        val accounts = listAccounts(context)
        if (accounts.length() == 0) throw IOException("尚未导入 WorkBuddy 账号")
        val results = JSONArray()
        var succeeded = 0
        for (i in 0 until accounts.length()) {
            val item = accounts.getJSONObject(i)
            val uid = item.getString("uid")
            val accountKey = item.optString("account_key", uid)
            val nickname = item.optString("nickname", uid)
            runCatching { refreshCredits(context, accountKey) }
                .onSuccess { results.put(JSONObject(it.toString()).put("nickname", nickname).put("ok", true)); succeeded++ }
                .onFailure { results.put(JSONObject().put("uid", uid).put("account_key", accountKey).put("region", item.optString("region")).put("region_label", item.optString("region_label")).put("nickname", nickname)
                    .put("ok", false).put("message", it.message ?: "刷新失败")) }
        }
        if (succeeded == 0) {
            val errors = (0 until results.length()).joinToString("；") {
                val item = results.getJSONObject(it); "${item.optString("nickname")}：${item.optString("message")}"
            }
            throw IOException(errors.ifBlank { "全部账号额度刷新失败" })
        }
        return JSONObject().put("results", results).put("summary", creditsSummary(context))
    }

    fun refreshCredits(context: Context, accountKey: String): JSONObject {
        val account = loadAccount(context, accountKey) ?: throw IOException("账号不存在：$accountKey")
        return fetchCredits(context, account)
    }

    private fun fetchCredits(context: Context, source: Account): JSONObject {
        val account = headersForFresh(context, source)
        // 计费接口必须走浏览器 UA：上游 billing.py:44-47 注释明确网关 WAF 会拦非浏览器 UA
        // 的计费请求（403/10085）。主链路不得使用此 UA（见 ClientIdentity 注释）。
        val h = account.apply { this["User-Agent"] = ClientIdentity.BROWSER_UA }
        val bodies = listOf(
            "${source.backend}/billing/meter/get-user-resource-summary" to JSONObject(),
            "${source.backend}/v2/billing/meter/get-user-resource" to JSONObject().put("PageNumber", 1).put("PageSize", 100).put("ProductCode", "p_tcaca").put("Status", JSONArray().put(0).put(3))
        )
        var accounts = JSONArray()
        for ((url, body) in bodies) {
            val response = runCatching { executeJson(request(url, "POST", body, h), true) }.getOrNull() ?: continue
            accounts = extractBillingAccounts(response)
            if (accounts.length() > 0) break
        }
        if (accounts.length() == 0) throw IOException("额度接口未返回账号数据")
        var remain = 0.0; var total = 0.0; var expire: Double? = null; val packages = JSONArray()
        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val cycle = a.optDouble("CycleCapacitySize", 0.0)
            val r = if (cycle > 0) a.optDouble("CycleCapacityRemain") else a.optDouble("CapacityRemain")
            val t = if (cycle > 0) cycle else a.optDouble("CapacitySize")
            remain += r; total += t
            // 每个包的到期时刻单独算，一起存进 packages —— 界面要按包展示
            // 「这笔还剩多少、哪天作废」，只有一个总额度的最早到期日不够用。
            val pkgExpire = expirySecondsOf(a, System.currentTimeMillis() / 1000.0)
            // 统一的包 schema。
            //
            // 原先直接把上游原始对象整份塞进来（JSONObject(a.toString())），
            // 于是界面拿到的是 CycleCapacitySize / CapacityRemain / ProductCode
            // 这类上游字段名，各处只能靠猜。现在显式投影成稳定字段：
            //   remaining / total / expire_at   —— 界面唯一依赖的三个
            //   label                          —— 可读名称，取不到就给 code
            //   is_cycle                       —— 是否周期额度（决定展示措辞）
            // 原始对象仍保留在 raw 里，需要排查上游字段变化时可查。
            packages.put(
                JSONObject()
                    .put("remaining", r)
                    .put("total", t)
                    .put("expire_at", pkgExpire ?: JSONObject.NULL)
                    .put("is_cycle", cycle > 0)
                    .put("label", a.optString("PackageName").ifBlank {
                        a.optString("ProductName").ifBlank {
                            a.optString("ProductCode").ifBlank { if (cycle > 0) "周期额度" else "总额度" }
                        }
                    })
                    .put("raw", JSONObject(a.toString()))
            )
            // 账号级的「最早到期」只统计还有余量的包：
            // 一个已用尽的包即使更早到期，也不该影响选号时的临期判定。
            if (pkgExpire != null && r > 0) {
                expire = expire?.coerceAtMost(pkgExpire) ?: pkgExpire
            }
        }
        store(context).setAccountCredits(source.key, remain, total, expire, packages)
        return JSONObject().put("uid", source.uid).put("account_key", source.key).put("region", source.region.id).put("region_label", source.region.label).put("remain", remain).put("total", total).put("expire_at", expire ?: JSONObject.NULL).put("packages", packages)
    }

    fun beginOAuth(region: AccountRegion): OAuthSession {
        val raw = executeJson(request("${region.backend}/v2/plugin/auth/state?platform=CLI", "POST", JSONObject(), noAuthHeaders(region)))
        val data = unwrap(raw); val state = data.optString("state"); val url = data.optString("authUrl")
        if (state.isBlank() || url.isBlank()) throw IOException("${region.label}登录接口未返回 state/authUrl")
        return OAuthSession(region, state, url)
    }

    fun pollOAuth(context: Context, session: OAuthSession): Account? {
        val encoded = URLEncoder.encode(session.state, "UTF-8"); val noAuth = noAuthHeaders(session.region)
        val tokenRaw = runCatching { executeJson(request("${session.region.backend}/v2/plugin/auth/token?state=$encoded", "GET", null, noAuth), true) }.getOrNull() ?: return null
        val token = runCatching { unwrap(tokenRaw) }.getOrNull() ?: return null
        if (token.optString("accessToken").isBlank()) return null
        if (token.optString("domain").isBlank()) token.put("domain", session.region.defaultDomain)
        val h = noAuth.toMutableMap().apply {
            this["Authorization"] = "Bearer ${token.optString("accessToken")}"; token.optString("domain").takeIf { it.isNotBlank() }?.let { this["X-Domain"] = it }
            token.optString("enterpriseId").takeIf { it.isNotBlank() }?.let { this["X-Enterprise-Id"] = it; this["X-Tenant-Id"] = it }
        }
        val profile = runCatching { unwrap(executeJson(request("${session.region.backend}/v2/plugin/login/account?state=$encoded", "GET", null, h), true)) }.getOrNull() ?: return null
        if (profile.optString("uid").isBlank()) return null
        return saveAccount(context, JSONObject().put("region", session.region.id).put("auth", token).put("account", profile), session.region)
    }

    /** 官方 OpenAIProvider 请求体的字段顺序（JSON.stringify 序列化顺序，上游可逐位观测）。 */
    private val BODY_FIELD_ORDER = listOf(
        "model", "messages", "tools", "temperature", "top_p", "frequency_penalty",
        "presence_penalty", "max_tokens", "tool_choice", "parallel_tool_calls",
        "stream", "stream_options", "store", "prompt_cache_retention", "response_format"
    )

    /**
     * 官方固定字段之外、允许透传的模型扩展参数。
     *
     * 对应官方的 `...a`（providerData 展开）与 `max_completion_tokens` 替换规则：
     * 官方按模型能力表把 max_tokens 改名为 max_completion_tokens（o1/o3/o4/gpt-4.1/gpt-5 系），
     * 那是 RequestRules 的事；这里只保留「模型能力差异参数」本身，不再接受 stop/seed/user/n
     * 这些官方根本不发的字段——多发一个官方没有的键，比少发更容易被识别。
     */
    private val EXTRA_FIELD_ALLOWLIST = setOf(
        "max_completion_tokens", "reasoning_effort", "verbosity", "reasoning_summary", "thinking"
    )

    /**
     * @param sessionKey 会话标识。非空时同会话固定同账号（会话粘性），
     *   避免多轮对话因换号导致上游上下文丢失。见 NativeStore.selectAccountForSession。
     */
    fun upstreamRequest(
        context: Context,
        body: JSONObject,
        requiredRegion: AccountRegion,
        sessionKey: String? = null,
    ): UpstreamCall {
        // 此前这里是「白名单过滤」：把客户端 body 里允许的键拷贝进一个新 JSONObject。
        // 问题不只是顺序——白名单本身就和官方不一致（官方不发 stop/seed/user/n 等，
        // 却会发 store/prompt_cache_retention/parallel_tool_calls）。
        //
        // 官方请求体（dist/lazy/335.13737b8c.js @177028 OpenAIProvider）是固定字段顺序：
        //   model → messages → tools(无则整体剔除) → temperature → top_p →
        //   frequency_penalty → presence_penalty → max_tokens → tool_choice →
        //   parallel_tool_calls → stream → stream_options → store →
        //   prompt_cache_retention → ...providerData → response_format(仅存在时)
        // 已实测 Android JSONObject 底层为 LinkedHashMap：按序 put 即按序序列化，
        // 且 remove 后再 put 会移到末尾（dex 实测 ORDER2={"a":1,"c":3,"b":9}），
        // 因此「先按官方顺序搬运 + 事后补默认值」能精确复刻官方字节序。
        val filtered = JSONObject()
        BODY_FIELD_ORDER.forEach { key ->
            if (body.has(key)) {
                val value = body.opt(key)
                // 官方用 `tools: i.length ? i : void 0` —— 空数组等于不发该字段。
                // JSON.stringify 会丢掉 undefined 键，故这里必须整体剔除而非发 []。
                if (key == "tools" && value is JSONArray && value.length() == 0) return@forEach
                filtered.put(key, value)
            }
        }
        if (!filtered.has("model")) filtered.put("model", "auto")
        filtered.put("stream", true)
        if (!filtered.has("stream_options")) filtered.put("stream_options", JSONObject().put("include_usage", true))
        // 官方 providerData 展开在固定字段之后（`...a` 位于 response_format 之前），
        // 这里承接客户端带来的模型扩展参数（reasoning_effort/verbosity 等）。
        body.keys().forEach { key ->
            if (key !in BODY_FIELD_ORDER && key in EXTRA_FIELD_ALLOWLIST) filtered.put(key, body.get(key))
        }
        val account = accountForRequest(context, requiredRegion, sessionKey)
        // 主对话走会话头版本：官方每一笔对话请求都挂会话上下文，
        // 这也是「反代看起来像 CLI」最关键的一层（详见 headersForConversation 注释）。
        val url = "${account.backend}/v2/chat/completions"
        val headers = headersForConversation(context, account)
        // 出网取证：记录【最终交给 OkHttp 的那一份】headers/body。
        //
        // 为什么要在这里记、而不是在 request() 里统一记：
        //   request() 还被 token 刷新/拉模型/签到等辅助链路复用，那些请求不带会话头，
        //   与本比对目标无关，混进去只会稀释信噪比。upstreamRequest 是唯一走
        //   headersForConversation 的出口，正好对应「主对话出网」这一件事。
        //
        // 为什么要复用 isNotEmpty 过滤：
        //   request() 实际发送时会跳过空值头（headers.forEach { if (v.isNotEmpty()) ... }），
        //   若记录时不照做，导出文本就会多出「实际没发」的头，比对时反而误导。
        //   这里复现同一规则，保证「记录 == 出网」。
        val sentHeaders = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> if (v.isNotEmpty()) sentHeaders[k] = v }
        val bodyText = filtered.toString()
        RequestInspector.record(
            context = context,
            endpoint = url,
            method = "POST",
            headers = sentHeaders,
            body = bodyText,
            accountKey = account.uid
        )
        return UpstreamCall(request(url, "POST", filtered, headers), account)
    }

    fun logUsage(context: Context, record: JSONObject): Long = store(context).logUsage(record)
    fun usageSummary(context: Context): JSONObject = store(context).usageSummary()
    fun usageRecent(context: Context, page: Int = 1, pageSize: Int = 20, protocol: String? = null, model: String? = null, appName: String? = null, status: String? = null, light: Boolean = true): JSONObject = store(context).usageRecent(page, pageSize, protocol, model, appName, status, light)

    /**
     * 读取单条记录的完整内容（含 input_content / output_content / reasoning_content）。
     *
     * 与 usageRecent 的 light 模式配套：列表只取元信息列，正文按需单条查询，
     * 避免一次拉 100 条正文导致内存与查询开销暴涨。
     */
    fun getUsage(context: Context, id: Long): JSONObject? = store(context).getUsage(id)
    fun usageDetail(context: Context, id: Long): JSONObject? = store(context).getUsage(id)
    fun usageFilters(context: Context): JSONObject = store(context).usageFilters()
    fun usageTimeseries(context: Context, granularity: String = "hour", points: Int = 24, model: String? = null): JSONArray = store(context).usageTimeseries(granularity, points, model)
    fun trimUsage(context: Context, inputLimit: Int = 4000, outputLimit: Int = 8000, reasoningLimit: Int = 8000): Int = store(context).trimUsageContent(inputLimit, outputLimit, reasoningLimit)
    fun cleanupUsage(context: Context, retentionDays: Int): Int = store(context).cleanupUsage(retentionDays)
    fun storageInfo(context: Context): JSONObject = store(context).storageInfo()
    fun vacuum(context: Context) = store(context).vacuum()

    /**
     * 递归测量一个目录的总字节数，用于诊断"数据到底被谁占了"。
     *
     * 之所以要自己扫而不是看系统设置：系统只给一个总数（例如"数据 200MB"），
     * 完全看不出是数据库、WebView 缓存还是外部缓存占的，等同于盲猜。
     * 这里逐个目录量体积，让结论建立在实测数字上。
     */
    fun dirSize(dir: java.io.File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    /** 汇总各关键目录占用，返回可直接展示的 JSON（全部为实测字节数）。 */
    fun storageBreakdown(context: Context): JSONObject {
        val dataDir = context.dataDir
        fun size(path: String) = dirSize(java.io.File(dataDir, path))
        val dbFile = context.getDatabasePath("workbuddy-native.db")
        return JSONObject()
            .put("databases", size("databases") + dirSize(java.io.File(dbFile.path + "-wal")) + dirSize(java.io.File(dbFile.path + "-shm")))
            .put("cache", dirSize(context.cacheDir))
            .put("webview", size("app_webview"))
            .put("files", size("files"))
            .put("shared_prefs", size("shared_prefs"))
            .put("code_cache", dirSize(context.codeCacheDir))
            .put("no_backup", dirSize(context.noBackupFilesDir))
            .put("external_cache", runCatching { dirSize(context.externalCacheDir) }.getOrDefault(0L))
            .put("external_files", runCatching { dirSize(context.getExternalFilesDir(null)) }.getOrDefault(0L))
            .put("data_total", dirSize(dataDir))
    }

    /** 清理 WebView 的全部磁盘缓存（HTTP/代码/GPU/ServiceWorker/存储）。 */
    fun clearWebViewCache(context: Context) {
        runCatching {
            android.webkit.WebStorage.getInstance().deleteAllData()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
        // WebView 实例化后才好清内部缓存；无 WebView 时 deleteAllData 已覆盖大部分。
        runCatching {
            val web = android.webkit.WebView(context)
            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()
            web.destroy()
        }
    }
    fun deleteUsage(context: Context, id: Long): Boolean = store(context).deleteUsage(id)
    fun clearUsage(context: Context, vacuum: Boolean = true): Int = store(context).clearUsage(vacuum)
    fun settings(context: Context): JSONObject = store(context).getSettings()
    fun saveSettings(context: Context, values: JSONObject): JSONObject = store(context).saveSettings(values)

    // ---------------------------------------------------------------- 积分详情

    /** 记一笔积分变动（正=获得，负=消耗）。 */
    fun recordCredit(context: Context, accountKey: String, kind: String, amount: Double, note: String = ""): Boolean =
        store(context).recordCredit(accountKey, kind, amount, note)

    /**
     * 今日各账号的积分收益汇总，供「成长」页顶部展示。
     *
     * 解决的是"任务做完了但不知道哪个号加了分"——原先只有执行当下的一行
     * 文本，切页即消失，多账号时要逐个切过去对比余额。
     *
     * @return [{account_key, kind, total, count}]；account_key 已替换成可读昵称
     */
    fun todaysGains(context: Context): JSONArray {
        val raw = store(context).creditGainsToday()
        // 把 account_key（region:uid）换成可读的「昵称·短UID」。
        // 保留 account_key 原值：调用方可能还要按它去跳转账号详情。
        val nameByKey = runCatching {
            val out = HashMap<String, String>()
            val all = store(context).listAccounts()
            for (i in 0 until all.length()) {
                val a = all.optJSONObject(i) ?: continue
                val region = a.optString("region", "domestic")
                val uid = a.optString("uid")
                if (uid.isBlank()) continue
                out["$region:$uid"] = a.optString("nickname").ifBlank { uid.take(8) }
            }
            out
        }.getOrDefault(emptyMap())

        return JSONArray().apply {
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val key = item.optString("account_key")
                put(
                    item.put("label", nameByKey[key] ?: key.substringAfter(':').take(8))
                        .put("note", if (item.optString("kind") == "task") "任务" else "签到/奖励")
                )
            }
        }
    }

    /** 清空某账号积分账本。 */
    fun clearCreditLedger(context: Context, accountKey: String): Int = store(context).clearCreditLedger(accountKey)

    /**
     * 某账号的完整积分详情。
     *
     * 由三部分拼成，缺一不可：
     *   ① 上游实时余额与资源包（fetchCredits）—— 真实剩余与到期时间，
     *      并按资源包拆开，能看到额度由哪几笔发放构成；
     *   ② 本地账本汇总（creditLedgerSummary）—— 区分签到/奖励/消耗，
     *      因为上游没有流水接口，这部分只能靠本地记账；
     *   ③ 本地账本明细（creditLedger）—— 逐笔记录，供核对。
     *
     * 上游刷新失败时不抛异常，而是带上 refresh_error 字段继续返回本地数据 ——
     * 用户关心的是"我的积分去哪了"，网络不通不应该让整个页面打不开。
     */
    fun creditDetail(context: Context, accountKey: String, ledgerLimit: Int = 200): JSONObject {
        val account = loadAccount(context, accountKey)
        val result = JSONObject().put("account_key", accountKey)
        result.put("nickname", account?.nickname ?: "")
        result.put("uid", account?.uid ?: "")
        result.put("region_label", account?.region?.label ?: "")

        // ① 上游实时
        val fresh = runCatching { fetchCredits(context, account ?: throw IOException("账号不存在")) }
        fresh.onSuccess { r ->
            result.put("remaining", r.optDouble("remain", 0.0))
            result.put("total", r.optDouble("total", 0.0))
            result.put("expire_at", r.opt("expire_at") ?: JSONObject.NULL)
            result.put("packages", r.optJSONArray("packages") ?: JSONArray())
        }.onFailure { e ->
            result.put("refresh_error", e.message ?: "刷新失败")
            // 退回本地缓存值，保证页面仍可打开
            val cached = loadAccount(context, accountKey)
            result.put("remaining", cached?.root?.optDouble("credits_remaining", 0.0) ?: 0.0)
            result.put("total", cached?.root?.optDouble("credits_total", 0.0) ?: 0.0)
            result.put("expire_at", cached?.root?.opt("credits_expire_at") ?: JSONObject.NULL)
            result.put("packages", runCatching {
                JSONArray(cached?.root?.optString("credit_packages_json").orEmpty())
            }.getOrDefault(JSONArray()))
        }

        // ②③ 本地账本
        result.put("ledger_summary", store(context).creditLedgerSummary(accountKey))
        result.put("ledger", store(context).creditLedger(accountKey, ledgerLimit))
        // last_checkin_date 是 accounts 表的顶层列（rowJson 直接映射），
        // 不在 auth_json 里 —— 所以必须读 store 的行记录，不能读 account.root。
        //
        // 改动前写的是 account?.root?.optString("last_checkin_date")，root 是
        // auth_json 解析出来的对象，里面根本没这个字段，永远返回空串
        // → 积分详情永远显示「未签到」，而账号卡读的是行记录，显示「已签到」。
        // 这就是「两处签到状态不一致」的根因。
        result.put(
            "last_checkin_date",
            store(context).getAccount(accountKey)?.optString("last_checkin_date").orEmpty()
        )
        return result
    }

    fun request(url: String, method: String, body: JSONObject?, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url); headers.forEach { (k, v) -> if (v.isNotEmpty()) builder.header(k, v) }
        return if (method.equals("GET", true)) builder.get().build() else builder.method(method.uppercase(Locale.US), (body ?: JSONObject()).toString().toRequestBody(JSON)).build()
    }

    /**
     * 执行一次性（非流式）请求并解析 JSON。
     *
     * 内置重试，针对移动网络下的瞬时故障（连接被重置、DNS 抖动、
     * 复用到已关闭的 keep-alive 连接等）。这些故障重发一次通常就成功，
     * 而此前是一次失败就抛给上层，用户看到的就是"上游经常断开"。
     *
     * 为什么可以安全重试：本方法只用于非流式请求（流式响应走 body.source()，
     * 不经过这里）。因此整个响应体在内存中，重试不会导致半截数据或重复消费。
     * 对 GET/查询类天然幂等；对 POST 类，上游接口在其自身的幂等/状态判定下
     * （如签到返回 already、领奖返回 already_claimed）也能安全重放。
     *
     * 重试策略：最多 2 次额外尝试，退避 400ms / 1200ms。仅重试网络层异常，
     * HTTP 状态码错误不重试 —— 那是业务结果，重发没有意义。
     */
    fun executeJson(request: Request, allowHttpError: Boolean = false): JSONObject {
        var lastError: Exception? = null
        val delays = longArrayOf(400L, 1200L)
        for (attempt in 0..delays.size) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!allowHttpError && !response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${text.take(240)}")
                    }
                    return runCatching { JSONObject(text) }
                        .getOrElse { throw IOException("服务器返回非 JSON：${text.take(160)}") }
                }
            } catch (e: IOException) {
                // HTTP 错误是我们自己抛的 IOException，用前缀区分，不参与重试
                if (e.message?.startsWith("HTTP ") == true || e.message?.startsWith("服务器返回非 JSON") == true) throw e
                lastError = e
                if (attempt < delays.size) {
                    Log.w(TAG, "request failed (attempt ${attempt + 1}), retrying in ${delays[attempt]}ms: ${e.message}")
                    runCatching { Thread.sleep(delays[attempt]) }
                }
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    private fun currentAccount(context: Context): Account {
        val selected = store(context).selectAccount() ?: throw IOException("尚未导入可用的 WorkBuddy 账号")
        return loadAccount(context, selected.getString("uid")) ?: throw IOException("账号认证数据缺失")
    }
    private fun headersForFresh(context: Context, account: Account): MutableMap<String, String> {
        val expiry = normalizedExpiryMillis(account.auth)
        return headersFor(if (expiry > 0 && System.currentTimeMillis() >= expiry - 60_000L) refreshToken(context, account) else account)
    }
    private fun normalizedExpiryMillis(auth: JSONObject): Long {
        val raw = auth.optLong("expiresAt", 0L)
        return if (raw in 1..9_999_999_999L) raw * 1000L else raw
    }
    /**
     * 一个资源包的「作废时刻」（epoch 秒）；无法判定时返回 null。
     *
     * 字段选择的依据（来自 workbuddy2api-hub 的实测校正，2026-10-08，31 行真实包数据）：
     *
     *  1) **必须优先 DeductionEndTime**（抵扣截止时间），而不是 CycleEndTime。
     *     免费包 / 体验版这类包的 CycleEndTime 只是「每月计费周期的边界」，
     *     与真正的抵扣截止能差 8 年。只认周期结束会让这些号每到月底都被
     *     误判成「即将到期」，反而被优先派分 —— 结果是把流量全推给它们。
     *
     *  2) **哨兵值必须过滤**。上游用一个远超任何真实计费周期的时间表示
     *     「不会过期」：Free Plan Subscription 与个人体验版的 DeductionEndTime
     *     落在 2034/2035 年，而真实包周期是 14 天或 1 个月。
     *     超过 [EXPIRY_SENTINEL_DAYS] 天一律当作「不过期」返回 null，
     *     否则这些号会永久命中「7 天内到期」的临期优先分支，霸占所有请求。
     *
     * 单位处理：上游给的是 epoch 毫秒，也有按秒返回的情况（历史接口），
     * 用 1e11 作为分界 —— 1e11 秒是公元 5138 年，任何合理的秒级时间戳都小于它。
     *
     * @param now 当前时刻（秒），由调用方传入以便测试注入
     */
    private fun expirySecondsOf(pkg: JSONObject, now: Double): Double? {
        // 顺序即优先级：DeductionEndTime 最可信，CycleEndTime 只在缺失时回退。
        // ExpireTime / PackageEndTime / EndTime 保留在末尾，属于历史字段名，
        // 实测较少见但无害 —— 能取到就用。
        val raw = listOf("DeductionEndTime", "CycleEndTime", "ExpireTime", "PackageEndTime", "EndTime")
            .firstNotNullOfOrNull { key ->
                pkg.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" && it != "0" }?.toDoubleOrNull()
            } ?: return null

        val seconds = if (raw > 1e11) raw / 1000.0 else raw
        // 已经作废的包也返回真实时刻（绝对值），让界面能标出「已过期」；
        // 由调用方决定是否统计（账号级只统计还有余量的包）。
        val daysLeft = (seconds - now) / 86400.0
        return when {
            daysLeft > EXPIRY_SENTINEL_DAYS -> null   // 哨兵值：视为不过期
            else -> seconds
        }
    }

    /** 超过这个天数就当「不会过期」—— 见 [expirySecondsOf] 的说明。 */
    private const val EXPIRY_SENTINEL_DAYS = 730.0

    private fun extractBillingAccounts(data: JSONObject): JSONArray = data.optJSONObject("data")?.optJSONObject("Response")?.optJSONObject("Data")?.optJSONArray("Accounts")
        ?: data.optJSONObject("data")?.optJSONObject("Response")?.optJSONArray("Accounts")
        ?: data.optJSONObject("data")?.optJSONArray("Accounts") ?: data.optJSONArray("Accounts") ?: JSONArray()
    private fun noAuthHeaders(region: AccountRegion) = ClientIdentity.noAuthHeaders(region)
    private fun unwrap(root: JSONObject): JSONObject {
        if (root.has("code") && root.optInt("code", -1) != 0) throw IOException(root.optString("msg", root.optString("message", "上游请求失败")))
        return root.optJSONObject("data")?.optJSONObject("data") ?: root.optJSONObject("data") ?: root
    }
    private fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences("native", Context.MODE_PRIVATE)
        val raw = prefs.getString("account", null) ?: return
        runCatching { saveAccount(context, JSONObject(raw)) }
    }
    private const val TAG = "NativeCore"

    /**
     * 按 HTTP 状态码决定账号冷却时长。
     *
     * 对齐上游 app.py:107-115 的 `_cooldown_for`，该分档在 AGENTS.md 中被列为
     * 不可删除的设计不变量。此前 Android 版只在 token 刷新失败时固定冷却 60s，
     * 真正代表「这个号被限了」的 429 打完不留任何痕迹，下一个请求还会选中它。
     *
     * 上游原始注释：429 限流若只用 60s 软冷却，账号会反复被限流、UI 在健康/不可用
     * 间抖动，故限流给 5 分钟；认证类错误（401/403）用 30 分钟。
     */
    fun cooldownFor(status: Int): Long = when {
        status == 429 -> 300L
        status == 401 || status == 403 -> 1800L
        status >= 500 -> 120L
        status > 0 -> 60L
        else -> 0L
    }

    /** 指数退避封顶：429 基准 300s，翻到 6 次是 9600s，截到 2 小时。 */
    private const val BACKOFF_CAP_SECONDS = 7200L

    /**
     * 在基础冷却上叠加指数退避。
     *
     * 问题：原 [cooldownFor] 是固定时长。一个反复被限流的账号，
     * 冷却 300s 到期后立刻又被选中、又被限流 —— 循环下去，
     * 用户看到的是「这个号好像坏了，一直在冷却」。
     *
     * 参考实现（workbuddy2api-panel cooldown.go:374）的三个要点：
     *
     *   1) **优先用上游给的重置时间**（resetAt），比自己猜准；
     *   2) **仍在冷却中**的探测不翻倍 —— 否则频繁探测会让退避指数爆炸；
     *   3) **封顶**，防止退避到天荒地老。
     *
     * @param status HTTP 状态码
     * @param resetAtSeconds 上游给的重置墙钟（秒）。null 表示上游没给
     * @param streak 这是连续第几次限流（调用方传入，1 表示首次）
     */
    fun cooldownFor(
        status: Int,
        resetAtSeconds: Long? = null,
        streak: Int = 1,
    ): Long {
        val now = System.currentTimeMillis() / 1000
        val base = cooldownFor(status)

        // ① 上游给了重置时间 → 直接用，但截到封顶。
        //    resetAt 已过期（时钟偏移）时给 1 秒，让它尽快恢复而不是卡死。
        if (resetAtSeconds != null) {
            return when {
                resetAtSeconds > now + BACKOFF_CAP_SECONDS -> BACKOFF_CAP_SECONDS
                resetAtSeconds > now -> resetAtSeconds - now
                else -> 1L
            }
        }

        // ② 无上游时间 → 指数退避。只有"新限流"才推进 streak，由调用方保证。
        if (streak <= 1) return base
        val exponent = (streak - 1).coerceAtMost(6)
        val raw = base shl exponent
        // ③ 抖动 ±20%：避免多个账号同时恢复、又同时被打爆。
        val jitter = 0.8 + kotlin.random.Random.nextDouble() * 0.4
        return (raw * jitter).toLong().coerceIn(1L, BACKOFF_CAP_SECONDS)
    }
}
