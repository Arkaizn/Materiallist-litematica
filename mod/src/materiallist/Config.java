package materiallist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Server settings, stored in config/materiallist.json */
public class Config {
    /** Port of the website */
    public int port = 8177;
    /** Address to listen on. Always 127.0.0.1 in singleplayer. */
    public String bind = "0.0.0.0";
    /** Address players use to reach the site. Used for the login link, e.g. http://play.example.org:8177 */
    public String publicUrl = "";
    /** Additional hostnames the site may be accessed through (the one from publicUrl is allowed automatically). "*" allows all. */
    public List<String> allowedHosts = new ArrayList<>();
    public int maxUploadMb = 64;
    public int maxProjectsPerPlayer = 50;
    /** Create projects automatically when a player with the client mod has an active Litematica placement */
    public boolean autoCreateFromLitematica = true;
    /** Seconds between two counts of the collection chests */
    public int syncSeconds = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Config load(Path file) {
        Config c = new Config();
        try {
            if (Files.exists(file)) { Config r = GSON.fromJson(Files.readString(file), Config.class); if (r != null) c = r; }
        } catch (Exception e) { System.err.println("[materiallist] config/materiallist.json is not readable, using the defaults: " + e)
; return c; }
        if (c.allowedHosts == null) c.allowedHosts = new ArrayList<>();
        if (c.bind == null || c.bind.isBlank()) c.bind = "0.0.0.0";
        if (c.publicUrl == null) c.publicUrl = "";
        try { Files.createDirectories(file.getParent()); Files.writeString(file, GSON.toJson(c)); } catch (Exception ignored) { }
        return c;
    }

    public String url() {
        String u = publicUrl.isBlank() ? "http://localhost:" + port : publicUrl.trim();
        return u.replaceAll("/+$", "");
    }
}
