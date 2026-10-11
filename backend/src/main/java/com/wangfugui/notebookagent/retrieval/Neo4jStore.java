package com.wangfugui.notebookagent.retrieval;

import com.wangfugui.notebookagent.config.AppProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Neo4j graph store: Paper -> DocumentVersion -> Section/Chunk plus semantic entities. */
public class Neo4jStore {

    private static final Set<String> RELATION_TYPES = Set.of(
            "USES_METHOD", "USES_DATASET", "REPORTS_RESULT", "MEASURES", "CITES", "COMPARES_WITH", "RELATED_TO");

    private final AppProperties.Neo4j config;
    private Driver driver;

    public Neo4jStore(AppProperties props) {
        this.config = props.getNeo4j();
        this.driver = null;
    }

    private synchronized Driver driver() {
        if (driver == null) {
            driver = GraphDatabase.driver(config.getUri(),
                    org.neo4j.driver.AuthTokens.basic(config.getUser(), config.getPassword()));
        }
        return driver;
    }

    public void close() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    public boolean verify() {
        try {
            driver().verifyConnectivity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void createConstraints() {
        try (Session session = driver().session()) {
            for (String cypher : List.of(
                    "CREATE CONSTRAINT paper_id IF NOT EXISTS FOR (n:Paper) REQUIRE n.id IS UNIQUE",
                    "CREATE CONSTRAINT document_version_id IF NOT EXISTS FOR (n:DocumentVersion) REQUIRE n.id IS UNIQUE",
                    "CREATE CONSTRAINT chunk_id IF NOT EXISTS FOR (n:Chunk) REQUIRE n.id IS UNIQUE",
                    "CREATE CONSTRAINT entity_key IF NOT EXISTS FOR (n:Entity) REQUIRE n.key IS UNIQUE",
                    "CREATE CONSTRAINT section_id IF NOT EXISTS FOR (n:Section) REQUIRE n.id IS UNIQUE")) {
                session.run(cypher);
            }
        }
    }

    public void upsertChunk(String notebookId, String documentVersionId, String documentId, String paperTitle,
                            String chunkId, Map<String, Object> section, List<Map<String, Object>> entities,
                            List<Map<String, Object>> relations) {
        String nodeCypher = """
                MERGE (p:Paper {id: $paper_id})
                SET p.title = $paper_title, p.notebook_id = $notebook_id
                MERGE (d:DocumentVersion {id: $document_version_id})
                SET d.notebook_id = $notebook_id
                MERGE (p)-[:HAS_VERSION]->(d)
                MERGE (c:Chunk {id: $chunk_id})
                SET c.notebook_id = $notebook_id
                MERGE (d)-[:HAS_CHUNK]->(c)
                MERGE (s:Section {id: $section_id})
                SET s.title = $section_title, s.notebook_id = $notebook_id,
                    s.document_version_id = $document_version_id
                MERGE (d)-[:HAS_SECTION]->(s)
                MERGE (s)-[:HAS_CHUNK]->(c)
                WITH p, c, s
                UNWIND $entities AS entity
                MERGE (e:Entity {key: $notebook_id + ':' + entity.key})
                SET e.name = entity.name, e.kind = entity.kind, e.notebook_id = $notebook_id
                FOREACH (_ IN CASE WHEN entity.kind = 'Experiment' THEN [1] ELSE [] END | SET e:Experiment)
                FOREACH (_ IN CASE WHEN entity.kind = 'Method' THEN [1] ELSE [] END | SET e:Method)
                FOREACH (_ IN CASE WHEN entity.kind = 'Dataset' THEN [1] ELSE [] END | SET e:Dataset)
                FOREACH (_ IN CASE WHEN entity.kind = 'Metric' THEN [1] ELSE [] END | SET e:Metric)
                FOREACH (_ IN CASE WHEN entity.kind = 'Result' THEN [1] ELSE [] END | SET e:Result)
                FOREACH (_ IN CASE WHEN entity.kind = 'Paper' THEN [1] ELSE [] END | SET e:Paper)
                FOREACH (_ IN CASE WHEN entity.kind = 'Experiment' THEN [1] ELSE [] END | MERGE (p)-[:HAS_EXPERIMENT]->(e))
                """;
        try (Session session = driver().session()) {
            session.run(nodeCypher, Values.parameters(
                    "paper_id", documentVersionId,
                    "paper_title", paperTitle == null ? "" : paperTitle,
                    "notebook_id", notebookId,
                    "document_version_id", documentVersionId,
                    "chunk_id", chunkId,
                    "section_id", section == null ? chunkId : String.valueOf(section.getOrDefault("id", chunkId)),
                    "section_title", section == null ? "" : String.valueOf(section.getOrDefault("title", "")),
                    "entities", entities)).consume();

            for (Map<String, Object> relation : relations) {
                String kind = String.valueOf(relation.getOrDefault("kind", "RELATED_TO")).toUpperCase();
                if (!RELATION_TYPES.contains(kind)) {
                    continue;
                }
                String cypher = "MATCH (a:Entity {key: $notebook_id + ':' + $source}) "
                        + "MATCH (b:Entity {key: $notebook_id + ':' + $target}) "
                        + "MERGE (a)-[r:" + kind + "]->(b) "
                        + "SET r.notebook_id = $notebook_id, r.document_version_id = $document_version_id, "
                        + "r.confidence = $confidence, r.extractor_model = $extractor_model, "
                        + "r.extractor_version = $extractor_version, "
                        + "r.evidence_chunk_ids = CASE WHEN r.evidence_chunk_ids IS NULL THEN [$chunk_id] "
                        + "WHEN $chunk_id IN r.evidence_chunk_ids THEN r.evidence_chunk_ids "
                        + "ELSE r.evidence_chunk_ids + [$chunk_id] END";
                session.run(cypher, Values.parameters(
                        "notebook_id", notebookId,
                        "source", relation.get("source"),
                        "target", relation.get("target"),
                        "document_version_id", documentVersionId,
                        "confidence", relation.getOrDefault("confidence", 0.5),
                        "extractor_model", relation.getOrDefault("extractor_model", "unknown"),
                        "extractor_version", relation.getOrDefault("extractor_version", "1"),
                        "chunk_id", chunkId)).consume();
            }
        }
    }

    /** Ranked evidence chunk ids via experiment / metric / citation paths. */
    public List<String> searchEvidence(String notebookId, List<GraphEntity> entities, List<String> intents, int limit) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> paperNames = new LinkedHashSet<>();
        Set<String> datasetNames = new LinkedHashSet<>();
        Set<String> otherNames = new LinkedHashSet<>();
        for (GraphEntity entity : entities) {
            String lower = entity.name() == null ? "" : entity.name().trim().toLowerCase();
            if (lower.isEmpty()) {
                continue;
            }
            names.add(lower);
            switch (entity.kind() == null ? "" : entity.kind().toLowerCase()) {
                case "paper" -> paperNames.add(lower);
                case "dataset" -> datasetNames.add(lower);
                default -> otherNames.add(lower);
            }
        }
        Set<String> experimentTypes = new LinkedHashSet<>();
        Map<String, String> relationMap = Map.of(
                "method", "USES_METHOD", "dataset", "USES_DATASET", "result", "REPORTS_RESULT");
        for (String intent : intents) {
            if (relationMap.containsKey(intent)) {
                experimentTypes.add(relationMap.get(intent));
            }
        }
        boolean includeMetric = intents.contains("metric");
        boolean includeCitation = intents.contains("citation");
        if (experimentTypes.isEmpty() && !includeMetric && !includeCitation) {
            return List.of();
        }
        boolean hasScope = !otherNames.isEmpty();

        String cypher = """
                CALL () {
                  MATCH (p:Paper)-[:HAS_VERSION]->(v:DocumentVersion)
                  MATCH (p)-[:HAS_EXPERIMENT]->(e:Experiment)
                  WHERE p.notebook_id = $notebook_id AND v.notebook_id = $notebook_id AND e.notebook_id = $notebook_id
                    AND (size($paper_names) = 0 OR toLower(trim(p.title)) IN $paper_names)
                  OPTIONAL MATCH (e)-[dataset_rel:USES_DATASET]->(required:Dataset)
                  WHERE required.notebook_id = $notebook_id AND dataset_rel.document_version_id = v.id
                    AND toLower(trim(required.name)) IN $dataset_names
                  WITH p, v, e, dataset_rel
                  WHERE size($dataset_names) = 0 OR dataset_rel IS NOT NULL
                  MATCH (e)-[r]->(target:Entity)
                  WHERE type(r) IN $experiment_types AND r.notebook_id = $notebook_id
                    AND r.document_version_id = v.id AND target.notebook_id = $notebook_id
                    AND ($has_scope = false OR toLower(trim(target.name)) IN $other_names)
                    AND (type(r) <> 'USES_DATASET' OR size($dataset_names) = 0
                         OR toLower(trim(target.name)) IN $dataset_names)
                  UNWIND coalesce(r.evidence_chunk_ids, []) AS cid
                  MATCH (v)-[:HAS_CHUNK]->(evidence:Chunk {id: cid})
                  WHERE evidence.notebook_id = $notebook_id
                  RETURN cid AS chunk_id, 3.0 + 2.0 * coalesce(toFloat(r.confidence), 0.5)
                    + CASE WHEN dataset_rel IS NULL THEN 0.0 ELSE coalesce(toFloat(dataset_rel.confidence), 0.5) END
                    + CASE WHEN toLower(trim(target.name)) IN $names THEN 2.0 ELSE 0.0 END AS path_score
                  UNION ALL
                  MATCH (p:Paper)-[:HAS_VERSION]->(v:DocumentVersion)
                  MATCH (p)-[:HAS_EXPERIMENT]->(e:Experiment)
                  WHERE p.notebook_id = $notebook_id AND v.notebook_id = $notebook_id AND e.notebook_id = $notebook_id
                    AND (size($paper_names) = 0 OR toLower(trim(p.title)) IN $paper_names)
                  OPTIONAL MATCH (e)-[dataset_rel:USES_DATASET]->(required:Dataset)
                  WHERE required.notebook_id = $notebook_id AND dataset_rel.document_version_id = v.id
                    AND toLower(trim(required.name)) IN $dataset_names
                  WITH p, v, e, dataset_rel
                  WHERE size($dataset_names) = 0 OR dataset_rel IS NOT NULL
                  MATCH (e)-[result_rel:REPORTS_RESULT]->(res:Result)-[r:MEASURES]->(metric:Metric)
                  WHERE $include_metric AND result_rel.document_version_id = v.id
                    AND r.document_version_id = v.id AND r.notebook_id = $notebook_id
                    AND metric.notebook_id = $notebook_id
                    AND ($has_scope = false OR toLower(trim(metric.name)) IN $other_names)
                  UNWIND coalesce(r.evidence_chunk_ids, []) AS cid
                  MATCH (v)-[:HAS_CHUNK]->(evidence:Chunk {id: cid})
                  WHERE evidence.notebook_id = $notebook_id
                  RETURN cid AS chunk_id, 2.5 + coalesce(toFloat(result_rel.confidence), 0.5)
                    + 2.0 * coalesce(toFloat(r.confidence), 0.5)
                    + CASE WHEN dataset_rel IS NULL THEN 0.0 ELSE coalesce(toFloat(dataset_rel.confidence), 0.5) END
                    + CASE WHEN toLower(trim(metric.name)) IN $names THEN 2.0 ELSE 0.0 END AS path_score
                  UNION ALL
                  MATCH (source:Entity)-[r:CITES]->(target:Entity)
                  WHERE $include_citation AND source.notebook_id = $notebook_id
                    AND target.notebook_id = $notebook_id AND r.notebook_id = $notebook_id
                    AND (toLower(trim(source.name)) IN $names OR toLower(trim(target.name)) IN $names)
                  UNWIND coalesce(r.evidence_chunk_ids, []) AS cid
                  MATCH (v:DocumentVersion {id: r.document_version_id})-[:HAS_CHUNK]->(evidence:Chunk {id: cid})
                  WHERE v.notebook_id = $notebook_id AND evidence.notebook_id = $notebook_id
                  RETURN cid AS chunk_id, 3.5 + 2.0 * coalesce(toFloat(r.confidence), 0.5) AS path_score
                }
                WITH chunk_id, sum(path_score) AS score, count(*) AS path_count
                RETURN chunk_id ORDER BY score DESC, path_count DESC, chunk_id ASC LIMIT $limit
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("notebook_id", notebookId);
        params.put("names", new ArrayList<>(names));
        params.put("paper_names", new ArrayList<>(paperNames));
        params.put("dataset_names", new ArrayList<>(datasetNames));
        params.put("other_names", new ArrayList<>(otherNames));
        params.put("has_scope", hasScope);
        params.put("experiment_types", new ArrayList<>(experimentTypes));
        params.put("include_metric", includeMetric);
        params.put("include_citation", includeCitation);
        params.put("limit", limit);
        List<String> ids = new ArrayList<>();
        try (Session session = driver().session()) {
            for (Record record : session.run(cypher, params).list()) {
                ids.add(record.get("chunk_id").asString(null));
            }
        } catch (Exception e) {
            throw new RuntimeException("neo4j search_evidence failed", e);
        }
        return ids;
    }

    public List<Map<String, Object>> searchExperiments(String notebookId, List<String> names, int limit) {
        String detail = """
                OPTIONAL MATCH (e)-[um:USES_METHOD]->(m:Method)
                OPTIONAL MATCH (e)-[ud:USES_DATASET]->(ds:Dataset)
                OPTIONAL MATCH (e)-[rr:REPORTS_RESULT]->(res:Result)
                OPTIONAL MATCH (res)-[mm:MEASURES]->(mt:Metric)
                RETURN p.id AS paper_id, p.title AS paper_title, e.key AS experiment_key,
                       e.name AS experiment_name,
                       collect(DISTINCT CASE WHEN m IS NULL THEN NULL ELSE {name: m.name, evidence: um.evidence_chunk_ids} END) AS methods,
                       collect(DISTINCT CASE WHEN ds IS NULL THEN NULL ELSE {name: ds.name, evidence: ud.evidence_chunk_ids} END) AS datasets,
                       collect(DISTINCT CASE WHEN res IS NULL THEN NULL ELSE {name: res.name, evidence: rr.evidence_chunk_ids} END) AS results,
                       collect(DISTINCT CASE WHEN mt IS NULL THEN NULL ELSE {name: mt.name, evidence: mm.evidence_chunk_ids} END) AS metrics
                """;
        String cypher = (names == null || names.isEmpty())
                ? "MATCH (p:Paper)-[:HAS_EXPERIMENT]->(e:Experiment) WHERE p.notebook_id = $notebook_id WITH p, e "
                + detail + " LIMIT $limit"
                : "MATCH (seed:Entity) WHERE seed.notebook_id = $notebook_id AND toLower(seed.name) IN $names "
                + "MATCH (seed)-[*1..3]-(e:Experiment) MATCH (p:Paper)-[:HAS_EXPERIMENT]->(e) "
                + "WITH DISTINCT p, e " + detail + " LIMIT $limit";
        List<Map<String, Object>> items = new ArrayList<>();
        try (Session session = driver().session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("notebook_id", notebookId);
            params.put("limit", limit);
            if (names != null && !names.isEmpty()) {
                params.put("names", names);
            }
            for (Record record : session.run(cypher, params).list()) {
                Map<String, Object> row = record.asMap();
                items.add(row);
            }
        }
        return items;
    }

    public void deleteDocumentVersion(String documentVersionId) {
        String cypher = """
                MATCH (d:DocumentVersion {id: $document_version_id})
                OPTIONAL MATCH (d)-[:HAS_SECTION|HAS_CHUNK]->(child)
                DETACH DELETE child, d
                """;
        try (Session session = driver().session()) {
            session.run(cypher, Values.parameters("document_version_id", documentVersionId)).consume();
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> linked(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }
}
