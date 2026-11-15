package io.opencrawler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** JSONL 结果存储：线程安全追加、内容哈希去重、末尾读取。 */
public final class Storage implements AutoCloseable {
    private final Path file;
    private final BufferedWriter writer;
    private final boolean enabled, dedupe;
    private final ConcurrentHashMap<String, Boolean> hashes = new ConcurrentHashMap<>();

    public Storage(Config cfg) throws IOException {
        this.enabled = !cfg.storage.equals("none");
        this.dedupe = cfg.dedupeContent;
        this.file = Path.of(cfg.outputDir, "items.jsonl");
        Files.createDirectories(file.getParent());
        this.writer = enabled ? Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND) : null;
    }

    public synchronized boolean write(Models.Item item) {
        if (!enabled || item == null) return false;
        if (dedupe && !item.hash().isEmpty()
            && hashes.putIfAbsent(item.hash(), Boolean.TRUE) != null) return false;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("url", item.url());
        row.put("kind", item.kind());
        row.put("hash", item.hash());
        row.put("fetched_at", Util.nowIso());
        row.put("data", item.data());
        try {
            writer.write(Json.stringify(row));
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException e) { return false; }
    }

    public static List<Map<String, Object>> tail(String outputDir, int limit) {
        Path p = Path.of(outputDir, "items.jsonl");
        if (!Files.isRegularFile(p)) return List.of();
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - Math.max(1, limit));
            List<Map<String, Object>> out = new ArrayList<>();
            for (String line : lines.subList(from, lines.size())) {
                try { out.add(Json.obj(Json.parse(line))); }
                catch (RuntimeException ignored) { }
            }
            return out;
        } catch (IOException e) { return List.of(); }
    }

    /** 与 Python / C++ 版一致的筛选查询：{items, total, limit, offset}。 */
    public static Map<String, Object> query(String outputDir, int limit,
                                            String q, String kind, int offset) {
        final int scanLimit = 20_000;
        List<Map<String, Object>> matched = new ArrayList<>();
        Path p = Path.of(outputDir, "items.jsonl");
        String needle = q == null ? "" : q.toLowerCase(Locale.ROOT);
        String wanted = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(p)) {
            try {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - scanLimit);
                for (String line : lines.subList(from, lines.size())) {
                    Map<String, Object> row;
                    try { row = Json.obj(Json.parse(line)); }
                    catch (RuntimeException ignored) { continue; }
                    Object raw = row.get("data");
                    Map<String, Object> data = raw instanceof Map<?, ?> ? Json.obj(raw) : row;
                    String rowKind = String.valueOf(row.getOrDefault("kind",
                        data.getOrDefault("kind", "page"))).toLowerCase(Locale.ROOT);
                    if (!wanted.isEmpty() && !wanted.equals(rowKind)) continue;
                    if (!needle.isEmpty()) {
                        String url = String.valueOf(row.getOrDefault("url", data.getOrDefault("url", "")));
                        String title = String.valueOf(data.getOrDefault("title", data.getOrDefault("name", "")));
                        if (!(url + " " + title).toLowerCase(Locale.ROOT).contains(needle)) continue;
                    }
                    matched.add(row);
                }
            } catch (IOException ignored) { /* 读取失败按空结果处理 */ }
        }
        int total = matched.size();
        int end = Math.max(0, total - Math.max(0, offset));
        int start = Math.max(0, end - Math.max(1, limit));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", new ArrayList<>(matched.subList(start, end)));
        out.put("total", total);
        out.put("limit", limit);
        out.put("offset", offset);
        return out;
    }

    public Path file() { return file; }

    @Override public void close() {
        if (writer != null) try { writer.flush(); writer.close(); } catch (IOException ignored) { }
    }
}
