# WebDAV 文件上传（Android）

一个轻量、无广告的 Android 工具：把手机里的任意文件上传到 WebDAV 服务器（Nextcloud / ownCloud / 群晖 / 坚果云 / 自建等）。

## 功能

- 配置 WebDAV 地址、账号、密码与远程目录，配置本地持久化保存
- 通过系统文件选择器（SAF）选取一个或多个文件，**无需存储权限**
- 自动创建远程目录（MKCOL），文件以 `PUT` 上传，支持 Basic 认证
- 上传进度实时显示，列表展示每个文件的状态
- 可选「忽略 SSL 证书」模式，方便连接自签名/测试服务器
- 纯标准库实现（`HttpURLConnection`），不依赖任何第三方网络库

## 构建

### 方式一：GitHub Actions 自动构建（推荐，无需本地环境）

每次向 `main` 推送或打 `v*` 标签，工作流会自动编译并用 debug 签名产出 APK：

- 推送后到仓库 **Actions** 页下载 `app-release-apk` 产物
- 打标签 `v1.0.0` 推送后，会在 **Releases** 自动发布带 APK 的版本

### 方式二：本地构建

```bash
# 需要：JDK 17+、Android SDK（platforms;android-34、build-tools;34.0.0）
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 国内网络可设置镜像环境变量后再构建：
> `export ANDROID_BUILD_MIRROR=https://mirrors.cloud.tencent.com/nexus/repository/maven-public/`

## 使用

1. 安装 APK（允许「未知来源」安装）。
2. 打开应用，填写 WebDAV 地址，例如 `https://dav.example.com/dav/`。
3. 填写用户名 / 密码；远程目录可留空（默认上传到根）。
4. 自签名证书服务器请勾选「忽略 SSL 证书」。
5. 点「保存配置」，再点「选择文件并上传」。

## 说明

- `release` 构建使用 Android 自动生成的 debug 签名，可直接安装测试。
- 如需正式发布，请在 `app/build.gradle` 配置自己的 release 签名（`signingConfigs`）。

## 权限

- `INTERNET`：用于访问 WebDAV 服务器。
- 不申请任何存储权限；文件通过系统选择器授权访问。
