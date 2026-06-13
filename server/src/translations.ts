/**
 * Cross-version option translation. Minecraft occasionally renames an options key
 * or changes its value format. Profiles are stored in the newest ("canonical")
 * format and translated at the edges: pushed keys to canonical form on PUT,
 * stored keys to the requesting client's ?gameVersion dialect on GET. Keeping
 * this knowledge server-side means a future key change only needs a new RULES
 * entry — mod builds for older versions keep working without an update.
 */

export interface TranslationRule {
  /** First game version that uses the canonical form. */
  changedIn: string;
  canonicalKey: string;
  /** Key name clients older than changedIn use (equals canonicalKey for pure value-format changes). */
  legacyKey: string;
  toCanonical: (legacyValue: string) => string;
  toLegacy: (canonicalValue: string) => string;
  /**
   * For same-key format changes only: recognizes values still stored in the
   * legacy format so they can be migrated in place.
   */
  isLegacyValue?: (value: string) => boolean;
}

export const RULES: TranslationRule[] = [
  {
    // 1.16: fancyGraphics true/false became graphicsMode 0/1/2 (fast/fancy/fabulous).
    changedIn: '1.16',
    canonicalKey: 'graphicsMode',
    legacyKey: 'fancyGraphics',
    toCanonical: (v) => (v === 'true' ? '1' : '0'),
    toLegacy: (v) => (v === '0' ? 'false' : 'true'),
  },
  {
    // 1.18: ao 0/1/2 became a boolean.
    changedIn: '1.18',
    canonicalKey: 'ao',
    legacyKey: 'ao',
    toCanonical: (v) => (v === '0' ? 'false' : 'true'),
    toLegacy: (v) => (v === 'false' ? '0' : '2'),
    isLegacyValue: (v) => v === '0' || v === '1' || v === '2',
  },
];

/** Keys that must never be stored: the DataFixer version marker is meaningful only locally. */
const FORBIDDEN_KEYS = ['version'];

function parseVersion(version: string | null | undefined): number[] | null {
  if (!version || !/^\d+(\.\d+)*$/.test(version)) {
    return null;
  }
  return version.split('.').map(Number);
}

/** True when gameVersion is a release older than changedIn. Snapshots/unknown formats count as current. */
function isPreChange(gameVersion: string | null | undefined, changedIn: string): boolean {
  const v = parseVersion(gameVersion);
  if (!v) {
    return false;
  }
  const c = parseVersion(changedIn)!;
  for (let i = 0; i < Math.max(v.length, c.length); i++) {
    const a = v[i] ?? 0;
    const b = c[i] ?? 0;
    if (a !== b) {
      return a < b;
    }
  }
  return false;
}

/** Migrates a stored options map fully into canonical form. */
export function normalizeStored(options: Record<string, string>): Record<string, string> {
  const out = { ...options };
  for (const key of FORBIDDEN_KEYS) {
    delete out[key];
  }
  for (const rule of RULES) {
    if (rule.legacyKey !== rule.canonicalKey) {
      if (rule.legacyKey in out) {
        if (!(rule.canonicalKey in out)) {
          out[rule.canonicalKey] = rule.toCanonical(out[rule.legacyKey]);
        }
        delete out[rule.legacyKey];
      }
    } else if (rule.canonicalKey in out && rule.isLegacyValue?.(out[rule.canonicalKey])) {
      out[rule.canonicalKey] = rule.toCanonical(out[rule.canonicalKey]);
    }
  }
  return out;
}

/**
 * Converts a pushed options map into canonical form. Translations can be lossy
 * (graphicsMode fabulous and fancy both present as fancyGraphics:true), so a
 * legacy value that still round-trips to the stored canonical value keeps the
 * stored one — only a setting the old client actually changed replaces it.
 */
export function normalizeIncoming(
  incoming: Record<string, string>,
  gameVersion: string | null | undefined,
  existing: Record<string, string>,
): Record<string, string> {
  const out = { ...incoming };
  for (const key of FORBIDDEN_KEYS) {
    delete out[key];
  }
  for (const rule of RULES) {
    if (!isPreChange(gameVersion, rule.changedIn) || !(rule.legacyKey in out)) {
      continue;
    }
    const legacyValue = out[rule.legacyKey];
    delete out[rule.legacyKey];
    const stored = existing[rule.canonicalKey];
    out[rule.canonicalKey] =
      stored !== undefined && rule.toLegacy(stored) === legacyValue
        ? stored
        : rule.toCanonical(legacyValue);
  }
  return out;
}

/** Converts a stored canonical options map into the dialect of the given game version. */
export function presentFor(
  options: Record<string, string>,
  gameVersion: string | null | undefined,
): Record<string, string> {
  const out = { ...options };
  for (const rule of RULES) {
    if (!isPreChange(gameVersion, rule.changedIn) || !(rule.canonicalKey in out)) {
      continue;
    }
    const canonicalValue = out[rule.canonicalKey];
    delete out[rule.canonicalKey];
    out[rule.legacyKey] = rule.toLegacy(canonicalValue);
  }
  return out;
}
