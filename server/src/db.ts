import { DatabaseSync } from 'node:sqlite';

export type Db = DatabaseSync;

export function openDb(path: string): Db {
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL;');
  db.exec('PRAGMA busy_timeout = 5000;');
  db.exec('PRAGMA foreign_keys = ON;');
  db.exec(`
    CREATE TABLE IF NOT EXISTS players (
      uuid TEXT PRIMARY KEY,
      name TEXT,
      default_profile_id INTEGER REFERENCES profiles(id) ON DELETE SET NULL,
      created_at INTEGER NOT NULL,
      last_seen_at INTEGER,
      excluded_keys TEXT,
      servers_dat BLOB,
      servers_scope TEXT
    );
    CREATE TABLE IF NOT EXISTS profiles (
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
  migrate(db);
  return db;
}

/**
 * Adds a column to an existing table only when it's missing. The schema above
 * uses CREATE TABLE IF NOT EXISTS, which never alters a table that already
 * exists, so production databases predating a column need this explicit step.
 */
function ensureColumn(db: Db, table: string, column: string, type: string): void {
  const cols = db.prepare(`PRAGMA table_info(${table})`).all() as Array<{ name: string }>;
  if (!cols.some((c) => c.name === column)) {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${type}`);
  }
}

/**
 * Idempotent, forward-only migrations. ensureColumn() handles columns added to
 * existing tables; PRAGMA user_version gates one-time data backfills so they run
 * exactly once and never on a re-deploy.
 */
function migrate(db: Db): void {
  ensureColumn(db, 'players', 'servers_dat', 'BLOB');
  ensureColumn(db, 'players', 'servers_scope', 'TEXT');

  const { user_version: version } = db.prepare('PRAGMA user_version').get() as { user_version: number };
  if (version < 1) {
    // servers.dat moved from per-profile-only to an account-level default. Seed
    // each player's account list from their default profile so nothing is lost;
    // per-profile blobs stay untouched for PROFILE-scope users.
    db.exec(`
      UPDATE players SET servers_dat = (
        SELECT servers_dat FROM profiles p WHERE p.id = players.default_profile_id)
      WHERE servers_dat IS NULL AND default_profile_id IS NOT NULL;
    `);
    db.exec('PRAGMA user_version = 1');
  }
}

export interface PlayerRow {
  uuid: string;
  name: string | null;
  default_profile_id: number | null;
  created_at: number;
  last_seen_at: number | null;
  excluded_keys: string | null;
  servers_dat: Uint8Array | null;
  servers_scope: string | null;
}

export interface ProfileRow {
  id: number;
  player_uuid: string;
  name: string;
  options_json: string;
  servers_dat: Uint8Array | null;
  game_version: string | null;
  updated_at: number;
  excluded_keys: string | null;
}
