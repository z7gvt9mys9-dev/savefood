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
