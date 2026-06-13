import { describe, expect, it } from 'vitest';
import { normalizeIncoming, normalizeStored, presentFor } from '../src/translations.js';

describe('normalizeIncoming', () => {
  it('translates a renamed key from an old client into canonical form', () => {
    const out = normalizeIncoming({ fancyGraphics: 'true', fov: '90' }, '1.15.2', {});
    expect(out).toEqual({ graphicsMode: '1', fov: '90' });
  });

  it('translates a same-key format change from an old client', () => {
    const out = normalizeIncoming({ ao: '2' }, '1.16.5', {});
    expect(out).toEqual({ ao: 'true' });
  });

  it('keeps the stored canonical value when a lossy translation round-trips', () => {
    // graphicsMode 2 (fabulous) shows as fancyGraphics:true to a 1.15 client;
    // echoing it back must not downgrade fabulous to fancy.
    const out = normalizeIncoming({ fancyGraphics: 'true' }, '1.15.2', { graphicsMode: '2' });
    expect(out.graphicsMode).toBe('2');
  });

  it('replaces the stored canonical value when the old client changed the setting', () => {
    const out = normalizeIncoming({ fancyGraphics: 'false' }, '1.15.2', { graphicsMode: '2' });
    expect(out.graphicsMode).toBe('0');
  });

  it('does not translate for clients at or above the change version', () => {
    const out = normalizeIncoming({ graphicsMode: '2', ao: 'true' }, '26.1.2', {});
    expect(out).toEqual({ graphicsMode: '2', ao: 'true' });
  });

  it('treats unparseable game versions as current', () => {
    const out = normalizeIncoming({ ao: 'true' }, '24w14a-snapshot', {});
    expect(out).toEqual({ ao: 'true' });
  });

  it('never stores the DataFixer version marker', () => {
    const out = normalizeIncoming({ version: '4189', fov: '90' }, '26.1.2', {});
    expect(out).toEqual({ fov: '90' });
  });
});

describe('normalizeStored', () => {
  it('migrates legacy debris into canonical form', () => {
    const out = normalizeStored({ fancyGraphics: 'true', ao: '1', version: '2230' });
    expect(out).toEqual({ graphicsMode: '1', ao: 'true' });
  });

  it('prefers an existing canonical key over legacy debris', () => {
    const out = normalizeStored({ fancyGraphics: 'false', graphicsMode: '2' });
    expect(out).toEqual({ graphicsMode: '2' });
  });
});

describe('presentFor', () => {
  it('serves old clients their dialect', () => {
    const out = presentFor({ graphicsMode: '2', ao: 'true', fov: '90' }, '1.15.2');
    expect(out).toEqual({ fancyGraphics: 'true', ao: '2', fov: '90' });
  });

  it('applies only the rules older than the requested version', () => {
    const out = presentFor({ graphicsMode: '2', ao: 'true' }, '1.16.5');
    expect(out).toEqual({ graphicsMode: '2', ao: '2' });
  });

  it('serves canonical form when no version is given', () => {
    const out = presentFor({ graphicsMode: '2', ao: 'true' }, null);
    expect(out).toEqual({ graphicsMode: '2', ao: 'true' });
  });
});
