package dev.lightlogs;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.GameMode;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class LoggingListener implements Listener {
    private final LightLogsPlugin plugin;
    private final DiscordLogger logger;

    LoggingListener(LightLogsPlugin plugin, DiscordLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) return;
        logger.send("chat", "💬 **" + DiscordLogger.clean(event.getPlayer().getName()) + "**: " + DiscordLogger.clean(event.getMessage()));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) return;
        String commandName = event.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (plugin.getConfig().getStringList("settings.ignored-commands").stream()
                .anyMatch(ignored -> ignored.equalsIgnoreCase(commandName))) return;
        logger.send("commands", "⌨️ **" + DiscordLogger.clean(event.getPlayer().getName()) + "** ran `" + DiscordLogger.clean(event.getMessage()) + "`");
        if ((plugin.getServer().getPluginManager().getPlugin("StaffPlusPlus") != null
                || plugin.getServer().getPluginManager().getPlugin("Staff++") != null)
                && plugin.getConfig().getStringList("settings.staff-commands").stream()
                .anyMatch(staffCommand -> staffCommand.equalsIgnoreCase(commandName))) {
            logger.send("staff", "🛡️ **" + DiscordLogger.clean(event.getPlayer().getName()) + "** ran Staff++ command `" + DiscordLogger.clean(event.getMessage()) + "`");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        logger.send("join", "➡️ **" + DiscordLogger.clean(event.getPlayer().getName()) + "** joined the server.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        logger.send("leave", "⬅️ **" + DiscordLogger.clean(event.getPlayer().getName()) + "** left the server.");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        logger.send("deaths", "☠️ **" + DiscordLogger.clean(event.getEntity().getName()) + "** " + DiscordLogger.clean(event.getDeathMessage()));
    }

    @EventHandler
    public void onGamemode(PlayerGameModeChangeEvent event) {
        if (event.isCancelled()) return;
        GameMode oldMode = event.getPlayer().getGameMode();
        logger.send("gamemode", "🎮 **" + DiscordLogger.clean(event.getPlayer().getName()) + "** changed gamemode from " + oldMode + " to " + event.getNewGameMode() + ".");
    }

    @EventHandler
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (event.isCancelled()) return;
        logger.send("explosions", "💥 **" + event.getEntityType() + "** exploded at " + coords(event) + " (" + event.blockList().size() + " blocks affected).");
    }

    @EventHandler
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (event.isCancelled()) return;
        logger.send("explosions", "💥 **" + event.getBlock().getType() + "** exploded at " + coords(event) + " (" + event.blockList().size() + " blocks affected).");
    }

    private String coords(Event event) {
        org.bukkit.Location location = event instanceof EntityExplodeEvent entityEvent
                ? entityEvent.getLocation()
                : ((BlockExplodeEvent) event).getBlock().getLocation();
        return DiscordLogger.clean(location.getWorld().getName()) + " " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }
}
