import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import QRCode from 'react-qr-code';
import { YMaps, Map, Placemark } from '@pbe/react-yandex-maps';
import AddressInput from '../Auth/AddressInput';
import EmptyState from '../../components/EmptyState';
import AccountLinks from '../../components/AccountLinks';
import PushToggle from '../../components/PushToggle';
import OnboardingChecklist from '../../components/OnboardingChecklist';
import TicketChat from '../../components/TicketChat';
import MonoIcon from '../../components/MonoIcon';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import { hasDeliveryLocation, hasValidCoordinates, isTerminalTicketStatus } from '../../utils/ticket';
import './Needy.css';

const YMAPS_KEY = import.meta.env.VITE_YANDEX_MAPS_API_KEY || '';

// Yandex balloonContent* renders HTML; escape any interpolated user data.
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (ch) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[ch]));

const PAGE = 20;

const CAT_KEYS = {
  'Выпечка': 'bakery',
  'Овощи/Фрукты': 'vegetables',
  'Готовая еда': 'prepared',
  'Молочные продукты': 'dairy',
};

const CATEGORIES = ['Выпечка', 'Овощи/Фрукты', 'Готовая еда', 'Молочные продукты'];

const NeedyDashboard = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const needyId = user?.relatedId;

  const [activeTab, setActiveTab] = useState('map');
  const [activeOrder, setActiveOrder] = useState(null);
  const [lots, setLots] = useState([]);
  const [lotsOffset, setLotsOffset] = useState(0);
  const [lotsHasMore, setLotsHasMore] = useState(true);
  const [notifications, setNotifications] = useState([]);
  const [history, setHistory] = useState([]);
  const [historyOffset, setHistoryOffset] = useState(0);
  const [historyHasMore, setHistoryHasMore] = useState(true);
  const [profile, setProfile] = useState({ address: '', family_size: 1, preferences: '', urgency: 'normal', available_time: '', apartment: '', floor_num: '', entrance: '', city: '', lat: null, lon: null, geo_push_enabled: true });
  const [addressNeedsGeocoding, setAddressNeedsGeocoding] = useState(false);
  const [filterCategory, setFilterCategory] = useState('');
  const [filterSearch, setFilterSearch] = useState('');
  const [ratings, setRatings] = useState({});
  const [thankNotes, setThankNotes] = useState({});
  const [sentNotes, setSentNotes] = useState({});
  const [volunteerLocation, setVolunteerLocation] = useState(null);
  const locationPollRef = useRef(null);
  const ticketPollRef = useRef(null);

  const loadLots = useCallback(async (offset = 0, append = false, category = filterCategory, search = filterSearch) => {
    try {
      const params = new URLSearchParams({ limit: PAGE, offset });
      if (category) params.append('category', category);
      if (search) params.append('search', search);
      const res = await fetch(`${API_URL}/lots?${params}`);
      const data = await res.json();
      const arr = Array.isArray(data) ? data : [];
      setLots(prev => append ? [...prev, ...arr] : arr);
      setLotsHasMore(arr.length === PAGE);
      setLotsOffset(offset + arr.length);
    } catch {}
  }, [filterCategory, filterSearch]);

  const loadHistory = async (offset = 0, append = false) => {
    if (!needyId) return;
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/history?limit=${PAGE}&offset=${offset}`, {
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      const data = await res.json();
      const arr = Array.isArray(data) ? data : [];
      setHistory(prev => append ? [...prev, ...arr] : arr);
      setHistoryHasMore(arr.length === PAGE);
      setHistoryOffset(offset + arr.length);
    } catch {}
  };

  useEffect(() => {
    loadLots(0);

    if (!needyId) return;

    fetch(`${API_URL}/needy/${needyId}/profile`, {
      headers: { Authorization: `Bearer ${user?.token}` },
    })
      .then(res => res.ok ? res.json() : null)
      .then(data => {
        if (!data) return;
        setProfile(prev => ({ ...prev, ...data }));
        setAddressNeedsGeocoding(false);
      })
      .catch(() => {});

    fetch(`${API_URL}/needy/${needyId}/notifications`, {
      headers: { Authorization: `Bearer ${user?.token}` },
    })
      .then(res => res.json())
      .then(data => setNotifications(Array.isArray(data) ? data : []))
      .catch(() => {});

    // Restore the active ticket after a page reload — otherwise the QR code is
    // gone (delivery can't be confirmed) and the ticket can't be cancelled,
    // while the weekly "one active ticket" rule blocks creating a new one.
    fetch(`${API_URL}/needy/${needyId}/tickets`, {
      headers: { Authorization: `Bearer ${user?.token}` },
    })
      .then(res => res.ok ? res.json() : null)
      .then(list => {
        if (!Array.isArray(list)) return;
        const active = list.find(x => x.status === 'open' || x.status === 'assigned');
        if (!active) return;
        setActiveOrder(prev => prev || {
          ticketId: active.id,
          qrCode: active.qr_code,
          selfPickup: !!active.self_pickup,
          description: active.items,
          address: active.address,
          shopName: t('needy.shop_name_default'),
          status: 'picking',
          assigned_volunteer_id: active.assigned_volunteer_id,
          ticketStatus: active.status,
        });
      })
      .catch(() => {});

    loadHistory(0);
  }, [needyId]);

  // WebSocket: live notification stream
  useEffect(() => {
    if (!needyId || !user?.token) return;
    const apiBase = import.meta.env.VITE_API_URL ?? '';
    const wsUrl = apiBase
      ? apiBase.replace(/^https?/, m => m === 'https' ? 'wss' : 'ws') + `/ws/needy/${needyId}`
      : `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws/needy/${needyId}`;
    let ws;
    let reconnectTimer;
    // Track the highest notification id we've seen so a reconnect can replay
    // anything that arrived while the socket was down.
    let lastSeenId = null;
    const connect = () => {
      ws = new WebSocket(wsUrl);
      ws.onopen = () => {
        // Token is sent in the first message instead of the query string so it
        // never lands in nginx access logs or browser history.
        const handshake = { type: 'auth', token: user.token };
        if (lastSeenId != null) handshake.since_id = lastSeenId;
        ws.send(JSON.stringify(handshake));
      };
      ws.onmessage = (e) => {
        try {
          const data = JSON.parse(e.data);
          if (data.type === 'ready') return;
          if (typeof data.id === 'number') lastSeenId = Math.max(lastSeenId ?? 0, data.id);
          setNotifications(prev => {
            // dedupe against the initial REST fetch and replayed-on-reconnect rows
            if (data.id != null && prev.some(x => x.id === data.id)) return prev;
            return [{
              id: data.id ?? Date.now(), type: data.type, payload: data.payload, read: 0, created_at: new Date().toISOString(),
            }, ...prev];
          });
        } catch {}
      };
      ws.onclose = () => { reconnectTimer = setTimeout(connect, 5000); };
      ws.onerror = () => ws.close();
    };
    connect();
    return () => { clearTimeout(reconnectTimer); ws?.close(); };
  }, [needyId, user?.token]);

  useEffect(() => {
    if (locationPollRef.current) clearInterval(locationPollRef.current);
    const assignedVolunteerId = activeOrder?.assigned_volunteer_id;
    const ticketFulfilled = activeOrder?.ticketStatus === 'fulfilled';
    if (!assignedVolunteerId || ticketFulfilled) { setVolunteerLocation(null); return; }
    const poll = () => {
      fetch(`${API_URL}/volunteers/${assignedVolunteerId}/location`, { headers: { Authorization: `Bearer ${user?.token}` } })
        .then(r => r.ok ? r.json() : null)
        .then(data => { if (data && hasValidCoordinates(data.lat, data.lon)) setVolunteerLocation(data); })
        .catch(() => {});
    };
    poll();
    locationPollRef.current = setInterval(poll, 15000);
    return () => clearInterval(locationPollRef.current);
  }, [activeOrder?.assigned_volunteer_id, activeOrder?.ticketStatus]);

  // Poll the active ticket so the recipient learns when a volunteer is assigned
  // (assigned_volunteer_id is set server-side when the volunteer takes the route).
  // This also covers self-pickup: the shop changes that ticket to fulfilled, so
  // leaving it out would keep an obsolete QR and cancellation button on screen.
  useEffect(() => {
    if (ticketPollRef.current) clearInterval(ticketPollRef.current);
    const ticketId = activeOrder?.ticketId;
    if (!ticketId) return;
    const poll = () => {
      fetch(`${API_URL}/needy/${needyId}/tickets`, { headers: { Authorization: `Bearer ${user?.token}` } })
        .then(r => r.ok ? r.json() : null)
        .then(list => {
          if (!Array.isArray(list)) return;
          const ticket = list.find(x => String(x.id) === String(ticketId));
          if (!ticket) return;
          if (isTerminalTicketStatus(ticket.status)) {
            setVolunteerLocation(null);
            setActiveOrder(prev => String(prev?.ticketId) === String(ticketId) ? null : prev);
            setActiveTab(prev => prev === 'order' ? 'map' : prev);
            loadHistory(0);
            loadLots(0);
            return;
          }
          setActiveOrder(prev => {
            if (!prev || String(prev.ticketId) !== String(ticketId)) return prev;
            if (prev.assigned_volunteer_id === ticket.assigned_volunteer_id
              && prev.ticketStatus === ticket.status
              && prev.qrCode === ticket.qr_code) return prev;
            return {
              ...prev,
              assigned_volunteer_id: ticket.assigned_volunteer_id,
              ticketStatus: ticket.status,
              qrCode: ticket.qr_code || prev.qrCode,
            };
          });
        })
        .catch(() => {});
    };
    poll();
    ticketPollRef.current = setInterval(poll, 15000);
    return () => clearInterval(ticketPollRef.current);
  }, [activeOrder?.ticketId, needyId, user?.token]);

  const handleBook = async (lot, selfPickup = false) => {
    if (!needyId) { alert(t('common.auth_required')); return; }
    if (!selfPickup && !hasDeliveryLocation(profile)) {
      alert(t('needy.delivery_location_required'));
      setActiveTab('profile');
      return;
    }
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/ticket`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify({
          items: lot.description,
          address: selfPickup ? (lot.address || '') : profile.address.trim(),
          lot_id: lot.id,
          available_time: profile.available_time || '',
          lat: selfPickup ? null : (profile.lat ?? null),
          lon: selfPickup ? null : (profile.lon ?? null),
          apartment: selfPickup ? null : (profile.apartment || null),
          floor_num: selfPickup ? null : (profile.floor_num || null),
          entrance: selfPickup ? null : (profile.entrance || null),
          self_pickup: selfPickup,
        }),
      });
      if (!res.ok) {
        const err = await res.json();
        alert(err.detail || t('needy.error_book'));
        return;
      }
      const createdTicket = await res.json();

      // Ticket creation used to return only an id, while the only valid QR
      // includes a server-generated secret. Read the owner-scoped ticket row
      // after creation (and use a direct response QR when available) so this
      // screen never manufactures an invalid `SF-<id>` substitute.
      let ticket = createdTicket;
      try {
        const ticketsRes = await fetch(`${API_URL}/needy/${needyId}/tickets`, {
          headers: { Authorization: `Bearer ${user?.token}` },
        });
        if (ticketsRes.ok) {
          const tickets = await ticketsRes.json();
          const storedTicket = Array.isArray(tickets)
            ? tickets.find(item => String(item.id) === String(createdTicket.id))
            : null;
          if (storedTicket) ticket = { ...createdTicket, ...storedTicket };
        }
      } catch {
        // The POST has succeeded. Keep the order state and rely on a later poll
        // to obtain the QR rather than displaying a fake fallback code.
      }

      setActiveOrder({
        ...lot,
        ticketId: createdTicket.id,
        qrCode: ticket.qr_code || null,
        selfPickup,
        shopName: lot.shop_name || t('needy.shop_name_default'),
        status: selfPickup ? 'self_pickup' : 'picking',
        eta: selfPickup ? null : t('needy.awaiting_volunteer'),
        ticketStatus: ticket.status || 'open',
        assigned_volunteer_id: ticket.assigned_volunteer_id ?? null,
      });
      loadLots(0);
      setActiveTab('order');
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const handleCancelTicket = async () => {
    if (!activeOrder?.ticketId || !needyId) return;
    if (!window.confirm(t('needy.confirm_cancel'))) return;
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/ticket/${activeOrder.ticketId}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) {
        const err = await res.json();
        alert(err.detail || t('needy.error_cancel'));
        return;
      }
      setActiveOrder(null);
      setActiveTab('map');
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const renderTicketQr = () => {
    const qrCode = activeOrder?.qrCode;
    if (!qrCode) {
      return <p className="qr-code-text" role="status">{t('needy.qr_loading')}</p>;
    }
    return (
      <div className="ticket-qr-code">
        <QRCode value={qrCode} size={208} level="Q" bgColor="#ffffff" fgColor="#000000" />
        <span className="qr-code-text">{qrCode}</span>
      </div>
    );
  };

  useEffect(() => {
    setLotsOffset(0);
    loadLots(0, false, filterCategory, filterSearch);
  }, [filterCategory, filterSearch]);

  const handleRateDelivery = async (ticketId, rating, comment) => {
    if (!needyId) return false;
    const stars = rating ?? ratings[ticketId];
    if (!stars) return false;
    try {
      const params = new URLSearchParams({ rating: String(stars) });
      if (comment != null) params.set('comment', comment);
      const res = await fetch(
        `${API_URL}/needy/${needyId}/ticket/${ticketId}/rate?${params.toString()}`,
        { method: 'POST', headers: { Authorization: `Bearer ${user?.token}` } }
      );
      if (res.ok) { setRatings(prev => ({ ...prev, [ticketId]: stars })); return true; }
      alert(t('needy.error_rate'));
    } catch { alert(t('common.connection_error')); }
    return false;
  };

  const handleSaveProfile = async (e) => {
    e.preventDefault();
    if (!needyId) { alert(t('common.auth_required')); return; }
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/profile`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify({
          address: profile.address,
          family_size: Number(profile.family_size),
          preferences: profile.preferences,
          urgency: profile.urgency,
          available_time: profile.available_time,
          apartment: profile.apartment || null,
          floor_num: profile.floor_num || null,
          entrance: profile.entrance || null,
          city: profile.city || null,
          lat: profile.lat ?? null,
          lon: profile.lon ?? null,
          // The server uses this explicit flag to clear the old point when the
          // street/house text was manually edited. Sending null alone is not
          // enough for PATCH semantics because omitted fields normally mean
          // "leave unchanged".
          clear_coordinates: addressNeedsGeocoding,
        }),
      });
      if (!res.ok) { alert(t('needy.error_save_profile')); return; }
      alert(t('needy.profile_saved'));
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const toggleGeoPush = async (enabled) => {
    if (!needyId) return;
    setProfile(p => ({ ...p, geo_push_enabled: enabled }));
    try {
      await fetch(`${API_URL}/needy/${needyId}/geo_push`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify({ enabled }),
      });
    } catch {
      setProfile(p => ({ ...p, geo_push_enabled: !enabled }));
    }
  };

  const exportData = async () => {
    if (!needyId) return;
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/export`, {
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert(t('common.connection_error')); return; }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `savefood_data_${needyId}.json`;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const handleLogout = () => {
    logout();
    navigate('/');
  };

  const deleteAccount = async () => {
    if (!needyId) return;
    if (!window.confirm(t('needy.delete_confirm'))) return;
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/account`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert(t('common.connection_error')); return; }
      alert(t('needy.deleted'));
      logout();
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const renderProfile = () => (
    <div className="tab-content">
      <form className="admin-form" onSubmit={handleSaveProfile}>
        <h2>{t('needy.profile_title')}</h2>
        <AddressInput
          label={t('needy.home_address_label')}
          value={profile.address}
          lat={profile.lat}
          lon={profile.lon}
          city={profile.city}
          apartment={profile.apartment}
          floorNum={profile.floor_num}
          entrance={profile.entrance}
          onChange={(addr) => {
            const hasCoordinates = addr.lat !== null && addr.lat !== undefined && addr.lat !== ''
              && addr.lon !== null && addr.lon !== undefined && addr.lon !== ''
              && Number.isFinite(Number(addr.lat)) && Number.isFinite(Number(addr.lon));
            setAddressNeedsGeocoding(!hasCoordinates);
            setProfile(prev => ({
              ...prev,
              address: addr.address,
              lat: addr.lat,
              lon: addr.lon,
              city: addr.city,
              apartment: addr.apartment,
              floor_num: addr.floor_num,
              entrance: addr.entrance,
            }));
          }}
        />
        <div className="form-row">
          <div className="form-group">
            <label>{t('needy.family_label')}</label>
            <input type="number" value={profile.family_size} onChange={(e) => setProfile({ ...profile, family_size: e.target.value })} />
          </div>
          <div className="form-group">
            <label>{t('needy.urgency')}</label>
            <select value={profile.urgency} onChange={(e) => setProfile({ ...profile, urgency: e.target.value })}>
              <option value="normal">{t('needy.urgency_normal')}</option>
              <option value="high">{t('needy.urgency_high')}</option>
              <option value="critical">{t('needy.urgency_critical')}</option>
            </select>
          </div>
        </div>
        <div className="form-group">
          <label>{t('needy.available_time')}</label>
          <input type="text" placeholder={t('needy.time_hint')} value={profile.available_time} onChange={(e) => setProfile({ ...profile, available_time: e.target.value })} />
        </div>
        <div className="form-group">
          <label>{t('needy.dietary_label')}</label>
          <textarea placeholder={t('needy.dietary_placeholder')} value={profile.preferences} onChange={(e) => setProfile({ ...profile, preferences: e.target.value })} />
        </div>
        <div className="form-group">
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={profile.geo_push_enabled !== false}
              onChange={(e) => toggleGeoPush(e.target.checked)}
              style={{ width: 'auto' }}
            />
            <MonoIcon name="bell" /> {t('needy.geo_push')}
          </label>
        </div>
        <button type="submit" className="btn btn-primary">{t('needy.save_profile')}</button>
      </form>

      <AccountLinks dashboardPath="/needy" />
      <PushToggle />

      <div className="admin-form" style={{ marginTop: 16 }}>
        <h3>{t('needy.privacy_title')}</h3>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-secondary" onClick={exportData}><MonoIcon name="download" /> {t('needy.export_data')}</button>
          <button type="button" className="btn btn-danger" onClick={deleteAccount}>{t('needy.delete_account')}</button>
        </div>
      </div>

      <button type="button" className="profile-logout-btn" onClick={handleLogout}>
        {t('profile.logout')}
      </button>
    </div>
  );

  const lotsWithCoords = useMemo(() => lots.filter(l => l.shop_lat && l.shop_lon), [lots]);
  const mapCenter = lotsWithCoords.length > 0
    ? [lotsWithCoords[0].shop_lat, lotsWithCoords[0].shop_lon]
    : [55.7522, 37.6156];

  const lotsByShop = useMemo(() => {
    const groups = {};
    lots.forEach(lot => {
      const sid = lot.shop_id;
      if (!groups[sid]) groups[sid] = { shopId: sid, shopName: lot.shop_name || t('needy.shop_name_default'), lots: [] };
      groups[sid].lots.push(lot);
    });
    return Object.values(groups);
  }, [lots, t]);

  const renderMap = () => (
    <>
      <div className="tab-content">
        <OnboardingChecklist
          storageKey="needy"
          items={[
            { id: 'profile', label: t('onboarding.needy_fill_profile'), done: !!profile.address },
            { id: 'ticket', label: t('onboarding.needy_first_ticket'), done: !!activeOrder || history.length > 0 },
          ]}
        />
        <div className="lot-filters">
          <select value={filterCategory} onChange={e => setFilterCategory(e.target.value)}>
            <option value="">{t('needy.filter_all_categories')}</option>
            {CATEGORIES.map(cat => (
              <option key={cat} value={cat}>{t(`categories.${CAT_KEYS[cat]}`, { defaultValue: cat })}</option>
            ))}
          </select>
          <input
            type="text"
            placeholder={t('needy.filter_search')}
            value={filterSearch}
            onChange={e => setFilterSearch(e.target.value)}
          />
        </div>
      </div>

      <div className="map-container">
        <YMaps query={{ apikey: YMAPS_KEY, lang: 'ru_RU' }}>
          <Map
            state={{ center: mapCenter, zoom: 12 }}
            width="100%"
            height="100%"
            options={{ suppressMapOpenBlock: true }}
          >
            {lotsWithCoords.map(lot => (
              <Placemark
                key={lot.id}
                geometry={[lot.shop_lat, lot.shop_lon]}
                properties={{
                  balloonContentHeader: escapeHtml(lot.shop_name || lot.description),
                  balloonContentBody: `${escapeHtml(lot.description)} · ${escapeHtml(lot.quantity)} ${escapeHtml(t('needy.qty_unit'))}`,
                  balloonContentFooter: lot.time_slot ? `${escapeHtml(t('needy.pickup_time_prefix'))}${escapeHtml(lot.time_slot)}` : '',
                  hintContent: escapeHtml(lot.shop_name || lot.description),
                }}
                options={{ preset: 'islands#greenFoodIcon', iconColor: '#2ecc71' }}
              />
            ))}
          </Map>
        </YMaps>
        {lotsWithCoords.length === 0 && (
          <div className="map-no-coords">{t('needy.no_coords')}</div>
        )}
      </div>
      <div className="tab-content">
        {lots.length === 0 && <EmptyState icon={<MonoIcon name="cart" />} title={t('empty.lots_title')} description={t('empty.lots_city_desc')} />}
        {lotsByShop.map(group => (
          <div key={group.shopId} className="shop-group">
            <div className="shop-group-header">
              <span className="shop-group-icon"><MonoIcon name="store" /></span>
              <h4>{group.shopName}</h4>
            </div>
            <div className="lot-grid">
              {group.lots.map(lot => (
                <div key={lot.id} className="lot-card-compact">
                  {Array.isArray(lot.photos) && lot.photos.length > 1 ? (
                    <div className="lot-photo-strip">
                      {lot.photos.map((p, i) => (
                        <img
                          key={i}
                          src={`${API_URL}${p}`}
                          alt={`${lot.description} ${i + 1}`}
                          onError={(e) => { e.target.style.display = 'none'; }}
                        />
                      ))}
                    </div>
                  ) : lot.photo && (
                    <img
                      src={`${API_URL}${lot.photo}`}
                      alt={lot.description}
                      className="lot-photo"
                      onError={(e) => { e.target.style.display = 'none'; }}
                    />
                  )}
                  {lot.category && <span className="category-badge">{t(`categories.${CAT_KEYS[lot.category]}`, { defaultValue: lot.category })}</span>}
                  {lot.shop_kind === 'private' && (
                    <span className="category-badge" style={{ background: '#FF980022', color: '#FFB74D', borderColor: '#FF980044', marginLeft: 4 }}>
                      <MonoIcon name="home" /> {t('donor.badge')}
                    </span>
                  )}
                  {lot.requires_cold && (
                    <span className="category-badge" style={{ background: '#4fc3f722', color: '#4fc3f7', borderColor: '#4fc3f744', marginLeft: 4 }}>
                      <MonoIcon name="snow" /> {t('shop.cold_badge')}
                    </span>
                  )}
                  <h4>{lot.description}</h4>
                  <p>{lot.address || t('needy.address_tbd')}</p>
                  <span className="distance">{lot.quantity} {t('needy.qty_unit')}</span>
                  {lot.time_slot && <p style={{ fontSize: '0.8em', color: '#aaa' }}>{t('needy.pickup_time_prefix')}{lot.time_slot}</p>}
                  <div className="lot-actions">
                    <button className="btn-small" onClick={() => handleBook(lot, false)}><MonoIcon name="truck" /> {t('needy.await_volunteer_btn')}</button>
                    <button className="btn-small btn-outline" onClick={() => handleBook(lot, true)}><MonoIcon name="walk" /> {t('needy.self_pickup_btn')}</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
        {lotsHasMore && (
          <button className="btn btn-secondary" style={{ margin: '16px auto', display: 'block' }} onClick={() => loadLots(lotsOffset, true)}>
            {t('common.show_more')}
          </button>
        )}
        <div className="limit-notice" style={{marginTop: '20px'}}>
          <p>{t('needy.limit_notice')}</p>
        </div>
      </div>
    </>
  );

  const renderOrder = () => (
    <div className="tab-content">
      {activeOrder ? (
        <div className="order-status-card">
          <h2>{t('needy.order')}</h2>

          {activeOrder.selfPickup ? (
            <>
              <div className="self-pickup-banner">
                <span style={{ fontSize: '2rem' }}><MonoIcon name="walk" /></span>
                <div>
                  <p style={{ fontWeight: 'bold', margin: '0 0 4px' }}>{t('needy.self_pickup_title')}</p>
                  <p style={{ color: '#aaa', fontSize: '0.85em', margin: 0 }}>{activeOrder.shopName}</p>
                </div>
              </div>
              <div className="order-details">
                <p><strong>{t('needy.items_label')}</strong> {activeOrder.description}</p>
                <p><strong>{t('needy.shop_address_label')}</strong> {activeOrder.address || t('needy.address_tbd')}</p>
                {activeOrder.time_slot && <p><strong>{t('needy.pickup_time_label')}</strong> {activeOrder.time_slot}</p>}
              </div>
              <div className="qr-section">
                <p>{t('needy.show_qr_shop')}</p>
                {renderTicketQr()}
              </div>
            </>
          ) : (
            <>
              {(() => {
                // 'delivering' once the volunteer pressed «Забрал» at the shop
                // (volunteer_en_route notification) or live location is streaming;
                // before that, with a volunteer assigned, they are still collecting.
                const enRoute = hasValidCoordinates(volunteerLocation?.lat, volunteerLocation?.lon) || notifications.some(n =>
                  n.type === 'volunteer_en_route' && (n.payload || '').includes(`тикет ${activeOrder.ticketId}`));
                const phase = enRoute ? 'delivering' : 'picking';
                return (
                  <div className="status-stepper">
                    <div className={`step ${phase === 'picking' ? 'active' : ''}`}>{t('needy.step_collecting')}</div>
                    <div className={`step ${phase === 'delivering' ? 'active' : ''}`}>{t('needy.step_delivering')}</div>
                  </div>
                );
              })()}

              {hasValidCoordinates(volunteerLocation?.lat, volunteerLocation?.lon) && (
                <div style={{ margin: '16px 0', borderRadius: 8, overflow: 'hidden', height: 200 }}>
                  <p style={{ color: '#4CAF50', fontSize: '0.82em', marginBottom: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
                    <span>●</span> {t('needy.volunteer_location')}
                  </p>
                  <YMaps query={{ apikey: YMAPS_KEY }}>
                    <Map
                      state={{ center: [volunteerLocation.lat, volunteerLocation.lon], zoom: 14 }}
                      width="100%"
                      height="180px"
                      options={{ suppressMapOpenBlock: true }}
                    >
                      <Placemark
                        geometry={[volunteerLocation.lat, volunteerLocation.lon]}
                        properties={{ hintContent: t('volunteer.shop_label') }}
                        options={{ preset: 'islands#bluePersonIcon' }}
                      />
                    </Map>
                  </YMaps>
                </div>
              )}

              <div className="order-details">
                <p><strong>{t('needy.items_label')}</strong> {activeOrder.description}</p>
                <p><strong>{t('common.status')}:</strong> {hasValidCoordinates(volunteerLocation?.lat, volunteerLocation?.lon) ? t('needy.volunteer_location') : t('needy.searching_volunteer')}</p>
              </div>
              <div className="qr-section">
                <p>{t('needy.show_qr_volunteer')}</p>
                {renderTicketQr()}
              </div>

              {activeOrder.assigned_volunteer_id && activeOrder.ticketStatus !== 'fulfilled' && (
                <div style={{ marginTop: 16 }}>
                  <h4>{t('needy.chat_title')}</h4>
                  <TicketChat ticketId={activeOrder.ticketId} token={user?.token} me="needy" ns="needy" />
                </div>
              )}
            </>
          )}

          <button className="btn btn-danger" style={{ marginTop: '16px', width: '100%' }} onClick={handleCancelTicket}>
            {t('needy.cancel_ticket')}
          </button>
        </div>
      ) : (
        <EmptyState icon={<MonoIcon name="box" />} title={t('empty.tickets_title')} description={t('empty.tickets_desc')} action={t('empty.tickets_action')} onAction={() => setActiveTab('map')} />
      )}
    </div>
  );

  const unreadCount = notifications.filter(n => !n.read).length;

  // Opening the notifications tab marks everything as read — otherwise the
  // unread badge only ever grows (the read endpoint was never called).
  useEffect(() => {
    if (activeTab !== 'notifications' || !needyId) return;
    const unread = notifications.filter(n => !n.read);
    if (unread.length === 0) return;
    unread.forEach(n => {
      fetch(`${API_URL}/needy/notifications/${n.id}/read`, {
        method: 'PATCH',
        headers: { Authorization: `Bearer ${user?.token}` },
      }).catch(() => {});
    });
    setNotifications(prev => prev.map(n => ({ ...n, read: 1 })));
  }, [activeTab, needyId]);

  const renderNotifications = () => (
    <div className="tab-content">
      <h3>{t('common.notifications')}</h3>
      {notifications.length === 0 ? (
        <p className="empty-msg">{t('needy.no_notifications')}</p>
      ) : (
        <div className="notification-list">
          {notifications.map(n => (
            <div key={n.id} className={`notification-item ${n.read ? '' : 'unread'}`}>
              <p>{n.payload}</p>
              <span className="notif-time">{new Date(n.created_at).toLocaleString()}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );

  return (
    <div className="dashboard-container">
      <aside className="sidebar">
        <h2>SaveFood</h2>
        <nav>
          <button className={activeTab === 'map' ? 'active' : ''} onClick={() => setActiveTab('map')}>{t('needy.lots')}</button>
          <button className={activeTab === 'order' ? 'active' : ''} onClick={() => setActiveTab('order')}>{t('needy.order')}</button>
          <button className={activeTab === 'notifications' ? 'active' : ''} onClick={() => setActiveTab('notifications')}>
            {t('common.notifications')} {unreadCount > 0 && `(${unreadCount})`}
          </button>
          <button className={activeTab === 'profile' ? 'active' : ''} onClick={() => setActiveTab('profile')}>{t('common.profile')}</button>
          <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>{t('common.history')}</button>
        </nav>
      </aside>

      <main className="main-content">
        <header className="content-header">
          <h1>
            {activeTab === 'map' ? t('needy.lots') :
             activeTab === 'order' ? t('needy.order') :
             activeTab === 'notifications' ? t('common.notifications') :
             activeTab === 'profile' ? t('common.profile') : t('common.history')}
          </h1>
        </header>
        {activeTab === 'map' && renderMap()}
        {activeTab === 'order' && renderOrder()}
        {activeTab === 'notifications' && renderNotifications()}
        {activeTab === 'profile' && renderProfile()}
        {activeTab === 'history' && (
          <div className="tab-content">
            <h3>{t('needy.history_page_title')}</h3>
            {history.length === 0 ? <p className="empty-msg">{t('needy.history_empty')}</p> : (
              history.map(item => (
                <div key={item.id} className="history-item">
                  <p><strong>{item.items || t('needy.items_default')}</strong></p>
                  <p>{t('common.status')}: {item.status === 'fulfilled' ? t('needy.status_fulfilled') : t('needy.status_processing')}</p>
                  <p>{new Date(item.created_at).toLocaleDateString()}</p>
                  {item.status === 'fulfilled' && (
                    <div className="rating-row">
                      <span style={{ color: '#aaa', fontSize: '0.85em' }}>{t('needy.rate_label')} </span>
                      {[1,2,3,4,5].map(star => (
                        <button
                          key={star}
                          className={`star-btn ${(ratings[item.id] || item.rating) >= star ? 'active' : ''}`}
                          onClick={() => handleRateDelivery(item.id, star)}
                        >★</button>
                      ))}
                      {(ratings[item.id] || item.rating) && <span style={{ color: '#aaa', fontSize: '0.8em', marginLeft: 6 }}>{ratings[item.id] || item.rating}/5</span>}
                    </div>
                  )}
                  {item.status === 'fulfilled' && (ratings[item.id] || item.rating) && (
                    (sentNotes[item.id] ?? item.rating_comment) ? (
                      <p style={{ color: '#aaa', fontSize: '0.85em', margin: '6px 0 0', fontStyle: 'italic' }}>
                        <MonoIcon name="mail" /> “{sentNotes[item.id] ?? item.rating_comment}”
                      </p>
                    ) : (
                      <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                        <input
                          type="text"
                          maxLength={300}
                          placeholder={t('needy.thanks_placeholder')}
                          value={thankNotes[item.id] || ''}
                          onChange={e => setThankNotes(prev => ({ ...prev, [item.id]: e.target.value }))}
                          style={{ flex: 1, minWidth: 0 }}
                        />
                        <button
                          className="btn-small btn-success"
                          disabled={!(thankNotes[item.id] || '').trim()}
                          onClick={async () => {
                            const note = (thankNotes[item.id] || '').trim();
                            // After a reload the stars live in item.rating (server),
                            // not in the session `ratings` state — pass them through,
                            // and only mark the note as sent if the POST succeeded.
                            const ok = await handleRateDelivery(item.id, ratings[item.id] || item.rating, note);
                            if (ok) {
                              setSentNotes(prev => ({ ...prev, [item.id]: note }));
                              setThankNotes(prev => ({ ...prev, [item.id]: '' }));
                            }
                          }}
                        >
                          {t('needy.thanks_send')}
                        </button>
                      </div>
                    )
                  )}
                </div>
              ))
            )}
            {historyHasMore && (
              <button className="btn btn-secondary" style={{ marginTop: '12px' }} onClick={() => loadHistory(historyOffset, true)}>
                {t('common.show_more')}
              </button>
            )}
          </div>
        )}
      </main>
    </div>
  );
};

export default NeedyDashboard;
