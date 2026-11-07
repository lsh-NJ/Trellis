#!/usr/bin/env python3
"""从 NJUPT-General-Free-Exams 仓库按需抓取题库素材。

仓库：https://github.com/NJUPTFreeExams/NJUPT-General-Free-Exams
它是一个 2.6GB 的公开仓库（566 份 PDF），**不能整克隆**。这里全部走 GitHub API + raw：

1. 列树：GET /repos/{repo}/git/trees/master?recursive=1（一次请求拿到全部路径+大小）；
2. 按路径取文件：GET raw.githubusercontent.com/{repo}/master/{urlencode(path)}。

只下载两条数据源：
- `src/content/docs/reserved/*.md`：考试回忆/题库/复习要点（几十 KB 级，必做）；
- `public/<course>/` 下挑少量「试卷/期末/真题」类 PDF（默认 8 份，带体积上限）。

产物全部落在 `tools/exams/.cache/`（已 gitignore），可随时删除重跑：

    python3 tools/exams/fetch.py tree        # 列树 -> .cache/tree.json
    python3 tools/exams/fetch.py reserved    # 下载 reserved md -> .cache/reserved/
    python3 tools/exams/fetch.py papers --count 8 --download
                                             # 选 + 下载试卷 PDF -> .cache/pdfs/ 与 .cache/papers.json
    python3 tools/exams/fetch.py all         # 以上全做

无 token 也能跑（GitHub 未认证 API 限流 60 次/小时，本管线只用 1 次列树）。
限流时可用 `GITHUB_TOKEN=... python3 tools/exams/fetch.py ...` 提升配额。
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = "NJUPTFreeExams/NJUPT-General-Free-Exams"
BRANCH = "master"
API_TREE = f"https://api.github.com/repos/{REPO}/git/trees/{BRANCH}?recursive=1"
RAW_BASE = f"https://raw.githubusercontent.com/{REPO}/{BRANCH}/"

CACHE = pathlib.Path(__file__).resolve().parent / ".cache"
TREE_FILE = CACHE / "tree.json"
RESERVED_DIR = CACHE / "reserved"
PAPERS_DIR = CACHE / "pdfs"
PAPERS_MANIFEST = CACHE / "papers.json"

# 试卷类 PDF 的路径关键词。命中越靠前分越高；「答案」类略降但也可用（题目仍在里面）。
PAPER_KEYWORDS = (
    ("期末", 3),
    ("试卷", 3),
    ("真题", 2),
    ("期中", 1),
    ("答案", 1),
)

USER_AGENT = "trellis-exam-fetch/1.0 (+https://github.com/trellis-local)"


def log(message: str) -> None:
    print(message, flush=True)


def http_get(url: str, *, timeout: int = 120, retries: int = 4, binary: bool = True):
    """带重试的 GET。401/403 给出 token 提示，404 直接失败。"""
    headers = {"User-Agent": USER_AGENT, "Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"

    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read() if binary else response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                raise SystemExit(
                    f"HTTP {error.code} 访问被拒绝：{url}\n"
                    "可能是 GitHub API 限流。可设置 GITHUB_TOKEN 环境变量后重试。"
                ) from error
            if error.code == 404:
                raise
            last_error = error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
        if attempt < retries:
            wait = 2 ** attempt
            log(f"  下载失败（第 {attempt} 次），{wait}s 后重试：{last_error}")
            time.sleep(wait)
    raise SystemExit(f"下载失败：{url}\n{last_error}")


def raw_url(path: str) -> str:
    """中文/括号路径必须 URL 编码；`/` 保留作为分隔符。"""
    return RAW_BASE + urllib.parse.quote(path, safe="/")


def load_tree(force: bool = False) -> dict:
    if TREE_FILE.exists() and not force:
        return json.loads(TREE_FILE.read_text(encoding="utf-8"))
    log(f"读取仓库树 {API_TREE}")
    data = json.loads(http_get(API_TREE, binary=False))
    if data.get("truncated"):
        raise SystemExit("GitHub 返回的树被截断（仓库太大），需要改成分页抓取；当前仓库不应触发。")
    CACHE.mkdir(parents=True, exist_ok=True)
    TREE_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    blobs = [item for item in data["tree"] if item["type"] == "blob"]
    log(f"树已缓存：{TREE_FILE}（{len(blobs)} 个文件）")
    return data


def blobs(tree: dict) -> list[dict]:
    return [item for item in tree["tree"] if item["type"] == "blob"]


def fetch_reserved(force: bool = False) -> list[dict]:
    """下载全部 `src/content/docs/reserved/*.md`。"""
    tree = load_tree()
    prefix = "src/content/docs/reserved/"
    targets = [b for b in blobs(tree) if b["path"].startswith(prefix) and b["path"].endswith(".md")]
    if not targets:
        raise SystemExit(f"仓库里没有找到 {prefix}*.md，上游结构可能变了。")

    RESERVED_DIR.mkdir(parents=True, exist_ok=True)
    manifest = []
    for item in targets:
        name = item["path"].rsplit("/", 1)[-1]
        # 文件名就是文字，直接当本地文件名是安全的（没有 '/'）；但仍做一次清理防意外。
        local_name = re.sub(r"[/\\]", "_", name)
        destination = RESERVED_DIR / local_name
        if destination.exists() and destination.stat().st_size > 0 and not force:
            size = destination.stat().st_size
            log(f"  跳过（已存在）{name} {size}B")
        else:
            data = http_get(raw_url(item["path"]))
            destination.write_bytes(data)
            size = len(data)
            log(f"  下载 {name} {size}B")
        manifest.append(
            {
                "repoPath": item["path"],
                "localName": local_name,
                "size": size,
            }
        )
        time.sleep(0.05)

    (RESERVED_DIR / "manifest.json").write_text(
        json.dumps({"repo": REPO, "branch": BRANCH, "files": manifest}, ensure_ascii=False, indent=1),
        encoding="utf-8",
    )
    log(f"reserved 完成：{len(manifest)} 个 md，共 {sum(m['size'] for m in manifest)}B -> {RESERVED_DIR}")
    return manifest


def score_paper(path: str, size: int, max_size_mb: float) -> int:
    score = 0
    for keyword, weight in PAPER_KEYWORDS:
        if keyword in path:
            score += weight
    parent = path.rsplit("/", 1)[0]
    if "试卷" in parent:
        score += 2
    if "期末" in parent:
        score += 2
    if size > max_size_mb * 1024 * 1024:
        score -= 8  # 大文件多为整本资料/扫描合集，解析成本高
    elif size < 1_500_000:
        score += 1
    # 纯答案文件（没有题目）优先级低一点
    name = path.rsplit("/", 1)[-1]
    if "答案" in name and "试卷" not in name:
        score -= 1
    return score


def select_papers(
    count: int = 8,
    max_size_mb: float = 4.0,
    min_size_kb: int = 60,
    per_course_limit: int = 2,
    only_courses: list[str] | None = None,
) -> list[dict]:
    """挑出跨课程、以「试卷/期末/真题」命名的 PDF；每门课默认最多 2 份。"""
    tree = load_tree()
    candidates: dict[str, list[dict]] = {}
    for item in blobs(tree):
        path, size = item["path"], int(item["size"])
        if not path.startswith("public/") or not path.lower().endswith(".pdf"):
            continue
        parts = path.split("/")
        if len(parts) < 3:
            continue
        course = parts[1]
        if only_courses and course not in only_courses:
            continue
        if size < min_size_kb * 1024 or size > max_size_mb * 1024 * 1024:
            continue
        if not any(keyword in path for keyword, _ in PAPER_KEYWORDS):
            continue
        candidates.setdefault(course, []).append(
            {
                "path": path,
                "course": course,
                "size": size,
                "score": score_paper(path, size, max_size_mb),
            }
        )

    for course in candidates:
        candidates[course].sort(key=lambda c: (-c["score"], c["size"]))

    # 先按「课程最佳候选」的质量排序课程，再轮流每门课取，保证学科覆盖面。
    course_order = sorted(
        candidates,
        key=lambda course: (-candidates[course][0]["score"], candidates[course][0]["size"]),
    )
    selected: list[dict] = []
    taken_per_course: dict[str, int] = {}
    while len(selected) < count:
        progressed = False
        for course in course_order:
            if len(selected) >= count:
                break
            if taken_per_course.get(course, 0) >= per_course_limit:
                continue
            pool = candidates[course]
            already = taken_per_course.get(course, 0)
            if already >= len(pool):
                continue
            selected.append(pool[already])
            taken_per_course[course] = already + 1
            progressed = True
        if not progressed:
            break

    if len(selected) < count:
        log(f"警告：只挑到 {len(selected)} 份（目标 {count}），可放宽 --max-size-mb 或 --min-size-kb。")
    if len({item["course"] for item in selected}) < 5:
        log("警告：入选 PDF 的课程数不足 5，学科覆盖可能不够。")
    return selected


def download_papers(selected: list[dict], force: bool = False) -> list[dict]:
    PAPERS_DIR.mkdir(parents=True, exist_ok=True)
    downloaded = []
    for item in selected:
        course = re.sub(r"[/\\]", "_", item["course"])
        name = re.sub(r"[/\\]", "_", item["path"].rsplit("/", 1)[-1])
        local_name = f"{course}__{name}"
        destination = PAPERS_DIR / local_name
        if destination.exists() and destination.stat().st_size > 0 and not force:
            log(f"  跳过（已存在）{local_name}")
        else:
            log(f"  下载 {item['path']}（{item['size'] // 1024}KB）")
            data = http_get(raw_url(item["path"]))
            destination.write_bytes(data)
        record = dict(item)
        record["localPath"] = str(destination.relative_to(CACHE.parent.parent))
        record["localName"] = local_name
        downloaded.append(record)
        time.sleep(0.1)

    PAPERS_MANIFEST.write_text(
        json.dumps({"repo": REPO, "branch": BRANCH, "papers": downloaded}, ensure_ascii=False, indent=1),
        encoding="utf-8",
    )
    log(f"试卷清单：{PAPERS_MANIFEST}（{len(downloaded)} 份）")
    return downloaded


def command_tree(args: argparse.Namespace) -> None:
    tree = load_tree(force=args.refresh)
    all_blobs = blobs(tree)
    pdfs = [b for b in all_blobs if b["path"].lower().endswith(".pdf")]
    reserved = [b for b in all_blobs if b["path"].startswith("src/content/docs/reserved/")]
    log(f"文件 {len(all_blobs)} 个；PDF {len(pdfs)} 个；reserved md {len(reserved)} 个")


def command_reserved(args: argparse.Namespace) -> None:
    fetch_reserved(force=args.force)


def command_papers(args: argparse.Namespace) -> None:
    only = [c.strip() for c in args.courses.split(",") if c.strip()] if args.courses else None
    selected = select_papers(
        count=args.count,
        max_size_mb=args.max_size_mb,
        min_size_kb=args.min_size_kb,
        per_course_limit=args.per_course,
        only_courses=only,
    )
    for item in selected:
        log(f"  [{item['score']:>2}] {item['size'] // 1024:>5}KB  {item['path']}")
    if args.download:
        download_papers(selected, force=args.force)
    else:
        log("（只选择不下载；加 --download 落到 .cache/pdfs/）")


def command_all(args: argparse.Namespace) -> None:
    fetch_reserved(force=args.force)
    command_papers(args)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    tree_parser = sub.add_parser("tree", help="只抓 Git 树并打印统计")
    tree_parser.add_argument("--refresh", action="store_true", help="忽略缓存重新抓树")
    tree_parser.set_defaults(handler=command_tree)

    reserved_parser = sub.add_parser("reserved", help="下载 src/content/docs/reserved/*.md")
    reserved_parser.add_argument("--force", action="store_true", help="已存在也重新下载")
    reserved_parser.set_defaults(handler=command_reserved)

    papers_parser = sub.add_parser("papers", help="挑选（可选下载）试卷类 PDF")
    papers_parser.add_argument("--count", type=int, default=8, help="目标份数（默认 8）")
    papers_parser.add_argument("--max-size-mb", type=float, default=4.0, help="单份 PDF 体积上限（默认 4MB）")
    papers_parser.add_argument("--min-size-kb", type=int, default=60, help="单份 PDF 体积下限（默认 60KB）")
    papers_parser.add_argument("--per-course", type=int, default=2, help="每门课最多选几份（默认 2）")
    papers_parser.add_argument("--courses", default="", help="逗号分隔的课程白名单（默认全部）")
    papers_parser.add_argument("--download", action="store_true", help="真正下载选中的 PDF")
    papers_parser.add_argument("--force", action="store_true", help="已存在也重新下载")
    papers_parser.set_defaults(handler=command_papers)

    all_parser = sub.add_parser("all", help="tree + reserved + papers --download 一次做完")
    all_parser.add_argument("--count", type=int, default=8)
    all_parser.add_argument("--max-size-mb", type=float, default=4.0)
    all_parser.add_argument("--min-size-kb", type=int, default=60)
    all_parser.add_argument("--per-course", type=int, default=2)
    all_parser.add_argument("--courses", default="")
    all_parser.add_argument("--download", action="store_true", default=True)
    all_parser.add_argument("--force", action="store_true")
    all_parser.set_defaults(handler=command_all)

    args = parser.parse_args(argv)
    CACHE.mkdir(parents=True, exist_ok=True)
    args.handler(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
