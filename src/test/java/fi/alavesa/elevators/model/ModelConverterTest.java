package fi.alavesa.elevators.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ModelConverterTest {

    private static JsonObject json(byte[] b) { return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject(); }

    @Test
    void groupsBecomeExactlyRotatedParts() {
        BbModel m = BbModelParser.parse("cabin", TestModels.cabin(), null, ParseOptions.STRICT).model();
        ConvertedModel cm = new ModelConverter("elevators").convert(m);
        assertEquals(2, cm.parts(), "root group + tilted group");
        assertEquals(List.of("item/cabin__p0", "item/cabin__p1"), cm.wrappers());
        assertTrue(cm.files().containsKey("assets/elevators/textures/item/cabin/tex.png"));
        JsonObject wrap1 = json(cm.files().get("assets/elevators/models/item/cabin__p1.json"));
        assertEquals("elevators:item/cabin__g1", wrap1.get("parent").getAsString());
        JsonArray rot = wrap1.getAsJsonObject("display").getAsJsonObject("fixed").getAsJsonArray("rotation");
        assertEquals(37.0, rot.get(1).getAsDouble(), 0.01, "group rotation is carried exactly by the wrapper");
        JsonArray tr = wrap1.getAsJsonObject("display").getAsJsonObject("fixed").getAsJsonArray("translation");
        assertEquals(12.0, tr.get(0).getAsDouble(), 0.01, "pivot x in px is the display translation");
        assertEquals(20.0, tr.get(1).getAsDouble(), 0.01);
        JsonObject geom1 = json(cm.files().get("assets/elevators/models/item/cabin__g1.json"));
        JsonObject element = geom1.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals(22.5, element.getAsJsonObject("rotation").get("angle").getAsDouble(), "the cube's own 22.5 stays a Java element rotation");
        JsonObject geom0 = json(cm.files().get("assets/elevators/models/item/cabin__g0.json"));
        JsonArray from = geom0.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonArray("from");
        assertTrue(from.get(0).getAsDouble() >= -16 && from.get(0).getAsDouble() <= 32, "geometry stays inside the Java range");
        assertTrue(cm.issues().stream().anyMatch(i -> i.message().contains("scaled")), "the 48px floor exceeds one part's range and is scaled with compensation");
    }

    @Test
    void packBuilderWritesItemsFileMcmetaReportAndRejectsDuplicates() throws Exception {
        PackBuilder.Options o = new PackBuilder.Options("elevators", "turtle_scute", "test pack", 46, 999, ParseOptions.STRICT);
        PackBuilder.Output out = PackBuilder.build(List.of(
            new PackBuilder.Source("cabin", TestModels.cabin(), null),
            new PackBuilder.Source("Cabin", TestModels.cabin(), null),
            new PackBuilder.Source("meshy", TestModels.withMesh(), null),
            new PackBuilder.Source("broken", "nope", null)), o);
        assertEquals(List.of("cabin"), out.exportedModels());
        assertEquals(3, out.errors().size(), out.errors().toString());
        assertTrue(out.errors().stream().anyMatch(i -> i.model().equals("Cabin") && i.message().contains("duplicate")));
        JsonObject items = json(out.files().get("assets/minecraft/items/turtle_scute.json"));
        JsonObject select = items.getAsJsonObject("model");
        assertEquals("minecraft:custom_model_data", select.get("property").getAsString());
        JsonArray cases = select.getAsJsonArray("cases");
        assertEquals(1, cases.size());
        assertEquals("cabin", cases.get(0).getAsJsonObject().get("when").getAsString());
        assertEquals("minecraft:composite", cases.get(0).getAsJsonObject().getAsJsonObject("model").get("type").getAsString());
        JsonObject mcmeta = json(out.files().get("pack.mcmeta")).getAsJsonObject("pack");
        assertEquals(46, mcmeta.get("min_format").getAsInt());
        assertEquals(999, mcmeta.get("max_format").getAsInt());
        assertTrue(out.report().contains("cabin"));
        assertTrue(out.report().contains("MESH"));

        File dir = Files.createTempDirectory("elev-pack").toFile();
        File zip = new File(dir, "Elevators-pack.zip");
        String sha = PackBuilder.writeZip(out, zip);
        assertEquals(40, sha.length());
        try (ZipFile z = new ZipFile(zip)) {
            assertNotNull(z.getEntry("pack.mcmeta"));
            assertNotNull(z.getEntry("assets/elevators/models/item/cabin__p0.json"));
            assertNotNull(z.getEntry("pack-report.txt"));
        }
    }

    @Test
    void freeRotatedCubeBecomesOwnPart() {
        String json = "{\"elements\":[{\"name\":\"c\",\"type\":\"cube\",\"uuid\":\"e1\",\"from\":[0,0,0],\"to\":[4,4,4],\"origin\":[2,2,2],\"rotation\":[10,20,30],\"faces\":" + TestModels.faces() + "}],\"outliner\":[\"e1\"],\"textures\":[" + TestModels.texture() + "]}";
        BbModel m = BbModelParser.parse("free", json, null, ParseOptions.STRICT).model();
        ConvertedModel cm = new ModelConverter("elevators").convert(m);
        assertEquals(1, cm.parts());
        assertEquals("item/free__pc0", cm.wrappers().get(0));
        JsonObject wrap = json(cm.files().get("assets/elevators/models/item/free__pc0.json"));
        JsonArray rot = wrap.getAsJsonObject("display").getAsJsonObject("fixed").getAsJsonArray("rotation");
        // the wrapper's XYZ display rotation must reproduce the Blockbench ZYX rotation matrix
        double[][] expect = Mat.rot3(new double[] {10, 20, 30});
        double[][] got = Mat.rotXYZ(new double[] {rot.get(0).getAsDouble(), rot.get(1).getAsDouble(), rot.get(2).getAsDouble()});
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) assertEquals(expect[i][j], got[i][j], 1e-3);
    }
}
