package com.xingheyuzhuan.shiguangschedule.data.api.score

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 教务系统个人成绩接口：
 * POST http://59.74.174.150/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005
 *
 * 模型严格对应 xust-grade-web/core/score.py 中 select_score() 的 items 字段。
 */
@Serializable
data class JwxtScoreResponse(
    @SerialName("items")
    val items: List<JwxtScoreItem>? = null
)

/**
 * 教务原始成绩条目
 *
 * @property course kcmc 课程名称
 * @property score cj 成绩
 * @property credit xf 学分
 * @property gradePoint xfjd 学分绩点
 * @property point jd 绩点
 * @property nature kcxzmc 课程性质
 * @property category kclbmc 课程类别
 * @property publishTime cjbdsj 成绩发布时间
 * @property teacher jsxm 任课教师
 * @property classId jxb_id 教学班 ID
 * @property studentIdHash xh_id 学号摘要
 * @property academicYear xnmmc 学年
 * @property term xqmmc 学期
 * @property courseCode kch 课程号
 * @property className bj 班级
 * @property examType ksxz 考试性质
 */
@Serializable
data class JwxtScoreItem(
    @SerialName("kcmc")
    val course: String? = null,
    @SerialName("cj")
    val score: String? = null,
    @SerialName("xf")
    val credit: String? = null,
    @SerialName("xfjd")
    val gradePoint: String? = null,
    @SerialName("jd")
    val point: String? = null,
    @SerialName("kcxzmc")
    val nature: String? = null,
    @SerialName("kclbmc")
    val category: String? = null,
    @SerialName("cjbdsj")
    val publishTime: String? = null,
    @SerialName("jsxm")
    val teacher: String? = null,
    @SerialName("jxb_id")
    val classId: String? = null,
    @SerialName("xh_id")
    val studentIdHash: String? = null,
    @SerialName("xnmmc")
    val academicYear: String? = null,
    @SerialName("xqmmc")
    val term: String? = null,
    @SerialName("kch")
    val courseCode: String? = null,
    @SerialName("bj")
    val className: String? = null,
    @SerialName("ksxz")
    val examType: String? = null
)

/**
 * 供界面直接渲染的成绩条目。
 *
 * @property details 成绩明细：平时 / 期中 / 期末 / 总评及其占比，
 *                   来自 cjcx_cxCjxqGjh.html 的详情表解析结果。
 */
@Serializable
data class ScoreRecord(
    val course: String = "",
    val score: String = "",
    val credit: String = "",
    val gradePoint: String = "",
    val nature: String = "",
    val category: String = "",
    val publishTime: String = "",
    val teacher: String = "",
    val examType: String = "",
    val academicYear: String = "",
    val term: String = "",
    val details: Map<String, String> = emptyMap()
)

/**
 * 成绩统计：课程门数、总学分、加权平均绩点（GPA）。
 */
@Serializable
data class ScoreStats(
    val count: Int = 0,
    val credits: Float = 0f,
    val gpa: Float? = null
)

/**
 * 成绩本地缓存快照，结构对齐 xust-grade-web 的 data/score_<xh>.json。
 */
@Serializable
data class ScoreSnapshot(
    val studentId: String = "",
    val schoolYear: String = "",
    val schoolTerm: String = "",
    val fetchedAt: String = "",
    val scores: List<ScoreRecord> = emptyList(),
    val stats: ScoreStats = ScoreStats()
)
