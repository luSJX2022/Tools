# 强智课表（Android）

一个原生 Android 课表应用：登录学校的**强智教务系统**自动导入本学期课表，
之后按设定的间隔在后台同步，课表有变动（调课 / 停课 / 换教室）时发通知。

- Kotlin + Jetpack Compose（Material 3）
- 数据保存在本机，只有登录时才会连学校的教务系统
- 支持桌面小组件（今日课程）、上课前提醒

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

## 二、首次使用

1. 打开 App，填写：
   - **学校强智地址**：就是你平时在电脑浏览器里打开教务系统的网址，例如 `http://jwgl.xxx.edu.cn`。
     程序会自动补上 `/app.do`。只填域名（不带 `http://`）也能用：会先按 `https` 试，
     连不上自动退回 `http` —— 很多学校的强智还是明文 HTTP。
   - **学号 / 密码**
   - **学期周数**（默认 20）、**第 1 周周一**（可留空，见下）
2. 点「测试连接」验证地址和账号。失败时会直接显示学校返回的原因。
3. 点「导入课表」，程序会逐周拉取整个学期的课表。20 周大约十几秒到一分钟。
4. 导入完成后进入周视图。左右滑动换周。

**第 1 周周一**留空时，程序会用教务系统报告的"当前是第几周"倒推出来。
如果显示的周次不对，到设置页手动填一个准确的"第 1 周周一"（必须是周一）。

---

## 三、"实时更新"到底是什么

强智教务系统**没有推送接口**，Android 也不允许 App 常驻长连接，所以这里的"实时"是：

| 手段 | 说明 |
|---|---|
| 定时后台同步 | 默认每 6 小时，可在设置里调到最短 15 分钟 |
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

## 四、网页导入失败 / 登录后白屏怎么办

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

## 五、各校强智字段不一样，课表显示不出来怎么办

强智有多个版本，同一个接口 `getKbcxAzc` 返回的字段名各校不同。本项目用
**别名表 + 启发式推断**尽量兜住，但仍可能遇到没见过的写法。

排查步骤（前两步在设置页）：

1. 设置页 →「查看接口原始返回」，看学校到底返回了什么 JSON。
2. 如果字段名是新的（比如课程名字段叫 `kc` 而不是 `kcmc`），在
   `app/src/main/kotlin/com/qzkt/timetable/jw/parse/FieldAliases.kt`
   对应的别名列表里加一条即可。
3. 加完重新编译安装。

解析器对三种已见的字段布局都有单元测试覆盖
（`KbcxParserTest` 里的布局 A / B / C），加别名时顺手加一条测试。

---

## 六、代码结构

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
├── data/                       JSON 文件持久化 + DataStore 配置 + 仓库（逐周合并、变更检测）
├── sync/                       WorkManager 定时同步、通知、上课提醒闹钟
├── widget/                     Glance 桌面小组件
└── ui/                         Compose 界面（配置向导 / 周视图 + 顶部栏的变动·账号 / 设置 / 调试 / 网页导入 / 播放器）
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

青岛农业大学海都学院就是这样：它的登录页要用浏览器执行 JS 才能拿到"通行证"，
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

## 七、测试

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

## 八、播放器（Tools 的第二个工具）

底部页签「播放器」能放三类东西：

1. **本地文件**（「选择文件」，或从别的应用「打开方式」进来）；
2. **网络地址**：HLS（`.m3u8`）、DASH（`.mpd`）、HTTP 直链；防盗链的站可以在
   「请求头」里补 `Referer` / `User-Agent`；
3. **B站 / 抖音的分享链接**：从手机 App 里「复制链接」出来的一整段文案直接粘进
   输入框（或点「粘贴」）再点「播放」即可 —— 短链、带一堆说明文字都行；
4. **下载**：正在播的那一路点「下载」就能存到本地（带进度），下完还能直接「播放已下载」。

播放器本体在 `PlaybackService`（MediaSessionService）里，页面通过 `MediaController`
连上去控制，所以切后台 / 锁屏 / 页面被回收都还能继续放。

### 分享链接是怎么解析的

| 平台 | 过程 |
|---|---|
| B站 | 短链（`b23.tv`）先跟一次跳转拿到 BV 号 → `x/web-interface/view` 拿 cid → `x/player/playurl` 拿播放地址；番剧（`ep` / `ss`）走 `pgc/view/web/season` |
| 抖音 | 短链跳转拿到作品号 → 抓分享页（`iesdouyin.com/share/video/{id}/`）HTML 里的 `_ROUTER_DATA` → 取 `video.play_addr`；分享页改版时退回 `aweme/iteminfo` 老接口 |

解析出来的地址和请求头会一起交给播放器：B站 CDN 必须带
`Referer: https://www.bilibili.com/`，抖音 CDN 只认手机 UA。

### 真机上踩到的坑（都已在代码里处理）

这几条是拿真机（vivo Y300 / Android 16）实测出来的，不是猜的：

1. **B站接口要 `buvid3` 这个 Cookie**。没有它，`x/web-interface/view` 不回 403 也不回 JSON，
   而是直接甩一页 `<!DOCTYPE html>` 的风控页 —— 现象是「解析失败」，跟链接对不对毫无关系。
   处理：请求前先像浏览器那样访问一次 `bilibili.com` 首页把 Set-Cookie 收进内存 CookieJar，
   首页没给就问 `x/frontend/finger/spi` 直接要 buvid3/buvid4（见 `LinkCookieJar`）。
   顺带一个坑：用户填的 `Cookie: SESSDATA=…` 如果当普通请求头传，OkHttp 会**顶掉** Jar 里的
   buvid3，风控立刻回来 —— 所以它被拆进 Jar 统一发。
2. **B站 CDN 不认安卓 UA**。请求接口时安卓 Chrome UA 最稳，但下载视频时 CDN 恰恰拒绝它：
   同一台手机上实测，安卓 UA + Accept 头 → 403，去掉 Accept → 403，Referer 不带斜杠 → 403，
   **换成桌面 Chrome UA 且不带 Accept → 206**。处理：解析完用 `Range: bytes=0-1` 探一次，
   按「桌面 UA / 安卓 UA」两组试，谁回 2xx 就用谁的头（正常情况只多花一个请求）。
3. **主地址可能落在边缘 / P2P 节点上**（例如 `809al93l.edge.mountaintoys.cn:4483`），
   那个域名在手机上 connect 直接超时；`backup_url` 里的 `upos-*.bilivideo.com` 才是通的。
   处理：候选地址里常规镜像排前面，主地址连不上就换备用镜像，不在它身上把每组请求头都超时一遍。

4. **抖音分享页没有 `ttwid` 就是空壳**。没有这个 Cookie 时，页面照样有 `_ROUTER_DATA`，
   但 `item_list` 是空的（页面也不给过滤原因），现象还是「解析不到播放地址」。
   处理：先向字节的 ttwid 注册接口换一个 ttwid 再抓分享页（见 `DouyinResolver.ensureTtwid`）。
5. **抖音分享页按 UA 给不同布局，而且不稳定**。有时回带 `videoInfoRes.item_list` 的移动分享页，
   有时回 **web 布局页**：`loaderData` 里只有 `video_layout` 和 `video_(id)/page`，压根没有播放信息
   （日志里连着两次 `没有播放地址：loaderData=[video_layout,video_(id)/page]`）。
   而且同一个 UA 两次请求结果都能不一样 —— 实测一次 UA#2 命中，另一次 UA#1、UA#2 全落空、UA#3 才命中。
   所以**不能押注某一个 UA**：按 iPhone Safari → 微信内置浏览器 → 安卓 Chrome 依次试，谁给数据用谁
   （logcat 里 tag `QzLink` 会写第几个 UA 命中）。
   解析器也不再写死 `loaderData → videoInfoRes → item_list` 这条路径，而是在整棵 JSON 树里
   找第一个真带播放地址的节点，结构再改也不至于直接失效。

实测结果（真机，两台都是完整链路）：

| 平台 | 解析 | 播放 | 下载 |
|---|---|---|---|
| B站 `BV1GJ411x7h7` | ✅ 标题 + 720P | ✅ | ✅ `Never Gonna Give You Up - Rick Astley · 720P.mp4` **51,973,319 字节**，与接口报的 size 一致 |
| 抖音 `v.douyin.com/LLb_Um7Z5wo/` | ✅ 标题取到作品原文 | ✅ | ✅ `KB 就这样被升调海伊原地硬控 #古风演唱会现场 #国风起时.mp4` **3,677,628 字节** |

两个文件都落在 `/sdcard/Movies/qzkt/`，可直接在相册/文件管理里打开。

### 下载到哪里

| 系统 | 位置 | 说明 |
|---|---|---|
| Android 10 及以上 | `Movies/qzkt/<标题>.mp4` | 走 MediaStore，**不需要存储权限**，文件管理/相册里能看到，卸载应用也还在 |
| Android 8 / 9 | `Android/data/com.qzkt.timetable/files/Movies/` | 这两个版本写公开目录要 `WRITE_EXTERNAL_STORAGE`；为了下载一个视频就弹权限不划算，退回应用自己的目录（完整路径会显示在界面上，卸载即删） |

文件名取解析出来的标题，去掉 `/ : * ? " < > |` 这些不能做文件名的字符，认不出扩展名时按 `.mp4`。
下载用的是**和播放完全相同的请求头** —— B站/抖音的 CDN 少了 Referer / UA 就是 403。
中途取消或失败会把半截文件清掉（MediaStore 里那条 `IS_PENDING` 记录一并删）。

### 已知限制（不粉饰）

1. **B站画质受登录状态限制**：不登录一般只给到 480P。想上 1080P，把
   `Cookie: SESSDATA=…`（浏览器登录 B站 后从开发者工具里复制）填进「请求头」，
   解析时会一起带给接口和 CDN。
2. **B站只支持 MP4 直链（`durl`）**，不合并 DASH：播放器这头只能塞一个
   `MediaItem`，音视频分离的流没法合并，所以接口只回 dash 时会明确报错，
   而不是丢一条没声音的流出来。
3. **超长视频可能被 B站切成多段**，这时只能播第一段（界面上会提示）。
4. **抖音的直链是有时效的**，解析出来的地址过几小时就失效；失效了就重新粘一次
   分享链接。
5. 抖音作品需要登录（或已删除）时拿不到地址，界面会直说，而不是转圈。
6. **HLS（`.m3u8`）和 DASH（`.mpd`）不能直接下载**：它们本身只是播放列表，
   要下载得把所有分片拉下来再合并（B站 DASH 还要音视频各合一路），这一步没做，
   点下载会明确说明，而不是存一个放着没用的 `.m3u8`。
