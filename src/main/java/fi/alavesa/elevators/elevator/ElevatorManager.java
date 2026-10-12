package fi.alavesa.elevators.elevator;

import fi.alavesa.elevators.ElevatorsPlugin;
import fi.alavesa.elevators.button.ButtonDef;
import fi.alavesa.elevators.type.ElevatorType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** All elevator instances: elevators.yml persistence, ticking, lookup, safe shutdown. */
public final class ElevatorManager {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final ElevatorsPlugin plugin;
    private final File file;
    private final Map<String, Elevator> elevators = new TreeMap<>();

    public ElevatorManager(ElevatorsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "elevators.yml");
    }

    // ---------------------------------------------------------------- persistence

    public void load() {
        shutdownAll();
        elevators.clear();
        sweepStaleEntities();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("elevators");
        int skipped = 0;
        if (root != null) for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            String typeId = s.getString("type", "");
            ElevatorType type = plugin.types().get(typeId);
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (type == null) { plugin.getLogger().warning("elevators.yml: elevator '" + id + "' uses unknown/invalid type '" + typeId + "' - skipped (fix types.yml and /elevator reload)"); skipped++; continue; }
            if (w == null) { plugin.getLogger().warning("elevators.yml: elevator '" + id + "' is in world '" + s.getString("world") + "' which is not loaded - skipped"); skipped++; continue; }
            Elevator e = new Elevator(plugin, id, type, w, s.getDouble("x"), s.getDouble("z"), (float) s.getDouble("yaw"));
            ConfigurationSection floors = s.getConfigurationSection("floors");
            if (floors != null) for (String fname : floors.getKeys(false)) {
                if (!Floor.validName(fname)) { plugin.getLogger().warning("elevators.yml: elevator '" + id + "' floor '" + fname + "' has an invalid name - skipped"); continue; }
                e.core.putFloor(new Floor(fname, floors.getDouble(fname)));
            }
            Floor cur = e.core.floor(s.getString("current", ""));
            if (cur != null) e.core.rest(cur); else if (e.core.nearestFloor() != null) e.core.rest(e.core.nearestFloor());
            ConfigurationSection buttons = s.getConfigurationSection("buttons");
            if (buttons != null) for (String bid : buttons.getKeys(false)) {
                ConfigurationSection bs = buttons.getConfigurationSection(bid);
                if (bs == null) continue;
                ButtonDef b = ButtonDef.fromSection(bid, bs);
                List<String> errors = b.validate(floorNames(e));
                if (!errors.isEmpty()) { plugin.getLogger().warning("elevators.yml: elevator '" + id + "' button '" + bid + "' is invalid and was skipped: " + String.join("; ", errors)); continue; }
                e.buttons.put(bid, b);
            }
            if (e.core.floors().isEmpty()) plugin.getLogger().warning("elevators.yml: elevator '" + id + "' has no floors yet (/elevator floor add " + id + " <name>)");
            elevators.put(id, e);
            e.spawnEntities();
            plugin.buttons().spawnAll(e);
        }
        plugin.getLogger().info("Loaded " + elevators.size() + " elevator(s)" + (skipped > 0 ? ", " + skipped + " skipped" : "") + ".");
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Elevator instances (managed in game with /elevator ...; editable by hand, then /elevator reload)."));
        for (Elevator e : elevators.values()) {
            ConfigurationSection s = y.createSection("elevators." + e.id);
            s.set("type", e.typeId);
            s.set("world", e.world.getName());
            s.set("x", e.ox); s.set("z", e.oz); s.set("yaw", (double) e.yaw);
            for (Floor f : e.core.floors()) s.set("floors." + f.name(), f.y());
            Floor cur = e.core.state() == ElevatorCore.State.MOVING ? e.core.nearestFloor() : e.core.current();
            s.set("current", cur == null ? null : cur.name());
            for (ButtonDef b : e.buttons.values()) b.toSection(s.createSection("buttons." + b.id));
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Could not save elevators.yml: " + ex); }
    }

    public static java.util.Set<String> floorNames(Elevator e) {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (Floor f : e.core.floors()) s.add(f.name());
        return s;
    }

    // ---------------------------------------------------------------- lifecycle

    public Elevator create(String id, ElevatorType type, Location at) {
        double ox = Math.floor(at.getX()) + 0.5, oz = Math.floor(at.getZ()) + 0.5;
        float yaw = Math.round(at.getYaw() / 90f) * 90f;
        Elevator e = new Elevator(plugin, id, type, at.getWorld(), ox, oz, yaw);
        elevators.put(id, e);
        e.spawnEntities();
        save();
        return e;
    }

    public boolean remove(String id) {
        Elevator e = elevators.remove(id);
        if (e == null) return false;
        plugin.buttons().removeAll(e);
        e.shutdown();
        plugin.buttons().cooldowns().clearElevator(id);
        save();
        return true;
    }

    public Elevator get(String id) { return id == null ? null : elevators.get(id.toLowerCase()); }

    public Collection<Elevator> all() { return elevators.values(); }

    /** The elevator whose cabin volume contains the player, else the nearest one within {@code radius}. */
    public Elevator at(Player p, double radius) {
        Elevator best = null;
        double bd = radius * radius;
        for (Elevator e : elevators.values()) {
            if (!e.world.equals(p.getWorld())) continue;
            if (e.isRider(p.getUniqueId()) || e.playersInCabin().contains(p)) return e;
            double d = p.getLocation().distanceSquared(e.origin());
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    public Elevator ridingOrNull(Player p) {
        for (Elevator e : elevators.values()) if (e.isRider(p.getUniqueId())) return e;
        return null;
    }

    public void tick() {
        for (Elevator e : elevators.values()) {
            try {
                e.tick();
            } catch (Throwable t) {
                plugin.getLogger().severe("Elevator '" + e.id + "' failed to tick: " + t);
                t.printStackTrace();
            }
        }
    }

    public void playerQuit(Player p) {
        for (Elevator e : elevators.values()) if (e.isRider(p.getUniqueId())) e.release(p);
    }

    /** Park every cabin at its nearest floor, put riders down, remove all entities. */
    public void shutdownAll() {
        for (Elevator e : elevators.values()) {
            try { plugin.buttons().removeAll(e); e.shutdown(); } catch (Throwable t) { plugin.getLogger().warning("Shutdown of '" + e.id + "': " + t); }
        }
    }

    /** Types were reloaded: re-bind every elevator to the new type objects. */
    public void retypeAll() {
        for (Elevator e : elevators.values()) {
            ElevatorType t = plugin.types().get(e.typeId);
            if (t == null) plugin.getLogger().warning("Elevator '" + e.id + "': type '" + e.typeId + "' no longer exists/valid - keeping the old settings until fixed");
            else e.retype(t);
        }
    }

    /** Remove display/seat/button entities left behind by a crash or /reload (they are never saved to disk). */
    public void sweepStaleEntities() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) for (Entity en : w.getEntities()) {
            if (en.getPersistentDataContainer().has(plugin.keyElevator(), PersistentDataType.STRING)) { en.eject(); en.remove(); n++; }
        }
        if (n > 0) plugin.getLogger().info("Removed " + n + " stale elevator entit" + (n == 1 ? "y" : "ies") + ".");
    }

    public List<String> ids() { return new ArrayList<>(elevators.keySet()); }
}
