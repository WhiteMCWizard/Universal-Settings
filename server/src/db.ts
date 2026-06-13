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
      excluded_keys TEXT
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
  return db;
}

export interface PlayerRow {
  uuid: string;
  name: string | null;
  default_profile_id: number | null;
  created_at: number;
  last_seen_at: number | null;
  excluded_keys: string | null;
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
