/* 功能登记表（数据源）：板块与功能的唯一登记处。
   界面按它组织；tools/check_coverage.py 与 selfcheck.js 都读它来报缺口。
   规矩（见 skill feature-registry-ui-architecture）：
   - 一个功能只属于一个板块；跨板块只放跳转入口，不复制控件
   - planned 的功能不许出现在界面上（会变成点不动的空块）
   - status=done 的必须有 testId（界面里查得到）与 test（对应的验收脚本）
   - testId 必须是「应用静默时也在 DOM 里」的选择器（浮层里的键在没打开时不存在，那类写入口选择器） */
window.MRN_REGISTRY = {
  "boards": [
    { "id": "notes",    "name": "随笔", "order": 1, "carrier": "主页 + 全屏白板" },
    { "id": "render",   "name": "渲染", "order": 2, "carrier": "预览层（白板内）" },
    { "id": "store",    "name": "存储", "order": 3, "carrier": "设置小窗里的状态行" },
    { "id": "export",   "name": "导出", "order": 4, "carrier": "导出小窗" },
    { "id": "settings", "name": "设置", "order": 5, "carrier": "设置小窗" },
    { "id": "sync",     "name": "同步", "order": 6, "carrier": "（计划中）", "planned": true }
  ],
  "features": [
    { "id": "notes.home",     "board": "notes", "name": "主页列出最近编辑的块", "carrier": "主页列表", "entry": "打开应用", "testId": "#list", "status": "done", "test": "boards/notes.sh#home" },
    { "id": "notes.new",      "board": "notes", "name": "新建块", "carrier": "主页底部按键", "entry": "＋ 新随笔", "testId": "#btn-new", "status": "done", "test": "boards/notes.sh#new" },
    { "id": "notes.board",    "board": "notes", "name": "全屏白板编辑", "carrier": "全屏浮层", "entry": "点卡片", "testId": "#board", "status": "done", "test": "boards/notes.sh#board" },
    { "id": "notes.arch",     "board": "notes", "name": "归档区（标题+标签）与正文隔离", "carrier": "白板顶部", "entry": "白板", "testId": ".arch", "status": "done", "test": "boards/notes.sh#arch" },
    { "id": "notes.move",     "board": "notes", "name": "改第一个标签＝整块搬家", "carrier": "白板归档区输入", "entry": "白板标签框", "testId": "#ed-tags", "status": "done", "test": "boards/notes.sh#move" },
    { "id": "notes.delete",   "board": "notes", "name": "删除本块（两次确认）", "carrier": "白板 ⋯ 小窗", "entry": "白板 ⋯", "testId": "#btn-more", "status": "done", "test": "boards/notes.sh#delete" },
    { "id": "render.md",      "board": "render", "name": "markdown 渲染（标题/列表/引用/代码）", "carrier": "预览层", "entry": "白板 预览", "testId": "#btn-preview", "status": "done", "test": "boards/render.sh#md" },
    { "id": "render.tex",     "board": "render", "name": "数学公式（内置 KaTeX）", "carrier": "预览层", "entry": "白板 预览", "testId": "#btn-preview", "status": "done", "test": "boards/render.sh#tex" },
    { "id": "render.img",     "board": "render", "name": "图片（本地相对路径 + 直链）", "carrier": "预览层", "entry": "白板 预览", "testId": "#btn-preview", "status": "done", "test": "boards/render.sh#img" },
    { "id": "render.sanitize","board": "render", "name": "基本 HTML 白名单消毒", "carrier": "预览层", "entry": "白板 预览", "testId": "#btn-preview", "status": "done", "test": "boards/render.sh#sanitize" },
    { "id": "render.link",    "board": "render", "name": "链接跳转（交给系统浏览器）", "carrier": "预览层", "entry": "点链接", "testId": "#btn-preview", "status": "done", "test": "boards/render.sh#link" },
    { "id": "store.auth",     "board": "store", "name": "目录授权（SAF 持久授权）", "carrier": "设置小窗", "entry": "重新选择目录", "testId": "#btn-pick-empty", "status": "done", "test": "boards/store.sh#auth" },
    { "id": "store.scan",     "board": "store", "name": "懒扫描索引（冷启动不扫目录）", "carrier": "后台", "entry": "冷启动", "testId": "#list", "status": "done", "test": "boards/store.sh#scan" },
    { "id": "store.write",    "board": "store", "name": "写回 md（文件是权威源）", "carrier": "后台", "entry": "保存块", "testId": "#btn-done", "status": "done", "test": "boards/store.sh#write" },
    { "id": "store.folder",   "board": "store", "name": "随笔独立目录 + 文件夹", "carrier": "（计划中）", "entry": "设置", "testId": null, "status": "planned", "test": null },
    { "id": "export.opts",    "board": "export", "name": "按标签导出（目录层级=标签顺序）", "carrier": "导出小窗", "entry": "主页 导出", "testId": "#btn-export", "status": "done", "test": "boards/export.sh#plan" },
    { "id": "export.exclude", "board": "export", "name": "排除标签", "carrier": "导出小窗", "entry": "点标签芯片", "testId": "#btn-export", "status": "done", "test": "boards/export.sh#exclude" },
    { "id": "export.preview", "board": "export", "name": "导出并预览（不写盘）", "carrier": "导出小窗", "entry": "导出并预览", "testId": "#btn-export", "status": "done", "test": "boards/export.sh#preview" },
    { "id": "export.write",   "board": "export", "name": "写入目录（覆盖不堆副本）", "carrier": "导出小窗", "entry": "写入目录 / 直接导出", "testId": "#btn-export", "status": "done", "test": "boards/export.sh#write" },
    { "id": "export.share",   "board": "export", "name": "系统分享", "carrier": "导出小窗", "entry": "系统分享", "testId": "#btn-export", "status": "done", "test": "boards/export.sh#share" },
    { "id": "settings.root",  "board": "settings", "name": "笔记根与块数状态行", "carrier": "设置小窗", "entry": "主页 设置", "testId": "#btn-settings", "status": "done", "test": "boards/settings.sh#info" },
    { "id": "settings.refresh","board": "settings", "name": "刷新索引", "carrier": "设置小窗", "entry": "主页 设置", "testId": "#btn-settings", "status": "done", "test": "boards/settings.sh#refresh" },
    { "id": "sync.webdav",    "board": "sync", "name": "WebDAV（基线 + 冲突三选）", "carrier": "（计划中）", "entry": "设置", "testId": null, "status": "planned", "test": null }
  ]
};
