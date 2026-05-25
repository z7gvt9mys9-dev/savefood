import React, { useState, useEffect } from 'react';

const AddressInput = ({ value, onChange, placeholder, label }) => {
  const [suggestions, setSuggestions] = useState([]);
  const [query, setQuery] = useState(value || '');
  const [showSuggestions, setShowSuggestions] = useState(false);

  useEffect(() => {
    const fetchSuggestions = async () => {
      if (query.length > 3) {
        try {
          // Using OpenStreetMap Nominatim API (Free and no key required for low volume)
          const response = await fetch(`https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(query)}&addressdetails=1&limit=5`);
          const data = await response.json();
          
          const formatted = data.map(item => ({
            address: item.display_name,
            lat: parseFloat(item.lat),
            lon: parseFloat(item.lon)
          }));
          
          setSuggestions(formatted);
          setShowSuggestions(true);
        } catch (err) {
          console.error("Geo API Error:", err);
        }
      } else {
        setSuggestions([]);
      }
    };

    const timer = setTimeout(fetchSuggestions, 500); // Debounce
    return () => clearTimeout(timer);
  }, [query]);

  const handleSelect = (s) => {
    setQuery(s.address);
    setShowSuggestions(false);
    onChange({ address: s.address, lat: s.lat, lon: s.lon });
  };

  return (
    <div className="address-input-container" style={{ position: 'relative', marginBottom: '15px' }}>
      {label && <label style={{ display: 'block', marginBottom: '5px', fontWeight: 'bold' }}>{label}</label>}
      <input
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={placeholder || "Начните вводить адрес..."}
        className="form-input"
        autoComplete="off"
        style={{ 
          width: '100%', 
          padding: '12px', 
          backgroundColor: '#000', 
          border: '1px solid #333', 
          color: '#fff',
          boxSizing: 'border-box'
        }}
      />
      {showSuggestions && suggestions.length > 0 && (
        <ul className="suggestions-list" style={{
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
          boxShadow: '0 4px 6px rgba(0,0,0,0.5)'
        }}>
          {suggestions.map((s, idx) => (
            <li
              key={idx}
              onClick={() => handleSelect(s)}
              style={{ 
                padding: '12px', 
                cursor: 'pointer', 
                borderBottom: '1px solid #222',
                color: '#ccc',
                fontSize: '0.85rem'
              }}
              onMouseOver={(e) => {
                e.target.style.backgroundColor = '#222';
                e.target.style.color = '#fff';
              }}
              onMouseOut={(e) => {
                e.target.style.backgroundColor = 'transparent';
                e.target.style.color = '#ccc';
              }}
            >
              {s.address}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

export default AddressInput;
