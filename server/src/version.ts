import { createRequire } from 'node:module';

// Read the version from package.json so it never drifts. createRequire resolves
// relative to this module, so '../package.json' works from both src/ (tests via
// tsx) and dist/ (compiled), each one level under the server root.
const require = createRequire(import.meta.url);
const pkg = require('../package.json') as { version: string };

export const SERVER_VERSION = pkg.version;

// Bump whenever the client starts *requiring* a new server capability:
//   1 — original 0.1.0 server (auth, profiles, exclusions)
//   2 — 0.1.1: account server-list endpoints (/players/:uuid/servers) + /version
export const PROTOCOL_VERSION = 2;
