# 飞哥影视仓 · Android

TVBox 类壳应用，通过自行提供的订阅地址加载点播与直播内容。**Android 手机 / 平板 / 电视共用一个 APK。**

> 本应用不内置任何内容源，也不提供解析接口。所有内容均来自使用者自行添加的订阅地址。

## 技术栈

| 项 | 选择 | 说明 |
|---|---|---|
| 语言 | Kotlin 2.0.21 | — |
| UI | Jetpack Compose（BOM 2024.10.01） | 三端一套代码，形态在运行时判定 |
| 构建 | AGP 8.6.1 / Gradle 8.7 / JDK 17 | minSdk 24，compileSdk 35 |
| 播放 | media3 ExoPlayer 1.4.1 | 支持 HLS，控制层自绘 |
| 图片 | Coil 2.7 | — |
| 网络 | OkHttp 4.12 | 只做 GET |

## 工程结构

```
app/src/main/java/me/bsdnme/tvbox/
├── MainActivity.kt              唯一 Activity，形态判定后进入 Compose
├── core/
│   ├── DeviceForm.kt            手机 / 平板 / 电视判定
│   └── theme/                   颜色令牌、圆角、字号、FormFactor
├── data/
│   ├── LocalStore.kt            订阅、收藏、历史、设置持久化
│   ├── model/Models.kt          全部数据模型
│   └── net/
│       ├── Http.kt              OkHttp 封装 + 统一异常
│       ├── VodParser.kt         CMS 的 JSON / XML 解析
│       ├── VodApi.kt            分类 / 列表 / 搜索 / 详情
│       ├── LiveParser.kt        直播 m3u + txt 解析
│       ├── LiveApi.kt           直播源加载
│       ├── SubscriptionApi.kt   订阅拉取（含多仓识别）
│       └── UpdateApi.kt         检查更新：拉取 update.json + 下载 APK
├── vm/AppViewModel.kt           全局状态（StateFlow）
└── ui/
    ├── FeigeMediaApp.kt             根组合 + 三层路由
    ├── component/               焦点容器、海报卡、轨道、网格、大图位
    ├── shell/AppShell.kt        手机底部栏 / 平板侧栏 / 电视轨道
    ├── update/UpdateCenter.kt   更新流程控制器（状态机 + 下载 + 安装）
    ├── update/UpdateDialog.kt   更新弹窗（检查中 / 新版本 / 下载进度 / 失败重试）
    └── home / category / live / search / library / detail / player / settings
```

## 三端布局差异

| 形态 | 导航 | 网格列数 | 焦点模型 |
|---|---|---|---|
| 手机 | 底部 5 栏 | 3 | 触摸 |
| 平板（最短边 ≥ 600dp） | 左侧 200dp 侧边栏 | 5 | 触摸 |
| 电视 | 左侧 96dp 图标轨道 | 6 | 遥控器方向键 + OK |

电视判定：`UiModeManager == UI_MODE_TYPE_TELEVISION` 或声明 `FEATURE_LEANBACK` / `FEATURE_TELEVISION`。**不靠屏幕尺寸猜**——Android TV 常见 960×540dp，与横屏手机尺寸一致。

设置页的「强制电视模式」可在手机上预览遥控器布局。

## 订阅格式支持

| 形态 | 支持 | 说明 |
|---|---|---|
| 标准 TVBox JSON（sites / lives / parses） | ✅ | `type: 0`（XML）、`type: 1`（JSON）与 `type: 4`（远程 Spider 接口）可用 |
| 多仓订阅（urls） | ✅ | 添加时弹窗选子仓库 |
| 裸地址（直接返回 CMS 接口 URL） | ✅ | 生成单一源站 |
| `type: 3` Spider（JAR / JS / Python） | ❌ | 暂未接入对应运行时 |
| `parses` 解析播放 | ❌ | 只播直链（m3u8 / mp4 / flv / mkv / ts） |
| 直播 m3u（#EXTM3U） | ✅ | 带台标、分组、catchup |
| 直播 txt（`频道名,url` + `#genre#`） | ✅ | TVBox 传统格式 |

直播解析会自动丢弃 `rtp://` `udp://` `rtsp://` 组播地址——这些是运营商内网地址，公网设备无法播放。

## 构建

```bash
# 需要设置 ANDROID_HOME
export ANDROID_HOME=/Users/fei.yang/tools/android-sdk

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:installDebug   # 安装到已连接设备
```

## 当前状态

已完成：三形态导航、首页、分类、详情、播放器、直播、搜索、收藏历史、订阅管理。
已接入 DLNA 投屏：播放时点击「投屏」，选择同一局域网内已开启 DLNA 的电视或盒子；可在手机上暂停、继续、快进、快退或结束投屏。电视直接请求播放地址，依赖特殊请求头的视频、电视不支持的格式可能无法播放。
已接入检查更新：启动静默检查 + 设置页手动检查，发现新版本弹窗提示，下载后拉起系统安装器。版本信息与 APK 托管在 Cloudflare R2（dl.bsdnme.me）。

未接入：本地 Spider 运行时、网页解析播放、EPG 节目单、跨源搜索。

## 检查更新与发版

### 机制

- 版本信息：`https://dl.bsdnme.me/feige-yingshicang/update.json`
- APK 本体：`https://dl.bsdnme.me/feige-yingshicang/feige-yingshicang-<version>.apk`
- 托管：Cloudflare R2 共享桶 `dl-apps`（绑定域 dl.bsdnme.me），**所有 App 共用一个桶一个域名，按 App 全拼目录区分**：
  - `dl.bsdnme.me/<app全拼>/update.json`
  - `dl.bsdnme.me/<app全拼>/<app全拼>-<版本>.apk`
  - 新 App 接入：R2 无需任何配置，客户端指向 `<app全拼>/update.json` 即可
- 比较依据：`versionCode`（Int），`versionName` 仅展示
- 强制更新：`force: true` 或已安装 `versionCode < minVersionCode`，弹窗不可关闭
- 启动静默检查失败不打扰；debug 包也会检查（可自行加过滤）

update.json 结构：

```json
{
  "versionCode": 3,
  "versionName": "1.0.2",
  "minVersionCode": 1,
  "apkUrl": "https://dl.bsdnme.me/feige-yingshicang/feige-yingshicang-1.0.2.apk",
  "changelog": "修复xxx；新增xxx",
  "force": false
}
```

### 发版步骤

1. 改 `app/build.gradle.kts` 的 `versionName`（`versionCode` 可不手动改：脚本发现它与上次提交一样时会询问是否自动 +1）
2. 跑发布脚本（版本号自动读取，只传更新说明）：

```bash
./scripts/release.sh "修复xxx；新增xxx"
# 可选第2参数：minVersionCode（默认 1），低于它的已装版本会强制升级
./scripts/release.sh "重要安全更新" 2
```

脚本做五件事：读取版本号（未高于线上已发布 versionCode 时自动 +1 或取消）→ `assembleRelease` 签名构建 → 生成 update.json → 上传 R2 → **自动清理历史 APK**。默认保留线上当前版本及最近一个旧版；`KEEP_APK_COUNT` 可调整保留总数（至少 2）。清理时会检查线上清单、Cloudflare 返回和删除结果；清理失败会明确报错，上传成功的版本不受影响。

> 认证优先级：环境变量 `CLOUDFLARE_API_TOKEN` → `local.properties` 的 `cloudflare.apiToken`（R2 专用 Token，推荐）→ wrangler 全局登录凭据（兜底）。注意不要把 R2 专用 Token export 进 `~/.zshrc`，否则所有 wrangler 命令（Workers / Pages 部署）都会改用这个受限 Token。

### 签名

- 密钥：`keystore/feige-release.jks`（不进 git），alias `feige`
- 密码等配置在 `local.properties`（不进 git），`app/build.gradle.kts` 从中读取
- **密钥丢失后无法给已安装用户发增量更新，务必备份 keystore + 密码**
