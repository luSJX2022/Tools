# Tools（Android）

原生 Android 校园工具箱。8 个内置工具，全部本地运行，不受第三方网页限制。

- **技术栈**：Kotlin + Jetpack Compose（Material 3）+ Media3 + OkHttp + DataStore
- **minSdk / targetSdk**：26 / 37，无 Room、无 Hilt（理由见 [设计取舍](#十三设计取舍)）
- **数据**：课表、收藏、书签都存本机；只有登录教务 / 拼多多 / 易校园时才会联网

---

## 目录

- [一、功能一览](#一功能一览)
- [二、教务：课表 / 成绩 / 考试 / 选课](#二教务课表-成绩-考试-选课)
- [三、图书](#三图书)
- [四、影视](#四影视)
- [五、链接解析](#五链接解析)
- [六、四级词库](#六四级词库)
- [七、易校园](#七易校园)
- [八、菜鸟包裹](#八菜鸟包裹)
- [九、P2P 密钥验证](#九p2p-密钥验证)
- [十、构建](#十构建)
- [十一、代码结构](#十一代码结构)
- [十二、教务接口约定](#十二教务接口约定)
- [十三、设计取舍](#十三设计取舍)

---

## 一、功能一览

| 工具 | 主要能力 | 数据来源 |
| --- | --- | --- |
| [教务](#二教务课表-成绩-考试-选课) | 课表同步、成绩查询、考试安排、选课 | 学校强智教务系统 |
| [图书](#三图书) | 本地 TXT 阅读、5 个在线书源、书城搜索 | 本机 / 第三方公开书站 |
| [影视](#四影视) | 6 个资源站、本地分类、收藏与进度、播放 | 苹果 CMS10 公开接口 |
| [链接解析](#五链接解析) | B站 / 抖音分享链接转直链、普通流地址 | B站 / 抖音公开接口 |
| [四级词库](#六四级词库) | 四级词库查询、题库练习、错题本 | 内置离线题库（4688 词） |
| [易校园](#七易校园) | 校园卡余额、消费明细、宿舍电费 | 易校园服务端 |
| [菜鸟包裹](#八菜鸟包裹) | 拼多多取快递、取件码、身份码 | 拼多多公开接口 |
| [P2P 密钥验证](#九p2p-密钥验证) | WireGuard 密钥强度检查、生成 | 全本地计算 |

**插件管理**：工具页右上角可以卸载 / 重新安装内置工具。卸载只移除入口并禁止进入，不会从已安装 APK 里物理删除代码；卸载时可选保留或清除该插件数据（默认保留）。清理图书数据不会删除原位置的 TXT 文件。

---

## 二、教务：课表 / 成绩 / 考试 / 选课

工具页「教务」进入，内部四个页签。所有数据都走**应用内登录留下的会话 cookie**，不用反复输密码。

### 课表

登录学校强智教务系统后自动导入本学期课表，之后按设定间隔后台同步。

**「实时更新」的真相**：强智没有推送接口，Android 也不允许 App 常驻长连接，所以「实时」由这些手段拼成：

| 手段 | 说明 |
| --- | --- |
| 定时后台同步 | 默认每 6 小时，可在「教务账号 → 课表同步」调到最短 15 分钟 |
| 手动刷新 | 课表页右上角刷新按钮 / 下拉 |
| 变动通知 | 同步后与本地比对，新增 / 停课 / 调课 / 换教室都通知，并记在「变动」页（有变动时带角标） |
| 桌面小组件 | 跟着同步结果刷新 |
| 上课提醒 | 提前 5–30 分钟通知（体感上最「实时」的部分） |

**已知限制**：

1. 后台任务最快 15 分钟一次；系统 Doze 与厂商省电策略下可能推迟到几小时。
2. 只有教务系统**已发布**的变更才同步得到，通知必然滞后于学校实际操作。
3. 部分学校强智只允许**校内网**访问，校外同步会失败。此时保留上次数据并提示原因，**不会清空课表**。
4. 学校登录若需**验证码**，自动同步失效（登录那步过不去）。
5. **网页导入的课表不会后台自动更新**：有的学校没开接口，只能靠 WebView 手动抓一次；此时后台同步会被跳过（不反复推「同步失败」），课表页顶部会写明。app.do 与 jsxsd 两个接口都试过且导入成功后，标记才解除。

### 成绩

走同一条会话路：GET /xxwcqk/xxwcqkOnkcxz.do（新版一体化）→ 失败再试 /kscj/cjcx_query → 最后补一次 POST 空查询（条件全空 = 全部学期）。每个地址 GET 两遍，因为学校服务器偶尔返回空页。

### 考试安排

**接口流程**（海都学院实地抓包确证，注意是两个页面）：

```
GET  {base}/xsks/xsksap_query   → 查询页，带 xnxqid 学期下拉（selected 即当前学期）
     页面加载后自动执行 queryKsap()：
     document.forms["ksapQueryForm"].action = "/xsks/xsksap_list";
     document.forms["ksapQueryForm"].submit();
POST {base}/xsks/xsksap_list    → 结果页，表单 xqlbmc=&xnxqid=2026-2027-1&xqlb=
```

> **坑**：GET /xsks/xsksap_list 永远返回「未查询到数据」—— 它是不带查询条件的结果页。
> 必须先**访问查询页拿学期，再 POST xnxqid**。参数名是 xnxqid，不是 xnxq01id。

**三层兜底**：

1. **地址**：先按「查询页 → 结果页」正规流程；页面有表单时按表单真实字段提交；
2. **学期**：优先用页面 selected 的那个，再按当前日期推算；查不到换下一个学期（最多 2 次，控制请求数）；
3. **解析**：不写死列号，按表头文字映射（考试课程 / 课程名称 / 考试时间 / 考试地点|考场 / 座位号 / 考试形式 / 备注）。表头可能是 th 也可能是整行 td；「考试场次」列含日期时段时也能取到时间；地点列空则退回「校区 / 考试校区」；整页没有表格时按 div 列表兜底。

日期解析支持 2026-11-21 星期六 19:00~21:00、2026年11月21日(星期六)19:00-21:00、19:00 至 21:00；解析不出日期的（如只写「第 13 周」）也照样显示，不丢弃。

界面按时间排序并标「今天 / 明天 / N 天后」；会话过期提示「去应用内登录」，登录正常但无记录显示「这学期还没有考试安排」（不当错误）。

### 选课

学生选课中心，走同一会话。

---

## 三、图书

工具页「图书」进入，分「书架」和「发现好书」两个页签。

### 书架

导入本地 TXT（UTF-8 / GBK 自动识别），段落滚动阅读、进度记忆、字号 / 夜间模式。阅读进度按**段落下标**保存，改字号导致重排也不丢位置。

### 发现好书（5 个在线书源）

| 书源 | 说明 |
| --- | --- |
| 全本小说网 | 经典 HTML 书站，明文正文；搜索是 JSONP + 自定义字符移位加密 |
| 鲲弩小说 | 同上，正文含落霞读书 / 鲲弩小说水印，已做行内水印净化 |
| 古腾堡中文公版（美国） | 通过 Gutendex 查询，只展示标记为美国公版的中文作品 |
| 维基文库（中文） | MediaWiki API 按条目取章节 |
| 互联网档案馆（中文公版） | 只接受 Public Domain Mark 1.0 / CC0 的条目，详情加载时再次校验 |

搜书 → 进书架 → 在线阅读。**版权状态可能因地区而异**，古腾堡与档案馆只按标注筛选，标注不是法律保证。

> **封面坑**：全本小说网的封面图床 img.c0m.io 在部分机型上 HTTPS 证书链校验失败（服务端只发叶子证书，中间 CA 是 Let's Encrypt 过渡证书 YE1，Android 图片下载器不会自动抓 AIA），所以封面 URL 降级为 HTTP。加载失败会写入「图书」日志。

---

## 四、影视

工具页「影视」进入，五个页签（推荐 / 筛选 / 番剧 / 排行 / 历史），底部悬浮液态玻璃导航。

### 资源站

6 个苹果 CMS10 格式公开接口（暴风 / 量子 / 360 / 非凡 / 索尼 / 无尽），分类 id 写死在 VideoSources.kt，逗号分隔的多个 id 当作一次聚合查询。可在「影视设置」里启停与切换，也支持添加自定义采集站（填域名即可）。

### 筛选页签用 App 自己的分类

不再是影视站给的分类，由 data/anime/AnimeCategories.kt 的规则决定：

| 页签 | 命中关键词（片名 + 备注 + 资源站 type/tag/演员/简介） |
| --- | --- |
| 短剧 | 短剧、微短剧、竖屏剧、漫剧、爽文、言情、古装仙侠、重生民国、穿越年代、甜宠、逆袭、虐恋 |
| 电影 | 电影、影片、剧场版、院线、蓝光、动作片、喜剧片、爱情片、科幻片、恐怖片…（排除记录片/动画片/综艺/短剧） |
| 动漫 | 动漫、动画、番剧、国漫、日漫、新番、OVA、泡面番 |
| 综艺 | 综艺、真人秀、脱口秀、晚会、访谈、选秀 |
| 纪录片 | 纪录片、记录片、纪实、专题片、自然探索 |
| 电视剧 | 剧、连续剧、美剧、韩剧、日剧、港剧、台剧（排除短剧/剧场版/电影/综艺/动漫） |

归类优先级：短剧 → 动漫 → 综艺 → 纪录片 → 电影 → 电视剧。取数时从该源「全部」流拿一页再本地筛，一页没命中顺延下一页（最多 3 页）。规则用线上 1200 条真实数据校验过，类型误判 0。

### 其它

- **收藏与进度**：本地 DataStore JSON，按「源：id」隔离，换源不串数据
- **播放**：点剧集进播放器，播放器内可「选集」
- **简介**：详情页优先豆瓣简介，拿不到时用资源站简介并标注来源
- **切换分类会取消上一次仍在跑的翻页**，避免旧数据追加造成重复 key 闪退

> 影视内容全部来自上述第三方公开接口，本项目只做了浏览 / 播放客户端，不存储、不转码、不代理任何视频内容。

---

## 五、链接解析

工具页「链接解析」进入，能吃两类输入：

1. **B站 / 抖音分享链接**：App 里「复制链接」出来的整段文案直接粘贴（b23.tv 短链、带说明文字都行）
2. **普通流地址**：HLS（.m3u8）、DASH（.mpd）、HTTP 直链，不走解析直接播

防盗链站点可在「请求头」里补 Referer / User-Agent / Cookie（每行一个「名称: 值」）。

**已知限制**：

1. **B站画质受登录状态限制**，不登录一般 720P。想上 1080P，把 Cookie: SESSDATA=… 填进请求头。
2. **B站只支持 MP4 直链（durl）**，不合并 DASH：播放器只能塞一个 MediaItem，音视频分离没法合并，接口只回 dash 时明确报错，而不是丢一条没声音的流。
3. **超长视频可能被切成多段**，这时只能播第一段（界面会提示）。
4. **抖音直链有时效**，几小时后失效，重新粘一次分享链接。
5. 抖音作品需登录（或已删除）时拿不到地址，界面直说，不转圈。

**下载落盘位置**：

| 系统 | 位置 | 说明 |
| --- | --- | --- |
| Android 10+ | Movies/qzkt/<标题>.mp4 | 走 MediaStore，**不需要存储权限**，卸载应用也还在 |
| Android 8 / 9 | Android/data/com.qzkt.timetable/files/Movies/ | 写公开目录要存储权限，为下个视频弹权限不划算，退回应用目录（完整路径显示在界面，卸载即删） |

**性能**：取流走 OkHttp（连接池 + 断线重连），HLS 分片落盘（256MB LRU，回拖不再走网络），最多预载 120 秒；无歌词文件时歌词区整体隐藏，不做高频刷新。

---

## 六、四级词库

工具页「四级词库」进入。大学英语四级词汇查询、题库练习、错题复习。

| 部分 | 位置 | 说明 |
| --- | --- | --- |
| 解析引擎 | cidaren/CidarenEngine.kt | 题干解析、选项评分、解析生成 |
| 词根词缀表 | cidaren/CidarenAffixes.kt | 前缀 / 词根 / 后缀 / 句式规则 |
| 精讲词条 + 题库 | assets/cidaren/data.json | 851 条精讲词条（带搭配 / 例句 / 词根）+ 160 道题 |
| 完整四级词库 | assets/cidaren/cet4.json | 4536 条四级大纲词表（含音标与释义） |
| 本地状态 | cidaren/CidarenStore.kt | 错题本 / 自定义词库 / 统计，落在 filesDir/cidaren.json |
| 词库导入 | cidaren/CidarenGlossary.kt | JSON / CSV / TXT 解析（字段别名、音标与词性识别） |

**词库合并**：精讲 851 + 四级 4536，按单词去重后 **4688 条**（同名以精讲为准，因为它带搭配、例句、词根）。速查页可按「全部 / 四级 / 精讲 / 自定义」与首字母筛选。数据包约 1MB，在 IO 线程读入并显示加载态，不占首帧。

**忠实度**：引擎是网页版 js/engine.js 的逐函数移植，CidarenEngineTest 用网页版在同一批输入上跑出的 177 条结果（app/src/test/resources/cidaren/fixtures.json）逐条比对题型、来源、答案、置信度、逐项分析条数、解析块与考点/句意文案。

---

## 七、易校园

工具页「易校园」进入。获取安全令牌 → 识别图形验证码 → 发送短信验证码 → 登录 → 查询校园卡余额与消费明细；也可通过 OAuth 查询已绑定的宿舍、电费缴费记录与用电明细（MGS / ZHPF 两种后端）。

- **学校代码**需填本人学校的；10337 只是接口文档里的浙工大示例
- 账号会话通过 **Android Keystore 加密**保存，退出登录会清除本地会话
- **短信请求必须由用户手动触发**，请遵守服务端频控，仅查询本人账号
- 接口可能因学校或服务端版本变化；失败时页面展示服务端错误信息
- 宿舍电费查询要求账号已在易校园 App 绑定房间；查不到绑定房间时切换 MGS / ZHPF 确认后端类型

---

## 八、菜鸟包裹

工具页「菜鸟包裹」进入。把拼多多「取快递」小程序**用原生语言 1:1 复刻**进 App：三个页签（取快递 / 身份码 / 个人中心）+ 底部 tab 栏。

配色（主文字 #151516、次文字 #58595B、弱化 #9C9C9C、拼多多红 #E02E24、选中态 #E53B43、链接 #0076FF、身份码底 #F5F5F5）、字号、间距、图标、文案全部取自页面真实 CSS（1rem = 16dp）与页面资源，不是凭感觉画的。

| 部分 | 位置 | 说明 |
| --- | --- | --- |
| 接口 | data/tools/PddExpressApi.kt | 我的快递：POST .../station/package（body entry_source=24）；身份码：POST .../generate_identification_code；头像昵称：POST .../base/nickname/query |
| 到站包裹 | 同上 | 待取件读 result.combine_list（递归解析、字段多别名）；package_out_list 是**近一周已取出**，单独展示；取件码取 pick_code 并去掉「取件码」前缀与空格 |
| 登录 | ui/tools/ParcelLoginScreen.kt | App 内 WebView 打开拼多多手机号登录页，登录后合并多域 Cookie；**不走微信** |
| 登录态 | data/tools/ExpressStore.kt | Cookie（pdd_user_id / accesstoken）存本机 DataStore；工具「清除数据」一并抹掉 |
| 界面 | ui/tools/PddExpressScreen.kt | 三页签：banner、包裹卡片（取件码 19sp Semibold、可点复制）、空态、近一周已取出、图片预览、身份码（旋转 90° 条形码 + 倒计时） |
| 头像昵称 | PddExpressApi.kt | result.nickname / result.avatar；aes_switch 时用页面同款 AES/ECB 固定 key 解密；拿不到显示「代收点用户」 |
| 条形码 / 二维码 | ui/tools/Code128Barcode.kt | 收件码用 CODE128 原生绘制（与小程序 JsBarcode 逐模块一致）；身份码用 ZXing |
| 深色模式 | PddExpressScreen.kt | 配色按角色定义（PddLight / PddDark + PddSkin），跟随「设置 → 主题」；条形码与二维码保持白底黑条（扫描必需） |
| 内嵌页面 | ui/tools/ParcelPddPageScreen.kt | WebView 内嵌拼多多取快递页（/package 路由）；pinduoduo:// 等深链一律拦截 |

**为什么不用微信**：这两个接口要的只是拼多多自己的登录 Cookie（pdd_user_id / accesstoken 这一套）。带 /weixin/ 前缀的路由会被 302 到 open.weixin.qq.com 的 OAuth，App 里做不到；但 /package 路由不跳转，接口也不校验微信。所以登录方式改成 **App 内 WebView 用手机号登录拼多多**。也保留「粘贴 Cookie」入口（可粘整段 Cookie 或 Copy as cURL 文本）。

请求头与网页一致：手机 Chrome UA、Origin: https://mdkd.pinduoduo.com、Referer: .../package。登录失效时服务端返回 424 / error_code 40001，界面提示重新登录。

---

## 九、P2P 密钥验证

工具页「P2P 密钥验证」进入。粘贴 WireGuard 配置或 Base64 密钥，检查密钥强度与配置风险（私钥可推导公钥是否匹配、预共享密钥、端点与 DNS 设置等），也可生成新密钥对。**全部本地计算**，不联网。

---

## 十、构建

本项目自带一套**免安装**的构建工具链（不需要系统里预先有 JDK 或 Gradle）。

### 1. 准备工具链

.toolchain/ 目录（不入 git）里需要两个东西：

```bash
cd qzkt/.toolchain

# JDK 21（清华镜像，约 205 MB）
curl -L -o jdk21.zip \
  "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"
unzip -q jdk21.zip && mv jdk-21.0.12.1+1 jdk-21

# Gradle 9.6.0（腾讯云镜像，约 150 MB）
# 版本不能低于 9.6.0：AGP 9.4.1 要求的最低 Gradle 版本就是它
curl -L -o gradle.zip "https://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip"
unzip -q gradle.zip   # 解压出 gradle-9.6.0/
```

gradle.properties 里的 org.gradle.java.home 指向 .toolchain/jdk-21，换别处的 JDK 就改这一行。

### 2. 指向 Android SDK

local.properties 里写你的 SDK 路径：

```properties
sdk.dir=C:/Users/<你的用户名>/AppData/Local/Android/Sdk
```

需要 platforms;android-37.0 与 build-tools。缺的话 AGP 会自己从 dl.google.com 下载。

### 3. 编译

```bash
cd qzkt

# 本机没有系统级 JDK，先指到项目自带的那份（gradlew 本身要用 java 启动）
export JAVA_HOME="$PWD/.toolchain/jdk-21"     # Windows: set JAVA_HOME=%CD%\.toolchain\jdk-21

./gradlew :app:assembleDebug                  # Windows: gradlew.bat :app:assembleDebug
```

首次运行 ./gradlew 会自己下载 Gradle 9.6.0 —— gradle/wrapper/gradle-wrapper.properties 的 distributionUrl 已指向腾讯云镜像，不走 GitHub。

产物：app/build/outputs/apk/debug/app-debug.apk

跑单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

报告在 app/build/reports/tests/testDebugUnitTest/index.html。

### 4. 装到手机

```bash
# 手机开 USB 调试后
%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

> **.toolchain/gradle-9.6.0 是干什么的？**
> 搭环境时本机没有任何 Gradle，得先用它跑一次 gradle wrapper 才能生成 gradlew。
> 生成之后日常编译只用 ./gradlew 就够了，那份 Gradle 可以删掉（JDK 不能删）。
>
> **关于依赖仓库**：settings.gradle.kts 里把阿里云和腾讯云的 Maven 镜像排在
> mavenCentral() 前面。原因是 Kotlin 的 kotlin-compiler-embeddable 在 Maven Central
> 上是个 301 跳到 GitHub Releases 的条目，而国内网络到 GitHub 的 TLS 常常直接失败；
> Gradle 遇到网络错误不会自动回退到下一个仓库，所以必须让镜像先命中。
> 如果你的网络能直连 GitHub，可以把这个顺序调回去。

---

## 十一、代码结构

```
app/src/main/kotlin/com/qzkt/timetable/
├── QzApp.kt                    手写的依赖容器（没有用 Hilt）
├── MainActivity.kt
├── model/                      CourseSession / Term / SyncChange 等数据模型
├── jw/                         教务接口层
│   ├── JwAdapter.kt            「登录 + 读课表」的抽象
│   ├── ExamInfo.kt             考试解析（表头映射 / 日期时刻 / 页面诊断）
│   ├── qz/QzAppDoAdapter.kt    强智 app.do 移动端接口（主通道）
│   ├── qz/QzJsxsdAdapter.kt    强智 jsxsd 网页版接口
│   ├── qz/QzJsxsdDirect.kt     拿已有会话直接查课表 / 成绩 / 考试
│   ├── qz/QzRateLimiter.kt     ★ 全局限速器（考试 / 成绩 / 课表共用闸门）
│   ├── qz/QzRequestGate.kt     ★ 请求单飞（同样的 GET 只发一次）
│   ├── qz/QzWebParser.kt       网页版课表 HTML 解析（兜底通道）
│   └── parse/                  节次、周次、字段别名、课表 JSON 的容错解析
├── data/
│   ├── AppSettings / SettingsStore / TimetableStore / TimetableRepository
│   ├── AppLogStore.kt          应用日志（filesDir/logs/app.log，私有、有上限、自动脱敏 token/cookie）
│   ├── PluginLog.kt            插件日志统一入口：tag = 插件名，异步写入上面那份 app.log
│   ├── LogFilters.kt           日志行解析 / 按插件筛选（纯函数，有单测）
│   ├── net/Ipv4FirstDns.kt     ★ 出网 DNS：IPv4 优先（所有 OkHttp 客户端共用）
│   ├── anime/                  影视：模型、资源站客户端、本地分类规则（AnimeCategories）、收藏与进度
│   ├── book/                   图书：5 个在线书源、本地 TXT 解析、封面归一化
│   ├── tools/                  菜鸟包裹：拼多多接口、Cookie 存储、手动运单
│   ├── backup/                 设置 + 书架 + 追番的备份恢复
│   └── update/                 检查更新（GitHub Releases）
├── cidaren/                    四级词库：引擎、词根词缀、词库合并、本地状态
├── yxy/                        易校园：签名、登录、校园卡查询与 Keystore 会话存储
├── sync/                       WorkManager 定时同步、通知、上课提醒闹钟
├── widget/                     Glance 桌面小组件
└── ui/
    ├── QzktApp.kt              导航壳：工具页为起始页，底部「工具 / 设置」两个页签
    ├── grid/                   课表周视图
    ├── academic/               教务页（课表 / 成绩 / 考试 / 选课页签）与考试安排页
    ├── tools/                  工具页与各工具入口（含菜鸟包裹 1:1 复刻）
    ├── anime/                  影视列表、详情、ViewModel
    ├── book/                   书架、阅读器、书城
    ├── player/                 播放页、快捷手势、后台播放服务、B站 / 抖音解析器
    ├── settings/               外观 / 影视源 / 存储管理 / 关于 / 日志（可按插件筛）
    ├── account/ log/ debug/ setup/ web/   账号 / 变动日志 / 调试 / 配置向导 / 网页导入
    └── common/ theme/          公共组件（SectionCard / SplashOverlay）/ 主题
```

---

## 十二、教务接口约定

强智不同代的产品接口完全不同，所以做了两个适配器，由 SmartQzAdapter 自动选。

### ① jsxsd 网页版（地址里含 /jsxsd）—— 几乎每所学校都有

```
POST {base}/Logon.do?method=logon&flag=sess   → "<scode>#<sxh>"
     必须带 X-Requested-With: XMLHttpRequest（登录页里是 jQuery 调的，
     不带这个头服务器会把登录页整页返回来）
     用 scode/sxh 把「学号%%%密码」搅成 encoded（算法见 QzJsxsdLogin）
POST {base}/xk/LoginToXk                      → loginMethod/userAccount/encoded，拿会话 cookie
GET  {base}/xskb/xskb_list.do                 → 整学期课表 HTML（每门课自带周次）
GET  {base}/framework/xsMain_new.jsp?t1=1     → 当前周次 / 总周数
GET  {base}/xxwcqk/xxwcqkOnkcxz.do            → 成绩
GET  {base}/xsks/xsksap_query                 → 考试查询页（拿学期选项）
POST {base}/xsks/xsksap_list                  → 考试结果页（xnxqid=<学期>）
```

### ② app.do 移动端接口 —— 只有一部分学校开放

```
GET {base}?method=authUser&xh={学号}&pwd={密码}
  → {"flag":"1","msg":"...","token":"..."}      之后所有请求带请求头 token
GET {base}?method=getCurrentTime&currDate=yyyy-MM-dd
  → {"xnxqh":"2026-2027-1","zc":"3"}
GET {base}?method=getKbcxAzc&xh={学号}&xnxqid={xnxqh}&zc={周次}
  → [{...}, ...]
```

SmartQzAdapter 的选法：地址里有 jsxsd 就先试网页版，否则先试 app.do；只有「网络不通」和「这个接口在这儿不存在」（JwException.wrongProtocol）才换另一个，**网关拦截和账号密码错会直接上报**。

### 频控：三条路共用一个闸门

考试 / 成绩 / 课表打的是同一台网关，学校对「同一会话短时间多次请求」非常敏感 —— 实测超量会**直接注销登录**并返回「您操作系统过于频繁，已被注销登录，正常使用请勿高频操作！」。

所以 QzHttp 的客户端上装了两层拦截器：

| 层 | 类 | 作用 |
| --- | --- | --- |
| 请求单飞 | QzRequestGate | 同样的 GET 3 秒内只真正发一次（连点、来回切页面、后台同步与手动刷新撞车都不重复占用配额）；「空页重试」用 X-Qz-No-Cache 头绕过缓存 |
| 全局限速 | QzRateLimiter | 相邻请求最少间隔 1 秒；一旦识别到限流页，全局静默 10 分钟，期间成绩 / 课表也一起停 |

识别到限流后，界面会明确提示「操作过于频繁、已注销登录，请重新登录并等待」，而不是误报「登录失效」。

### 出网 DNS：IPv4 优先

所有 OkHttp 客户端（教务、影视、图书、易校园、拼多多、更新检查、播放器）都装 `Ipv4FirstDns`：解析结果里 **IPv4 排前面，IPv6 一个不丢地排在后面当备用**。

原因是线上日志里 `bfzyapi.com/[2a03:2880:...]:443` 反复超时 —— 手机网络常常「IPv6 能解析但连不上」，OkHttp 按 DNS 返回顺序逐个尝试，于是每次都先撞满 `connectTimeout(15s)` 才回落 IPv4，用户看到的就是影视页一直转圈。IPv4 优先把这次白等省掉了；纯 IPv6 网络（没有 A 记录）时顺序不变，不受影响。

---

## 十三、设计取舍

### 学校前台有网关校验怎么办

登录页要用浏览器执行 JS 才能拿到「通行证」，纯 HTTP 客户端请求 Logon.do 会被前面一层网关拦下，返回 {"flag1":2,"msgContent":"请先登录系统"}。**账号密码登录在这类学校走不通**，适配器会认出这个响应并明确告诉用户，而不是含糊地报「密码错误」。

正确做法是**在应用内登录一次**：

```
WebView 里登录 → 会话 cookie 存进设置 → 后台定时同步拿这个 cookie 直接拉页面
```

会话过期时同步会提示「请打开应用重新登录一次」（数据保留，不反复推失败通知）。

**会话客户端不带 CookieJar**：用带 jar 的客户端请求已有会话时，服务器重定向到登录页会下发新的 Set-Cookie，jar 收到后**覆盖**手动设的会话头，之后的请求全变成未登录状态（用户会看到「明明刚登录，却说登录失效」）。所以拉数据用 QzHttp.statelessClient()。

### 为什么不用 Room / Hilt

课表数据一学期只有几百条，整份存 JSON 文件 + 内存索引足够，查询是毫秒级；去掉 Room 就同时去掉了 KSP 代码生成插件（KSP 和 Kotlin 版本必须严格配对，是这个项目里最容易在换环境时炸掉的一环）。依赖注入只有七八个对象，手写容器更省事。

数据层抽象在 TimetableStore 后面，日后想换 Room 只改实现。

### 日志

所有插件通过 PluginLog 写进同一份 filesDir/logs/app.log（256KB 上限、自动脱敏 token/cookie/密码），日志行格式「时间 级别/插件名: 内容」，tag 就是插件名。设置里的「日志」页可按插件筛选，崩溃堆栈作为多行记录完整显示（不会被筛选切断）。

---

*影视与在线图书的内容均来自第三方公开接口，本项目不存储、不转码、不代理任何内容，仅供学习交流。*
