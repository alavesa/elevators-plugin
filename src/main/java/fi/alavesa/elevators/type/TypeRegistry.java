package fi.alavesa.elevators.type;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/** types.yml <-> {@link ElevatorType}s. Invalid types are kept out of the registry and logged. */
public final class TypeRegistry {

    private final File file;
    private final Logger log;
    private final Map<String, ElevatorType> types = new TreeMap<>();

    public TypeRegistry(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        types.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("types");
        if (root == null) { log.warning("types.yml has no 'types:' section - no elevator types loaded"); return; }
        int bad = 0;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            ElevatorType t = ElevatorType.fromSection(id, s);
            List<String> errors = t.validate();
            if (!errors.isEmpty()) { bad++; log.warning("types.yml: type '" + id + "' is invalid and was skipped: " + String.join("; ", errors)); continue; }
            types.put(id, t);
        }
        log.info("Loaded " + types.size() + " elevator type(s)" + (bad > 0 ? ", " + bad + " invalid (see warnings above)" : "") + ".");
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Elevator types. Keys: " + String.join(", ", ElevatorType.KEYS), "Edit by hand (then /elevator reload) or with /elevator type set."));
        for (ElevatorType t : types.values()) t.toSection(y.createSection("types." + t.id));
        try { y.save(file); } catch (IOException e) { log.warning("Could not save types.yml: " + e); }
    }

    public ElevatorType get(String id) { return id == null ? null : types.get(id.toLowerCase()); }

    public void put(ElevatorType t) { types.put(t.id, t); save(); }

    public boolean remove(String id) { boolean r = types.remove(id) != null; if (r) save(); return r; }

    public Collection<ElevatorType> all() { return types.values(); }
}
