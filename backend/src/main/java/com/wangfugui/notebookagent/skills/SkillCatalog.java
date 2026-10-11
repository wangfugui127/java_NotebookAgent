package com.wangfugui.notebookagent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.wangfugui.notebookagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Loads SKILL.md manifests from configured roots (skills.yaml). */
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);
    private static final Pattern REFERENCE = Pattern.compile("(?:\\(|\\s)(references/[A-Za-z0-9._/-]+)");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final AppProperties props;
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public record Skill(String name, String description, String version, List<String> allowedTools,
                        Path path, String body) {
    }

    public SkillCatalog(AppProperties props) {
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Skill> reload(Set<String> knownTools) {
        skills.clear();
        Path configPath = Path.of(props.getSkillsConfig());
        if (!Files.exists(configPath)) {
            log.warn("skills config not found at {}", configPath);
            return List.of();
        }
        try {
            Map<String, Object> config = YAML.readValue(Files.readString(configPath), Map.class);
            List<String> roots = (List<String>) config.getOrDefault("roots", List.of("./skills"));
            List<String> enabled = (List<String>) config.getOrDefault("enabled", List.of());
            Set<String> enabledSet = new LinkedHashSet<>(enabled);
            for (String root : roots) {
                Path rootPath = resolve(root);
                if (!Files.isDirectory(rootPath)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(rootPath, 2)) {
                    for (Path file : files.filter(f -> f.getFileName().toString().equals("SKILL.md")).toList()) {
                        Skill skill = parse(file);
                        if (skill == null || !enabledSet.contains(skill.name())) {
                            continue;
                        }
                        for (String tool : skill.allowedTools()) {
                            if (!knownTools.contains(tool)) {
                                log.warn("skill {} allows unknown tool {}", skill.name(), tool);
                            }
                        }
                        skills.put(skill.name(), skill);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("failed to load skills: {}", e.getMessage());
        }
        return new ArrayList<>(skills.values());
    }

    private Path resolve(String root) {
        Path candidate = Path.of(root);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        Path configDir = Path.of(props.getSkillsConfig()).toAbsolutePath().getParent();
        Path relativeToProject = configDir == null ? candidate : configDir.resolve("..").resolve(root).normalize();
        if (Files.exists(relativeToProject)) {
            return relativeToProject;
        }
        Path relativeToConfig = configDir == null ? candidate : configDir.resolve(root).normalize();
        return Files.exists(relativeToConfig) ? relativeToConfig : relativeToProject;
    }

    private Skill parse(Path file) {
        try {
            String content = Files.readString(file).replace("\r\n", "\n");
            if (!content.startsWith("---\n")) {
                return null;
            }
            int end = content.indexOf("\n---\n", 4);
            if (end < 0) {
                return null;
            }
            String frontmatter = content.substring(4, end);
            String body = content.substring(end + 5).strip();
            Map<String, Object> meta = YAML.readValue(frontmatter, Map.class);
            String name = meta.get("name") == null ? file.getParent().getFileName().toString()
                    : String.valueOf(meta.get("name"));
            Object description = meta.get("description");
            if (description == null || String.valueOf(description).isBlank()) {
                return null;
            }
            String version = meta.get("version") == null ? "1" : String.valueOf(meta.get("version"));
            List<String> allowed = meta.get("allowed_tools") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
            return new Skill(name, String.valueOf(description), version, allowed, file, body);
        } catch (Exception e) {
            log.warn("failed to parse skill {}: {}", file, e.getMessage());
            return null;
        }
    }

    public synchronized List<Map<String, Object>> summaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Skill skill : skills.values()) {
            out.add(Map.of("name", skill.name(), "description", skill.description(), "version", skill.version()));
        }
        return out;
    }

    public synchronized Map<String, Object> load(String name, List<String> userAllowedTools) {
        Skill skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("unknown skill: " + name);
        }
        List<String> effective = skill.allowedTools().stream()
                .filter(userAllowedTools::contains).sorted().toList();
        Map<String, String> references = new LinkedHashMap<>();
        Matcher matcher = REFERENCE.matcher(skill.body());
        Set<String> paths = new LinkedHashSet<>();
        while (matcher.find() && paths.size() < 10) {
            paths.add(matcher.group(1));
        }
        for (String reference : paths) {
            Path resolved = skill.path().getParent().resolve(reference).normalize();
            if (!resolved.startsWith(skill.path().getParent().resolve("references").normalize())) {
                continue;
            }
            try {
                if (Files.isRegularFile(resolved)) {
                    String text = Files.readString(resolved);
                    references.put(reference, text.length() > 50000 ? text.substring(0, 50000) : text);
                }
            } catch (Exception ignored) {
                // skip unreadable reference
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", skill.name());
        result.put("description", skill.description());
        result.put("version", skill.version());
        result.put("instructions", skill.body());
        result.put("allowed_tools", effective);
        result.put("references", references);
        return result;
    }

    public synchronized boolean has(String name) {
        return skills.containsKey(name);
    }
}
