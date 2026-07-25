// Geo helpers shared by the volunteer flow. Extracted from VolunteerDashboard so
// they can be tested without mounting the component — this is the arithmetic the
// delivery confirmation button depends on.

/** Great-circle distance in metres. Mirrors Geo.haversineMeters on the server. */
export const haversineMeters = (lat1, lon1, lat2, lon2) => {
  const R = 6371000;
  const p1 = (lat1 * Math.PI) / 180;
  const p2 = (lat2 * Math.PI) / 180;
  const dp = ((lat2 - lat1) * Math.PI) / 180;
  const dl = ((lon2 - lon1) * Math.PI) / 180;
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
};

/**
 * Build the navigation links for the remaining itinerary.
 *
 * Web Yandex Maps takes the whole route (`rtext=` with `~`-separated waypoints)
 * and gives a traffic-aware ETA for the entire trip. The Navigator deep link
 * accepts a single destination only, so it gets the next stop and the driver
 * reopens it at each point.
 *
 * @param {{lat: number, lon: number}[]} stops remaining stops, nearest first
 * @returns {{app: string, web: string}|null} null when there is nothing to route to
 */
export const buildNavigatorUrls = (stops) => {
  const list = (Array.isArray(stops) ? stops : [stops]).filter(
    p => Number.isFinite(p?.lat) && Number.isFinite(p?.lon),
  );
  if (list.length === 0) return null;
  const waypoints = list.map(p => `${p.lat},${p.lon}`).join('~');
  const { lat, lon } = list[0];
  return {
    app: `yandexnavi://build_route_on_map?lat_to=${lat}&lon_to=${lon}`,
    web: `https://yandex.ru/maps/?rtext=~${waypoints}&rtt=auto`,
  };
};
