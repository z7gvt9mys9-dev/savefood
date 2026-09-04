import { describe, expect, it } from 'vitest';
import { volunteerMapUrl } from './mapRequest';
describe('volunteer map request', () => {
  it('sends the volunteer city scope', () => {
    expect(volunteerMapUrl('/api', 'Алматы')).toBe('/api/volunteers/map?city=%D0%90%D0%BB%D0%BC%D0%B0%D1%82%D1%8B');
  });
});
