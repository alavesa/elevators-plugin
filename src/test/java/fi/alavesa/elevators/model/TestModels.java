package fi.alavesa.elevators.model;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Builds small .bbmodel JSON strings for the tests. */
final class TestModels {

    private TestModels() { }

    static String png16Base64() {
        byte[] raw = new byte[16 * (1 + 16 * 4)];
        for (int y = 0; y < 16; y++) { raw[y * 65] = 0; for (int x = 0; x < 16; x++) { int o = y * 65 + 1 + x * 4; raw[o] = (byte) 200; raw[o + 1] = 120; raw[o + 2] = 60; raw[o + 3] = (byte) 255; } }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        byte[] ihdr = {0, 0, 0, 16, 0, 0, 0, 16, 8, 6, 0, 0, 0};
        chunk(out, "IHDR", ihdr);
        Deflater d = new Deflater();
        d.setInput(raw); d.finish();
        byte[] buf = new byte[4096]; int n = d.deflate(buf);
        byte[] idat = new byte[n]; System.arraycopy(buf, 0, idat, 0, n);
        chunk(out, "IDAT", idat);
        chunk(out, "IEND", new byte[0]);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        out.writeBytes(new byte[] {(byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length});
        byte[] t = type.getBytes();
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32(); crc.update(t); crc.update(data);
        long c = crc.getValue();
        out.writeBytes(new byte[] {(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
    }

    static String faces() {
        StringBuilder sb = new StringBuilder("{");
        String[] f = {"north", "east", "south", "west", "up", "down"};
        for (int i = 0; i < f.length; i++) sb.append(i > 0 ? "," : "").append('"').append(f[i]).append("\":{\"uv\":[0,0,8,8],\"texture\":0}");
        return sb.append('}').toString();
    }

    static String texture() {
        return "{\"name\":\"tex.png\",\"id\":\"0\",\"uuid\":\"t-1\",\"source\":\"data:image/png;base64," + png16Base64() + "\"}";
    }

    /** Two cubes: one in a group rotated 37 degrees on Y, one free cube with a 22.5 rotation. */
    static String cabin() {
        return "{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\",\"box_uv\":false},\"name\":\"cabin\",\"resolution\":{\"width\":16,\"height\":16},"
            + "\"elements\":[{\"name\":\"floor\",\"type\":\"cube\",\"uuid\":\"e1\",\"from\":[-24,0,-24],\"to\":[24,1,24],\"origin\":[0,0,0],\"faces\":" + faces() + "},"
            + "{\"name\":\"panel\",\"type\":\"cube\",\"uuid\":\"e2\",\"from\":[10,10,-2],\"to\":[14,30,2],\"origin\":[12,20,0],\"rotation\":[0,22.5,0],\"faces\":" + faces() + "}],"
            + "\"outliner\":[{\"name\":\"root\",\"uuid\":\"g1\",\"origin\":[0,0,0],\"rotation\":[0,0,0],\"children\":[\"e1\",{\"name\":\"tilted\",\"uuid\":\"g2\",\"origin\":[12,20,0],\"rotation\":[0,37,0],\"children\":[\"e2\"]}]}],"
            + "\"textures\":[" + texture() + "],\"animations\":[{\"name\":\"open\",\"uuid\":\"a1\",\"length\":1,\"animators\":{}}]}";
    }

    static String withMesh() {
        return "{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\"},\"name\":\"meshy\",\"resolution\":{\"width\":16,\"height\":16},"
            + "\"elements\":[{\"name\":\"cube\",\"type\":\"cube\",\"uuid\":\"e1\",\"from\":[0,0,0],\"to\":[8,8,8],\"origin\":[0,0,0],\"faces\":" + faces() + "},"
            + "{\"name\":\"wheel\",\"type\":\"mesh\",\"uuid\":\"m1\",\"origin\":[0,16,0],\"vertices\":{\"a\":[-4,-4,0],\"b\":[4,-4,0],\"c\":[0,4,0]},\"faces\":{\"f\":{\"vertices\":[\"a\",\"b\",\"c\"],\"texture\":0}}}],"
            + "\"outliner\":[\"e1\",\"m1\"],\"textures\":[" + texture() + "]}";
    }

    static String missingTexture() {
        return "{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\"},\"name\":\"notex\",\"resolution\":{\"width\":16,\"height\":16},"
            + "\"elements\":[{\"name\":\"cube\",\"type\":\"cube\",\"uuid\":\"e1\",\"from\":[0,0,0],\"to\":[8,8,8],\"origin\":[0,0,0],\"faces\":" + faces() + "}],"
            + "\"outliner\":[\"e1\"],\"textures\":[{\"name\":\"skin.png\",\"id\":\"0\",\"uuid\":\"t-2\",\"relative_path\":\"../textures/skin.png\"}]}";
    }
}
