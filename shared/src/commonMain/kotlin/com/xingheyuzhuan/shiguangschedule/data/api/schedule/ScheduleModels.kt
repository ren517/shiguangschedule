package com.xingheyuzhuan.shiguangschedule.data.api.schedule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 教务系统个人课表接口：
 * POST http://59.74.174.150/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508
 *
 * 模型严格对应 xust-grade-web/core/schedule.py 中 fetch_schedule() 的 kbList 字段。
 */
@Serializable
data class JwxtScheduleResponse(
    @SerialName("kbList")
    val kbList: List<JwxtScheduleItem>? = null
)

/**
 * 教务原始课表条目
 *
 * @property course kcmc 课程名称
 * @property teacher xm 任课教师
 * @property place cdmc 上课地点
 * @property dayOfWeek xqj 星期几（1-7，字符串）
 * @property dayOfWeekName xqjmc 星期几中文名
 * @property sectionText jc 节次（形如 "1-2节"）
 * @property weekText zcd 周次（形如 "1-9周"、"1-8周,10-16周"、"2-16周(双)"）
 * @property credit xf 学分
 * @property hours zxs 总学时
 * @property examMethod khfsmc 考核方式
 * @property nature kcxz 课程性质
 * @property classGroup jxbzc 上课班级
 * @property campus xqmc 校区
 * @property classId jxb_id 教学班 ID
 * @property courseCode kch 课程号
 * @property teachingClassName jxbmc 教学班名称
 */
@Serializable
data class JwxtScheduleItem(
    @SerialName("kcmc")
    val course: String? = null,
    @SerialName("xm")
    val teacher: String? = null,
    @SerialName("cdmc")
    val place: String? = null,
    @SerialName("xqj")
    val dayOfWeek: String? = null,
    @SerialName("xqjmc")
    val dayOfWeekName: String? = null,
    @SerialName("jc")
    val sectionText: String? = null,
    @SerialName("zcd")
    val weekText: String? = null,
    @SerialName("xf")
    val credit: String? = null,
    @SerialName("zxs")
    val hours: String? = null,
    @SerialName("khfsmc")
    val examMethod: String? = null,
    @SerialName("kcxz")
    val nature: String? = null,
    @SerialName("jxbzc")
    val classGroup: String? = null,
    @SerialName("xqmc")
    val campus: String? = null,
    @SerialName("jxb_id")
    val classId: String? = null,
    @SerialName("kch")
    val courseCode: String? = null,
    @SerialName("jxbmc")
    val teachingClassName: String? = null
)
