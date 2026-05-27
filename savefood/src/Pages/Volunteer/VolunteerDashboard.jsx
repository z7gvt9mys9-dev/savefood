import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Html5Qrcode } from 'html5-qrcode';
import { YMaps, Map, Placemark, useYMaps } from '@pbe/react-yandex-maps';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import EmptyState from '../../components/EmptyState';
import './Volunteer.css';

const haversineMeters = (lat1, lon1, lat2, lon2) => {
  const R = 6371000;
  const p1 = lat1 * Math.PI/180, p2 = lat2 * Math.PI/180;
  const dp = (lat2-lat1)*Math.PI/180, dl = (lon2-lon1)*Math.PI/180;
  const a = Math.sin(dp/2)**2 + Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)**2;
  return R*2*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
};

const isMobileDevice = () =>
  (typeof window !== 'undefined' && window.Capacitor?.isNativePlatform?.()) ||
  /Android|iPhone|iPad|iPod/i.test(navigator.userAgent || '');

// Open the device's navigation app routed to (lat, lon). On mobile we try the
// Yandex Navigator deep link and fall back to web Yandex Maps if the app isn't
// installed (the page stays visible, so the timeout fires). On desktop there is
// no nav app, so we just open the web route — the on-site map is the real fallback.
const openInNavigator = (lat, lon) => {
  const webUrl = `https://yandex.ru/maps/?rtext=~${lat},${lon}&rtt=auto`;
  if (!isMobileDevice()) {
    window.open(webUrl, '_blank', 'noopener');
    return;
  }
  const appUrl = `yandexnavi://build_route_on_map?lat_to=${lat}&lon_to=${lon}`;
  let opened = false;
  const onHide = () => { opened = true; };
  document.addEventListener('visibilitychange', onHide, { once: true });
  const fallback = setTimeout(() => {
    document.removeEventListener('visibilitychange', onHide);
    if (!opened && document.visibilityState === 'visible') window.location.href = webUrl;
  }, 1500);
  window.location.href = appUrl;
  setTimeout(() => clearTimeout(fallback), 4000);
};

// Inner component — must be rendered inside <YMaps> to use useYMaps
const RouteMapView = ({ points }) => {
  const ymaps = useYMaps(['multiRouter.MultiRoute']);
  const mapRef = useRef(null);
  const routeRef = useRef(null);
  const shopPoint = points.find(p => p.kind === 'shop');
  const center = shopPoint?.lat ? [shopPoint.lat, shopPoint.lon] : [55.75, 37.62];

  useEffect(() => {
    if (!ymaps || !mapRef.current) return;
    // clean up previous route
    if (routeRef.current) {
      mapRef.current.geoObjects.remove(routeRef.current);
      routeRef.current = null;
    }
    const coords = points.filter(p => p.lat && p.lon).map(p => [p.lat, p.lon]);
    if (coords.length < 2) return;
    const multiRoute = new ymaps.multiRouter.MultiRoute(
      { referencePoints: coords },
      {
        routeActiveStrokeColor: '#4CAF50',
        routeActiveStrokeWidth: 4,
        boundsAutoApply: true,
        wayPointStartIconColor: '#ff6b35',
        wayPointFinishIconColor: '#2196F3',
      }
    );
    mapRef.current.geoObjects.add(multiRoute);
    routeRef.current = multiRoute;
    return () => {
      if (mapRef.current && routeRef.current) {
        mapRef.current.geoObjects.remove(routeRef.current);
        routeRef.current = null;
      }
    };
  }, [ymaps, JSON.stringify(points)]);

  return (
    <Map instanceRef={mapRef} state={{ center, zoom: 13 }} width="100%" height="100%">
      {points.map((p, i) => p.lat && p.lon && (
        <Placemark
          key={i}
          geometry={[p.lat, p.lon]}
          properties={{ balloonContent: p.description || '' }}
          options={{ preset: p.kind === 'shop' ? 'islands#redShoppingIcon' : 'islands#greenHomeIcon' }}
        />
      ))}
    </Map>
  );
};

const VolunteerDashboard = () => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const volunteerId = user?.relatedId;
  const authHeader = { Authorization: `Bearer ${user?.token}` };

  const [activeTab, setActiveTab] = useState('map');
  const [mapData, setMapData] = useState({ shops: [], tickets: [] });
  const [filterCategory, setFilterCategory] = useState('');
  const [activeRoute, setActiveRoute] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [loading, setLoading] = useState(false);
  const [routes, setRoutes] = useState([]);
  const [gpsStatus, setGpsStatus] = useState('unknown');
  const [volunteerRating, setVolunteerRating] = useState(null);
  const [tgLink, setTgLink] = useState(null);
  const [tgLoading, setTgLoading] = useState(false);
  const [stats, setStats] = useState(null);
  const [attemptMsgs, setAttemptMsgs] = useState({});
  const [photoUploading, setPhotoUploading] = useState({});
  const locationWatchRef = useRef(null);
  const locationIntervalRef = useRef(null);
  const qrScannerRef = useRef(null);

  const stopScanner = useCallback(() => {
    if (qrScannerRef.current) {
      qrScannerRef.current.stop()
        .then(() => { qrScannerRef.current.clear(); qrScannerRef.current = null; })
        .catch(() => { qrScannerRef.current = null; });
    }
  }, []);

  useEffect(() => {
    if (!scanning) { stopScanner(); return; }
    const scanner = new Html5Qrcode('qr-reader');
    qrScannerRef.current = scanner;
    scanner.start(
      { facingMode: 'environment' },
      { fps: 10, qrbox: 250 },
      async (decodedText) => {
        stopScanner();
        const match = decodedText.match(/^SF-(\d+)$/);
        if (match && nextTicket && parseInt(match[1]) === nextTicket.ticket_id) {
          await handleCompletePoint(nextTicket.ticket_id);
          setScanning(false);
        } else {
          alert('Неверный QR-код. Ожидается: SF-' + (nextTicket?.ticket_id ?? '?'));
          setScanning(false);
        }
      },
      () => {}
    ).catch(() => { setScanning(false); });
    return stopScanner;
  }, [scanning]);

  useEffect(() => {
    fetchMapData();
    if (volunteerId) {
      fetchActiveRoute();
      fetch(`${API_URL}/volunteers/${volunteerId}/history`, { headers: authHeader })
        .then(res => res.ok ? res.json() : [])
        .then(data => setRoutes(Array.isArray(data) ? data : []))
        .catch(() => {});
      fetch(`${API_URL}/volunteers/${volunteerId}/rating`, { headers: authHeader })
        .then(res => res.ok ? res.json() : null)
        .then(data => { if (data) setVolunteerRating(data); })
        .catch(() => {});
    }
  }, [volunteerId]);

  useEffect(() => {
    if (!volunteerId || !activeRoute) {
      if (locationIntervalRef.current) clearInterval(locationIntervalRef.current);
      return;
    }
    const sendLocation = () => {
      navigator.geolocation && navigator.geolocation.getCurrentPosition(
        pos => {
          fetch(`${API_URL}/volunteers/${volunteerId}/location`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ lat: pos.coords.latitude, lon: pos.coords.longitude }),
          }).catch(() => {});
        },
        () => {}
      );
    };
    sendLocation();
    locationIntervalRef.current = setInterval(sendLocation, 20000);
    return () => clearInterval(locationIntervalRef.current);
  }, [volunteerId, activeRoute?.id]);

  const fetchStats = async () => {
    if (!volunteerId) return;
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/stats`, { headers: authHeader });
      if (res.ok) setStats(await res.json());
    } catch {}
  };

  const handleAttemptDelivery = async (ticketId) => {
    if (!activeRoute) return;
    try {
      const res = await fetch(`${API_URL}/volunteers/route/${activeRoute.id}/attempt_delivery`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ volunteer_id: volunteerId, ticket_id: ticketId }),
      });
      if (!res.ok) { const e = await res.json(); alert(e.detail || 'Ошибка'); return; }
      const data = await res.json();
      setAttemptMsgs(prev => ({ ...prev, [ticketId]: `Попытка #${data.attempt_count} зарегистрирована` }));
    } catch { alert('Ошибка подключения'); }
  };

  const handlePhotoUpload = async (routeId, ticketId, file) => {
    setPhotoUploading(prev => ({ ...prev, [ticketId]: true }));
    try {
      const fd = new FormData();
      fd.append('file', file);
      const res = await fetch(`${API_URL}/volunteers/route/${routeId}/point/${ticketId}/photo`, {
        method: 'POST',
        body: fd,
      });
      if (!res.ok) { alert('Ошибка загрузки фото'); return; }
      alert('Фото загружено!');
    } catch { alert('Ошибка подключения'); }
    finally { setPhotoUploading(prev => ({ ...prev, [ticketId]: false })); }
  };

  const fetchMapData = async () => {
    try {
      const res = await fetch(`${API_URL}/volunteers/map`, { headers: authHeader });
      if (res.ok) setMapData(await res.json());
    } catch {}
  };

  const fetchActiveRoute = async () => {
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/active_route`, { headers: authHeader });
      if (!res.ok) return;
      const data = await res.json();
      setActiveRoute(data && data.id ? data : null);
    } catch {}
  };

  const handleTakeTask = async (lotId) => {
    if (!volunteerId) { alert('Необходима авторизация'); return; }
    setLoading(true);
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/start_route`, {
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
      const res = await fetch(`${API_URL}/volunteers/route/${activeRoute.id}/complete_point`, {
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
      const res = await fetch(`${API_URL}/volunteers/route/${activeRoute.id}/finish`, {
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
        <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
          {['', 'Выпечка', 'Овощи/Фрукты', 'Готовая еда', 'Молочные продукты'].map(cat => (
            <button
              key={cat}
              onClick={() => setFilterCategory(cat)}
              style={{
                padding: '4px 10px', fontSize: '0.75rem', borderRadius: 12,
                border: '1px solid', cursor: 'pointer',
                borderColor: filterCategory === cat ? '#4CAF50' : '#333',
                background: filterCategory === cat ? '#4CAF5022' : 'transparent',
                color: filterCategory === cat ? '#4CAF50' : '#888',
              }}
            >
              {cat || t('needy.filter_all')}
            </button>
          ))}
        </div>
        {mapData.shops.length === 0 && (
          <EmptyState icon="🗺️" title={t('empty.map_title')} description={t('empty.map_desc')} />
        )}
        {mapData.shops.flatMap(s =>
          s.lots
            .filter(lot => !filterCategory || lot.category === filterCategory)
            .map(lot => (
              <div key={lot.lot_id} className="task-card-mobile">
                {lot.photo && (
                  <img
                    src={`${API_URL}${lot.photo}`}
                    alt={lot.description}
                    className="lot-photo"
                    onError={(e) => { e.target.style.display = 'none'; }}
                  />
                )}
                <div className="task-info">
                  {lot.category && <span className="category-badge">{lot.category}</span>}
                  <h4>{s.name}</h4>
                  <p>{lot.description} — {lot.quantity} шт.</p>
                </div>
                <button
                  className="btn btn-primary"
                  disabled={loading || !!activeRoute}
                  onClick={() => handleTakeTask(lot.lot_id)}
                >
                  {t('volunteer.take')}
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
        <EmptyState
          icon="🚗"
          title={t('empty.route_title')}
          description={t('empty.route_desc')}
          action={t('empty.route_action')}
          onAction={() => setActiveTab('map')}
        />
      ) : (
        <>
          <div className="map-container-mobile mini-map">
            <YMaps query={{ apikey: process.env.REACT_APP_YANDEX_MAPS_API_KEY, load: 'package.full' }}>
              <RouteMapView points={points} />
            </YMaps>
          </div>
          <div className="navigator-card">
            <div className="route-header">
              <h2>{!isShopDone ? 'Заберите из магазина' : nextTicket ? 'Доставьте получателю' : 'Маршрут выполнен'}</h2>
              <span className="badge">В пути</span>
            </div>

            {(() => {
              const dest = !isShopDone ? shopPoint : nextTicket;
              if (!dest?.lat || !dest?.lon) return null;
              return (
                <button
                  className="btn btn-secondary btn-full"
                  style={{ marginBottom: 12 }}
                  onClick={() => openInNavigator(dest.lat, dest.lon)}
                >
                  🧭 Открыть в навигаторе
                </button>
              );
            })()}

            <div className="route-points">
              {points.map((p, i) => {
                const letter = p.kind === 'shop' ? 'A' : String.fromCharCode(65 + points.filter((x, j) => x.kind === 'ticket' && j <= i).length);
                return (
                  <div key={i} className={`point ${!p.done && p === (isShopDone ? (nextTicket || null) : shopPoint) ? 'current' : p.done ? 'done' : ''}`}>
                    <div className="point-icon">{letter}</div>
                    <div className="point-text" style={{ flex: 1 }}>
                      <p className="point-label">{p.kind === 'shop' ? 'Магазин' : 'Получатель'}</p>
                      <p className="point-addr">{p.description}</p>
                      {p.kind === 'ticket' && p.addr_detail && (
                        <p style={{ fontSize: '0.78rem', color: '#aaa', margin: '2px 0 0' }}>{p.addr_detail}</p>
                      )}
                      {p.kind === 'ticket' && p.attempt_count > 0 && (
                        <p style={{ color: '#f90', fontSize: '0.75rem', margin: '2px 0 0' }}>Попыток: {p.attempt_count}</p>
                      )}
                      {p.kind === 'ticket' && !p.done && isShopDone && (
                        <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                          <button className="btn-small btn-warning" onClick={() => handleAttemptDelivery(p.ticket_id)}>
                            Не открыли дверь
                          </button>
                          {attemptMsgs[p.ticket_id] && (
                            <span style={{ color: '#f90', fontSize: '0.75rem', alignSelf: 'center' }}>{attemptMsgs[p.ticket_id]}</span>
                          )}
                        </div>
                      )}
                      {p.kind === 'ticket' && p.done && (
                        <div style={{ marginTop: 6 }}>
                          <label className="btn-small" style={{ cursor: 'pointer', opacity: photoUploading[p.ticket_id] ? 0.6 : 1 }}>
                            {photoUploading[p.ticket_id] ? 'Загрузка...' : 'Добавить фото'}
                            <input type="file" accept="image/*" style={{ display: 'none' }}
                              onChange={e => e.target.files[0] && handlePhotoUpload(activeRoute.id, p.ticket_id, e.target.files[0])} />
                          </label>
                        </div>
                      )}
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
                  <div className="scanner-container">
                    <p style={{ textAlign: 'center', color: '#aaa', marginBottom: 8 }}>Наведите камеру на QR-код получателя</p>
                    <div id="qr-reader" style={{ width: '100%', borderRadius: 8, overflow: 'hidden' }}></div>
                    <button className="btn-small" style={{ marginTop: 10, width: '100%' }} onClick={() => setScanning(false)}>Отмена</button>
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
        {activeTab === 'stats' && (
          <div className="volunteer-tab">
            <h3>{t('volunteer.stats')}</h3>
            {!stats ? (
              <p className="empty-msg">{t('common.loading')}</p>
            ) : (
              <div className="stats-row" style={{ flexWrap: 'wrap' }}>
                <div className="v-stat">
                  <span>{stats.total_routes}</span>
                  {t('volunteer.total_routes')}
                </div>
                <div className="v-stat">
                  <span>{stats.total_deliveries}</span>
                  {t('volunteer.total_deliveries')}
                </div>
                <div className="v-stat">
                  <span>{stats.total_kg}</span>
                  {t('volunteer.total_kg')}
                </div>
                <div className="v-stat">
                  <span>{stats.avg_rating ? stats.avg_rating.toFixed(1) : '—'}</span>
                  {t('volunteer.rating')} {stats.rating_count > 0 && `(${stats.rating_count})`}
                </div>
              </div>
            )}
          </div>
        )}
        {activeTab === 'history' && (
          <div className="volunteer-tab">
            <h3>{t('volunteer.my_routes')}</h3>
            <div className="stats-row">
              <div className="v-stat"><span>{routes.length}</span> {t('volunteer.total_routes')}</div>
              <div className="v-stat"><span>{routes.filter(r=>r.status==='finished').length}</span> {t('volunteer.completed_count')}</div>
              {volunteerRating?.average && (
                <div className="v-stat">
                  <span>{'★'.repeat(Math.round(volunteerRating.average))}{'☆'.repeat(5 - Math.round(volunteerRating.average))}</span>
                  <span style={{ fontSize: '0.8em', color: '#aaa' }}>{volunteerRating.average} ({volunteerRating.count})</span>
                </div>
              )}
              <div className="v-stat" style={{ justifyContent: 'center', gap: 6 }}>
                {tgLink ? (
                  <a href={tgLink.link} target="_blank" rel="noreferrer"
                    style={{ fontSize: '0.78rem', color: '#5dade2', textDecoration: 'none', fontWeight: 600 }}>
                    @{tgLink.bot_name}
                  </a>
                ) : (
                  <button className="btn-small" onClick={async () => {
                    setTgLoading(true);
                    try {
                      const res = await fetch(`${API_URL}/auth/telegram/init-link`, { headers: authHeader });
                      if (res.ok) setTgLink(await res.json());
                    } finally { setTgLoading(false); }
                  }} disabled={tgLoading} style={{ fontSize: '0.75rem' }}>
                    {tgLoading ? '...' : 'Telegram'}
                  </button>
                )}
                <span style={{ fontSize: '0.78rem', color: '#555' }}>{t('common.notifications')}</span>
              </div>
            </div>
          {routes.length === 0 ? (
            <EmptyState icon="📋" title={t('empty.history_title')} description={t('empty.history_desc')} />
          ) : routes.map(r => (
              <div key={r.id} className="task-card-mobile">
                <div className="task-info">
                  <p>{t('volunteer.route')} #{r.id}</p>
                  <p>{t('common.status')}: {r.status === 'finished' ? t('volunteer.status_finished') : r.status === 'timed_out' ? t('volunteer.status_timed_out') : t('volunteer.status_in_progress')}</p>
                  <p>{new Date(r.started_at).toLocaleDateString()}</p>
                </div>
              </div>
            ))}
          </div>
        )}
      </main>

      <nav className="mobile-nav">
        <button className={activeTab === 'map' ? 'active' : ''} onClick={() => setActiveTab('map')}>{t('volunteer.map')}</button>
        <button className={activeTab === 'route' ? 'active' : ''} onClick={() => { setActiveTab('route'); fetchActiveRoute(); }}>{t('volunteer.route')}</button>
        <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>{t('volunteer.history')}</button>
        <button className={activeTab === 'stats' ? 'active' : ''} onClick={() => { setActiveTab('stats'); fetchStats(); }}>{t('volunteer.stats')}</button>
      </nav>
    </div>
  );
};

export default VolunteerDashboard;
