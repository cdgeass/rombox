# RomBox

Android 上的 ROM 库管理 + 无线传输工具，两件事：
**扫描解析卡带头部、规范命名、按内容去重、zip 自动解包**，以及把「往掌机里塞 ROM」变成
**手机开个网页、电脑浏览器拖文件**。

当前只实现了 **Game Boy Advance**，但平台解析是插件式的，加新平台不用动 UI 和传输层。

## 功能

- **ROM 库**：扫描指定目录，读 GBA 卡带头部（标题、游戏码、厂商、版本、区域、校验），算 CRC32，列表展示 + 搜索 + 排序
- **规范命名**：`游戏名 (区域).gba`，剔除 `(USA)`/`[九柳]`/`(64Mb)` 等标签、中文名优先、非法字符过滤、重名加序号，支持单个与整目录（两阶段改名，不会互相覆盖）
- **去重**：按 `(大小, CRC32)` 分组，每组保留一份，其余一键删除
- **无线传输**：内置 HTTP 服务（默认 8080），电脑浏览器访问 `http://<手机IP>:8080` 即可拖拽上传、删除、改名、下载；上传 `.zip` 会自动解出包里所有 ROM；文件名支持 UTF-8 / GBK（Windows curl 常见）
- **可指定 ROM 目录**：内置目录选择器，默认挑 `PocketGames/roms/gba`（掌机常见布局），也可切到 SD 卡任意目录
- **前台服务**：切到后台、锁屏后传输服务继续跑，通知栏显示访问地址

## 快速开始

```bash
# 1. 构建（需要 JDK 17 + Android SDK 34）
./gradlew assembleDebug

# 2. 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. 授权「所有文件访问权限」（Android 11+ 直接读写 /sdcard 必需）
adb shell appops set com.cdgeass.rombox MANAGE_EXTERNAL_STORAGE allow
#    也可以在 App 内「设置 → 授予所有文件访问权限」走系统设置页

# 4. 打开 App，进「无线传输」开启服务，电脑浏览器访问提示的地址
```

## 三个页面

| 页面 | 内容 |
|------|------|
| ROM 库 | 目录路径、搜索、扫描、右上角菜单（全部规范命名 / 整理重复 / 排序）；点条目看信息和单条操作 |
| 无线传输 | 开关键、端口、访问地址（可复制）、已接收统计、运行日志 |
| 设置 | ROM 目录选择/恢复默认、端口、启动时自动开启、权限状态、关于 |

## HTTP 接口

网页用的就是这些接口，也可以脚本直接调：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 网页（上传 + 列表 + 删除/改名） |
| GET | `/api/info` | 目录、剩余空间、运行状态、已接收统计 |
| GET | `/api/roms` | ROM 列表 JSON |
| GET | `/file?name=` | 下载单个 ROM |
| POST | `/upload` | `multipart/form-data`，字段名随意，按文件名判断类型；`.zip` 自动解包 |
| POST | `/delete` | 表单 `name=`，删除文件 |
| POST | `/rename` | 表单 `name=`，按规范名重命名 |
| POST | `/rename-all` | 整目录规范命名，返回改动数量 |

请求带 `X-Requested-With: fetch` 头时返回 JSON，否则回 303 跳回首页（普通表单提交也能用）。

## 架构

```
app/src/main/java/com/cdgeass/rombox/
├── RomBoxApp.kt              Application + AppContainer（手写依赖容器）
├── core/
│   ├── model/RomPlatform.kt  平台接口 + RomHeader 数据模型   ← 扩展点 1
│   ├── platform/GbaPlatform.kt  GBA 头部解析（0xA0 标题 / 0xAC 游戏码 / 0xB2 / 0xBD 补码）
│   ├── platform/Platforms.kt    注册表                        ← 扩展点 2
│   ├── rom/RomRepository.kt  扫描、CRC32、去重、改名、删除
│   ├── rom/Naming.kt         规范命名规则
│   ├── rom/ZipImporter.kt    zip 内 ROM 解包
│   ├── settings/AppSettings.kt  SharedPreferences 配置
│   └── util/                 IPv4、容量格式化、存储权限判断
├── transfer/
│   ├── RomHttpServer.kt      极简 HTTP 服务（ServerSocket + 线程池，无第三方依赖）
│   ├── MultipartStream.kt    流式 multipart 解析（KMP 匹配分隔符，不整包进内存）
│   ├── Page.kt               内联 CSS/JS 的单页网页
│   ├── TransferService.kt    前台服务（保证后台存活）
│   └── NotificationHelper.kt
└── ui/
    ├── MainActivity.kt       BottomNavigation + hide/show 保状态
    ├── roms/                 RomsFragment / RomsViewModel / RomAdapter
    ├── transfer/             TransferFragment / TransferViewModel
    ├── settings/             SettingsFragment
    └── common/DirPicker.kt   目录选择对话框
```

### 加一个平台要做什么

1. 新建 `XxxPlatform : RomPlatform`，实现 `id`、`displayName`、`extensions`、`headerReadSize`、`parseHeader`
2. 在 `Platforms` 的列表里加一项

之后扫描、上传校验、规范命名、列表筛选都会自动带上新扩展名。区域/标题的展示规则沿用 `RomHeader`，
命名复用 `Naming.canonicalFileName()`。

## 行为说明

| 场景 | 行为 |
|------|------|
| 进「ROM 库」 | 自动扫描目录（CRC32 在线程池算，进度条显示） |
| 菜单「全部改为规范名称」 | 确认后两阶段改名（先临时名再落地，互换/链式改名不丢文件） |
| 菜单「整理重复 ROM」 | 按大小 + CRC32 分组，每组保留一份，其余删除 |
| 上传 `.zip` | 自动解出内部 ROM（流式 + 只取一层，忽略 readme 等） |
| 索引 | 不落盘，内存态；每次进页面 / 传输后重扫（几百个文件毫秒级） |

## 测试

```bash
# 单元测试（multipart 解析）
./gradlew testDebugUnitTest

# 端到端：手机开服务后，在电脑上跑（改一下 HOST）
python tools/e2e_test.py
```

## 已知限制

- 只支持 `.gba` / `.agb`；`.7z` / `.rar` 需要第三方库，暂不支持
- 存储用的是绝对路径 + 「所有文件访问权限」，没有走 SAF（Storage Access Framework）。要上架 Google Play 需换成 SAF
- 传输是明文 HTTP，仅建议在自家局域网使用；没有鉴权，同一网段的人都能访问
- 单文件上传没有大小上限校验（GBA ROM 一般 ≤32 MB，问题不大；以后支持大平台要加限制）
