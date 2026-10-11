"""Phase 0 PDF reference extractor using PyMuPDF, mirroring the Python backend's approach.

Reports the metrics we need to compare against the Java/PDFBox port: page count,
text length, block count, heading candidates (font-size heuristic), and table count
(page.find_tables()). Writes JSON to out/pymupdf_<name>.json and prints a summary.
"""
from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

import pymupdf

CAPTION = re.compile(r"(?i)^(figure|table|fig\.?|tab\.?|图|表)\s*\d*")
NUMBERED = re.compile(r"^\s*(?:\d+(?:\.\d+){0,3}|[A-Z](?:\.\d+){0,3})[.、]?\s+")
CLAUSE_END = (".", "。", ";", "；", ",", "，", ":", "：")


def body_font_size(doc) -> float:
    weights: dict[float, int] = defaultdict(int)
    for page in doc:
        d = page.get_text("dict")
        for block in d.get("blocks", []):
            if block.get("type") != 0:
                continue
            for line in block.get("lines", []):
                for span in line.get("spans", []):
                    size = round(float(span.get("size", 0.0)) * 2) / 2
                    weights[size] += len(span.get("text", ""))
    if not weights:
        return 10.0
    return max(weights.items(), key=lambda kv: kv[1])[0]


def classify(text: str, size: float, body: float, bold: bool) -> str:
    t = text.strip()
    if not t:
        return "empty"
    if CAPTION.match(t):
        return "figure_caption"
    if len(t) > 100 or len(t.split()) > 16 or t.endswith(CLAUSE_END):
        return "paragraph"
    ratio = size / body if body else 1.0
    if ratio >= 1.5:
        return "h1"
    if ratio >= 1.25:
        return "h2"
    if ratio >= 1.1:
        return "h3"
    if NUMBERED.match(t):
        return "h4"
    if bold and ratio >= 1.05 and len(t) <= 60:
        return "h4"
    return "paragraph"


def main(path: Path, out_dir: Path) -> None:
    doc = pymupdf.open(path)
    body = body_font_size(doc)
    text_len = 0
    block_count = 0
    headings: list[dict] = []
    for pno, page in enumerate(doc, start=1):
        text_len += len(page.get_text("text"))
        d = page.get_text("dict")
        for block in d.get("blocks", []):
            if block.get("type") != 0:
                continue
            block_count += 1
            for line in block.get("lines", []):
                for span in line.get("spans", []):
                    size = round(float(span.get("size", 0.0)) * 2) / 2
                    bold = "bold" in (span.get("font", "").lower())
                    kind = classify(span.get("text", ""), size, body, bold)
                    if kind.startswith("h"):
                        headings.append({
                            "page": pno, "level": kind, "size": size,
                            "text": span.get("text", "").strip()[:90],
                        })
    tables = []
    for pno, page in enumerate(doc, start=1):
        try:
            found = page.find_tables()
            for t in found.tables:
                tables.append({"page": pno, "rows": t.row_count, "cols": t.col_count})
        except Exception as exc:  # noqa: BLE001
            tables.append({"page": pno, "error": str(exc)})

    result = {
        "name": path.name,
        "pages": doc.page_count,
        "text_len": text_len,
        "block_count": block_count,
        "body_font_size": body,
        "heading_count": len(headings),
        "headings": headings[:40],
        "table_count": len(tables),
        "tables": tables,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"pymupdf_{path.stem}.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"[pymupdf] {path.name}: pages={doc.page_count} text_len={text_len} "
          f"blocks={block_count} body={body} headings={len(headings)} tables={len(tables)}")
    doc.close()


if __name__ == "__main__":
    base = Path(__file__).resolve().parent
    for p in sorted((base / "samples").glob("*.pdf")):
        main(p, base / "out")
