package dev.lightlogs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LightLogsPlugin extends JavaPlugin implements Listener {
    private static final String UPDATE_PERMISSION = "lightlogs.update-notify";
    private static final Pattern JSON_STRING = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private DiscordLogger discordLogger;
    private boolean updateCheckerEnabled;
    private boolean updateCheckStarted;
    private boolean updateAvailable;
    private String latestVersion;
    private String updateDownloadUrl;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        discordLogger = new DiscordLogger(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new LoggingListener(this, discordLogger), this);
        OptionalIntegrations.register(this, discordLogger);
        loadUpdateCheckerSettings();
        logStartupBanner();
        if (updateCheckerEnabled) checkForUpdatesAsync();
    }

    private void logStartupBanner() {
        List<String> integrations = new ArrayList<>();
        addIntegrationIfEnabled(integrations, "CMI", "CMI");
        addIntegrationIfEnabled(integrations, "LuckPerms", "LuckPerms");
        addIntegrationIfEnabled(integrations, "NuVotifier", "NuVotifier");
        if (!integrations.contains("NuVotifier")) addIntegrationIfEnabled(integrations, "Votifier", "Votifier");
        addIntegrationIfEnabled(integrations, "Towny", "Towny");
        addIntegrationIfEnabled(integrations, "AuraSkills", "AuraSkills");
        addIntegrationIfEnabled(integrations, "RealisticSeasons", "RealisticSeasons");
        var staffPlusPlus = getServer().getPluginManager().getPlugin("StaffPlusPlus");
        if (staffPlusPlus != null && staffPlusPlus.isEnabled()) integrations.add("Staff++");
        else addIntegrationIfEnabled(integrations, "Staff++", "Staff++");

        var console = getServer().getConsoleSender();
        console.sendMessage("§8§m----------------------------------------");
        console.sendMessage("§b  ◆ LightLogs");
        console.sendMessage("§7  Version: §f" + getDescription().getVersion());
        List<String> authors = getDescription().getAuthors();
        console.sendMessage("§7  Author: §f" + (authors.isEmpty() ? "LightLogs" : String.join(", ", authors)));
        console.sendMessage("§8§m----------------------------------------");
        console.sendMessage("§b  ✔ §fPlugin enabled successfully!");
        console.sendMessage("§7  Core log categories: §f7 (chat, commands, join, leave, deaths, explosions, gamemode)");
        console.sendMessage("§7  Optional plugins detected: §f" + (integrations.isEmpty() ? "None" : String.join(", ", integrations)));
        console.sendMessage("§7  Configuration: §fplugins/LightLogs/config.yml");
        console.sendMessage("§8§m----------------------------------------");
    }

    private void addIntegrationIfEnabled(List<String> integrations, String pluginName, String label) {
        var optionalPlugin = getServer().getPluginManager().getPlugin(pluginName);
        if (optionalPlugin != null && optionalPlugin.isEnabled()) integrations.add(label);
    }

    private void loadUpdateCheckerSettings() {
        updateCheckerEnabled = getConfig().getBoolean("options.update-checker.enabled", true);
        String repository = getConfig().getString("options.update-checker.repository", "").trim();
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            updateCheckerEnabled = false;
            getLogger().fine("Update checking is disabled until options.update-checker.repository is set to owner/repository.");
        }
    }

    private void checkForUpdatesAsync() {
        if (updateCheckStarted || !updateCheckerEnabled) return;
        updateCheckStarted = true;
        String repository = getConfig().getString("options.update-checker.repository", "").trim();
        BukkitTaskRunner.runAsync(this, () -> {
            HttpURLConnection connection = null;
            try {
                URI endpoint = URI.create("https://api.github.com/repos/" + repository + "/releases/latest");
                connection = (HttpURLConnection) endpoint.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "LightLogs/" + getDescription().getVersion());
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                int response = connection.getResponseCode();
                if (response != HttpURLConnection.HTTP_OK) {
                    getLogger().fine("Update check returned HTTP " + response + ".");
                    return;
                }
                String json;
                try (InputStream input = connection.getInputStream();
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    StringBuilder body = new StringBuilder();
                    char[] buffer = new char[2048];
                    int read;
                    while ((read = reader.read(buffer)) != -1) body.append(buffer, 0, read);
                    json = body.toString();
                }
                String releaseTag = jsonString(json, "tag_name");
                String releasePage = jsonString(json, "html_url");
                String currentVersion = getDescription().getVersion();
                if (releaseTag.isBlank() || releasePage.isBlank() || !isVersionNewer(currentVersion, releaseTag)) return;
                String normalizedVersion = releaseTag.replaceFirst("(?i)^v-?", "");
                getServer().getScheduler().runTask(this, () -> {
                    if (!updateCheckerEnabled) return;
                    latestVersion = normalizedVersion;
                    updateDownloadUrl = releasePage;
                    updateAvailable = true;
                    getLogger().info("LightLogs " + latestVersion + " is available. Current version: " + currentVersion
                            + ". Download: " + updateDownloadUrl);
                    for (Player player : getServer().getOnlinePlayers()) {
                        if (player.hasPermission(UPDATE_PERMISSION)) notifyPlayerOfUpdate(player);
                    }
                });
            } catch (Exception exception) {
                getLogger().fine("Update check failed: " + exception.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private String jsonString(String json, String key) {
        Matcher matcher = JSON_STRING.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) return matcher.group(2).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return "";
    }

    private boolean isVersionNewer(String currentVersion, String candidateVersion) {
        List<Integer> current = versionParts(currentVersion);
        List<Integer> candidate = versionParts(candidateVersion);
        if (current.isEmpty() || candidate.isEmpty()) return false;
        for (int i = 0; i < Math.max(current.size(), candidate.size()); i++) {
            int currentPart = i < current.size() ? current.get(i) : 0;
            int candidatePart = i < candidate.size() ? candidate.get(i) : 0;
            if (candidatePart != currentPart) return candidatePart > currentPart;
        }
        return false;
    }

    private List<Integer> versionParts(String version) {
        Matcher matcher = Pattern.compile("\\d+").matcher(version);
        List<Integer> parts = new ArrayList<>();
        while (matcher.find()) {
            try { parts.add(Integer.parseInt(matcher.group())); }
            catch (NumberFormatException ignored) { return List.of(); }
        }
        return parts;
    }

    private void notifyPlayerOfUpdate(Player player) {
        Component message = Component.text("[LightLogs] Version " + latestVersion + " is available. ")
                .append(Component.text("Open the release page.").clickEvent(ClickEvent.openUrl(updateDownloadUrl)));
        player.sendMessage(message);
    }

    private static final class BukkitTaskRunner {
        private BukkitTaskRunner() {}
        static void runAsync(JavaPlugin plugin, Runnable task) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    @Override
    public void onDisable() {
        if (discordLogger != null) {
            discordLogger.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lightlogs")) return false;
        if (!sender.hasPermission("lightlogs.admin")) {
            sender.sendMessage("You do not have permission to do that.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            discordLogger.refresh();
            loadUpdateCheckerSettings();
            if (updateCheckerEnabled) checkForUpdatesAsync();
            sender.sendMessage("LightLogs configuration reloaded.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            if (discordLogger.isAllPaused()) sender.sendMessage("LightLogs logging is globally paused.");
            else sender.sendMessage("LightLogs logging is active unless individual targets are paused.");
            var paused = discordLogger.getPausedTargets();
            sender.sendMessage(paused.isEmpty() ? "No individual log targets are paused."
                    : "Paused targets: " + String.join(", ", paused));
            return true;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("pause") || args[0].equalsIgnoreCase("resume"))) {
            String target = args[1].toLowerCase(Locale.ROOT);
            if (!discordLogger.getPauseTargets().contains(target)) {
                sender.sendMessage("Unknown log target '" + args[1] + "'. Use tab completion or /lightlogs status.");
                return true;
            }
            if (args[0].equalsIgnoreCase("pause")) {
                discordLogger.pause(target);
                sender.sendMessage(target.equals("all") ? "All LightLogs output is now paused."
                        : "LightLogs output paused for '" + target + "'.");
            } else {
                discordLogger.resume(target);
                sender.sendMessage(target.equals("all") ? "All LightLogs pause states cleared."
                        : "LightLogs output resumed for '" + target + "'.");
            }
            return true;
        }

        sender.sendMessage("Usage: /lightlogs <reload|status|pause|resume> [all|log-target]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("lightlogs.admin")) return List.of();
        if (args.length == 1) return matching(List.of("reload", "status", "pause", "resume"), args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("pause") || args[0].equalsIgnoreCase("resume"))) {
            return matching(discordLogger.getPauseTargets().stream().sorted().toList(), args[1]);
        }
        return List.of();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (updateCheckerEnabled && updateAvailable && event.getPlayer().hasPermission(UPDATE_PERMISSION)) {
            notifyPlayerOfUpdate(event.getPlayer());
        }
    }

    private static List<String> matching(List<String> choices, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String choice : choices) {
            if (choice.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) result.add(choice);
        }
        return result;
    }
}
