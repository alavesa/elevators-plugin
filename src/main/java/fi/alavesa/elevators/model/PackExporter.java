package fi.alavesa.elevators.model;

import fi.alavesa.elevators.ElevatorsPlugin;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The server side of the pipeline: reads plugins/Elevators/models/*.bbmodel, runs {@link PackBuilder} on
 * an async thread and writes plugins/Elevators/pack/Elevators-pack.zip, Elevators-pack.sha1 and
 * pack-report.txt. Source models are never modified; the pack folder is wiped of our own outputs only.
 */
public final class PackExporter {

    public record Result(List<String> models, List<Issue> issues, File zip, String sha1, long bytes, long millis) {
        public long errors() { return issues.stream().filter(Issue::isError).count(); }
        public long warnings() { return issues.stream().filter(i -> !i.isError()).count(); }
    }

    private final ElevatorsPlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile List<String> lastExported = List.of();

    public PackExporter(ElevatorsPlugin plugin) { this.plugin = plugin; }

    public File modelsDir() { return new File(plugin.getDataFolder(), "models"); }
    public File packDir() { return new File(plugin.getDataFolder(), "pack"); }
    public File zipFile() { return new File(packDir(), "Elevators-pack.zip"); }

    /** Model names found on disk right now (also before any export). */
    public List<String> modelNames() {
        List<String> names = new ArrayList<>();
        File[] list = modelsDir().listFiles();
        if (list != null) for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".bbmodel"))
                names.add(f.getName().substring(0, f.getName().length() - ".bbmodel".length()));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public boolean modelExists(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String n : modelNames()) if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    public List<String> lastExported() { return lastExported; }

    public boolean isRunning() { return running.get(); }

    /** Export asynchronously; {@code done} runs on the main thread (null result = already running / failed). */
    public void export(Consumer<Result> done) {
        if (!running.compareAndSet(false, true)) { if (done != null) done.accept(null); return; }
        modelsDir().mkdirs();
        packDir().mkdirs();
        final File models = modelsDir();
        final var settings = plugin.settings();
        plugin.getLogger().info("Exporting resource pack from " + models.getPath() + " ...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long t0 = System.currentTimeMillis();
            Result result = null;
            try {
                List<PackBuilder.Source> sources = new ArrayList<>();
                File[] files = models.listFiles();
                if (files != null) for (File f : files) {
                    if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".bbmodel")) continue;
                    String name = f.getName().substring(0, f.getName().length() - ".bbmodel".length());
                    String json;
                    try { json = Files.readString(f.toPath()); } catch (IOException e) { json = ""; }
                    sources.add(new PackBuilder.Source(name, json, rel -> readTexture(models, f, rel)));
                }
                PackBuilder.Options o = new PackBuilder.Options(settings.namespace, settings.baseItem.getKey().getKey(), settings.packDescription, settings.minFormat, settings.maxFormat, settings.parse);
                PackBuilder.Output out = PackBuilder.build(sources, o);
                File zip = zipFile();
                String sha1 = PackBuilder.writeZip(out, zip);
                Files.writeString(new File(packDir(), "Elevators-pack.sha1").toPath(), sha1 + "\n");
                Files.writeString(new File(packDir(), "pack-report.txt").toPath(), out.report());
                lastExported = List.copyOf(out.exportedModels());
                result = new Result(out.exportedModels(), out.issues(), zip, sha1, zip.length(), System.currentTimeMillis() - t0);
            } catch (Exception e) {
                plugin.getLogger().severe("Pack export failed: " + e);
                e.printStackTrace();
            } finally {
                running.set(false);
            }
            final Result r = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (r != null) {
                    plugin.getLogger().info("Pack exported: " + r.models().size() + " model(s) " + r.models() + ", " + r.errors() + " error(s), " + r.warnings() + " warning(s), " + (r.bytes() / 1024) + " KB in " + r.millis() + " ms -> " + r.zip().getPath() + " (sha1 " + r.sha1() + ")");
                    for (Issue i : r.issues()) { if (i.isError()) plugin.getLogger().warning("  " + i); else plugin.getLogger().info("  " + i); }
                }
                if (done != null) done.accept(r);
            });
        });
    }

    /** A texture referenced by relative path/name: next to the model, in models/textures/, or as recorded. */
    private static byte[] readTexture(File modelsDir, File model, String rel) {
        if (rel == null || rel.isBlank()) return null;
        String fileName = new File(rel).getName();
        List<File> candidates = List.of(
            new File(model.getParentFile(), rel), new File(modelsDir, rel), new File(modelsDir, fileName),
            new File(modelsDir, fileName.endsWith(".png") ? fileName : fileName + ".png"),
            new File(new File(modelsDir, "textures"), fileName.endsWith(".png") ? fileName : fileName + ".png"),
            new File(rel));
        for (File c : candidates) {
            try {
                if (c.isFile() && c.getCanonicalPath().endsWith(".png")) return Files.readAllBytes(c.toPath());
            } catch (IOException ignored) { }
        }
        return null;
    }
}
