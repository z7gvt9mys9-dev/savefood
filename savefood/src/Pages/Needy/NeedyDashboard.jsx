import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import QRCode from 'react-qr-code';
import { YMaps, Map, Placemark } from '@pbe/react-yandex-maps';
import AddressInput from '../Auth/AddressInput';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import './Needy.css';

const YMAPS_KEY = process.env.REACT_APP_YANDEX_MAPS_API_KEY || '';

const PAGE = 20;

const NeedyDashboard = () => {
  const { user } = useAuth();
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
  const [profile, setProfile] = useState({ address: '', family_size: 1, preferences: '', urgency: 'normal', available_time: '', apartment: '', floor_num: '', entrance: '', city: '' });
  const [tgLink, setTgLink] = useState(null);
  const [tgLoading, setTgLoading] = useState(false);
  const [filterCategory, setFilterCategory] = useState('');
  const [filterSearch, setFilterSearch] = useState('');
  const [ratings, setRatings] = useState({});
  const [volunteerLocation, setVolunteerLocation] = useState(null);
  const locationPollRef = useRef(null);

  const lotPositions = useMemo(
    () => lots.map(lot => ({ ...lot, _top: Math.random() * 80, _left: Math.random() * 80 })),
    [lots]
  );

  const loadLots = useCallback(async (offset = 0, append = false, category = filterCategory, search = filterSearch) => {
    try {
      const params = new URLSearchParams({ limit: PAGE, offset });
      if (category) params.append('category', category);
      if (search) params.append('search', search);
      if (profile.city) params.append('city', profile.city);
      const res = await fetch(`${API_URL}/lots?${params}`);
      const data = await res.json();
      const arr = Array.isArray(data) ? data : [];
      setLots(prev => append ? [...prev, ...arr] : arr);
      setLotsHasMore(arr.length === PAGE);
      setLotsOffset(offset + arr.length);
    } catch {}
  }, [filterCategory, filterSearch, profile.city]);

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
    if (!needyId) return;
    const apiBase = process.env.REACT_APP_API_URL ?? '';
    const wsUrl = apiBase
      ? apiBase.replace(/^https?/, m => m === 'https' ? 'wss' : 'ws') + `/ws/needy/${needyId}`
      : `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws/needy/${needyId}`;
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
  }, [needyId]);

  useEffect(() => {
    if (locationPollRef.current) clearInterval(locationPollRef.current);
    const assignedVolunteerId = activeOrder?.assigned_volunteer_id;
    const ticketFulfilled = activeOrder?.status === 'fulfilled';
    if (!assignedVolunteerId || ticketFulfilled) { setVolunteerLocation(null); return; }
    const poll = () => {
      fetch(`${API_URL}/volunteers/${assignedVolunteerId}/location`)
        .then(r => r.ok ? r.json() : null)
        .then(data => { if (data && data.lat && data.lon) setVolunteerLocation(data); })
        .catch(() => {});
    };
    poll();
    locationPollRef.current = setInterval(poll, 15000);
    return () => clearInterval(locationPollRef.current);
  }, [activeOrder?.assigned_volunteer_id, activeOrder?.status]);

  const handleBook = async (lot) => {
    if (!needyId) { alert('Необходима авторизация'); return; }
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/ticket`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify({
          items: lot.description,
          address: profile.address || '',
          lot_id: lot.id,
          available_time: profile.available_time || '',
          apartment: profile.apartment || null,
          floor_num: profile.floor_num || null,
          entrance: profile.entrance || null,
        }),
      });
      if (!res.ok) {
        const err = await res.json();
        alert(err.detail || 'Ошибка бронирования');
        return;
      }
      const ticket = await res.json();
      setActiveOrder({ ...lot, ticketId: ticket.id, status: 'picking', eta: 'Ожидайте волонтера' });
      setActiveTab('order');
    } catch {
      alert('Ошибка подключения к серверу');
    }
  };

  const handleCancelTicket = async () => {
    if (!activeOrder?.ticketId || !needyId) return;
    if (!window.confirm('Отменить заявку?')) return;
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/ticket/${activeOrder.ticketId}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) {
        const err = await res.json();
        alert(err.detail || 'Не удалось отменить заявку');
        return;
      }
      setActiveOrder(null);
      setActiveTab('map');
    } catch {
      alert('Ошибка подключения к серверу');
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
      else alert('Не удалось сохранить оценку');
    } catch { alert('Ошибка подключения'); }
  };

  const handleConnectTelegram = async () => {
    setTgLoading(true);
    try {
      const res = await fetch(`${API_URL}/auth/telegram/init-link`, {
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert('Ошибка генерации ссылки'); return; }
      const data = await res.json();
      setTgLink(data);
    } catch {
      alert('Ошибка подключения к серверу');
    } finally {
      setTgLoading(false);
    }
  };

  const handleSaveProfile = async (e) => {
    e.preventDefault();
    if (!needyId) { alert('Необходима авторизация'); return; }
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
        }),
      });
      if (!res.ok) { alert('Ошибка сохранения'); return; }
      alert('Изменения сохранены!');
    } catch {
      alert('Ошибка подключения к серверу');
    }
  };

  const renderProfile = () => (
    <div className="tab-content">
      <form className="admin-form" onSubmit={handleSaveProfile}>
        <h2>Анкета получателя</h2>
        <AddressInput
          label="Адрес проживания"
          value={profile.address}
          onChange={(addr) => setProfile({ ...profile, address: addr.address, apartment: addr.apartment || profile.apartment, floor_num: addr.floor_num || profile.floor_num, entrance: addr.entrance || profile.entrance })}
        />
        <div className="form-row">
          <div className="form-group">
            <label>Состав семьи (чел)</label>
            <input type="number" value={profile.family_size} onChange={(e) => setProfile({ ...profile, family_size: e.target.value })} />
          </div>
          <div className="form-group">
            <label>Уровень срочности</label>
            <select value={profile.urgency} onChange={(e) => setProfile({ ...profile, urgency: e.target.value })}>
              <option value="normal">Обычный</option>
              <option value="high">Высокий</option>
              <option value="critical">Критический</option>
            </select>
          </div>
        </div>
        <div className="form-group">
          <label>Удобное время получения (например: 14:00-18:00)</label>
          <input type="text" placeholder="14:00-18:00" value={profile.available_time} onChange={(e) => setProfile({ ...profile, available_time: e.target.value })} />
        </div>
        <div className="form-group">
          <label>Пищевые ограничения</label>
          <textarea placeholder="Например: аллергия на лактозу" value={profile.preferences} onChange={(e) => setProfile({ ...profile, preferences: e.target.value })} />
        </div>
        <button type="submit" className="btn btn-primary">Сохранить изменения</button>
      </form>

      <div className="tg-connect-section">
        <h3>Уведомления в Telegram</h3>
        {tgLink ? (
          <div className="tg-link-box">
            <p>Ссылка действует 10 минут. Нажмите кнопку ниже, чтобы открыть бота:</p>
            <a href={tgLink.link} target="_blank" rel="noreferrer" className="btn btn-primary tg-btn">
              Открыть @{tgLink.bot_name} в Telegram
            </a>
            {tgLink.already_linked && <p className="tg-hint">У вас уже подключён Telegram — ссылка обновит привязку.</p>}
          </div>
        ) : (
          <button className="btn btn-secondary" onClick={handleConnectTelegram} disabled={tgLoading}>
            {tgLoading ? 'Загрузка...' : 'Подключить Telegram'}
          </button>
        )}
      </div>
    </div>
  );

  const lotsWithCoords = useMemo(() => lots.filter(l => l.lat && l.lon), [lots]);
  const mapCenter = lotsWithCoords.length > 0
    ? [lotsWithCoords[0].lat, lotsWithCoords[0].lon]
    : [55.7522, 37.6156];

  const renderMap = () => (
    <>
      <div className="tab-content">
        <div className="lot-filters">
          <select value={filterCategory} onChange={e => setFilterCategory(e.target.value)}>
            <option value="">Все категории</option>
            <option value="Выпечка">Выпечка</option>
            <option value="Овощи/Фрукты">Овощи/Фрукты</option>
            <option value="Готовая еда">Готовая еда</option>
            <option value="Молочные продукты">Молочные продукты</option>
          </select>
          <input
            type="text"
            placeholder="Поиск по описанию или адресу..."
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
                geometry={[lot.lat, lot.lon]}
                properties={{
                  balloonContentHeader: lot.description,
                  balloonContentBody: `${lot.quantity} кг/шт · ${lot.address || ''}`,
                  balloonContentFooter: lot.time_slot ? `Выдача: ${lot.time_slot}` : '',
                  hintContent: lot.description,
                }}
                options={{ preset: 'islands#greenFoodIcon', iconColor: '#2ecc71' }}
              />
            ))}
            {volunteerLocation && volunteerLocation.lat && volunteerLocation.lon && (
              <Placemark
                geometry={[volunteerLocation.lat, volunteerLocation.lon]}
                properties={{ balloonContent: 'Волонтёр', hintContent: 'Волонтёр' }}
                options={{ preset: 'islands#bluePersonIcon' }}
              />
            )}
          </Map>
        </YMaps>
        {lotsWithCoords.length === 0 && (
          <div className="map-no-coords">Нет лотов с указанными координатами</div>
        )}
      </div>
      <div className="tab-content">
        <div className="lot-grid">
          {lots.length === 0 && <p className="empty-msg">Нет доступных лотов</p>}
          {lots.map(lot => (
            <div key={lot.id} className="lot-card-compact">
              {lot.photo && (
                <img
                  src={`${API_URL}${lot.photo}`}
                  alt={lot.description}
                  className="lot-photo"
                  onError={(e) => { e.target.style.display = 'none'; }}
                />
              )}
              <h4>{lot.description}</h4>
              <p>{lot.address || 'Адрес уточняется'}</p>
              <span className="distance">{lot.quantity} кг/шт</span>
              {lot.time_slot && <p style={{ fontSize: '0.8em', color: '#aaa' }}>Выдача: {lot.time_slot}</p>}
              <button className="btn-small" onClick={() => handleBook(lot)}>Забронировать</button>
            </div>
          ))}
        </div>
        {lotsHasMore && (
          <button className="btn btn-secondary" style={{ margin: '16px auto', display: 'block' }} onClick={() => loadLots(lotsOffset, true)}>
            Показать ещё
          </button>
        )}
        <div className="limit-notice" style={{marginTop: '20px'}}>
          <p>Вы можете оформить заявку не чаще 1 раза в неделю.</p>
        </div>
      </div>
    </>
  );

  const renderOrder = () => (
    <div className="tab-content">
      {activeOrder ? (
        <div className="order-status-card">
          <h2>Текущий заказ</h2>
          <div className="status-stepper">
            <div className={`step ${activeOrder.status === 'picking' ? 'active' : ''}`}>Сборка</div>
            <div className={`step ${activeOrder.status === 'delivering' ? 'active' : ''}`}>Доставка</div>
          </div>
          <div className="order-details">
            <p><strong>Продукты:</strong> {activeOrder.description}</p>
            <p><strong>Статус:</strong> Волонтер собирает продукты</p>
            <p><strong>Ожидаемое время:</strong> {activeOrder.eta}</p>
          </div>
          <div className="qr-section">
            <p>Покажите этот код волонтеру при получении:</p>
            {activeOrder.ticketId && (
              <QRCode value={`SF-${activeOrder.ticketId}`} size={128} bgColor="#1a1a2e" fgColor="#ffffff" />
            )}
            <span className="qr-code-text">SF-{activeOrder.ticketId || '???'}</span>
          </div>
          <button className="btn btn-danger" style={{ marginTop: '16px', width: '100%' }} onClick={handleCancelTicket}>
            Отменить заявку
          </button>
        </div>
      ) : (
        <p className="empty-msg">У вас нет активных заказов.</p>
      )}
    </div>
  );

  const unreadCount = notifications.filter(n => !n.read).length;

  const renderNotifications = () => (
    <div className="tab-content">
      <h3>Уведомления</h3>
      {notifications.length === 0 ? (
        <p className="empty-msg">Нет новых уведомлений</p>
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
        <h2>Моя Помощь</h2>
        <nav>
          <button className={activeTab === 'map' ? 'active' : ''} onClick={() => setActiveTab('map')}>Карта лотов</button>
          <button className={activeTab === 'order' ? 'active' : ''} onClick={() => setActiveTab('order')}>Текущий заказ</button>
          <button className={activeTab === 'notifications' ? 'active' : ''} onClick={() => setActiveTab('notifications')}>
            Уведомления {unreadCount > 0 && `(${unreadCount})`}
          </button>
          <button className={activeTab === 'profile' ? 'active' : ''} onClick={() => setActiveTab('profile')}>Анкета</button>
          <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>История</button>
        </nav>
      </aside>

      <main className="main-content">
        <header className="content-header">
          <h1>
            {activeTab === 'map' ? 'Доступная еда' :
             activeTab === 'order' ? 'Статус доставки' :
             activeTab === 'notifications' ? 'Уведомления' :
             activeTab === 'profile' ? 'Мой профиль' : 'Архив помощи'}
          </h1>
        </header>
        {activeTab === 'map' && renderMap()}
        {activeTab === 'order' && renderOrder()}
        {activeTab === 'notifications' && renderNotifications()}
        {activeTab === 'profile' && renderProfile()}
        {activeTab === 'history' && (
          <div className="tab-content">
            <h3>История заявок</h3>
            {history.length === 0 ? <p className="empty-msg">История пуста</p> : (
              history.map(t => (
                <div key={t.id} className="history-item">
                  <p><strong>{t.items || 'Продукты'}</strong></p>
                  <p>Статус: {t.status === 'fulfilled' ? 'Получено' : 'В процессе'}</p>
                  <p>{new Date(t.created_at).toLocaleDateString()}</p>
                  {t.delivery_photo && (
                    <a href={`${API_URL}${t.delivery_photo}`} target="_blank" rel="noreferrer">
                      <img src={`${API_URL}${t.delivery_photo}`} alt="Фото доставки" className="delivery-photo-thumb" />
                    </a>
                  )}
                  {t.status === 'fulfilled' && (
                    <div className="rating-row">
                      <span style={{ color: '#aaa', fontSize: '0.85em' }}>Оценить: </span>
                      {[1,2,3,4,5].map(star => (
                        <button
                          key={star}
                          className={`star-btn ${(ratings[t.id] || t.rating) >= star ? 'active' : ''}`}
                          onClick={() => handleRateDelivery(t.id, star)}
                        >★</button>
                      ))}
                      {(ratings[t.id] || t.rating) && <span style={{ color: '#aaa', fontSize: '0.8em', marginLeft: 6 }}>{ratings[t.id] || t.rating}/5</span>}
                    </div>
                  )}
                </div>
              ))
            )}
            {historyHasMore && (
              <button className="btn btn-secondary" style={{ marginTop: '12px' }} onClick={() => loadHistory(historyOffset, true)}>
                Показать ещё
              </button>
            )}
          </div>
        )}
      </main>
    </div>
  );
};

export default NeedyDashboard;
