package materialliste.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Alles, was Litematica-Klassen anfasst. Wird nur geladen, wenn Litematica installiert ist. */
final class LitematicaBridge {
    /** Je Platzierung: pro Chunk die richtig gesetzten Blöcke. Chunks ausser Sichtweite behalten ihren letzten Stand. */
    static class Cache { String sig = ""; Map<String, Map<String, Integer>> chunks = new HashMap<>(); transient int cursor; }

    // Nur Eigenschaften, die für die Stückzahl wichtig sind (Doppel-Slabs, obere Türhälfte, Kerzen …)
    private static final Set<String> PROPS = Set.of("type", "half", "part", "layers", "pickles", "candles", "eggs", "flower_amount", "segment_amount");
    private static final int BUDGET = 200_000;   // Blockvergleiche pro Durchlauf

    static String keyOf(SchematicPlacement p) {
        Path f = p.getSchematicFile();
        return p.getName() + "|" + (f != null && f.getFileName() != null ? f.getFileName().toString() : "");
    }

    /** Datei der Schematic zu einer aktiven Platzierung, null wenn es sie nicht (mehr) gibt */
    static Path fileOf(String key) {
        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            if (p.isEnabled() && keyOf(p).equals(key)) return p.getSchematicFile();
        return null;
    }

    /** Aktive Platzierungen als { Schlüssel, Anzeigename } für den Knopf in der Kiste */
    static List<String[]> active() {
        List<String[]> out = new ArrayList<>();
        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            if (p.isEnabled()) out.add(new String[] { keyOf(p), p.getName() });
        return out;
    }

    /** Vergleicht alle aktiven Platzierungen mit der Welt und hängt sie an out. Gibt true zurück, wenn sich etwas geändert hat. */
    static boolean scan(Minecraft mc, Map<String, Cache> caches, JsonArray out) {
        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
        ClientLevel level = mc.level;
        if (sw == null || level == null) return false;
        boolean changed = false;
        int budget = BUDGET;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!p.isEnabled()) continue;
            String name = p.getName();
            Path f = p.getSchematicFile();
            String file = f != null && f.getFileName() != null ? f.getFileName().toString() : "";
            Collection<Box> boxes = p.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values();
            List<int[]> bounds = new ArrayList<>();
            StringBuilder sig = new StringBuilder();
            for (Box b : boxes) {
                BlockPos a = b.getPos1(), c = b.getPos2();
                if (a == null || c == null) continue;
                int[] r = { Math.min(a.getX(), c.getX()), Math.min(a.getY(), c.getY()), Math.min(a.getZ(), c.getZ()), Math.max(a.getX(), c.getX()), Math.max(a.getY(), c.getY()), Math.max(a.getZ(), c.getZ()) };
                bounds.add(r);
                for (int v : r) sig.append(v).append(',');
            }
            if (bounds.isEmpty()) continue;
            Cache cache = caches.computeIfAbsent(keyOf(p), k -> new Cache());
            if (cache.chunks == null) cache.chunks = new HashMap<>();
            if (!sig.toString().equals(cache.sig)) { cache.sig = sig.toString(); cache.chunks.clear(); changed = true; }   // verschoben oder gedreht

            // alle Chunks der Platzierung, reihum mit begrenztem Aufwand pro Durchlauf
            List<long[]> chunks = new ArrayList<>();
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (int[] r : bounds) for (int cx = r[0] >> 4; cx <= r[3] >> 4; cx++) for (int cz = r[2] >> 4; cz <= r[5] >> 4; cz++)
                if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) chunks.add(new long[] { cx, cz });
            int start = cache.cursor % chunks.size(), visited = 0;
            for (; visited < chunks.size() && budget > 0; visited++) {
                long[] ch = chunks.get((start + visited) % chunks.size());
                int cx = (int) ch[0], cz = (int) ch[1];
                if (!level.getChunkSource().hasChunk(cx, cz) || !sw.getChunkSource().hasChunk(cx, cz)) continue;
                Map<String, Integer> counts = new TreeMap<>();
                for (int[] r : bounds) {
                    int x0 = Math.max(r[0], cx << 4), x1 = Math.min(r[3], (cx << 4) + 15), z0 = Math.max(r[2], cz << 4), z1 = Math.min(r[5], (cz << 4) + 15);
                    for (int y = r[1]; y <= r[4]; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                        budget--;
                        BlockState want = sw.getBlockState(pos.set(x, y, z));
                        if (want.isAir()) continue;
                        if (level.getBlockState(pos).getBlock() == want.getBlock()) counts.merge(key(want), 1, Integer::sum);
                    }
                }
                String ck = cx + "," + cz;
                if (!counts.equals(cache.chunks.get(ck))) { cache.chunks.put(ck, counts); changed = true; }
            }
            cache.cursor = (start + visited) % chunks.size();   // beim nächsten Durchlauf dort weitermachen

            Map<String, Integer> total = new TreeMap<>();
            for (Map<String, Integer> c : cache.chunks.values()) c.forEach((k, v) -> total.merge(k, v, Integer::sum));
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("file", file);
            o.addProperty("key", keyOf(p));
            JsonArray placed = new JsonArray();
            total.forEach((k, v) -> {
                String[] parts = k.split("\\|", 2);
                JsonObject e = new JsonObject(), props = new JsonObject();
                e.addProperty("name", parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) for (String kv : parts[1].split(",")) { int i = kv.indexOf('='); props.addProperty(kv.substring(0, i), kv.substring(i + 1)); }
                e.add("props", props);
                e.addProperty("count", v);
                placed.add(e);
            });
            o.add("placed", placed);
            out.add(o);
        }
        return changed;
    }

    private static String key(BlockState s) {
        StringBuilder b = new StringBuilder(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()).append('|');
        boolean first = true;
        for (Property<?> p : s.getProperties()) {
            if (!PROPS.contains(p.getName())) continue;
            if (!first) b.append(',');
            first = false;
            b.append(p.getName()).append('=').append(value(s, p));
        }
        return b.toString();
    }

    private static <T extends Comparable<T>> String value(BlockState s, Property<T> p) { return p.getName(s.getValue(p)); }

    private LitematicaBridge() { }
}
