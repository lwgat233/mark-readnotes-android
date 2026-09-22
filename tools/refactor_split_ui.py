#!/usr/bin/env python3
"""把 app.js（一个 523 行的单体）按板块切成多个文件 —— 机械切分，不改任何函数体。

切分结果（按 index.html 里的加载顺序）：
  core.js      桥 + 全局状态 + 小工具（el/toast）
  render.js    渲染器（markdown / 公式 / 消毒 / 图片地址）—— 一个渲染器一个文件
  sheet.js     小窗基础设施（rowKV/sheetShow/closeSheet/sheetOpen）
  home.js      主页（最近编辑的块）
  board.js     白板（全屏编辑层）
  settings.js  设置小窗
  export.js    导出（按标签）
  boot.js      启动装配（事件绑定 + onPush + mrBack/mrState）
  registry.js  功能登记表（数据源，界面与自检都读它）—— 另写
  selfcheck.js 覆盖自检 —— 另写
"""
import pathlib

UI = pathlib.Path("/vol1/1000/airesults/mark-readnotes/source/mark-readnotes/app/src/main/assets/ui")
src = (UI / "app.js").read_text(encoding="utf-8").splitlines()


def seg(a: int, b: int) -> str:
    return "\n".join(src[a - 1:b]).rstrip() + "\n"


head = lambda title, body: "/* %s —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */\n" % title

files = {
    "core.js": head("核心层：桥 + 全局状态",
                    "") + seg(1, 47),
    "render.js": head("渲染层：一个渲染器一个文件（markdown / KaTeX / 消毒 / 图片地址）", "") + seg(288, 393),
    "sheet.js": head("小窗基础设施", "") + seg(211, 243),
    "home.js": head("随笔板块：主页（最近编辑的块）", "") + seg(49, 101),
    "board.js": head("随笔板块：白板（全屏编辑层）", "") + seg(103, 209),
    "settings.js": head("设置板块", "") + seg(245, 269),
    "export.js": head("导出板块", "") + seg(395, 483),
    "boot.js": head("启动装配", "") + seg(271, 286) + "\n" + seg(485, len(src)),
}

for name, body in files.items():
    (UI / name).write_text(body, encoding="utf-8")
    print("写出", name, len(body), "字节")

# 自证：每个函数只出现在一个文件里（重复定义会在运行期覆盖，属于拆分事故）
import re
seen = {}
for name in files:
    for m in re.finditer(r"^(?:async )?function (\w+)|^window\.(\w+) = function", (UI / name).read_text(encoding="utf-8"), re.M):
        fn = m.group(1) or m.group(2)
        seen.setdefault(fn, []).append(name)
dups = {k: v for k, v in seen.items() if len(v) > 1}
print("重复定义的函数：", dups if dups else "无")
