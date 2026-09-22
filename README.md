# mark-readnotes.android（随笔）

看网页、视频、论文时的**阅读随笔** App。安卓端跑在 WebView 里（前端是本地 assets），
原生层负责文件与索引。目前只有「随笔」一个板块，为以后加别的东西留了位置。

- 包名 / applicationId：`dev.markreadnotes`　版本：`0.1.0`
- 当前构件：`apk/mark-readnotes-debug-20260922-r2.apk`（页内标记 `0.1.0+r4`）
  sha256 `e2a355781fd6de6aedcee9673d76f8269958602cbca9f1236f10360a340d46c9`
  上一版：`apk/mark-readnotes-debug-20260922.apk`（`0.1.0+r1`，第 1 轮闭环）
- 构建：JDK 17 + Gradle 8.7 + Android SDK（compileSdk 34 / minSdk 26 / targetSdk 34）

## 文档索引

| 想找什么 | 看哪份 |
|---|---|
| 这一轮在做什么、判据、下一步 | `docs/当前目标.md`（唯一权威，开工前先读） |
| 功能 ↔ 板块 ↔ 按键 ↔ 判据 | `docs/功能规格.md` |
| 块/文件怎么划、SQLite 表、懒扫描规则 | `docs/数据模型.md` |
| 第 1 轮怎么验的（授权→编辑→搬家→懒扫描） | `docs/验收清单-第1轮.md` |
| 第 2 轮怎么验的（公式/图片/消毒/层级） | `docs/验收清单-第2轮.md` |
| 发现的问题与需求登记 | `docs/问题与需求登记.md` |
| 还没做的（= 开发不足清单） | `docs/缺口清单.md` |
| 环境配方（工具链/模拟器/镜像源） | `env/环境配方.txt` |
| 第三方组件与许可 | `THIRD-PARTY-NOTICES.md` |

## 核心规则（一句话版）

- **一级标题分块**；标题后第一行是标签行（可写多个），**第一个标签决定这块进哪个 md 文件**；
  改那个标签 = 整块搬到另一个文件。
- **正文的权威源是磁盘上的 md 文件**，SQLite 只放索引与「最近编辑」顺序；写入永远先改文件再更新索引。
- **不全局扫目录**：冷启动只读 SQLite；只在首次授权、用户点刷新、打开某个块前 stat 它那一个文件时才碰存储。
  索引永久保留，离线文件也不删行。
- 笔记根 = 用户选的可见目录下的固定英文子目录 `mark-readnotes`（用户要求：目录不要用中文）。
- 公式用**内置 KaTeX**（离线）；图片：相对路径按笔记根用 SAF 取出、带协议的直链直接交给 WebView；
  放行的基本 HTML 会先剥掉脚本类标签与事件属性。

## 目录结构

```
apk/        交付构件（带日期）
docs/       规格、数据模型、当前目标、验收清单、问题登记、缺口
evidence/   实测证据（logcat、SQLite 导出、构件哈希、KaTeX 来源与校验、网络图访问日志）
source/mark-readnotes/   源码（Gradle 工程 + assets 前端 + 内置 KaTeX）
tools/      webview-cdp.mjs（读真实 DOM / 真手指点击）、netimg-server.py（网络图验收用）
env/        环境配方
```

## 构建与验证（复跑）

```
export JAVA_HOME=/home/lwgat/tools/jdk-17.0.2 ANDROID_HOME=/home/lwgat/tools/android-sdk
unset ANDROID_SDK_ROOT
cd source/mark-readnotes
/home/lwgat/tools/gradle-8.7/bin/gradle assembleDebug --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process
# 装包前先证明构件里有启动类（源文件被写空时构建也会“成功”）：
unzip -p app/build/outputs/apk/debug/app-debug.apk classes3.dex | strings | grep -c Ldev/markreadnotes/MainActivity;
```

验证步骤见 `docs/验收清单-第1轮.md`、`docs/验收清单-第2轮.md`（起模拟器 → 装包 → 启动 → CDP 读真实状态）。

## 复现校验（口径在问题登记 P8）

```
bash tools/verify-repro.sh          # 用 git 里的源码在干净目录重建，与 apk/ 最新构件比
```
实测：69 个条目逐条 CRC 一致（`内容不一致条目: 0`）→ 源码快照能重建这一版；
**整包 sha256 不同**（打包期 zip 对齐/extra 字段差异，差 ~48KB），所以整包哈希只用于标识“交付的那一份”。

## 缺口

导出、WebDAV、图片插入入口、标签筛选/搜索、外部控制口、列表懒加载都**还没做**，明细见 `docs/缺口清单.md`。
界面里的空态、按键都对应真实功能，没有装饰性控件。
