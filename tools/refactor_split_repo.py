#!/usr/bin/env python3
"""把 Repo.kt 按板块切成 notes/ 与 export/ 两个文件（机械切分，不动任何函数体）。

为什么用脚本：23000 字节的文件手抄一遍必然抄错几行；按行号切分是机械的、可复核的。
切分后：
  notes/NotesRepo.kt   —— 目录授权、扫描与索引、读、写、图片、预览文案
  export/ExportRepo.kt —— 导出（按标签）：计划、写盘、分享准备
两者共用一个 core：dev.markreadnotes.Store（ctx/db/saf/root/tagList）。
"""
import pathlib
import sys

ROOT = pathlib.Path("/vol1/1000/airesults/mark-readnotes/source/mark-readnotes/app/src/main/java/dev/markreadnotes")
SRC = ROOT / "Repo.kt"

lines = SRC.read_text(encoding="utf-8").splitlines()


def seg(a: int, b: int) -> str:
    """1-based，闭区间。"""
    return "\n".join(lines[a - 1:b])


# 行号来自 grep 出来的分节横幅（见 docs/重构规格-第4轮.md 的对照表）
notes_body = seg(28, 341)          # 目录授权 → openImage
export_body = seg(342, 475)        # 导出（按标签）
preview_body = seg(476, 483)       # preview() 文案，归随笔（列表摘要用）

assert "fun attachTree" in notes_body, "notes 切分起点不对"
assert "fun tagStats" in export_body, "export 切分起点不对"
assert "fun preview" in preview_body, "preview 切分不对"

NOTES = '''package dev.markreadnotes.notes

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import dev.markreadnotes.BlockRow
import dev.markreadnotes.Logs
import dev.markreadnotes.Md
import dev.markreadnotes.Saf
import dev.markreadnotes.Store
import dev.markreadnotes.Store.Root

/** 随笔本体：块/文件的读写、搬家、扫描与索引。导出去 ExportRepo，界面在 ops/ 与 assets/ui。 */
class NotesRepo(private val store: Store) {
    private val ctx get() = store.ctx
    private val db get() = store.db
    private val saf get() = store.saf
    fun root(): Root? = store.root()
    private fun tagList(row: BlockRow): List<String> = store.tagList(row)

''' + notes_body + "\n" + preview_body + "\n}\n"

EXPORT = '''package dev.markreadnotes.export

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import dev.markreadnotes.BlockRow
import dev.markreadnotes.Logs
import dev.markreadnotes.Saf
import dev.markreadnotes.Store
import dev.markreadnotes.Store.Root

/** 导出板块：目录层级＝标签顺序、排除标签、预览、写盘、分享准备。 */
class ExportRepo(private val store: Store) {
    private val ctx get() = store.ctx
    private val db get() = store.db
    private val saf get() = store.saf
    fun root(): Root? = store.root()
    private fun tagList(row: BlockRow): List<String> = store.tagList(row)
    @Suppress("unused")
    private val safDoc = Saf.Doc::class.java.name   // 保住对 Saf.Doc 的引用（findImage 返回类型在 NotesRepo）

''' + export_body + "\n}\n"

(ROOT / "notes").mkdir(exist_ok=True)
(ROOT / "export").mkdir(exist_ok=True)
(ROOT / "notes" / "NotesRepo.kt").write_text(NOTES, encoding="utf-8")
(ROOT / "export" / "ExportRepo.kt").write_text(EXPORT, encoding="utf-8")
print("写出 notes/NotesRepo.kt:", len(NOTES), "字节")
print("写出 export/ExportRepo.kt:", len(EXPORT), "字节")
print("原 Repo.kt:", len(SRC.read_text(encoding='utf-8')), "字节 → 请人工确认后再删")
