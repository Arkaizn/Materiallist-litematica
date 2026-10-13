package materiallist.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Litematica schematic (.litematic) -> material list and block positions. Files come from players, so everything is limited and checked. */
public final class Litematic {
    static final long MAX_UNPACKED = 512L * 1024 * 1024;
    static final long MAX_VOLUME = 64_000_000;

    public record Parsed(String name, JsonObject meta, JsonArray items) { }
    private record Region(List<String> names, List<Map<String, String>> props, int[] size, int[] min, short[] idx) { }

    private static final Set<String> SKIP = Set.of("air", "cave_air", "void_air", "water", "lava", "bubble_column", "piston_head", "moving_piston",
        "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway", "light", "structure_void", "powder_snow");
    private static final Map<String, String> RENAME = Map.ofEntries(
        Map.entry("carrots", "carrot"), Map.entry("potatoes", "potato"), Map.entry("beetroots", "beetroot"), Map.entry("wheat", "wheat_seeds"), Map.entry("cocoa", "cocoa_beans"),
        Map.entry("melon_stem", "melon_seeds"), Map.entry("attached_melon_stem", "melon_seeds"), Map.entry("pumpkin_stem", "pumpkin_seeds"),
        Map.entry("attached_pumpkin_stem", "pumpkin_seeds"), Map.entry("sweet_berry_bush", "sweet_berries"), Map.entry("cave_vines", "glow_berries"),
        Map.entry("cave_vines_plant", "glow_berries"), Map.entry("kelp_plant", "kelp"), Map.entry("weeping_vines_plant", "weeping_vines"),
        Map.entry("twisting_vines_plant", "twisting_vines"), Map.entry("tall_seagrass", "seagrass"), Map.entry("bamboo_sapling", "bamboo"),
        Map.entry("big_dripleaf_stem", "big_dripleaf"), Map.entry("frosted_ice", "ice"), Map.entry("redstone_wire", "redstone"), Map.entry("tripwire", "string"));
    private static final Pattern WALL = Pattern.compile("^(.+)_wall_(sign|hanging_sign|banner|head|skull)$");
    private static final Pattern CANDLE_CAKE = Pattern.compile("^(.*candle)_cake$");
    private static final Pattern POTTED = Pattern.compile("^potted_(.+)$");

    /** Block state -> items needed for it (added into out) */
    public static void blockToItems(String fullName, Map<String, String> p, long count, Map<String, Long> out) {
        if (fullName.contains(":") && !fullName.startsWith("minecraft:")) { out.merge(fullName, count, Long::sum); return; }   // mod block
        String id = fullName.replaceFirst("^minecraft:", "");
        if (SKIP.contains(id)) return;
        if ("upper".equals(p.get("half"))) return;                              // upper half of doors / tall plants
        if ("head".equals(p.get("part")) && id.endsWith("_bed")) return;        // a bed only counts once
        id = RENAME.getOrDefault(id, id);
        if (id.endsWith("wall_torch")) id = id.substring(0, id.length() - "wall_torch".length()) + "torch";
        Matcher m = WALL.matcher(id);
        if (m.matches()) id = m.group(1) + "_" + m.group(2);
        if (id.endsWith("_cauldron")) id = "cauldron";
        if ((m = CANDLE_CAKE.matcher(id)).matches() || id.equals("candle_cake")) {
            out.merge(id.equals("candle_cake") ? "candle" : m.group(1), count, Long::sum);
            out.merge("cake", count, Long::sum);
            return;
        }
        if ((m = POTTED.matcher(id)).matches()) {
            String plant = m.group(1);
            if (plant.equals("azalea_bush")) plant = "azalea";
            if (plant.equals("flowering_azalea_bush")) plant = "flowering_azalea";
            out.merge("flower_pot", count, Long::sum);
            out.merge(plant, count, Long::sum);
            return;
        }
        long mult = 1;
        if ("double".equals(p.get("type")) && id.endsWith("_slab")) mult = 2;
        else if (id.equals("snow") && p.containsKey("layers")) mult = number(p.get("layers"));
        else for (String k : List.of("pickles", "candles", "eggs", "flower_amount", "segment_amount")) if (p.containsKey(k)) { mult = number(p.get(k)); break; }
        out.merge(id, count * Math.max(1, Math.min(64, mult)), Long::sum);
    }
    private static long number(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 1; } }

    private static CompoundTag read(byte[] data) throws Exception {
        CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(MAX_UNPACKED));
        if (root.getCompound("Regions").isEmpty()) throw new IllegalArgumentException("Not a valid .litematic file (Regions missing)");
        return root;
    }

    private static List<Region> regions(CompoundTag root) {
        CompoundTag regs = root.getCompoundOrEmpty("Regions");
        List<Region> out = new ArrayList<>();
        long total = 0;
        for (String key : regs.keySet()) {
            CompoundTag reg = regs.getCompoundOrEmpty(key);
            ListTag pal = reg.getListOrEmpty("BlockStatePalette");
            long[] longs = reg.getLongArray("BlockStates").orElse(null);
            CompoundTag sz = reg.getCompoundOrEmpty("Size"), pos = reg.getCompoundOrEmpty("Position");
            int[] raw = { sz.getIntOr("x", 0), sz.getIntOr("y", 0), sz.getIntOr("z", 0) };
            int[] size = { Math.abs(raw[0]), Math.abs(raw[1]), Math.abs(raw[2]) };
            long vol = (long) size[0] * size[1] * size[2];
            if (pal.isEmpty() || longs == null || vol <= 0) continue;
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(pal.size() - 1));
            total += vol;
            // Sizes come from the file: don't allocate anything the data can't actually back
            if (total > MAX_VOLUME || pal.size() > 65536 || (long) longs.length * 64 < vol * bits) throw new IllegalArgumentException("Schematic is too large or damaged");
            List<String> names = new ArrayList<>();
            List<Map<String, String>> props = new ArrayList<>();
            for (int i = 0; i < pal.size(); i++) {
                CompoundTag e = pal.getCompoundOrEmpty(i);
                names.add(e.getStringOr("Name", "minecraft:air"));
                CompoundTag pr = e.getCompoundOrEmpty("Properties");
                Map<String, String> m = new LinkedHashMap<>();
                for (String k : pr.keySet()) m.put(k, pr.getStringOr(k, ""));
                props.add(m);
            }
            short[] idx = new short[(int) vol];
            long mask = (1L << bits) - 1;
            for (int i = 0; i < vol; i++) {
                long o = (long) i * bits;
                int li = (int) (o >>> 6), s = (int) (o & 63);
                long v = longs[li] >>> s;
                if (s + bits > 64) v |= longs[li + 1] << (64 - s);
                v &= mask;
                idx[i] = (short) (v < pal.size() ? v : 0);
            }
            int[] p0 = { pos.getIntOr("x", 0), pos.getIntOr("y", 0), pos.getIntOr("z", 0) };
            int[] min = new int[3];
            for (int a = 0; a < 3; a++) min[a] = raw[a] < 0 ? p0[a] + raw[a] + 1 : p0[a];
            out.add(new Region(names, props, size, min, idx));
        }
        return out;
    }

    /** Material list and metadata */
    public static Parsed parse(byte[] data) throws Exception {
        CompoundTag root = read(data);
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Region> regions = regions(root);
        for (Region r : regions) {
            long[] pc = new long[r.names.size()];
            for (short v : r.idx) pc[v & 0xffff]++;
            for (int i = 0; i < pc.length; i++) if (pc[i] > 0) blockToItems(r.names.get(i), r.props.get(i), pc[i], counts);
        }
        JsonArray items = new JsonArray();
        counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e -> {
            JsonObject o = new JsonObject(); o.addProperty("id", e.getKey()); o.addProperty("count", e.getValue()); items.add(o);
        });
        CompoundTag md = root.getCompoundOrEmpty("Metadata"), es = md.getCompoundOrEmpty("EnclosingSize");
        JsonObject meta = new JsonObject();
        meta.addProperty("author", cut(md.getStringOr("Author", ""), 80));
        meta.addProperty("description", cut(md.getStringOr("Description", ""), 500));
        meta.addProperty("size", es.isEmpty() ? "" : es.getIntOr("x", 0) + "×" + es.getIntOr("y", 0) + "×" + es.getIntOr("z", 0));
        meta.addProperty("regions", regions.size());
        meta.addProperty("dataVersion", root.getIntOr("MinecraftDataVersion", 0));
        return new Parsed(cut(md.getStringOr("Name", ""), 120), meta, items);
    }
    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    /** Block positions for the 3D view: palette + indices per region (uint16, little endian, base64) */

    public static JsonObject voxels(byte[] data) throws Exception {
        JsonArray regs = new JsonArray();
        for (Region r : regions(read(data))) {
            JsonObject o = new JsonObject();
            JsonArray min = new JsonArray(), size = new JsonArray(), pal = new JsonArray();
            for (int a = 0; a < 3; a++) { min.add(r.min[a]); size.add(r.size[a]); }
            for (int i = 0; i < r.names.size(); i++) {
                JsonObject e = new JsonObject(), pr = new JsonObject();
                e.addProperty("name", r.names.get(i));
                r.props.get(i).forEach(pr::addProperty);
                e.add("props", pr);
                pal.add(e);
            }
            ByteBuffer buf = ByteBuffer.allocate(r.idx.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            buf.asShortBuffer().put(r.idx);
            o.add("min", min); o.add("size", size); o.add("palette", pal);
            o.addProperty("data", Base64.getEncoder().encodeToString(buf.array()));
            regs.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("regions", regs);
        return out;
    }

    private Litematic() { }
}
