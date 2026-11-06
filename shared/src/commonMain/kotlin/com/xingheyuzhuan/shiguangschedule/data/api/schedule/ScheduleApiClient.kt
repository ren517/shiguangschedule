package com.xingheyuzhuan.shiguangschedule.data.api.schedule

import com.xingheyuzhuan.shiguangschedule.data.api.jwxt.JwxtAuthClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 个人课表远程数据源。
 *
 * 请求参数与 xust-grade-web/core/schedule.py 的 fetch_schedule() 完全一致：
 * 表单 xnm / xqm 决定学年学期，kzlx=ck 表示「查看」课表。
 */
class ScheduleApiClient {

    /**
     * 课表接口常量集合
     */
    object Endpoints {
        const val JWXT_BASE = "http://59.74.174.150/jwglxt"

        /** 学生个人课表查询接口 */
        const val SCHEDULE_QUERY_URL =
            "$JWXT_BASE/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508"

        /** 接口要求携带的 Referer */
        const val SCHEDULE_REFERER =
            "$JWXT_BASE/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508"

        const val USER_AGENT = JwxtAuthClient.Endpoints.USER_AGENT
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = HttpClient {
        followRedirects = false

        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }

    /**
     * 拉取指定学年学期的个人课表。
     *
     * @param cookie 教务系统会话 Cookie
     * @param schoolYear 学年代号 xnm
     * @param schoolTerm 学期代号 xqm（"3" 第一学期 / "12" 第二学期）
     * @return 教务原始课表条目列表；会话失效或教务无数据时返回 null
     */
    suspend fun fetchScheduleItems(
        cookie: String,
        schoolYear: String,
        schoolTerm: String
    ): List<JwxtScheduleItem>? {
        if (cookie.isBlank()) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val formParameters = Parameters.build {
                    append("xnm", schoolYear)
                    append("xqm", schoolTerm)
                    append("kzlx", "ck")
                    append("xsdm", "")
                    append("kclbdm", "")
                    append("kclxdm", "")
                }

                val response = client.post(Endpoints.SCHEDULE_QUERY_URL) {
                    header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
                    header(REFERRER_HEADER, Endpoints.SCHEDULE_REFERER)
                    header(HttpHeaders.Cookie, cookie)
                    setBody(
                        TextContent(
                            formParameters.formUrlEncode(),
                            ContentType.Application.FormUrlEncoded
                        )
                    )
                }

                if (response.status.value !in SUCCESS_STATUS_RANGE) return@runCatching null

                val parsed = json.decodeFromString<JwxtScheduleResponse>(response.bodyAsText())
                parsed.kbList
            }.getOrNull()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L

        /** Ktor 未提供 Referer 常量，这里使用原始请求头名 */
        private const val REFERRER_HEADER = "Referer"

        private val SUCCESS_STATUS_RANGE = 200..299
    }
}
