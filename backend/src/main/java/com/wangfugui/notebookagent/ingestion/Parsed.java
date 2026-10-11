package com.wangfugui.notebookagent.ingestion;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Parsed document structures shared by parser / chunker. */
public final class Parsed {

    @Getter
    @Setter
    public static class Block {
        private String type;
        private String text;
        private String markdown;
        private Integer page;
        private int charStart;
        private int charEnd;
        private List<Double> bbox = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Page {
        private int number;
        private int charStart;
        private int charEnd;
        private String text;
    }

    @Getter
    @Setter
    public static class Document {
        private String text;
        private String markdown;
        private List<Page> pages = new ArrayList<>();
        private List<Block> blocks = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class SectionDraft {
        private String title;
        private int ordinal;
        private int charStart;
        private int charEnd;
        private Integer pageStart;
        private Integer pageEnd;
    }

    @Getter
    @Setter
    public static class ChunkDraft {
        private String content;
        private String contentHash;
        private String chunkType;
        private int ordinal;
        private int charStart;
        private int charEnd;
        private Integer pageStart;
        private Integer pageEnd;
        private List<String> blockIds = new ArrayList<>();
    }

    private Parsed() {
    }
}
