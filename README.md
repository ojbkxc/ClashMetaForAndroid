# 蓝星网络 (LanXing Network)

基于 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 定制的 V2Board 订阅客户端。

## 功能特性

- V2Board 账号登录（首次安装后自动跳转，登录状态持久化）
- 订阅配置自动同步（可配置间隔）
- 多域名自动回退（内置域名不可用时自动从更新地址拉取新域名）
- 主页显示账户信息（流量、到期时间）
- 帮助/关于页面直接打开 V2Board 知识库和关于页
- 兼容原版 Clash Meta 全部功能（代理、规则、日志等）

## 编译配置

在项目根目录创建 `local.properties`，添加以下配置：

```properties
# Android SDK 路径（必填）
sdk.dir=/path/to/android-sdk

# ========== V2Board 配置 ==========

# V2Board 主域名（可选，留空则使用内置域名列表第一个）
v2board.url=https://jc.lxseek.com

# 内置域名列表，逗号分隔（可选）
v2board.domains=https://jc.lxseek.com,https://go.lxkjzh.top,https://cdn.lxkjzh.top

# 域名更新地址（可选，当所有内置域名不可用时从此地址拉取新域名列表）
v2board.update.url=https://update.lxseek.com

# 订阅同步间隔，单位分钟（可选，默认 1440 = 24小时）
v2board.sync.interval=1440

# 自定义应用包名（可选）
custom.application.id=com.my.compile.clash

# 去掉包名后缀（可选，设为 true 后包名不再带 .alpha/.meta 后缀）
remove.suffix=true
```

### 签名配置

创建 `signing.properties`：

```properties
keystore.path=/path/to/keystore/file
keystore.password=<password>
key.alias=<alias>
key.password=<password>
```

### 编译命令

```bash
# 编译 Alpha 版本
./gradlew app:assembleAlphaRelease

# 编译 Meta 版本
./gradlew app:assembleMetaRelease
```

## 架构说明

### 新增模块

| 模块 | 说明 |
|------|------|
| `v2board/` | V2Board 核心模块（API、配置、会话管理、域名回退） |

### 新增文件

| 文件 | 说明 |
|------|------|
| `V2BoardConfig.kt` | 多域名配置、更新地址、同步间隔管理 |
| `V2BoardSession.kt` | 登录态持久化（SharedPreferences） |
| `V2BoardApi.kt` | Retrofit API 接口定义 |
| `V2BoardSync.kt` | 域名回退、API 调用、单例管理 |
| `V2BoardActivity.kt` | WebView 壳（登录/用户中心/套餐/关于/知识库） |
| `V2BoardAutoSync.kt` | 订阅自动同步到 Profile |
| `V2BoardDesign.kt` | WebView UI 层 |

### 登录流程

```
首次安装
  → 启动 App → 检测 hasEverLoggedIn = false
  → 打开 V2Board 登录页（WebView）
  → 用户登录 → JS Bridge 捕获 auth_data
  → 保存到 SharedPreferences → hasEverLoggedIn = true
  → 自动同步订阅 → 进入主页

再次启动
  → 检测 hasEverLoggedIn = true → 直接进入主页
  → 不发起任何网络请求
  → 点击"我的账户"等按钮时才加载 WebView
```

### 域名回退机制

```
1. 优先使用 activeDomain（上次成功的域名）
2. 其次使用 serverUrl（配置的域名）
3. 最后使用内置域名列表逐个探测
4. 全部失败 → 从 updateUrl 拉取新域名列表 → 再次探测
```

### 更新地址接口格式

更新地址 `https://update.lxseek.com/domains.json` 应返回：

```json
{
  "domains": ["https://new-domain1.com", "https://new-domain2.com"],
  "update_url": "https://new-update-server.com"
}
```

## 编译要求

- Android 5.0+（最低），Android 7.0+（推荐）
- OpenJDK 11+
- Android SDK
- CMake
- Golang
- 架构：`armeabi-v7a` / `arm64-v8a` / `x86` / `x86_64`
