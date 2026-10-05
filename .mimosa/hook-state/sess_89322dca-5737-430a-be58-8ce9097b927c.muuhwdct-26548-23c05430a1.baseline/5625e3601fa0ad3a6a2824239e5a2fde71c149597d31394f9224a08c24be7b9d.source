// 说明：这里刻意把阿里云/腾讯云的 Maven 镜像排在 mavenCentral() 前面。
// Kotlin 的 kotlin-compiler-embeddable 在 Maven Central 上是一个 301 跳转到 GitHub Releases
// 的条目，而本机到 GitHub 的 TLS 握手过不去（PKIX path building failed），
// Gradle 遇到这种网络错误不会自动回退到下一个仓库，所以必须让镜像先被命中。
pluginManagement {
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}

rootProject.name = "tools"
include(":app")
