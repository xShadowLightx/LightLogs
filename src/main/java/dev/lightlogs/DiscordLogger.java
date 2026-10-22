package dev.lightlogs;

import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

final class DiscordLogger {
    private final JavaPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Map<String, String> webhooks = Map.of();
    private volatile Map<String, String> auraSkillWebhooks = Map.of();
    private volatile Map<String, String> townyContextWebhooks = Map.of();
    private volatile Map<String, Boolean> townyContextEnabled = Map.of();
    private volatile Map<String, String> realisticSeasonsContextWebhooks = Map.of();
    private final Set<String> pausedTargets = ConcurrentHashMap.newKeySet();
    private volatile boolean allPaused;
    private volatile boolean townyEnabled = true;
    private volatile String serverName;
    private volatile boolean suppressMentions;

    DiscordLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        refresh();
    }

    void refresh() {
        Map<String, String> configured = new ConcurrentHashMap<>();
        if (plugin.getConfig().isConfigurationSection("webhooks")) {
            for (String category : plugin.getConfig().getConfigurationSection("webhooks").getKeys(false)) {
                String path = "webhooks." + category;
                if (plugin.getConfig().isString(path)) configured.put(category, normalizeWebhookValue(plugin.getConfig().getString(path, "")));
            }
        }
        webhooks = Map.copyOf(configured);
        Map<String, String> skillConfigured = new ConcurrentHashMap<>();
        if (plugin.getConfig().isConfigurationSection("webhooks.auraskills-skills")) {
            for (String skillId : plugin.getConfig().getConfigurationSection("webhooks.auraskills-skills").getKeys(false)) {
                skillConfigured.put(normalizeSkillId(skillId), normalizeWebhookValue(plugin.getConfig().getString("webhooks.auraskills-skills." + skillId, "")));
            }
        }
        auraSkillWebhooks = Map.copyOf(skillConfigured);
        Map<String, String> townyUrls = new ConcurrentHashMap<>();
        if (plugin.getConfig().isConfigurationSection("webhooks.towny-contexts")) {
            for (String context : plugin.getConfig().getConfigurationSection("webhooks.towny-contexts").getKeys(false)) {
                townyUrls.put(context.toLowerCase(java.util.Locale.ROOT), normalizeWebhookValue(plugin.getConfig().getString("webhooks.towny-contexts." + context, "")));
            }
        }
        townyContextWebhooks = Map.copyOf(townyUrls);
        Map<String, Boolean> townyToggles = new ConcurrentHashMap<>();
        if (plugin.getConfig().isConfigurationSection("settings.towny.contexts")) {
            for (String context : plugin.getConfig().getConfigurationSection("settings.towny.contexts").getKeys(false)) {
                townyToggles.put(context.toLowerCase(java.util.Locale.ROOT), plugin.getConfig().getBoolean("settings.towny.contexts." + context, true));
            }
        }
        townyContextEnabled = Map.copyOf(townyToggles);
        Map<String, String> seasonUrls = new ConcurrentHashMap<>();
        if (plugin.getConfig().isConfigurationSection("webhooks.realistic-seasons-contexts")) {
            for (String context : plugin.getConfig().getConfigurationSection("webhooks.realistic-seasons-contexts").getKeys(false)) {
                seasonUrls.put(context.toLowerCase(java.util.Locale.ROOT), normalizeWebhookValue(
                        plugin.getConfig().getString("webhooks.realistic-seasons-contexts." + context, "")));
            }
        }
        realisticSeasonsContextWebhooks = Map.copyOf(seasonUrls);
        townyEnabled = plugin.getConfig().getBoolean("settings.towny.enabled", true);
        serverName = clean(plugin.getConfig().getString("settings.server-name", "Minecraft Server"));
        suppressMentions = plugin.getConfig().getBoolean("settings.suppress-mentions", true);
    }

    void send(String category, String message) {
        if (isPaused(category)) return;
        String url = webhooks.getOrDefault(category, "");
        if ("none".equalsIgnoreCase(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault("global", "");
        sendWebhook(url, category, message);
    }

    void sendAuraSkill(String skillId, String skillName, String message) {
        String normalizedSkillId = normalizeSkillId(skillId);
        if (isPaused("auraskills", "auraskills." + normalizedSkillId)) return;
        String url = auraSkillWebhooks.get(normalizedSkillId);
        if (url == null || url.isEmpty()) url = auraSkillWebhooks.get(normalizeSkillId(skillName));
        if ("none".equals(url)) return;
        if (url == null || url.isEmpty()) url = webhooks.getOrDefault("auraskills", "");
        if ("none".equalsIgnoreCase(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault("global", "");
        sendWebhook(url, "auraskills", message);
    }

    void sendTowny(String context, String message) {
        if (!townyEnabled) return;
        String normalized = context == null ? "" : context.trim().toLowerCase(java.util.Locale.ROOT);
        if (isPaused("towny", "towny." + normalized)) return;
        if (!townyContextEnabled.getOrDefault(normalized, true)) return;
        String url = townyContextWebhooks.getOrDefault(normalized, "");
        if ("none".equals(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault("towny", "");
        if ("none".equalsIgnoreCase(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault("global", "");
        sendWebhook(url, "towny-" + normalized, message);
    }

    void sendLuckPerms(String context, String legacyCategory, String message) {
        if (isPaused("luckperms", "luckperms." + context, legacyCategory)) return;
        String url = webhooks.getOrDefault("luckperms-" + context, "");
        if ("none".equalsIgnoreCase(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault(legacyCategory, "");
        if ("none".equalsIgnoreCase(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault("luckperms", "");
        if ("none".equalsIgnoreCase(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault("global", "");
        sendWebhook(url, "luckperms-" + context, message);
    }

    void sendRealisticSeasons(String context, String message) {
        String normalized = context == null ? "" : context.trim().toLowerCase(java.util.Locale.ROOT);
        if (isPaused("realistic-seasons", "realistic-seasons." + normalized)) return;
        String url = realisticSeasonsContextWebhooks.getOrDefault(normalized, "");
        if ("none".equalsIgnoreCase(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault("realistic-seasons", "");
        if ("none".equalsIgnoreCase(url)) return;
        if (url.isEmpty()) url = webhooks.getOrDefault("global", "");
        sendWebhook(url, "realistic-seasons-" + normalized, message);
    }

    void pause(String target) {
        String normalized = normalizePauseTarget(target);
        if (normalized.equals("all")) allPaused = true;
        else pausedTargets.add(normalized);
    }

    void resume(String target) {
        String normalized = normalizePauseTarget(target);
        if (normalized.equals("all")) {
            allPaused = false;
            pausedTargets.clear();
        } else {
            pausedTargets.remove(normalized);
        }
    }

    boolean isAllPaused() {
        return allPaused;
    }

    Set<String> getPausedTargets() {
        return Set.copyOf(new TreeSet<>(pausedTargets));
    }

    Set<String> getPauseTargets() {
        TreeSet<String> targets = new TreeSet<>(Set.of("all", "chat", "commands", "join", "leave", "deaths", "explosions",
                "gamemode", "votes", "economy", "staff", "towny", "auraskills", "realistic-seasons", "luckperms",
                "luckperms.user-parents", "luckperms.group-permissions", "luckperms.user-permissions", "luckperms.group-parents",
                "luckperms-parents", "luckperms-permissions", "towny.towns", "towny.nations", "towny.residents", "towny.plots",
                "towny.permissions", "towny.balances", "towny.jails", "towny.wars", "towny.ranks", "towny.settings",
                "realistic-seasons.season-changes", "realistic-seasons.special-events", "realistic-seasons.day-changes",
                "realistic-seasons.weather-reports"));
        for (String skillId : auraSkillWebhooks.keySet()) targets.add("auraskills." + skillId);
        return Set.copyOf(targets);
    }

    private boolean isPaused(String... targets) {
        if (allPaused) return true;
        for (String target : targets) {
            if (pausedTargets.contains(normalizePauseTarget(target))) return true;
        }
        return false;
    }

    private static String normalizePauseTarget(String target) {
        return target == null ? "" : target.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void sendWebhook(String url, String category, String message) {
        if (url.isEmpty() || "none".equalsIgnoreCase(url)) return;
        if (!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/")) {
            plugin.getLogger().warning("Invalid Discord webhook URL configured for '" + category + "'.");
            return;
        }
        String content = "[" + serverName + "] " + message;
        if (suppressMentions) {
            content = content.replace("@", "＠");
        }
        String body = "{\"content\":\"" + jsonEscape(content) + "\",\"allowed_mentions\":{\"parse\":[]}}";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Malformed Discord webhook URL configured for '" + category + "'.");
            return;
        }
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                plugin.getLogger().warning("Discord webhook for '" + category + "' returned HTTP " + response.statusCode() + ".");
            }
        }).exceptionally(error -> {
            plugin.getLogger().warning("Could not send Discord log for '" + category + "': " + error.getClass().getSimpleName());
            return null;
        });
    }

    void close() {
        // HttpClient has no explicit close operation on the supported Java baseline.
    }

    static String clean(String text) {
        return text == null ? "" : text.replace("\r", " ").replace("\n", " ");
    }

    private static String normalizeSkillId(String skillId) {
        return skillId == null ? "" : skillId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeWebhookValue(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.equalsIgnoreCase("none") ? "none" : normalized;
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }
}
