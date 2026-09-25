import { describe, expect, it } from 'vitest';
import { volunteerMapUrl } from './mapRequest';
describe('volunteer map request', () => {
  it('sends the volunteer city scope', () => {
    expect(volunteerMapUrl('/api', 'Москва')).toBe('/api/volunteers/map?city=%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0');
  });
});
