import React from 'react';
const EmptyState = ({ icon, title, description, action, onAction }) => (
  <div style={{
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '48px 24px',
    textAlign: 'center',
    color: '#666',
    gap: '12px',
  }}>
    {icon && (
      <div style={{ fontSize: '2.8rem', lineHeight: 1, marginBottom: '4px', opacity: 0.5 }}>
        {icon}
      </div>
    )}
    <h3 style={{ margin: 0, fontSize: '1rem', fontWeight: 700, color: '#888', letterSpacing: '0.5px' }}>
      {title}
    </h3>
    {description && (
      <p style={{ margin: 0, fontSize: '0.82rem', color: '#555', maxWidth: '280px', lineHeight: 1.6 }}>
        {description}
      </p>
    )}
    {action && onAction && (
      <button
        onClick={onAction}
        style={{
          marginTop: '8px',
          padding: '8px 20px',
          background: 'none',
          border: '1px solid #444',
          color: '#ccc',
          fontSize: '0.78rem',
          fontWeight: 600,
          letterSpacing: '1px',
          textTransform: 'uppercase',
          cursor: 'pointer',
          transition: 'border-color 0.2s, color 0.2s',
        }}
        onMouseOver={e => { e.currentTarget.style.borderColor = '#fff'; e.currentTarget.style.color = '#fff'; }}
        onMouseOut={e => { e.currentTarget.style.borderColor = '#444'; e.currentTarget.style.color = '#ccc'; }}
      >
        {action}
      </button>
    )}
  </div>
);
export default EmptyState;
