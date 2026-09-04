import { describe, expect, it } from 'vitest';
import en from './en.json';
import ru from './ru.json';
const collectKeys = (obj, prefix = '') =>
  Object.entries(obj).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    return value && typeof value === 'object' && !Array.isArray(value)
      ? collectKeys(value, path)
      : [path];
  });
const RU = collectKeys(ru);
describe('translations', () => {
  it('russian is the reference and is non-empty', () => {
    expect(RU.length).toBeGreaterThan(100);
  });
  it.each([['en', en]])('%s has every russian key', (_name, bundle) => {
    const missing = RU.filter(k => !collectKeys(bundle).includes(k));
    expect(missing).toEqual([]);
  });
  it.each([['en', en]])('%s has no keys russian lacks', (_name, bundle) => {
    const extra = collectKeys(bundle).filter(k => !RU.includes(k));
    expect(extra).toEqual([]);
  });
  it.each([['ru', ru], ['en', en]])('%s has no blank values', (_name, bundle) => {
    const walk = (obj, prefix = '') =>
      Object.entries(obj).flatMap(([key, value]) => {
        const path = prefix ? `${prefix}.${key}` : key;
        if (value && typeof value === 'object' && !Array.isArray(value)) return walk(value, path);
        return typeof value === 'string' && value.trim() === '' ? [path] : [];
      });
    expect(walk(bundle)).toEqual([]);
  });
});
