package fi.alavesa.elevators.command;

import fi.alavesa.elevators.ElevatorsPlugin;
import fi.alavesa.elevators.button.ButtonAction;
import fi.alavesa.elevators.button.ButtonAuthorizer;
import fi.alavesa.elevators.button.ButtonDef;
import fi.alavesa.elevators.elevator.Elevator;
import fi.alavesa.elevators.elevator.ElevatorCore;
import fi.alavesa.elevators.elevator.ElevatorManager;
import fi.alavesa.elevators.elevator.Floor;
import fi.alavesa.elevators.model.Issue;
import fi.alavesa.elevators.type.ElevatorType;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** /elevator ... - player commands (call, go, floors) and the admin editor. */
public final class ElevatorCommand implements TabExecutor {

    private final ElevatorsPlugin plugin;

    public ElevatorCommand(ElevatorsPlugin plugin) { this.plugin = plugin; }

    private static final String ADMIN = "elevators.admin";

    private void ok(CommandSender s, String m) { plugin.msg(s, "§a" + m); }
    private void err(CommandSender s, String m) { plugin.msg(s, "§c" + m); }
    private void info(CommandSender s, String m) { plugin.msg(s, "§7" + m); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(s); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "call" -> call(s, tail(a, 1));
                case "go" -> go(s, tail(a, 1));
                case "floors" -> floors(s, tail(a, 1));
                default -> {
                    if (!s.hasPermission(ADMIN)) { err(s, "No permission."); return true; }
                    switch (sub) {
                        case "type" -> type(s, tail(a, 1));
                        case "create" -> create(s, tail(a, 1));
                        case "remove" -> { if (need(s, a, 2, "remove <elevator>")) return true; if (plugin.elevators().remove(a[1])) ok(s, "removed elevator " + a[1]); else err(s, "no elevator '" + a[1] + "'"); }
                        case "list" -> { if (plugin.elevators().all().isEmpty()) info(s, "no elevators. /elevator create <id> <type>"); for (Elevator e : plugin.elevators().all()) info(s, e.describe()); }
                        case "info" -> { if (need(s, a, 2, "info <elevator>")) return true; Elevator e = elev(s, a[1]); if (e == null) return true; info(s, e.describe()); info(s, "type: " + e.type().describe()); for (ButtonDef b : e.buttons.values()) info(s, "  button " + b.describe()); }
                        case "floor" -> floor(s, tail(a, 1));
                        case "button" -> button(s, tail(a, 1));
                        case "stop" -> { if (need(s, a, 2, "stop <elevator>")) return true; Elevator e = elev(s, a[1]); if (e == null) return true; Floor f = e.emergencyStop(); ok(s, f == null ? "stopped" : "stopping at " + f.name()); }
                        case "pack" -> pack(s);
                        case "models" -> { List<String> names = plugin.exporter().modelNames(); info(s, names.isEmpty() ? "no .bbmodel files in plugins/Elevators/models/" : "models on disk: " + names); info(s, "last exported: " + plugin.exporter().lastExported()); if (plugin.bridge() != null) { StringBuilder b = new StringBuilder(); for (String n : names) b.append(n).append(plugin.bridge().has(n) ? " (BetterModel: loaded as " + plugin.bridge().key(n) + ", animations " + plugin.bridge().animations(n) + ")" : " (BetterModel: NOT loaded yet - see BetterModel's log)").append("; "); info(s, "BetterModel renders the cabins: " + b); } }
                        case "reload" -> { plugin.reloadAll(); ok(s, "reloaded: " + plugin.types().all().size() + " type(s), " + plugin.elevators().all().size() + " elevator(s)"); }
                        default -> help(s);
                    }
                }
            }
        } catch (NumberFormatException ex) {
            err(s, "not a number: " + ex.getMessage());
        }
        return true;
    }

    private void help(CommandSender s) {
        info(s, "§6Elevators " + plugin.getDescription().getVersion());
        info(s, "/elevator call [elevator] [floor]   - call the nearest elevator to your floor (or a named floor)");
        info(s, "/elevator go <floor>                - choose a floor while standing in a cabin");
        info(s, "/elevator floors [elevator]         - list floors");
        if (!s.hasPermission(ADMIN)) return;
        info(s, "§eAdmin");
        info(s, "/elevator type create <id> <model> | set <id> <key> <value> | list | info <id> | remove <id>");
        info(s, "/elevator create <id> <type> [firstFloor]   (where you stand = cabin origin; the floor = your feet)");
        info(s, "/elevator floor add <elev> <name> [y] | remove <elev> <name>      /elevator remove|info|list|stop");
        info(s, "/elevator button add <elev> <id> <floor:<f>|call:<f>|open|close|stop> [label…]   (at your position, in the cabin)");
        info(s, "/elevator button add <elev> <id> call:<f> --floor <f> [label…]   (landing button fixed at that floor)");
        info(s, "/elevator button set <elev> <id> <key> <value> | move <elev> <id> | remove <elev> <id> | list <elev>");
        info(s, "/elevator pack | models | reload");
    }

    // ---------------------------------------------------------------- player

    private void call(CommandSender s, String[] a) {
        Player p = player(s); if (p == null) return;
        Elevator e = a.length > 0 ? plugin.elevators().get(a[0]) : plugin.elevators().at(p, 24);
        if (e == null) { err(s, a.length > 0 ? "no elevator '" + a[0] + "'" : "no elevator within 24 blocks"); return; }
        String deny = ButtonAuthorizer.denyElevator(e.type(), p::hasPermission);
        if (deny != null) { err(s, "you may not use this elevator (" + deny + ")"); return; }
        if (!plugin.buttons().cooldowns().tryUse(p.getUniqueId(), e.id, "__call", plugin.buttons().now(), e.type().callCooldown)) { err(s, "please wait a moment"); return; }
        Floor f = a.length > 1 ? e.core.floor(a[1]) : nearestFloor(e, p.getLocation().getY());
        if (f == null) { err(s, a.length > 1 ? "unknown floor '" + a[1] + "'. Floors: " + ElevatorManager.floorNames(e) : "this elevator has no floors"); return; }
        plugin.buttons().feedback(p, e, f, e.core.request(f));
    }

    private void go(CommandSender s, String[] a) {
        Player p = player(s); if (p == null) return;
        if (a.length < 1) { err(s, "/elevator go <floor>"); return; }
        Elevator e = plugin.elevators().ridingOrNull(p);
        if (e == null) for (Elevator x : plugin.elevators().all()) if (x.playersInCabin().contains(p)) { e = x; break; }
        if (e == null) { err(s, "stand inside an elevator cabin first"); return; }
        String deny = ButtonAuthorizer.denyElevator(e.type(), p::hasPermission);
        if (deny != null) { err(s, "you may not use this elevator (" + deny + ")"); return; }
        Floor f = e.core.floor(a[0]);
        if (f == null) { err(s, "unknown floor '" + a[0] + "'. Floors: " + ElevatorManager.floorNames(e)); return; }
        plugin.buttons().feedback(p, e, f, e.core.request(f));
    }

    private void floors(CommandSender s, String[] a) {
        Elevator e = a.length > 0 ? plugin.elevators().get(a[0]) : (s instanceof Player p ? plugin.elevators().at(p, 24) : null);
        if (e == null) { err(s, "no elevator found"); return; }
        info(s, e.id + " floors (bottom to top): " + e.core.floors().stream().map(f -> f.name() + " (y " + (int) f.y() + ")" + (e.core.current() != null && e.core.current().name().equals(f.name()) ? " <- cabin" : "")).toList());
    }

    private static Floor nearestFloor(Elevator e, double y) {
        Floor best = null;
        for (Floor f : e.core.floors()) if (best == null || Math.abs(f.y() - y) < Math.abs(best.y() - y)) best = f;
        return best;
    }

    // ---------------------------------------------------------------- types

    private void type(CommandSender s, String[] a) {
        if (a.length == 0) { help(s); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (a.length < 3) { err(s, "type create <id> <model>"); return; }
                String id = a[1].toLowerCase(Locale.ROOT);
                if (plugin.types().get(id) != null) { err(s, "type '" + id + "' exists"); return; }
                ElevatorType t = new ElevatorType(id);
                t.model = a[2];
                List<String> errors = t.validate();
                if (!errors.isEmpty()) { err(s, String.join("; ", errors)); return; }
                if (!plugin.exporter().modelExists(a[2])) info(s, "note: no models/" + a[2] + ".bbmodel yet - add it and run /elevator pack");
                plugin.types().put(t);
                ok(s, "type " + id + " created: " + t.describe());
            }
            case "set" -> {
                if (a.length < 4) { err(s, "type set <id> <key> <value>  keys: " + String.join(", ", ElevatorType.KEYS)); return; }
                ElevatorType t = plugin.types().get(a[1]);
                if (t == null) { err(s, "no type '" + a[1] + "'"); return; }
                ElevatorType copy = ElevatorType.fromSection(t.id, snapshot(t));
                String e = copy.set(a[2], String.join(" ", tail(a, 3)));
                if (e != null) { err(s, e); return; }
                List<String> errors = copy.validate();
                if (!errors.isEmpty()) { err(s, "rejected: " + String.join("; ", errors)); return; }
                if (e == null) t.set(a[2], String.join(" ", tail(a, 3)));
                plugin.types().save();
                plugin.elevators().retypeAll();
                if ((a[2].equalsIgnoreCase("model") || a[2].equalsIgnoreCase("model-open")) && !plugin.exporter().modelExists(a[3])) info(s, "note: no models/" + a[3] + ".bbmodel on disk - add it and /elevator pack");
                ok(s, t.id + ": " + t.describe());
            }
            case "list" -> { if (plugin.types().all().isEmpty()) info(s, "no types (types.yml)"); for (ElevatorType t : plugin.types().all()) info(s, "§f" + t.id + "§7: " + t.describe()); }
            case "info" -> { if (a.length < 2) { err(s, "type info <id>"); return; } ElevatorType t = plugin.types().get(a[1]); if (t == null) { err(s, "no type '" + a[1] + "'"); return; } info(s, t.id + ": " + t.describe()); info(s, "sounds: start " + t.soundStart + ", move " + t.soundMove + "/" + t.soundMoveInterval + "t, arrive " + t.soundArrive + ", doors " + t.soundDoorsOpen + " / " + t.soundDoorsClose + ", music " + t.music + "/" + t.musicInterval + "t, volume " + t.volume); info(s, "cooldowns: buttons " + t.buttonCooldown + "t, call " + t.callCooldown + "t; door-close-delay " + t.doorCloseDelay + "t"); }
            case "remove" -> {
                if (a.length < 2) { err(s, "type remove <id>"); return; }
                long used = plugin.elevators().all().stream().filter(e -> e.typeId.equalsIgnoreCase(a[1])).count();
                if (used > 0) { err(s, used + " elevator(s) use this type - remove them first"); return; }
                if (plugin.types().remove(a[1])) ok(s, "removed type " + a[1]); else err(s, "no type '" + a[1] + "'");
            }
            default -> help(s);
        }
    }

    private static org.bukkit.configuration.ConfigurationSection snapshot(ElevatorType t) {
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        t.toSection(y.createSection("t"));
        return y.getConfigurationSection("t");
    }

    // ---------------------------------------------------------------- elevators

    private void create(CommandSender s, String[] a) {
        Player p = player(s); if (p == null) return;
        if (a.length < 2) { err(s, "create <id> <type> [firstFloorName]"); return; }
        String id = a[0].toLowerCase(Locale.ROOT);
        if (!ElevatorManager.ID.matcher(id).matches()) { err(s, "id must be 1-32 chars of a-z 0-9 _ -"); return; }
        if (plugin.elevators().get(id) != null) { err(s, "elevator '" + id + "' exists"); return; }
        ElevatorType t = plugin.types().get(a[1]);
        if (t == null) { err(s, "no type '" + a[1] + "' (types: " + plugin.types().all().stream().map(x -> x.id).toList() + ")"); return; }
        Elevator e = plugin.elevators().create(id, t, p.getLocation());
        String first = a.length > 2 ? a[2] : "1";
        if (Floor.validName(first)) {
            e.core.putFloor(new Floor(first, Math.floor(p.getLocation().getY())));
            e.core.rest(e.core.floor(first));
            plugin.elevators().save();
            e.spawnEntities();
        }
        ok(s, "elevator " + id + " created at " + (int) e.ox + " " + (int) p.getLocation().getY() + " " + (int) e.oz + " with floor '" + first + "'. Next: /elevator floor add " + id + " <name> at each level, then /elevator button add " + id + " ...");
        Block under = e.world.getBlockAt((int) Math.floor(e.ox), (int) Math.floor(p.getLocation().getY()) - 1, (int) Math.floor(e.oz));
        if (!under.getType().isSolid()) info(s, "warning: the block under the cabin origin is not solid - riders need a floor to stand on at each level");
    }

    private void floor(CommandSender s, String[] a) {
        if (a.length < 3) { err(s, "floor add <elevator> <name> [y] | floor remove <elevator> <name>"); return; }
        Elevator e = elev(s, a[1]); if (e == null) return;
        String name = a[2];
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (!Floor.validName(name)) { err(s, "floor names: 1-16 letters/digits/_/-"); return; }
                double y;
                if (a.length > 3) y = Double.parseDouble(a[3]);
                else { Player p = player(s); if (p == null) return; y = Math.floor(p.getLocation().getY()); }
                for (Floor f : e.core.floors()) if (Math.abs(f.y() - y) < 1 && !f.name().equalsIgnoreCase(name)) { err(s, "floor '" + f.name() + "' is already at y " + (int) f.y()); return; }
                boolean replaced = e.core.floor(name) != null;
                e.core.putFloor(new Floor(name, y));
                if (e.core.floors().size() == 1) e.core.rest(e.core.floor(name));
                plugin.elevators().save();
                Block under = e.world.getBlockAt((int) Math.floor(e.ox), (int) y - 1, (int) Math.floor(e.oz));
                Block obst = e.obstructionAt(y);
                ok(s, (replaced ? "moved" : "added") + " floor '" + name + "' at y " + (int) y + " (" + e.core.floors().size() + " floors)");
                if (!under.getType().isSolid()) info(s, "warning: no solid block under the cabin origin at this floor");
                if (obst != null) info(s, "warning: the cabin volume at this floor contains " + obst.getType().name().toLowerCase() + " at " + obst.getX() + " " + obst.getY() + " " + obst.getZ() + " - the elevator will refuse to travel here");
            }
            case "remove" -> {
                if (!e.core.removeFloor(name)) { err(s, "no floor '" + name + "'"); return; }
                List<String> orphan = new ArrayList<>();
                for (ButtonDef b : e.buttons.values()) if (!b.validate(ElevatorManager.floorNames(e)).isEmpty()) orphan.add(b.id);
                plugin.elevators().save();
                ok(s, "removed floor " + name + (orphan.isEmpty() ? "" : " - buttons now invalid (fix or remove): " + orphan));
            }
            default -> err(s, "floor add|remove");
        }
    }

    // ---------------------------------------------------------------- buttons

    private void button(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "button add|set|move|remove|list ..."); return; }
        Elevator e = elev(s, a[1]); if (e == null) return;
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                Player p = player(s); if (p == null) return;
                if (a.length < 4) { err(s, "button add <elev> <id> <floor:<f>|call:<f>|open|close|stop> [--floor <f>] [label…]"); return; }
                String id = a[2].toLowerCase(Locale.ROOT);
                if (e.buttons.containsKey(id)) { err(s, "button '" + id + "' exists on " + e.id); return; }
                ButtonDef b = new ButtonDef(id);
                b.width = b.height = plugin.settings().defaultButtonSize;
                String er = b.set("action", a[3]);
                if (er != null) { err(s, er); return; }
                List<String> rest = new ArrayList<>(Arrays.asList(tail(a, 4)));
                int fi = rest.indexOf("--floor");
                if (fi >= 0 && fi + 1 < rest.size()) { b.space = rest.get(fi + 1); rest.remove(fi + 1); rest.remove(fi); }
                if (!rest.isEmpty()) b.label = String.join(" ", rest);
                Location l = p.getLocation();
                double baseY = b.inCabin() ? e.cabinY() : (e.core.floor(b.space) == null ? e.cabinY() : e.core.floor(b.space).y());
                b.dx = round(l.getX() - e.ox); b.dy = round(l.getY() + 1.0 - baseY); b.dz = round(l.getZ() - e.oz);
                List<String> errors = b.validate(ElevatorManager.floorNames(e));
                if (!errors.isEmpty()) { err(s, String.join("; ", errors)); return; }
                e.buttons.put(id, b);
                plugin.buttons().spawn(e, b);
                plugin.elevators().save();
                ok(s, "button " + b.describe());
                info(s, "tip: /elevator button set " + e.id + " " + id + " model <bbmodel> | label <text> | size 0.3x0.3 | cooldown <ticks>");
            }
            case "set" -> {
                if (a.length < 5) { err(s, "button set <elev> <id> <key> <value>  keys: " + String.join(", ", ButtonDef.KEYS)); return; }
                ButtonDef b = e.buttons.get(a[2].toLowerCase(Locale.ROOT));
                if (b == null) { err(s, "no button '" + a[2] + "'"); return; }
                String er = b.set(a[3], String.join(" ", tail(a, 4)));
                if (er != null) { err(s, er); return; }
                List<String> errors = b.validate(ElevatorManager.floorNames(e));
                if (!errors.isEmpty()) err(s, "warning: " + String.join("; ", errors));
                if (a[3].equalsIgnoreCase("model") && !b.model.isEmpty() && !plugin.exporter().modelExists(b.model)) info(s, "note: no models/" + b.model + ".bbmodel on disk - add it and /elevator pack");
                plugin.buttons().spawn(e, b);
                plugin.elevators().save();
                ok(s, "button " + b.describe());
            }
            case "move" -> {
                Player p = player(s); if (p == null) return;
                if (a.length < 3) { err(s, "button move <elev> <id>"); return; }
                ButtonDef b = e.buttons.get(a[2].toLowerCase(Locale.ROOT));
                if (b == null) { err(s, "no button '" + a[2] + "'"); return; }
                Location l = p.getLocation();
                double baseY = b.inCabin() ? e.cabinY() : (e.core.floor(b.space) == null ? e.cabinY() : e.core.floor(b.space).y());
                b.dx = round(l.getX() - e.ox); b.dy = round(l.getY() + 1.0 - baseY); b.dz = round(l.getZ() - e.oz);
                plugin.buttons().spawn(e, b);
                plugin.elevators().save();
                ok(s, "moved " + b.describe());
            }
            case "remove" -> {
                if (a.length < 3) { err(s, "button remove <elev> <id>"); return; }
                ButtonDef b = e.buttons.remove(a[2].toLowerCase(Locale.ROOT));
                if (b == null) { err(s, "no button '" + a[2] + "'"); return; }
                plugin.buttons().remove(e, b);
                plugin.elevators().save();
                ok(s, "removed button " + b.id);
            }
            case "list" -> { if (e.buttons.isEmpty()) info(s, "no buttons on " + e.id); for (ButtonDef b : e.buttons.values()) info(s, b.describe()); }
            default -> err(s, "button add|set|move|remove|list");
        }
    }

    // ---------------------------------------------------------------- pack

    private void pack(CommandSender s) {
        if (plugin.exporter().isRunning()) { err(s, "an export is already running"); return; }
        info(s, "exporting " + plugin.exporter().modelNames().size() + " model(s) in the background ...");
        plugin.exporter().export(r -> {
            if (r == null) { err(s, "export failed - see console"); return; }
            ok(s, "pack written: " + r.models().size() + " model(s) " + r.models() + ", " + (r.bytes() / 1024) + " KB, " + r.millis() + " ms");
            info(s, "file: " + r.zip().getPath() + "  sha1: " + r.sha1());
            for (Issue i : r.issues()) if (i.isError()) err(s, i.model() + ": " + i.message()); else info(s, "warn " + i.model() + ": " + i.message());
            if (r.errors() > 0) info(s, "details: plugins/Elevators/pack/pack-report.txt");
        });
    }

    // ---------------------------------------------------------------- helpers

    private Elevator elev(CommandSender s, String id) {
        Elevator e = plugin.elevators().get(id);
        if (e == null) err(s, "no elevator '" + id + "' (" + plugin.elevators().ids() + ")");
        return e;
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        err(s, "in-game only");
        return null;
    }

    private boolean need(CommandSender s, String[] a, int n, String usage) {
        if (a.length >= n) return false;
        err(s, "/elevator " + usage);
        return true;
    }

    private static String[] tail(String[] a, int from) { return from >= a.length ? new String[0] : Arrays.copyOfRange(a, from, a.length); }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        boolean admin = s.hasPermission(ADMIN);
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("call", "go", "floors", "help"));
            if (admin) out.addAll(List.of("type", "create", "remove", "list", "info", "floor", "button", "stop", "pack", "models", "reload"));
            return filter(out, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "call" -> { if (a.length == 2) return filter(plugin.elevators().ids(), a[1]); if (a.length == 3) { Elevator e = plugin.elevators().get(a[1]); return e == null ? out : filter(ElevatorManager.floorNames(e), a[2]); } }
            case "go" -> { if (a.length == 2 && s instanceof Player p) { Elevator e = plugin.elevators().at(p, 24); return e == null ? out : filter(ElevatorManager.floorNames(e), a[1]); } }
            case "floors", "remove", "info", "stop" -> { if (a.length == 2) return filter(plugin.elevators().ids(), a[1]); }
            case "type" -> {
                if (!admin) return out;
                if (a.length == 2) return filter(List.of("create", "set", "list", "info", "remove"), a[1]);
                if (a.length == 3 && !a[1].equalsIgnoreCase("create")) return filter(plugin.types().all().stream().map(t -> t.id).toList(), a[2]);
                if (a.length == 3) return filter(plugin.exporter().modelNames(), a[2]);
                if (a.length == 4 && a[1].equalsIgnoreCase("set")) return filter(ElevatorType.KEYS, a[3]);
                if (a.length == 5 && a[1].equalsIgnoreCase("set") && a[3].toLowerCase(Locale.ROOT).startsWith("model")) return filter(plugin.exporter().modelNames(), a[4]);
            }
            case "create" -> { if (a.length == 3) return filter(plugin.types().all().stream().map(t -> t.id).toList(), a[2]); }
            case "floor" -> {
                if (a.length == 2) return filter(List.of("add", "remove"), a[1]);
                if (a.length == 3) return filter(plugin.elevators().ids(), a[2]);
                if (a.length == 4 && a[1].equalsIgnoreCase("remove")) { Elevator e = plugin.elevators().get(a[2]); return e == null ? out : filter(ElevatorManager.floorNames(e), a[3]); }
            }
            case "button" -> {
                if (a.length == 2) return filter(List.of("add", "set", "move", "remove", "list"), a[1]);
                if (a.length == 3) return filter(plugin.elevators().ids(), a[2]);
                Elevator e = plugin.elevators().get(a[2]);
                if (e == null) return out;
                if (a.length == 4 && !a[1].equalsIgnoreCase("add")) return filter(e.buttons.keySet(), a[3]);
                if (a.length == 5 && a[1].equalsIgnoreCase("add")) { out.addAll(List.of("open", "close", "stop")); for (String f : ElevatorManager.floorNames(e)) { out.add("floor:" + f); out.add("call:" + f); } return filter(out, a[4]); }
                if (a.length == 6 && a[1].equalsIgnoreCase("add")) return filter(List.of("--floor"), a[5]);
                if (a.length == 7 && a[1].equalsIgnoreCase("add") && a[5].equals("--floor")) return filter(ElevatorManager.floorNames(e), a[6]);
                if (a.length == 5 && a[1].equalsIgnoreCase("set")) return filter(ButtonDef.KEYS, a[4]);
                if (a.length == 6 && a[1].equalsIgnoreCase("set")) {
                    switch (a[4].toLowerCase(Locale.ROOT)) {
                        case "model" -> { out.add("none"); out.addAll(plugin.exporter().modelNames()); }
                        case "floor" -> out.addAll(ElevatorManager.floorNames(e));
                        case "space" -> { out.add("cabin"); out.addAll(ElevatorManager.floorNames(e)); }
                        case "action" -> out.addAll(List.of("floor:", "call:", "open", "close", "stop"));
                        case "cooldown" -> out.addAll(List.of("default", "0", "20", "40"));
                        case "size" -> out.addAll(List.of("0.3x0.3", "0.4x0.4", "0.5x0.8"));
                        default -> { }
                    }
                    return filter(out, a[5]);
                }
            }
            default -> { }
        }
        return out;
    }

    private static List<String> filter(Collection<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
