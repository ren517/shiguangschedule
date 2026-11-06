package com.xingheyuzhuan.shiguangschedule.data.api.score

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
 * 个人成绩远程数据源。
 *
 * 复刻 xust-grade-web/core/score.py：
 * 1. select_score() 拉取成绩列表（cjcx_cxXsgrcj.html）
 * 2. select_score_detail() 逐门课程拉取明细页（cjcx_cxCjxqGjh.html）
 * 3. parse_detail() 解析明细页 id=subtab 的三列表格
 */
class ScoreApiClient {

    /**
     * 成绩接口常量集合
     */
    object Endpoints {
        const val JWXT_BASE = "http://59.74.174.150/jwglxt"

        /** 学生个人成绩查询接口 */
        const val SCORE_QUERY_URL =
            "$JWXT_BASE/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005"

        /** 成绩明细接口 */
        const val SCORE_DETAIL_URL =
            "$JWXT_BASE/cjcx/cjcx_cxCjxqGjh.html?gnmkdm=N305005"

        /** 接口要求携带的 Referer */
        const val SCORE_REFERER = "$JWXT_BASE/"

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
     * 拉取指定学年学期的成绩列表。
     *
     * @return 教务原始成绩条目列表；会话失效或教务未发布成绩时返回 null
     */
    suspend fun fetchScoreItems(
        cookie: String,
        schoolYear: String,
        schoolTerm: String
    ): List<JwxtScoreItem>? {
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

                val response = client.post(Endpoints.SCORE_QUERY_URL) {
                    header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
                    header(REFERRER_HEADER, Endpoints.SCORE_REFERER)
                    header(HttpHeaders.Cookie, cookie)
                    setBody(
                        TextContent(
                            formParameters.formUrlEncode(),
                            ContentType.Application.FormUrlEncoded
                        )
                    )
                }

                if (response.status.value !in SUCCESS_STATUS_RANGE) return@runCatching null

                val parsed = json.decodeFromString<JwxtScoreResponse>(response.bodyAsText())
                parsed.items
            }.getOrNull()
        }
    }

    /**
     * 拉取单门课程的成绩明细页原始 HTML。
     */
    suspend fun fetchScoreDetailHtml(
        cookie: String,
        classId: String?,
        schoolYear: String,
        schoolTerm: String,
        studentIdHash: String?,
        courseName: String?
    ): String? {
        if (cookie.isBlank() || classId.isNullOrBlank()) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val formParameters = Parameters.build {
                    append("jxb_id", classId)
                    append("xnm", schoolYear)
                    append("xqm", schoolTerm)
                    append("xh_id", studentIdHash.orEmpty())
                    append("kcmc", courseName.orEmpty())
                }

                val response = client.post(Endpoints.SCORE_DETAIL_URL) {
                    header(HttpHeaders.UserAgent, Endpoints.USER_AGENT)
                    header(REFERRER_HEADER, Endpoints.SCORE_REFERER)
                    header(HttpHeaders.Cookie, cookie)
                    setBody(
                        TextContent(
                            formParameters.formUrlEncode(),
                            ContentType.Application.FormUrlEncoded
                        )
                    )
                }

                if (response.status.value !in SUCCESS_STATUS_RANGE) return@runCatching null
                response.bodyAsText()
            }.getOrNull()
        }
    }

    /**
     * 解析成绩明细页，把 id=subtab 的三列表格转换成「项目成绩 / 项目占比」映射。
     *
     * 对应 xust-grade-web/core/score.py 的 parse_detail()。
     */
    fun parseDetail(html: String?): Map<String, String> {
        if (html.isNullOrBlank()) return emptyMap()

        val table = TABLE_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return emptyMap()

        val result = linkedMapOf<String, String>()

        ROW_REGEX.findAll(table).forEach { rowMatch ->
            val cells = CELL_REGEX.findAll(rowMatch.groupValues[1])
                .map { cellMatch -> decodeText(cellMatch.groupValues[1]) }
                .toList()

            if (cells.size != 3) return@forEach

            val item = cells[0].replace("【", "").replace("】", "").trim()
            val percent = cells[1].trim()
            val score = cells[2].trim()

            if (item.isEmpty()) return@forEach

            result["${item}成绩"] = score
            if (percent.isNotEmpty()) {
                result["${item}占比"] = percent
            }
        }

        return result
    }

    /**
     * 去掉 HTML 标签并把常见实体还原为普通字符。
     */
    private fun decodeText(raw: String): String {
        return raw
            .replace(TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L

        private const val PAGE_SIZE = "100"

        /** Ktor 未提供 Referer 常量，这里使用原始请求头名 */
        private const val REFERRER_HEADER = "Referer"

        private val SUCCESS_STATUS_RANGE = 200..299

        /** 明细页表格可能跨行，统一开启 DOT_MATCHES_ALL 让 `.` 匹配换行 */
        private val HTML_REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

        private val TABLE_REGEX =
            Regex("<table[^>]*id=[\"']?subtab[\"']?[^>]*>(.*?)</table>", HTML_REGEX_OPTIONS)
        private val ROW_REGEX = Regex("<tr[^>]*>(.*?)</tr>", HTML_REGEX_OPTIONS)
        private val CELL_REGEX = Regex("<td[^>]*>(.*?)</td>", HTML_REGEX_OPTIONS)
        private val TAG_REGEX = Regex("<[^>]+>")
    }
}
