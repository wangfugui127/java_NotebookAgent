package com.wangfugui.notebookagent.providers;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAlex works search/details client. */
public class OpenAlexProvider {

    private final AppProperties.Openalex config;
    private final HttpClient http;

    public OpenAlexProvider(AppProperties props) {
        this.config = props.getOpenalex();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String query, Integer yearFrom, Integer yearTo, int limit) {
        StringBuilder url = new StringBuilder("https://api.openalex.org/works?search=")
                .append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                .append("&per-page=").append(Math.min(limit, 50));
        List<String> filters = new ArrayList<>();
        if (yearFrom != null) {
            filters.add("from_publication_date:" + yearFrom + "-01-01");
        }
        if (yearTo != null) {
            filters.add("to_publication_date:" + yearTo + "-12-31");
        }
        if (!filters.isEmpty()) {
            url.append("&filter=").append(String.join(",", filters));
        }
        if (config.getMailto() != null && !config.getMailto().isBlank()) {
            url.append("&mailto=").append(URLEncoder.encode(config.getMailto(), StandardCharsets.UTF_8));
        }
        Map<String, Object> response = getJson(url.toString());
        List<Map<String, Object>> items = new ArrayList<>();
        Object results = response.get("results");
        if (results instanceof List<?> list) {
            for (Object work : list) {
                if (work instanceof Map<?, ?> m) {
                    items.add(normalize(cast(m)));
                }
            }
        }
        return items;
    }

    public Map<String, Object> get(String paperId) {
        Map<String, Object> work = getJson("https://api.openalex.org/works/" + URLEncoder.encode(paperId, StandardCharsets.UTF_8));
        return normalize(work);
    }

    public Map<String, Object> normalize(Map<String, Object> work) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = String.valueOf(work.getOrDefault("id", ""));
        out.put("paper_id", id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id);
        out.put("title", work.getOrDefault("display_name", ""));
        List<String> authors = new ArrayList<>();
        if (work.get("authorships") instanceof List<?> list) {
            for (Object a : list) {
                if (a instanceof Map<?, ?> am && am.get("author") instanceof Map<?, ?> author
                        && author.get("display_name") != null) {
                    authors.add(String.valueOf(author.get("display_name")));
                }
            }
        }
        out.put("authors", authors);
        out.put("year", work.get("publication_year"));
        out.put("abstract", reconstructAbstract(work.get("abstract_inverted_index")));
        out.put("doi", work.get("doi"));
        out.put("url", landingUrl(work));
        out.put("pdf_url", pdfUrl(work));
        Object oa = work.get("open_access");
        boolean isOa = oa instanceof Map<?, ?> oam && Boolean.TRUE.equals(oam.get("is_oa"));
        out.put("is_oa", isOa);
        out.put("oa_url", oa instanceof Map<?, ?> oam ? oam.get("oa_url") : null);
        Object cited = work.get("cited_by_count");
        out.put("citation_count", cited instanceof Number n ? n.intValue() : 0);
        out.put("source", sourceName(work));
        return out;
    }

    @SuppressWarnings("unchecked")
    private String reconstructAbstract(Object inverted) {
        if (!(inverted instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        List<Object[]> words = new ArrayList<>();
        for (Map.Entry<?, ?> e : ((Map<Object, Object>) map).entrySet()) {
            if (e.getValue() instanceof List<?> positions) {
                for (Object p : positions) {
                    if (p instanceof Number n) {
                        words.add(new Object[] {n.intValue(), String.valueOf(e.getKey())});
                    }
                }
            }
        }
        words.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
        StringBuilder sb = new StringBuilder();
        for (Object[] w : words) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w[1]);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String landingUrl(Map<String, Object> work) {
        if (work.get("primary_location") instanceof Map<?, ?> pl && pl.get("landing_page_url") != null) {
            return String.valueOf(pl.get("landing_page_url"));
        }
        return work.get("doi") == null ? null : String.valueOf(work.get("doi"));
    }

    private String pdfUrl(Map<String, Object> work) {
        if (work.get("best_oa_location") instanceof Map<?, ?> loc && loc.get("pdf_url") != null) {
            return String.valueOf(loc.get("pdf_url"));
        }
        if (work.get("primary_location") instanceof Map<?, ?> pl && pl.get("pdf_url") != null) {
            return String.valueOf(pl.get("pdf_url"));
        }
        return null;
    }

    private String sourceName(Map<String, Object> work) {
        if (work.get("primary_location") instanceof Map<?, ?> pl && pl.get("source") instanceof Map<?, ?> src
                && src.get("display_name") != null) {
            return String.valueOf(src.get("display_name"));
        }
        return "OpenAlex";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "NotebookAgent/0.1 (" + (config.getMailto() == null ? "" : config.getMailto()) + ")")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("openalex http " + response.statusCode());
            }
            return Json.readMap(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("openalex request failed", e);
        }
    }

    private static Map<String, Object> cast(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
