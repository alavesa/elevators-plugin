package fi.alavesa.elevators.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BbModelParserTest {

    @Test
    void parsesCubesGroupsTexturesAndFlagsAnimations() {
        BbModelParser.Result r = BbModelParser.parse("cabin", TestModels.cabin(), null, ParseOptions.STRICT);
        assertTrue(r.ok(), r.issues().toString());
        BbModel m = r.model();
        assertEquals(2, m.cubes.size());
        assertEquals(2, m.bones.size());
        assertEquals(1, m.textures.size());
        assertNotNull(m.textures.get(0).png());
        assertEquals("tilted", m.cubes.get("e2").bone.name);
        assertEquals("root", m.cubes.get("e1").bone.name);
        assertEquals(37.0, m.cubes.get("e2").bone.rotation[1]);
        assertTrue(r.issues().stream().anyMatch(i -> !i.isError() && i.message().contains("animations")), "animations must be reported as unsupported");
        double[] b = m.bounds();
        assertEquals(-24, b[0]); assertEquals(30, b[4]);
    }

    @Test
    void malformedJsonIsAnError() {
        BbModelParser.Result r = BbModelParser.parse("broken", "{ this is not json", null, ParseOptions.STRICT);
        assertFalse(r.ok());
        assertTrue(r.issues().get(0).isError());
        assertTrue(r.issues().get(0).message().contains("malformed"));
        BbModelParser.Result r2 = BbModelParser.parse("empty", "{\"meta\":{}}", null, ParseOptions.STRICT);
        assertFalse(r2.ok());
    }

    @Test
    void meshFailsStrictAndBoxesWithPolicy() {
        BbModelParser.Result strict = BbModelParser.parse("meshy", TestModels.withMesh(), null, ParseOptions.STRICT);
        assertFalse(strict.ok());
        assertTrue(strict.issues().stream().anyMatch(i -> i.isError() && i.message().contains("MESH")));
        BbModelParser.Result box = BbModelParser.parse("meshy", TestModels.withMesh(), null, ParseOptions.of("bounding-box", "fail"));
        assertTrue(box.ok());
        assertEquals(2, box.model().cubes.size());
        assertTrue(box.model().cubes.get("m1").meshBox);
        assertTrue(box.issues().stream().anyMatch(i -> !i.isError() && i.message().contains("bounding box")));
    }

    @Test
    void missingTextureFailsUnlessResolvedOrWarnPolicy() {
        BbModelParser.Result fail = BbModelParser.parse("notex", TestModels.missingTexture(), p -> null, ParseOptions.STRICT);
        assertFalse(fail.ok());
        assertTrue(fail.issues().get(0).message().contains("not embedded"));
        byte[] png = Base64.getDecoder().decode(TestModels.png16Base64());
        BbModelParser.Result resolved = BbModelParser.parse("notex", TestModels.missingTexture(), p -> p.endsWith("skin.png") ? png : null, ParseOptions.STRICT);
        assertTrue(resolved.ok(), resolved.issues().toString());
        BbModelParser.Result warn = BbModelParser.parse("notex", TestModels.missingTexture(), p -> null, ParseOptions.of("fail", "warn"));
        assertTrue(warn.ok());
        assertNull(warn.model().textures.get(0).png());
        BbModelParser.Result notPng = BbModelParser.parse("notex", TestModels.missingTexture(), p -> "hello".getBytes(StandardCharsets.UTF_8), ParseOptions.STRICT);
        assertFalse(notPng.ok());
    }

    @Test
    void namesAreSanitisedButPreserved() {
        BbModelParser.Result r = BbModelParser.parse("My Cabin.v2", TestModels.cabin(), null, ParseOptions.STRICT);
        assertTrue(r.ok());
        assertEquals("My Cabin.v2", r.model().name);
        assertEquals("my_cabin.v2", r.model().path);
        assertTrue(r.issues().stream().anyMatch(i -> i.message().contains("upper-case")));
    }
}
