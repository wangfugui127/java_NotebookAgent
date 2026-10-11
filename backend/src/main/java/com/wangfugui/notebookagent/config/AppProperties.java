package com.wangfugui.notebookagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String name = "NotebookAgent";
    private String env = "development";
    private String secretKey = "development-only-change-me-at-least-32-bytes";
    private int accessTokenMinutes = 30;
    private int refreshTokenDays = 30;

    private Milvus milvus = new Milvus();
    private Neo4j neo4j = new Neo4j();
    private Deepseek deepseek = new Deepseek();
    private Siliconflow siliconflow = new Siliconflow();
    private Openalex openalex = new Openalex();

    private int modelContextWindow = 1_048_576;
    private int maxOutputTokens = 32_768;
    private double contextSafetyRatio = 0.6;
    private int contextKeepRecentTurns = 6;
    private int contextKeepRecentToolResults = 4;

    private String uploadDir = "./uploads";
    private String mcpServersConfig = "../config/mcp_servers.yaml";
    private String mcpExposeConfig = "../config/mcp_expose.yaml";
    private String skillsConfig = "../config/skills.yaml";
    private String evalGoldenPath = "../config/eval_golden.json";

    private Queue queue = new Queue();

    public int effectiveInputBudget() {
        int safeWindow = (int) (modelContextWindow * contextSafetyRatio);
        return Math.max(1, safeWindow - maxOutputTokens);
    }

    public static class Milvus {
        private String uri = "http://localhost:19530";
        private String collection = "document_chunks_v3";
        private String documentCollection = "notebook_documents_v1";

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public String getDocumentCollection() {
            return documentCollection;
        }

        public void setDocumentCollection(String documentCollection) {
            this.documentCollection = documentCollection;
        }
    }

    public static class Neo4j {
        private String uri = "bolt://localhost:7687";
        private String user = "neo4j";
        private String password = "notebookagent-neo4j";

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Deepseek {
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-flash";
        private String apiKey = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class Siliconflow {
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey = "";
        private String embeddingModel = "BAAI/bge-m3";
        private int embeddingDim = 1024;
        private String rerankModel = "BAAI/bge-reranker-v2-m3";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDim() {
            return embeddingDim;
        }

        public void setEmbeddingDim(int embeddingDim) {
            this.embeddingDim = embeddingDim;
        }

        public String getRerankModel() {
            return rerankModel;
        }

        public void setRerankModel(String rerankModel) {
            this.rerankModel = rerankModel;
        }
    }

    public static class Openalex {
        private String mailto = "";

        public String getMailto() {
            return mailto;
        }

        public void setMailto(String mailto) {
            this.mailto = mailto;
        }
    }

    public static class Queue {
        private String keyPrefix = "notebookagent";
        private String stream = "tasks";
        private String consumerGroup = "workers";
        private int visibilityTimeoutSeconds = 120;
        private int leaseSeconds = 90;
        private long dispatcherIntervalMs = 1000;
        private long pollBlockMs = 2000;
        private boolean workerEnabled = false;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = stream;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public int getVisibilityTimeoutSeconds() {
            return visibilityTimeoutSeconds;
        }

        public void setVisibilityTimeoutSeconds(int visibilityTimeoutSeconds) {
            this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public long getDispatcherIntervalMs() {
            return dispatcherIntervalMs;
        }

        public void setDispatcherIntervalMs(long dispatcherIntervalMs) {
            this.dispatcherIntervalMs = dispatcherIntervalMs;
        }

        public long getPollBlockMs() {
            return pollBlockMs;
        }

        public void setPollBlockMs(long pollBlockMs) {
            this.pollBlockMs = pollBlockMs;
        }

        public boolean isWorkerEnabled() {
            return workerEnabled;
        }

        public void setWorkerEnabled(boolean workerEnabled) {
            this.workerEnabled = workerEnabled;
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public int getAccessTokenMinutes() {
        return accessTokenMinutes;
    }

    public void setAccessTokenMinutes(int accessTokenMinutes) {
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public int getRefreshTokenDays() {
        return refreshTokenDays;
    }

    public void setRefreshTokenDays(int refreshTokenDays) {
        this.refreshTokenDays = refreshTokenDays;
    }

    public Milvus getMilvus() {
        return milvus;
    }

    public void setMilvus(Milvus milvus) {
        this.milvus = milvus;
    }

    public Neo4j getNeo4j() {
        return neo4j;
    }

    public void setNeo4j(Neo4j neo4j) {
        this.neo4j = neo4j;
    }

    public Deepseek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(Deepseek deepseek) {
        this.deepseek = deepseek;
    }

    public Siliconflow getSiliconflow() {
        return siliconflow;
    }

    public void setSiliconflow(Siliconflow siliconflow) {
        this.siliconflow = siliconflow;
    }

    public Openalex getOpenalex() {
        return openalex;
    }

    public void setOpenalex(Openalex openalex) {
        this.openalex = openalex;
    }

    public int getModelContextWindow() {
        return modelContextWindow;
    }

    public void setModelContextWindow(int modelContextWindow) {
        this.modelContextWindow = modelContextWindow;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public double getContextSafetyRatio() {
        return contextSafetyRatio;
    }

    public void setContextSafetyRatio(double contextSafetyRatio) {
        this.contextSafetyRatio = contextSafetyRatio;
    }

    public int getContextKeepRecentTurns() {
        return contextKeepRecentTurns;
    }

    public void setContextKeepRecentTurns(int contextKeepRecentTurns) {
        this.contextKeepRecentTurns = contextKeepRecentTurns;
    }

    public int getContextKeepRecentToolResults() {
        return contextKeepRecentToolResults;
    }

    public void setContextKeepRecentToolResults(int contextKeepRecentToolResults) {
        this.contextKeepRecentToolResults = contextKeepRecentToolResults;
    }

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public String getMcpServersConfig() {
        return mcpServersConfig;
    }

    public void setMcpServersConfig(String mcpServersConfig) {
        this.mcpServersConfig = mcpServersConfig;
    }

    public String getMcpExposeConfig() {
        return mcpExposeConfig;
    }

    public void setMcpExposeConfig(String mcpExposeConfig) {
        this.mcpExposeConfig = mcpExposeConfig;
    }

    public String getSkillsConfig() {
        return skillsConfig;
    }

    public void setSkillsConfig(String skillsConfig) {
        this.skillsConfig = skillsConfig;
    }

    public String getEvalGoldenPath() {
        return evalGoldenPath;
    }

    public void setEvalGoldenPath(String evalGoldenPath) {
        this.evalGoldenPath = evalGoldenPath;
    }

    public Queue getQueue() {
        return queue;
    }

    public void setQueue(Queue queue) {
        this.queue = queue;
    }
}
