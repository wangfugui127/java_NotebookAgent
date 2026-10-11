package com.wangfugui.notebookagent.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Layout-aware document parser (PDF via PDFBox, text/markdown directly). */
public class Parser {

    public static final String PARSER_NAME = "layout-markdown";
    public static final String PARSER_VERSION = "1";

    private static final Logger log = LoggerFactory.getLogger(Parser.class);
    private static final Pattern CAPTION = Pattern.compile("(?i)^(figure|table|fig\\.?|tab\\.?|图|表)\\s*\\d*");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(?:\\d+(?:\\.\\d+){0,3}|[A-Z](?:\\.\\d+){0,3})[.、]?\\s+");
    private static final Pattern FORMULA = Pattern.compile("\\\\[a-z]+|[∑∫√±^]|_\\{|\\^\\{|=");
    private static final String CLAUSE_END = ".。;；,，:：";

    public Parsed.Document parse(byte[] content, String mediaType, String suffix) {
        String lower = suffix == null ? "" : suffix.toLowerCase();
        boolean isPdf = "application/pdf".equalsIgnoreCase(mediaType) || lower.equals(".pdf");
        if (isPdf) {
            return parsePdf(content);
        }
        if (lower.equals(".txt") || lower.equals(".md") || lower.equals(".markdown")) {
            String text = new String(content, StandardCharsets.UTF_8);
            return parseText(text);
        }
        // octet-stream fallback: accept only valid UTF-8 text
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) {
            throw new IllegalArgumentException("unsupported binary content");
        }
        return parseText(text);
    }

    // ------------------------------------------------------------------ PDF

    private Parsed.Document parsePdf(byte[] content) {
        List<Line> lines = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(content)) {
            LineStripper stripper = new LineStripper();
            stripper.setSortByPosition(true);
            stripper.extract(doc);
            lines = stripper.lines;
        } catch (IOException e) {
            throw new RuntimeException("failed to parse pdf", e);
        }
        float body = bodyFontSize(lines);
        Parsed.Document doc = new Parsed.Document();
        List<Parsed.Page> pages = new ArrayList<>();
        Map<Integer, StringBuilder> pageText = new LinkedHashMap<>();
        StringBuilder full = new StringBuilder();
        int offset = 0;
        for (Line line : lines) {
            String kind = classify(line.text, line.size, body, line.bold);
            line.type = kind;
            String markdown = toMarkdown(kind, line.text);
            Parsed.Block block = new Parsed.Block();
            block.setType(kind);
            block.setText(line.text.trim());
            block.setMarkdown(markdown);
            block.setPage(line.page);
            block.setCharStart(offset);
            block.setCharEnd(offset + markdown.length());
            block.setBbox(List.of((double) line.x, (double) line.y));
            doc.getBlocks().add(block);
            full.append(markdown).append("\n\n");
            pageText.computeIfAbsent(line.page, p -> new StringBuilder()).append(line.text).append('\n');
            offset += markdown.length() + 2;
        }
        for (Map.Entry<Integer, StringBuilder> e : pageText.entrySet()) {
            Parsed.Page page = new Parsed.Page();
            page.setNumber(e.getKey());
            var pageBlocks = doc.getBlocks().stream().filter(b -> e.getKey().equals(b.getPage())).toList();
            page.setCharStart(pageBlocks.getFirst().getCharStart());
            page.setCharEnd(pageBlocks.getLast().getCharEnd() + 2);
            page.setText(e.getValue().toString());
            pages.add(page);
        }
        doc.setPages(pages);
        doc.setText(full.toString());
        doc.setMarkdown(full.toString());
        if (full.toString().replaceAll("\\s", "").length() < 50) {
            throw new IllegalArgumentException("PDF has no usable text layer; OCR is required before ingestion");
        }
        return doc;
    }

    static float bodyFontSize(List<Line> lines) {
        Map<Float, Long> weights = new LinkedHashMap<>();
        for (Line line : lines) {
            float size = Math.round(line.size * 2f) / 2f;
            weights.merge(size, (long) line.text.length(), Long::sum);
        }
        float best = 10f;
        long bestWeight = -1;
        for (Map.Entry<Float, Long> e : weights.entrySet()) {
            if (e.getValue() > bestWeight) {
                bestWeight = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    static String classify(String text, float size, float body, boolean bold) {
        String t = text.strip();
        if (t.isEmpty()) {
            return "paragraph";
        }
        if (CAPTION.matcher(t).find()) {
            return "figure_caption";
        }
        boolean longText = t.length() > 100 || t.split("\\s+").length > 16;
        boolean endsClause = t.endsWith(".") || t.endsWith("。") || t.endsWith(";") || t.endsWith("；")
                || t.endsWith(",") || t.endsWith("，") || t.endsWith(":") || t.endsWith("：");
        if (FORMULA.matcher(t).find() && t.length() <= 200 && (t.contains("=") || t.contains("∑") || t.contains("∫"))) {
            return "formula";
        }
        if (longText || endsClause) {
            return "paragraph";
        }
        float ratio = body == 0f ? 1f : size / body;
        if (ratio >= 1.5f) {
            return "heading";
        }
        if (ratio >= 1.25f) {
            return "heading";
        }
        if (ratio >= 1.1f) {
            return "heading";
        }
        if (NUMBERED.matcher(t).find()) {
            return "heading";
        }
        if (bold && ratio >= 1.05f && t.length() <= 60) {
            return "heading";
        }
        return "paragraph";
    }

    private static String toMarkdown(String kind, String text) {
        String t = text.strip();
        return "heading".equals(kind) ? "# " + t : t;
    }

    // ------------------------------------------------------------------ text

    private Parsed.Document parseText(String text) {
        Parsed.Document doc = new Parsed.Document();
        String[] rawLines = text.split("\n", -1);
        List<Parsed.Block> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int offset = 0;
        for (String rawLine : rawLines) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            String kind;
            String markdown;
            if (line.matches("^#{1,6}\\s+.*")) {
                kind = "heading";
                markdown = line;
            } else if (NUMBERED.matcher(line).find() && line.length() <= 64 && !line.endsWith("。")
                    && !line.endsWith(".") && !line.endsWith("，")) {
                kind = "heading";
                markdown = "# " + line;
            } else if (CAPTION.matcher(line).find()) {
                kind = "figure_caption";
                markdown = line;
            } else if (looksLikeTable(line)) {
                kind = "table";
                markdown = line;
            } else {
                kind = "paragraph";
                markdown = line;
            }
            Parsed.Block block = new Parsed.Block();
            block.setType(kind);
            block.setText(line);
            block.setMarkdown(markdown);
            block.setPage(1);
            block.setCharStart(offset);
            block.setCharEnd(offset + markdown.length());
            blocks.add(block);
            full.append(markdown).append("\n\n");
            offset += markdown.length() + 2;
        }
        Parsed.Page page = new Parsed.Page();
        page.setNumber(1);
        page.setText(full.toString());
        page.setCharStart(0);
        page.setCharEnd(full.length());
        doc.setPages(List.of(page));
        doc.setBlocks(blocks);
        doc.setText(full.toString());
        doc.setMarkdown(full.toString());
        return doc;
    }

    private static boolean looksLikeTable(String line) {
        return line.contains("|") && line.split("\\|").length >= 3;
    }

    // ------------------------------------------------------------------ line stripper

    static final class Line {
        String text;
        float size;
        boolean bold;
        int page;
        float x;
        float y;
        String type;
    }

    private static final class LineStripper extends PDFTextStripper {
        final List<Line> lines = new ArrayList<>();
        private int pageNo = 1;

        LineStripper() {
        }

        void extract(PDDocument doc) throws IOException {
            getText(doc);
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            pageNo = getCurrentPageNo();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (text == null || text.strip().isEmpty()) {
                return;
            }
            Line line = new Line();
            line.text = text.strip();
            line.page = pageNo;
            float maxSize = 0f;
            boolean bold = false;
            if (!positions.isEmpty()) {
                TextPosition first = positions.get(0);
                line.x = first.getXDirAdj();
                line.y = first.getYDirAdj();
                for (TextPosition tp : positions) {
                    maxSize = Math.max(maxSize, tp.getFontSizeInPt());
                    String font = tp.getFont() != null ? tp.getFont().getName() : "";
                    if (font != null && font.toLowerCase().contains("bold")) {
                        bold = true;
                    }
                }
            }
            line.size = maxSize;
            line.bold = bold;
            lines.add(line);
        }
    }
}
