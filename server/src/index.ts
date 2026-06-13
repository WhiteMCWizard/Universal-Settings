import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { buildApp } from './app.js';

const isProduction = process.env.NODE_ENV === 'production';

if (process.env.AUTH_DISABLED === 'true' && isProduction) {
  console.error('Refusing to start: AUTH_DISABLED must never be set in production.');
  process.exit(1);
}
if (isProduction && !process.env.JWT_SECRET) {
  console.error('Refusing to start: JWT_SECRET must be set in production.');
  process.exit(1);
}

const dbPath = process.env.DB_PATH ?? './data/universalsettings.db';
mkdirSync(dirname(dbPath), { recursive: true });

const app = await buildApp({ dbPath, logger: true });
const port = Number(process.env.PORT ?? 8080);
await app.listen({ port, host: '0.0.0.0' });

// Close in-flight requests and the database cleanly on container stop.
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.once(signal, async () => {
    await app.close();
    process.exit(0);
  });
}
