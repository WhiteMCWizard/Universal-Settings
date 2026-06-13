import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';

const UUID = 'a'.repeat(32);
const OTHER_UUID = 'b'.repeat(32);

let app: FastifyInstance;

beforeEach(async () => {
  app = await buildApp({ dbPath: ':memory:', jwtSecret: 'test-secret', authDisabled: true, rateLimit: false });
  await app.ready();
});

afterEach(async () => {
  await app.close();
});

async function getToken(uuid: string): Promise<string> {
  const challenge = await app.inject({
    method: 'POST',
    url: '/auth/challenge',
    payload: { uuid, username: 'TestPlayer' },
  });
  expect(challenge.statusCode).toBe(200);
  const { challengeId } = challenge.json();
  const verify = await app.inject({
    method: 'POST',
    url: '/auth/verify',
    payload: { challengeId },
  });
  expect(verify.statusCode).toBe(200);
  return verify.json().token;
}

async function putProfile(token: string, name: string, options: Record<string, string> = { fov: '90' }) {
  return app.inject({
    method: 'PUT',
    url: `/players/${UUID}/profiles/${encodeURIComponent(name)}`,
    headers: { authorization: `Bearer ${token}` },
    payload: { options, gameVersion: '26.1.2' },
  });
}

async function getAuthed(token: string, url: string) {
  return app.inject({ method: 'GET', url, headers: { authorization: `Bearer ${token}` } });
}

describe('auth', () => {
  it('rejects writes without a token', async () => {
    const res = await app.inject({
      method: 'PUT',
      url: `/players/${UUID}/profiles/default`,
      payload: { options: { fov: '90' } },
    });
    expect(res.statusCode).toBe(401);
  });

  it('rejects reads without a token', async () => {
    for (const url of [
      `/players/${UUID}/profiles`,
      `/players/${UUID}/profiles/default`,
      `/players/${UUID}/profiles/laptop`,
      `/players/${UUID}/exclusions`,
    ]) {
      const res = await app.inject({ method: 'GET', url });
      expect(res.statusCode).toBe(401);
    }
  });

  it('rejects reads when the token belongs to another player', async () => {
    const token = await getToken(OTHER_UUID);
    const res = await getAuthed(token, `/players/${UUID}/profiles`);
    expect(res.statusCode).toBe(403);
  });

  it('rejects writes when the token belongs to another player', async () => {
    const token = await getToken(OTHER_UUID);
    const res = await putProfile(token, 'default');
    expect(res.statusCode).toBe(403);
  });

  it('rejects unknown challenges', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/auth/verify',
      payload: { challengeId: 'nope' },
    });
    expect(res.statusCode).toBe(401);
  });
});

describe('profiles', () => {
  it('returns 404 when there is no default profile', async () => {
    const token = await getToken(UUID);
    const res = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(res.statusCode).toBe(404);
  });

  it('creates a profile and makes the first one the default', async () => {
    const token = await getToken(UUID);
    const put = await putProfile(token, 'default', { fov: '110', 'key_key.jump': 'key.keyboard.space' });
    expect(put.statusCode).toBe(200);
    expect(put.json().updatedAt).toBeGreaterThan(0);

    const def = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(def.statusCode).toBe(200);
    expect(def.json().options.fov).toBe('110');
    expect(def.json().isDefault).toBe(true);
  });

  it('reads a profile with the owner token', async () => {
    const token = await getToken(UUID);
    await putProfile(token, 'laptop');
    const res = await getAuthed(token, `/players/${UUID}/profiles/laptop`);
    expect(res.statusCode).toBe(200);
    expect(res.json().name).toBe('laptop');
  });

  it('upserts on repeated PUT instead of duplicating', async () => {
    const token = await getToken(UUID);
    await putProfile(token, 'default', { fov: '90' });
    await putProfile(token, 'default', { fov: '120' });
    const list = await getAuthed(token, `/players/${UUID}/profiles`);
    expect(list.json()).toHaveLength(1);
    const def = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(def.json().options.fov).toBe('120');
  });

  it('merges pushed options per-key, preserving keys the client did not send', async () => {
    const token = await getToken(UUID);
    // A newer game version pushes a key an older version doesn't know about.
    await putProfile(token, 'default', { fov: '90', newVersionOnlyKey: 'true' });
    // The older version pushes its full (smaller) key set.
    await putProfile(token, 'default', { fov: '110' });
    const def = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(def.json().options.fov).toBe('110');
    expect(def.json().options.newVersionOnlyKey).toBe('true');
  });

  it('translates option keys between game version dialects', async () => {
    const token = await getToken(UUID);
    // An old-version client pushes its dialect...
    await app.inject({
      method: 'PUT',
      url: `/players/${UUID}/profiles/default`,
      headers: { authorization: `Bearer ${token}` },
      payload: { options: { fancyGraphics: 'true', ao: '2' }, gameVersion: '1.15.2' },
    });
    // ...a new client reads canonical form...
    const modern = await getAuthed(token, `/players/${UUID}/profiles/default?gameVersion=26.1.2`);
    expect(modern.json().options).toEqual({ graphicsMode: '1', ao: 'true' });
    // ...and the old client reads its own dialect back.
    const legacy = await getAuthed(token, `/players/${UUID}/profiles/default?gameVersion=1.15.2`);
    expect(legacy.json().options).toEqual({ fancyGraphics: 'true', ao: '2' });
  });

  it('does not let a lossy translation round-trip degrade the stored value', async () => {
    const token = await getToken(UUID);
    // A modern client stores fabulous graphics.
    await putProfile(token, 'default', { graphicsMode: '2' });
    // An old client sees fancyGraphics:true and echoes it back unchanged.
    await app.inject({
      method: 'PUT',
      url: `/players/${UUID}/profiles/default`,
      headers: { authorization: `Bearer ${token}` },
      payload: { options: { fancyGraphics: 'true' }, gameVersion: '1.15.2' },
    });
    const res = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(res.json().options.graphicsMode).toBe('2');
  });

  it('preserves servers.dat when a push omits it', async () => {
    const token = await getToken(UUID);
    const bytes = Buffer.from([0x0a, 0x00, 0x00, 0x09]).toString('base64');
    await app.inject({
      method: 'PUT',
      url: `/players/${UUID}/profiles/default`,
      headers: { authorization: `Bearer ${token}` },
      payload: { options: { fov: '90' }, serversDat: bytes },
    });
    // An instance with server sync disabled pushes without serversDat.
    await putProfile(token, 'default', { fov: '100' });
    const res = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(res.json().serversDat).toBe(bytes);
  });

  it('keeps the first profile as default when a second is added, then allows switching', async () => {
    const token = await getToken(UUID);
    await putProfile(token, 'default');
    await putProfile(token, 'laptop');
    let def = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(def.json().name).toBe('default');

    const set = await app.inject({
      method: 'POST',
      url: `/players/${UUID}/default`,
      headers: { authorization: `Bearer ${token}` },
      payload: { name: 'laptop' },
    });
    expect(set.statusCode).toBe(200);
    def = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(def.json().name).toBe('laptop');
  });

  it('round-trips servers.dat as base64', async () => {
    const token = await getToken(UUID);
    const bytes = Buffer.from([0x0a, 0x00, 0x00, 0x09]).toString('base64');
    const put = await app.inject({
      method: 'PUT',
      url: `/players/${UUID}/profiles/default`,
      headers: { authorization: `Bearer ${token}` },
      payload: { options: { fov: '90' }, serversDat: bytes },
    });
    expect(put.statusCode).toBe(200);
    const res = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(res.json().serversDat).toBe(bytes);
  });

  it('accepts names with spaces via percent-encoded paths', async () => {
    const token = await getToken(UUID);
    const res = await putProfile(token, 'default 2');
    expect(res.statusCode).toBe(200);
    const get = await getAuthed(token, `/players/${UUID}/profiles/default%202`);
    expect(get.statusCode).toBe(200);
    expect(get.json().name).toBe('default 2');
  });

  it('rejects invalid profile names', async () => {
    const token = await getToken(UUID);
    const res = await putProfile(token, '../evil');
    expect(res.statusCode).toBe(400);
  });

  it('allows deleting the default profile and clears the default flag', async () => {
    const token = await getToken(UUID);
    await putProfile(token, 'default');
    await putProfile(token, 'laptop');
    const res = await app.inject({
      method: 'DELETE',
      url: `/players/${UUID}/profiles/default`,
      headers: { authorization: `Bearer ${token}` },
    });
    expect(res.statusCode).toBe(200);
    const def = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(def.statusCode).toBe(404);

    // The next created/updated profile becomes the new default.
    await putProfile(token, 'fresh');
    const newDef = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(newDef.json().name).toBe('fresh');
  });

  it('deletes the last profile and clears the default', async () => {
    const token = await getToken(UUID);
    await putProfile(token, 'default');
    const del = await app.inject({
      method: 'DELETE',
      url: `/players/${UUID}/profiles/default`,
      headers: { authorization: `Bearer ${token}` },
    });
    expect(del.statusCode).toBe(200);
    const def = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(def.statusCode).toBe(404);
  });

  it('renames a profile and keeps its default status', async () => {
    const token = await getToken(UUID);
    await putProfile(token, 'default');
    const res = await app.inject({
      method: 'POST',
      url: `/players/${UUID}/profiles/default/rename`,
      headers: { authorization: `Bearer ${token}` },
      payload: { newName: 'main' },
    });
    expect(res.statusCode).toBe(200);
    const def = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(def.json().name).toBe('main');
    expect(def.json().isDefault).toBe(true);
  });

  it('refuses to rename onto an existing profile', async () => {
    const token = await getToken(UUID);
    await putProfile(token, 'one');
    await putProfile(token, 'two');
    const res = await app.inject({
      method: 'POST',
      url: `/players/${UUID}/profiles/one/rename`,
      headers: { authorization: `Bearer ${token}` },
      payload: { newName: 'two' },
    });
    expect(res.statusCode).toBe(409);
  });

  it('stores per-profile exclusions and preserves them across pushes', async () => {
    const token = await getToken(UUID);
    await putProfile(token, 'default');
    const set = await app.inject({
      method: 'PUT',
      url: `/players/${UUID}/profiles/default/exclusions`,
      headers: { authorization: `Bearer ${token}` },
      payload: { excludedKeys: ['guiScale', 'fov'] },
    });
    expect(set.statusCode).toBe(200);
    // A normal push without excludedKeys must not clobber them.
    await putProfile(token, 'default', { fov: '120' });
    const get = await getAuthed(token, `/players/${UUID}/profiles/default`);
    expect(get.json().excludedKeys).toEqual(['guiScale', 'fov']);
  });

  it('stores per-player global exclusions, readable by the owner', async () => {
    const token = await getToken(UUID);
    const set = await app.inject({
      method: 'PUT',
      url: `/players/${UUID}/exclusions`,
      headers: { authorization: `Bearer ${token}` },
      payload: { excludedKeys: ['soundCategory_music'] },
    });
    expect(set.statusCode).toBe(200);
    const get = await getAuthed(token, `/players/${UUID}/exclusions`);
    expect(get.statusCode).toBe(200);
    expect(get.json().excludedKeys).toEqual(['soundCategory_music']);
  });

  it('rejects exclusion writes without a token', async () => {
    const res = await app.inject({
      method: 'PUT',
      url: `/players/${UUID}/exclusions`,
      payload: { excludedKeys: [] },
    });
    expect(res.statusCode).toBe(401);
  });

  it('enforces the profile count quota', async () => {
    const token = await getToken(UUID);
    for (let i = 0; i < 50; i++) {
      const res = await putProfile(token, `profile-${i}`);
      expect(res.statusCode).toBe(200);
    }
    const res = await putProfile(token, 'one-too-many');
    expect(res.statusCode).toBe(400);
  });
});
