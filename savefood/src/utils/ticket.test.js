import { describe, expect, it } from 'vitest';
import { hasDeliveryLocation, hasValidCoordinates, isTerminalTicketStatus } from './ticket';
describe('hasDeliveryLocation', () => {
  it('requires a non-empty address and a complete, in-range coordinate pair', () => {
    expect(hasDeliveryLocation({ address: 'Москва, Тверская 1', lat: 55.76, lon: 37.61 })).toBe(true);
    expect(hasDeliveryLocation({ address: '  ', lat: 55.76, lon: 37.61 })).toBe(false);
    expect(hasDeliveryLocation({ address: 'Москва, Тверская 1', lat: null, lon: 37.61 })).toBe(false);
    expect(hasDeliveryLocation({ address: 'Москва, Тверская 1', lat: 55.76, lon: null })).toBe(false);
    expect(hasDeliveryLocation({ address: 'Москва, Тверская 1', lat: 91, lon: 37.61 })).toBe(false);
  });
  it('accepts zero coordinates instead of treating them as missing', () => {
    expect(hasDeliveryLocation({ address: 'Гвинейский залив', lat: 0, lon: 0 })).toBe(true);
    expect(hasValidCoordinates(0, 0)).toBe(true);
  });
});
describe('isTerminalTicketStatus', () => {
  it.each(['fulfilled', 'cancelled', 'expired'])('recognises %s as terminal', status => {
    expect(isTerminalTicketStatus(status)).toBe(true);
  });
  it.each(['open', 'assigned', null, undefined])('keeps %s active', status => {
    expect(isTerminalTicketStatus(status)).toBe(false);
  });
});
