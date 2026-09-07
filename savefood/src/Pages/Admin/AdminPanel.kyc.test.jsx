import { describe, expect, it } from 'vitest';
import { buildKycDecisionPayload } from './AdminPanel';

describe('admin KYC decision payload', () => {
  it('binds the decision to the generation shown in the queue', () => {
    expect(buildKycDecisionPayload({ id: 3, kyc_generation: 'generation-a' }, 'approved'))
      .toEqual({ status: 'approved', generation: 'generation-a' });
  });
});
