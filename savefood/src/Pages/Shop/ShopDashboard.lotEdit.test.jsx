import { describe, expect, it } from 'vitest';
import { buildLotEditPayload } from './ShopDashboard';

const openedLot = {
  id: 7,
  description: 'Bread',
  quantity: '5',
  address: 'Store',
  category: 'Выпечка',
  comment: '',
  requires_cold: false,
  _expectedQuantity: 5,
  _expectedInitialQuantity: 5,
};

describe('shop lot edit payload', () => {
  it('omits inventory fields when only a descriptive field changed', () => {
    const payload = buildLotEditPayload({ ...openedLot, description: 'Fresh bread' });

    expect(payload).toMatchObject({ description: 'Fresh bread' });
    expect(payload).not.toHaveProperty('quantity');
    expect(payload).not.toHaveProperty('expected_quantity');
    expect(payload).not.toHaveProperty('expected_initial_quantity');
  });

  it('binds an explicit quantity edit to the editor snapshot', () => {
    const payload = buildLotEditPayload({ ...openedLot, quantity: '7' });

    expect(payload).toMatchObject({
      quantity: 7,
      expected_quantity: 5,
      expected_initial_quantity: 5,
    });
  });
});
