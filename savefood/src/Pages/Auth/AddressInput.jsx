import React, { useState, useEffect } from 'react';

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

const AddressInput = ({ value, onChange, placeholder, label }) => {
  const [suggestions, setSuggestions] = useState([]);
  const [query, setQuery] = useState(value || '');
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [apartment, setApartment] = useState('');
  const [floorNum, setFloorNum] = useState('');
  const [entrance, setEntrance] = useState('');
  const [latLon, setLatLon] = useState({ lat: null, lon: null });

  useEffect(() => {
    const fetchSuggestions = async () => {
      if (query.length > 3) {
        try {
          const response = await fetch(
            `https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(query)}&addressdetails=1&limit=5`
          );
          const data = await response.json();
          setSuggestions(data.map(item => ({
            address: item.display_name,
            lat: parseFloat(item.lat),
            lon: parseFloat(item.lon),
          })));
          setShowSuggestions(true);
        } catch {
          // ignore
        }
      } else {
        setSuggestions([]);
      }
    };
    const timer = setTimeout(fetchSuggestions, 500);
    return () => clearTimeout(timer);
  }, [query]);

  const emit = (overrides = {}) => {
    const next = {
      address: query,
      lat: latLon.lat,
      lon: latLon.lon,
      apartment,
      floor_num: floorNum,
      entrance,
      ...overrides,
    };
    onChange(next);
  };

  const handleSelect = (s) => {
    setQuery(s.address);
    setLatLon({ lat: s.lat, lon: s.lon });
    setShowSuggestions(false);
    onChange({
      address: s.address,
      lat: s.lat,
      lon: s.lon,
      apartment,
      floor_num: floorNum,
      entrance,
    });
  };

  const handleApartment = (v) => { setApartment(v); emit({ apartment: v }); };
  const handleFloor = (v) => { setFloorNum(v); emit({ floor_num: v }); };
  const handleEntrance = (v) => { setEntrance(v); emit({ entrance: v }); };

  return (
    <div style={{ marginBottom: '18px' }}>
      {label && <label style={labelStyle}>{label}</label>}

      {/* Улица / дом с автодополнением */}
      <div style={{ position: 'relative', marginBottom: '10px' }}>
        <input
          type="text"
          value={query}
          onChange={(e) => { setQuery(e.target.value); emit({ address: e.target.value }); }}
          placeholder={placeholder || 'Улица, дом...'}
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
                {s.address}
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Квартира + Этаж + Подъезд */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '10px' }}>
        <div>
          <label style={labelStyle}>Квартира</label>
          <input
            type="text"
            value={apartment}
            onChange={(e) => handleApartment(e.target.value)}
            placeholder="№ кв."
            style={inputStyle}
          />
        </div>
        <div>
          <label style={labelStyle}>Этаж</label>
          <input
            type="number"
            value={floorNum}
            onChange={(e) => handleFloor(e.target.value)}
            placeholder="Этаж"
            min="1"
            max="100"
            style={inputStyle}
          />
        </div>
        <div>
          <label style={labelStyle}>
            Подъезд
            <span style={{ color: '#555', fontWeight: 'normal', marginLeft: 4 }}>(если &gt;1)</span>
          </label>
          <input
            type="text"
            value={entrance}
            onChange={(e) => handleEntrance(e.target.value)}
            placeholder="№ подъезда"
            style={inputStyle}
          />
        </div>
      </div>
    </div>
  );
};

export default AddressInput;
