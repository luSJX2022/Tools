package com.qzkt.timetable.jw.parse

/**
 * 强智 `getKbcxAzc` 返回项的字段别名表。
 *
 * 各校部署的强智版本不同，同一个逻辑字段的键名不一样，所以每个字段都给一组候选键名，
 * 按顺序取第一个非空的。全部小写比较。
 *
 * 命名规律（强智惯例）：`kcmc`=课程名称，`jsxm`=教师姓名，`jsmc`=教室名称，
 * `xqj`/`xqjmc`=星期几，`jcs`=节次，`zcd`/`zcmc`=周次段，`jxbmc`=教学班名称。
 */
object FieldAliases {

    /** 课程名称。 */
    val NAME = listOf(
        "kcmc", "kcmcname", "kcname", "coursename", "course_name", "kcm", "name", "kcbm",
    )

    /** 教师姓名。 */
    val TEACHER = listOf(
        "jsxm", "jsxmname", "teacher", "teachername", "teacher_name", "skjs", "rkjs", "jsxms",
    )

    /** 教室名称。 */
    val ROOM = listOf(
        "jsmc", "jsmcname", "classroom", "room", "roomname", "cdmc", "skdd", "jsdd", "place",
        "jxdd", "kkdd",
    )

    /** 星期（数字形式，1=周一）。 */
    val DAY_NUM = listOf("xqj", "xq", "week", "weekday", "dayofweek", "weekno")

    /** 星期（中文形式，"星期一"）。 */
    val DAY_NAME = listOf("xqjmc", "xqmc", "weekname", "xqjmcname")

    /** 节次。 */
    val PERIODS = listOf("jcs", "jcinfo", "jcxx", "jc", "period", "periods", "jcsname")

    /** 周次段。 */
    val WEEKS = listOf("zcd", "zcmc", "zcstr", "weeks", "weekrange", "kkzc", "zc")

    /** 单双周标记（可能是独立字段）。 */
    val WEEK_FLAG = listOf("zcmc", "zclx", "weekflag", "sfzd", "danzhou", "zcbm")

    /** 教学班 / 班级。 */
    val CLASS = listOf("jxbmc", "jxbmcname", "bj", "bjmc", "classname", "clsname", "jxbmcstr")

    /** 优先用于启发式的键名前缀（命中则在同等条件下优先选它）。 */
    val HEURISTIC_HINTS = mapOf(
        "period" to listOf("jc", "period", "节"),
        "week" to listOf("zc", "week", "周"),
        "teacher" to listOf("js", "teacher", "师"),
        "room" to listOf("js", "room", "dd", "cd", "class"),
        "name" to listOf("kc", "course", "name", "课"),
    )
}
