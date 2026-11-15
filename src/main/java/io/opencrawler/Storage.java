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

    public Path file() { return file; }

    @Override public void close() {
        if (writer != null) try { writer.flush(); writer.close(); } catch (IOException ignored) { }
    }
}
