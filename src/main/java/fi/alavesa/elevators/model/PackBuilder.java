package fi.alavesa.elevators.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runs parser + converter over a set of model sources and assembles a complete resource pack:
 * pack.mcmeta, the models/textures of every model that converted cleanly, and ONE items file
 * (assets/minecraft/items/&lt;base item&gt;.json) that selects a model by the custom_model_data string
 * (= the model name). Pure: no Bukkit, safe to run off the main thread.
 */
public final class PackBuilder {

    public record Options(String namespace, String baseItem, String description, int minFormat, int maxFormat, ParseOptions parse) { }

    public record Source(String name, String json, Function<String, byte[]> textureResolver) { }

    public record Output(Map<String, byte[]> files, List<String> exportedModels, List<Issue> issues, String report) {
        public List<Issue> errors() { List<Issue> l = new ArrayList<>(); for (Issue i : issues) if (i.isError()) l.add(i); return l; }
        public List<Issue> warnings() { List<Issue> l = new ArrayList<>(); for (Issue i : issues) if (!i.isError()) l.add(i); return l; }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PackBuilder() { }

    public static Output build(List<Source> sources, Options o) {
        List<Issue> issues = new ArrayList<>();
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> report = new ArrayList<>();
        List<ConvertedModel> converted = new ArrayList<>();
        Set<String> usedPaths = new LinkedHashSet<>();
        Set<String> usedNames = new LinkedHashSet<>();
        ModelConverter converter = new ModelConverter(o.namespace());
        for (Source s : sources) {
            if (!usedNames.add(s.name().toLowerCase(java.util.Locale.ROOT))) { issues.add(Issue.error(s.name(), "duplicate model name (names are case-insensitive) - rename one of the files")); continue; }
            String p = BbModel.safePath(s.name());
            if (!usedPaths.add(p)) { issues.add(Issue.error(s.name(), "another model already uses the pack path '" + p + "' - rename one of the files")); continue; }
            BbModelParser.Result r;
            try {
                r = BbModelParser.parse(s.name(), s.json(), s.textureResolver(), o.parse());
            } catch (Exception e) {
                issues.add(Issue.error(s.name(), "could not parse: " + e));
                continue;
            }
            issues.addAll(r.issues());
            if (!r.ok()) continue;
            try {
                ConvertedModel cm = converter.convert(r.model());
                issues.addAll(cm.issues());
                converted.add(cm);
                files.putAll(cm.files());
            } catch (Exception e) {
                issues.add(Issue.error(s.name(), "could not convert: " + e));
            }
        }

        // items file
        JsonArray cases = new JsonArray();
        for (ConvertedModel cm : converted) {
            JsonObject c = new JsonObject();
            c.addProperty("when", cm.name());
            JsonObject comp = new JsonObject();
            comp.addProperty("type", "minecraft:composite");
            JsonArray models = new JsonArray();
            for (String w : cm.wrappers()) {
                JsonObject mm = new JsonObject();
                mm.addProperty("type", "minecraft:model");
                mm.addProperty("model", o.namespace() + ":" + w);
                models.add(mm);
            }
            comp.add("models", models);
            c.add("model", comp);
            cases.add(c);
        }
        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:custom_model_data");
        select.add("cases", cases);
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "minecraft:model");
        fallback.addProperty("model", "minecraft:item/" + o.baseItem());
        select.add("fallback", fallback);
        JsonObject itemsRoot = new JsonObject();
        itemsRoot.add("model", select);
        files.put("assets/minecraft/items/" + o.baseItem() + ".json", GSON.toJson(itemsRoot).getBytes(StandardCharsets.UTF_8));

        // pack.mcmeta
        JsonObject pack = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("description", o.description());
        p.addProperty("pack_format", o.minFormat());
        p.addProperty("min_format", o.minFormat());
        p.addProperty("max_format", o.maxFormat());
        pack.add("pack", p);
        files.put("pack.mcmeta", GSON.toJson(pack).getBytes(StandardCharsets.UTF_8));

        // report
        report.add("Elevators pack report - " + new Date());
        report.add("base item: " + o.baseItem() + "   namespace: " + o.namespace() + "   pack_format " + o.minFormat() + ".." + o.maxFormat());
        report.add("");
        report.add("exported models (use the NAME in types.yml / button models; the pack selects it by custom_model_data):");
        List<String> names = new ArrayList<>();
        for (ConvertedModel cm : converted) {
            names.add(cm.name());
            double[] b = cm.boundsPx();
            report.add(String.format(java.util.Locale.ROOT, "  %-24s %3d cubes, %2d part(s), size %.0fx%.0fx%.0f px, x %.0f..%.0f  y %.0f..%.0f  z %.0f..%.0f  -> %s:item/%s__p*",
                cm.name(), cm.cubes(), cm.parts(), b[3] - b[0], b[4] - b[1], b[5] - b[2], b[0], b[3], b[1], b[4], b[2], b[5], o.namespace(), cm.path()));
        }
        if (converted.isEmpty()) report.add("  none");
        report.add("");
        List<Issue> errors = new ArrayList<>(), warnings = new ArrayList<>();
        for (Issue i : issues) (i.isError() ? errors : warnings).add(i);
        report.add("errors (" + errors.size() + ") - these models are NOT in the pack:");
        if (errors.isEmpty()) report.add("  none"); else for (Issue i : errors) report.add("  ! " + i.model() + ": " + i.message());
        report.add("");
        report.add("warnings (" + warnings.size() + "):");
        if (warnings.isEmpty()) report.add("  none"); else for (Issue i : warnings) report.add("  - " + i.model() + ": " + i.message());
        String reportText = String.join("\n", report) + "\n";
        files.put("pack-report.txt", reportText.getBytes(StandardCharsets.UTF_8));
        return new Output(files, names, issues, reportText);
    }

    /** Write the zip atomically (tmp + move) and return its sha1. */
    public static String writeZip(Output out, File zip) throws IOException {
        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmp");
        zip.getParentFile().mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            for (var e : out.files().entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return sha1(zip);
    }

    public static String sha1(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(Files.readAllBytes(f.toPath()));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }
}
