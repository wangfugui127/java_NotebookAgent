package com.wangfugui.notebookagent.retrieval;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SearchHit {
    private String chunkId;
    private String text;
    private double score;
    private String documentId;
    private String documentVersionId;
    private String title;
    private Integer charStart;
    private Integer charEnd;
    private Integer pageStart;
    private Integer pageEnd;
    private String sectionId;
    private String sectionTitle;
    private Integer ordinal;
    private String chunkType;
    private List<String> blockIds = new ArrayList<>();
    private List<String> sources = new ArrayList<>();
}
