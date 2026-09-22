# 第三方组件与许可

## 参考的项目

### KardLeaf / 卡叶笔记（waikr/KardLeaf，Apache-2.0）

本项目**参考**了它的实现思路与做法（用户 2026-09-22 指定参考）。按 Apache-2.0 的要求在此声明来源：

- 上游：https://github.com/waikr/KardLeaf ，许可 Apache-2.0（仓库内 `LICENSE`）
- 参考的方面与出处（抄行为与判据，代码按本项目的 WebView 架构重写，不整段复制其 Compose/Room 代码）：
  - Markdown 渲染层：`app/src/main/assets/preview/preview.html`、`markdown-it*.js`、`preview-html-sanitizer.js`
  - 同步语义（基线 + 冲突三选）：`app/src/main/java/com/kangle/kardleaf/data/sync/S3SyncPlanner.kt`、`S3SyncStateStore.kt`
  - 外链新建笔记：`AndroidManifest.xml` 的 `kardleaf://` 深链、`ui/WebClipImportDialog.kt`
- 改动说明：凡直接引用其代码或规则的处，在本项目对应文件里以注释标注
  `// 参考 waikr/KardLeaf (Apache-2.0) <文件>`；对照表见 `docs/参考与改进计划-KardLeaf.md`
- 若后续整段复制它的某个源文件，会在该文件头保留原始版权与许可声明并注明 modified

## 直接内置的第三方库

### markdown-it（MIT，计划第 4 轮引入）

- **用途**：替换自写的 markdown 渲染器（表格、嵌套列表、任务清单等）
- 计划一同引入：`markdown-it-texmath`（MIT）、`markdown-it-task-lists`（MIT）
- 引入时照 KaTeX 的做法：离线内置到 `assets/ui/vendor/`，校验 npm 声明的 integrity，来源与哈希记进 `evidence/`

### KaTeX 0.18.7（MIT）

- **用途**：正文里的数学公式渲染，**内置在应用内（离线，不联网）**
- **位置**：`source/mark-readnotes/app/src/main/assets/ui/vendor/katex/`
  （`katex.min.js`、裁剪过的 `katex.min.css`、20 个 `.woff2` 字体；压缩后入包约 555KB）
- **获取方式**：从 npm 镜像拉 tarball，并**校验过 npm 声明的 `dist.integrity`（sha512）一致**：
  `https://registry.npmmirror.com/katex/-/katex-0.18.7.tgz`
  tarball sha256 `9a80a3fba2367e99bf67b52bfff52e9534c8c7f198e4eaa12699e4f1df9a0bda`
- **裁剪说明**：CSS 里只保留 `woff2` 字体回退（去掉 woff/ttf，安卓 WebView 都支持 woff2），代码未改
- **许可全文**：`evidence/katex-LICENSE-MIT.txt`；来源与校验记录：`evidence/katex-provenance.txt`

## AndroidX WebKit 1.11.0（Apache-2.0）

- **用途**：`WebViewCompat.addWebMessageListener`（按 origin 开放的 JS↔原生通道）
- 由 Gradle 从阿里云镜像解析，未改代码

## 其它

- 前端（`assets/ui/app.js`、`app.css`、`index.html`）与 markdown 渲染器均为本项目自己写的，无第三方依赖
- 构建工具链（Gradle / AGP / Kotlin 编译器）不随产物分发
