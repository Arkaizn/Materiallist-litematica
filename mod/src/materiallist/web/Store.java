package materiallist.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** All data: projects, logins, collection chests. Every method is synchronized (web server and server thread both access it). */
public class Store {
    public record User(UUID uuid, String name) { }
    private record Login(User user, long expires) { }

    static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_MS = 30L * 24 * 3600 * 1000, LOGIN_MS = 5 * 60 * 1000;

    public final Path dir, projectDir, schematicDir, assetDir;
    private final Map<String, JsonObject> projects = new HashMap<>();
    private final Map<String, Login> logins = new HashMap<>();          // one-time tokens from the game
    private JsonObject sessions = new JsonObject();                      // sha256(Cookie) -> { uuid, name, expires }
    private JsonObject marks = new JsonObject();                         // "dimension|x,y,z" -> { project, items: {id: n} }

    public Store(Path dir) throws Exception {
        this.dir = dir;
        projectDir = dir.resolve("projects"); schematicDir = dir.resolve("schematics"); assetDir = dir.resolve("cache").resolve("assets");
        for (Path p : List.of(projectDir, schematicDir, assetDir)) Files.createDirectories(p);
        try (Stream<Path> s = Files.list(projectDir)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String n = f.getFileName().toString();
                if (!n.matches("[a-f0-9]{12}\\.json")) continue;
                try { JsonObject p = GSON.fromJson(Files.readString(f), JsonObject.class); projects.put(n.substring(0, 12), migrate(p)); }
                catch (Exception e) { System.err.println("[materiallist] Project " + n + " is not readable: " + e); }
            }
        }
        sessions = readJson("sessions.json");
        marks = readJson("marks.json");
    }

    private JsonObject readJson(String name) {
        try { Path f = dir.resolve(name); if (Files.exists(f)) { JsonObject o = GSON.fromJson(Files.readString(f), JsonObject.class); if (o != null) return o; } }
        catch (Exception e) { System.err.println("[materiallist] " + name + " is not readable: " + e); }
        return new JsonObject();
    }
    private void writeJson(Path file, JsonElement o) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { System.err.println("[materiallist] Saving failed (" + file + "): " + e); }
    }

    /** Old format of the Docker version: merge separate counters into one stock */
    private static JsonObject migrate(JsonObject p) {
        if (!p.has("stock")) {
            JsonObject stock = new JsonObject();
            for (String k : List.of("collected", "rawCollected")) if (p.has(k) && p.get(k).isJsonObject())
                for (var e : p.getAsJsonObject(k).entrySet()) { int n = num(e.getValue()); if (n > 0) stock.addProperty(e.getKey(), n + (stock.has(e.getKey()) ? stock.get(e.getKey()).getAsInt() : 0)); }
            p.add("stock", stock);
        }
        for (String k : List.of("collected", "rawCollected", "subtractCollected", "rawIncludeDone")) p.remove(k);
        if (!p.has("shared")) p.add("shared", new JsonArray());
        if (!p.has("items")) p.add("items", new JsonArray());
        if (!p.has("stopItems")) p.add("stopItems", new JsonArray());
        return p;
    }

    /* ---------- Login ---------- */
    private static String token() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** One-time token for the login link, valid for 5 minutes */
    public synchronized String createLogin(User user) {
        long now = System.currentTimeMillis();
        logins.values().removeIf(l -> l.expires < now);
        String t = token();
        logins.put(t, new Login(user, now + LOGIN_MS));
        return t;
    }
    /** Exchanges the one-time token for a session cookie, null if invalid */
    public synchronized String redeemLogin(String t) {
        Login l = t == null ? null : logins.remove(t);
        if (l == null || l.expires < System.currentTimeMillis()) return null;
        return createSession(l.user);
    }
    public synchronized String createSession(User user) {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> e.getValue().getAsJsonObject().get("expires").getAsLong() < now);
        String cookie = token();
        JsonObject s = new JsonObject();
        s.addProperty("uuid", user.uuid.toString()); s.addProperty("name", user.name); s.addProperty("expires", now + SESSION_MS);
        sessions.add(hash(cookie), s);
        writeJson(dir.resolve("sessions.json"), sessions);
        adoptOrphans(user);
        return cookie;
    }
    public synchronized User session(String cookie) {
        if (cookie == null || cookie.isEmpty()) return null;
        JsonElement e = sessions.get(hash(cookie));
        if (e == null) return null;
        JsonObject s = e.getAsJsonObject();
        if (s.get("expires").getAsLong() < System.currentTimeMillis()) return null;
        return new User(UUID.fromString(s.get("uuid").getAsString()), s.get("name").getAsString());
    }
    public synchronized void logout(String cookie) {
        if (cookie != null && sessions.remove(hash(cookie)) != null) writeJson(dir.resolve("sessions.json"), sessions);
    }

    /** Projects without an owner (copied from the old Docker version) belong to the first one who logs in */
    private void adoptOrphans(User user) {
        for (JsonObject p : projects.values()) if (!p.has("owner")) { p.add("owner", ownerJson(user)); save(p); System.out.println("[materiallist] Project \"" + str(p, "name") + "\" now belongs to " + user.name); }
    }
    private static JsonObject ownerJson(User u) { JsonObject o = new JsonObject(); o.addProperty("uuid", u.uuid.toString()); o.addProperty("name", u.name); return o; }

    /* ---------- Projects ---------- */
    static String str(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : ""; }
    static int num(JsonElement e) { try { double d = e.getAsDouble(); return Double.isFinite(d) ? (int) Math.max(-1e9, Math.min(1e9, Math.round(d))) : 0; } catch (Exception x) { return 0; } }

    public static boolean isOwner(JsonObject p, User u) { return p.has("owner") && u.uuid.toString().equals(str(p.getAsJsonObject("owner"), "uuid")); }
    public static boolean canAccess(JsonObject p, User u) {
        if (isOwner(p, u)) return true;
        String n = u.name.toLowerCase(Locale.ROOT);
        for (JsonElement e : p.getAsJsonArray("shared")) if (n.equals(e.getAsString())) return true;
        return false;
    }

    public synchronized JsonObject get(String id) { return id != null && id.matches("[a-f0-9]{12}") ? projects.get(id) : null; }
    public synchronized List<JsonObject> accessible(User u) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject p : projects.values()) if (canAccess(p, u)) out.add(p);
        out.sort((a, b) -> Long.compare(b.get("updated").getAsLong(), a.get("updated").getAsLong()));
        return out;
    }
    /** Projects that have received a state from the game at least once */
    public synchronized List<String> syncedIds() {
        List<String> out = new ArrayList<>();
        for (JsonObject p : projects.values()) if (p.has("sync")) out.add(str(p, "id"));
        return out;
    }
    /** Known player with this name (owner of a project), null if unknown */
    public synchronized User knownUser(String name) {
        for (JsonObject p : projects.values()) if (p.has("owner") && name.equalsIgnoreCase(str(p.getAsJsonObject("owner"), "name")))
            return new User(UUID.fromString(str(p.getAsJsonObject("owner"), "uuid")), str(p.getAsJsonObject("owner"), "name"));
        return null;
    }
    public synchronized long ownedCount(User u) { return projects.values().stream().filter(p -> isOwner(p, u)).count(); }

    public synchronized JsonObject create(User owner, String name, JsonArray items, JsonObject source) {
        byte[] b = new byte[6]; RANDOM.nextBytes(b);
        long now = System.currentTimeMillis();
        JsonObject p = new JsonObject();
        p.addProperty("id", HexFormat.of().formatHex(b));
        p.addProperty("name", name == null || name.isBlank() ? "New project" : name.strip().substring(0, Math.min(120, name.strip().length())));
        p.addProperty("created", now); p.addProperty("updated", now);
        p.add("owner", ownerJson(owner));
        p.add("shared", new JsonArray());
        p.add("source", source);
        p.add("items", normalizeItems(items));
        p.add("stock", new JsonObject());
        p.add("stopItems", new JsonArray());
        p.addProperty("notes", "");
        p.addProperty("countInventory", false);
        projects.put(str(p, "id"), p);
        save(p);
        return p;
    }
    public synchronized void save(JsonObject p) { writeJson(projectDir.resolve(str(p, "id") + ".json"), p); }
    public synchronized void delete(String id) {
        if (projects.remove(id) == null) return;
        try { Files.deleteIfExists(projectDir.resolve(id + ".json")); Files.deleteIfExists(schematic(id)); } catch (Exception ignored) { }
        if (marks.entrySet().removeIf(e -> id.equals(str(e.getValue().getAsJsonObject(), "project")))) saveMarks();
    }
    public Path schematic(String id) { return schematicDir.resolve(id + ".litematic"); }

    /** Clean up and merge an item list -> [{id, count}] */
    public static JsonArray normalizeItems(JsonArray list) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (list != null) for (JsonElement e : list) {
            if (!e.isJsonObject()) continue;
            String id = cleanId(str(e.getAsJsonObject(), "id"));
            int n = e.getAsJsonObject().has("count") ? num(e.getAsJsonObject().get("count")) : 0;
            if (id == null || n <= 0) continue;
            m.merge(id, n, (a, c) -> (int) Math.min(1e9, (long) a + c));
            if (m.size() > 5000) break;
        }
        JsonArray out = new JsonArray();
        m.forEach((id, n) -> { JsonObject o = new JsonObject(); o.addProperty("id", id); o.addProperty("count", n); out.add(o); });
        return out;
    }
    /** Item ID without "minecraft:", only harmless characters. null if unusable. */
    public static String cleanId(String raw) {
        String id = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        // Names that have a special meaning in JavaScript objects are not items
        if (id.equals("__proto__") || id.equals("constructor") || id.equals("prototype")) return null;
        return id.matches("[a-z0-9_#][a-z0-9_:./-]{0,99}") ? id : null;
    }
    /** Clean up {id: count} */
    public static JsonObject cleanCounts(JsonObject in) {
        Map<String, Integer> m = new TreeMap<>();
        if (in != null) for (var e : in.entrySet()) {
            String id = cleanId(e.getKey());
            int n = num(e.getValue());
            if (id != null && n >= 0 && m.size() < 5000) m.merge(id, n, Integer::sum);
        }
        JsonObject out = new JsonObject();
        m.forEach(out::addProperty);
        return out;
    }

    /** Summary for the project list */
    public synchronized JsonObject summary(JsonObject p, User u) {
        JsonObject sync = p.has("sync") ? p.getAsJsonObject("sync") : new JsonObject();
        JsonObject game = sync.has("stock") ? sync.getAsJsonObject("stock") : new JsonObject(), placed = sync.has("placed") ? sync.getAsJsonObject("placed") : new JsonObject();
        JsonObject manual = p.getAsJsonObject("stock");
        long total = 0, done = 0;
        JsonArray items = p.getAsJsonArray("items");
        for (JsonElement e : items) {
            JsonObject i = e.getAsJsonObject();
            String id = str(i, "id");
            int count = i.get("count").getAsInt();
            int have = manual.has(id) ? num(manual.get(id)) : game.has(id) ? num(game.get(id)) : 0;
            total += count;
            done += Math.min(count, (long) have + (placed.has(id) ? num(placed.get(id)) : 0));
        }
        JsonObject s = new JsonObject();
        s.addProperty("id", str(p, "id")); s.addProperty("name", str(p, "name")); s.add("updated", p.get("updated"));
        s.addProperty("types", items.size()); s.addProperty("total", total); s.addProperty("done", done);
        s.addProperty("source", p.has("source") ? str(p.getAsJsonObject("source"), "type") : "");
        s.addProperty("syncTime", sync.has("time") ? sync.get("time").getAsLong() : 0);
        s.addProperty("mine", isOwner(p, u));
        s.addProperty("owner", p.has("owner") ? str(p.getAsJsonObject("owner"), "name") : "");
        return s;
    }

    /* ---------- Collection chests ---------- */
    public synchronized Map<String, String> markTargets() {
        Map<String, String> m = new HashMap<>();
        for (var e : marks.entrySet()) m.put(e.getKey(), str(e.getValue().getAsJsonObject(), "project"));
        return m;
    }
    public synchronized String markOf(String key) { return marks.has(key) ? str(marks.getAsJsonObject(key), "project") : null; }
    public synchronized void setMark(String key, String project) {
        if (project == null) marks.remove(key);
        else { JsonObject o = new JsonObject(); o.addProperty("project", project); o.add("items", new JsonObject()); marks.add(key, o); }
        saveMarks();
    }
    /** Assign all collection chests of one project to another project */
    public synchronized void moveMarks(String from, String to) {
        boolean any = false;
        for (var e : marks.entrySet()) if (from.equals(str(e.getValue().getAsJsonObject(), "project"))) { e.getValue().getAsJsonObject().addProperty("project", to); any = true; }
        if (any) saveMarks();
    }
    /** Remember the contents of a collection chest. Returns true if they changed. */
    public synchronized boolean setMarkItems(String key, Map<String, Integer> items) {
        if (!marks.has(key)) return false;
        JsonObject o = new JsonObject();
        new TreeMap<>(items).forEach(o::addProperty);
        JsonObject m = marks.getAsJsonObject(key);
        if (o.equals(m.get("items"))) return false;
        m.add("items", o);
        return true;
    }
    public synchronized void saveMarks() { writeJson(dir.resolve("marks.json"), marks); }
    public synchronized int markCount(String project) {
        int n = 0;
        for (var e : marks.entrySet()) if (project.equals(str(e.getValue().getAsJsonObject(), "project"))) n++;
        return n;
    }
    /** Sum of all collection chests of a project */
    public synchronized Map<String, Integer> markStock(String project) {
        Map<String, Integer> m = new TreeMap<>();
        for (var e : marks.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            if (!project.equals(str(o, "project")) || !o.has("items")) continue;
            for (var i : o.getAsJsonObject("items").entrySet()) m.merge(i.getKey(), num(i.getValue()), Integer::sum);
        }
        return m;
    }

    /**
     * Write the state from the game into a project (null = leave unchanged). Manually entered amounts apply until the value in the game changes.

     */
    public synchronized void applySync(JsonObject p, JsonObject stock, JsonObject placed, String player) {
        JsonObject prev = p.has("sync") ? p.getAsJsonObject("sync") : new JsonObject();
        JsonObject old = prev.has("stock") ? prev.getAsJsonObject("stock") : null;
        if (stock != null && stock.equals(old) && (placed == null || placed.equals(prev.get("placed")))) return;
        if (stock != null && old != null) {
            JsonObject manual = p.getAsJsonObject("stock");
            final JsonObject fresh = stock;
            manual.entrySet().removeIf(e -> (fresh.has(e.getKey()) ? num(fresh.get(e.getKey())) : 0) != (old.has(e.getKey()) ? num(old.get(e.getKey())) : 0));
        }
        JsonObject s = new JsonObject();
        s.addProperty("time", System.currentTimeMillis());
        s.addProperty("player", player == null ? str(prev, "player") : player);
        s.add("stock", stock != null ? stock : old != null ? old : new JsonObject());
        s.add("placed", placed != null ? placed : prev.has("placed") ? prev.get("placed") : new JsonObject());
        p.add("sync", s);
        save(p);
    }
}
