package network.warzone.tgm.modules.controlpoint;

import com.google.common.collect.Sets;
import lombok.Getter;
import network.warzone.tgm.TGM;
import network.warzone.tgm.modules.SpectatorModule;
import network.warzone.tgm.modules.region.Region;
import network.warzone.tgm.modules.region.RegionSave;
import network.warzone.tgm.modules.team.MatchTeam;
import network.warzone.tgm.modules.team.TeamChangeEvent;
import network.warzone.tgm.modules.team.TeamManagerModule;
import network.warzone.tgm.util.Blocks;
import network.warzone.tgm.util.ColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Set;

/**
 * Not a module! Other modules should initialize these and keep track of them.
 *
 * Must register listener on load.
 */
@Getter
public class ControlPoint implements Listener {
    public static final ChatColor COLOR_NEUTRAL_TEAM = ChatColor.WHITE;

    public static final String SYMBOL_CP_INCOMPLETE = "\u29be";     // ⦾
    public static final String SYMBOL_CP_COMPLETE = "\u29bf";       // ⦿

    public static final long TICK_RATE = 10;

    private final ControlPointDefinition definition;

    private final Region region;
    private final RegionSave regionSave;
    private final ControlPointService controlPointService;

    private final Set<Player> playersOnPoint = Sets.newHashSet();

    private MatchTeam controller = null;

    private int progress = 0;
    private MatchTeam progressingTowardsTeam = null;

    private boolean initialCapture = true;

    private int runnableId = -1;

    public ControlPoint(ControlPointDefinition controlPointDefinition, Region region, ControlPointService controlPointService) {
        this.definition = controlPointDefinition;
        this.region = region;
        this.controlPointService = controlPointService;

        regionSave = new RegionSave(region);
    }

    public boolean isInProgress() {
        return progress > 0 && progress < definition.getMaxProgress();
    }

    private void handlePlayerMove(Player player, Location to) {
        if(TGM.get().getModule(SpectatorModule.class).isSpectating(player)) return;

        if (!player.isDead() && region.contains(to)) {
            playersOnPoint.add(player);
        } else {
            playersOnPoint.remove(player);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        handlePlayerMove(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        handlePlayerMove(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onTeamChange(TeamChangeEvent event) {
        if (event.isCancelled()) return;
        this.playersOnPoint.remove(event.getPlayerContext().getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.playersOnPoint.remove(event.getPlayer());
    }

    public void enable() {
        HashMap<MatchTeam, Integer> holding = new HashMap<>();

        runnableId = Bukkit.getScheduler().runTaskTimer(TGM.get(), () -> {
            holding.clear();

            for (MatchTeam matchTeam : TGM.get().getModule(TeamManagerModule.class).getTeams()) {
                if (matchTeam.isSpectator()) continue;

                for (Player player : playersOnPoint) {
                    if (matchTeam.containsPlayer(player)) {
                        holding.put(matchTeam, holding.getOrDefault(matchTeam, 0) + 1);
                    }
                }
            }

            MatchTeam most = null;
            int mostCount = 0;
            for (MatchTeam matchTeam : holding.keySet()) {
                if (most == null) {
                    most = matchTeam;
                } else {
                    if (holding.get(matchTeam).equals(holding.get(most))) {
                        mostCount++;
                    } else if (holding.get(matchTeam) > holding.get(most)) {
                        most = matchTeam;
                        mostCount = 0;
                    }
                }
            }

            if (most != null && mostCount == 0) {
                handleCap(most);
            } else {
                if (controller != null) {
                    controlPointService.holding(controller);
                }
            }
        }, TICK_RATE, TICK_RATE).getTaskId();

        TGM.registerEvents(this);
    }

    private void handleCap(MatchTeam matchTeam) {
        boolean isInitial = initialCapture;
        if (progressingTowardsTeam == null) { //switch from neutral to progressing
            progressingTowardsTeam = matchTeam;
            progress++;
            controlPointService.capturing(matchTeam, progress, definition.getMaxProgress(), true);
        } else {
            if (matchTeam == progressingTowardsTeam) {
                if (progress < definition.getMaxProgress()) {
                    progress++; //don't go over the max cap number.
                    controlPointService.capturing(matchTeam, progress, definition.getMaxProgress(), true);
                }
            } else {
                progress--;
                controlPointService.capturing(matchTeam, progress, definition.getMaxProgress(), false);
            }

            if (progress <= 0) {
                progressingTowardsTeam = matchTeam; //change directions

                if (controller != null) {
                    controlPointService.lost(controller);
                    controller = null;
                }
            } else if (progress >= definition.getMaxProgress() && matchTeam == progressingTowardsTeam) {
                if (controller == null) {
                    controller = matchTeam;
                    controlPointService.captured(matchTeam);
                    if (initialCapture) initialCapture = false;
                } else {
                    controlPointService.holding(matchTeam);
                }
            } else { //hill isn't at 100%, but the owning team should still get points.
                if (controller != null) {
                    controlPointService.holding(controller);
                }
            }
        }

        renderBlocks(matchTeam, isInitial);
    }

    public int getPercent() {
        return Math.min(100, Math.max(0, (progress * 100) / definition.getMaxProgress()));
    }

    private void renderBlocks(MatchTeam matchTeam, boolean isInitial) {
        ChatColor color1 = progressingTowardsTeam.getColor();
        ChatColor color2 = controller != null && matchTeam == controller ? controller.getColor() : (isInitial ? ChatColor.RESET : ChatColor.WHITE);
        Location center = region.getCenter();
        double x = center.getX();
        double z = center.getZ();
        double percent = Math.toRadians(getPercent() * 3.6);
        for (Block block : region.getBlocks()) {
            if (!Blocks.isVisualMaterial(block.getType())) continue;
            double dx = block.getX() - x;
            double dz = block.getZ() - z;
            double angle = Math.atan2(dz, dx);
            if (angle < 0) angle += 2 * Math.PI;
            ChatColor color = angle < percent ? color1 : color2;
            if (color != ChatColor.RESET) block.setType(ColorConverter.convertChatColorToColoredBlock(block.getType(), color));
        }
    }

    public void unload() {
        Bukkit.getScheduler().cancelTask(runnableId);
        HandlerList.unregisterAll(this);

        playersOnPoint.clear();
        regionSave.clear();
    }
}