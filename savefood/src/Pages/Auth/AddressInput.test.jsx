import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AddressInput from './AddressInput';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('AddressInput', () => {
  it('clears a selected address point when the street text is edited manually', () => {
    const onChange = vi.fn();
    render(
      <AddressInput
        value="Москва, Тверская, 1"
        lat={55.757}
        lon={37.615}
        city="Москва"
        onChange={onChange}
      />
    );

    fireEvent.change(screen.getByPlaceholderText('address.street_placeholder'), {
      target: { value: 'Москва, Арбат, 1' },
    });

    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      address: 'Москва, Арбат, 1',
      lat: null,
      lon: null,
      city: null,
    }));
  });
});
