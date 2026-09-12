import { describe, expect, it } from 'vitest';
import { buildKycDecisionPayload, deliveryPhotoLocationLabel } from './AdminPanel';

describe('admin KYC decision payload', () => {
  it('binds the decision to the generation shown in the queue', () => {
    expect(buildKycDecisionPayload({ id: 3, kyc_generation: 'generation-a' }, 'approved'))
      .toEqual({ status: 'approved', generation: 'generation-a' });
  });
});

describe('admin delivery-photo verification location', () => {
  it('shows only the city, without the lot category or separator', () => {
    expect(deliveryPhotoLocationLabel({ category: 'Выпечка', city: 'Москва' }))
      .toBe('Москва');
  });
});
