# 会话交接文档 — Tools (Android) 1.0.74

> 生成时间：2026-11-25 · 项目路径：D:\C\qzkt · 仓库：https://github.com/luSJX2022/Tools

---

## 一、当前状态

| 项 | 值 |
| --- | --- |
| 版本 | versionCode **74** / versionName **1.0.74** |
| HEAD | `a1cf129`（README 重构） |
| 远端 | main 与 origin/main 完全同步（0/0） |
| 最新 Release | **v1.0.74**（已发布，apk + zip） |
| 单元测试 | **338 用例 / 54 类 / 0 失败** |
| 工作区 | 干净 |

最近提交：

```
a1cf129  README 重构：章节按工具顺序重排（一~十三）
b289f31  考试查询按真实流程：GET xsksap_query 取学期后 POST xnxqid
e6d5348  考试查询改为按页面真实表单提交
ea1ac6a  考试时间从「考试场次」列兜底解析
754a0c3  考试页面解析加固：td 表头 / div 兜底 / 结构日志
fe5e607  教务请求单飞（3 秒缓存 + 并发等待）
18c7ae8  app.do 客户端也接上全局限速器
af8d5dd  教务三路请求共用全局限速器（1 秒间隔 + 静默 10 分钟）
0207e94  考试安排：查清限流根因（请求压到 3 次内）
9c4d44e  考试：无 CookieJar 客户端 / 登录页结构化判定
```

---

## 二、本会话做了什么

### 1. 菜鸟包裹（拼多多取快递）

| 改动 | 说明 |
| --- | --- |
| **取件码** | 包裹卡片显示 19sp Semibold 取件码，点一下复制；字段是 `pick_code`，去掉「取件码」前缀与空格 |
| **头像昵称** | 个人中心显示头像 + 昵称，接口 `POST /api/express_trackbox/base/nickname/query`（body `{"scene":"dsd.usercenter"}`）；`aes_switch` 为 true 时用 AES/ECB 固定 key `Pdd123oadefjikmh` 解密；拿不到显示「代收点用户」 |
| **待取件 / 已取出分开** | 主列表是 `result.combine_list`；`package_out_list` 是**近一周已取出**，之前误并进待取件导致「取出了还显示未取件」 |
| **深色模式** | 配色按角色定义（PddLight / PddDark + PddSkin 可组合 getter），跟随 App 主题；条形码与二维码保持白底黑条（扫描必需） |

### 2. 影视中心

| 改动 | 说明 |
| --- | --- |
| **分类规则改为自有** | 不再用影视站分类，由 `data/anime/AnimeCategories.kt` 按片名+备注+元数据关键词归类 |
| **关键词按真站数据重写** | 站点把电影叫「动作片/喜剧片/恐怖片」、短剧叫「反转爽文/AI漫剧」，旧规则只写「电影」所以永远 0 条 |
| **归类优先级** | 短剧 → 动漫 → 综艺 → 纪录片 → 电影 → 电视剧；线上 1200 条真实数据校验，类型误判 0 |
| **修复闪退** | `Key "lziapi162599" was already used` —— 切分类时旧请求晚到把同页追加两次。修复：追加去重（AnimeItemList.merge）+ 请求代数 epoch + 切分类取消旧 job |
| **排版整理** | 间距/段落标题/网格参数统一成常量；分页加载条改为网格最后一格；底栏高度按 WindowInsets 动态算，不再遮挡最后一行 |

### 3. 日志系统

- 新增 `data/PluginLog.kt`：所有插件日志写进同一份 `filesDir/logs/app.log`，tag = 插件名
- 新增 `data/LogFilters.kt`：日志行解析与按插件筛选（纯函数 + 单测）
- 日志页改**按整条日志**显示与筛选：崩溃堆栈作为多行续行跟着首行走，不再被筛选切断
- 已接入：教务、课表、图书、链接解析、影视、四级词库、易校园、菜鸟包裹、P2P 密钥验证、备份

### 4. 教务稳定性（本会话最大的坑，详见第三节）

- **全局限速器** `QzRateLimiter`：考试/成绩/课表（jsxsd + app.do）共用闸门，最小间隔 1 秒，识别到限流后全局静默 10 分钟
- **请求单飞** `QzRequestGate`：同样的 GET 3 秒内只真正发一次；「空页重试」用 `X-Qz-No-Cache` 头绕过缓存
- **会话客户端不带 CookieJar**：避免重定向下发的 Set-Cookie 覆盖手动会话头
- **登录页判定改为结构化**：必须真有登录表单元素 **且** 解析不出考试表
- **考试查询按真实流程**（详见第三节）

### 5. 图书

- 全本小说网封面 URL 降级为 HTTP（img.c0m.io 图床在部分机型 HTTPS 证书链校验失败）
- 封面加载失败写入「图书」日志

### 6. README 重构

原 README 编号错乱（1→2→4→5→3）、词达人/易校园/菜鸟包裹挤在「构建」之前、易校园段落被插入词达人表格、图书没有独立章节、考试接口说明是错的。重构为 13 章 + 目录，事实全部核对过（8 工具 / 6 影视源 / 5 书源 / 4688 词 / SDK 26·37）。

---
## 三、考试安排：完整排障过程（重要，避免重复踩坑）

这条路径尝试了 6 个版本才走通，每一步的**错误假设**都记下来：

| 版本 | 假设 | 结果 |
| --- | --- | --- |
| 1.0.65 | 用 package_out_list 那张表 | 页面有但返回 0 条 |
| 1.0.66 | 强智要 xnxq01id 参数 | 参数名错，不生效 |
| 1.0.67 | 登录页判定太粗暴 / CookieJar 覆盖会话 | 修了真 bug，但仍 0 条 |
| 1.0.68 | 请求太频繁被学校注销 | 是真的（日志有「操作过于频繁，已被注销登录」），请求压到 3 次 |
| 1.0.71~72 | 表头用 td 而非 th / 时间在「考试场次」列 | 都修了，仍 0 条 |
| **1.0.74** | **请求错了页面** | ✅ 真正根因 |

### 真正的根因

「考试安排」有**两个页面**，之前一直请求错了那一个：

```
GET  /jsxsd/xsks/xsksap_query    ← 查询页：有表单，xnxqid 学期下拉（selected = 当前学期）
      页面加载后自动执行：
      document.forms["ksapQueryForm"].action = "/jsxsd/xsks/xsksap_list";
      document.forms["ksapQueryForm"].submit();     // POST

POST /jsxsd/xsks/xsksap_list     ← 结果页：表单 xqlbmc=&xnxqid=2026-2027-1&xqlb=
      → 表格里才有数据
```

**关键**：`GET /xsks/xsksap_list` **永远**返回 `<td colspan="12">未查询到数据</td>` —— 它是不带查询条件的结果页。参数名是 `xnxqid`，不是 `xnxq01id`。

### 正确的排查方法（下次遇到类似问题照做）

不要靠猜参数。用**内置浏览器 + CDP** 实地跑一遍：

```powershell
# 1. 启动带调试端口的 Edge（独立 profile，不干扰日常浏览）
$args = @('--remote-debugging-port=9223','--user-data-dir=D:\C\.edge-cdp-qz',
          '--no-first-run','--no-default-browser-check','https://jw.hdxy.edu.cn/jsxsd/')
Start-Process 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe' -ArgumentList $args
```

```js
// 2. 从 CDP 拿 cookie（HTTP-only 也能拿）并监听真实请求
const list = await (await fetch('http://127.0.0.1:9223/json/list')).json();
const page = list.find(t => t.type === 'page' && t.url.includes('hdxy'));
const ws = new WebSocket(page.webSocketDebuggerUrl);
// send('Network.enable')，然后监听 Network.requestWillBeSent 能看到真实 POST body
```

用这个方法一次就抓到真实 POST body `xqlbmc=&xnxqid=2026-2027-1&xqlb=`，还拿到真实 3 条考试数据做回归测试。

### 踩坑记录

- **外部 fetch 会顶掉浏览器会话**：用 Node 直接 POST 带 cookie 的请求后，浏览器被踢回登录页。调试时**只在浏览器内用 CDP 执行**，不要另起 HTTP 客户端。
- 用户手工登录后 cookie 在 `.edge-cdp-qz` 这个 profile 里；换会话要重新登录。

---

## 四、关键文件地图

| 关注点 | 文件 |
| --- | --- |
| 考试解析（表头映射/日期时刻/诊断） | jw/ExamInfo.kt |
| 考试/成绩/课表取数 | jw/qz/QzJsxsdDirect.kt |
| 全局限速器 | jw/qz/QzRateLimiter.kt |
| 请求单飞 | jw/qz/QzRequestGate.kt |
| 教务客户端工厂（拦截器安装） | jw/qz/QzHttp.kt |
| 影视分类规则 | data/anime/AnimeCategories.kt |
| 影视列表去重 | data/anime/AnimeItemList.kt |
| 图书书源 + 封面归一化 | data/book/OnlineBookSource.kt |
| 拼多多接口 | data/tools/PddExpressApi.kt |
| 插件日志 | data/PluginLog.kt / data/LogFilters.kt |
| 考试回归夹具（真实表格） | app/src/test/resources/exam/qz_exam_table.html |

---

## 五、构建环境（这台机器的确切路径）

```powershell
$env:JAVA_HOME        = 'D:\C\qzkt\.toolchain\jdk-21'
$env:GRADLE_USER_HOME = 'D:\C\.gradle-home'
& 'D:\C\qzkt\.toolchain\gradle-9.6.0\bin\gradle.bat' -p 'D:\C\qzkt' :app:testDebugUnitTest :app:assembleRelease
```

**AGP / Gradle 版本**：AGP 9.4.1 要求 Gradle ≥ 9.6.0。

### 两个环境坑（都已绕过，但下次会遇到）

1. **APK 被文件预览进程占住** → `app/build/outputs/apk/debug` 删不掉，Gradle 报 `Unable to delete directory`。
   绕法：用 init 脚本把 `:app` 的 build 目录切到 `app/build-alt`：

   ```kotlin
   // D:/C/_verify/build-alt.init.gradle.kts
   gradle.beforeProject {
       if (path == ":app") layout.buildDirectory.set(file("../app/build-alt"))
   }
   ```

   然后加参数 `--init-script 'D:\C\_verify\build-alt.init.gradle.kts'`，产物在 `app/build-alt/outputs/apk/...`。

2. **签名任务要写用户目录**：`validateSigningDebug` 需要在 `C:\Users\LTY0712\.android\debug.keystore` 旁建锁文件。
   如果沙箱只允许写 `D:\C`，会报 `AccessDeniedException: debug.keystore.lock`，需要完整文件访问权限。

### 这台机器上有的 / 没有的

- ❌ **没有 adb / 模拟器** → 无法真机验证，只能靠编译 + 单测 + APK 静态校验
- ✅ 有 **aapt2**（`C:\Users\LTY0712\AppData\Local\Android\Sdk\build-tools\...\aapt2.exe`）可查 versionName
- ✅ 有 **Edge** + CDP 可做真实浏览器验证（见第三节）

---
## 六、待办 / 已知缺口

| 优先级 | 事项 | 备注 |
| --- | --- | --- |
| 中 | **IPv6 连接超时** | 日志里 `bfzyapi.com/[2a03:2880:...]:443` 超时，OkHttp 优先试 IPv6。可给 OkHttp 配 IPv4 优先 DNS。用户提过但未修 |
| 中 | **考试安排端到端未在 App 内验证** | 真实流程已在浏览器验证成功、解析有真实夹具回归，但**没有在手机上跑过一次**（无设备）。1.0.74 装到手机后需实测确认 |
| 低 | P2P 密钥验证 | 本会话只加了日志，功能未深测 |
| 低 | 图书封面 | 降级 HTTP 是绕过方案，若图床后续只允许 HTTPS 需换方案 |

---

## 七、用户偏好（重要）

- **语言**：全程中文，界面文案必须中文
- **技术选型**：坚持**原生 Kotlin**，不要 WebView 承载核心 UX；不要 Room / Hilt（见 README 设计取舍）
- **数据来源**：拼多多走 **cookie 不走微信**；影视/图书只用公开接口
- **视觉**：要求 1:1 复刻时要**取自真实 CSS / 资源**，不要凭感觉画
- **诚实**：不允许编造数据（取件码没有就显示「待同步」，考试没有就说没有）
- **沟通**：喜欢看到**证据链**（日志、截图、抓包），不喜欢猜测式修复
- **发版**：versionCode +1、tag vX.Y.Z、Release 附 apk + zip（资产名用 **ASCII**，中文会被 GitHub 过滤掉）

### 发版清单

```powershell
cd 'D:\C\qzkt'
# 1. 改 app/build.gradle.kts 的 versionCode / versionName
# 2. 构建
& 'D:\C\qzkt\.toolchain\gradle-9.6.0\bin\gradle.bat' -p 'D:\C\qzkt' --init-script 'D:\C\_verify\build-alt.init.gradle.kts' :app:testDebugUnitTest :app:assembleRelease
# 3. 打包（资产名 ASCII）
Copy-Item 'app\build-alt\outputs\apk\release\app-release.apk' 'dist\Tools-vX.Y.Z.apk'
Compress-Archive -Path 'dist\Tools-vX.Y.Z.apk' -DestinationPath 'dist\Tools-vX.Y.Z.zip'
# 4. 提交 + tag + 推送 + Release
git add -A; git commit -m '...'; git tag vX.Y.Z
git push origin main; git push origin vX.Y.Z
gh release create vX.Y.Z --title 'vX.Y.Z' --notes-file 'dist\notes-X.Y.Z.md' 'dist\Tools-vX.Y.Z.zip' 'dist\Tools-vX.Y.Z.apk'
```

---

## 八、验证手段（这台机器上能做的）

| 手段 | 命令 / 方式 |
| --- | --- |
| 单元测试 | `:app:testDebugUnitTest`，报告在 `app/build-alt/test-results/` |
| 测试计数 | 读 `TEST-*.xml` 里的 `tests="N" failures="N" errors="N"` |
| 版本校验 | `aapt2 dump badging app-release.apk` |
| dex 字符串校验 | `tar -xf apk` 后 grep dex 里的中文/类名 |
| 签名校验 | 搜 APK 里有没有 `APK Sig Block 42` |
| 真实接口验证 | Edge + CDP（见第三节） |

---

## 九、下个会话建议的起点

1. 先读 `README.md`（已重构，13 章，事实准确）
2. 跑一次单测确认基线 338/0
3. 如果要动教务相关：**先读第三节**，别重复那 6 个版本的弯路
4. 如果要发版：照第七节的清单走，注意资产名 ASCII
5. 如果要真机验证：这台机器没 adb，需要用户配合截图 / 日志
