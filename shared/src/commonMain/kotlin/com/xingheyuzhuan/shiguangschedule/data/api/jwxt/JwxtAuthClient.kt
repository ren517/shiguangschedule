package com.xingheyuzhuan.shiguangschedule.data.api.jwxt

import com.xingheyuzhuan.shiguangschedule.tool.Aes128
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.formUrlEncode
import kotlin.random.Random

/**
 * 西安科技大学教务系统认证客户端（统一身份认证中心 jwsso）。
 *
 * 完整复刻 xust-grade-web/core/jwsso_login.py 的流程：
 * 1. 访问教务 CAS 入口（不自动跟随跳转），拿到 jwsso 的 CAS 登录地址；
 * 2. 在登录页取动态 __token__（每次请求都会变化）；
 * 3. 以 `__token__` 前 16 位作为 AES-128-CBC 的 key 与 iv 加密密码后提交；
 * 4. 手动跟随票据跳转（最多 8 跳），换取 59.74.174.150 域下的 JSESSIONID + route。
 *
 * Cookie 由 [SessionCookies] 手动维护，避免使用 Ktor Cookie 插件时不同跳转域之间的丢失问题。
 */
class JwxtAuthClient {

    /**
     * 教务系统接口常量集合
     */
    object Endpoints {
        /** 教务系统根地址 */
        const val JWXT_BASE = "http://59.74.174.150/jwglxt"

        /** CAS 认证入口：访问它会 302 到统一身份认证中心 */
        const val CAS_ENTRY = "$JWXT_BASE/auth/auth_casAuthLogin.html"

        /** 统一身份认证中心地址 */
        const val JWSSO_BASE = "http://jwsso.xust.edu.cn"

        /** 账号密码登录页（内含 __token__） */
        const val LOGIN_URL = "$JWSSO_BASE/auth/oauth/login"

        /** 登录态校验页：正文出现「退出」视为已登录 */
        const val INDEX_MENU_URL = "$JWXT_BASE/xtgl/index_initMenu.html?jsdm=xs"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    /**
     * 登录过程中的会话 Cookie 容器（name -> value，全部请求统一携带）。
     */
    private class SessionCookies {
        private val cookies = linkedMapOf<String, String>()

        /**
         * 吸收响应中的 Set-Cookie（忽略 Path / HttpOnly 等属性）。
         */
        fun absorb(response: HttpResponse) {
            response.headers.getAll(HttpHeaders.SetCookie)?.forEach { raw ->
                val pair = raw.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty()) {
                    cookies[name] = value
                }
            }
        }

        /**
         * 生成 Cookie 请求头。
         */
        fun header(): String = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

        fun isEmpty(): Boolean = cookies.isEmpty()
    }

    private val client = HttpClient {
        followRedirects = false

        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }

    /**
     * 执行完整登录流程。
     *
     * @param studentId 学号
     * @param password 教务系统明文密码
     * @return 登录成功时携带会话 Cookie 字符串的 [Result.success]，否则为错误信息
     */
    suspend fun login(studentId: String, password: String): Result<String> {
        val trimmedId = studentId.trim()
        val trimmedPassword = password.trim()

        if (trimmedId.isEmpty() || trimmedPassword.isEmpty()) {
            return Result.failure(IllegalArgumentException("学号或密码不能为空"))
        }

        return runCatching {
            val cookies = SessionCookies()

            val casLoginUrl = try {
                fetchCasLoginUrl(cookies)
            } catch (e: Exception) {
                throw IllegalStateException(NETWORK_UNREACHABLE_MESSAGE, e)
            } ?: throw IllegalStateException(NETWORK_UNREACHABLE_MESSAGE)

            var token = ""
            repeat(TOKEN_RETRY_TIMES) {
                if (token.isEmpty()) {
                    token = try {
                        fetchLoginToken(casLoginUrl, cookies)
                    } catch (e: Exception) {
                        throw IllegalStateException(NETWORK_UNREACHABLE_MESSAGE, e)
                    }
                }
            }
            if (token.isEmpty()) {
                throw IllegalStateException("未能获取登录页动态令牌，请稍后重试")
            }

            val encryptedPassword = Aes128.encodeBase64(
                Aes128.encryptCbcWithZeroPadding(
                    data = trimmedPassword.encodeToByteArray(),
                    keyAndIv = token.take(AES_KEY_LENGTH).encodeToByteArray()
                )
            )

            val formParameters = Parameters.build {
                append("username", trimmedId)
                append("password", encryptedPassword)
                append("__token__", token)
                append("fingerprint", generateFingerprint())
            }

            val loginResponse = try {
                client.post(appendQuery(casLoginUrl, "login_type" to "normal")) {
                    header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
                    header(REFERRER_HEADER, casLoginUrl)
                    header(HttpHeaders.Cookie, cookies.header())
                    setBody(
                        TextContent(
                            formParameters.formUrlEncode(),
                            ContentType.Application.FormUrlEncoded
                        )
                    )
                }
            } catch (e: Exception) {
                throw IllegalStateException(NETWORK_UNREACHABLE_MESSAGE, e)
            }
            cookies.absorb(loginResponse)

            val responseBody = loginResponse.bodyAsText()
            if (responseBody.contains(LOGIN_ERROR_MARKER) || responseBody.contains(NORMAL_LOGIN_MARKER)) {
                throw IllegalStateException(BAD_CREDENTIALS_MESSAGE)
            }

            var nextLocation = loginResponse.headers[HttpHeaders.Location]
            var base = casLoginUrl
            var hops = 0

            while (!nextLocation.isNullOrEmpty() && hops < MAX_TICKET_HOPS) {
                hops++

                val location = nextLocation
                val target = resolveUrl(base, location)

                val response = try {
                    client.get(target) {
                        header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
                        header(HttpHeaders.Cookie, cookies.header())
                    }
                } catch (e: Exception) {
                    throw IllegalStateException(NETWORK_UNREACHABLE_MESSAGE, e)
                }
                cookies.absorb(response)

                base = target
                nextLocation = response.headers[HttpHeaders.Location]
            }

            if (!isSessionValid(cookies.header())) {
                throw IllegalStateException(BAD_CREDENTIALS_MESSAGE)
            }

            cookies.header()
        }
    }

    /**
     * 校验给定 Cookie 是否仍然是有效登录态。
     */
    suspend fun isSessionValid(cookie: String): Boolean {
        if (cookie.isBlank()) return false

        return runCatching {
            val response = client.get(Endpoints.INDEX_MENU_URL) {
                header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
                header(HttpHeaders.Cookie, cookie)
            }
            response.status == HttpStatusCode.OK && response.bodyAsText().contains(LOGGED_IN_MARKER)
        }.getOrDefault(false)
    }

    /**
     * 第一步：访问教务 CAS 入口，取 jwsso 的 CAS 登录地址。
     */
    private suspend fun fetchCasLoginUrl(cookies: SessionCookies): String? {
        val response = client.get(Endpoints.CAS_ENTRY) {
            header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
            if (!cookies.isEmpty()) {
                header(HttpHeaders.Cookie, cookies.header())
            }
        }
        cookies.absorb(response)

        val location = response.headers[HttpHeaders.Location]
        val isRedirect = response.status.value in REDIRECT_STATUS_CODES

        return if (isRedirect && !location.isNullOrEmpty()) {
            resolveUrl(Endpoints.CAS_ENTRY, location)
        } else {
            null
        }
    }

    /**
     * 第二步：抓取登录页里的动态 __token__。
     */
    private suspend fun fetchLoginToken(casLoginUrl: String, cookies: SessionCookies): String {
        val target = appendQuery(
            casLoginUrl,
            "login_type" to "normal",
            "login_type_fragment" to "ImplNormal"
        )

        val response = client.get(target) {
            header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
            if (!cookies.isEmpty()) {
                header(HttpHeaders.Cookie, cookies.header())
            }
        }
        cookies.absorb(response)

        val html = response.bodyAsText()
        if (html.contains(APP_UNREGISTERED_MARKER)) {
            return ""
        }

        return TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    /**
     * 生成本次登录的随机指纹（等价于 Python 的 uuid4().hex）。
     */
    private fun generateFingerprint(): String {
        val bytes = Random.Default.nextBytes(FINGERPRINT_BYTE_LENGTH)
        return bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${HEX_DIGITS[value shr 4]}${HEX_DIGITS[value and 0x0f]}"
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L

        private const val BAD_CREDENTIALS_MESSAGE = "账号不存在或密码错误"

        /** 网络不可达时的统一提示：教务系统只允许校内或可直连 59.74.174.150 的网络访问 */
        private const val NETWORK_UNREACHABLE_MESSAGE =
            "无法连接教务系统：请确认已接入校园网或可直连 59.74.174.150 的网络（移动数据通常无法访问）"

        private const val LOGIN_ERROR_MARKER = "o-form-error-container"
        private const val NORMAL_LOGIN_MARKER = "normal_login_container"
        private const val APP_UNREGISTERED_MARKER = "应用未注册或地址不匹配"
        private const val LOGGED_IN_MARKER = "退出"

        private const val AES_KEY_LENGTH = 16
        private const val TOKEN_RETRY_TIMES = 3
        private const val MAX_TICKET_HOPS = 8
        private const val FINGERPRINT_BYTE_LENGTH = 16

        private const val HEX_DIGITS = "0123456789abcdef"

        private const val SCHEME_SEPARATOR = "://"

        /** Ktor 未提供 Referer 常量，这里使用原始请求头名 */
        private const val REFERRER_HEADER = "Referer"

        private val REDIRECT_STATUS_CODES = listOf(301, 302, 303, 307)
        private val TOKEN_REGEX = Regex("name=\"__token__\"\\s+value=\"([^\"]+)\"")

        /**
         * 为地址拼接查询参数。
         */
        private fun appendQuery(url: String, vararg params: Pair<String, String>): String {
            val query = params.joinToString("&") { (key, value) -> "$key=$value" }
            if (query.isEmpty()) return url

            val separator = if (url.contains('?')) '&' else '?'
            return "$url$separator$query"
        }

        /**
         * 依据当前地址解析可能为相对路径的 Location。
         */
        private fun resolveUrl(base: String, location: String): String {
            if (location.startsWith("http://", true) || location.startsWith("https://", true)) {
                return location
            }

            val schemeEnd = base.indexOf(SCHEME_SEPARATOR)
            val authorityStart = if (schemeEnd < 0) 0 else schemeEnd + SCHEME_SEPARATOR.length
            val authorityEnd = base.indexOf('/', authorityStart)
            val origin = if (authorityEnd == -1) base.substringBefore('?') else base.substring(0, authorityEnd)

            return if (location.startsWith("/")) {
                origin + location
            } else {
                val basePath = base.substringBefore('?')
                val folderEnd = basePath.lastIndexOf('/')
                val folder = if (folderEnd < authorityStart) origin else basePath.substring(0, folderEnd)
                "$folder/$location"
            }
        }
    }
}
