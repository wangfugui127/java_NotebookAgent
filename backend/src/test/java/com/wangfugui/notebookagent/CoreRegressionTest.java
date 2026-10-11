package com.wangfugui.notebookagent;

import com.wangfugui.notebookagent.agent.*;
import com.wangfugui.notebookagent.core.*;
import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.ingestion.*;
import com.wangfugui.notebookagent.providers.*;
import com.wangfugui.notebookagent.retrieval.*;
import com.wangfugui.notebookagent.evaluation.Scorers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreRegressionTest {
    @Test void exposureConfigurationCanDisableAndRestrictTools(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file=dir.resolve("expose.yaml"); java.nio.file.Files.writeString(file,"enabled: false\npath: /research-mcp\nallowed_tools: [search_notebook]\n");
        var exposure=com.wangfugui.notebookagent.mcp.McpExposeServer.readExposure(file.toString());
        assertThat(exposure.enabled()).isFalse(); assertThat(exposure.path()).isEqualTo("/research-mcp"); assertThat(exposure.tools()).containsExactly("search_notebook");
    }
    @Test void graphExtractionProviderFailureIsNotReportedAsReady() {
        var provider=mock(DeepSeekProvider.class); when(provider.invoke(anyList(),anyList(),any())).thenThrow(new IllegalStateException("down"));
        assertThatThrownBy(()->GraphExtractor.extractSection("section",List.of("text"),provider)).hasMessageContaining("graph extraction failed");
    }

    @Test void schemasAcceptDefaultsButRejectWrongTypesAndMissingArguments() {
        ToolRegistry registry=new ToolRegistry();NativeTools.build(registry);
        assertThat(registry.argumentsError("list_notebook_sources",Map.of())).isNull();
        assertThat(registry.argumentsError("search_notebook",Map.of("query","test"))).isNull();
        assertThat(registry.argumentsError("search_notebook",Map.of("query","test","top_k","bad"))).isNotNull();
        assertThat(registry.argumentsError("remove_notebook_source",Map.of())).isNotNull();
        assertThat(registry.argumentsError("missing",Map.of())).isNotNull();
        assertThat(registry.schemas(List.of("search_notebook","search_notebook"))).hasSize(1);
    }
    @Test void attachmentWindowsCoverEntireTextWithinMeasuredBudget() {
        String text="全文窗口验证：没有任何字符被丢弃。".repeat(600);
        List<Map<String,Object>> base=List.of(Map.of("role","system","content","test"),Map.of("role","user","content","分析"));
        var packets=ContextBuilder.withAttachments(base,"分析",List.of(Map.of("document_id","doc","document_version_id","version","name","sample","instruction","逐字检查","text",text)),900);
        assertThat(packets.size()).isGreaterThan(1);StringBuilder recovered=new StringBuilder();
        for(var packet:packets) {
            assertThat(ContextBuilder.messagesTokens(packet)).isLessThanOrEqualTo(900);
            String body=String.valueOf(packet.getLast().get("content"));assertThat(body).contains("逐字检查");
            recovered.append(body.substring(body.indexOf("完整正文:\n")+6,body.lastIndexOf("\n--- 附件结束 ---")));
        }
        assertThat(recovered.toString()).isEqualTo(text);
    }
    private static ProviderResponse response(String text) { return new ProviderResponse(text, List.of(), Map.of(), Map.of()); }
    @Test void scryptRoundTripAndWrongPassword() {
        String encoded = PasswordHasher.hash("研究密码123");
        assertThat(PasswordHasher.verify("研究密码123", encoded)).isTrue();
        assertThat(PasswordHasher.verify("wrong", encoded)).isFalse();
        assertThat(PasswordHasher.verify("wrong", "invalid")).isFalse();
    }
    @Test void tokensUseHs256EvenWithLongSecretsAndCannotMixTypes() {
        AppProperties props = new AppProperties(); props.setSecretKey("x".repeat(100));
        TokenService tokens = new TokenService(props); String access = tokens.accessToken("user-id");
        assertThat(new String(Base64.getUrlDecoder().decode(access.split("\\.")[0]), StandardCharsets.UTF_8)).contains("HS256");
        assertThat(tokens.decode(access, "access").getSubject()).isEqualTo("user-id");
        assertThatThrownBy(() -> tokens.decode(access, "refresh")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.decode(access.substring(0, access.length()-10), "access")).isInstanceOf(RuntimeException.class);
        assertThat(tokens.refreshToken("user-id")).isNotEqualTo(tokens.refreshToken("user-id"));
    }
    @Test void nestedStateRoundTripKeepsMutableEvidenceAndAllowlist() {
        AgentState state = new AgentState(); state.setRoundNo(2); state.getEvidenceIds().add("e1"); state.getActiveToolAllowlist().add("tool");
        AgentState restored = AgentState.fromMap(Json.readMap(Json.write(state.toMap())));
        restored.getEvidenceIds().add("e2"); restored.getActiveToolAllowlist().add("new");
        assertThat(restored.getRoundNo()).isEqualTo(2); assertThat(restored.getEvidenceIds()).containsExactly("e1", "e2");
    }
    @Test void toolPolicyAndAliasAreStable() {
        ToolRegistry registry = new ToolRegistry(); NativeTools.build(registry);
        assertThat(registry.decide("search_notebook", "read_only")).isEqualTo("allow");
        assertThat(registry.decide("remove_notebook_source", "read_only")).isEqualTo("deny");
        assertThat(registry.decide("remove_notebook_source", "confirm")).isEqualTo("approve");
        assertThat(registry.decide("remove_notebook_source", "auto")).isEqualTo("allow");
        String name = "mcp." + "long".repeat(30) + ".search";
        assertThat(registry.providerName(name)).hasSizeLessThanOrEqualTo(64);
        assertThat(registry.resolveProviderName(registry.providerName(name))).isEqualTo(name);
        assertThat(ToolRegistry.argumentsHash("t", Map.of("b", 2, "a", 1))).isEqualTo(ToolRegistry.argumentsHash("t", Map.of("a", 1, "b", 2)));
    }
    @ParameterizedTest @ValueSource(strings={"rag", "no_rag", "uncertain"})
    void legacyRagRouteRemainsCompatible(String route) {
        DeepSeekProvider provider = mock(DeepSeekProvider.class); when(provider.invoke(anyList(), anyList(), any())).thenReturn(response("{\"route\":\"" + route + "\"}"));
        assertThat(new RagRouter(provider).route("question").route()).isEqualTo(route);
    }
    @Test void ragRouterRejectsBadGraphButKeepsVectorRoute() {
        DeepSeekProvider provider = mock(DeepSeekProvider.class);
        when(provider.invoke(anyList(), anyList(), any())).thenReturn(response("{\"route\":\"rag\",\"use_graph\":true,\"entities\":[{\"name\":\"x\",\"kind\":\"bad\"}],\"intents\":[\"method\"]}"));
        RagDecision decision = new RagRouter(provider).route("q"); assertThat(decision.route()).isEqualTo("rag"); assertThat(decision.graph().useGraph()).isFalse();
    }
    @Test void ragProviderFailureIsUncertain() {
        DeepSeekProvider provider = mock(DeepSeekProvider.class); when(provider.invoke(anyList(), anyList(), any())).thenThrow(new RuntimeException("down"));
        assertThat(new RagRouter(provider).route("q").route()).isEqualTo("uncertain");
    }
    @Test void sanitizerDropsOrphansAndFillsExactlyTheMissingResponses() {
        List<Map<String,Object>> input = List.of(Map.of("role","tool","tool_call_id","orphan","content","x"),
                Map.of("role","assistant","tool_calls",List.of(Map.of("id","a"),Map.of("id","b"))),
                Map.of("role","tool","tool_call_id","a","content","A"),
                Map.of("role","tool","tool_call_id","a","content","duplicate"), Map.of("role","user","content","next"));
        List<Map<String,Object>> out=Compactor.sanitize(input);
        assertThat(out.stream().filter(m -> "tool".equals(m.get("role"))).map(m -> m.get("tool_call_id"))).containsExactly("a","b");
        assertThat(out.get(2).get("content")).isEqualTo(Compactor.TOOL_INCOMPLETE);
    }
    @Test void smallContextIsUntouchedAndCleanupDoesNotMutateInput() {
        List<Map<String,Object>> input=List.of(Map.of("role","user","content","q"),Map.of("role","tool","content","large"));
        boolean[] cleaned={false}; assertThat(Compactor.compact(input,6,false,1000,null,cleaned)).isEqualTo(input); assertThat(cleaned[0]).isFalse();
        assertThat(Compactor.clearOldToolResults(input,0).get(1).get("content")).isEqualTo(Compactor.TOOL_RESULT_PLACEHOLDER);
        assertThat(input.get(1).get("content")).isEqualTo("large");
    }
    @Test void oversizeRecentContextFailsWithoutSilentlyDroppingIt() {
        assertThatThrownBy(() -> Compactor.compact(List.of(Map.of("role","user","content","中".repeat(1000))),6,false,20,null,new boolean[]{false})).hasMessageContaining("budget");
    }
    @Test void textParsingChunkingCoverAllCharactersAndPreserveOffsets() {
        String text="# 1 Introduction\n" + "research vegetation " .repeat(600) + "\n## 2 Results\nNDVI recovers.";
        Parsed.Document parsed=new Parser().parse(text.getBytes(StandardCharsets.UTF_8),"text/markdown",".md");
        List<Parsed.ChunkDraft> chunks=Chunker.chunkDocument(parsed);
        assertThat(chunks).isNotEmpty(); assertThat(chunks.getFirst().getCharStart()).isZero();
        assertThat(chunks.getLast().getCharEnd()).isEqualTo(parsed.getText().length());
        for (var chunk:chunks) assertThat(chunk.getContent()).isEqualTo(parsed.getText().substring(chunk.getCharStart(),chunk.getCharEnd()));
        assertThat(Chunker.detectSections(parsed).size()).isGreaterThanOrEqualTo(2);
    }
    @Test void binaryUploadIsRejected() {
        assertThatThrownBy(() -> new Parser().parse(new byte[]{(byte)0xff,0},"application/octet-stream","")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void profilesPreserveHeuristicAbstractAndEnforceBudget() {
        String text="Abstract\nWe reconstruct seasonal NDVI curves.\nKeywords: NDVI; vegetation\n1 Introduction\nBody";
        Map<String,Object> profile=ProfileExtractor.build("Paper",text,null,1000);
        assertThat(profile.get("abstract_source")).isEqualTo("original"); assertThat(profile.get("abstract").toString()).contains("NDVI");
        assertThatThrownBy(() -> ProfileExtractor.build("Paper","中".repeat(10000),mock(DeepSeekProvider.class),100)).hasMessageContaining("budget");
    }
    @Test void rrfDeduplicatesAndTracksBothRetrievalSources() {
        SearchHit a=new SearchHit();a.setChunkId("a"); SearchHit b=new SearchHit();b.setChunkId("b");
        List<SearchHit> fused=Fusion.reciprocalRankFusion(Map.of("dense",List.of(a,b),"bm25",List.of(a)),60,5);
        assertThat(fused).hasSize(2);assertThat(fused.getFirst().getSources()).containsExactly("bm25","dense");
        assertThat(fused.getFirst().getScore()).isCloseTo(2.0/61,within(0.000001));
    }
    @Test void scoringRequiresCompletedRunAndConfiguredDimensions() {
        var good=Scorers.scoreCase(List.of("search_notebook"),List.of("search_notebook"),List.of("Paper"),List.of("Paper title"),List.of("Paper title"),List.of(),List.of("NDVI"),"NDVI", "completed",Scorers.DEFAULT_SCORE_CONFIG);
        assertThat(good.get("passed")).isEqualTo(true); assertThat(good.get("total_score")).isEqualTo(100.0);
        var empty=Scorers.scoreCase(List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"answer","completed",Scorers.DEFAULT_SCORE_CONFIG);
        assertThat(empty.get("passed")).isEqualTo(false);assertThat(empty.get("total_score")).isNull();
    }
}
