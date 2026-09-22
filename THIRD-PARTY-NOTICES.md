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

### markdown-it 14.1.0（MIT）· markdown-it-texmath 1.0.0（MIT）· markdown-it-task-lists 2.1.1（ISC）

- **用途**：渲染层的 markdown 引擎（表格、嵌套列表、任务清单）与公式分隔符解析
- **位置**：`source/mark-readnotes/app/src/main/assets/ui/vendor/{markdown-it,markdown-it-texmath,markdown-it-task-lists}/`
  （离线内置，不联网）
- **获取方式**：从 npm 镜像拉 tarball，并**校验过 npm 声明的 `dist.integrity`（sha512）三个包全部一致**：
  来源与 tarball sha256 见 `evidence/vendor-provenance.txt`，拉取脚本 `tools/fetch_vendor.py`
- **改动说明（与上游不同之处，均为本项目有意为之，代码里也写了注释）**：
  1) 不用 texmath 自带的 `<eq>/<section>` 模板，改由 KaTeX 直接产出公式 HTML（四条分隔符规则都重绑）；
  2) 未链接 texmath 的 CSS（`texmath.css` 随包保留，因为模板已被替换）；
  3) 消毒分成两层：`html_block` 走白名单，行内 HTML 交给「整段解析后整体消毒」
     （逐 token 消毒会把成对标签拆散，见 `docs/问题与需求登记.md` P15）
- **许可全文**：`evidence/vendor-LICENSES.txt`

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

## OkHttp 4.12.0（Apache-2.0）

- **用途**：WebDAV 同步的 HTTP 客户端。系统自带的 `HttpURLConnection` 不允许 `PROPFIND` 这类自定义方法
  （Android 的实现只放行 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE），KardLeaf 用的也是 OkHttp。
- 由 Gradle 从阿里云镜像解析，未改代码

## 验收用的工具（本项目自写，不随产物分发）

- `tools/webdav-mini.py`：只用标准库写的极简 WebDAV 服务（PROPFIND/GET/PUT/DELETE/MKCOL + Basic 认证），
  只为验收用；不含任何第三方代码。`tools/dav-selftest.py` 是它的自测。

## 其它

- 前端（`assets/ui/*.js`、`app.css`、`index.html`）、原生层与「块 → 标签 → 文件」的读写规则都是本项目自己写的；
  只有渲染引擎与公式渲染用上面列出的内置库（markdown-it 系 + KaTeX），全部离线、不联网
- 构建工具链（Gradle / AGP / Kotlin 编译器）不随产物分发
