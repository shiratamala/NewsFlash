package dev.newsflash.provider.p2pquake;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newsflash.NewsFlashPlugin;
import dev.newsflash.broadcast.NewsBroadcaster;
import dev.newsflash.config.P2pQuakeConfig;
import dev.newsflash.model.NewsItem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

public final class P2pQuakeRealtimeProvider implements WebSocket.Listener {
    private static final int JMA_QUAKE_CODE = 551;
    private static final DateTimeFormatter P2PQUAKE_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss[.SSS]");

    private final NewsFlashPlugin plugin;
    private final P2pQuakeConfig config;
    private final NewsBroadcaster broadcaster;
    private final HttpClient client;
    private final Queue<String> seenIds = new ArrayDeque<>();
    private final Set<String> seenIdSet = new java.util.HashSet<>();
    private final StringBuilder messageBuffer = new StringBuilder();
    private WebSocket webSocket;
    private BukkitTask reconnectTask;
    private volatile boolean stopping;

    public P2pQuakeRealtimeProvider(NewsFlashPlugin plugin, P2pQuakeConfig config, NewsBroadcaster broadcaster) {
        this.plugin = plugin;
        this.config = config;
        this.broadcaster = broadcaster;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public void start() {
        if (!config.enabled()) {
            return;
        }
        stopping = false;
        connect();
    }

    public void stop() {
        stopping = true;
        if (reconnectTask != null) {
            reconnectTask.cancel();
            reconnectTask = null;
        }
        if (webSocket != null) {
            webSocket.abort();
            webSocket = null;
        }
    }

    private void connect() {
        plugin.getLogger().info("Connecting to P2PQuake WebSocket: " + config.websocketUrl());
        client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .buildAsync(URI.create(config.websocketUrl()), this)
            .whenComplete((socket, throwable) -> {
                if (throwable != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to connect to P2PQuake WebSocket.", throwable);
                    scheduleReconnect();
                    return;
                }
                webSocket = socket;
                plugin.getLogger().info("Connected to P2PQuake WebSocket.");
            });
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        WebSocket.Listener.super.onText(webSocket, data, last);
        messageBuffer.append(data);
        if (!last) {
            return null;
        }
        String payload = messageBuffer.toString();
        messageBuffer.setLength(0);
        handleMessage(payload);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        if (!stopping) {
            plugin.getLogger().warning("P2PQuake WebSocket closed: " + statusCode + " " + reason);
            scheduleReconnect();
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (!stopping) {
            plugin.getLogger().log(Level.WARNING, "P2PQuake WebSocket error.", error);
            scheduleReconnect();
        }
    }

    private void handleMessage(String payload) {
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            int code = intValue(root, "code", -1);
            if (code != JMA_QUAKE_CODE || !config.earthquakeEnabled()) {
                return;
            }

            String id = stringValue(root, "id", "");
            if (id.isBlank() || isSeen(id)) {
                return;
            }

            JsonObject earthquake = objectValue(root, "earthquake");
            if (earthquake == null) {
                return;
            }

            QuakeMatch match = matchQuake(root, earthquake);
            if (!match.shouldBroadcast()) {
                remember(id);
                return;
            }

            NewsItem item = toNewsItem(root, earthquake, match);
            remember(id);
            Bukkit.getScheduler().runTask(plugin, () -> broadcaster.broadcast(List.of(item)));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to handle P2PQuake message.", exception);
        }
    }

    private QuakeMatch matchQuake(JsonObject root, JsonObject earthquake) {
        int nationwideMaxScale = intValue(earthquake, "maxScale", -1);
        if (!config.targetPrefecturesEnabled() || config.targetPrefectures().isEmpty()) {
            return new QuakeMatch(shouldBroadcastScale(nationwideMaxScale), nationwideMaxScale, nationwideMaxScale, List.of());
        }

        List<String> matchedAreas = new ArrayList<>();
        int targetMaxScale = -1;
        JsonArray points = arrayValue(root, "points");
        if (points != null) {
            for (JsonElement element : points) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject point = element.getAsJsonObject();
                String pref = stringValue(point, "pref", "");
                int scale = intValue(point, "scale", -1);
                if (!config.targetPrefectures().contains(pref)) {
                    continue;
                }
                targetMaxScale = Math.max(targetMaxScale, scale);
                if (scale >= config.minScale() && matchedAreas.size() < 5) {
                    matchedAreas.add(pref + " " + stringValue(point, "addr", "") + " 震度" + scaleLabel(scale));
                }
            }
        }

        return new QuakeMatch(shouldBroadcastScale(targetMaxScale), nationwideMaxScale, targetMaxScale, List.copyOf(matchedAreas));
    }

    private boolean shouldBroadcastScale(int scale) {
        if (scale < 0) {
            return config.includeUnknownScale();
        }
        return scale >= config.minScale();
    }

    private NewsItem toNewsItem(JsonObject root, JsonObject earthquake, QuakeMatch match) {
        JsonObject hypocenter = objectValue(earthquake, "hypocenter");
        String hypocenterName = hypocenter == null ? "震源不明" : stringValue(hypocenter, "name", "震源不明");
        double magnitude = hypocenter == null ? -1.0 : doubleValue(hypocenter, "magnitude", -1.0);
        int depth = hypocenter == null ? -1 : intValue(hypocenter, "depth", -1);
        String tsunami = stringValue(earthquake, "domesticTsunami", "Unknown");

        String title = "地震情報: 最大震度" + scaleLabel(match.nationwideMaxScale()) + " " + hypocenterName;
        String lead = "発生時刻: " + stringValue(earthquake, "time", "不明")
            + " / M" + (magnitude < 0 ? "不明" : String.format("%.1f", magnitude))
            + " / 深さ" + (depth < 0 ? "不明" : depth + "km")
            + " / 津波: " + tsunamiLabel(tsunami)
            + targetAreaText(match);

        return new NewsItem(
            stringValue(root, "id", ""),
            "P2P地震情報",
            "地震情報",
            title,
            lead,
            "https://www.p2pquake.net/",
            parseTime(stringValue(root, "time", "")),
            matchedKeyword(match)
        );
    }

    private String targetAreaText(QuakeMatch match) {
        if (!config.targetPrefecturesEnabled() || config.targetPrefectures().isEmpty()) {
            return "";
        }
        if (match.matchedAreas().isEmpty()) {
            return " / 対象地域最大震度: " + scaleLabel(match.targetMaxScale());
        }
        return " / 対象地域: " + String.join(", ", match.matchedAreas());
    }

    private String matchedKeyword(QuakeMatch match) {
        if (!config.targetPrefecturesEnabled() || config.targetPrefectures().isEmpty()) {
            return "最大震度" + scaleLabel(match.nationwideMaxScale());
        }
        return "対象地域最大震度" + scaleLabel(match.targetMaxScale());
    }

    private void scheduleReconnect() {
        if (stopping || reconnectTask != null) {
            return;
        }
        reconnectTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            reconnectTask = null;
            if (!stopping) {
                connect();
            }
        }, config.reconnectDelaySeconds() * 20L);
    }

    private boolean isSeen(String id) {
        return seenIdSet.contains(id);
    }

    private void remember(String id) {
        if (!seenIdSet.add(id)) {
            return;
        }
        seenIds.add(id);
        while (seenIds.size() > config.seenHistoryLimit()) {
            String removed = seenIds.poll();
            if (removed != null) {
                seenIdSet.remove(removed);
            }
        }
    }

    private Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return LocalDateTime.parse(value, P2PQUAKE_TIME)
                .atZone(ZoneId.of("Asia/Tokyo"))
                .toInstant();
        } catch (Exception ignored) {
            return Instant.now();
        }
    }

    private String scaleLabel(int scale) {
        return switch (scale) {
            case 10 -> "1";
            case 20 -> "2";
            case 30 -> "3";
            case 40 -> "4";
            case 45 -> "5弱";
            case 46 -> "5弱以上";
            case 50 -> "5強";
            case 55 -> "6弱";
            case 60 -> "6強";
            case 70 -> "7";
            default -> "不明";
        };
    }

    private String tsunamiLabel(String tsunami) {
        return switch (tsunami) {
            case "None" -> "なし";
            case "Checking" -> "調査中";
            case "NonEffective" -> "若干の海面変動";
            case "Watch" -> "津波注意報";
            case "Warning" -> "津波警報";
            default -> "不明";
        };
    }

    private JsonObject objectValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private JsonArray arrayValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        return element.getAsJsonArray();
    }

    private String stringValue(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return element.getAsString();
    }

    private int intValue(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return element.getAsInt();
    }

    private double doubleValue(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return element.getAsDouble();
    }

    private record QuakeMatch(
        boolean shouldBroadcast,
        int nationwideMaxScale,
        int targetMaxScale,
        List<String> matchedAreas
    ) {
    }
}
