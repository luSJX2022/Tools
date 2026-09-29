package com.qzkt.timetable.jw.qz

/**
 * 强智 jsxsd 平台（含你学校用的 `xsMainV.htmlx` 这一代）的登录参数计算。
 *
 * 算法直接读自学校登录页里的 `submitForm1()`：
 *
 * ```js
 * var strUrl = "/jsxsd/Logon.do?method=logon&flag=sess";
 * // 返回 dataStr，形如 "<scode>#<sxh>"
 * var scode = dataStr.split("#")[0], sxh = dataStr.split("#")[1];
 * var code = userAccount + "%%%" + userPassword;
 * var encoded = "";
 * for (var i = 0; i < code.length; i++) {
 *     if (i < 20) {
 *         encoded += code.charAt(i) + scode.substring(0, parseInt(sxh.charAt(i)));
 *         scode = scode.substring(parseInt(sxh.charAt(i)));
 *     } else {
 *         encoded += code.substring(i);   // 第 20 位之后原样拼接
 *         i = code.length;
 *     }
 * }
 * ```
 *
 * 也就是把前 20 个字符各自"吃掉" scode 的一段前缀，剩下的明文直接附在末尾。
 */
object QzJsxsdLogin {

    /** 前 20 个字符参与混淆，与网页脚本里的常量一致。 */
    private const val SCRAMBLE_LENGTH = 20

    /**
     * 把 `学号%%%密码` 按 scode/sxh 搅成 encoded。
     *
     * @param session 来自 `/jsxsd/Logon.do?method=logon&flag=sess` 的响应，形如 `<scode>#<sxh>`。
     * @return encoded 串；session 格式不对时返回 null。
     */
    fun encode(account: String, password: String, session: String): String? {
        val separator = session.indexOf('#')
        if (separator <= 0 || separator == session.length - 1) return null

        var scode = session.substring(0, separator)
        val sxh = session.substring(separator + 1)
        if (sxh.isEmpty()) return null

        val code = "$account%%%$password"
        val encoded = StringBuilder(code.length * 2)

        var index = 0
        while (index < code.length) {
            // 超过 20 位、或 sxh 已经不够长（脚本会报错，这里按"不再混淆"处理），原样收尾
            if (index >= SCRAMBLE_LENGTH || index >= sxh.length) {
                encoded.append(code, index, code.length)
                break
            }

            val take = sxh[index].digitToIntOrNull()
            if (take == null) {
                // sxh 里有非数字，说明这个 session 有问题，不要硬猜
                return null
            }

            encoded.append(code[index])
            val chunk = minOf(take, scode.length)
            encoded.append(scode, 0, chunk)
            scode = scode.substring(chunk)
            index++
        }

        return encoded.toString()
    }
}
