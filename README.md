# Universal Settings

A client-side Minecraft mod (Fabric + NeoForge) that syncs your settings across every computer, instance, and game version you play on.

No more redoing the same setup for every new instance or PC. A fresh install pulls your settings on first launch, and every change you make afterwards syncs to your other installs automatically.

**What syncs:** everything in `options.txt` and your multiplayer server list.

**Profiles:** keep multiple named profiles (e.g. `default`, `laptop`) and pick one as the default. If an install already has customized settings, you get a one-time choice to keep them as a new profile or switch to your synced ones.

**Authentication:** no login, ever. The mod proves who you are through the same Mojang session handshake used to join multiplayer servers, so only you can read or write your settings. In offline mode syncing pauses for the session and your local settings are untouched.

## Privacy

This mod uploads your settings to a sync server so they can be shared across your installs. By default that server is the central instance operated by the mod author ([universalsettings.whitemcwizard.nl](https://universalsettings.whitemcwizard.nl)).

- **What is stored:** your Minecraft username and UUID, the contents of your `options.txt`, your `servers.dat` (multiplayer server list), and your exclusion lists.
- **Your server list is included.** `servers.dat` holds the addresses of every multiplayer server you've saved. If you'd rather not upload that, turn off **Sync servers** in the in-game *Ignored Settings* screen.
- **You can delete it.** Deleting a profile in-game removes it from the server.
- **No secrets are stored.** The Mojang handshake never exposes your password or launcher token to the sync server.

Prefer not to use the central server? This repository includes the full server (`server/`). Self-host it and point the mod at your own instance with `serverUrl` in `config/universalsettings.json`.

## Layout

- `common/`: all mod logic (sync manager, options codec, HTTP client, auth, first-run UI, mixins)
- `fabric/`, `neoforge/`: thin loader entry points
- `server/`: the central sync server (Node.js + Fastify + SQLite)
- `versions/{mc}/gradle.properties`: per-Minecraft-version properties, shared by all branches

Multi-version builds are managed by [Stonecutter](https://stonecutter.kikugie.dev/):
every supported Minecraft version is a Gradle subproject ("node") of each branch,
e.g. `:fabric:26.1.2`. The active version's sources live directly in `{branch}/src/`;
use the `Reset active project` task before committing after a version switch.

To add a Minecraft version: register it in `settings.gradle` (`versions '26.1.2', '...'`),
create `versions/{mc}/gradle.properties` with that version's properties, and port
version-specific code with Stonecutter comments (`//? if <1.21 {`).

## Development

Mod (JDK 21+ recommended to run Gradle; each Minecraft version targets a different
Java release, and Gradle auto-provisions the right toolchain per version):

```sh
./gradlew buildAll                            # build every version of every branch
./gradlew :fabric:26.1.2:build                # build one loader jar
./gradlew :fabric:26.1.2:runClient            # dev client
```

Server (Node 22+):

```sh
cd server
npm install
AUTH_DISABLED=true npm run dev   # dev server on :8080, skips Mojang verification
npm test                         # vitest API tests
```

For an end-to-end dev loop, point the run dir's `config/universalsettings.json` `serverUrl` at `http://localhost:8080` and start the dev server with `AUTH_DISABLED=true`. Dev clients have no real Mojang token, so the mod skips the client-side join in dev environments to match. The server refuses to start with `AUTH_DISABLED` when `NODE_ENV=production`.

### API

| Method/Path | Auth |
|---|---|
| `POST /auth/challenge`, `POST /auth/verify` | (rate limited) |
| `GET /players/:uuid/profiles[/default\|/:name]` | JWT |
| `PUT /players/:uuid/profiles/:name` | JWT |
| `POST /players/:uuid/default` | JWT |
| `POST /players/:uuid/profiles/:name/rename` | JWT |
| `DELETE /players/:uuid/profiles/:name` | JWT |
| `GET\|PUT /players/:uuid/exclusions` | JWT |
| `PUT /players/:uuid/profiles/:name/exclusions` | JWT |
| `GET /health` | |

### Deploying the central server

```sh
cd server
cp .env.example .env       # set JWT_SECRET (openssl rand -hex 32) and DOMAIN
docker compose up -d --build
```

The bundled Compose stack runs the API behind Caddy, which obtains HTTPS automatically for `DOMAIN`. The SQLite database lives in `server/data/`; back it up by copying the file.

## License

[MIT](LICENSE). Based on the [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template).
