#!/usr/bin/env python3
"""批量调用 pdf_extract.swift：把 tools/exams/.cache/pdfs/ 下的 PDF 抽成 JSONL 文本。

用法（仓库根目录）：
    python3 tools/exams/extract_pdfs.py [--max-pages N] [--force]

输入：
    tools/exams/.cache/papers.json   fetch.py papers 生成的清单（含仓库路径）
    tools/exams/.cache/pdfs/*.pdf    下载好的试卷（文件名 <course>__<原文件名>.pdf）

输出：
    tools/exams/.cache/pdf-text/<文件名去后缀>.jsonl   每页一行（method=text|ocr）
    tools/exams/.cache/media/<pdf 名>/pNNN.png          仅 OCR 页，勿提交
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "tools" / "exams" / ".cache"
PDF_DIR = CACHE / "pdfs"
TEXT_DIR = CACHE / "pdf-text"
MEDIA_DIR = CACHE / "media"
SWIFT = ROOT / "tools" / "exams" / "pdf_extract.swift"


def load_papers() -> list[dict]:
    papers_json = CACHE / "papers.json"
    if not papers_json.exists():
        print("缺少 tools/exams/.cache/papers.json，先跑：python3 tools/exams/fetch.py papers --download")
        raise SystemExit(2)
    data = json.loads(papers_json.read_text(encoding="utf-8"))
    return data.get("papers", data if isinstance(data, list) else [])


def local_name(course: str, repo_path: str) -> str:
    """与 fetch.py 下载时一致：<course>__<basename>。"""
    return f"{course}__{Path(repo_path).name}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-pages", type=int, default=20, help="每份 PDF 最多抽前 N 页（默认 20）")
    parser.add_argument("--force", action="store_true", help="输出已存在也重跑")
    parser.add_argument("--swift", default="swift", help="swift 可执行文件（默认 PATH 里的 swift）")
    args = parser.parse_args()

    TEXT_DIR.mkdir(parents=True, exist_ok=True)
    MEDIA_DIR.mkdir(parents=True, exist_ok=True)

    papers = load_papers()
    if not papers:
        print("papers.json 为空")
        return 2

    failures: list[str] = []
    for index, paper in enumerate(papers, 1):
        course = paper.get("course", "")
        repo_path = paper.get("path", "")
        pdf_name = local_name(course, repo_path)
        pdf_path = PDF_DIR / pdf_name
        if not pdf_path.exists():
            print(f"[{index}/{len(papers)}] 缺文件，跳过：{pdf_path.name}")
            failures.append(f"{pdf_name}（文件不存在）")
            continue
        out_path = TEXT_DIR / f"{pdf_path.stem}.jsonl"
        print(f"[{index}/{len(papers)}] {pdf_name}")
        cmd = [
            args.swift,
            str(SWIFT),
            str(pdf_path),
            str(out_path),
            "--media-dir",
            str(MEDIA_DIR / pdf_path.stem),
            "--source-path",
            repo_path,
            "--max-pages",
            str(args.max_pages),
        ]
        if args.force:
            cmd.append("--force")
        completed = subprocess.run(cmd, cwd=ROOT)
        if completed.returncode != 0:
            failures.append(f"{pdf_name}（exit {completed.returncode}）")

    if failures:
        print("\n以下 PDF 抽取失败：")
        for item in failures:
            print("  -", item)
        return 1
    print(f"\n全部完成，输出在 {TEXT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
