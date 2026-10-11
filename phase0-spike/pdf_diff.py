"""Compare the PyMuPDF reference JSON with the PDFBox JSON for each sample."""
from __future__ import annotations

import json
from pathlib import Path

base = Path(__file__).resolve().parent
out = base / "out"


def load(prefix: str, stem: str):
    p = out / f"{prefix}_{stem}.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def norm(s: str) -> str:
    return " ".join(s.lower().split())


for stem in sorted({p.name.split("_", 1)[1][:-5] for p in out.glob("pymupdf_*.json")}):
    py = load("pymupdf", stem)
    jv = load("pdfbox", stem)
    if not py or not jv:
        print(f"[{stem}] missing reference or java output")
        continue
    py_h = {norm(h["text"]) for h in py["headings"] if h.get("text")}
    jv_h = {norm(h["text"]) for h in jv["headings"] if h.get("text")}
    overlap = len(py_h & jv_h)
    print(f"=== {stem} ===")
    print(f"  pages:            pymupdf={py['pages']}  pdfbox={jv['pages']}")
    print(f"  text_len:         pymupdf={py['text_len']}  pdfbox={jv['text_len']}  "
          f"ratio={jv['text_len']/max(1,py['text_len']):.3f}")
    print(f"  body_font_size:   pymupdf={py['body_font_size']}  pdfbox={jv['body_font_size']}")
    print(f"  heading_count:    pymupdf={py['heading_count']}  pdfbox={jv['heading_count']}  "
          f"exact-text overlap={overlap}")
    print(f"  table_count:      pymupdf.find_tables={py['table_count']}  "
          f"pdfbox.table_like_pages={jv['table_like_pages']}")
