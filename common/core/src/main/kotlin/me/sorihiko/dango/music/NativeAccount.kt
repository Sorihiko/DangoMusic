package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoMusic
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Per-provider state, created only by the official QR exchange. Never shared with audio CDNs. */
class NativeAccount @JvmOverloads constructor(
    val source: MusicSource,
    val userId: String,
    val nickname: String,
    internal val token: String,
    internal val deviceId: String,
    internal val cookies: CookieManager,
    val loginType: Int = 2,
    val avatarUrl: String = "",
    /** 酷狗：用概念版扫码登录的（之后取歌、领会员都用概念版的身份）。旧的登录信息没有这一项，按普通版算 */
    val concept: Boolean = false,
    /** QQ：续期登录用的凭证（refresh_key 等，见 [QqRenewal]）。旧的登录信息没有，到期后只能重新扫码 */
    internal val renewal: JsonObject? = null,
)

object NativeAccounts {
    private val accounts = ConcurrentHashMap<MusicSource, NativeAccount>()
    private val loaded = ConcurrentHashMap.newKeySet<MusicSource>()
    private val lock = Any()
    private val profileAttempts = ConcurrentHashMap<MusicSource, NativeAccount>()
    private val profileWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DangoMusic-account-profile").apply { isDaemon = true }
    }
    private fun path(source: MusicSource) = DangoMusic.instance.clientDir.resolve("${source.key}_session.json")

    fun get(source: MusicSource): NativeAccount? = load(source)?.also(::renewIfDue)

    private fun load(source: MusicSource): NativeAccount? = synchronized(lock) {
        if (source == MusicSource.NETEASE) return@synchronized null
        if (loaded.add(source)) {
            try {
                val file = path(source)
                if (Files.isRegularFile(file)) {
                    val root = JsonParser().parse(String(Files.readAllBytes(file), Charsets.UTF_8)).asJsonObject
                    require(root.text("source") == source.key)
                    val manager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
                    root.getAsJsonArray("cookies")?.forEach { entry ->
                        val item = entry.asJsonObject
                        val origin = URI(item.text("origin"))
                        val domain = item.text("domain")
                        val providerDomain = when (source) {
                            MusicSource.QQ -> "qq.com"
                            MusicSource.SODA -> "qishui.com"
                            else -> "kugou.com"
                        }
                        if (origin.scheme == "https" && AuthHttp.allowed(source, origin.host) &&
                            (domain.trimStart('.') == providerDomain || domain.endsWith(".$providerDomain"))) {
                            val expires = item.number("expires")
                            if (expires == 0L || expires > System.currentTimeMillis()) {
                                manager.cookieStore.add(origin, HttpCookie(item.text("name"), item.text("value")).apply {
                                    this.domain = domain
                                    this.path = item.text("path").ifEmpty { "/" }
                                    secure = true
                                    version = 0
                                    if (expires > 0) maxAge = (expires - System.currentTimeMillis()) / 1000
                                })
                            }
                        }
                    }
                    val account = NativeAccount(source, root.text("userId"), root.text("nickname"), root.text("token"), root.text("deviceId"), manager,
                        if (root.has("loginType")) root.number("loginType").toInt() else 2, root.text("avatarUrl"),
                        root.get("concept")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                        root.get("renewal")?.takeIf { it.isJsonObject && source == MusicSource.QQ }?.asJsonObject)
                    if (account.userId.matches(Regex("[0-9]{1,20}")) && account.userId.any { it != '0' } &&
                        (if (source == MusicSource.QQ) manager.cookieStore.cookies.any { it.name == "qqmusic_key" } else account.token.isNotEmpty())) accounts[source] = account
                }
            } catch (_: Exception) { /* Missing, expired or invalid local state: offer QR login again. */ }
        }
        accounts[source]
    }

    internal fun save(account: NativeAccount) = synchronized(lock) {
        require(account.source != MusicSource.NETEASE && account.userId.matches(Regex("[0-9]{1,20}")) && account.userId.any { it != '0' })
        val root = JsonObject().apply {
            addProperty("source", account.source.key); addProperty("userId", account.userId)
            addProperty("nickname", account.nickname); addProperty("token", account.token); addProperty("deviceId", account.deviceId)
            addProperty("loginType", account.loginType)
            addProperty("avatarUrl", account.avatarUrl)
            if (account.concept) addProperty("concept", true)
            if (account.renewal != null) add("renewal", account.renewal)
            add("cookies", JsonArray().apply {
                for (origin in account.cookies.cookieStore.urIs) {
                    // Java's cookie store normalizes its origin index to http:// even for HTTPS.
                    // Query using HTTPS so Secure login cookies are included in the saved session.
                    for (cookie in account.cookies.cookieStore.get(URI("https://${origin.host}/"))) {
                        if (cookie.hasExpired()) continue
                        add(JsonObject().apply {
                            addProperty("origin", "https://${origin.host}/"); addProperty("name", cookie.name); addProperty("value", cookie.value)
                            addProperty("domain", cookie.domain ?: origin.host); addProperty("path", cookie.path ?: "/")
                            addProperty("expires", if (cookie.maxAge < 0) 0L else System.currentTimeMillis() + cookie.maxAge * 1000)
                        })
                    }
                }
            })
        }
        val target = path(account.source)
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(temporary, root.toString().toByteArray(Charsets.UTF_8))
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        accounts[account.source] = account
        loaded.add(account.source)
    }

    private val renewAttempts = ConcurrentHashMap<MusicSource, Long>()

    /** QQ 的登录三天就到期：快到期时在后台续一次（失败了过一会儿再试，不影响现在的登录） */
    private fun renewIfDue(account: NativeAccount) {
        if (!QqRenewal.due(account)) return
        val now = System.currentTimeMillis()
        val last = renewAttempts[account.source]
        if (last != null && now - last < QqRenewal.RETRY_MS) return
        renewAttempts[account.source] = now
        profileWorker.execute {
            try {
                val renewed = QqAppSession().renew(account) ?: return@execute
                synchronized(lock) { if (accounts[account.source] === account) save(renewed) }
            } catch (e: Exception) {
                me.sorihiko.dango.DangoLogger.warn("QQ Music login renewal failed: {}", e.javaClass.simpleName)
            }
        }
    }

    /** Also fills profiles saved by earlier beta versions, without another QR login. */
    fun refreshProfile(source: MusicSource) {
        if (source == MusicSource.NETEASE || source == MusicSource.SODA) return
        val account = get(source) ?: return
        if (account.nickname.isNotEmpty() && account.avatarUrl.isNotEmpty()) return
        synchronized(lock) {
            if (accounts[source] !== account || profileAttempts[source] === account) return
            profileAttempts[source] = account
        }
        profileWorker.execute {
            try {
                val updated = if (source == MusicSource.QQ) QqAppSession().profile(account) { accounts[source] === account }
                    else NativeAccount(source,account.userId,account.nickname,account.token,account.deviceId,account.cookies,account.loginType,
                        KugouCatalog.image(KugouWeb.cookiePart(account.cookies,"Pic").ifEmpty { cookie(account.cookies,"KugooPic") }).ifEmpty { account.avatarUrl },
                        account.concept)
                updateProfileIfCurrent(account, updated)
            } catch (_: Exception) { /* Cosmetic metadata failure leaves the verified login intact. */ }
        }
    }

    internal fun updateProfileIfCurrent(expected: NativeAccount, updated: NativeAccount) = synchronized(lock) {
        if (accounts[expected.source] === expected && updated.source == expected.source &&
            updated.userId == expected.userId &&
            (updated.nickname != expected.nickname || updated.avatarUrl != expected.avatarUrl)) save(updated)
    }

    fun logout(source: MusicSource) = synchronized(lock) {
        Files.deleteIfExists(path(source))
        accounts.remove(source)
        loaded.add(source)
        Unit
    }

    internal fun cookie(manager: CookieManager, name: String): String =
        manager.cookieStore.cookies.firstOrNull { it.name == name && !it.hasExpired() }?.value.orEmpty()

    internal fun hash33(value: String, seed: Int = 5381): Int {
        var hash = seed
        value.forEach { hash = hash * 33 + it.code }
        return hash and Int.MAX_VALUE
    }
}
