import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { PROTOCOL_VERSION, SERVER_VERSION } from '../src/version.js';

let app: FastifyInstance;

beforeEach(async () => {
  app = await buildApp({ dbPath: ':memory:', jwtSecret: 'test-secret', authDisabled: true, rateLimit: false });
  await app.ready();
});

afterEach(async () => {
  await app.close();
});

describe('version', () => {
  it('reports the server version and protocol without auth', async () => {
    const res = await app.inject({ method: 'GET', url: '/version' });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ version: SERVER_VERSION, protocol: PROTOCOL_VERSION });
  });

  it('exposes the current protocol (2) and the package version', async () => {
    expect(PROTOCOL_VERSION).toBe(2);
    expect(SERVER_VERSION).toMatch(/^\d+\.\d+\.\d+/);
  });
});
