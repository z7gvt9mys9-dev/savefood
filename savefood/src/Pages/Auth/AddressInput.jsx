import React, { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
const inputStyle = {
  width: '100%',
  padding: '12px',
  backgroundColor: '#000',
  border: '1px solid #333',
  color: '#fff',
  boxSizing: 'border-box',
  fontSize: '0.9rem',
};
const labelStyle = {
  display: 'block',
  marginBottom: '5px',
  fontWeight: 'bold',
  fontSize: '0.85rem',
  color: '#ccc',
};
const SUGGEST_KEY = import.meta.env.VITE_YANDEX_SUGGEST_API_KEY || '';
const GEOCODER_KEY = import.meta.env.VITE_YANDEX_MAPS_API_KEY || '';
const EMPTY_COORDS = { lat: null, lon: null, city: null };
const geocodeBy = async (param) => {
  const res = await fetch(
    `https://geocode-maps.yandex.ru/1.x/?apikey=${GEOCODER_KEY}&format=json&lang=ru_RU&results=1&${param}`
  );
  const data = await res.json();
  const obj = data?.response?.GeoObjectCollection?.featureMember?.[0]?.GeoObject;
  if (!obj) return null;
  const [lon, lat] = (obj.Point?.pos || '').split(' ').map(Number);
  const comps = obj.metaDataProperty?.GeocoderMetaData?.Address?.Components || [];
  const pick = (kind) => comps.find(c => c.kind === kind)?.name || null;
  return {
    lat: Number.isFinite(lat) ? lat : null,
    lon: Number.isFinite(lon) ? lon : null,
    city: pick('locality') || pick('area') || pick('province') || null,
  };
};
const geocode = async (item) => {
  if (!GEOCODER_KEY) return EMPTY_COORDS;
  try {
    if (item.uri) {
      const byUri = await geocodeBy(`uri=${encodeURIComponent(item.uri)}`);
      if (byUri?.lat != null) return byUri;
    }
    return (await geocodeBy(`geocode=${encodeURIComponent(item.address)}`)) || EMPTY_COORDS;
  } catch {
    return EMPTY_COORDS;
  }
};
const validCoordinate = (value) => (
  value === null || value === undefined || value === '' || !Number.isFinite(Number(value))
    ? null
    : Number(value)
);
const AddressInput = ({
  value,
  onChange,
  placeholder,
  label,
  lat: initialLat,
  lon: initialLon,
  city: initialCity,
  apartment: initialApartment,
  floorNum: initialFloorNum,
  entrance: initialEntrance,
  showUnitFields = true,
}) => {
  const { t } = useTranslation();
  const [suggestions, setSuggestions] = useState([]);
  const [query, setQuery] = useState(value || '');
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [apartment, setApartment] = useState(initialApartment || '');
  const [floorNum, setFloorNum] = useState(initialFloorNum || '');
  const [entrance, setEntrance] = useState(initialEntrance || '');
  const [latLon, setLatLon] = useState({
    lat: validCoordinate(initialLat),
    lon: validCoordinate(initialLon),
    city: initialCity || null,
  });
  const geocodeRequestRef = useRef(0);
  useEffect(() => {
    setQuery(value || '');
    setLatLon({
      lat: validCoordinate(initialLat),
      lon: validCoordinate(initialLon),
      city: initialCity || null,
    });
  }, [value, initialLat, initialLon, initialCity]);
  useEffect(() => setApartment(initialApartment || ''), [initialApartment]);
  useEffect(() => setFloorNum(initialFloorNum || ''), [initialFloorNum]);
  useEffect(() => setEntrance(initialEntrance || ''), [initialEntrance]);
  useEffect(() => {
    const fetchSuggestions = async () => {
      if (query.length > 3 && SUGGEST_KEY) {
        try {
          const response = await fetch(
            `https://suggest-maps.yandex.ru/v1/suggest?apikey=${SUGGEST_KEY}&text=${encodeURIComponent(query)}&lang=ru_RU&results=5&print_address=1&attrs=uri`
          );
          const data = await response.json();
          setSuggestions((data.results || []).map(r => ({
            address: r.address?.formatted_address
              || [r.title?.text, r.subtitle?.text].filter(Boolean).join(', '),
            title: r.title?.text || '',
            subtitle: r.subtitle?.text || '',
            uri: r.uri || null,
          })));
          setShowSuggestions(true);
        } catch {
        }
      } else {
        setSuggestions([]);
      }
    };
    const timer = setTimeout(fetchSuggestions, 300);
    return () => clearTimeout(timer);
  }, [query]);
  const emit = (overrides = {}) => {
    const next = {
      address: query,
      lat: latLon.lat,
      lon: latLon.lon,
      city: latLon.city,
      apartment,
      floor_num: floorNum,
      entrance,
      ...overrides,
    };
    onChange(next);
  };
  const handleSelect = async (s) => {
    const requestId = ++geocodeRequestRef.current;
    setQuery(s.address);
    setShowSuggestions(false);
    const coords = await geocode(s);
    if (requestId !== geocodeRequestRef.current) return;
    setLatLon(coords);
    onChange({
      address: s.address,
      lat: coords.lat,
      lon: coords.lon,
      city: coords.city,
      apartment,
      floor_num: floorNum,
      entrance,
    });
  };
  const handleApartment = (v) => { setApartment(v); emit({ apartment: v }); };
  const handleFloor = (v) => { setFloorNum(v); emit({ floor_num: v }); };
  const handleEntrance = (v) => { setEntrance(v); emit({ entrance: v }); };
  const handleAddressInput = (event) => {
    const address = event.target.value;
    geocodeRequestRef.current += 1;
    setQuery(address);
    setLatLon(EMPTY_COORDS);
    setShowSuggestions(false);
    emit({ address, ...EMPTY_COORDS });
  };
  return (
    <div style={{ marginBottom: '18px' }}>
      {label && <label style={labelStyle}>{label}</label>}
      <div style={{ position: 'relative', marginBottom: '10px' }}>
        <input
          type="text"
          value={query}
          onChange={handleAddressInput}
          placeholder={placeholder || t('address.street_placeholder')}
          className="form-input"
          autoComplete="off"
          style={inputStyle}
        />
        {showSuggestions && suggestions.length > 0 && (
          <ul style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            right: 0,
            backgroundColor: '#111',
            border: '1px solid #333',
            borderTop: 'none',
            zIndex: 1000,
            listStyle: 'none',
            margin: 0,
            padding: 0,
            boxShadow: '0 4px 6px rgba(0,0,0,0.5)',
          }}>
            {suggestions.map((s, idx) => (
              <li
                key={idx}
                onClick={() => handleSelect(s)}
                style={{
                  padding: '10px 12px',
                  cursor: 'pointer',
                  borderBottom: '1px solid #222',
                  color: '#ccc',
                  fontSize: '0.82rem',
                }}
                onMouseOver={(e) => { e.currentTarget.style.background = '#222'; e.currentTarget.style.color = '#fff'; }}
                onMouseOut={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = '#ccc'; }}
              >
                <div>{s.title || s.address}</div>
                {s.subtitle && <div style={{ color: '#777', fontSize: '0.72rem', marginTop: 2 }}>{s.subtitle}</div>}
              </li>
            ))}
          </ul>
        )}
      </div>
      {showUnitFields && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '10px' }}>
          <div>
            <label style={labelStyle}>{t('address.apartment')}</label>
            <input
              type="text"
              value={apartment}
              onChange={(e) => handleApartment(e.target.value)}
              placeholder={t('address.apartment_placeholder')}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>{t('address.floor')}</label>
            <input
              type="number"
              value={floorNum}
              onChange={(e) => handleFloor(e.target.value)}
              placeholder={t('address.floor')}
              min="1"
              max="100"
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>
              {t('address.entrance')}
              <span style={{ color: '#555', fontWeight: 'normal', marginLeft: 4 }}>{t('address.entrance_hint')}</span>
            </label>
            <input
              type="text"
              value={entrance}
              onChange={(e) => handleEntrance(e.target.value)}
              placeholder={t('address.entrance_placeholder')}
              style={inputStyle}
            />
          </div>
        </div>
      )}
    </div>
  );
};
export default AddressInput;
