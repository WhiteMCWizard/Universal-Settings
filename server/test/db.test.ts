import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { openDb } from '../src/db.js';

const UUID = 'a'.repeat(32);

let dir: string;
let dbPath: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'us-db-'));
  dbPath = join(dir, 'test.db');
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

/** Recreates the pre-0.1.1 schema: players had no servers_dat/servers_scope. */
function seedLegacyDb(serversDat: Buffer): void {
  const db = new DatabaseSync(dbPath);
  db.exec(`
    CREATE TABLE players (
      uuid TEXT PRIMARY KEY,
      name TEXT,
      default_profile_id INTEGER REFERENCES profiles(id) ON DELETE SET NULL,
      created_at INTEGER NOT NULL,
      last_seen_at INTEGER,
      excluded_keys TEXT
    );
    CREATE TABLE profiles (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      player_uuid TEXT NOT NULL REFERENCES players(uuid),
      name TEXT NOT NULL,
      options_json TEXT NOT NULL,
      servers_dat BLOB,
      game_version TEXT,
      updated_at INTEGER NOT NULL,
      excluded_keys TEXT,
      UNIQUE(player_uuid, name)
    );
  `);
  db.prepare('INSERT INTO players (uuid, created_at) VALUES (?, ?)').run(UUID, Date.now());
  const info = db
    .prepare('INSERT INTO profiles (player_uuid, name, options_json, servers_dat, updated_at) VALUES (?, ?, ?, ?, ?)')
    .run(UUID, 'default', '{}', serversDat, Date.now());
  db.prepare('UPDATE players SET default_profile_id = ? WHERE uuid = ?').run(info.lastInsertRowid, UUID);
  db.close();
}

describe('db migration', () => {
  it('backfills the account server list from the default profile without touching profile blobs', () => {
    const bytes = Buffer.from([0x0a, 0x00, 0x00, 0x09]);
    seedLegacyDb(bytes);

    const db = openDb(dbPath);
    const player = db.prepare('SELECT servers_dat, servers_scope FROM players WHERE uuid = ?').get(UUID) as {
      servers_dat: Uint8Array | null;
      servers_scope: string | null;
    };
    expect(player.servers_dat && Buffer.from(player.servers_dat).equals(bytes)).toBe(true);
    // Scope stays null, meaning the default ("account").
    expect(player.servers_scope).toBeNull();

    // The per-profile blob is preserved, not moved.
    const profile = db.prepare('SELECT servers_dat FROM profiles WHERE player_uuid = ? AND name = ?').get(
      UUID,
      'default',
    ) as { servers_dat: Uint8Array | null };
    expect(profile.servers_dat && Buffer.from(profile.servers_dat).equals(bytes)).toBe(true);

    const { user_version } = db.prepare('PRAGMA user_version').get() as { user_version: number };
    expect(user_version).toBe(1);
    db.close();
  });

  it('is idempotent across repeated opens and does not re-run the backfill', () => {
    const bytes = Buffer.from([0x01, 0x02]);
    seedLegacyDb(bytes);

    openDb(dbPath).close();
    // Clear the account list; a second open must NOT re-seed it (backfill is once-only).
    const mid = new DatabaseSync(dbPath);
    mid.prepare('UPDATE players SET servers_dat = NULL WHERE uuid = ?').run(UUID);
    mid.close();

    const db = openDb(dbPath);
    const player = db.prepare('SELECT servers_dat FROM players WHERE uuid = ?').get(UUID) as {
      servers_dat: Uint8Array | null;
    };
    expect(player.servers_dat).toBeNull();
    db.close();
  });

  it('creates a fresh database with the new columns and version stamp', () => {
    const db = openDb(dbPath);
    const cols = (db.prepare('PRAGMA table_info(players)').all() as Array<{ name: string }>).map((c) => c.name);
    expect(cols).toContain('servers_dat');
    expect(cols).toContain('servers_scope');
    const { user_version } = db.prepare('PRAGMA user_version').get() as { user_version: number };
    expect(user_version).toBe(1);
    db.close();
  });
});
