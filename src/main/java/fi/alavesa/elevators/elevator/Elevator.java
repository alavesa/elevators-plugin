package fi.alavesa.elevators.elevator;

import fi.alavesa.elevators.ElevatorsPlugin;
import fi.alavesa.elevators.button.ButtonAuthorizer;
import fi.alavesa.elevators.button.ButtonDef;
import fi.alavesa.elevators.type.ElevatorType;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One elevator in the world: the pure {@link ElevatorCore} plus everything Bukkit - the cabin display,
 * the riders' seats, sounds, obstruction checks and safe exits. Ticked by {@link ElevatorManager}.
 */
public final class Elevator implements ElevatorCore.Port {

    public static final String ROLE_CABIN = "cabin", ROLE_SEAT = "seat";

    private final ElevatorsPlugin plugin;
    public final String id;
    public String typeId;
    private ElevatorType type;
    public final World world;
    public final double ox, oz;
    public final float yaw;
    public final ElevatorCore core;
    public final Map<String, ButtonDef> buttons = new LinkedHashMap<>();

    private ItemDisplay cabin;
    private kr.toxicity.model.api.tracker.EntityTracker tracker;   // BetterModel cabin (null when BetterModel is not active)
    private boolean doorsOpen;
    private final Map<UUID, Seat> riders = new LinkedHashMap<>();
    private int moveSoundTimer, musicTimer;
    private String lastCabinModel = "";

    private record Seat(ArmorStand stand, double x, double z) { }

    public Elevator(ElevatorsPlugin plugin, String id, ElevatorType type, World world, double ox, double oz, float yaw) {
        this.plugin = plugin;
        this.id = id;
        this.type = type;
        this.typeId = type.id;
        this.world = world;
        this.ox = ox;
        this.oz = oz;
        this.yaw = yaw;
        this.core = new ElevatorCore(this, type.speed, type.acceleration, type.doorTime, type.doorCloseDelay);
    }

    public ElevatorType type() { return type; }

    /** Re-point at a (re)loaded type; falls back to the old values if the type vanished. */
    public void retype(ElevatorType t) {
        if (t == null) return;
        type = t;
        typeId = t.id;
        core.configure(t.speed, t.acceleration, t.doorTime, t.doorCloseDelay);
        if (cabin != null) applyCabinModel(true);
    }

    public Location origin() { return new Location(world, ox, core.y(), oz, yaw, 0); }

    public double cabinY() { return core.y(); }

    public Collection<UUID> riders() { return riders.keySet(); }

    public boolean isRider(UUID u) { return riders.containsKey(u); }

    // ---------------------------------------------------------------- entities

    public void spawnEntities() {
        removeEntities();
        Location l = new Location(world, ox, core.y(), oz, yaw, 0);
        cabin = world.spawn(l, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTeleportDuration(plugin.settings().teleportDuration);
            d.setInterpolationDuration(0);
            d.setViewRange(1.5f);
            tag(d, ROLE_CABIN);
        });
        applyCabinModel(true);
    }

    private org.bukkit.scheduler.BukkitTask bmRetry;
    /** BetterModel loads its models asynchronously after it enables (and after a /bettermodel reload): poll until ours is there. */
    private void retryAttachLater(String model) {
        if (bmRetry != null && !bmRetry.isCancelled()) return;
        final int[] left = { 90 };   // 90 x 20 ticks = 90 s
        bmRetry = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var bridge = plugin.bridge();
            if (bridge == null || cabin == null || !cabin.isValid() || --left[0] < 0) { bmRetry.cancel(); bmRetry = null; return; }
            if (!bridge.has(model)) return;
            bmRetry.cancel(); bmRetry = null;
            applyCabinModel(true);
        }, 20L, 20L);
    }

    /** BetterModel renders the cabin: attach (or re-attach) the tracker to the cabin display entity. */
    private boolean attachTracker(String model) {
        var bridge = plugin.bridge();
        if (bridge == null || cabin == null || !cabin.isValid()) return false;
        if (tracker != null) { bridge.close(tracker); tracker = null; }
        if (!bridge.has(model)) { retryAttachLater(model); return false; }
        tracker = bridge.attach(cabin, model, () -> fi.alavesa.elevators.model.ModelBridge.modelYaw(yaw, bridge.yawOffset()), 1.0);
        if (tracker == null) { plugin.getLogger().warning("elevator " + id + ": BetterModel could not attach model '" + model + "' to the cabin"); return false; }
        plugin.getLogger().info("elevator " + id + ": cabin rendered by BetterModel (model " + model + (bridge.hasAnimation(model, "open") ? ", open/close animations" : ", doors via model-open") + (bridge.hasAnimation(model, "move") ? ", move animation" : "") + ")");
        cabin.setItemStack(new ItemStack(org.bukkit.Material.AIR));
        if (doorsOpen && bridge.hasAnimation(model, "open")) bridge.animateHold(tracker, "open");
        return true;
    }

    private void tag(Entity e, String role) {
        e.getPersistentDataContainer().set(plugin.keyElevator(), PersistentDataType.STRING, id);
        e.getPersistentDataContainer().set(plugin.keyRole(), PersistentDataType.STRING, role);
    }

    private void applyCabinModel(boolean force) {
        var bridge = plugin.bridge();
        if (bridge != null && !bridge.has(type.model)) retryAttachLater(type.model);   // BetterModel still loading its models: item cabin for now
        if (bridge != null && bridge.has(type.model)) {
            // Doors: an "open" / "close" animation in the .bbmodel if the model has them, else the model-open variant.
            boolean hasOpenAnim = bridge.hasAnimation(type.model, "open");
            String model = !hasOpenAnim && doorsOpen && !type.modelOpen.isEmpty() && bridge.has(type.modelOpen) ? type.modelOpen : type.model;
            if (force || tracker == null || !model.equals(lastCabinModel)) { lastCabinModel = model; attachTracker(model); }
            if (hasOpenAnim && tracker != null && !force) {
                if (doorsOpen) bridge.animateHold(tracker, "open");
                else if (bridge.hasAnimation(type.model, "close")) { bridge.stop(tracker, "open"); bridge.animateHold(tracker, "close"); }
                else bridge.stop(tracker, "open");
            }
            return;
        }
        String model = doorsOpen && !type.modelOpen.isEmpty() ? type.modelOpen : type.model;
        if (!force && model.equals(lastCabinModel)) return;
        lastCabinModel = model;
        if (cabin != null && cabin.isValid()) cabin.setItemStack(modelItem(model));
    }

    public ItemStack modelItem(String model) {
        ItemStack item = new ItemStack(plugin.settings().baseItem);
        ItemMeta meta = item.getItemMeta();
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    public void removeEntities() {
        if (bmRetry != null) { bmRetry.cancel(); bmRetry = null; }
        if (tracker != null) { var b = plugin.bridge(); if (b != null) b.close(tracker); tracker = null; }
        if (cabin != null) { cabin.remove(); cabin = null; }
        for (Seat s : riders.values()) { s.stand.eject(); s.stand.remove(); }
        riders.clear();
    }

    // ---------------------------------------------------------------- per tick

    public void tick() {
        core.tick();
        if (cabin == null || !cabin.isValid()) spawnEntities();
        if (!riders.isEmpty()) {
            if (!type.music.isEmpty() && --musicTimer <= 0) {
                musicTimer = type.musicInterval;
                for (UUID u : riders.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null) p.playSound(p.getLocation(), type.music, SoundCategory.RECORDS, type.volume, 1f); }
            }
            // a rider that left the seat (died, teleported, admin) stops riding
            for (UUID u : new ArrayList<>(riders.keySet())) {
                Player p = Bukkit.getPlayer(u);
                Seat s = riders.get(u);
                if (p == null || !s.stand.isValid() || !s.stand.getPassengers().contains(p)) {
                    if (p != null && core.moving() && s.stand.isValid()) { s.stand.addPassenger(p); continue; }
                    riders.remove(u);
                    s.stand.remove();
                }
            }
        }
    }

    // ---------------------------------------------------------------- Port

    @Override
    public boolean isObstructed(Floor floor) {
        if (!plugin.settings().obstructionCheck) return false;
        return obstructionAt(floor.y()) != null;
    }

    /** First solid block inside the cabin volume at a cabin Y, or null. */
    public Block obstructionAt(double y) {
        int x0 = (int) Math.floor(ox - type.cabinWidth / 2 + 0.01), x1 = (int) Math.floor(ox + type.cabinWidth / 2 - 0.01);
        int z0 = (int) Math.floor(oz - type.cabinDepth / 2 + 0.01), z1 = (int) Math.floor(oz + type.cabinDepth / 2 - 0.01);
        int y0 = (int) Math.floor(y + 0.01), y1 = (int) Math.floor(y + type.cabinHeight - 0.01);
        for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++) {
            Block b = world.getBlockAt(bx, by, bz);
            if (b.getType().isSolid() && b.isCollidable()) return b;
        }
        return null;
    }

    @Override
    public void onDepart(Floor from, Floor to) {
        if (tracker != null && plugin.bridge() != null && plugin.bridge().hasAnimation(type.model, "move")) plugin.bridge().animate(tracker, "move", true, () -> 1f);
        doorsOpen = false;
        applyCabinModel(false);
        board();
        sound(type.soundStart);
        moveSoundTimer = type.soundMoveInterval;
        musicTimer = 1;
        String msg = "Going to " + to.name();
        for (UUID u : riders.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendActionBar(Component.text(msg)); }
        plugin.getLogger().fine("[" + id + "] depart " + from.name() + " -> " + to.name() + " with " + riders.size() + " rider(s)");
    }

    @Override
    public void onMove(double y, double velocity) {
        Location l = new Location(world, ox, y, oz, yaw, 0);
        if (cabin != null && cabin.isValid()) cabin.teleport(l);
        for (Seat s : riders.values()) {
            if (!s.stand.isValid()) continue;
            Location sl = new Location(world, s.x, y + plugin.settings().seatOffset, s.z, yaw, 0);
            if (s.stand.getPassengers().isEmpty()) s.stand.teleport(sl); else s.stand.teleport(sl, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        plugin.buttons().moveCabinButtons(this, y);
        if (!type.soundMove.isEmpty() && --moveSoundTimer <= 0) { moveSoundTimer = type.soundMoveInterval; sound(type.soundMove); }
    }

    @Override
    public void onArrive(Floor floor) {
        if (tracker != null && plugin.bridge() != null) plugin.bridge().stop(tracker, "move");
        sound(type.soundArrive);
        Location l = new Location(world, ox, floor.y(), oz, yaw, 0);
        if (cabin != null && cabin.isValid()) cabin.teleport(l);
        plugin.buttons().moveCabinButtons(this, floor.y());
        unboard(floor.y(), "Arrived at " + floor.name());
    }

    @Override
    public void onDoors(boolean open) {
        doorsOpen = open;
        applyCabinModel(false);
        sound(open ? type.soundDoorsOpen : type.soundDoorsClose);
    }

    @Override
    public void onDropped(Floor floor, ElevatorCore.Result why) {
        plugin.getLogger().info("[" + id + "] request for floor " + floor.name() + " dropped: " + why);
        for (UUID u : riders.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null) plugin.msg(p, "Floor " + floor.name() + " is obstructed - request cancelled."); }
    }

    private void sound(String s) {
        if (s == null || s.isEmpty()) return;
        try { world.playSound(new Location(world, ox, core.y() + 1, oz), s, SoundCategory.BLOCKS, type.volume, 1f); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- riders

    /** Players standing inside the cabin volume right now. */
    public List<Player> playersInCabin() {
        List<Player> out = new ArrayList<>();
        double y = core.y();
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (Math.abs(l.getX() - ox) <= type.cabinWidth / 2 && Math.abs(l.getZ() - oz) <= type.cabinDepth / 2
                && l.getY() >= y - 0.6 && l.getY() <= y + type.cabinHeight) out.add(p);
        }
        return out;
    }

    /** Seat everybody inside the cabin (authorised, up to capacity). Called when the cabin departs. */
    private void board() {
        List<Player> inside = playersInCabin();
        inside.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(origin()), b.getLocation().distanceSquared(origin())));
        for (Player p : inside) {
            if (riders.containsKey(p.getUniqueId())) continue;
            if (ButtonAuthorizer.denyElevator(type, p::hasPermission) != null) { plugin.msg(p, "You may not ride this elevator."); continue; }
            if (type.capacity > 0 && riders.size() >= type.capacity) { plugin.msg(p, "The elevator is full (" + type.capacity + ")."); continue; }
            Location l = p.getLocation();
            double y = core.y();
            Location seatLoc = new Location(world, l.getX(), y + plugin.settings().seatOffset, l.getZ(), yaw, 0);
            ArmorStand stand = world.spawn(seatLoc, ArmorStand.class, s -> {
                s.setPersistent(false);
                s.setMarker(true);
                s.setInvisible(true);
                s.setSmall(true);
                s.setGravity(false);
                s.setInvulnerable(true);
                s.setBasePlate(false);
                s.setCollidable(false);
                tag(s, ROLE_SEAT);
            });
            stand.addPassenger(p);
            riders.put(p.getUniqueId(), new Seat(stand, l.getX(), l.getZ()));
        }
    }

    /** Put every rider down on the floor at {@code floorY}; falls back to the origin when the spot is unsafe. */
    private void unboard(double floorY, String message) {
        for (Map.Entry<UUID, Seat> e : new ArrayList<>(riders.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Seat s = e.getValue();
            s.stand.eject();
            s.stand.remove();
            if (p == null) continue;
            exitTo(p, s.x, floorY, s.z);
            if (!type.music.isEmpty()) p.stopSound(type.music, SoundCategory.RECORDS);
            if (message != null) p.sendActionBar(Component.text(message));
        }
        riders.clear();
    }

    /** Teleport a player onto solid ground at (x, y, z) or, if there is none, at the elevator origin. */
    public void exitTo(Player p, double x, double y, double z) {
        Location l = p.getLocation();
        Location spot = safeSpot(x, y, z);
        if (spot == null) spot = safeSpot(ox, y, oz);
        if (spot == null) { spot = new Location(world, ox, y, oz); plugin.getLogger().warning("[" + id + "] no solid ground for " + p.getName() + " at floor y=" + y + " - placed at the origin anyway"); }
        spot.setYaw(l.getYaw());
        spot.setPitch(l.getPitch());
        p.teleport(spot, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_VEHICLE);
    }

    private Location safeSpot(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int dy = 0; dy <= plugin.settings().safeExitSearch; dy++) {
            int by = (int) Math.floor(y) - 1 - dy;
            Block ground = world.getBlockAt(bx, by, bz);
            Block feet = world.getBlockAt(bx, by + 1, bz), head = world.getBlockAt(bx, by + 2, bz);
            if (ground.getType().isSolid() && !feet.getType().isSolid() && !head.getType().isSolid()) return new Location(world, x, by + 1, z);
        }
        return null;
    }

    /** Something else moved the player away: drop the seat without teleporting them anywhere. */
    public void forget(UUID u) {
        Seat s = riders.remove(u);
        if (s == null) return;
        s.stand.eject();
        s.stand.remove();
    }

    /** A rider disconnects or is force-released: put them down at the nearest floor. */
    public void release(Player p) {
        Seat s = riders.remove(p.getUniqueId());
        if (s == null) return;
        s.stand.eject();
        s.stand.remove();
        Floor n = core.nearestFloor();
        double y = n == null ? core.y() : n.y();
        exitTo(p, s.x, y, s.z);
        if (!type.music.isEmpty()) p.stopSound(type.music, SoundCategory.RECORDS);
    }

    /** Plugin shutdown / elevator removal: everybody out at the nearest floor, cabin parked there. */
    public void shutdown() {
        Floor n = core.nearestFloor();
        if (n != null) { core.rest(n); }
        unboard(n == null ? core.y() : n.y(), null);
        removeEntities();
    }

    /** Emergency stop; returns the floor the cabin will stop at. */
    public Floor emergencyStop() {
        Floor f = core.stop();
        for (UUID u : riders.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendActionBar(Component.text("Emergency stop - next floor " + (f == null ? "?" : f.name()))); }
        return f;
    }

    public String describe() {
        return id + " (" + typeId + ") @ " + world.getName() + String.format(java.util.Locale.ROOT, " %.1f %.1f %.1f", ox, core.y(), oz) + " " + core.state()
            + (core.current() == null ? "" : " at " + core.current().name()) + (core.target() == null ? "" : " -> " + core.target().name())
            + ", floors " + core.floors().stream().map(f -> f.name() + "=" + (int) f.y()).toList() + ", " + buttons.size() + " button(s), " + riders.size() + " rider(s)";
    }
}
