# 参考与改进计划：照着 KardLeaf（waikr/KardLeaf）补功能

用户 2026-09-22 指示：KardLeaf 是「正常能上的项目」，**功能都可以照着抄，但必须写明参考了哪个**；
代码要用**我们的 WebView 架构重写**（不是移植它的 Compose/Room 结构）；**第一优先是把渲染做好**。

## 0. 合规（先定规矩，再抄）

| 项 | 结论 |
|---|---|
| KardLeaf 许可 | **Apache-2.0**（仓库内 `LICENSE`）→ 允许参考、允许移植代码 |
| 我们的义务 | ① 在 `THIRD-PARTY-NOTICES.md` 写明：参考/移植自 `waikr/KardLeaf`、许可 Apache-2.0、**改了哪些文件**；② 若整段复制其源码文件，在文件头保留版权与许可声明并标注「modified」 |
| 前端三方库 | 只引 **MIT/同类宽松**许可：markdown-it、markdown-it-task-lists、markdown-it-texmath（MIT）、KaTeX（MIT）；逐条记来源与哈希校验（照 KaTeX 那次的做法） |
| 不碰的东西 | 若其 vendored 目录带有传染性许可（如 AGPL）的文件，**不读不抄**；真要参考先单独核许可再决定 |

## 1. 它的能力清单 → 我们怎么重写 → 属于哪一轮

参考出处一律写成 `路径:文件`，抄的时候在代码注释里也留一行 `// 参考 waikr/KardLeaf (Apache-2.0) <文件>`。

| 能力 | 它的实现（参考出处） | 我们的做法（WebView 架构） | 轮次 |
|---|---|---|---|
| **Markdown 渲染** | `app/src/main/assets/preview/markdown-it.min.js` + `preview.html`（63KB，含渲染规则与滚动锚点） | 内置 markdown-it + 插件重写渲染层（替换我手写的那套），判据是渲染后的真实 DOM | **第 4 轮（重点）** |
| HTML 消毒 | `app/src/main/assets/preview/preview-html-sanitizer.js`（重写 markdown-it 的 html_block/html_inline，白名单属性；只放行 `#hex` 颜色与两种字号） | 沿用我们的 DOM 消毒思路，但补白名单：保留允许的标签/属性，去掉事件与危险协议 | 第 4 轮 |
| 任务清单 | `preview/markdown-it-task-lists.min.js` | 同款插件（MIT），并可点选切换（回写 md） | 第 5 轮 |
| 数学公式 | `preview/markdown-it-texmath.min.js` + `katex.min.js` | 已内置 KaTeX；改成走 texmath 插件，语法更全（`$…$`/`$$…$$`/`\(…\)`） | 第 4 轮 |
| 图片/媒体 | `preview/preview-media.js` | 已有本地/网络图片，补齐：点击放大、相对路径、附件类型 | 第 5 轮 |
| 编辑↔预览定位 | `preview.html` 的 `getMarkdownOffsetAtPoint` / `getMarkdownViewportAnchor` | 预览点一下就跳到源码对应位置（我们白板是「编辑/预览」切换，需要记录锚点） | 第 6 轮 |
| 外部 URL 新建笔记 | 清单里 `scheme="kardleaf"` + `VIEW/BROWSABLE`；`ui/WebClipImportDialog.kt` | 深链 `markreadnotes://new?title=…&body=…&url=…` + 系统分享 `text/*` 收进来（“网页随笔”的入口） | 第 5 轮 |
| **网页正文提取** | 依赖 `readability4j` + `flexmark-html2md`，`ui/WebClipImportDialog.kt` | 在 WebView 里抓正文（我们本来就有 WebView），转 markdown 建块 | 第 6 轮 |
| 文件夹/多级目录 | `ui/...`、`data/repository/`（目录树 + 分类） | **随笔独立目录 + 目录内建文件夹**（用户本轮点名） | 第 5 轮 |
| 标签管理 | `ui/TagManagementScreen.kt` | 标签筛选/改名/合并（顺带解决“改标签＝搬家”的批量场景） | 第 6 轮 |
| 全文/正则搜索 | `ui/SearchBar.kt` + `data/database` | 块内搜索（我们索引里已有 body，可直接查） | 第 6 轮 |
| 双向链接/关系图 | `ui/RelationGraphScreen.kt`、`ui/WikilinkPromptDialog.kt` | wikilink `[[…]]` 解析 + 关系图（先做解析与跳转，图后面） | 第 7 轮 |
| 思维导图 | `ui/MarkdownMindMapScreen.kt` | 从标题层级生成（块模型天然有层级） | 第 7 轮 |
| 任务与提醒 | `data/task/`（12 个文件）、`TaskReminderUi.kt` | 任务的 markdown 化 + 提醒（Android 通知） | 第 7 轮 |
| 历史版本/恢复 | 依赖快照式备份 | md 快照目录 + 界面回滚 | 第 7 轮 |
| 加密隐私仓库 | `.KardLeaf/Vault/`、`ui/PasswordLockCardScreen.kt` | 笔记根里一个加密子目录（密钥走 Android Keystore，密文不进 SQLite 明文索引） | 第 8 轮 |
| 绘图 | `ui/NoteImagesScreen.kt` + 绘图面板 | 手写笔迹存 png + 引用进块 | 第 8 轮 |
| 桌面小部件/速记 | 3 个 AppWidget + QS Tile | 「随手记」小部件（直达新建块） | 第 8 轮 |
| AI 摘要/润色 | `data/ai/` + 自定义接口 | 走 op 层给外部接口（用户需求里的“ai 接口”板块） | 第 8 轮 |
| 每日笔记 | 日期命名 + 模板 | 按日期建块（块模型里就是一条随笔） | 第 8 轮 |
| **同步（WebDAV/S3/Syncthing）** | `data/sync/WebDavCloudSyncManager.kt`(39KB)、`S3SyncPlanner.kt`、`S3SyncStateStore.kt` | 自写 WebDAV 客户端（OkHttp）+ **借它的「基线 + 冲突三选」语义** | 第 6 轮 |
| 备份/更新检查 | `AppUpdateChecker.kt` | 更新检查走 GitHub Release（可选，后期） | 第 8 轮 |

## 2. 我们要做的“改进”（不是纯抄）

用户明确：**重点是随笔方面的改进**。

1. **随笔独立目录**（见 `docs/新需求规格-随笔独立目录.md`）：笔记根下单独一层 `随笔/`，里面可再建文件夹，
   与以后别的功能板块隔离；块归属仍由**第一个标签**决定（标签→文件不变），文件夹是**人给的位置**。
2. **渲染质量**（见 `docs/渲染规格.md`）：手写渲染器只算能用，按 markdown-it + 插件重写，判据是 DOM。
3. **块模型是它没有的**：我们是「一级标题分块、标签决定归属」，它是一文件一笔记 → 抄功能时必须重新落成块模型
   （例如它的“文件夹分类”对我们就是“文件夹 + 标签”双轨）。
4. **懒扫描/永久索引**保留我们的做法（它没有“不扫目录也能用”的约束）。

## 3. 抄的规矩（写给自己看，免得忘）

- 抄功能＝抄**行为与判据**，不是抄代码结构；我们的分层是「能力层 → op 层 → 渲染器层 → 界面层」。
- 每个抄来的功能，在 `docs/功能规格.md` 里必须补一行：**归属板块 / 载体 / 按键 / 判据**，
  并在 `THIRD-PARTY-NOTICES.md` 里补一行参考出处。
- 一轮只落一个功能点，判据读回来（DOM / 文件字节 / SQLite 行 / 系统窗口焦点）。
