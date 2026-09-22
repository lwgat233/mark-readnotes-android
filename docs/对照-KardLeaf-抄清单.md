# 抄清单：KardLeaf（waikr/KardLeaf，Apache-2.0）功能对照与移植计划

用户 2026-09-22：**「他几乎所有的功能都可以抄下来，但是你要写是参考了哪个的」**、「还有那么多功能，我不说了让你去抄吗」。
这份表就是**逐条抄清单**：左边是他有什么，右边是我们抄到哪一步、出处文件、以及不做的话为什么不做。
（合规：代码里留 `参考 waikr/KardLeaf (Apache-2.0) <文件>` 注释；`THIRD-PARTY-NOTICES.md` 已声明来源。）

状态口径：`已抄` = 我们这边有对应实现且分板块验收覆盖；`半抄` = 有但缺关键部分；`没抄` = 还没有。

## A. 界面与导航

| 他的功能 | 出处 | 我们 | 说明 |
|---|---|---|---|
| 仪表盘（Dashboard：文件夹条 + 笔记网格/列表 + 排序 + 选择工具栏） | `ui/DashboardScreen.kt`、`ui/DashboardSelectionTopAppBar.kt`、`repo/prefs/DashboardPreferences.kt` | 半抄 | 我们主页只有"最近编辑的块"列表；缺 文件夹条、排序、多选批量操作 |
| 左侧栏/抽屉（导航入口） | `ui/DashboardScreen.kt`（drawer） | **不做** | 用户 2026-09-22 拍板「不做」：主页布局不动（曾提出把导出/设置/同步收进侧栏，随后否掉） |
| 搜索（带命中高亮、匹配条目） | `ui/SearchBar`、`model/NoteSearchMatch.kt` | **已抄** | 第 9 轮做完：`idx.search{q,limit}`，界面在主页顶部，命中处 `<mark>` 高亮（判据 `notes.sh#search`） |
| 标签管理（重命名/合并/删除） | `ui/TagManagementScreen.kt`、`database/LabelDao.kt`、`LabelEntity.kt` | 没抄 | 我们的标签现在只能靠改块里的标签行 |
| 文件夹管理（新建/重命名/移动，带事务） | `ui/FolderManagementScreen.kt`、`data/database` 迁移与事务测试 | 半抄 | 我们能在设置里建文件夹 + 标签→文件夹；缺 重命名/删除 |
| 笔记信息与备注 | `ui/NoteInfoDialog.kt`、`NoteRemarkDao/Entity` | 没抄 | 备注/高亮类 |
| 自定义排序 | `ui/CustomSortDialog.kt` | 没抄 | 主页排序 |
| 设置页（一大堆开关） | `ui/KardLeafSettingsScreen.kt`、`repo/PrefsManager.kt` | 半抄 | 我们的设置只有目录/计数/文件夹/WebDAV |
| 自定义工具栏项（编辑器工具条可配） | `ui/CustomToolbarItemsScreen.kt` | 没抄 | 依赖编辑器先到位 |
| 入门引导 | `ui/OnboardingDialog.kt` | 不做 | 我们的首页空态文案已经够（不写说明书是他的规矩） |

## B. 编辑器

| 他的功能 | 出处 | 我们 | 说明 |
|---|---|---|---|
| CodeMirror 编辑器（行号/高亮/选择工具条） | `assets/codemirror-editor/`、`ui/editor/codemirror/`、`codemirror-editor/`（含 NOTICE） | 没抄 | 我们现在是 `<textarea>`；这是他最重的一块，要单独一轮 |
| 富文本编辑（Quillpad） | `ui/quillpad/KardLeafQuillpadEditor` | 不做 | 我们是"md 是权威源"，富文本会破坏纯文本可编辑性 |
| 插入图片/表格/超链接（对话框） | `ui/InsertImageDialog.kt`、`InsertTableDialog.kt`、`InsertHyperlinkDialog.kt` | 没抄 | 至少"插入图片"要有（用户早就点过名 R4） |
| 编辑器偏好（字号/换行/预览开关等） | `repo/prefs/EditorPreferences.kt` | 没抄 | |
| 图片查看器 | `ui/ImageViewerScreen.kt`、`data/task/...ImageConverter` | 没抄 | 点图放大/保存 |
| 笔记图片列表 | `ui/NoteImagesScreen.kt` | 没抄 | 列出这篇引用的图 |
| 手绘板 | `ui/DrawingPadScreen.kt` | 不做（可后置） | 与"阅读随笔"关系不大 |
| 思维导图 | `ui/MarkdownMindMapScreen.kt` | 不做（可后置） | 同上 |
| 录音/附件 | `ui/RecordAudioDialog.kt`、`EditAttachmentDialog.kt` | 不做（可后置） | |

## C. 笔记组织与关联

| 他的功能 | 出处 | 我们 | 说明 |
|---|---|---|---|
| 双链 `[[wiki]]` + 关系图 | `database/NoteLinkDao/Entity`、`ui/RelationshipGraphScreen.kt`、`ui/WikilinkPromptDialog.kt` | 没抄 | 随笔里"论文/网页之间互指"很有用，值得抄 |
| 笔记历史（自动快照 + 清理预览） | `database/NoteHistory*`、`model/HistoryCleanupPreview.kt`、`repo/note/NoteHistoryStore.kt` | 没抄 | 改坏了能回退 |
| 内容缓存 / 元数据 | `repo/note/NoteContentCache.kt`、`MetadataManager.kt` | 半抄 | 我们有 SQLite 索引（同类作用） |
| 隐私笔记（加密仓库） | `database/PrivacyNote*`、`repo/note/PrivacyVaultCrypto.kt`、`ui/PasswordLockCardScreen.kt`、`PrivacyScreen.kt` | 没抄 | 加密笔记 + 密码锁；用户提过"加密"（缺口清单里也记了） |
| Web 剪藏导入 | `ui/WebClipImportDialog.kt`、`AndroidManifest` 深链 | 没抄 | 与"随笔"主题最贴：从浏览器分享进来直接成块 |
| 待办 + 提醒（含开机重建/闹钟接收器） | `database/TaskDao/Entity`、`data/receiver/Task*Receiver.kt`、`ui/TaskListScreen.kt`、`TaskEditorDialog.kt` | 没抄 | 任务清单是完整子功能 |
| AI 助手 | `data/ai/KardLeafAiAssistant.kt` | 没抄 | 依赖用户自己的 API Key，要单独定 |

## D. 数据与同步

| 他的功能 | 出处 | 我们 | 说明 |
|---|---|---|---|
| WebDAV 同步（基线 + 冲突三选） | `data/sync/WebDavCloudSyncManager.kt` | **已抄** | 第 8 轮做完，判据 S1–S8（`docs/同步规格.md`） |
| S3 同步 | `data/sync/S3SyncPlanner.kt`、`S3SyncStateStore.kt` | 不做（说清） | 用户机器上没有 S3；要加也能加，先不做 |
| Syncthing（文件夹监视同步） | `repo/VaultChangeObserver`、同步管理器里的 Syncthing 分支 | 不做（说清） | 他是"应用自己管卷"，我们走 SAF + WebDAV，两套模型 |
| 备份（应用内备份 + 外部备份记录） | `repo/note/NoteBackupManager.kt`、`NoteRecordExternalBackup.kt` | 半抄 | 我们的"按标签导出"是第一版；缺备份清单/一键全量备份 |
| 迁移（DB 版本迁移，带测试） | `database/Migrations.kt`、`androidTest/.../AppDatabaseMigrationTest.kt` | 半抄 | 我们有 v1→v3 的迁移，但**没有迁移测试** |

## E. 应用外壳

| 他的功能 | 出处 | 我们 | 说明 |
|---|---|---|---|
| 换图标 | `AppIconManager.kt` | 不做 | 与笔记无关 |
| 多语言 | `AppLocaleManager.kt` | 不做 | 只给一个人用 |
| 检查更新 | `AppUpdateChecker.kt` | 不做 | 我们是从 GitHub 仓库自己发版 |

## 抄的顺序（我建议，你可以改）

1. **左侧栏（功能入口）** + **渲染样式重做**（用户刚提，正在做）
2. 编辑器工具栏：**插入图片/表格/超链接** + **图片查看器**（依赖最小、立刻有用）
3. **搜索**（带高亮）
4. **标签管理**（重命名/合并/删除）+ 主页**排序**
5. **笔记历史**（快照 + 回退 + 清理预览）
6. **Web 剪藏导入**（浏览器分享进来直接成块 —— 最贴"阅读随笔"）
7. **双链 + 关系图**、**思维导图**
8. **待办与提醒**
9. **加密笔记（Vault + 密码锁）**
10. **备份增强**（一键全量 + 外部备份记录）、**迁移测试**
11. CodeMirror 编辑器（最重，放最后单独一轮）
