package com.wangfugui.spike.pdf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Phase 0 probe: parse sample PDFs with PDFBox 3 and report structural metrics
 * (pages, text length, font-size based heading candidates, ruled-line table estimate)
 * so they can be diffed against the PyMuPDF reference (out/pymupdf_*.json).
 */
public final class PdfProbe {

    private static final Pattern CAPTION = Pattern.compile("(?i)^(figure|table|fig\\.?|tab\\.?|图|表)\\s*\\d*");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(?:\\d+(?:\\.\\d+){0,3}|[A-Z](?:\\.\\d+){0,3})[.、]?\\s+");

    public static void main(String[] args) throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        Path samples = base.resolve("samples");
        Path out = base.resolve("out");
        Files.createDirectories(out);
        try (Stream<Path> files = Files.list(samples)) {
            for (Path pdf : files.filter(p -> p.toString().endsWith(".pdf")).sorted().toList()) {
                analyze(pdf, out);
            }
        }
    }

    private static void analyze(Path pdf, Path out) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", pdf.getFileName().toString());
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int pages = doc.getNumberOfPages();
            result.put("pages", pages);

            // Pass 1: body font size by weighted character count.
            FontStripper pass1 = new FontStripper();
            pass1.setSortByPosition(true);
            pass1.getText(doc);
            float body = pass1.bodyFontSize();
            System.out.printf("[pdfbox] %s top font sizes (size=chars): %s%n",
                    pdf.getFileName(), pass1.topWeights(5));

            // Pass 2: heading candidates using the body font size.
            FontStripper pass2 = new FontStripper(body);
            pass2.setSortByPosition(true);
            String text = pass2.getText(doc);
            List<Map<String, Object>> headings = pass2.headings;

            result.put("text_len", text.length());
            result.put("body_font_size", body);
            result.put("heading_count", headings.size());
            result.put("headings", headings);

            // Ruled-line based table estimate per page (PDFBox has no native table detector).
            List<Map<String, Object>> ruled = new ArrayList<>();
            int tableLikePages = 0;
            for (int i = 0; i < pages; i++) {
                try {
                    LineCollector lc = new LineCollector(doc.getPage(i));
                    lc.processPage(doc.getPage(i));
                    int h = lc.horizontalLines();
                    int v = lc.verticalLines();
                    if (h >= 4 && v >= 2) {
                        tableLikePages++;
                    }
                    if (h > 0 || v > 0) {
                        ruled.add(Map.of("page", i + 1, "horizontal", h, "vertical", v));
                    }
                } catch (Exception e) {
                    ruled.add(Map.of("page", i + 1, "error", String.valueOf(e.getMessage())));
                }
            }
            result.put("table_like_pages", tableLikePages);
            result.put("ruled_lines", ruled);
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(out.resolve("pdfbox_" + stem(pdf) + ".json"),
                gson.toJson(result), StandardCharsets.UTF_8);
        System.out.printf("[pdfbox] %s: pages=%s text_len=%s body=%s headings=%s table_like_pages=%s%n",
                pdf.getFileName(), result.get("pages"), result.get("text_len"),
                result.get("body_font_size"), result.get("heading_count"), result.get("table_like_pages"));
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.length() - 4);
    }

    /** Text stripper that records font-size weights and (optionally) heading candidates. */
    private static final class FontStripper extends PDFTextStripper {
        private final Map<Float, Long> weights = new TreeMap<>();
        private final List<Map<String, Object>> headings = new ArrayList<>();
        private final Float body;
        private int pageNo = 1;

        FontStripper() {
            this(null);
        }

        FontStripper(Float body) {
            this.body = body;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            this.pageNo = getCurrentPageNo();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            super.writeString(text, positions);
            if (positions.isEmpty()) {
                return;
            }
            float maxSize = 0f;
            boolean bold = false;
            for (TextPosition tp : positions) {
                float size = Math.round(tp.getFontSizeInPt() * 2f) / 2f;
                weights.merge(size, (long) tp.getUnicode().length(), Long::sum);
                if (size > maxSize) {
                    maxSize = size;
                }
                String font = tp.getFont() != null ? tp.getFont().getName() : "";
                if (font != null && font.toLowerCase().contains("bold")) {
                    bold = true;
                }
            }
            if (body != null) {
                String kind = classify(text, maxSize, body, bold);
                if (kind.startsWith("h")) {
                    headings.add(Map.of(
                            "page", pageNo,
                            "level", kind,
                            "size", maxSize,
                            "text", text.strip().length() > 90 ? text.strip().substring(0, 90) : text.strip()));
                }
            }
        }

        float bodyFontSize() {
            if (weights.isEmpty()) {
                return 10f;
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

        String topWeights(int n) {
            return weights.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(n)
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }
    }

    private static String classify(String text, float size, float body, boolean bold) {
        String t = text.strip();
        if (t.isEmpty()) {
            return "empty";
        }
        if (CAPTION.matcher(t).find()) {
            return "figure_caption";
        }
        if (t.length() > 100 || t.split("\\s+").length > 16
                || t.endsWith(".") || t.endsWith("。") || t.endsWith(";") || t.endsWith("；")
                || t.endsWith(",") || t.endsWith("，") || t.endsWith(":") || t.endsWith("：")) {
            return "paragraph";
        }
        float ratio = body == 0f ? 1f : size / body;
        if (ratio >= 1.5f) {
            return "h1";
        }
        if (ratio >= 1.25f) {
            return "h2";
        }
        if (ratio >= 1.1f) {
            return "h3";
        }
        if (NUMBERED.matcher(t).find()) {
            return "h4";
        }
        if (bold && ratio >= 1.05f && t.length() <= 60) {
            return "h4";
        }
        return "paragraph";
    }

    /** Collects stroked line segments to estimate ruled tables. */
    private static final class LineCollector extends PDFGraphicsStreamEngine {
        private final List<float[]> segments = new ArrayList<>();
        private float curX;
        private float curY;
        private boolean hasCur;

        LineCollector(PDPage page) {
            super(page);
        }

        @Override
        public void appendRectangle(java.awt.geom.Point2D p0, java.awt.geom.Point2D p1,
                                    java.awt.geom.Point2D p2, java.awt.geom.Point2D p3) {
            // rectangles are handled by lineTo segments
        }

        @Override
        public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage image) {
            // no-op
        }

        @Override
        public void clip(int windingRule) {
            // no-op
        }

        @Override
        public void moveTo(float x, float y) {
            curX = x;
            curY = y;
            hasCur = true;
        }

        @Override
        public void lineTo(float x, float y) {
            if (hasCur) {
                segments.add(new float[] {curX, curY, x, y});
            }
            curX = x;
            curY = y;
            hasCur = true;
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            curX = x3;
            curY = y3;
            hasCur = true;
        }

        @Override
        public java.awt.geom.Point2D getCurrentPoint() {
            return new java.awt.geom.Point2D.Float(curX, curY);
        }

        @Override
        public void closePath() {
            // no-op
        }

        @Override
        public void endPath() {
            hasCur = false;
        }

        @Override
        public void strokePath() {
            hasCur = false;
        }

        @Override
        public void fillPath(int windingRule) {
            hasCur = false;
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            hasCur = false;
        }

        @Override
        public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) {
            // no-op
        }

        int horizontalLines() {
            int n = 0;
            for (float[] s : segments) {
                if (Math.abs(s[1] - s[3]) < 1.0f && Math.abs(s[2] - s[0]) > 50f) {
                    n++;
                }
            }
            return n;
        }

        int verticalLines() {
            int n = 0;
            for (float[] s : segments) {
                if (Math.abs(s[0] - s[2]) < 1.0f && Math.abs(s[3] - s[1]) > 20f) {
                    n++;
                }
            }
            return n;
        }
    }

    private PdfProbe() {
    }
}
