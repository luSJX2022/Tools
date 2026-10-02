# Tools（Android）：课表 · 影视 · 链接解析

一个原生 Android 工具箱应用，三个入口：

- **课表**：登录学校的**强智教务系统**自动导入本学期课表，之后按设定的间隔后台同步，
  课表有变动（调课 / 停课 / 换教室）时发通知；带桌面小组件和上课提醒。
- **影视**：接入四个第三方资源站（暴风 / 量子 / 360 / 非凡），电影、剧集、动漫、综艺、短剧
  分类浏览 + 搜索，本地收藏与观看进度，选集后直接进播放器。
- **链接解析**：B站 / 抖音 App 里「复制链接」出来的一整段文案（短链、带说明文字都行），
  粘进去解析成真实播放地址，直接播放。

三个入口最后都汇到同一个**后台播放器**（Media3/ExoPlayer + MediaSessionService）：
锁屏、切后台、页面被回收都还能继续放，支持手势、选集、清晰度切换和下载。

- Kotlin + Jetpack Compose（Material 3）+ Media3 + OkHttp + DataStore
- minSdk 26，无 Room / 无 Hilt（JSON 文件持久化 + 手写依赖容器，理由见下文）
- 课表数据保存在本机，只有登录时才会连学校的教务系统

---

## 一、构建

本项目自带一套**免安装**的构建工具链（不需要系统里预先有 JDK 或 Gradle），
所以在一台干净机器上也能直接编译。

### 1. 准备工具链

`.toolchain/` 目录（不入 git）里需要两个东西：

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

`gradle.properties` 里的 `org.gradle.java.home` 指向 `.toolchain/jdk-21`。
换成别处的 JDK 就改这一行。

### 2. 指向 Android SDK

`local.properties` 里写你的 SDK 路径：

```properties
sdk.dir=C:/Users/<你的用户名>/AppData/Local/Android/Sdk
```

需要 `platforms;android-37.0` 与 `build-tools`。缺的话 AGP 会自己从 `dl.google.com` 下载。

### 3. 编译

```bash
cd qzkt

# 本机没有系统级 JDK，先指到项目自带的那份（gradlew 本身要用 java 启动）
export JAVA_HOME="$PWD/.toolchain/jdk-21"     # Windows cmd: set JAVA_HOME=%CD%\.toolchain\jdk-21

./gradlew :app:assembleDebug                  # Windows 用 gradlew.bat :app:assembleDebug
```

首次运行 `./gradlew` 会自己下载 Gradle 9.6.0 —— `gradle/wrapper/gradle-wrapper.properties`
里的 `distributionUrl` 已经指向腾讯云镜像，不会去走 GitHub。

产物：`app/build/outputs/apk/debug/app-debug.apk`

> **`.toolchain/gradle-9.6.0` 是干什么的？**
> 搭环境时本机没有任何 Gradle，得先用它跑一次 `gradle wrapper` 才能生成 `gradlew`。
> 生成之后日常编译只用 `./gradlew` 就够了，那份 Gradle 可以删掉（JDK 不能删）。
>
> **关于依赖仓库**：`settings.gradle.kts` 里把阿里云和腾讯云的 Maven 镜像排在了
> `mavenCentral()` 前面。原因是 Kotlin 的 `kotlin-compiler-embeddable` 在 Maven Central
> 上是个 301 跳到 GitHub Releases 的条目，而国内网络到 GitHub 的 TLS 常常直接失败；
> Gradle 遇到网络错误不会自动回退到下一个仓库，所以必须让镜像先命中。
> 如果你的网络能直连 GitHub，可以把这个顺序调回去。

跑单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。

### 4. 装到手机

```bash
# 手机开 USB 调试后
%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

或者直接把 apk 拷进手机点击安装（需要在系统设置里允许安装未知来源应用）。

---

## 二、首次使用（课表）

1. 打开 App，填写：
   - **学校强智地址**：就是你平时在电脑浏览器里打开教务系统的网址，例如 `http://jwgl.xxx.edu.cn`。
     程序会自动补上 `/app.do`。只填域名（不带 `http://`）也能用：会先按 `https` 试，
     连不上自动退回 `http` —— 很多学校的强智还是明文 HTTP。
   - **学号 / 密码**
   - **学期周数**（默认 20）、**第 1 周周一**（可留空，见下）
2. 点「测试连接」验证地址和账号。失败时会直接显示学校返回的原因。
3. 点「导入课表」，程序会逐周拉取整个学期的课表。20 周大约十几秒到一分钟。
4. 导入完成后进入**工具页**（课表 / 影视 / 链接解析三个入口），点「课表」进入周视图，左右滑动换周。

**第 1 周周一**留空时，程序会用教务系统报告的"当前是第几周"倒推出来。
如果显示的周次不对，到课表页顶栏的「教务账号」页 →「学期」里手动填一个准确的"第 1 周周一"（必须是周一）。

---

## 三、课表的「实时更新」到底是什么

强智教务系统**没有推送接口**，Android 也不允许 App 常驻长连接，所以这里的"实时"是：

| 手段 | 说明 |
|---|---|
| 定时后台同步 | 默认每 6 小时，可在课表页顶栏「教务账号」页 →「课表同步」里调到最短 15 分钟 |
| 手动刷新 | 课表页右上角的刷新按钮 |
| 变动通知 | 同步后和本地比对，新增 / 停课 / 调课 / 换教室都会通知，并记在课表右上角的「变动」页（有变动时图标上带角标） |
| 桌面小组件 | 跟着同步结果刷新 |
| 上课提醒 | 提前 5–30 分钟通知（这是体感上最"实时"的部分） |

**已知限制**（不粉饰）：

1. 后台任务最快 15 分钟一次；系统 Doze 和厂商省电策略下可能被推迟到几小时才执行。
   想要立刻看到最新课表，用下拉刷新或右上角刷新按钮。
2. 只有教务系统**已经发布**的变更才同步得到，通知必然滞后于学校实际操作。
3. 有些学校的强智系统只允许**校内网**访问，出门在外同步会失败。
   这时 App 会保留上一次的数据并提示失败原因，不会把课表清空。
4. 如果学校登录需要**验证码**，自动同步会失效（登录那一步过不去）。
5. **网页导入来的课表不会后台自动更新**：有的学校没开接口，只能靠 WebView 手动抓一次；
   这种情况下后台同步会被跳过（不会反复推"同步失败"），课表页顶部也会写明。
   `app.do` 与 jsxsd 两个接口都试过、并且接口导入成功之后，标记才会解除、自动同步恢复。

---

## 四、影视

工具页「影视」进来，数据来自四个**苹果CMS10 格式的资源站 JSON 接口**（都是公开接口，
实测存活；分类 id 写死在 `VideoSources.kt` 里，逗号分隔的多个 id 会被当作一次聚合查询）：

| 源 | 域名 | 说明 |
|---|---|---|
| 暴风资源 | `bfzyapi.com` | 默认源，m3u8 直连干净 |
| 量子资源 | `cj.lziapi.com` | 备选，双播放源 |
| 360资源 | `360zy.com` | HLS 是 AES-128 加密，ExoPlayer 原生支持 |
| 非凡资源 | `ffzy5.tv` | HTTP 明文接口，要取第二个播放源（代码已处理） |

- **页签**：电影 / 剧集 / 动漫 / 综艺 / 短剧 —— 每个页签聚合该源的一组子分类
  （接口查父分类不会带子分类内容，所以用逗号拼接叶子分类 id 一次查回）。
- **搜索**：按名称关键词，跨该源全部分类。
- **收藏与进度**：详情页点「收藏」，看过第几集自动记录（本地 DataStore JSON，
  按「源：id」隔离，换源不串数据）；列表里当前位置高亮、看过的变淡。
- **换源**：设置页 →「影视源」单选切换，列表自动按新源重载。某个源挂了就换一个。
- **播放**：点剧集进播放器页；在播放器里也能点「选集」直接换集，不用退回详情页。

> **说明**：影视内容全部来自上述第三方公开接口，本项目只做了一个浏览 / 播放的客户端，
> 不存储、不转码、不代理任何视频内容。

---

## 五、链接解析

工具页「链接解析」进来。能吃两类输入：

1. **B站 / 抖音分享链接**：从 App 里「复制链接」出来的一整段文案直接粘进去
   （`b23.tv` 短链、带一堆说明文字的都行），点「解析并播放」。
2. **普通流地址**：HLS（`.m3u8`）、DASH（`.mpd`）、HTTP 直链，不走解析直接播。

防盗链的站可以在「请求头」里补 `Referer` / `User-Agent` / `Cookie`（每行一个 `名称: 值`）。




### 已知限制（不粉饰）

1. **B站画质受登录状态限制**：不登录一般给到 720P。想上 1080P，把
   `Cookie: SESSDATA=…`（浏览器登录 B站 后从开发者工具里复制）填进「请求头」。
2. **B站只支持 MP4 直链（`durl`）**，不合并 DASH：播放器这头只能塞一个
   `MediaItem`，音视频分离的流没法合并，所以接口只回 dash 时会明确报错，
   而不是丢一条没声音的流出来。
3. **超长视频可能被 B站切成多段**，这时只能播第一段（界面上会提示）。
4. **抖音的直链是有时效的**，解析出来的地址过几小时就失效；失效了就重新粘一次
   分享链接。
5. 抖音作品需要登录（或已删除）时拿不到地址，界面会直说，而不是转圈。

---

## 六、播放器（内嵌播放页）

播放器页面**不在工具页显示**，由「影视」选集和「链接解析」跳进来，只负责播：
Media3/ExoPlayer 解码（硬解失败自动回退软解），支持 HLS / DASH / MP4 直链。

播放本体在 `PlaybackService`（MediaSessionService）里，页面通过 `MediaController`
连上去控制，所以切后台 / 锁屏 / 页面被回收都还能继续放。

### 快捷手势（小窗和全屏都支持）

| 手势 | 作用 |
|---|---|
| 横向拖动 | 快进 / 快退（整屏宽约 90 秒，松手生效，拖动全程有提示气泡） |
| 左半边上下拖动 | 亮度（窗口亮度，只影响当前页面） |
| 右半边上下拖动 | 音量 |
| 双击 | 播放 / 暂停 |
| 单击 | 显示 / 隐藏控制条 |

手势挂在 PlayerView 的触摸监听上：控制条上的按钮（播放、进度条、选集、清晰度、
全屏、设置）是它的子 View，触摸优先分发给子 View，所以手势和控制条互不干扰。

### 选集与清晰度

控制条上的「选集」「清晰度」按钮（排在全屏按钮左边）点开**暗色菜单**，
样式与 media3 自带的设置菜单一致：

- 选集：当前集打勾、看过的变淡，点任意一集原地切集（进度自动记录）；
- 清晰度：列出当前流的分辨率，「自动」+ 各档位，用轨道选择参数限高切换。

### 下载

正在播的那一路点「下载到本地」就能存下来（带进度）。下载用的是**和播放完全相同的
请求头** —— B站/抖音的 CDN 少了 Referer / UA 就是 403。中途取消或失败会把半截文件清掉。

| 系统 | 位置 | 说明 |
|---|---|---|
| Android 10 及以上 | `Movies/qzkt/<标题>.mp4` | 走 MediaStore，**不需要存储权限**，文件管理/相册里能看到，卸载应用也还在 |
| Android 8 / 9 | `Android/data/com.qzkt.timetable/files/Movies/` | 这两个版本写公开目录要 `WRITE_EXTERNAL_STORAGE`；为了下载一个视频就弹权限不划算，退回应用自己的目录（完整路径会显示在界面上，卸载即删） |

文件名取解析出来的标题，去掉 `/ : * ? " < > |` 这些不能做文件名的字符，认不出扩展名时按 `.mp4`。

### 性能

取流走 OkHttp（连接池 + 断线自动重连），下载过的 HLS 分片落盘（256MB LRU 缓存，
回拖 / 重播不再走网络），最多预载 120 秒；无歌词时不做高频页面刷新。
没有歌词文件时歌词区整体隐藏。

---

## 七、网页导入失败 / 登录后白屏怎么办

网页导入用的是系统 WebView。有几个坑是这一类教务系统必踩的，代码里已经处理了：

| 现象 | 原因 | 处理 |
|---|---|---|
| **首页（配置页）用账号密码登不进去，提示"网页校验"** | 学校前台有一层网关，要浏览器执行 JS 才放行；纯 HTTP 客户端请求 `Logon.do` 会被拦下返回 `{"flag1":2,"msgContent":"请先登录系统"}`（青岛农业大学海都学院实测如此） | 改走**应用内登录**：WebView 登录一次后，会话 cookie 存下来，后台自动同步照常工作。程序会认出这个响应并直接这样提示，不再含糊地报"密码错" |
| **接口导入提示"返回的不是 JSON"** | 这所学校没开放 `app.do` 移动端接口。那个地址在网页版平台上会被路由成登录页 HTML，自然不是 JSON | 程序会自动改走 jsxsd 网页版；两个接口都不存在时走应用内登录 |
| **整页空白，诊断里"当前地址：还没开始加载 / 访问过的页面：无"** | 网页导入拿到的地址是空的，`WebView` 压根没被创建，一次导航都没发生 | 网页导入页现在**自带地址输入框**，不依赖上一页表单；配置页也改成地址为空时不再落盘（之前会把已存的好地址覆盖成空串） |
| **登录成功之后一片白** | 强智登录后用 `window.open` 开主界面，而 Android WebView 默认 `setSupportMultipleWindows(false)`，这次跳转被直接丢掉 | 已开 `setSupportMultipleWindows(true)` + `WebChromeClient.onCreateWindow`，把新窗口的地址接回主 WebView |
| **连登录页都是白的**，或"加载完成但什么都没有" | 地址栏被填成了接口地址（`.../app.do`）。那是接口不是网页，用浏览器打开同样是空白 | 网页导入会自动把 `/app.do` 后缀去掉，回到站点根路径；界面上也会明确提示"正文是空的" |
| 只填了域名时连不上 | 默认按 `https` 起手，但学校可能只有 `http` | 加载失败会自动换回 `http` 重试一次 |
| 登录后白屏（另一种） | 教务系统是 https 页面里引 http 资源，默认会被静默拦掉 | 已设 `MIXED_CONTENT_ALWAYS_ALLOW` |
| 抓取了但一门课都没有 | 主界面是 `<frameset>`，把所有 frame 的 HTML 拼成一份再解析的话，HTML 解析器遇到 `<frameset>` 会把后面的内容整段丢掉 | 现在每份文档**分开解析再合并**（`QzWebParser.parseDocuments`），有单元测试锁住这个行为 |
| 提示证书校验失败 | 学校用的是自签证书 | 把学校的 CA 装进手机；或者点界面上的「继续（不安全）」（明确标注了风险） |

**分不清是地址问题还是 WebView 问题？** 空白页提示里有个**「用系统浏览器打开」**按钮：手机会用浏览器打开同一个地址。浏览器能出登录页 → 地址是对的，问题在 WebView；浏览器也空白 → 地址本身不对。

页面还带了一个**诊断信息**面板（在按钮下方展开），会显示：

- 当前地址
- **访问过的所有地址（含 iframe）** —— 用来判断课表到底在哪一层
- 最近一次抓取：地址、标题、iframe 数、HTML 长度
- 页面正文前 800 字 —— 解析不出来时，直接看页面到底写了什么
- 导入结果（成功几条 / 为什么失败）

还有「复制诊断信息」按钮。**如果还是不行，把这个面板的内容发我**，就能定位是排版不认识、还是跨域 frame 读不到。

已知限制：如果课表在**跨域 iframe** 里，JS 读不到它的 `document`，抓取必然为空（诊断面板的地址列表里能看到那个 frame 的 URL）。

---

## 八、各校强智字段不一样，课表显示不出来怎么办

强智有多个版本，同一个接口 `getKbcxAzc` 返回的字段名各校不同。本项目用
**别名表 + 启发式推断**尽量兜住，但仍可能遇到没见过的写法。

排查步骤：

1. 课表页顶栏「教务账号」页 →「数据」→「查看接口原始返回」，看学校到底返回了什么 JSON。
2. 如果字段名是新的（比如课程名字段叫 `kc` 而不是 `kcmc`），在
   `app/src/main/kotlin/com/qzkt/timetable/jw/parse/FieldAliases.kt`
   对应的别名列表里加一条即可。
3. 加完重新编译安装。

解析器对三种已见的字段布局都有单元测试覆盖
（`KbcxParserTest` 里的布局 A / B / C），加别名时顺手加一条测试。

---

## 九、代码结构

```
app/src/main/kotlin/com/qzkt/timetable/
├── QzApp.kt                    手写的依赖容器（没有用 Hilt）
├── MainActivity.kt
├── model/                      CourseSession / Term / SyncChange 等数据模型
├── jw/                         教务接口层
│   ├── JwAdapter.kt            "登录 + 读课表"的抽象
│   ├── qz/QzAppDoAdapter.kt    强智 app.do 移动端接口（主通道）
│   ├── qz/QzWebParser.kt       网页版课表 HTML 解析（兜底通道）
│   └── parse/                  节次、周次、字段别名、课表 JSON 的容错解析
├── data/
│   ├── AppSettings / SettingsStore / TimetableStore / TimetableRepository
│   ├── anime/                  影视：模型、资源站客户端（MacCmsSource / VideoSources）、收藏与进度
│   └── update/                 检查更新（GitHub Releases）
├── sync/                       WorkManager 定时同步、通知、上课提醒闹钟
├── widget/                     Glance 桌面小组件
└── ui/
    ├── QzktApp.kt              导航壳：工具页为起始页，底部「工具 / 设置」两个页签
    ├── grid/                   课表周视图
    ├── tools/                  工具页（起始页：课表 / 影视 / 链接解析）
    ├── anime/                  影视列表、详情、ViewModel
    ├── player/                 播放页、快捷手势、后台播放服务、链接解析页、B站/抖音解析器
    ├── settings/               外观 / 影视源 / 存储管理 / 关于
    ├── account/ log/ debug/ setup/ web/   账号（含学期 / 作息 / 同步 / 提醒 / 数据） / 变动日志 / 调试 / 配置向导 / 网页导入
    └── common/ theme/          公共组件（SectionCard / SplashOverlay）/ 主题
```

### 接口约定

强智不同代的产品接口完全不同，所以做了两个适配器，由 `SmartQzAdapter` 自动选：

**① jsxsd 网页版**（地址里含 `/jsxsd`）—— 几乎每所学校都有：

```
POST {base}/Logon.do?method=logon&flag=sess   → "<scode>#<sxh>"
     必须带 X-Requested-With: XMLHttpRequest（登录页里是 jQuery 调的，
     不带这个头服务器会把登录页整页返回来）
     用 scode/sxh 把「学号%%%密码」搅成 encoded（算法见 QzJsxsdLogin）
POST {base}/xk/LoginToXk                      → loginMethod/userAccount/encoded，拿会话 cookie
GET  {base}/xskb/xskb_list.do                 → 整学期课表 HTML（每门课自带周次）
GET  {base}/framework/xsMain_new.jsp?t1=1     → 当前周次 / 总周数
```

**② app.do 移动端接口** —— 只有一部分学校开放：

```
GET {base}?method=authUser&xh={学号}&pwd={密码}
  → {"flag":"1","msg":"...","token":"..."}      之后所有请求带请求头 token
GET {base}?method=getCurrentTime&currDate=yyyy-MM-dd
  → {"xnxqh":"2026-2027-1","zc":"3"}
GET {base}?method=getKbcxAzc&xh={学号}&xnxqid={xnxqh}&zc={周次}
  → [{...}, ...]
```

`SmartQzAdapter` 的选法：地址里有 `jsxsd` 就先试网页版，否则先试 app.do；
只有"网络不通"和"这个接口在这儿不存在"（`JwException.wrongProtocol`）才换另一个，
**网关拦截和账号密码错会直接上报**——换平台也一样，没必要让用户多等一轮。

### 学校前台有网关校验怎么办

登录页要用浏览器执行 JS 才能拿到"通行证"，
纯 HTTP 客户端请求 `Logon.do` 会被前面一层网关拦下，返回
`{"flag1":2,"msgContent":"请先登录系统"}`。**账号密码登录在这类学校是走不通的**，
适配器会认出这个响应并明确告诉用户，而不是含糊地报"密码错误"。

对这类学校，正确做法是**在应用内登录一次**：

```
WebView 里登录 → 会话 cookie 存进设置 → 后台定时同步拿这个 cookie 直接拉课表页
```

会话过期时同步会提示"请打开应用重新登录一次"（数据保留，不会反复推失败通知）。
课表页顶部只有在**没有可用会话**时才会提示无法自动更新。

### 为什么不用 Room / Hilt

课表数据一学期只有几百条，整份存 JSON 文件 + 内存索引足够，查询是毫秒级；
去掉 Room 就同时去掉了 KSP 代码生成插件（KSP 和 Kotlin 版本必须严格配对，
是这个项目里最容易在换环境时炸掉的一环）。依赖注入只有七八个对象，手写容器更省事。

数据层抽象在 `TimetableStore` 后面，日后想换 Room 只改实现。

---

## 十、测试

```bash
./gradlew :app:testDebugUnitTest
```

- `PeriodParserTest` / `WeekRangeParserTest`：节次与周次的各种写法（含单双周、全角）
- `KbcxParserTest`：三套不同字段布局的课表 JSON（别名表命中 / 英文键名 / 全靠启发式）
- `QzWebParserTest` / `QzWebParserListModeTest`：网页版课表的网格排版与列表排版，
  含 colspan/rowspan 合并、一个格子塞多门课、"别把『第 5 节』当成星期三"，
  以及**frameset 主界面必须分开解析而不能拼成一份 HTML**（拼了就全军覆没）
- `WebImportSupportTest`：网页导入的地址整型（`app.do` 后缀会被去掉）、
  探针结果的**两层 JSON 解码**、以及"空白页"的判定（frameset 没有 body 但不算空白）
- `QzAppDoAdapterTest`：用 JDK 自带的 HTTP 服务器起一个**假的强智系统**，
  用真实的 OkHttp 请求跑通「登录 → 取学期 → 取课表 → 解析」整条链路，
  覆盖密码错误、GBK 编码、非 JSON 响应、只填域名时的 https→http 回退等分支
- `TimetableRepositoryTest`：逐周合并与变更检测（换教室 / 停课 / 调课）
- `MediaLinkTest`：分享链接识别（BV / av / ep / ss、分P、抖音短链与网页地址，
  以及**普通 `.m3u8` 直链不能被误判成分享链接**）
- `BilibiliResolverTest` / `DouyinResolverTest`：用 JDK 自带的 HTTP 服务器假一个
  B站 / 抖音，跑真实的 OkHttp 请求与跳转，覆盖短链跳转、接口报文解析、
  去水印改写（`playwm` → `play`）、分享页改版后的兜底，以及接口报错时的话术
- `MediaDownloaderTest`：真下 200 KB 字节，验证**字节一个不少**、进度最后一定到 100%
  （而不是停在 97%）、请求头原样带上、服务端不给 Content-Length 时不瞎报进度，
  以及文件名清洗 / HLS·DASH 判定 / MIME 推断

---

## 十一、检查更新与发新版

设置页 →「关于」→「检查更新」：读 GitHub Releases 的最新版本，
和当前版本号**逐段数值比较**（`1.2.10 > 1.2.9`，带不带 `v` 前缀都认）。

- 最新版号更大 → 显示「发现新版本」+ Release 说明 + 「下载更新」按钮
  （Release 里传了 `.apk` 附件就跳浏览器下载 APK，没传就跳发布页）；
- 仓库还没有 Release，或当前已是最新 → 明确提示。

**发一版的固定流程**（Git Bash，可整段照抄，把版本号替换掉即可）：

```bash
cd /d/C/qzkt

# 0) 升版本号：编辑 app/build.gradle.kts，两行都要动 ——
#    versionCode  加 1（安卓用它判断能否覆盖安装，忘了改旧包就装不上新包）
#    versionName  升一档（应用内检查更新显示的就是它，如 "1.0.8" → "1.0.9"）

# 1) 打包（JAVA_HOME 指向项目自带 JDK，本机不用装 Java）
export JAVA_HOME="$PWD/.toolchain/jdk-21"
./gradlew.bat :app:assembleDebug

# 2) 取出 APK，按版本号重命名（放哪个目录都行）
cp app/build/outputs/apk/debug/app-debug.apk /d/tmp/Tools-v1.0.9.apk

# 3) 写更新说明（应用内「检查更新」展示的就是这段）
cat > /d/tmp/notes.md << 'EOF'
- 本次改动一
- 本次改动二
EOF

# 4) 提交推送代码（含版本号改动）
git add -A
git commit -m "版本号升至 1.0.9"
git push origin main

# 5) 发 Release：tag 带 v 前缀，挂上 APK
gh release create v1.0.9 /d/tmp/Tools-v1.0.9.apk \
  --title "v1.0.9" \
  --notes-file /d/tmp/notes.md
```

要点与排错：

- **版本号规则**：`versionCode` 永远 +1；`versionName` 三段递增；Release 的 tag 用
  `vX.Y.Z`（应用内检查更新按它比较新旧）。
- **gh 没登录**：`gh auth login` 按提示走一遍（本仓库是 luSJX2022/Tools）。
- **push 连不上**（`Connection was reset` / 连接超时）：GitHub 偶发抽风，等一两分钟
  重试 `git push origin main` 即可，提交不会丢。
- **旧包装不上新包**：多半是忘了升 `versionCode`。
- **忘了挂 APK**：`gh release upload vX.Y.Z Tools-vX.Y.Z.apk` 可以事后补传。
- 用 Android Studio 的话：Build → Build App Bundle(s) / APK(s) → Build APK(s)，
  产物在同一路径，其余步骤相同。

Release 的 `body` 会作为更新说明显示在应用内（默认折叠 4 行，可展开）。

---

*影视功能的视频内容均来自上述第三方公开接口，本项目不存储、不转码、不代理任何视频内容，仅供学习交流。*
