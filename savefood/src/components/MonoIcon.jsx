import React from 'react';
import './MonoIcon.css';

const ICONS = {
  award: <><circle cx="12" cy="9" r="5" /><path d="m8.5 13-1 7 4.5-2 4.5 2-1-7" /></>,
  bag: <><path d="M5 8h14l-1 12H6L5 8Z" /><path d="M9 8V6a3 3 0 0 1 6 0v2" /></>,
  bell: <><path d="M6 10a6 6 0 0 1 12 0v4l2 3H4l2-3v-4Z" /><path d="M10 20h4" /></>,
  bicycle: <><circle cx="6" cy="17" r="3" /><circle cx="18" cy="17" r="3" /><path d="m6 17 4-8 4 8m-7-4h7l4 4M9 7h3" /></>,
  blocked: <><circle cx="12" cy="12" r="9" /><path d="m5.6 5.6 12.8 12.8" /></>,
  box: <><path d="m4 7 8-4 8 4v10l-8 4-8-4V7Z" /><path d="m4 7 8 4 8-4M12 11v10" /></>,
  building: <><path d="M5 21V5h10v16M9 9h2m-2 4h2m-2 4h2M15 10h4v11H3" /></>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M7 3v4m10-4v4M3 10h18" /></>,
  camera: <><path d="M4 7h4l1.5-2h5L16 7h4v12H4V7Z" /><circle cx="12" cy="13" r="4" /></>,
  cart: <><path d="M3 4h2l2 11h10l3-7H6" /><circle cx="9" cy="19" r="1" /><circle cx="17" cy="19" r="1" /></>,
  chart: <><path d="M4 20V10m6 10V4m6 16v-7m4 7H2" /></>,
  chat: <path d="M4 5h16v11H9l-5 4V5Z" />,
  clipboard: <><rect x="5" y="4" width="14" height="17" rx="2" /><path d="M9 4V2h6v2m-6 6h6m-6 4h6" /></>,
  diamond: <><path d="m3 8 4-5h10l4 5-9 13L3 8Z" /><path d="M3 8h18M8 3l4 5 4-5" /></>,
  download: <><path d="M12 3v12m-5-5 5 5 5-5" /><path d="M4 19h16" /></>,
  folder: <path d="M3 6h7l2 2h9v11H3V6Z" />,
  gear: <><circle cx="12" cy="12" r="3" /><path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M19 5l-2 2M7 17l-2 2" /></>,
  home: <><path d="m3 11 9-8 9 8" /><path d="M5 10v11h14V10M9 21v-7h6v7" /></>,
  id: <><rect x="3" y="5" width="18" height="14" rx="2" /><circle cx="8" cy="11" r="2" /><path d="M6 16c.7-1.5 3.3-1.5 4 0m3-5h5m-5 4h4" /></>,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v6m0-10v.2" /></>,
  leaf: <path d="M20 4C11 4 5 8 5 16c5 1 12-1 15-12ZM5 20c2-6 6-9 11-12" />,
  mail: <><rect x="3" y="5" width="18" height="14" rx="2" /><path d="m4 7 8 6 8-6" /></>,
  map: <><path d="m3 6 6-3 6 3 6-3v15l-6 3-6-3-6 3V6Z" /><path d="M9 3v15m6-12v15" /></>,
  money: <><rect x="3" y="6" width="18" height="13" rx="2" /><circle cx="12" cy="12.5" r="3" /><path d="M6 9h.1M18 16h.1" /></>,
  paperclip: <path d="m8 12 6-6a3 3 0 0 1 4 4l-8 8a5 5 0 0 1-7-7l8-8" />,
  refresh: <><path d="M20 7V3l-2 2a8 8 0 1 0 2 8" /><path d="M20 3h-4" /></>,
  send: <path d="m3 11 18-8-8 18-2-8-8-2Zm8 2 5-5" />,
  shield: <path d="M12 3 20 6v6c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-3Z" />,
  snow: <><path d="M12 2v20M3.3 7l17.4 10M3.3 17 20.7 7" /><path d="m9 4 3 2 3-2M9 20l3-2 3 2M4 10l3-1V6m13 8-3 1v3M4 14l3 1v3m13-8-3-1V6" /></>,
  store: <><path d="M4 11v10h16V11M3 9l2-6h14l2 6" /><path d="M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0M9 21v-6h6v6" /></>,
  truck: <><path d="M3 6h11v11H3V6Zm11 5h4l3 3v3h-7" /><circle cx="7" cy="18" r="2" /><circle cx="18" cy="18" r="2" /></>,
  users: <><circle cx="9" cy="8" r="3" /><circle cx="17" cy="9" r="2" /><path d="M3 20c0-4 2-7 6-7s6 3 6 7m0-6c3 0 5 2 5 5" /></>,
  walk: <><circle cx="13" cy="4" r="2" /><path d="m10 21 2-7-3-3 2-4 4 3 3 1M12 14l4 6M9 11l-4 4" /></>,
  warning: <><path d="M12 3 22 20H2L12 3Z" /><path d="M12 9v5m0 3v.2" /></>,
  wait: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
};

const MonoIcon = ({ name, className = '', title }) => (
  <svg
    className={`mono-icon${className ? ` ${className}` : ''}`}
    viewBox="0 0 24 24"
    fill="none"
    aria-hidden={title ? undefined : 'true'}
    role={title ? 'img' : undefined}
  >
    {title && <title>{title}</title>}
    {ICONS[name] || ICONS.info}
  </svg>
);

export default MonoIcon;
