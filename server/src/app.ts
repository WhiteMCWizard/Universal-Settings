import Fastify from 'fastify';
import fastifyJwt from '@fastify/jwt';
import fastifyRateLimit from '@fastify/rate-limit';
import { openDb } from './db.js';
import { registerAuthRoutes } from './auth.js';
import { registerProfileRoutes } from './routes/profiles.js';

export interface AppOptions {
  dbPath?: string;
  jwtSecret?: string;
  authDisabled?: boolean;
  logger?: boolean;
  rateLimit?: boolean;
}

export async function buildApp(opts: AppOptions = {}) {
  const app = Fastify({
    logger: opts.logger ?? false,
    // Options (~256 KB) + base64 servers.dat (~683 KB) with headroom.
    bodyLimit: 1_500_000,
    trustProxy: true,
  });

  const db = openDb(opts.dbPath ?? process.env.DB_PATH ?? './data/universalsettings.db');

  await app.register(fastifyJwt, {
    secret: opts.jwtSecret ?? process.env.JWT_SECRET ?? 'dev-secret-do-not-use-in-production',
  });
  if (opts.rateLimit ?? true) {
    await app.register(fastifyRateLimit, {
      max: 120,
      timeWindow: '1 minute',
    });
  }

  registerAuthRoutes(app, db, {
    authDisabled: opts.authDisabled ?? process.env.AUTH_DISABLED === 'true',
  });
  registerProfileRoutes(app, db);

  app.get('/health', async () => ({ ok: true }));

  app.addHook('onClose', async () => {
    db.close();
  });

  return app;
}
