import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type { Db, PlayerRow, ProfileRow } from '../db.js';
import { normalizeIncoming, normalizeStored, presentFor } from '../translations.js';

const PROFILE_NAME_PATTERN = /^[A-Za-z0-9 _-]{1,32}$/;
const MAX_PROFILES_PER_PLAYER = 50;
const MAX_OPTIONS_JSON_BYTES = 256 * 1024;
const MAX_SERVERS_DAT_BYTES = 512 * 1024;

const UUID_PARAM_SCHEMA = {
  type: 'object',
  required: ['uuid'],
  properties: { uuid: { type: 'string', pattern: '^[0-9a-f]{32}$' } },
};

interface UuidParams {
  uuid: string;
}

interface UuidNameParams extends UuidParams {
  name: string;
}

function summary(row: ProfileRow, defaultProfileId: number | null) {
  return {
    name: row.name,
    updatedAt: row.updated_at,
    gameVersion: row.game_version,
    isDefault: row.id === defaultProfileId,
  };
}

function fullProfile(row: ProfileRow, defaultProfileId: number | null, forGameVersion: string | null) {
  return {
    ...summary(row, defaultProfileId),
    options: presentFor(JSON.parse(row.options_json) as Record<string, string>, forGameVersion),
    serversDat: row.servers_dat ? Buffer.from(row.servers_dat).toString('base64') : null,
    excludedKeys: row.excluded_keys ? (JSON.parse(row.excluded_keys) as string[]) : [],
  };
}

const PROFILE_GET_QUERY_SCHEMA = {
  type: 'object',
  properties: { gameVersion: { type: 'string', maxLength: 32 } },
};

interface ProfileGetQuery {
  gameVersion?: string;
}

const EXCLUSIONS_BODY_SCHEMA = {
  type: 'object',
  required: ['excludedKeys'],
  properties: {
    excludedKeys: {
      type: 'array',
      maxItems: 512,
      items: { type: 'string', maxLength: 128 },
    },
  },
};

export function registerProfileRoutes(app: FastifyInstance, db: Db) {
  const getPlayer = db.prepare('SELECT * FROM players WHERE uuid = ?');
  const getProfiles = db.prepare('SELECT * FROM profiles WHERE player_uuid = ? ORDER BY name');
  const getProfile = db.prepare('SELECT * FROM profiles WHERE player_uuid = ? AND name = ?');
  const getProfileById = db.prepare('SELECT * FROM profiles WHERE id = ?');
  const countProfiles = db.prepare('SELECT COUNT(*) AS n FROM profiles WHERE player_uuid = ?');
  const setDefault = db.prepare('UPDATE players SET default_profile_id = ? WHERE uuid = ?');

  /** Every endpoint: the JWT subject must be the player whose data is touched. */
  async function requireOwner(request: FastifyRequest, reply: FastifyReply) {
    try {
      await request.jwtVerify();
    } catch {
      return reply.code(401).send({ error: 'missing or invalid token' });
    }
    const { uuid } = request.params as UuidParams;
    const sub = (request.user as { sub?: string }).sub;
    if (sub !== uuid) {
      return reply.code(403).send({ error: 'token does not match player' });
    }
  }

  app.get(
    '/players/:uuid/profiles',
    { preHandler: requireOwner, schema: { params: UUID_PARAM_SCHEMA } },
    async (request) => {
      const { uuid } = request.params as UuidParams;
      const player = getPlayer.get(uuid) as unknown as PlayerRow | undefined;
      const rows = getProfiles.all(uuid) as unknown as ProfileRow[];
      return rows.map((row) => summary(row, player?.default_profile_id ?? null));
    },
  );

  app.get(
    '/players/:uuid/profiles/default',
    { preHandler: requireOwner, schema: { params: UUID_PARAM_SCHEMA, querystring: PROFILE_GET_QUERY_SCHEMA } },
    async (request, reply) => {
      const { uuid } = request.params as UuidParams;
      const { gameVersion } = request.query as ProfileGetQuery;
      const player = getPlayer.get(uuid) as unknown as PlayerRow | undefined;
      if (!player?.default_profile_id) {
        return reply.code(404).send({ error: 'no default profile' });
      }
      const row = getProfileById.get(player.default_profile_id) as unknown as ProfileRow | undefined;
      if (!row) {
        return reply.code(404).send({ error: 'no default profile' });
      }
      return fullProfile(row, player.default_profile_id, gameVersion ?? null);
    },
  );

  app.get(
    '/players/:uuid/profiles/:name',
    { preHandler: requireOwner, schema: { params: UUID_PARAM_SCHEMA, querystring: PROFILE_GET_QUERY_SCHEMA } },
    async (request, reply) => {
      const { uuid, name } = request.params as UuidNameParams;
      const { gameVersion } = request.query as ProfileGetQuery;
      const player = getPlayer.get(uuid) as unknown as PlayerRow | undefined;
      const row = getProfile.get(uuid, name) as unknown as ProfileRow | undefined;
      if (!row) {
        return reply.code(404).send({ error: 'profile not found' });
      }
      return fullProfile(row, player?.default_profile_id ?? null, gameVersion ?? null);
    },
  );

  app.put(
    '/players/:uuid/profiles/:name',
    {
      preHandler: requireOwner,
      config: { rateLimit: { max: 30, timeWindow: '1 minute' } },
      schema: {
        params: UUID_PARAM_SCHEMA,
        body: {
          type: 'object',
          required: ['options'],
          properties: {
            options: { type: 'object', additionalProperties: { type: 'string' } },
            serversDat: { type: ['string', 'null'] },
            gameVersion: { type: ['string', 'null'], maxLength: 32 },
            excludedKeys: {
              type: ['array', 'null'],
              maxItems: 512,
              items: { type: 'string', maxLength: 128 },
            },
          },
        },
      },
    },
    async (request, reply) => {
      const { uuid, name } = request.params as UuidNameParams;
      if (!PROFILE_NAME_PATTERN.test(name)) {
        return reply.code(400).send({ error: 'invalid profile name' });
      }
      const body = request.body as {
        options: Record<string, string>;
        serversDat?: string | null;
        gameVersion?: string | null;
        excludedKeys?: string[] | null;
      };

      const now = Date.now();
      const existing = getProfile.get(uuid, name) as unknown as ProfileRow | undefined;
      if (!existing) {
        const count = (countProfiles.get(uuid) as unknown as { n: number }).n;
        if (count >= MAX_PROFILES_PER_PLAYER) {
          return reply.code(400).send({ error: 'profile limit reached' });
        }
      }

      // Merge per-key into the stored options: a client only pushes the keys its
      // game version knows about, so versions sharing a profile must not wipe
      // each other's settings with a full replace.
      const stored = existing
        ? normalizeStored(JSON.parse(existing.options_json) as Record<string, string>)
        : {};
      const incoming = normalizeIncoming(body.options, body.gameVersion ?? null, stored);
      const options = { ...stored, ...incoming };
      const optionsJson = JSON.stringify(options);
      if (Buffer.byteLength(optionsJson) > MAX_OPTIONS_JSON_BYTES) {
        return reply.code(413).send({ error: 'options payload too large' });
      }
      // No serversDat means "this instance isn't syncing servers", not "clear them".
      let serversDat: Buffer | Uint8Array | null = existing?.servers_dat ?? null;
      if (body.serversDat) {
        const decoded = Buffer.from(body.serversDat, 'base64');
        if (decoded.length > MAX_SERVERS_DAT_BYTES) {
          return reply.code(413).send({ error: 'servers.dat too large' });
        }
        serversDat = decoded;
      }

      db.prepare(
        `INSERT INTO players (uuid, created_at, last_seen_at) VALUES (?, ?, ?)
         ON CONFLICT(uuid) DO UPDATE SET last_seen_at = excluded.last_seen_at`,
      ).run(uuid, now, now);
      // excludedKeys is omitted on normal pushes; preserve the stored list then.
      const excludedKeys = body.excludedKeys
        ? JSON.stringify(body.excludedKeys)
        : (existing?.excluded_keys ?? null);
      db.prepare(
        `INSERT INTO profiles (player_uuid, name, options_json, servers_dat, game_version, updated_at, excluded_keys)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(player_uuid, name) DO UPDATE SET
           options_json = excluded.options_json,
           servers_dat = excluded.servers_dat,
           game_version = excluded.game_version,
           updated_at = excluded.updated_at,
           excluded_keys = excluded.excluded_keys`,
      ).run(uuid, name, optionsJson, serversDat, body.gameVersion ?? null, now, excludedKeys);

      // A profile created while no default exists becomes the default.
      const player = getPlayer.get(uuid) as unknown as PlayerRow;
      if (!player.default_profile_id) {
        const row = getProfile.get(uuid, name) as unknown as ProfileRow;
        setDefault.run(row.id, uuid);
      }
      return { updatedAt: now };
    },
  );

  app.put(
    '/players/:uuid/profiles/:name/exclusions',
    {
      preHandler: requireOwner,
      schema: { params: UUID_PARAM_SCHEMA, body: EXCLUSIONS_BODY_SCHEMA },
    },
    async (request, reply) => {
      const { uuid, name } = request.params as UuidNameParams;
      const { excludedKeys } = request.body as { excludedKeys: string[] };
      const row = getProfile.get(uuid, name) as unknown as ProfileRow | undefined;
      if (!row) {
        return reply.code(404).send({ error: 'profile not found' });
      }
      db.prepare('UPDATE profiles SET excluded_keys = ? WHERE id = ?').run(JSON.stringify(excludedKeys), row.id);
      return { ok: true };
    },
  );

  app.get(
    '/players/:uuid/exclusions',
    { preHandler: requireOwner, schema: { params: UUID_PARAM_SCHEMA } },
    async (request) => {
      const { uuid } = request.params as UuidParams;
      const player = getPlayer.get(uuid) as unknown as PlayerRow | undefined;
      return {
        excludedKeys: player?.excluded_keys ? (JSON.parse(player.excluded_keys) as string[]) : [],
      };
    },
  );

  app.put(
    '/players/:uuid/exclusions',
    {
      preHandler: requireOwner,
      schema: { params: UUID_PARAM_SCHEMA, body: EXCLUSIONS_BODY_SCHEMA },
    },
    async (request) => {
      const { uuid } = request.params as UuidParams;
      const { excludedKeys } = request.body as { excludedKeys: string[] };
      const now = Date.now();
      db.prepare(
        `INSERT INTO players (uuid, created_at, last_seen_at, excluded_keys) VALUES (?, ?, ?, ?)
         ON CONFLICT(uuid) DO UPDATE SET excluded_keys = excluded.excluded_keys`,
      ).run(uuid, now, now, JSON.stringify(excludedKeys));
      return { ok: true };
    },
  );

  app.post(
    '/players/:uuid/profiles/:name/rename',
    {
      preHandler: requireOwner,
      schema: {
        params: UUID_PARAM_SCHEMA,
        body: {
          type: 'object',
          required: ['newName'],
          properties: { newName: { type: 'string', maxLength: 32 } },
        },
      },
    },
    async (request, reply) => {
      const { uuid, name } = request.params as UuidNameParams;
      const { newName } = request.body as { newName: string };
      if (!PROFILE_NAME_PATTERN.test(newName)) {
        return reply.code(400).send({ error: 'invalid profile name' });
      }
      const row = getProfile.get(uuid, name) as unknown as ProfileRow | undefined;
      if (!row) {
        return reply.code(404).send({ error: 'profile not found' });
      }
      if (newName !== name && getProfile.get(uuid, newName)) {
        return reply.code(409).send({ error: 'a profile with that name already exists' });
      }
      // The default pointer references the row id, so it survives the rename.
      db.prepare('UPDATE profiles SET name = ? WHERE id = ?').run(newName, row.id);
      return { ok: true };
    },
  );

  app.post(
    '/players/:uuid/default',
    {
      preHandler: requireOwner,
      schema: {
        params: UUID_PARAM_SCHEMA,
        body: {
          type: 'object',
          required: ['name'],
          properties: { name: { type: 'string', maxLength: 32 } },
        },
      },
    },
    async (request, reply) => {
      const { uuid } = request.params as UuidParams;
      const { name } = request.body as { name: string };
      const row = getProfile.get(uuid, name) as unknown as ProfileRow | undefined;
      if (!row) {
        return reply.code(404).send({ error: 'profile not found' });
      }
      setDefault.run(row.id, uuid);
      return { ok: true };
    },
  );

  app.delete(
    '/players/:uuid/profiles/:name',
    { preHandler: requireOwner, schema: { params: UUID_PARAM_SCHEMA } },
    async (request, reply) => {
      const { uuid, name } = request.params as UuidNameParams;
      const row = getProfile.get(uuid, name) as unknown as ProfileRow | undefined;
      if (!row) {
        return reply.code(404).send({ error: 'profile not found' });
      }
      // Deleting the default is allowed; the ON DELETE SET NULL foreign key clears
      // the default pointer, and the client immediately recreates a fresh
      // default-settings profile, which auto-becomes the new default.
      db.prepare('DELETE FROM profiles WHERE id = ?').run(row.id);
      return { ok: true };
    },
  );
}
