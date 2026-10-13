package materiallist.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import materiallist.Config;
import materiallist.web.Store.User;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/** The website: static files from the jar, JSON API with login, texture cache. */
public class WebServer {
    private static final Map<String, String> STATIC = Map.of("/", "index.html", "/index.html", "index.html", "/logic.js", "logic.js", "/mcmodel.js", "mcmodel.js", "/render.js", "render.js");
    private static final String ASSET_BASE = "https://raw.githubusercontent.com/misode/mcmeta/";
    private static final Map<String, String> ASSETS = Map.of(
        "atlas.png", "atlas/all/atlas.png", "atlas.json", "atlas/all/data.min.json",
        "blockstates.json", "summary/assets/block_definition/data.min.json",
        "models.json", "summary/assets/model/data.min.json", "items.json", "summary/assets/item_definition/data.min.json");
    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private static class HttpError extends RuntimeException { final int code; HttpError(int code, String msg) { super(msg); this.code = code; } }

    private final Config cfg;
    private final Store store;
    private final Supplier<User> localUser;   // singleplayer: whoever sits at the same machine is automatically the player
    private final HttpServer http;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "materiallist-web"); t.setDaemon(true); return t; });
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String publicHost;

    public WebServer(Config cfg, Store store, String bind, Supplier<User> localUser) throws IOException {
        this.cfg = cfg; this.store = store; this.localUser = localUser;
        String h = "";
        try { h = URI.create(cfg.url()).getHost(); } catch (Exception ignored) { }
        publicHost = h == null ? "" : h.toLowerCase(Locale.ROOT);
        // close hanging connections after one minute
        for (String k : List.of("sun.net.httpserver.maxReqTime", "sun.net.httpserver.maxRspTime")) if (System.getProperty(k) == null) System.setProperty(k, "60");
        http = HttpServer.create(new InetSocketAddress(bind, cfg.port), 32);
        http.createContext("/", this::handle);
        http.setExecutor(pool);
        http.start();
    }
    public void stop() { http.stop(0); pool.shutdownNow(); }

    /* ---------- Responses ---------- */
    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        ex.close();
    }
    private static void json(HttpExchange ex, int code, JsonElement o) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] body = Store.GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (body.length > 4096 && ae != null && ae.contains("gzip")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(b)) { g.write(body); }
            body = b.toByteArray();
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        send(ex, code, "application/json; charset=utf-8", body);
    }
    private static JsonObject ok() { JsonObject o = new JsonObject(); o.addProperty("ok", true); return o; }
    private static void fail(HttpExchange ex, int code, String msg) throws IOException { JsonObject o = new JsonObject(); o.addProperty("error", msg); json(ex, code, o); }

    private byte[] body(HttpExchange ex, long limit) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) try { if (Long.parseLong(len) > limit) throw new HttpError(413, "File too large"); } catch (NumberFormatException ignored) { }
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
            if (b.length > limit) throw new HttpError(413, "File too large");
            return b;
        }
    }
    private JsonObject jsonBody(HttpExchange ex) throws IOException {
        try { JsonObject o = Store.GSON.fromJson(new String(body(ex, 4 << 20), StandardCharsets.UTF_8), JsonObject.class); return o == null ? new JsonObject() : o; }
        catch (com.google.gson.JsonParseException e) { throw new HttpError(400, "Invalid JSON"); }
    }
    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try { m.put(URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8), i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        }
        return m;
    }

    /* ---------- Protection against foreign websites ---------- */
    private boolean hostAllowed(String header) {
        if (header == null) return false;
        String h = header.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "").replaceAll("^\\[|\\]$", "");
        if (h.isEmpty()) return false;
        if (h.equals(publicHost)) return true;
        for (String a : cfg.allowedHosts) if (a != null && (a.equals("*") || a.trim().toLowerCase(Locale.ROOT).equals(h))) return true;
        // IP addresses and local names are fine, other domains only from the config (protection against DNS rebinding)
        return h.equals("localhost") || h.matches("[0-9.]+") || h.contains(":") || !h.contains(".") || h.matches(".*\\.(local|lan|home|internal|home\\.arpa|localhost)");
    }
    private boolean requestAllowed(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (!hostAllowed(host)) return false;
        boolean get = ex.getRequestMethod().equals("GET");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("null")) {
            try { URI u = URI.create(origin); String oh = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""); if (!oh.equalsIgnoreCase(host)) return false; } catch (Exception e) { return false; }
        } else if (origin != null && !get) return false;
        String site = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
        return site == null || get || site.equals("same-origin") || site.equals("none");
    }

    private static String cookie(HttpExchange ex) {
        for (String line : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) for (String c : line.split(";")) {
            String t = c.trim();
            if (t.startsWith("ml_session=")) return t.substring(11);
        }
        return null;
    }
    private void setCookie(HttpExchange ex, String value, long maxAge) {
        // If the site runs over HTTPS according to publicUrl, the cookie is never sent unencrypted
        ex.getResponseHeaders().add("Set-Cookie", "ml_session=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAge + (cfg.url().startsWith("https://") ? "; Secure" : ""));
    }
    private User user(HttpExchange ex) {
        User u = store.session(cookie(ex));
        if (u == null && ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            User local = localUser.get();
            if (local != null) { setCookie(ex, store.createSession(local), 30L * 24 * 3600); return local; }
        }
        return u;
    }

    /* ---------- Dispatcher ---------- */
    private void handle(HttpExchange ex) {
        try {
            var h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff"); h.set("X-Frame-Options", "DENY"); h.set("Referrer-Policy", "no-referrer"); h.set("Content-Security-Policy", CSP);
            if (!requestAllowed(ex)) { fail(ex, 403, "Request denied: this address is not allowed. Add the hostname to allowedHosts or publicUrl in config/materiallist.json."); return; }
            String path = ex.getRequestURI().getPath(), m = ex.getRequestMethod();
            if (path.startsWith("/api/")) api(ex, path.substring(5).split("/"), m);
            else if (!m.equals("GET")) fail(ex, 405, "Not allowed");
            else if (path.equals("/login")) login(ex);
            else if (path.startsWith("/assets/")) asset(ex, path.substring(8));
            else if (STATIC.containsKey(path)) {
                try (InputStream in = WebServer.class.getResourceAsStream("/web/" + STATIC.get(path))) {
                    if (in == null) { fail(ex, 404, "Not found"); return; }
                    h.set("Cache-Control", "no-cache");
                    send(ex, 200, path.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8", in.readAllBytes());
                }
            } else fail(ex, 404, "Not found");
        } catch (HttpError e) {
            try { fail(ex, e.code, e.getMessage()); } catch (IOException ignored) { }
        } catch (Throwable t) {
            System.err.println("[materiallist] Error at " + ex.getRequestURI().getPath() + ": " + t);
            try { fail(ex, 500, "Internal error"); } catch (Exception ignored) { }
        } finally { ex.close(); }
    }

    private void login(HttpExchange ex) throws IOException {
        String cookie = store.redeemLogin(query(ex).get("t"));
        if (cookie == null) { send(ex, 403, "text/plain; charset=utf-8", "This login link has expired or was already used. Run /materiallist login in the game again.".getBytes(StandardCharsets.UTF_8)); return; }
        setCookie(ex, cookie, 30L * 24 * 3600);
        ex.getResponseHeaders().set("Location", "/");
        send(ex, 302, "text/plain", new byte[0]);
    }

    private void api(HttpExchange ex, String[] p, String m) throws Exception {
        User u = user(ex);
        if (p[0].equals("me") && m.equals("GET")) {
            if (u == null) { fail(ex, 401, "Not logged in"); return; }
            JsonObject o = new JsonObject(); o.addProperty("name", u.name()); o.addProperty("uuid", u.uuid().toString()); o.addProperty("local", localUser.get() != null);
            json(ex, 200, o); return;
        }
        if (u == null) { fail(ex, 401, "Not logged in. Run /materiallist login in the game."); return; }
        if (p[0].equals("logout") && m.equals("POST")) { store.logout(cookie(ex)); setCookie(ex, "", 0); json(ex, 200, ok()); return; }
        if (p[0].equals("custom-recipes") && m.equals("GET")) {
            Path f = store.dir.resolve("recipes.custom.json");
            JsonObject o = new JsonObject();
            try { if (Files.exists(f)) o = Store.GSON.fromJson(Files.readString(f), JsonObject.class); } catch (Exception ignored) { }
            json(ex, 200, o == null ? new JsonObject() : o); return;
        }
        if (p[0].equals("upload") && m.equals("POST")) { upload(ex, u); return; }
        if (p[0].equals("projects") && p.length == 1) {
            if (m.equals("GET")) {
                JsonArray list = new JsonArray();
                synchronized (store) { for (JsonObject pr : store.accessible(u)) list.add(store.summary(pr, u)); }
                json(ex, 200, list); return;
            }
            if (m.equals("POST")) {
                JsonObject b = jsonBody(ex), created;
                synchronized (store) {
                    quota(u);
                    JsonObject src = new JsonObject(); src.addProperty("type", "manual");
                    created = view(store.create(u, Store.str(b, "name"), b.has("items") && b.get("items").isJsonArray() ? b.getAsJsonArray("items") : null, src), u);
                }
                json(ex, 201, created); return;
            }
        }
        if (p[0].equals("projects") && p.length >= 2) { project(ex, p, m, u); return; }
        fail(ex, 404, "Unknown endpoint");
    }

    private void quota(User u) { if (store.ownedCount(u) >= cfg.maxProjectsPerPlayer) throw new HttpError(429, "You have reached the maximum number of projects (" + cfg.maxProjectsPerPlayer + ")"); }

    /** Project as the website receives it */
    private JsonObject view(JsonObject p, User u) {
        JsonObject o = p.deepCopy();
        String id = Store.str(p, "id");
        o.addProperty("has3d", Files.exists(store.schematic(id)));
        o.addProperty("mine", Store.isOwner(p, u));
        o.addProperty("chests", store.markCount(id));
        return o;
    }

    private void project(HttpExchange ex, String[] parts, String m, User u) throws Exception {
        String id = parts[1];
        byte[] upload = parts.length == 3 && parts[2].equals("schematic") && m.equals("POST") ? body(ex, (long) cfg.maxUploadMb << 20) : null;
        if (upload != null) { try { Litematic.voxels(upload); } catch (Exception e) { throw new HttpError(422, "File could not be read"); } }
        JsonObject b = (parts.length == 2 && m.equals("PUT")) || (parts.length == 3 && parts[2].equals("merge") && m.equals("POST")) ? jsonBody(ex) : null;
        Path download = null;
        String downloadName = "schematic.litematic";
        JsonElement out;
        int code = 200;
        Path voxelFile = null;
        synchronized (store) {
            JsonObject p = store.get(id);
            if (p == null || !Store.canAccess(p, u)) throw new HttpError(404, "Project not found");   // other people's projects don't even reveal that they exist
            boolean owner = Store.isOwner(p, u);
            if (parts.length == 3) {
                if (parts[2].equals("voxels") && m.equals("GET")) {
                    if (!Files.exists(store.schematic(id))) throw new HttpError(404, "No .litematic file is stored for this project");
                    voxelFile = store.schematic(id); out = null;
                } else if (upload != null) { Files.write(store.schematic(id), upload); out = ok(); }
                else if (parts[2].equals("schematic") && m.equals("GET")) {
                    // Whoever has access to the project may also download the schematic
                    if (!Files.exists(store.schematic(id))) throw new HttpError(404, "No .litematic file is stored for this project");
                    download = store.schematic(id); out = null;
                    String fn = p.has("source") ? Store.str(p.getAsJsonObject("source"), "filename") : "";
                    if (fn.isBlank()) fn = Store.str(p, "name") + ".litematic";
                    downloadName = fn.replaceAll("[^A-Za-z0-9 ._()\\-]", "_");
                    if (!downloadName.toLowerCase(Locale.ROOT).endsWith(".litematic")) downloadName += ".litematic";
                } else if (parts[2].equals("merge") && b != null) {
                    // Move your own project with the same schematic into this (shared) project: collection chests and manual entries come along
                    JsonObject from = store.get(Store.str(b, "from"));
                    if (from == null || from == p || !Store.isOwner(from, u)) throw new HttpError(404, "Your project was not found");
                    if (!Store.normalizeItems(from.getAsJsonArray("items")).equals(Store.normalizeItems(p.getAsJsonArray("items")))) throw new HttpError(409, "The two projects don't have the same material list");
                    JsonObject mine = from.getAsJsonObject("stock"), target = p.getAsJsonObject("stock");
                    for (var e : mine.entrySet()) target.addProperty(e.getKey(), (long) Store.num(e.getValue()) + (target.has(e.getKey()) ? Store.num(target.get(e.getKey())) : 0));
                    p.add("stock", Store.cleanCounts(target));
                    if (!Files.exists(store.schematic(id)) && Files.exists(store.schematic(Store.str(from, "id")))) Files.copy(store.schematic(Store.str(from, "id")), store.schematic(id));
                    store.moveMarks(Store.str(from, "id"), id);
                    store.delete(Store.str(from, "id"));
                    p.addProperty("updated", System.currentTimeMillis());
                    store.save(p);
                    out = store.summary(p, u);
                }
                else throw new HttpError(404, "Unknown endpoint");
            } else if (m.equals("GET")) out = view(p, u);
            else if (m.equals("DELETE")) {
                if (!owner) throw new HttpError(403, "Only the owner can delete the project");
                store.delete(id); out = ok();
            } else if (m.equals("PUT")) {
                String name = Store.str(b, "name").strip();
                if (!name.isEmpty()) p.addProperty("name", name.substring(0, Math.min(120, name.length())));
                if (b.has("items") && b.get("items").isJsonArray()) p.add("items", Store.normalizeItems(b.getAsJsonArray("items")));
                if (b.has("stock") && b.get("stock").isJsonObject()) p.add("stock", Store.cleanCounts(b.getAsJsonObject("stock")));
                if (b.has("stopItems") && b.get("stopItems").isJsonArray()) {
                    JsonArray stop = new JsonArray();
                    for (JsonElement e : b.getAsJsonArray("stopItems")) { String s = e.isJsonPrimitive() ? Store.cleanId(e.getAsString()) : null; if (s != null && stop.size() < 2000) stop.add(s); }
                    p.add("stopItems", stop);
                }
                if (b.has("notes") && b.get("notes").isJsonPrimitive()) { String n = b.get("notes").getAsString(); p.addProperty("notes", n.substring(0, Math.min(20000, n.length()))); }
                if (b.has("clearSync") && b.get("clearSync").isJsonPrimitive() && b.get("clearSync").getAsBoolean()) p.remove("sync");
                if (owner && b.has("shared") && b.get("shared").isJsonArray()) {
                    JsonArray shared = new JsonArray();
                    for (JsonElement e : b.getAsJsonArray("shared")) {
                        String s = e.isJsonPrimitive() ? e.getAsString().strip().toLowerCase(Locale.ROOT) : "";
                        if (s.matches("[a-z0-9_.]{1,32}") && !s.equalsIgnoreCase(u.name()) && shared.size() < 50 && !shared.contains(new com.google.gson.JsonPrimitive(s))) shared.add(s);
                    }
                    p.add("shared", shared);
                }
                if (owner && b.has("countInventory") && b.get("countInventory").isJsonPrimitive()) p.addProperty("countInventory", b.get("countInventory").getAsBoolean());
                p.addProperty("updated", System.currentTimeMillis());
                store.save(p);
                out = store.summary(p, u);
            } else throw new HttpError(405, "Not allowed");
        }
        if (download != null) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            send(ex, 200, "application/octet-stream", Files.readAllBytes(download));
            return;
        }
        if (voxelFile != null) {
            try { out = Litematic.voxels(Files.readAllBytes(voxelFile)); } catch (Exception e) { throw new HttpError(422, "Schematic could not be read"); }
        }
        json(ex, code, out);
    }

    private void upload(HttpExchange ex, User u) throws Exception {
        String filename = query(ex).getOrDefault("filename", "upload").replaceAll("[\\\\/\\x00-\\x1f]", "_");
        if (filename.length() > 150) filename = filename.substring(filename.length() - 150);
        String lower = filename.toLowerCase(Locale.ROOT), base = filename.replaceFirst("\\.[^.]+$", "");
        if (!lower.endsWith(".litematic") && !lower.endsWith(".json")) throw new HttpError(400, "Only .litematic and .json are supported");
        byte[] data = body(ex, (long) cfg.maxUploadMb << 20);
        String name = base;
        JsonArray items;
        JsonObject src = new JsonObject();
        src.addProperty("filename", filename);
        try {
            if (lower.endsWith(".litematic")) {
                Litematic.Parsed r = Litematic.parse(data);
                items = r.items();
                if (!r.name().isBlank() && !r.name().equalsIgnoreCase("Unnamed")) name = r.name();
                src.addProperty("type", "litematic"); src.add("meta", r.meta());
            } else {
                JsonElement j = Store.GSON.fromJson(new String(data, StandardCharsets.UTF_8), JsonElement.class);
                src.addProperty("type", "json");
                if (j != null && j.isJsonArray()) items = j.getAsJsonArray();
                else if (j != null && j.isJsonObject() && j.getAsJsonObject().has("items") && j.getAsJsonObject().get("items").isJsonArray()) {
                    items = j.getAsJsonObject().getAsJsonArray("items");
                    String n = Store.str(j.getAsJsonObject(), "name");
                    if (!n.isBlank() && !n.equals("Unnamed")) name = n;
                } else throw new IllegalArgumentException("JSON format not recognized");
            }
        } catch (HttpError e) { throw e; }
        catch (Exception e) { throw new HttpError(422, "\"" + filename + "\" could not be read"); }
        JsonObject sum = createProject(u, name, items, src, lower.endsWith(".litematic") ? data : null);
        json(ex, 201, sum);
    }

    /** Creates a project (also for uploads from the game) */
    public JsonObject createProject(User u, String name, JsonArray items, JsonObject src, byte[] schematic) throws IOException {
        synchronized (store) {
            quota(u);
            if (Store.normalizeItems(items).isEmpty()) throw new HttpError(422, "No items found in the file");
            JsonObject p = store.create(u, name, items, src);
            if (schematic != null) Files.write(store.schematic(Store.str(p, "id")), schematic);
            return store.summary(p, u);
        }
    }

    /* ---------- Textures and models (load once, then from the cache) ---------- */
    private final Object assetLock = new Object();
    private void asset(HttpExchange ex, String name) throws Exception {
        if (!ASSETS.containsKey(name)) { fail(ex, 404, "Not found"); return; }
        Path file = store.assetDir.resolve(name);
        synchronized (assetLock) {
            if (!Files.exists(file)) {
                try {
                    HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(ASSET_BASE + ASSETS.get(name))).timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
                    Path tmp = file.resolveSibling(name + ".tmp");
                    Files.write(tmp, r.body());
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    System.err.println("[materiallist] Textures could not be loaded (" + name + "): " + e);
                    fail(ex, 502, "Textures could not be loaded (no internet connection?)"); return;

                }
            }
        }
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (name.endsWith(".json") && ae != null && ae.contains("gzip")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(b)) { g.write(data); }
            data = b.toByteArray();
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        send(ex, 200, name.endsWith(".png") ? "image/png" : "application/json", data);
    }
}
