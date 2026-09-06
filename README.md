# WebDAV 同步（Android）

一个轻量、无广告的 Android 工具：把手机里的**整个目录单向上传（同步）**到 WebDAV 服务器（Nextcloud / ownCloud / 群晖 / 坚果云 / 自建等）。

界面分为 **「任务」** 和 **「配置」** 两个标签页。

## 功能

- **配置页（登录）**
  - WebDAV 服务器地址、用户名、密码集中管理，本地持久化保存
  - 「测试连接」一键验证（PROPFIND，自动回退 HEAD）
  - 可选「忽略 SSL 证书」，方便连接自签名 / 内网服务器
  - 「仅在 WiFi 下同步」开关（默认开启）
- **任务页（多任务同步）**
  - 可添加任意多个同步任务，每个任务 = 一个本地目录 → 一个远程目录
  - 通过系统目录选择器（SAF）授权，**无需任何存储权限**
  - 递归上传整个目录（含所有子目录），保持远程目录结构
  - 每个任务独立开关，可单独「同步」或「全部同步」
  - 实时进度：当前文件、文件数进度、已上传 / 已跳过计数
  - 记录上次同步时间与结果；可编辑或删除任务
- **单向上传**：只上传、不下载、不删除远端任何文件
- **增量跳过**：远端已存在且大小一致的文件自动跳过，不重复上传
- **仅 WiFi 同步**：非 WiFi 时任务挂起显示「等待 WiFi」，连上 WiFi 自动续传
- 自动创建远程目录（MKCOL），文件以 `PUT` 上传，支持 Basic 认证
- 纯标准库实现（`HttpURLConnection`），不依赖任何第三方网络库

## 下载 APK

仓库根目录包含编译并签名好的安装包：

- `WebDAVUploader-v1.1.1.apk`（v2 签名，Android 7.0+ / API 24 起可安装）
- 直链：https://github.com/totootao/WebDAVUploader/raw/main/WebDAVUploader-v1.1.1.apk
- 或到 [Releases](https://github.com/totootao/WebDAVUploader/releases) 页面下载

## 更新记录

- **v1.1.1**：修复 Android 上 `HttpURLConnection` 方法白名单导致 MKCOL/PROPFIND
  报错（"Expected one of [OPTIONS, GET, …] but was MKCOL"）、目录无法创建的问题；
  两者改为原生 Socket 通道实现。
- **v1.1.0**：界面重构为「任务 / 配置」双标签页；新增目录单向上传、多任务、仅 WiFi 同步、增量跳过、应用图标。

## 使用

1. 安装 APK（允许「未知来源」安装）。
2. 打开应用 → 切到 **配置** 页：
   - 服务器地址，如 `https://dav.example.com/dav/`（结尾带 `/`）
   - 填写用户名 / 密码
   - 自签名证书勾选「忽略 SSL 证书」
   - 点「测试连接」确认能连通，再点「保存配置」
3. 回到 **任务** 页 → 点「新建任务」：
   - 任务名称（如「相册备份」）
   - 「选择本地目录」：在系统目录选择器中选中目录并允许授权
   - 远程目录：如 `/backup/photos`，留空表示服务器根目录
4. 点「同步」执行单个任务，或点「全部同步」按顺序执行所有已开启的任务。

> 提示：仅 WiFi 同步默认开启。若当前不在 WiFi，任务会标记「等待 WiFi」，
> 连上 WiFi 后应用会自动继续执行（需应用在前台）。如需用移动网络同步，
> 可在配置页关闭「仅在 WiFi 下同步」。

## 构建

### 方式一：GitHub Actions 自动构建（无需本地环境）

- `build.yml`：**Actions** 页面手动触发，编译并产出 `app-release-apk` 构件
- `publish-release.yml`：推送 `v*` 标签或手动触发，用仓库内已编译好的 APK 创建 Release

### 方式二：本地构建

```bash
# 需要：JDK 17+、Android SDK（platforms;android-34、build-tools;34.0.0）
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 国内网络可设置镜像环境变量后再构建：
> `export ANDROID_BUILD_MIRROR=https://mirrors.cloud.tencent.com/nexus/repository/maven-public/`

## 说明

- `release` 构建使用 Android 自动生成的 debug 签名，可直接安装测试。
- 如需正式发布，请在 `app/build.gradle` 配置自己的 release 签名（`signingConfigs`）。
- 目录授权使用 SAF 的可持久化 URI 权限；若系统回收了授权，任务会提示重新选择目录。

## 权限

- `INTERNET`：访问 WebDAV 服务器
- `ACCESS_NETWORK_STATE`：判断当前是否为 WiFi，用于「仅 WiFi 同步」

不申请任何存储权限；目录通过系统选择器授权访问。
