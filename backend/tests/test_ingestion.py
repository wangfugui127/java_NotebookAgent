from pathlib import Path

from app.ingestion.chunker import (
    blocks_for_range,
    chunk_document,
    detect_sections,
    infer_chunk_type,
)
from app.ingestion.parser import (
    BLOCK_FIGURE_CAPTION,
    BLOCK_HEADING,
    BLOCK_TABLE,
    ParsedDocument,
    ParsedPage,
    parse_document,
    parse_text_blocks,
)


def test_chunker_covers_document_and_preserves_page_bounds() -> None:
    text = "A" * 3000 + "\n\n" + "B" * 3000
    parsed = ParsedDocument(
        text=text,
        pages=[
            ParsedPage(1, 0, 3000, text[:3000]),
            ParsedPage(2, 3002, len(text), text[3002:]),
        ],
    )
    chunks = chunk_document(parsed, max_chars=2000, overlap_chars=200)
    assert chunks[0].char_start == 0
    assert chunks[-1].char_end == len(text)
    assert {item.page_start for item in chunks} >= {1, 2}


def test_section_detection_covers_markdown_document() -> None:
    text = "# 引言\n内容一。\n\n## 方法\n内容二。"
    parsed = ParsedDocument(text=text, pages=[ParsedPage(1, 0, len(text), text)])
    sections = detect_sections(parsed)
    assert [item.title for item in sections] == ["引言", "方法"]
    assert sections[0].char_start == 0
    assert sections[-1].char_end == len(text)


def test_section_detection_rejects_affiliations_and_captions() -> None:
    text = (
        "1 Introduction\n"
        "Mila - Quebec AI Institute\n"
        "McGill University\n"
        "Figure 2: Presto learns to reconstruct channels.\n"
        "one is missing (for instance, relying on Sentinel-1)\n"
        "2 Method\n"
        "3 Results\n"
    )
    parsed = ParsedDocument(text=text, pages=[ParsedPage(1, 0, len(text), text)])
    titles = [item.title for item in detect_sections(parsed)]
    assert "McGill University" not in titles
    assert all(not title.startswith("Figure") for title in titles)
    assert len(titles) <= 64


def test_section_detection_caps_noisy_numbered_lines() -> None:
    text = "\n".join(f"{index} Heading Number {index}" for index in range(1, 400))
    parsed = ParsedDocument(text=text, pages=[ParsedPage(1, 0, len(text), text)])
    sections = detect_sections(parsed)
    assert len(sections) == 1
    assert sections[0].title == "正文"


def test_octet_stream_without_filename_accepts_utf8_text(tmp_path: Path) -> None:
    path = tmp_path / "v1"
    path.write_text("# 方法\n这是一个没有原始文件名的 Markdown 上传。", encoding="utf-8")
    parsed = parse_document(path, "application/octet-stream")
    assert parsed.text.startswith("# 方法")
    assert parsed.pages[0].char_end == len(parsed.text)


def test_text_blocks_detect_structure_and_offsets() -> None:
    text = (
        "# 方法\n正文内容。\n\n"
        "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n"
        "Figure 1: 示例\n"
    )
    blocks = parse_text_blocks(text)
    kinds = {block.kind for block in blocks}
    assert BLOCK_HEADING in kinds
    assert BLOCK_TABLE in kinds
    assert BLOCK_FIGURE_CAPTION in kinds
    for block in blocks:
        assert text[block.char_start : block.char_end].strip()


def test_infer_chunk_type_distinguishes_table_caption_paragraph() -> None:
    assert infer_chunk_type("| A | B |\n| --- | --- |\n| 1 | 2 |") == "table"
    assert infer_chunk_type("Figure 1: 示例说明") == "figure_caption"
    assert infer_chunk_type("这是一段普通正文。") == "paragraph"


def test_blocks_for_range_selects_overlapping_blocks() -> None:
    blocks = parse_text_blocks("# A\n第一段。\n\n# B\n第二段。\n")
    selected = blocks_for_range(blocks, 0, 4)
    assert selected
    assert all(block.char_start < 4 for block in selected)


def test_section_detection_keeps_numbered_headings() -> None:
    text = (
        "# Abstract\n摘要内容。\n\n"
        "1 Introduction\n引言内容。\n\n"
        "2 Related Work\n相关工作内容。\n\n"
        "3 Method\n方法内容。\n\n"
        "4 Experiments\n实验内容。\n"
    )
    parsed = ParsedDocument(text=text, pages=[ParsedPage(1, 0, len(text), text)])
    sections = detect_sections(parsed)
    titles = [item.title for item in sections]
    assert "Introduction" in titles
    assert "Method" in titles
    assert "Experiments" in titles
    abstract = next(item for item in sections if item.title == "Abstract")
    assert abstract.char_end < len(text)


def test_section_detection_keeps_letter_numbered_headings() -> None:
    text = "1 Introduction\n正文。\n\nA Appendix\n附录内容。\n"
    parsed = ParsedDocument(text=text, pages=[ParsedPage(1, 0, len(text), text)])
    titles = [item.title for item in detect_sections(parsed)]
    assert "Appendix" in titles


def test_section_detection_rejects_numeric_data_lines() -> None:
    text = "1 Introduction\n正文。\n\n10.09 29.42 12.82 33.09 LightGBM 11.86\n更多正文。\n"
    parsed = ParsedDocument(text=text, pages=[ParsedPage(1, 0, len(text), text)])
    titles = [item.title for item in detect_sections(parsed)]
    assert titles == ["Introduction"]
