# Materiallist-Litematica

Material lists for building projects as a website, served straight from the Minecraft server (like BlueMap). A single mod jar for **Fabric, Minecraft 26.3**:

- **On the server:** The mod starts the website when the server starts. Players don't need to install anything.
- **In singleplayer:** The same jar starts the site for your own machine only (`http://localhost:8177`), without login.
- **On the client (optional):** Detects Litematica placements automatically, creates projects from them, reports build progress and shows a button for collection chests in the chest screen.

Requires Fabric API. Build the jar with `mod/build.sh` (no Gradle needed, just the Prism instance); it ends up in `mod/build/materiallist-litematica-2.0.0+26.3.jar`.

## Server setup

1. Put the jar into the `mods` folder of the Fabric server and start the server.
2. Adjust `config/materiallist.json` (created on first start) and restart the server:

```json
{
  "port": 8177,
  "bind": "0.0.0.0",
  "publicUrl": "http://play.example.org:8177",
  "allowedHosts": [],
  "maxUploadMb": 64,
  "maxProjectsPerPlayer": 50,
  "autoCreateFromLitematica": true,
  "syncSeconds": 5
}
```

- **`publicUrl`**: the address players use to reach the site. It is used in the login link, and its hostname is allowed automatically.
- **`allowedHosts`**: additional hostnames the site may be accessed through, e.g. `["list.example.org"]`. IP addresses and `localhost` always work. Everything else is rejected with error 403 (protection against foreign websites). `["*"]` turns the check off.
- The port must be open in the firewall. A proxy like Velocity does not forward it: the site runs on the machine of the actual Fabric server.

The data lives in the server folder under `materiallist/` (projects, schematics, collection chests, logins, texture cache). To back up, copy this folder.

**Upgrading from the old "Materialliste" version:** on first start, `config/materialliste.json` and the `materialliste/` data folder are moved to their new names automatically (only if the new ones don't exist yet). The client mod does the same for `config/materialliste-sync/`. Remove the old `materialliste-*.jar` from the `mods` folder.

## Usage

| In game | |
|---|---|
| `/materiallist login` | Puts a link into chat. Click it and you are logged in as this player in the browser for 30 days. |
| `/materiallist chest <project>` | The chest you are looking at becomes a collection chest for the project. Its contents are counted automatically. |
| `/materiallist chest off` | The chest no longer counts. |
| `/materiallist` | Address of the site and short help. |
| `/materiallist weblogin <player>` | Console/owners only: create a login link for a player. |

On the website:

- **Create projects**: upload a `.litematic` or JSON material list (or automatically via the client mod).
- **Checklist**, **Next steps** (what to gather, what to craft, where to put what you have), **Raw materials**, **Crafting steps**, **3D view**, **Edit & export**.
- **Share**: "Share" button at the top right, then enter player names. Shared players see the project, enter amounts, set collection chests and can download the schematic (under "Edit & export"). Only the owner can delete and share.
- **Merge**: if someone already has the same schematic as their own project, the shared project shows a "Merge" button. Collection chests and entered amounts move into the shared project, and the own project is deleted.
- **Next steps**: one card per final product with everything it needs in total, and the crafting steps in the right order. Plus a gathering list of all missing raw materials.
- **Stock**: comes from the collection chests (including the contents of shulker boxes and bundles), optionally also from the inventories of members who are online. Manually entered amounts apply until the value in the game changes or you click ✎.

## With and without the client mod

| | without client mod | with client mod + Litematica |
|---|---|---|
| Site, login, sharing, 3D view | yes | yes |
| Upload schematic | on the website | automatically from the active placement |
| Collection chests | via command | via button in the chest screen or command |
| Build progress (subtract placed blocks) | no | yes |

The server cannot see what a player has loaded in Litematica. That only works through the client mod. The old `materialliste-sync` mod must be removed from the `mods` folder first.

## Moving from the Docker version

1. The old container is already stopped, the old files are in `archive/docker-version/`.
2. Copy `archive/docker-version/data/projects/` and `…/schematics/` into the server's `materiallist/` folder (before starting the server).
3. In game, run `/materiallist login`. Projects without an owner belong to the first player who logs in.


## Security

- **Login only through the game**: the login link is a one-time token (valid for 5 minutes), so nobody can pose as another player. Sessions are only stored as a hash on the server.
- **Access**: everyone only sees their own projects and projects shared with them. Other people's projects respond as if they didn't exist.
- **Foreign websites** cannot send requests on behalf of a logged-in player (checks on `Origin`, `Host` and a cookie with `SameSite`).
- **Uploads** are limited: 64 MB per file, 512 MB unpacked, 64 million blocks, 50 projects per player.
- **No HTTPS**: the site only speaks HTTP. Anyone who can listen in (e.g. on the same Wi-Fi) can grab the session cookie. For public servers, put a reverse proxy with HTTPS in front and set its domain as `publicUrl`.
- **Client mod**: the reported build progress is trusted from the client. A manipulated client can therefore fake the progress of its own projects or projects shared with it, nothing more.

## Textures (icons and 3D view)

Textures and block models are loaded once on first use from [misode/mcmeta](https://github.com/misode/mcmeta) and stored in `materiallist/cache/assets/`. If the server has no internet connection, you get letter icons and single-colored blocks. To reload, delete the `materiallist/cache/` folder.

## Custom recipes

Create `materiallist/recipes.custom.json`, see the example in `examples/recipes.custom.example.json`. This lets you add mod blocks or override recipes.

## Notes on the Litematic import

- Double slabs count as 2, the upper half of doors and beds isn't counted, wall torches/signs become their item, flower pots give pot + plant, redstone wire gives redstone.
- Air, water, lava and piston heads are ignored.
- Contents of chests in the schematic are not counted.
