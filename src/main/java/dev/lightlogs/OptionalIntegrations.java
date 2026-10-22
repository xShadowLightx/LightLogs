package dev.lightlogs;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.function.Consumer;

/** Optional integrations are discovered at runtime so LightLogs has no runtime dependencies on them. */
final class OptionalIntegrations {
    private OptionalIntegrations() {}

    static void register(LightLogsPlugin plugin, DiscordLogger logger) {
        registerVotifier(plugin, logger);
        registerCmiEconomy(plugin, logger);
        registerLuckPerms(plugin, logger);
        registerTowny(plugin, logger);
        registerAuraSkills(plugin, logger);
        registerRealisticSeasons(plugin, logger);
        registerStaffPlusPlus(plugin, logger);
    }

    private static void registerVotifier(LightLogsPlugin plugin, DiscordLogger logger) {
        Plugin votifier = plugin.getServer().getPluginManager().getPlugin("NuVotifier");
        if (votifier == null) votifier = plugin.getServer().getPluginManager().getPlugin("Votifier");
        if (votifier == null || !votifier.isEnabled()) return;
        try {
            Class<?> rawEvent = Class.forName("com.vexsoftware.votifier.model.VotifierEvent", true, votifier.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(rawEvent)) throw new IllegalStateException("Votifier event is not a Bukkit event");
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) rawEvent;
            Listener listener = new Listener() {};
            plugin.getServer().getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR,
                    (ignored, event) -> {
                        try {
                            Object vote = event.getClass().getMethod("getVote").invoke(event);
                            String player = callString(vote, "getUsername");
                            String service = callString(vote, "getServiceName");
                            logger.send("votes", "🗳️ **" + DiscordLogger.clean(player) + "** voted on **" + DiscordLogger.clean(service) + "**.");
                        } catch (ReflectiveOperationException exception) {
                            plugin.getLogger().warning("Could not read a NuVotifier vote event: " + exception.getClass().getSimpleName());
                        }
                    }, plugin, true);
        } catch (ReflectiveOperationException | IllegalStateException exception) {
            plugin.getLogger().warning("NuVotifier was found, but its vote event could not be registered.");
        }
    }

    private static void registerLuckPerms(LightLogsPlugin plugin, DiscordLogger logger) {
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) return;
        try {
            ClassLoader loader = luckPerms.getClass().getClassLoader();
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader);
            Object api = provider.getMethod("get").invoke(null);
            Object eventBus = api.getClass().getMethod("getEventBus").invoke(api);
            subscribe(plugin, logger, eventBus, loader, "NodeAddEvent", true);
            subscribe(plugin, logger, eventBus, loader, "NodeRemoveEvent", false);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning("LuckPerms was found, but its change events could not be registered.");
        }
    }

    private static void registerCmiEconomy(LightLogsPlugin plugin, DiscordLogger logger) {
        Plugin cmi = plugin.getServer().getPluginManager().getPlugin("CMI");
        if (cmi == null || !cmi.isEnabled()) return;
        try {
            Class<?> rawEvent = Class.forName("com.Zrips.CMI.events.CMIUserBalanceChangeEvent", true, cmi.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(rawEvent)) throw new IllegalStateException("CMI balance event is not a Bukkit event");
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) rawEvent;
            plugin.getServer().getPluginManager().registerEvent(eventClass, new Listener() {}, EventPriority.MONITOR,
                    (ignored, event) -> logCmiEconomyChange(logger, event), plugin, true);
            plugin.getLogger().info("Connected CMI economy change logging.");
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException exception) {
            plugin.getLogger().warning("CMI was found, but its economy change event could not be registered.");
        }
    }

    private static void logCmiEconomyChange(DiscordLogger logger, Event event) {
        try {
            try {
                Method isCancelled = event.getClass().getMethod("isCancelled");
                if (Boolean.TRUE.equals(isCancelled.invoke(event))) return;
            } catch (ReflectiveOperationException ignored) {
                // Non-cancellable event.
            }
            Object user = event.getClass().getMethod("getUser").invoke(event);
            String userName = economyName(user);
            double before = ((Number) event.getClass().getMethod("getFrom").invoke(event)).doubleValue();
            double after = ((Number) event.getClass().getMethod("getTo").invoke(event)).doubleValue();
            String action = callString(event, "getActionType");
            String sourceName = "";
            try {
                Object source = event.getClass().getMethod("getSource").invoke(event);
                if (source != null) sourceName = economyName(source);
            } catch (ReflectiveOperationException ignored) {
                // Source is not available in every CMI API version.
            }
            double delta = after - before;
            String direction = delta > 0 ? "increased" : delta < 0 ? "decreased" : "changed";
            String message = "💰 CMI economy: **" + DiscordLogger.clean(userName) + "** balance " + direction + " from **"
                    + money(before) + "** to **" + money(after) + "** (" + (delta > 0 ? "+" : "") + money(delta) + ")"
                    + (action.isBlank() ? "" : " — " + DiscordLogger.clean(action))
                    + (sourceName.isBlank() ? "" : "; source: **" + DiscordLogger.clean(sourceName) + "**") + ".";
            logger.send("economy", message);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // Skip incompatible event shapes without affecting CMI or the server.
        }
    }

    private static String economyName(Object account) {
        for (String method : List.of("getName", "getDisplayName", "getPlayerName")) {
            try {
                Object value = account.getClass().getMethod(method).invoke(account);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            } catch (ReflectiveOperationException ignored) {
                // Try the next API naming convention.
            }
        }
        return String.valueOf(account);
    }

    private static String money(double amount) {
        return String.format(java.util.Locale.ROOT, "%.2f", amount);
    }

    private static void subscribe(LightLogsPlugin plugin, DiscordLogger logger, Object eventBus,
                                 ClassLoader loader, String eventName, boolean added) throws ReflectiveOperationException {
        Class<?> eventType = Class.forName("net.luckperms.api.event.node." + eventName, true, loader);
        Method subscribe = eventBus.getClass().getMethod("subscribe", Object.class, Class.class, Consumer.class);
        Consumer<Object> handler = event -> logLuckPermsChange(logger, event, added);
        subscribe.invoke(eventBus, plugin, eventType, handler);
    }

    private static void logLuckPermsChange(DiscordLogger logger, Object event, boolean added) {
        try {
            Object target = event.getClass().getMethod("getTarget").invoke(event);
            Object node = event.getClass().getMethod("getNode").invoke(event);
            Object key = node.getClass().getMethod("getKey").invoke(node);
            String keyName = String.valueOf(key.getClass().getMethod("getKey").invoke(key));
            String value = String.valueOf(key.getClass().getMethod("getValue").invoke(key));
            String targetType = target.getClass().getSimpleName().equals("User") ? "user" : "group";
            String targetName;
            try {
                targetName = callString(target, targetType.equals("user") ? "getUsername" : "getName");
            } catch (ReflectiveOperationException exception) {
                targetName = callString(target, "getName");
            }
            boolean parentChange = keyName.equalsIgnoreCase("group");
            boolean userParentChange = targetType.equals("user") && parentChange;
            boolean groupPermissionChange = targetType.equals("group") && !parentChange;
            String legacyCategory = parentChange ? "luckperms-parents" : "luckperms-permissions";
            String context = userParentChange ? "user-parents" : groupPermissionChange ? "group-permissions"
                    : parentChange ? "group-parents" : "user-permissions";
            String action = added ? "added" : "removed";
            String description = parentChange
                    ? "parent group `" + DiscordLogger.clean(value) + "`"
                    : "node `" + DiscordLogger.clean(keyName + ":" + value) + "`";
            logger.sendLuckPerms(context, legacyCategory, "🔐 LuckPerms " + action + " " + description + " for " + targetType + " **" + DiscordLogger.clean(targetName) + "**.");
        } catch (ReflectiveOperationException ignored) {
            // A plugin API change should not interrupt LuckPerms or the server.
        }
    }

    private static String callString(Object target, String method) throws ReflectiveOperationException {
        return String.valueOf(target.getClass().getMethod(method).invoke(target));
    }

    private static void registerTowny(LightLogsPlugin plugin, DiscordLogger logger) {
        Plugin towny = plugin.getServer().getPluginManager().getPlugin("Towny");
        if (towny == null || !towny.isEnabled()) return;
        registerMatchingEventsByContext(plugin, towny, logger, simpleName -> townyContext(simpleName));
    }

    private static void registerAuraSkills(LightLogsPlugin plugin, DiscordLogger logger) {
        Plugin auraSkills = plugin.getServer().getPluginManager().getPlugin("AuraSkills");
        if (auraSkills == null || !auraSkills.isEnabled()) return;
        registerMatchingEvents(plugin, auraSkills, logger, "auraskills", simpleName -> simpleName.equals("SkillLevelUpEvent"));
    }

    private static void registerRealisticSeasons(LightLogsPlugin plugin, DiscordLogger logger) {
        Plugin seasons = plugin.getServer().getPluginManager().getPlugin("RealisticSeasons");
        if (seasons == null || !seasons.isEnabled()) return;
        registerMatchingEventsByContext(plugin, seasons, logger, OptionalIntegrations::realisticSeasonsContext);
    }

    private static String realisticSeasonsContext(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.equals("seasonchangeevent")) return "season-changes";
        if (n.equals("seasoneventstart") || n.equals("seasoneventend") || n.contains("specialevent")) return "special-events";
        if (n.equals("daychangeevent")) return "day-changes";
        if (n.endsWith("event") && (n.contains("weatherreport") || n.contains("weatherforecast")
                || n.contains("dailyforecast") || n.contains("dailyweather") || n.contains("forecastreport"))) return "weather-reports";
        return null;
    }

    private static void registerStaffPlusPlus(LightLogsPlugin plugin, DiscordLogger logger) {
        Plugin staff = plugin.getServer().getPluginManager().getPlugin("StaffPlusPlus");
        if (staff == null || !staff.isEnabled()) staff = plugin.getServer().getPluginManager().getPlugin("Staff++");
        if (staff == null || !staff.isEnabled()) return;
        Plugin staffPlugin = staff;
        registerMatchingEvents(plugin, staffPlugin, logger, "staff", simpleName ->
                simpleName.endsWith("Event") && simpleName.matches("(?i).*(ban|mute|warn|report|ticket|freeze|staffmode|investigation|punish|appeal).*"));
    }

    @FunctionalInterface
    private interface EventNameFilter {
        boolean matches(String simpleName);
    }

    private static void registerMatchingEvents(LightLogsPlugin plugin, Plugin source, DiscordLogger logger,
                                               String category, EventNameFilter filter) {
        registerMatchingEventsByContext(plugin, source, logger, simpleName -> filter.matches(simpleName) ? category : null);
    }

    private static void registerMatchingEventsByContext(LightLogsPlugin plugin, Plugin source, DiscordLogger logger,
                                                        java.util.function.Function<String, String> contextResolver) {
        try {
            URI sourceLocation = source.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            File sourceFile = new File(sourceLocation);
            if (!sourceFile.isFile()) return;
            int registered = 0;
            try (JarFile jar = new JarFile(sourceFile)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (!entry.endsWith(".class") || entry.contains("$") || entry.startsWith("META-INF/")) continue;
                    String className = entry.substring(0, entry.length() - 6).replace('/', '.');
                    String simpleName = className.substring(className.lastIndexOf('.') + 1);
                    String context = contextResolver.apply(simpleName);
                    if (context == null) continue;
                    try {
                        Class<?> type = Class.forName(className, false, source.getClass().getClassLoader());
                        if (!Event.class.isAssignableFrom(type) || type.getMethod("getHandlerList") == null) continue;
                        @SuppressWarnings("unchecked")
                        Class<? extends Event> eventType = (Class<? extends Event>) type;
                        plugin.getServer().getPluginManager().registerEvent(eventType, new Listener() {}, EventPriority.MONITOR,
                                (listener, event) -> {
                                    if (context.equals("auraskills")) logAuraSkillLevelUp(logger, event);
                                    else if (source.getName().equalsIgnoreCase("Towny")) logTownyEvent(logger, context, event);
                                    else if (source.getName().equalsIgnoreCase("RealisticSeasons")) logRealisticSeasonsEvent(logger, context, event);
                                    else logGenericEvent(logger, context, source.getName(), event);
                                }, plugin, true);
                        registered++;
                    } catch (ReflectiveOperationException | LinkageError ignored) {
                        // Optional plugin internals vary by release; skip event types we cannot load.
                    }
                }
            }
            if (registered > 0) plugin.getLogger().info("Connected " + source.getName() + " event logging (" + registered + " event types).");
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not inspect " + source.getName() + " events; its integration will be skipped.");
        }
    }

    private static void logRealisticSeasonsEvent(DiscordLogger logger, String context, Event event) {
        try {
            Method isCancelled = event.getClass().getMethod("isCancelled");
            if (Boolean.TRUE.equals(isCancelled.invoke(event))) return;
        } catch (ReflectiveOperationException ignored) {
            // Non-cancellable event.
        }
        List<String> fields = new ArrayList<>();
        for (String getter : List.of("getWorld", "getOldSeason", "getNewSeason", "getFrom", "getTo", "getWeather",
                "getForecast", "getReport", "getCustomEvent", "getEvent", "getDate", "getTemperature", "getChance")) {
            try {
                Object value = event.getClass().getMethod(getter).invoke(event);
                if (value == null) continue;
                if (getter.equals("getCustomEvent") || getter.equals("getEvent")) {
                    try { value = value.getClass().getMethod("getName").invoke(value); }
                    catch (ReflectiveOperationException ignored) { /* Use its string form. */ }
                }
                fields.add(getter.substring(3) + "=" + DiscordLogger.clean(String.valueOf(value)));
            } catch (ReflectiveOperationException ignored) {
                // This event does not expose that field.
            }
        }
        String details = fields.isEmpty() ? "" : " — " + String.join(", ", fields);
        String message = "🍂 **RealisticSeasons / " + context + "** " + event.getClass().getSimpleName() + details;
        if (message.length() > 1900) message = message.substring(0, 1897) + "...";
        logger.sendRealisticSeasons(context, message);
    }

    private static String townyContext(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (!n.endsWith("event")) return null;
        if (n.contains("jail")) return "jails";
        if (n.contains("transaction") || n.contains("bankruptcy") || n.contains("tax") || n.contains("balance")) return "balances";
        if (n.contains("rank")) return "ranks";
        if (n.contains("war") || n.contains("conquer") || n.contains("enemy") || n.contains("allied")) return "wars";
        if (n.contains("permission") || n.contains("trust")) return "permissions";
        if (n.contains("toggle") || n.contains("settingschanged")) return "settings";
        if (n.contains("claim") || n.contains("unclaim") || n.contains("plot") || n.contains("townblock") || n.contains("cede")) return "plots";
        if (n.contains("resident") || n.contains("outlaw") || n.contains("kick") || n.contains("leave")) return "residents";
        if (n.contains("nation")) return "nations";
        if (n.contains("town") || n.contains("ruined") || n.contains("reclaimed")) return "towns";
        return null;
    }

    private static void logTownyEvent(DiscordLogger logger, String context, Event event) {
        try {
            Method isCancelled = event.getClass().getMethod("isCancelled");
            if (Boolean.TRUE.equals(isCancelled.invoke(event))) return;
        } catch (ReflectiveOperationException ignored) {
            // Non-cancellable event.
        }
        List<String> fields = new ArrayList<>();
        for (String getter : List.of("getPlayer", "getActor", "getTown", "getNation", "getResident", "getTownBlock", "getPlot",
                "getTarget", "getReason", "getAction", "getAmount", "getTransaction", "getBalance", "getJail", "getRank",
                "getPermission", "getOldMayor", "getNewMayor", "getMayor", "getAttacker", "getDefender", "getWar", "getFrom", "getTo")) {
            try {
                Object value = event.getClass().getMethod(getter).invoke(event);
                if (value != null) fields.add(getter.substring(3) + "=" + DiscordLogger.clean(String.valueOf(value)));
            } catch (ReflectiveOperationException ignored) {
                // Towny event does not expose that field.
            }
        }
        String details = fields.isEmpty() ? "" : " — " + String.join(", ", fields);
        String message = "🏘️ **Towny / " + context + "** " + event.getClass().getSimpleName() + details;
        if (message.length() > 1900) message = message.substring(0, 1897) + "...";
        logger.sendTowny(context, message);
    }

    private static void logGenericEvent(DiscordLogger logger, String category, String pluginName, Event event) {
        try {
            Method isCancelled = event.getClass().getMethod("isCancelled");
            if (Boolean.TRUE.equals(isCancelled.invoke(event))) return;
        } catch (ReflectiveOperationException ignored) {
            // Non-cancellable event.
        }
        List<String> fields = new ArrayList<>();
        for (String getter : List.of("getPlayer", "getStaff", "getActor", "getTarget", "getVictim", "getReporter", "getReported",
                "getReason", "getTown", "getNation", "getResident", "getTownBlock", "getSkill", "getOldLevel", "getNewLevel",
                "getOldSeason", "getNewSeason", "getWorld", "getAction", "getReport", "getTicket", "getDuration", "getMessage")) {
            try {
                Object value = event.getClass().getMethod(getter).invoke(event);
                if (value != null) fields.add(getter.substring(3) + "=" + DiscordLogger.clean(String.valueOf(value)));
            } catch (ReflectiveOperationException ignored) {
                // This event does not expose that field.
            }
        }
        String details = fields.isEmpty() ? "" : " — " + String.join(", ", fields);
        String message = "📋 **" + DiscordLogger.clean(pluginName) + "** " + event.getClass().getSimpleName() + details;
        if (message.length() > 1900) message = message.substring(0, 1897) + "...";
        logger.send(category, message);
    }

    private static void logAuraSkillLevelUp(DiscordLogger logger, Event event) {
        try {
            Object player = event.getClass().getMethod("getPlayer").invoke(event);
            Object skill = event.getClass().getMethod("getSkill").invoke(event);
            int level = ((Number) event.getClass().getMethod("getLevel").invoke(event)).intValue();
            String playerName = callString(player, "getName");
            String skillId = skill.toString();
            String skillName;
            try {
                skillName = callString(skill, "name");
            } catch (ReflectiveOperationException ignored) {
                skillName = skillId;
            }
            String message = "✨ **" + DiscordLogger.clean(playerName) + "** leveled up **" + DiscordLogger.clean(skillName)
                    + "** from level " + Math.max(0, level - 1) + " to **" + level + "** (`" + DiscordLogger.clean(skillId) + "`).";
            logger.sendAuraSkill(skillId, skillName, message);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            // Skip events whose AuraSkills API shape does not match this known level-up event.
        }
    }
}
