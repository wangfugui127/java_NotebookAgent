package com.wangfugui.notebookagent;

import com.wangfugui.notebookagent.agent.*;
import com.wangfugui.notebookagent.api.AgentRunsController;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.Chunk;
import com.wangfugui.notebookagent.evaluation.Scorers;
import com.wangfugui.notebookagent.ingestion.*;
import com.wangfugui.notebookagent.providers.*;
import com.wangfugui.notebookagent.repository.*;
import com.wangfugui.notebookagent.retrieval.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundaryRegressionTest {
    @Test void pdfChunksHaveActualPageBoundsEvenWithBlankPagesAndOneSection() throws Exception {
        byte[] bytes;
        try (PDDocument pdf=new PDDocument(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            for (int n=1;n<=3;n++) {
                PDPage page=new PDPage();pdf.addPage(page);
                if(n==2)continue;
                try (PDPageContentStream text=new PDPageContentStream(pdf,page)) {
                    text.beginText();text.newLineAtOffset(30,750);
                    if(n==1) { text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),18);text.showText("Introduction");text.newLineAtOffset(0,-25); }
                    text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                    for(int i=0;i<45;i++) { text.showText("Page " + n + " line " + i + " provides precise evidence for document citations.");text.newLineAtOffset(0,-14); }
                    text.endText();
                }
            }
            pdf.save(output);bytes=output.toByteArray();
        }
        var parsed=new Parser().parse(bytes,"application/pdf",".pdf");
        assertThat(parsed.getPages()).extracting(Parsed.Page::getNumber).containsExactly(1,3);
        var section=Chunker.detectSections(parsed).getFirst();
        assertThat(section.getPageStart()).isEqualTo(1);assertThat(section.getPageEnd()).isEqualTo(3);
        var chunks=Chunker.chunkDocument(parsed);
        assertThat(chunks).anySatisfy(c -> { assertThat(c.getPageStart()).isEqualTo(3);assertThat(c.getPageEnd()).isEqualTo(3); });
        for(var chunk:chunks) {
            var pages=parsed.getPages().stream().filter(p -> p.getCharEnd()>chunk.getCharStart() && p.getCharStart()<chunk.getCharEnd()).toList();
            assertThat(chunk.getPageStart()).isEqualTo(pages.getFirst().getNumber());
            assertThat(chunk.getPageEnd()).isEqualTo(pages.getLast().getNumber());
        }
    }
    @Test void scoringNormalizesBlanksAndDuplicatesAndMergesPartialConfiguration() {
        var score=Scorers.scoreCase(List.of(),List.of(),List.of(" A ","A"," "),List.of("A"),List.of(" A "),List.of(" "),List.of(),"","completed",Map.of("pass_threshold",90.0));
        assertThat(score.get("total_score")).isEqualTo(100.0);assertThat(score.get("status")).isEqualTo("passed");
        var blank=Scorers.scoreCase(List.of(),List.of(),List.of("A"),List.of(" "),List.of(""),List.of(),List.of(),"","completed",null);
        assertThat(blank.get("total_score")).isEqualTo(0.0);
        var none=Scorers.scoreCase(List.of(" "),List.of(),List.of(""),List.of(),List.of(),List.of(),List.of(" "),"","completed",null);
        assertThat(none.get("total_score")).isNull();assertThat(none.get("status")).isEqualTo("error");
    }
    @Test void documentRoutingFailureKeepsChunkEvidenceAndDoesNotHideChunkFailures() {
        var milvus=mock(MilvusStore.class);var provider=mock(SiliconFlowProvider.class);var chunks=mock(ChunkRepository.class);
        when(provider.embed(anyList())).thenReturn(List.of(new float[]{1}));
        when(milvus.searchDocuments(anyString(),any(),anyString(),isNull(),anyInt())).thenThrow(new IllegalStateException("profile collection down"));
        when(milvus.hybridSearch(anyString(),any(),anyString(),isNull(),isNull(),anyInt())).thenReturn(List.of(new MilvusStore.MilvusHit("chunk",1)));
        Chunk chunk=new Chunk();chunk.setId("chunk");chunk.setContent("actual chunk evidence");chunk.setDocumentVersionId("version");chunk.setBlockIds("[]");
        when(chunks.findByIdInAndIsActiveTrue(anyList())).thenReturn(List.of(chunk));
        var service=new RetrievalService(milvus,mock(Neo4jStore.class),provider,null,chunks,mock(SectionRepository.class),mock(DocumentVersionRepository.class),mock(DocumentRepository.class));
        var result=service.search("notebook","query",null,"layered",5,GraphPlan.none());
        assertThat(result.get("layered_degraded")).isEqualTo(true);assertThat((List<?>)result.get("items")).hasSize(1);
        when(milvus.hybridSearch(anyString(),any(),anyString(),isNull(),isNull(),anyInt())).thenThrow(new IllegalStateException("chunk collection down"));
        assertThatThrownBy(() -> service.search("notebook","query",null,"layered",5,GraphPlan.none())).hasMessage("chunk collection down");
    }
    @Test void reducerProcessesAllWindowsWithinBudgetAndKeepsEveryReference() {
        var provider=mock(DeepSeekProvider.class);AtomicInteger calls=new AtomicInteger();
        when(provider.invoke(anyList(),anyList(),any())).thenAnswer(a -> {
            List<Map<String,Object>> packet=a.getArgument(0);assertThat(ContextBuilder.messagesTokens(packet)).isLessThanOrEqualTo(4500);calls.incrementAndGet();
            var matcher=java.util.regex.Pattern.compile("\\[evidence:[^\\]]+\\]").matcher(Json.write(packet));Set<String> refs=new LinkedHashSet<>();while(matcher.find())refs.add(matcher.group());
            return new ProviderResponse("综合结论"+"压缩结论".repeat(60)+String.join(" ",refs),List.of(),Map.of(),Map.of());
        });
        List<Map<String,Object>> items=new ArrayList<>();
        for(int i=0;i<36;i++) items.add(Map.of("manifest",Map.of("index",i,"char_start",i*100,"char_end",(i+1)*100),"conclusion","[evidence:"+i+"] "+"详细结论".repeat(450)));
        var result=WindowReducer.reduce("综合所有附件",items,4500,provider);
        String reduced=Json.write(result);assertThat(ContextBuilder.estimateTokens(reduced)).isLessThanOrEqualTo(2925);
        for(int i=0;i<36;i++)assertThat(reduced).contains("[evidence:"+i+"]", "\"index\":"+i);
        assertThat(calls.get()).isGreaterThan(36);
    }
    @Test void reducerRejectsNonShrinkingOutputAndLostReferences() {
        var provider=mock(DeepSeekProvider.class);
        var input=List.<Map<String,Object>>of(Map.of("manifest",Map.of("index",0),"conclusion","[evidence:x] "+"长".repeat(2800)));
        when(provider.invoke(anyList(),anyList(),any())).thenReturn(new ProviderResponse("omitted",List.of(),Map.of(),Map.of()));
        assertThatThrownBy(() -> WindowReducer.reduce("task",input,4000,provider)).hasMessageContaining("omitted an evidence");
        when(provider.invoke(anyList(),anyList(),any())).thenReturn(new ProviderResponse("[evidence:x] "+"长".repeat(3900),List.of(),Map.of(),Map.of()));
        assertThatThrownBy(() -> WindowReducer.reduce("task",input,4000,provider)).hasMessageContaining("cannot be reduced");
    }
    @Test void embeddingRouterCachesCatalogRanksExternalToolsAndFallsBackOffline() {
        AtomicInteger catalogCalls=new AtomicInteger();
        var router=new EmbeddingToolRouter(texts -> { if(texts.size()==2){catalogCalls.incrementAndGet();return List.of(new float[]{0,1},new float[]{1,0});}return List.of(new float[]{1,0}); });
        var catalog=List.of(new ToolDefinition("task_update","更新任务",Map.of(),"read","native",null),new ToolDefinition("mcp.search","外部论文",Map.of(),"read","mcp",null));
        assertThat(router.select("论文",catalog,1)).containsExactly("mcp.search");router.select("other",catalog,1);assertThat(catalogCalls.get()).isEqualTo(1);
        var offline=new EmbeddingToolRouter(texts -> {throw new IllegalStateException("offline");});
        assertThat(offline.select("更新计划进度",catalog,1)).containsExactly("task_update");
    }
    @Test void sseHeaderHasPriorityAndInvalidCursorsAreRejected() {
        assertThat(AgentRunsController.resumeCursor(" 9 ",2)).isEqualTo(9);
        assertThat(AgentRunsController.resumeCursor(null,2)).isEqualTo(2);
        assertThatThrownBy(() -> AgentRunsController.resumeCursor("bad",2)).hasMessageContaining("invalid event cursor");
        assertThatThrownBy(() -> AgentRunsController.resumeCursor("-1",2)).hasMessageContaining("invalid event cursor");
    }
}
