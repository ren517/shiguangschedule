package com.xingheyuzhuan.shiguangschedule.data.api.exam

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
import kotlin.time.Clock

/**
 * 考试安排远程数据源。
 *
 * 复用 xust-grade-web/core/exam.py 的 fetch_exam() 请求参数与字段规则：
 * 表单 xnm / xqm 决定学年学期，其余 queryModel.* 为教务分页协议的固定写法。
 */
class ExamApiClient {

    /**
     * 考试接口常量集合
     */
    object Endpoints {
        const val JWXT_BASE = "http://59.74.174.150/jwglxt"

        /** 学生考试查询接口 */
        const val EXAM_QUERY_URL =
            "$JWXT_BASE/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105"

        /** 接口要求携带的 Referer */
        const val EXAM_REFERER =
            "$JWXT_BASE/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default"

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
     * 拉取指定学年学期的考试安排。
     *
     * @param cookie 教务系统会话 Cookie
     * @param schoolYear 学年代号 xnm（如 "2026" 表示 2026-2027 学年）
     * @param schoolTerm 学期代号 xqm（"3" 第一学期 / "12" 第二学期）
     * @return 教务原始考试条目列表；会话失效或教务未发布时返回 null
     */
    suspend fun fetchExamItems(
        cookie: String,
        schoolYear: String,
        schoolTerm: String
    ): List<JwxtExamItem>? {
        if (cookie.isBlank()) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val formParameters = Parameters.build {
                    append("xnm", schoolYear)
                    append("xqm", schoolTerm)
                    append("_search", "false")
                    append("nd", Clock.System.now().toEpochMilliseconds().toString())
                    append("queryModel.showCount", PAGE_SIZE)
                    append("queryModel.currentPage", "1")
                    append("queryModel.sortName", "")
                    append("queryModel.sortOrder", "asc")
                    append("time", "0")
                }

                val response = client.post(Endpoints.EXAM_QUERY_URL) {
                    header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
                    header(REFERRER_HEADER, Endpoints.EXAM_REFERER)
                    header("X-Requested-With", "XMLHttpRequest")
                    header(HttpHeaders.Cookie, cookie)
                    setBody(
                        TextContent(
                            formParameters.formUrlEncode(),
                            ContentType.Application.FormUrlEncoded
                        )
                    )
                }

                if (response.status.value !in SUCCESS_STATUS_RANGE) return@runCatching null

                val parsed = json.decodeFromString<JwxtExamResponse>(response.bodyAsText())
                parsed.items
            }.getOrNull()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L

        private const val PAGE_SIZE = "100"

        /** Ktor 未提供 Referer 常量，这里使用原始请求头名 */
        private const val REFERRER_HEADER = "Referer"

        private val SUCCESS_STATUS_RANGE = 200..299
    }
}
