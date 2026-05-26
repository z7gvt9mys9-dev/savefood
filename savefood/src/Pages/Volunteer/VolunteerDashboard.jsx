import React, { useState, useEffect } from 'react';
import { YMaps, Map, Placemark } from '@pbe/react-yandex-maps';
import { useAuth } from '../../context/AuthContext';
import './Volunteer.css';

const haversineMeters = (lat1, lon1, lat2, lon2) => {
  const R = 6371000;
  const p1 = lat1 * Math.PI/180, p2 = lat2 * Math.PI/180;
  const dp = (lat2-lat1)*Math.PI/180, dl = (lon2-lon1)*Math.PI/180;
  const a = Math.sin(dp/2)**2 + Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)**2;
  return R*2*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
};

const VolunteerDashboard = () => {
  const { user } = useAuth();
  const volunteerId = user?.relatedId;
  const authHeader = { Authorization: `Bearer ${user?.token}` };

  const [activeTab, setActiveTab] = useState('map');
  const [mapData, setMapData] = useState({ shops: [], tickets: [] });
  const [activeRoute, setActiveRoute] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [loading, setLoading] = useState(false);
  const [routes, setRoutes] = useState([]);
  const [gpsStatus, setGpsStatus] = useState('unknown');

  useEffect(() => {
    fetchMapData();
    if (volunteerId) {
      fetchActiveRoute();
      fetch(`http://localhost:8000/volunteers/${volunteerId}/history`, { headers: authHeader })
        .then(res => res.ok ? res.json() : [])
        .then(data => setRoutes(Array.isArray(data) ? data : []))
        .catch(() => {});
    }
  }, [volunteerId]);

  const fetchMapData = async () => {
    try {
      const res = await fetch('http://localhost:8000/volunteers/map', { headers: authHeader });
      if (res.ok) setMapData(await res.json());
    } catch {}
  };

  const fetchActiveRoute = async () => {
    try {
      const res = await fetch(`http://localhost:8000/volunteers/${volunteerId}/active_route`, { headers: authHeader });
      if (!res.ok) return;
      const data = await res.json();
      setActiveRoute(data && data.id ? data : null);
    } catch {}
  };

  const handleTakeTask = async (lotId) => {
    if (!volunteerId) { alert('Необходима авторизация'); return; }
    setLoading(true);
    try {
      const res = await fetch(`http://localhost:8000/volunteers/${volunteerId}/start_route`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ lot_id: lotId }),
      });
      if (!res.ok) { const e = await res.json(); alert(e.detail || 'Не удалось начать маршрут'); return; }
      await fetchActiveRoute();
      setActiveTab('route');
    } catch {
      alert('Ошибка подключения к серверу');
    } finally {
      setLoading(false);
    }
  };

  const handleCompletePoint = async (ticketId = null) => {
    if (!activeRoute) return;
    try {
      const res = await fetch(`http://localhost:8000/volunteers/route/${activeRoute.id}/complete_point`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ volunteer_id: volunteerId, ticket_id: ticketId }),
      });
      if (!res.ok) { const e = await res.json(); alert(e.detail || 'Ошибка'); return; }
      await fetchActiveRoute();
    } catch {
      alert('Ошибка подключения к серверу');
    }
  };

  const handleFinishRoute = async () => {
    if (!activeRoute) return;
    try {
      const res = await fetch(`http://localhost:8000/volunteers/route/${activeRoute.id}/finish`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ volunteer_id: volunteerId }),
      });
      if (res.ok) {
        setActiveRoute(null);
        setActiveTab('map');
        fetchMapData();
      }
    } catch {}
  };

  const points = activeRoute?.points || [];
  const shopPoint = points.find(p => p.kind === 'shop');
  const isShopDone = shopPoint?.done ?? false;
  const pendingTickets = points.filter(p => p.kind === 'ticket' && !p.done);
  const nextTicket = pendingTickets[0] || null;

  const checkGPS = async () => {
    if (!nextTicket?.lat || !nextTicket?.lon) { setGpsStatus('ok'); return 'ok'; }
    setGpsStatus('checking');
    return new Promise((resolve) => {
      if (!navigator.geolocation) { setGpsStatus('error'); resolve('error'); return; }
      navigator.geolocation.getCurrentPosition(
        pos => {
          const d = haversineMeters(pos.coords.latitude, pos.coords.longitude, nextTicket.lat, nextTicket.lon);
          const s = d <= 100 ? 'ok' : 'far';
          setGpsStatus(s); resolve(s);
        },
        () => { setGpsStatus('error'); resolve('error'); },
        { timeout: 8000, enableHighAccuracy: true }
      );
    });
  };

  const renderMap = () => (
    <div className="volunteer-tab">
      <div className="map-container-mobile">
        <YMaps query={{ apikey: process.env.REACT_APP_YANDEX_MAPS_API_KEY }}>
          <Map defaultState={{ center: [55.75, 37.62], zoom: 12 }} width="100%" height="100%">
            {mapData.shops.map(s => s.lat && s.lon && (
              <Placemark
                key={`shop-${s.shop_id}`}
                geometry={[s.lat, s.lon]}
                properties={{ balloonContent: `<strong>${s.name}</strong><br/>${s.lots.map(l => l.description).join(', ')}` }}
              />
            ))}
            {mapData.tickets.map(t => t.lat && t.lon && (
              <Placemark
                key={`ticket-${t.ticket_id}`}
                geometry={[t.lat, t.lon]}
                properties={{ balloonContent: t.items || 'Заявка' }}
                options={{ preset: 'islands#greenCircleDotIcon' }}
              />
            ))}
          </Map>
        </YMaps>
      </div>
      <div className="task-list-mobile">
        <h3>Доступные задачи</h3>
        {mapData.shops.length === 0 && <p className="empty-msg">Нет доступных лотов</p>}
        {mapData.shops.flatMap(s =>
          s.lots.map(lot => (
            <div key={lot.lot_id} className="task-card-mobile">
              <div className="task-info">
                <h4>{s.name}</h4>
                <p>{lot.description} — {lot.quantity} шт.</p>
              </div>
              <button
                className="btn btn-primary"
                disabled={loading || !!activeRoute}
                onClick={() => handleTakeTask(lot.lot_id)}
              >
                Взять
              </button>
            </div>
          ))
        )}
      </div>
    </div>
  );

  const renderRoute = () => (
    <div className="volunteer-tab route-page">
      {!activeRoute ? (
        <p className="empty-msg">У вас нет активного маршрута.</p>
      ) : (
        <>
          <div className="map-container-mobile mini-map">
            <YMaps query={{ apikey: process.env.REACT_APP_YANDEX_MAPS_API_KEY }}>
              <Map
                state={{ center: shopPoint?.lat ? [shopPoint.lat, shopPoint.lon] : [55.75, 37.62], zoom: 14 }}
                width="100%" height="100%"
              >
                {points.map((p, i) => p.lat && p.lon && (
                  <Placemark
                    key={i}
                    geometry={[p.lat, p.lon]}
                    properties={{ balloonContent: p.description || '' }}
                    options={{ preset: p.kind === 'shop' ? 'islands#redShoppingIcon' : 'islands#greenHomeIcon' }}
                  />
                ))}
              </Map>
            </YMaps>
          </div>
          <div className="navigator-card">
            <div className="route-header">
              <h2>{!isShopDone ? 'Заберите из магазина' : nextTicket ? 'Доставьте получателю' : 'Маршрут выполнен'}</h2>
              <span className="badge">В пути</span>
            </div>

            <div className="route-points">
              {points.map((p, i) => {
                const letter = p.kind === 'shop' ? 'A' : String.fromCharCode(65 + points.filter((x, j) => x.kind === 'ticket' && j <= i).length);
                return (
                  <div key={i} className={`point ${!p.done && p === (isShopDone ? (nextTicket || null) : shopPoint) ? 'current' : p.done ? 'done' : ''}`}>
                    <div className="point-icon">{letter}</div>
                    <div className="point-text">
                      <p className="point-label">{p.kind === 'shop' ? 'Магазин' : 'Получатель'}</p>
                      <p className="point-addr">{p.description}</p>
                    </div>
                    {p.done && <span style={{ color: '#0f0', marginLeft: 'auto' }}>✓</span>}
                  </div>
                );
              })}
            </div>

            <div className="navigation-actions">
              {!isShopDone ? (
                <button className="btn btn-primary btn-full" onClick={() => handleCompletePoint(null)}>
                  Я ЗАБРАЛ (Уведомить получателей)
                </button>
              ) : nextTicket ? (
                scanning ? (
                  <div className="scanner-mock" onClick={async () => {
                    await handleCompletePoint(nextTicket.ticket_id);
                    setScanning(false);
                  }}>
                    <div className="scanner-frame"></div>
                    <p>Наведите камеру на QR-код получателя</p>
                    <button className="btn-small" onClick={(e) => { e.stopPropagation(); setScanning(false); }}>Отмена</button>
                  </div>
                ) : (
                  <div>
                    {gpsStatus === 'checking' && <p style={{ color: '#aaa', textAlign: 'center' }}>Определяем геолокацию…</p>}
                    {gpsStatus === 'far' && <p style={{ color: '#f90', textAlign: 'center' }}>Вы слишком далеко от адреса доставки (&gt;100м)</p>}
                    {gpsStatus === 'error' && <p style={{ color: '#fa0', textAlign: 'center' }}>Не удалось определить геолокацию. Продолжайте осторожно.</p>}
                    <button
                      className="btn btn-primary btn-full"
                      disabled={gpsStatus === 'checking' || gpsStatus === 'far'}
                      onClick={async () => {
                        const status = await checkGPS();
                        if (status === 'ok' || status === 'error') setScanning(true);
                      }}
                    >
                      СКАНЕР QR-КОДА
                    </button>
                  </div>
                )
              ) : (
                <button className="btn btn-primary btn-full" onClick={handleFinishRoute}>
                  Завершить маршрут
                </button>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );

  return (
    <div className="mobile-container">
      <main className="mobile-content">
        {activeTab === 'map' && renderMap()}
        {activeTab === 'route' && renderRoute()}
        {activeTab === 'history' && (
          <div className="volunteer-tab">
            <h3>Мои маршруты</h3>
            <div className="stats-row">
              <div className="v-stat"><span>{routes.length}</span> Маршрутов</div>
              <div className="v-stat"><span>{routes.filter(r=>r.status==='finished').length}</span> Завершено</div>
            </div>
            {routes.length === 0 ? <p className="empty-msg">История пуста</p> : routes.map(r => (
              <div key={r.id} className="task-card-mobile">
                <div className="task-info">
                  <p>Маршрут #{r.id}</p>
                  <p>Статус: {r.status === 'finished' ? 'Завершён' : r.status === 'timed_out' ? 'Истёк' : 'В процессе'}</p>
                  <p>{new Date(r.started_at).toLocaleDateString()}</p>
                </div>
              </div>
            ))}
          </div>
        )}
      </main>

      <nav className="mobile-nav">
        <button className={activeTab === 'map' ? 'active' : ''} onClick={() => setActiveTab('map')}>Карта</button>
        <button className={activeTab === 'route' ? 'active' : ''} onClick={() => { setActiveTab('route'); fetchActiveRoute(); }}>Маршрут</button>
        <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>История</button>
      </nav>
    </div>
  );
};

export default VolunteerDashboard;
