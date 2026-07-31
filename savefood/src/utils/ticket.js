const TERMINAL_STATUSES = new Set(['fulfilled', 'cancelled', 'expired']);

/** Do not use truthiness for coordinates: 0 is a valid value. */
export const hasValidCoordinates = (lat, lon) => {
  if (lat === null || lat === undefined || lat === '' || lon === null || lon === undefined || lon === '') {
    return false;
  }
  const latitude = Number(lat);
  const longitude = Number(lon);
  return Number.isFinite(latitude)
    && Number.isFinite(longitude)
    && latitude >= -90
    && latitude <= 90
    && longitude >= -180
    && longitude <= 180;
};

/** Delivery tickets need both a human-readable address and an exact point. */
export const hasDeliveryLocation = ({ address, lat, lon } = {}) => (
  typeof address === 'string' && address.trim().length > 0 && hasValidCoordinates(lat, lon)
);

export const isTerminalTicketStatus = (status) => TERMINAL_STATUSES.has(status);
