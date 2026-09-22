#!/usr/bin/env python3
"""覆盖自检（文件层面）：登记表 vs 测试映射。

页面里的 DOM 挂载由 selfcheck.js 负责（打开界面时自动跑，结果在 window.__coverage）；
这里管文件层面：每个 status=done 的功能必须有 testId 与对应的验收脚本，且脚本真的存在。

用法：python3 tools/check_coverage.py [--strict]
"""
import json
import pathlib
import sys

ROOT = pathlib.Path("/vol1/1000/airesults/mark-readnotes")
REG = ROOT / "source/mark-readnotes/app/src/main/assets/ui/registry.js"


def load_registry() -> dict:
    js = REG.read_text(encoding="utf-8")
    return json.loads(js[js.index("{"):js.rindex("}") + 1])


def main() -> int:
    data = load_registry()
    boards = {b["id"]: b for b in data["boards"]}
    feats = data["features"]
    gaps = {"没归属": [], "空集合": [], "没测": [], "脚本不存在": [], "未做": []}

    for b in data["boards"]:
        if b.get("planned"):
            continue   # 计划中的板块不算缺口
        if not [f for f in feats if f["board"] == b["id"]]:
            gaps["空集合"].append(b["id"])

    for f in feats:
        if f["board"] not in boards:
            gaps["没归属"].append(f["id"])
        if f["status"] == "planned":
            gaps["未做"].append(f["id"])
            continue
        if not f.get("test"):
            gaps["没测"].append(f["id"])
        else:
            script = ROOT / "tools" / f["test"].split("#")[0]
            if not script.exists():
                gaps["脚本不存在"].append(f'{f["id"]} → {f["test"]}')
        if not f.get("testId"):
            gaps["没测"].append(f'{f["id"]}（没有 testId，界面挂不上也不知道）')

    total = sum(len(v) for v in gaps.values())
    print(f'登记：板块 {len(data["boards"])} 个；功能 {len(feats)} 个（已实现 '
          f'{len([f for f in feats if f["status"] == "done"])}，计划中 {len(gaps["未做"])}）')
    for k, v in gaps.items():
        print(f'  {k}: {len(v)}' + (f' → {v}' if v else ''))
    print(f'缺口合计：{total}')
    if "--strict" in sys.argv:
        # “未做/计划中”不算缺口门禁，其它三类必须为 0
        blocking = len(gaps["没归属"]) + len(gaps["空集合"]) + len(gaps["没测"]) + len(gaps["脚本不存在"])
        return 1 if blocking else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
