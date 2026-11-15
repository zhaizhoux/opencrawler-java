package io.opencrawler;

import java.util.List;
import java.util.Map;

/** 引擎内部流转的不可变数据模型。 */
public final class Models {
    private Models() {}

    public record Request(String url, int depth, int priority, String kind) {
        public static Request page(String url, int depth) { return new Request(url, depth, 0, "page"); }
        public Request withDepth(int d) { return new Request(url, d, priority, kind); }
    }

    public record Response(String url, int status, String contentType,
                           Map<String, List<String>> headers, byte[] body, long elapsedMs) {
        public String text() { return Util.decode(body, contentType); }
        public String header(String name) {
            if (headers == null) return "";
            for (var e : headers.entrySet())
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            return "";
        }
    }

    public record Outcome(Response response, String error, String skipped) {
        public static Outcome ok(Response r) { return new Outcome(r, "", ""); }
        public static Outcome fail(String e) { return new Outcome(null, e, ""); }
        public static Outcome skip(String why) { return new Outcome(null, "", why); }
        public boolean success() { return error.isEmpty() && skipped.isEmpty() && response != null; }
    }

    public record Item(Map<String, Object> data, String url, String kind, String hash) {}

    public record Extracted(Item item, List<Request> links, String reason, boolean skip) {
        public static Extracted skipped(String why) {
            return new Extracted(null, List.of(), why, true);
        }
    }
}
