package com.wangfugui.notebookagent.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Section detection and length-bounded chunking. */
public final class Chunker {

    public static final int MAX_CHARS = 4000;
    public static final int OVERLAP_CHARS = 300;

    public static List<Parsed.SectionDraft> detectSections(Parsed.Document doc) {
        List<Parsed.SectionDraft> sections = new ArrayList<>();
        List<Parsed.Block> headings = new ArrayList<>();
        for (Parsed.Block block : doc.getBlocks()) {
            if ("heading".equals(block.getType()) && !block.getText().isBlank() && block.getText().length() <= 64) {
                headings.add(block);
            }
        }
        if (headings.isEmpty() || headings.size() > 64) {
            Parsed.SectionDraft only = new Parsed.SectionDraft();
            only.setTitle("正文");
            only.setOrdinal(0);
            only.setCharStart(0);
            only.setCharEnd(doc.getText().length());
            Integer[] pages = pagesFor(doc, only.getCharStart(), only.getCharEnd());
            only.setPageStart(pages[0]);
            only.setPageEnd(pages[1]);
            sections.add(only);
            return sections;
        }
        if (headings.get(0).getCharStart() > 0) {
            Parsed.SectionDraft preface = new Parsed.SectionDraft();
            preface.setTitle("前言");
            preface.setOrdinal(0);
            preface.setCharStart(0);
            preface.setCharEnd(headings.get(0).getCharStart());
            Integer[] pages = pagesFor(doc, preface.getCharStart(), preface.getCharEnd());
            preface.setPageStart(pages[0]);
            preface.setPageEnd(pages[1]);
            sections.add(preface);
        }
        for (int i = 0; i < headings.size(); i++) {
            Parsed.Block heading = headings.get(i);
            int end = i + 1 < headings.size() ? headings.get(i + 1).getCharStart() : doc.getText().length();
            Parsed.SectionDraft section = new Parsed.SectionDraft();
            section.setTitle(heading.getText());
            section.setOrdinal(sections.size());
            section.setCharStart(heading.getCharStart());
            section.setCharEnd(Math.max(end, heading.getCharEnd()));
            Integer[] pages = pagesFor(doc, section.getCharStart(), section.getCharEnd());
            section.setPageStart(pages[0]);
            section.setPageEnd(pages[1]);
            sections.add(section);
        }
        return sections;
    }

    public static List<Parsed.ChunkDraft> chunkDocument(Parsed.Document doc) {
        List<Parsed.ChunkDraft> drafts = new ArrayList<>();
        List<Parsed.SectionDraft> sections = detectSections(doc);
        int ordinal = 0;
        for (Parsed.SectionDraft section : sections) {
            String text = doc.getText();
            int start = Math.min(section.getCharStart(), text.length());
            int end = Math.min(section.getCharEnd(), text.length());
            if (end <= start) {
                continue;
            }
            int localStart = start;
            while (localStart < end) {
                int hardEnd = Math.min(localStart + MAX_CHARS, end);
                int chunkEnd = hardEnd;
                if (hardEnd < end) {
                    int boundary = text.lastIndexOf("\n\n", hardEnd);
                    if (boundary < localStart + 2000) {
                        boundary = text.lastIndexOf('。', hardEnd);
                    }
                    if (boundary > localStart + 2000) {
                        chunkEnd = boundary + 1;
                    }
                }
                String content = text.substring(localStart, chunkEnd);
                Parsed.ChunkDraft draft = new Parsed.ChunkDraft();
                draft.setContent(content);
                draft.setContentHash(sha256(content));
                draft.setChunkType(inferChunkType(content));
                draft.setOrdinal(ordinal++);
                draft.setCharStart(localStart);
                draft.setCharEnd(chunkEnd);
                Integer[] pages = pagesFor(doc, localStart, chunkEnd);
                draft.setPageStart(pages[0]);
                draft.setPageEnd(pages[1]);
                drafts.add(draft);
                if (chunkEnd >= end) {
                    break;
                }
                localStart = Math.max(localStart + 1, chunkEnd - OVERLAP_CHARS);
            }
        }
        return drafts;
    }

    private static Integer[] pagesFor(Parsed.Document doc, int start, int end) {
        Integer first = null, last = null;
        for (Parsed.Page page : doc.getPages()) {
            if (page.getCharEnd() > start && page.getCharStart() < end) {
                first = first == null ? page.getNumber() : Math.min(first, page.getNumber());
                last = last == null ? page.getNumber() : Math.max(last, page.getNumber());
            }
        }
        return new Integer[]{first, last};
    }

    static String inferChunkType(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("|") || (trimmed.lines().filter(l -> l.contains("|")).count() >= 2
                && trimmed.lines().count() > 0
                && trimmed.lines().filter(l -> l.contains("|")).count() * 2 >= trimmed.lines().count())) {
            return "table";
        }
        if (trimmed.length() <= 400 && (trimmed.contains("=") || trimmed.contains("∑") || trimmed.contains("∫"))) {
            return "formula";
        }
        return "paragraph";
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Chunker() {
    }
}
