package fi.alavesa.elevators.button;

import fi.alavesa.elevators.ElevatorsPlugin;
import fi.alavesa.elevators.elevator.Elevator;
import fi.alavesa.elevators.elevator.ElevatorCore;
import fi.alavesa.elevators.elevator.Floor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Button entities: one {@link Interaction} (the clickable box), an optional {@link ItemDisplay} (its
 * model) and an optional {@link TextDisplay} (its label) per {@link ButtonDef}. All are non-persistent,
 * tagged with the elevator + button id, rebuilt on load and removed on unload. Clicks are resolved by the
 * tags, so a button can never fire for the wrong elevator.
 */
public final class ButtonManager {

    public static final String ROLE_BUTTON = "button", ROLE_BUTTON_MODEL = "button-model", ROLE_BUTTON_LABEL = "button-label";

    private record Live(Interaction box, ItemDisplay model, TextDisplay label) { }

    private final ElevatorsPlugin plugin;
    private final Map<String, Live> live = new HashMap<>();              // elevatorId|buttonId
    private final ButtonCooldowns cooldowns = new ButtonCooldowns();
    private final Map<UUID, Long> debounce = new HashMap<>();
    private long tick;

    public ButtonManager(ElevatorsPlugin plugin) { this.plugin = plugin; }

    public ButtonCooldowns cooldowns() { return cooldowns; }

    public void tick() {
        tick++;
        if (tick % 200 == 0) { cooldowns.prune(tick); debounce.values().removeIf(v -> v < tick); }
    }

    public long now() { return tick; }

    private static String key(Elevator e, ButtonDef b) { return e.id + "|" + b.id; }

    /** World position of a button right now. */
    public Location position(Elevator e, ButtonDef b) {
        double y;
        if (b.inCabin()) y = e.cabinY();
        else { Floor f = e.core.floor(b.space); y = f == null ? e.cabinY() : f.y(); }
        return new Location(e.world, e.ox + b.dx, y + b.dy, e.oz + b.dz, e.yaw, 0);
    }

    public void spawnAll(Elevator e) {
        for (ButtonDef b : e.buttons.values()) spawn(e, b);
    }

    public void spawn(Elevator e, ButtonDef b) {
        remove(e, b);
        Location at = position(e, b);
        Interaction box = e.world.spawn(at, Interaction.class, i -> {
            i.setPersistent(false);
            i.setInteractionWidth((float) b.width);
            i.setInteractionHeight((float) b.height);
            i.setResponsive(true);
            tag(i, e, b, ROLE_BUTTON);
        });
        ItemDisplay model = null;
        if (!b.model.isEmpty()) {
            model = e.world.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBillboard(Display.Billboard.FIXED);
                d.setTeleportDuration(plugin.settings().teleportDuration);
                d.setInterpolationDuration(0);
                d.setItemStack(e.modelItem(b.model));
                tag(d, e, b, ROLE_BUTTON_MODEL);
            });
        }
        TextDisplay label = null;
        if (!b.label.isEmpty()) {
            Location lat = at.clone().add(0, b.height + 0.1, 0);
            label = e.world.spawn(lat, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setTeleportDuration(plugin.settings().teleportDuration);
                t.setInterpolationDuration(0);
                t.setSeeThrough(false);
                t.setShadowed(true);
                t.text(LegacyComponentSerializer.legacyAmpersand().deserialize(b.label));
                float s = plugin.settings().labelScale;
                t.setTransformation(new org.bukkit.util.Transformation(new Vector3f(), new org.joml.AxisAngle4f(), new Vector3f(s, s, s), new org.joml.AxisAngle4f()));
                tag(t, e, b, ROLE_BUTTON_LABEL);
            });
        }
        live.put(key(e, b), new Live(box, model, label));
    }

    private void tag(Entity en, Elevator e, ButtonDef b, String role) {
        en.getPersistentDataContainer().set(plugin.keyElevator(), PersistentDataType.STRING, e.id);
        en.getPersistentDataContainer().set(plugin.keyButton(), PersistentDataType.STRING, b.id);
        en.getPersistentDataContainer().set(plugin.keyRole(), PersistentDataType.STRING, role);
    }

    public void remove(Elevator e, ButtonDef b) {
        Live l = live.remove(key(e, b));
        if (l == null) return;
        if (l.box != null) l.box.remove();
        if (l.model != null) l.model.remove();
        if (l.label != null) l.label.remove();
    }

    public void removeAll(Elevator e) {
        for (ButtonDef b : new ArrayList<>(e.buttons.values())) remove(e, b);
    }

    /** Cabin-space buttons follow the cabin; called from the elevator's move/arrive. */
    public void moveCabinButtons(Elevator e, double cabinY) {
        for (ButtonDef b : e.buttons.values()) {
            if (!b.inCabin()) continue;
            Live l = live.get(key(e, b));
            if (l == null) continue;
            Location at = new Location(e.world, e.ox + b.dx, cabinY + b.dy, e.oz + b.dz, e.yaw, 0);
            if (l.box != null && l.box.isValid()) l.box.teleport(at);
            if (l.model != null && l.model.isValid()) l.model.teleport(at);
            if (l.label != null && l.label.isValid()) l.label.teleport(at.clone().add(0, b.height + 0.1, 0));
        }
    }

    /** True when the entity is one of our button boxes. */
    public boolean isButton(Entity en) {
        String role = en.getPersistentDataContainer().get(plugin.keyRole(), PersistentDataType.STRING);
        return ROLE_BUTTON.equals(role);
    }

    /** Handle a right-click on an interaction entity. Returns true when it was one of ours. */
    public boolean click(Player p, Entity en) {
        if (!isButton(en)) return false;
        String eid = en.getPersistentDataContainer().get(plugin.keyElevator(), PersistentDataType.STRING);
        String bid = en.getPersistentDataContainer().get(plugin.keyButton(), PersistentDataType.STRING);
        Elevator e = plugin.elevators().get(eid);
        ButtonDef b = e == null ? null : e.buttons.get(bid);
        if (e == null || b == null) {
            plugin.getLogger().warning("Removed an orphaned button entity (" + eid + "/" + bid + ")");
            en.remove();
            return true;
        }
        Long last = debounce.get(p.getUniqueId());
        if (last != null && tick - last < plugin.settings().debounceTicks) return true;
        debounce.put(p.getUniqueId(), tick);
        press(p, e, b);
        return true;
    }

    /** The button's action, with authorisation and cooldown. Also used by tests of the command path. */
    public void press(Player p, Elevator e, ButtonDef b) {
        String deny = ButtonAuthorizer.denyButton(b, e.type(), p::hasPermission);
        if (deny != null) { plugin.msg(p, "You may not use this (" + deny + ")."); return; }
        int cd = b.cooldownOr(e.type().buttonCooldown);
        if (!cooldowns.tryUse(p.getUniqueId(), e.id, b.id, tick, cd)) {
            long left = cooldowns.remaining(p.getUniqueId(), e.id, b.id, tick);
            if (left > 5) p.sendActionBar(net.kyori.adventure.text.Component.text("Wait " + (left / 20.0) + " s"));
            return;
        }
        switch (b.action) {
            case FLOOR, CALL -> {
                Floor f = e.core.floor(b.floor);
                if (f == null) { plugin.msg(p, "This button points to a floor that no longer exists (" + b.floor + ")."); return; }
                feedback(p, e, f, e.core.request(f));
            }
            case OPEN -> { if (e.core.moving()) plugin.msg(p, "The elevator is moving."); else { e.core.openDoors(); p.sendActionBar(net.kyori.adventure.text.Component.text("Doors open")); } }
            case CLOSE -> { if (e.core.doorsOpen()) { e.core.closeDoors(); p.sendActionBar(net.kyori.adventure.text.Component.text("Doors closing")); } }
            case STOP -> { Floor f = e.emergencyStop(); plugin.msg(p, f == null ? "Stopped." : "Emergency stop - stopping at " + f.name() + "."); }
        }
    }

    public void feedback(Player p, Elevator e, Floor f, ElevatorCore.Result r) {
        switch (r) {
            case OK -> p.sendActionBar(net.kyori.adventure.text.Component.text("Elevator " + (e.isRider(p.getUniqueId()) || e.playersInCabin().contains(p) ? "going to " : "coming from " + (e.core.current() == null ? "?" : e.core.current().name()) + " to ") + f.name()));
            case QUEUED -> p.sendActionBar(net.kyori.adventure.text.Component.text("Floor " + f.name() + " queued (" + e.core.queue().size() + " waiting)"));
            case ALREADY_THERE -> p.sendActionBar(net.kyori.adventure.text.Component.text("Already at " + f.name() + " - doors open"));
            case BUSY -> p.sendActionBar(net.kyori.adventure.text.Component.text("Already heading to " + f.name()));
            case OBSTRUCTED -> plugin.msg(p, "Floor " + f.name() + " is obstructed" + obstruction(e, f) + ".");
            case INVALID_FLOOR -> plugin.msg(p, "Unknown floor '" + f.name() + "'. Floors: " + String.join(", ", plugin.elevators().floorNames(e)));
            case NO_FLOORS -> plugin.msg(p, "This elevator has no floors configured.");
        }
    }

    private static String obstruction(Elevator e, Floor f) {
        var b = e.obstructionAt(f.y());
        return b == null ? "" : " by " + b.getType().name().toLowerCase() + " at " + b.getX() + " " + b.getY() + " " + b.getZ();
    }

    public List<String> liveKeys() { return new ArrayList<>(live.keySet()); }
}
