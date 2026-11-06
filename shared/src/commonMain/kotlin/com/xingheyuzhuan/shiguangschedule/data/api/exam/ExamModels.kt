package com.xingheyuzhuan.shiguangschedule.data.api.exam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 教务系统考试安排接口：
 * POST http://59.74.174.150/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105
 *
 * 本组模型严格对应 JSON 中的 items 字段，字段注释保留教务原始含义，
 * 便于后续扩展时对照排查。
 */
@Serializable
data class JwxtExamResponse(
    @SerialName("items")
    val items: List<JwxtExamItem>? = null
)

/**
 * 教务原始考试条目
 *
 * @property examName ksmc 考试名称
 * @property academicYear xnmc 学年
 * @property term xqmmc 学期
 * @property campus xqmc 校区
 * @property course kcmc 课程名称
 * @property method khfs 考核方式
 * @property credit xf 学分
 * @property timeText kssj 考试时间（形如 2026-06-25(14:00-16:00)）
 * @property place cdbh 考场
 * @property seat zwh 座位号
 * @property college kkxy 开课学院
 * @property teacherInfo jsxx 教师信息（形如 001344/罗清郁;000850/刘文刚）
 */
@Serializable
data class JwxtExamItem(
    @SerialName("ksmc")
    val examName: String? = null,
    @SerialName("xnmc")
    val academicYear: String? = null,
    @SerialName("xqmmc")
    val term: String? = null,
    @SerialName("xqmc")
    val campus: String? = null,
    @SerialName("kcmc")
    val course: String? = null,
    @SerialName("khfs")
    val method: String? = null,
    @SerialName("xf")
    val credit: String? = null,
    @SerialName("kssj")
    val timeText: String? = null,
    @SerialName("cdbh")
    val place: String? = null,
    @SerialName("zwh")
    val seat: String? = null,
    @SerialName("kkxy")
    val college: String? = null,
    @SerialName("jsxx")
    val teacherInfo: String? = null
)

/**
 * 供界面直接渲染的考试条目（等价于 xust-grade-web/core/exam.py 的 normalize() 产物）。
 *
 * @property course 课程名称
 * @property timeText 教务原始考试时间文本
 * @property date 截取出的考试日期（yyyy-MM-dd）
 * @property timeRange 截取出的考试时段（HH:mm-HH:mm）
 * @property place 考场
 * @property seat 座位号
 * @property credit 学分
 * @property college 开课学院
 * @property teachers 去掉工号前缀后的监考教师
 * @property method 考核方式
 * @property campus 校区
 * @property examName 考试名称
 * @property academicYear 学年
 * @property term 学期
 */
@Serializable
data class ExamRecord(
    val course: String,
    val timeText: String,
    val date: String,
    val timeRange: String,
    val place: String,
    val seat: String,
    val credit: String,
    val college: String,
    val teachers: String,
    val method: String,
    val campus: String,
    val examName: String,
    val academicYear: String,
    val term: String
)

/**
 * 考试安排的本地缓存快照，结构对齐 xust-grade-web 的 data/exam_<xh>.json。
 *
 * @property studentId 学号
 * @property schoolYear 抓取时的学年代号（xnm）
 * @property schoolTerm 抓取时的学期代号（xqm）
 * @property fetchedAt 抓取时间文本
 * @property exams 已按考试时间排序的考试列表
 */
@Serializable
data class ExamSnapshot(
    val studentId: String = "",
    val schoolYear: String = "",
    val schoolTerm: String = "",
    val fetchedAt: String = "",
    val exams: List<ExamRecord> = emptyList()
)
