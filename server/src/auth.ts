import { randomBytes, randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Db } from './db.js';
import { hasJoined } from './mojang.js';

const CHALLENGE_TTL_MS = 60_000;
const TOKEN_TTL_MS = 24 * 60 * 60 * 1000;

interface Challenge {
  uuid: string;
  username: string;
  serverId: string;
  expiresAt: number;
}

const challenges = new Map<string, Challenge>();

function purgeExpired() {
  const now = Date.now();
  for (const [id, challenge] of challenges) {
    if (challenge.expiresAt < now) {
      challenges.delete(id);
    }
  }
}

export interface AuthOptions {
  authDisabled: boolean;
}

export function registerAuthRoutes(app: FastifyInstance, db: Db, opts: AuthOptions) {
  const authRateLimit = {
    rateLimit: { max: 10, timeWindow: '1 minute' },
  };

  app.post(
    '/auth/challenge',
    {
      config: authRateLimit,
      schema: {
        body: {
          type: 'object',
          required: ['uuid', 'username'],
          properties: {
            uuid: { type: 'string', pattern: '^[0-9a-f]{32}$' },
            username: { type: 'string', minLength: 1, maxLength: 16 },
          },
        },
      },
    },
    async (request) => {
      purgeExpired();
      const { uuid, username } = request.body as { uuid: string; username: string };
      const challengeId = randomUUID();
      const serverId = randomBytes(20).toString('hex');
      challenges.set(challengeId, {
        uuid,
        username,
        serverId,
        expiresAt: Date.now() + CHALLENGE_TTL_MS,
      });
      return { challengeId, serverId };
    },
  );

  app.post(
    '/auth/verify',
    {
      config: authRateLimit,
      schema: {
        body: {
          type: 'object',
          required: ['challengeId'],
          properties: { challengeId: { type: 'string', maxLength: 64 } },
        },
      },
    },
    async (request, reply) => {
      purgeExpired();
      const { challengeId } = request.body as { challengeId: string };
      const challenge = challenges.get(challengeId);
      challenges.delete(challengeId);
      if (!challenge) {
        return reply.code(401).send({ error: 'unknown or expired challenge' });
      }

      if (!opts.authDisabled) {
        const joinedUuid = await hasJoined(challenge.username, challenge.serverId);
        if (joinedUuid !== challenge.uuid) {
          return reply.code(401).send({ error: 'session verification failed' });
        }
      }

      const now = Date.now();
      db.prepare(
        `INSERT INTO players (uuid, name, created_at, last_seen_at) VALUES (?, ?, ?, ?)
         ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen_at = excluded.last_seen_at`,
      ).run(challenge.uuid, challenge.username, now, now);

      const token = app.jwt.sign({ sub: challenge.uuid }, { expiresIn: '24h' });
      return { token, expiresAt: now + TOKEN_TTL_MS };
    },
  );
}
