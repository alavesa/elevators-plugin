package fi.alavesa.elevators;

import fi.alavesa.elevators.elevator.Elevator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Button clicks, rider safety (no dismount mid-ride, no fall damage), disconnects. */
public final class ElevatorListener implements Listener {

    private final ElevatorsPlugin plugin;

    ElevatorListener(ElevatorsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e instanceof PlayerInteractAtEntityEvent) return;   // the client sends INTERACT_AT + INTERACT; use one
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (plugin.buttons().click(e.getPlayer(), e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.elevators().playerQuit(e.getPlayer());
    }

    /** Riders stay seated while the cabin moves (shift would drop them into the shaft). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.settings().lockRiders) return;
        Elevator el = plugin.elevators().ridingOrNull(p);
        if (el != null && el.core.moving()) e.setCancelled(true);
    }

    /** A rider teleported away by something else (another plugin, /tp) is released cleanly. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN && e.getFrom().getWorld().equals(e.getTo().getWorld()) && e.getFrom().distanceSquared(e.getTo()) < 25) return;
        Elevator el = plugin.elevators().ridingOrNull(e.getPlayer());
        if (el != null) el.forget(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL && e.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION && e.getCause() != EntityDamageEvent.DamageCause.FLY_INTO_WALL) return;
        if (plugin.elevators().ridingOrNull(p) != null) e.setCancelled(true);
    }
}
