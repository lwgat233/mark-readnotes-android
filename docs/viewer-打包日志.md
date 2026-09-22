# viewer 打包日志（缺什么、没验什么，都记在这里）

用户 2026-09-22 定的规矩：**viewer 那边只管「渲染 + 打包」两步，缺什么功能一律写到这个项目的日志里**。
（viewer 的源码与说明在 `/vol1/1000/aicache/viewer/`，指导文件 `aicache/viewer/docs/指导.md`。）

## 交付物

| 项 | 位置 | 大小 / 哈希 |
|---|---|---|
| viewer APK（debug，可直接装） | `apk/viewer/viewer-debug-20260922.apk` | 1,686,772 字节<br>sha256 `b8c5feb7aceb88496e11361cf54cd93a9f0a841b8b2f49b1d7d872dc06493b0e` |
| 包名 / 版本 | `dev.viewer`，versionName 0.1.0，页内标记 `0.1.0+v1`，原生日志标记 `viewer-0.1.0+apk1` | — |

**它是干什么的**：把本项目的「功能分板块」渲染到手机上看 —— 左边 7 行板块（随笔/渲染/存储/导出/设置/同步 ＋ 总纲），
右边板块页（功能表：功能 ↔ 入口 ↔ 稳定标识 ↔ 判据 ＋ 相关文档）或文档正文（markdown 真渲染：表格、任务清单、公式、代码）。
**数据不在包里**：装好后用系统目录选择器指定本项目的目录（手机上放一份 docs/ 与 source/.../registry.js 即可），
功能与板块读 `assets/ui/registry.js`，文档读 `docs/*.md`，都是现读 —— 所以手机上看到的永远是最新那一版。

复现这一版（工具链与 mark-readnotes 相同：JDK 17 + Gradle 8.7 + Android SDK）：

```
cd /vol1/1000/aicache/viewer/source/android
export JAVA_HOME=/home/lwgat/tools/jdk-17.0.2 ANDROID_HOME=/home/lwgat/tools/android-sdk
unset ANDROID_SDK_ROOT
/home/lwgat/tools/gradle-8.7/bin/gradle assembleDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```

## 已经验过的（设备端真实路径：Android 14 x86_64 模拟器 emulator-5554）

- 装上后首次打开：空态一句「选 mark-readnotes 项目所在的文件夹」＋ 一个「选择目录」键 → 真手指点开系统目录选择器
- 系统选择器里真手指走完：`Download` → `mark-readnotes` → `USE THIS FOLDER` → `ALLOW`（只读授权）
- 授权后当场变成：`源：mark-readnotes（点这里换）｜ 板块 6 ＋ 总纲 1 ｜ 功能 30 ｜ 文档 22`，
  7 行板块、功能表 7 行、相关文档 4 份（读回来的 DOM）
- 真手指点搜索结果里的功能行 → 跳到该板块页；搜索框真输入「同步」→ `功能 2 ｜ 文档 10`
- 真手指点文档行 → 打开 `docs/功能调用与意义.md`：**8 张表格、11 个标题**（markdown 真渲染出来了）
- 点功能表里的判据芯片 → 打开 `tools/boards/notes.sh`（按代码显示），面包屑能回板块
- 设备上装的这份 apk 与归档的 `apk/viewer/viewer-debug-20260922.apk` 哈希一致（`b8c5feb7…`）

## 缺什么 / 没验什么（这就是「开发不足」清单）

| # | 项 | 说明 |
|---|---|---|
| V-1 | **只能看，不能改** | viewer 是只读的：不改本项目的任何文件（有意为之）。要「在手机上改文档」不在这一版范围 |
| V-2 | **文档改动要重进才刷新** | 源目录里的 md 是每次请求现读的，但页面不会自己重载；换目录或重开应用才刷新（没有下拉刷新/自动重载） |
| V-3 | **不能搜索“源代码/证据”** | 只搜 `docs/*.md` 与登记表里的功能；`source/`、`evidence/`、`tools/` 里的内容搜不到（判据脚本能点开看，但搜不到） |
| V-4 | **文档里的路径点不动** | 本项目文档用反引号写路径（如 `docs/数据模型.md`），不是 markdown 链接；viewer 只对真链接做页内跳转 |
| V-5 | **没有文档内锚点/命中高亮** | 搜索命中行点进去只打开整篇，不滚到那一行；关键字也没标黄 |
| V-6 | **窄屏没细调** | 手机竖屏能用（本次验收就是 1080×2400），横屏与超长文档没逐个看 |
| V-7 | **没测真机** | 结论全部来自 Android 14 x86_64 模拟器；真机（外观/字号/权限弹窗形态）未测 |
| V-8 | **没测大目录** | 只对 22 份文档的项目量过；几百份文档时 `view.index` 每次都现读全部 md，可能慢（没有缓存/分页） |
| V-9 | **授权只读，未测“拒绝授权/换目录”** | 「点状态行换目录」这条只在代码里通了，没在设备上真点过（本次只验了首次授权） |
| V-10 | **没有深色/浅色跟随系统** | 固定深色 |

## 与本项目的关系（不干扰本项目）

- 只读本项目，不改一个字节（viewer 侧验收有「源目录 mtime/size 快照比对」这一条）。
- 本项目 `docs/` 里新增的这份文件与 `apk/viewer/` 是 viewer 的产物，**没有改动本项目的功能与代码**；
  本项目的 git 里还有上一轮未提交的改动，我**没有提交**这里的任何文件，要不要入库由你定。
