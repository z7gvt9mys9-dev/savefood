import { describe, expect, it } from 'vitest';
import { buildNavigatorUrls, haversineMeters } from './geo';

describe('haversineMeters', () => {
  it('returns zero for the same point', () => {
    expect(haversineMeters(43.238, 76.889, 43.238, 76.889)).toBe(0);
  });

  // The delivery button unlocks at ≤100 m, so accuracy at that scale is what
  // actually matters — a percent of error here is a wrongly blocked handover.
  it('is accurate at the 100 m confirmation radius', () => {
    // ~111.32 m per 0.001° of latitude at the equator, and latitude spacing is
    // constant everywhere.
    const d = haversineMeters(43.238, 76.889, 43.239, 76.889);
    expect(d).toBeGreaterThan(110);
    expect(d).toBeLessThan(113);
  });

  it('is symmetric', () => {
    const a = haversineMeters(43.2, 76.8, 43.3, 76.9);
    const b = haversineMeters(43.3, 76.9, 43.2, 76.8);
    expect(a).toBeCloseTo(b, 6);
  });

  it('matches a known long distance (Almaty → Astana ≈ 970 km)', () => {
    const km = haversineMeters(43.238, 76.889, 51.169, 71.449) / 1000;
    expect(km).toBeGreaterThan(950);
    expect(km).toBeLessThan(990);
  });
});

describe('buildNavigatorUrls', () => {
  const shop = { lat: 43.238, lon: 76.889 };
  const first = { lat: 43.24, lon: 76.9 };
  const second = { lat: 43.25, lon: 76.91 };

  it('routes the app link to the next stop only', () => {
    const urls = buildNavigatorUrls([shop, first, second]);
    expect(urls.app).toBe('yandexnavi://build_route_on_map?lat_to=43.238&lon_to=76.889');
  });

  it('puts every remaining stop into the web route', () => {
    const urls = buildNavigatorUrls([shop, first, second]);
    expect(urls.web).toBe(
      'https://yandex.ru/maps/?rtext=~43.238,76.889~43.24,76.9~43.25,76.91&rtt=auto',
    );
  });

  it('accepts a single stop, with or without an array', () => {
    expect(buildNavigatorUrls(shop).web).toBe(
      'https://yandex.ru/maps/?rtext=~43.238,76.889&rtt=auto',
    );
    expect(buildNavigatorUrls([shop]).app).toContain('lat_to=43.238');
  });

  it('drops stops without usable coordinates', () => {
    const urls = buildNavigatorUrls([{ lat: null, lon: 76.9 }, first, { lat: 43.3 }]);
    expect(urls.web).toBe('https://yandex.ru/maps/?rtext=~43.24,76.9&rtt=auto');
  });

  it('returns null when nothing is routable', () => {
    expect(buildNavigatorUrls([])).toBeNull();
    expect(buildNavigatorUrls([{ lat: null, lon: null }])).toBeNull();
    expect(buildNavigatorUrls(undefined)).toBeNull();
  });
});
