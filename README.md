# Tools（Android）

一个原生 Android 工具箱应用：

- **课表**：登录学校的**强智教务系统**自动导入本学期课表，之后按设定的间隔后台同步，
  课表有变动（调课 / 停课 / 换教室）时发通知；带桌面小组件和上课提醒。
- **影视**：接入四个第三方资源站（暴风 / 量子 / 360 / 非凡），电影、剧集、动漫、综艺、短剧
  分类浏览 + 搜索，本地收藏与观看进度，选集后直接进播放器。
- **链接解析**：B站 / 抖音 App 里「复制链接」出来的一整段文案（短链、带说明文字都行），
  粘进去解析成真实播放地址，直接播放。
  图书（本地 TXT / 在线书源 / 国图检索）

图书：

- **书架**：导入本地 TXT（UTF-8 / GBK 自动识别），段落滚动阅读、进度记忆、
  字号 / 夜间模式；阅读进度按段落下标存，重排不丢位置。
- **书城**：可切换全本小说网、鲲弩小说，以及古腾堡中文公版书；搜书 → 进书架 → 在线阅读。
  古腾堡书目通过 Gutendex 查询，只展示标记为美国公版的中文作品；版权状态可能因地区而异。

- Kotlin + Jetpack Compose（Material 3）+ Media3 + OkHttp + DataStore
- minSdk 26，无 Room / 无 Hilt（JSON 文件持久化 + 手写依赖容器，理由见下文）
- 课表数据保存在本机，只有登录时才会连学校的教务系统

---

## 一、构建

本项目自带一套**免安装**的构建工具链（不需要系统里预先有 JDK 或 Gradle）。

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


---


## 二、课表的「实时更新」到底是什么

强智教务系统**没有推送接口**，Android 也不允许 App 常驻长连接，所以这里的"实时"是：

| 手段 | 说明 |
|---|---|
| 定时后台同步 | 默认每 6 小时，可在课表页顶栏「教务账号」页 →「课表同步」里调到最短 15 分钟 |
| 手动刷新 | 课表页右上角的刷新按钮 |
| 变动通知 | 同步后和本地比对，新增 / 停课 / 调课 / 换教室都会通知，并记在课表右上角的「变动」页（有变动时图标上带角标） |
| 桌面小组件 | 跟着同步结果刷新 |
| 上课提醒 | 提前 5–30 分钟通知（这是体感上最"实时"的部分） |

**已知限制**：

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



## 三、代码结构

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
*影视功能的视频内容均来自上述第三方公开接口，本项目不存储、不转码、不代理任何视频内容，仅供学习交流。*
