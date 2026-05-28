import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import QRCode from 'react-qr-code';
import { YMaps, Map, Placemark } from '@pbe/react-yandex-maps';
import AddressInput from '../Auth/AddressInput';
import EmptyState from '../../components/EmptyState';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import './Needy.css';

const YMAPS_KEY = process.env.REACT_APP_YANDEX_MAPS_API_KEY || '';

const PAGE = 20;

const CAT_KEYS = {
  'Выпечка': 'bakery',
  'Овощи/Фрукты': 'vegetables',
  'Готовая еда': 'prepared',
  'Молочные продукты': 'dairy',
};

const CATEGORIES = ['Выпечка', 'Овощи/Фрукты', 'Готовая еда', 'Молочные продукты'];

const NeedyDashboard = () => {
  const { user } = useAuth();
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
  const [profile, setProfile] = useState({ address: '', family_size: 1, preferences: '', urgency: 'normal', available_time: '', apartment: '', floor_num: '', entrance: '', city: '', lat: null, lon: null });
  const [tgLink, setTgLink] = useState(null);
  const [tgLoading, setTgLoading] = useState(false);
  const [filterCategory, setFilterCategory] = useState('');
  const [filterSearch, setFilterSearch] = useState('');
  const [ratings, setRatings] = useState({});
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
      .then(data => { if (data) setProfile(prev => ({ ...prev, ...data })); })
      .catch(() => {});

    fetch(`${API_URL}/needy/${needyId}/notifications`, {
      headers: { Authorization: `Bearer ${user?.token}` },
    })
      .then(res => res.json())
      .then(data => setNotifications(Array.isArray(data) ? data : []))
      .catch(() => {});

    loadHistory(0);
  }, [needyId]);

  // WebSocket: live notification stream
  useEffect(() => {
    if (!needyId || !user?.token) return;
    const apiBase = process.env.REACT_APP_API_URL ?? '';
    const base = apiBase
      ? apiBase.replace(/^https?/, m => m === 'https' ? 'wss' : 'ws') + `/ws/needy/${needyId}`
      : `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws/needy/${needyId}`;
    const wsUrl = `${base}?token=${encodeURIComponent(user.token)}`;
    let ws;
    let reconnectTimer;
    const connect = () => {
      ws = new WebSocket(wsUrl);
      ws.onmessage = (e) => {
        try {
          const data = JSON.parse(e.data);
          setNotifications(prev => [{
            id: Date.now(), type: data.type, payload: data.payload, read: 0, created_at: new Date().toISOString(),
          }, ...prev]);
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
        .then(data => { if (data && data.lat && data.lon) setVolunteerLocation(data); })
        .catch(() => {});
    };
    poll();
    locationPollRef.current = setInterval(poll, 15000);
    return () => clearInterval(locationPollRef.current);
  }, [activeOrder?.assigned_volunteer_id, activeOrder?.ticketStatus]);

  // Poll the active ticket so the recipient learns when a volunteer is assigned
  // (assigned_volunteer_id is set server-side when the volunteer takes the route).
  useEffect(() => {
    if (ticketPollRef.current) clearInterval(ticketPollRef.current);
    const ticketId = activeOrder?.ticketId;
    if (!ticketId || activeOrder?.selfPickup || activeOrder?.ticketStatus === 'fulfilled') return;
    const poll = () => {
      fetch(`${API_URL}/needy/${needyId}/tickets`, { headers: { Authorization: `Bearer ${user?.token}` } })
        .then(r => r.ok ? r.json() : null)
        .then(list => {
          if (!Array.isArray(list)) return;
          const ticket = list.find(x => x.id === ticketId);
          if (!ticket) return;
          setActiveOrder(prev => {
            if (!prev || prev.ticketId !== ticketId) return prev;
            if (prev.assigned_volunteer_id === ticket.assigned_volunteer_id && prev.ticketStatus === ticket.status) return prev;
            return { ...prev, assigned_volunteer_id: ticket.assigned_volunteer_id, ticketStatus: ticket.status };
          });
        })
        .catch(() => {});
    };
    poll();
    ticketPollRef.current = setInterval(poll, 15000);
    return () => clearInterval(ticketPollRef.current);
  }, [activeOrder?.ticketId, activeOrder?.selfPickup, activeOrder?.ticketStatus, needyId]);

  const handleBook = async (lot, selfPickup = false) => {
    if (!needyId) { alert(t('common.auth_required')); return; }
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/ticket`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify({
          items: lot.description,
          address: selfPickup ? (lot.address || '') : (profile.address || ''),
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
      const ticket = await res.json();
      setActiveOrder({
        ...lot,
        ticketId: ticket.id,
        selfPickup,
        shopName: lot.shop_name || t('needy.shop_name_default'),
        status: selfPickup ? 'self_pickup' : 'picking',
        eta: selfPickup ? null : t('needy.awaiting_volunteer'),
      });
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

  useEffect(() => {
    setLotsOffset(0);
    loadLots(0, false, filterCategory, filterSearch);
  }, [filterCategory, filterSearch]);

  const handleRateDelivery = async (ticketId, rating) => {
    if (!needyId) return;
    try {
      const res = await fetch(
        `${API_URL}/needy/${needyId}/ticket/${ticketId}/rate?rating=${rating}`,
        { method: 'POST', headers: { Authorization: `Bearer ${user?.token}` } }
      );
      if (res.ok) setRatings(prev => ({ ...prev, [ticketId]: rating }));
      else alert(t('needy.error_rate'));
    } catch { alert(t('common.connection_error')); }
  };

  const handleConnectTelegram = async () => {
    setTgLoading(true);
    try {
      const res = await fetch(`${API_URL}/auth/telegram/init-link`, {
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert(t('needy.error_tg')); return; }
      const data = await res.json();
      setTgLink(data);
    } catch {
      alert(t('common.connection_error'));
    } finally {
      setTgLoading(false);
    }
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
        }),
      });
      if (!res.ok) { alert(t('needy.error_save_profile')); return; }
      alert(t('needy.profile_saved'));
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
          onChange={(addr) => setProfile({ ...profile, address: addr.address, lat: addr.lat ?? profile.lat, lon: addr.lon ?? profile.lon, city: addr.city ?? profile.city, apartment: addr.apartment || profile.apartment, floor_num: addr.floor_num || profile.floor_num, entrance: addr.entrance || profile.entrance })}
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
        <button type="submit" className="btn btn-primary">{t('needy.save_profile')}</button>
      </form>

      <div className="tg-connect-section">
        <h3>{t('needy.tg_title')}</h3>
        {tgLink ? (
          <div className="tg-link-box">
            <p>{t('needy.tg_link_label')}</p>
            <a href={tgLink.link} target="_blank" rel="noreferrer" className="btn btn-primary tg-btn">
              {t('needy.tg_open', { name: tgLink.bot_name })}
            </a>
            {tgLink.already_linked && <p className="tg-hint">{t('needy.tg_already_linked')}</p>}
          </div>
        ) : (
          <button className="btn btn-secondary" onClick={handleConnectTelegram} disabled={tgLoading}>
            {tgLoading ? t('common.loading') : t('needy.telegram_connect')}
          </button>
        )}
      </div>
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
                  balloonContentHeader: lot.shop_name || lot.description,
                  balloonContentBody: `${lot.description} · ${lot.quantity} ${t('needy.qty_unit')}`,
                  balloonContentFooter: lot.time_slot ? `${t('needy.pickup_time_prefix')}${lot.time_slot}` : '',
                  hintContent: lot.shop_name || lot.description,
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
        {lots.length === 0 && <EmptyState icon="🛒" title={t('empty.lots_title')} description={t('empty.lots_city_desc')} />}
        {lotsByShop.map(group => (
          <div key={group.shopId} className="shop-group">
            <div className="shop-group-header">
              <span className="shop-group-icon">🏪</span>
              <h4>{group.shopName}</h4>
            </div>
            <div className="lot-grid">
              {group.lots.map(lot => (
                <div key={lot.id} className="lot-card-compact">
                  {lot.photo && (
                    <img
                      src={`${API_URL}${lot.photo}`}
                      alt={lot.description}
                      className="lot-photo"
                      onError={(e) => { e.target.style.display = 'none'; }}
                    />
                  )}
                  {lot.category && <span className="category-badge">{t(`categories.${CAT_KEYS[lot.category]}`, { defaultValue: lot.category })}</span>}
                  <h4>{lot.description}</h4>
                  <p>{lot.address || t('needy.address_tbd')}</p>
                  <span className="distance">{lot.quantity} {t('needy.qty_unit')}</span>
                  {lot.time_slot && <p style={{ fontSize: '0.8em', color: '#aaa' }}>{t('needy.pickup_time_prefix')}{lot.time_slot}</p>}
                  <div className="lot-actions">
                    <button className="btn-small" onClick={() => handleBook(lot, false)}>🚚 {t('needy.await_volunteer_btn')}</button>
                    <button className="btn-small btn-outline" onClick={() => handleBook(lot, true)}>🚶 {t('needy.self_pickup_btn')}</button>
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
                <span style={{ fontSize: '2rem' }}>🚶</span>
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
                {activeOrder.ticketId && (
                  <QRCode value={`SF-${activeOrder.ticketId}`} size={128} bgColor="#1a1a2e" fgColor="#ffffff" />
                )}
                <span className="qr-code-text">SF-{activeOrder.ticketId || '???'}</span>
              </div>
            </>
          ) : (
            <>
              <div className="status-stepper">
                <div className={`step ${activeOrder.status === 'picking' ? 'active' : ''}`}>{t('needy.step_collecting')}</div>
                <div className={`step ${activeOrder.status === 'delivering' ? 'active' : ''}`}>{t('needy.step_delivering')}</div>
              </div>

              {volunteerLocation?.lat && (
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
                <p><strong>{t('common.status')}:</strong> {volunteerLocation?.lat ? t('needy.volunteer_location') : t('needy.searching_volunteer')}</p>
              </div>
              <div className="qr-section">
                <p>{t('needy.show_qr_volunteer')}</p>
                {activeOrder.ticketId && (
                  <QRCode value={`SF-${activeOrder.ticketId}`} size={128} bgColor="#1a1a2e" fgColor="#ffffff" />
                )}
                <span className="qr-code-text">SF-{activeOrder.ticketId || '???'}</span>
              </div>
            </>
          )}

          <button className="btn btn-danger" style={{ marginTop: '16px', width: '100%' }} onClick={handleCancelTicket}>
            {t('needy.cancel_ticket')}
          </button>
        </div>
      ) : (
        <EmptyState icon="📦" title={t('empty.tickets_title')} description={t('empty.tickets_desc')} action={t('empty.tickets_action')} onAction={() => setActiveTab('map')} />
      )}
    </div>
  );

  const unreadCount = notifications.filter(n => !n.read).length;

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
                  {item.delivery_photo && (
                    <a href={`${API_URL}${item.delivery_photo}`} target="_blank" rel="noreferrer">
                      <img src={`${API_URL}${item.delivery_photo}`} alt={t('needy.delivery_photo_alt')} className="delivery-photo-thumb" />
                    </a>
                  )}
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
