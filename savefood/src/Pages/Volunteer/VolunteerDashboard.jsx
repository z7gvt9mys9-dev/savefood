import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Html5Qrcode } from 'html5-qrcode';
import { YMaps, Map, Placemark, useYMaps } from '@pbe/react-yandex-maps';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import EmptyState from '../../components/EmptyState';
import AccountLinks from '../../components/AccountLinks';
import PushToggle from '../../components/PushToggle';
import OnboardingChecklist from '../../components/OnboardingChecklist';
import TicketChat from '../../components/TicketChat';
import './Volunteer.css';

const CAT_KEYS = {
  'Выпечка': 'bakery',
  'Овощи/Фрукты': 'vegetables',
  'Готовая еда': 'prepared',
  'Молочные продукты': 'dairy',
};

// Yandex Maps' balloonContent renders raw HTML, so any string interpolated
// here must be escaped to prevent stored XSS via shop name / lot description.
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (ch) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[ch]));

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
          properties={{ balloonContent: escapeHtml(p.description || '') }}
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
  const [stats, setStats] = useState(null);
  const [volunteerInfo, setVolunteerInfo] = useState(null);
  const [availability, setAvailability] = useState([]);
  const [isOnline, setIsOnline] = useState(typeof navigator === 'undefined' ? true : navigator.onLine);
  const [thanks, setThanks] = useState(null);
  const [attemptMsgs, setAttemptMsgs] = useState({});
  const [leaderboard, setLeaderboard] = useState(null);
  const [team, setTeam] = useState(undefined); // undefined=loading, null=no team
  const [teamName, setTeamName] = useState('');
  const [teamCode, setTeamCode] = useState('');
  const [teamBusy, setTeamBusy] = useState(false);
  const locationWatchRef = useRef(null);
  const locationIntervalRef = useRef(null);
  const qrScannerRef = useRef(null);
  // The scanner effect only depends on `scanning`, so its decode callback would
  // capture a stale nextTicket if the route updates mid-scan — read via ref.
  const nextTicketRef = useRef(null);

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
        const current = nextTicketRef.current;
        // SF-{id} or SF-{id}-{secret}; the full string (incl. secret) goes to
        // the server for verification, match[1] only routes it to the right stop.
        const match = decodedText.match(/^SF-(\d+)(?:-[A-Za-z0-9_-]+)?$/);
        if (match && current && parseInt(match[1]) === current.ticket_id) {
          await handleCompletePoint(current.ticket_id, { qrCode: decodedText });
          setScanning(false);
        } else {
          alert(t('volunteer.error_qr', { id: current?.ticket_id ?? '?' }));
          setScanning(false);
        }
      },
      () => {}
    ).catch(() => { setScanning(false); });
    return stopScanner;
  }, [scanning]);

  // Single place that (re)loads the volunteer account into volunteerInfo —
  // used by the initial load, the KYC upload, and the stats refresh so the
  // fetch+set isn't copy-pasted (and can't diverge in error handling).
  const refreshVolunteerInfo = async () => {
    if (!volunteerId) return;
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}`, { headers: authHeader });
      if (res.ok) setVolunteerInfo(await res.json());
    } catch { /* offline */ }
  };

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
      // KYC (§58): load the account so the verification banner can show when
      // the volunteer is not yet 'approved' and routes are blocked.
      refreshVolunteerInfo();
    }
  }, [volunteerId]);

  const [kycBusy, setKycBusy] = useState(false);
  const uploadKycDocument = async (file) => {
    if (!file || !volunteerId) return;
    setKycBusy(true);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/document/upload`, {
        method: 'POST', headers: authHeader, body: form,
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        alert(err.detail || t('volunteer.kyc_upload_error'));
      } else {
        alert(t('volunteer.kyc_uploaded'));
        await refreshVolunteerInfo();
      }
    } catch {
      alert(t('volunteer.kyc_upload_error'));
    } finally {
      setKycBusy(false);
    }
  };

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
            headers: { 'Content-Type': 'application/json', ...authHeader },
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

  useEffect(() => {
    const on = () => setIsOnline(true);
    const off = () => setIsOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => { window.removeEventListener('online', on); window.removeEventListener('offline', off); };
  }, []);

  // Keep the availability editor in sync with whatever the profile fetch returns.
  useEffect(() => {
    if (Array.isArray(volunteerInfo?.availability)) setAvailability(volunteerInfo.availability);
  }, [volunteerInfo]);

  const saveAvailability = async () => {
    if (!volunteerId) return;
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ availability }),
      });
      if (res.ok) alert(t('volunteer.availability_saved'));
    } catch { /* offline */ }
  };

  const toggleThermalBag = async (checked) => {
    if (!volunteerId) return;
    setVolunteerInfo(v => ({ ...(v || {}), has_thermal_bag: checked }));
    try {
      await fetch(`${API_URL}/volunteers/${volunteerId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ has_thermal_bag: checked }),
      });
    } catch {
      setVolunteerInfo(v => ({ ...(v || {}), has_thermal_bag: !checked }));
    }
  };

  const fetchStats = async () => {
    if (!volunteerId) return;
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/stats`, { headers: authHeader });
      if (res.ok) setStats(await res.json());
    } catch {}
    await refreshVolunteerInfo();
    // Leaderboards are public impact endpoints — no auth header needed.
    try {
      const [citiesRes, volsRes] = await Promise.all([
        fetch(`${API_URL}/impact/cities`),
        fetch(`${API_URL}/impact/volunteers`),
      ]);
      setLeaderboard({
        cities: citiesRes.ok ? await citiesRes.json() : [],
        volunteers: volsRes.ok ? await volsRes.json() : [],
      });
    } catch {}
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/team`, { headers: authHeader });
      if (res.ok) setTeam((await res.json()).team);
    } catch {}
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/thanks`, { headers: authHeader });
      if (res.ok) setThanks(await res.json());
    } catch {}
  };

  const teamAction = async (path, body) => {
    setTeamBusy(true);
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/team/${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: body ? JSON.stringify(body) : undefined,
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { alert(data.detail || t('common.error')); return; }
      setTeam(path === 'leave' ? null : data.team);
      setTeamName(''); setTeamCode('');
    } catch { alert(t('common.connection_error')); }
    finally { setTeamBusy(false); }
  };

  const handleAttemptDelivery = async (ticketId) => {
    if (!activeRoute) return;
    try {
      const res = await fetch(`${API_URL}/volunteers/route/${activeRoute.id}/attempt_delivery`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ volunteer_id: volunteerId, ticket_id: ticketId }),
      });
      if (!res.ok) { const e = await res.json(); alert(e.detail || t('common.error')); return; }
      const data = await res.json();
      setAttemptMsgs(prev => ({ ...prev, [ticketId]: t('volunteer.attempt_registered', { count: data.attempt_count }) }));
    } catch { alert(t('common.connection_error')); }
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
    if (!volunteerId) { alert(t('volunteer.error_auth')); return; }
    setLoading(true);
    try {
      const res = await fetch(`${API_URL}/volunteers/${volunteerId}/start_route`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ lot_id: lotId }),
      });
      if (!res.ok) { const e = await res.json(); alert(e.detail || t('volunteer.error_start_route')); return; }
      await fetchActiveRoute();
      setActiveTab('route');
    } catch {
      alert(t('common.connection_error'));
    } finally {
      setLoading(false);
    }
  };

  const getCurrentPosition = () => new Promise((resolve) => {
    if (!navigator.geolocation) { resolve(null); return; }
    navigator.geolocation.getCurrentPosition(
      pos => resolve({ lat: pos.coords.latitude, lon: pos.coords.longitude }),
      () => resolve(null),
      { timeout: 8000, enableHighAccuracy: true }
    );
  });

  const handleCompletePoint = async (ticketId = null, { qrCode = null } = {}) => {
    if (!activeRoute) return;
    const body = { volunteer_id: volunteerId, ticket_id: ticketId };
    // Every point completion is GPS-verified server-side (§13): ticket points
    // additionally require the recipient's QR, shop point requires presence
    // at the shop (otherwise «Я забрал» from home would disarm the §27 antifraud).
    if (ticketId != null) {
      body.qr_code = qrCode;
    }
    const pos = await getCurrentPosition();
    if (!pos) {
      alert(t('volunteer.gps_no_location'));
      return;
    }
    body.lat = pos.lat;
    body.lon = pos.lon;
    try {
      const res = await fetch(`${API_URL}/volunteers/route/${activeRoute.id}/complete_point`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify(body),
      });
      if (!res.ok) { const e = await res.json(); alert(e.detail || t('common.error')); return; }
      await fetchActiveRoute();
    } catch {
      alert(t('common.connection_error'));
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
  nextTicketRef.current = nextTicket;

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

  const CATEGORIES = ['', 'Выпечка', 'Овощи/Фрукты', 'Готовая еда', 'Молочные продукты'];

  const renderMap = () => (
    <div className="volunteer-tab">
      <div className="map-container-mobile">
        <YMaps query={{ apikey: import.meta.env.VITE_YANDEX_MAPS_API_KEY }}>
          <Map defaultState={{ center: [55.75, 37.62], zoom: 12 }} width="100%" height="100%">
            {mapData.shops.map(s => s.lat && s.lon && (
              <Placemark
                key={`shop-${s.shop_id}`}
                geometry={[s.lat, s.lon]}
                properties={{ balloonContent: `<strong>${escapeHtml(s.name)}</strong><br/>${s.lots.map(l => escapeHtml(l.description)).join(', ')}` }}
              />
            ))}
            {mapData.tickets.map(tick => tick.lat && tick.lon && (
              <Placemark
                key={`ticket-${tick.ticket_id}`}
                geometry={[tick.lat, tick.lon]}
                properties={{ balloonContent: escapeHtml(tick.items || t('common.description')) }}
                options={{ preset: 'islands#greenCircleDotIcon' }}
              />
            ))}
          </Map>
        </YMaps>
      </div>
      <div className="task-list-mobile">
        <OnboardingChecklist
          storageKey="volunteer"
          items={[
            { id: 'route', label: t('onboarding.volunteer_first_route'), done: routes.length > 0 || !!activeRoute },
            { id: 'delivery', label: t('onboarding.volunteer_first_delivery'), done: routes.some(r => r.status === 'finished') },
          ]}
        />
        <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
          {CATEGORIES.map(cat => (
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
              {cat ? t(`categories.${CAT_KEYS[cat]}`, { defaultValue: cat }) : t('needy.filter_all')}
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
                  {lot.category && <span className="category-badge">{t(`categories.${CAT_KEYS[lot.category]}`, { defaultValue: lot.category })}</span>}
                  {s.kind === 'private' && (
                    <span className="category-badge" style={{ background: '#FF980022', color: '#FFB74D', borderColor: '#FF980044', marginLeft: 4 }}>
                      🏠 {t('donor.badge')}
                    </span>
                  )}
                  <h4>{s.name}</h4>
                  <p>{lot.description} — {lot.quantity} {t('volunteer.qty_pcs')}</p>
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
          {!isOnline && (
            <div className="offline-banner">📴 {t('volunteer.offline_route')}</div>
          )}
          <div className="map-container-mobile mini-map">
            <YMaps query={{ apikey: import.meta.env.VITE_YANDEX_MAPS_API_KEY, load: 'package.full' }}>
              <RouteMapView points={points} />
            </YMaps>
          </div>
          <div className="navigator-card">
            <div className="route-header">
              <h2>{!isShopDone ? t('volunteer.pickup_at_shop') : nextTicket ? t('volunteer.deliver_to_recipient') : t('volunteer.route_complete_msg')}</h2>
              <span className="badge">{t('volunteer.in_transit')}</span>
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
                  🧭 {t('volunteer.open_navigator')}
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
                      <p className="point-label">{p.kind === 'shop' ? t('volunteer.shop_label') : t('volunteer.recipient_label')}</p>
                      <p className="point-addr">{p.description}</p>
                      {p.kind === 'ticket' && p.addr_detail && (
                        <p style={{ fontSize: '0.78rem', color: '#aaa', margin: '2px 0 0' }}>{p.addr_detail}</p>
                      )}
                      {p.kind === 'ticket' && p.attempt_count > 0 && (
                        <p style={{ color: '#f90', fontSize: '0.75rem', margin: '2px 0 0' }}>{t('volunteer.attempts_count', { count: p.attempt_count })}</p>
                      )}
                      {p.kind === 'ticket' && !p.done && isShopDone && (
                        <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                          <button className="btn-small btn-warning" onClick={() => handleAttemptDelivery(p.ticket_id)}>
                            {t('volunteer.no_answer')}
                          </button>
                          {attemptMsgs[p.ticket_id] && (
                            <span style={{ color: '#f90', fontSize: '0.75rem', alignSelf: 'center' }}>{attemptMsgs[p.ticket_id]}</span>
                          )}
                        </div>
                      )}
                    </div>
                    {p.done && <span style={{ color: '#0f0', marginLeft: 'auto' }}>✓</span>}
                  </div>
                );
              })}
            </div>

            {isShopDone && nextTicket?.ticket_id && (
              <div style={{ marginTop: 12 }}>
                <h4 style={{ margin: '0 0 4px' }}>{t('volunteer.chat_title')}</h4>
                <TicketChat ticketId={nextTicket.ticket_id} token={user?.token} me="volunteer" ns="volunteer" />
              </div>
            )}

            <div className="navigation-actions">
              {!isShopDone ? (
                <button className="btn btn-primary btn-full" onClick={() => handleCompletePoint(null)}>
                  {t('volunteer.i_picked_up')}
                </button>
              ) : nextTicket ? (
                scanning ? (
                  <div className="scanner-container">
                    <p style={{ textAlign: 'center', color: '#aaa', marginBottom: 8 }}>{t('volunteer.scan_camera_hint')}</p>
                    <div id="qr-reader" style={{ width: '100%', borderRadius: 8, overflow: 'hidden' }}></div>
                    <button className="btn-small" style={{ marginTop: 10, width: '100%' }} onClick={() => setScanning(false)}>{t('common.cancel')}</button>
                  </div>
                ) : (
                  <div>
                    {gpsStatus === 'checking' && <p style={{ color: '#aaa', textAlign: 'center' }}>{t('volunteer.gps_locating')}</p>}
                    {gpsStatus === 'far' && <p style={{ color: '#f90', textAlign: 'center' }}>{t('volunteer.gps_far_detail')}</p>}
                    {gpsStatus === 'error' && <p style={{ color: '#fa0', textAlign: 'center' }}>{t('volunteer.gps_no_location')}</p>}
                    <button
                      className="btn btn-primary btn-full"
                      disabled={gpsStatus === 'checking' || gpsStatus === 'far'}
                      onClick={async () => {
                        const status = await checkGPS();
                        if (status === 'ok' || status === 'error') setScanning(true);
                      }}
                    >
                      {t('volunteer.scan_qr')}
                    </button>
                  </div>
                )
              ) : (
                <button className="btn btn-primary btn-full" onClick={handleFinishRoute}>
                  {t('volunteer.finish_route')}
                </button>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );

  const kycStatus = volunteerInfo?.status;
  const renderKycBanner = () => {
    if (!volunteerInfo || kycStatus === 'approved') return null;
    const rejected = kycStatus === 'rejected';
    return (
      <div style={{
        background: rejected ? '#3a1a1a' : '#2a2416',
        border: `1px solid ${rejected ? '#5a2a2a' : '#4a4020'}`,
        borderRadius: 12, padding: 14, margin: '10px 12px',
      }}>
        <strong style={{ display: 'block', marginBottom: 6 }}>
          {rejected ? `⚠️ ${t('volunteer.kyc_rejected_title')}` : `🪪 ${t('volunteer.kyc_pending_title')}`}
        </strong>
        <p style={{ fontSize: '0.82rem', color: '#bbb', margin: '0 0 10px' }}>
          {rejected ? t('volunteer.kyc_rejected_hint') : t('volunteer.kyc_pending_hint')}
        </p>
        <label className="btn-small btn-primary" style={{ cursor: kycBusy ? 'wait' : 'pointer', display: 'inline-block' }}>
          {kycBusy ? '…' : t('volunteer.kyc_upload')}
          <input type="file" accept="image/*,.pdf" disabled={kycBusy} style={{ display: 'none' }}
            onChange={(e) => uploadKycDocument(e.target.files?.[0])} />
        </label>
      </div>
    );
  };

  return (
    <div className="mobile-container">
      <main className="mobile-content">
        {renderKycBanner()}
        {activeTab === 'map' && renderMap()}
        {activeTab === 'route' && renderRoute()}
        {activeTab === 'stats' && (
          <div className="volunteer-tab">
            <h3>{t('volunteer.stats')}</h3>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={!!volunteerInfo?.has_thermal_bag}
                onChange={(e) => toggleThermalBag(e.target.checked)}
              />
              <span>❄️ {t('volunteer.thermal_bag')}</span>
            </label>
            <p style={{ fontSize: '0.78rem', color: '#888', marginTop: -8, marginBottom: 14 }}>{t('volunteer.thermal_bag_hint')}</p>

            <div style={{ background: '#1a1a26', border: '1px solid #2a2a3a', borderRadius: 12, padding: 12, marginBottom: 16 }}>
              <h4 style={{ margin: '0 0 4px' }}>🗓️ {t('volunteer.availability_title')}</h4>
              <p style={{ fontSize: '0.78rem', color: '#888', margin: '0 0 10px' }}>{t('volunteer.availability_hint')}</p>
              {availability.length === 0 && (
                <p style={{ fontSize: '0.8rem', color: '#aaa' }}>{t('volunteer.availability_empty')}</p>
              )}
              {availability.map((w, i) => (
                <div className="avail-row" key={i}>
                  <select value={w.day} onChange={e => setAvailability(a => a.map((x, j) => j === i ? { ...x, day: Number(e.target.value) } : x))}>
                    {[0,1,2,3,4,5,6].map(d => <option key={d} value={d}>{t(`volunteer.day_${d}`)}</option>)}
                  </select>
                  <input type="time" value={w.start} onChange={e => setAvailability(a => a.map((x, j) => j === i ? { ...x, start: e.target.value } : x))} />
                  <span>—</span>
                  <input type="time" value={w.end} onChange={e => setAvailability(a => a.map((x, j) => j === i ? { ...x, end: e.target.value } : x))} />
                  <button className="btn-small btn-danger" onClick={() => setAvailability(a => a.filter((_, j) => j !== i))}>✕</button>
                </div>
              ))}
              <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
                <button className="btn-small" onClick={() => setAvailability(a => [...a, { day: 1, start: '18:00', end: '21:00' }])}>+ {t('volunteer.availability_add')}</button>
                <button className="btn-small btn-success" onClick={saveAvailability}>{t('volunteer.availability_save')}</button>
              </div>
            </div>
            {!stats ? (
              <p className="empty-msg">{t('common.loading')}</p>
            ) : (
              <>
                {stats.level && (
                  <div style={{ background: '#4CAF5012', border: '1px solid #4CAF5044', borderRadius: 12, padding: '12px 14px', marginBottom: 14 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', flexWrap: 'wrap', gap: 6 }}>
                      <strong style={{ color: '#4CAF50' }}>
                        {t(`volunteer.level_${stats.level.code}`)}
                      </strong>
                      <span style={{ fontSize: '0.8rem', color: '#aaa' }}>
                        {t('volunteer.level_points', { count: Math.round(stats.level.points) })}
                      </span>
                    </div>
                    <div style={{ height: 8, borderRadius: 4, background: '#333', marginTop: 8, overflow: 'hidden' }}>
                      <div style={{ height: '100%', width: `${Math.round(stats.level.progress * 100)}%`, background: '#4CAF50', transition: 'width .4s' }} />
                    </div>
                    {stats.level.next_code && (
                      <p style={{ fontSize: '0.78rem', color: '#aaa', margin: '6px 0 0' }}>
                        {t('volunteer.level_next', {
                          level: t(`volunteer.level_${stats.level.next_code}`),
                          points: Math.ceil(stats.level.points_to_next),
                        })}
                      </p>
                    )}
                  </div>
                )}
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
                {thanks && thanks.length > 0 && (
                  <>
                    <h4 style={{ margin: '18px 0 8px' }}>💌 {t('volunteer.thanks_title')}</h4>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {thanks.map((th, i) => (
                        <div key={i} style={{ background: '#E91E6310', border: '1px solid #E91E6333', borderRadius: 12, padding: '10px 12px' }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
                            <span style={{ color: '#FFC107', fontSize: '0.9rem', letterSpacing: 1 }}>
                              {'★'.repeat(th.rating)}{'☆'.repeat(5 - th.rating)}
                            </span>
                            {th.created_at && (
                              <span style={{ fontSize: '0.72rem', color: '#888' }}>{new Date(th.created_at).toLocaleDateString()}</span>
                            )}
                          </div>
                          <p style={{ margin: '6px 0 0', fontSize: '0.9rem', lineHeight: 1.4 }}>“{th.comment}”</p>
                          {th.category && (
                            <span className="category-badge" style={{ fontSize: '0.72rem', marginTop: 6, display: 'inline-block' }}>
                              {t(`categories.${CAT_KEYS[th.category]}`, { defaultValue: th.category })}
                            </span>
                          )}
                        </div>
                      ))}
                    </div>
                  </>
                )}
                <h4 style={{ margin: '18px 0 8px' }}>{t('volunteer.achievements')}</h4>
                {(stats.achievements || []).length === 0 ? (
                  <p className="empty-msg" style={{ fontSize: '0.85rem' }}>{t('volunteer.achievements_empty')}</p>
                ) : (
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    {stats.achievements.map(a => (
                      <span key={a} className="category-badge" style={{ fontSize: '0.85rem', padding: '6px 12px' }}>
                        {t(`volunteer.ach_${a}`)}
                      </span>
                    ))}
                  </div>
                )}
                <h4 style={{ margin: '18px 0 8px' }}>{t('volunteer.team_title')}</h4>
                {team === undefined ? (
                  <p className="empty-msg" style={{ fontSize: '0.85rem' }}>{t('common.loading')}</p>
                ) : team ? (
                  <div style={{ background: '#2196F312', border: '1px solid #2196F344', borderRadius: 12, padding: '12px 14px' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: 6 }}>
                      <strong style={{ color: '#64B5F6' }}>🏢 {team.name}</strong>
                      <span style={{ fontSize: '0.8rem', color: '#aaa' }}>
                        {t('volunteer.team_members', { count: team.members })}
                      </span>
                    </div>
                    <p style={{ fontSize: '0.85rem', margin: '8px 0 4px' }}>
                      {team.deliveries} {t('volunteer.total_deliveries').toLowerCase()} · {Math.round(team.kg)} {t('volunteer.total_kg').toLowerCase()}
                    </p>
                    <p style={{ fontSize: '0.8rem', color: '#aaa', margin: '4px 0 8px' }}>
                      {t('volunteer.team_code_hint')}: <code style={{ color: '#64B5F6' }}>{team.join_code}</code>
                    </p>
                    <button className="btn-small btn-warning" disabled={teamBusy}
                      onClick={() => window.confirm(t('volunteer.team_leave_confirm')) && teamAction('leave')}>
                      {t('volunteer.team_leave')}
                    </button>
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    <p style={{ fontSize: '0.82rem', color: '#aaa', margin: 0 }}>{t('volunteer.team_intro')}</p>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      <input type="text" placeholder={t('volunteer.team_code_placeholder')} value={teamCode}
                        onChange={e => setTeamCode(e.target.value.toUpperCase())} style={{ flex: 1, minWidth: 120 }} />
                      <button className="btn-small btn-success" disabled={teamBusy || !teamCode.trim()}
                        onClick={() => teamAction('join', { code: teamCode })}>
                        {t('volunteer.team_join')}
                      </button>
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      <input type="text" placeholder={t('volunteer.team_name_placeholder')} value={teamName}
                        onChange={e => setTeamName(e.target.value)} style={{ flex: 1, minWidth: 120 }} />
                      <button className="btn-small" disabled={teamBusy || teamName.trim().length < 3}
                        onClick={() => teamAction('create', { name: teamName })}>
                        {t('volunteer.team_create')}
                      </button>
                    </div>
                  </div>
                )}
                {leaderboard && leaderboard.cities.length > 0 && (
                  <>
                    <h4 style={{ margin: '18px 0 8px' }}>{t('volunteer.leaderboard_cities')}</h4>
                    {leaderboard.cities.map((c, i) => (
                      <div key={c.city} style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 4px', borderBottom: '1px solid #2a2a3a', fontSize: '0.9rem' }}>
                        <span>{i + 1}. {c.city}</span>
                        <span style={{ color: '#4CAF50' }}>{Math.round(c.kg)} {t('volunteer.total_kg').toLowerCase()}</span>
                      </div>
                    ))}
                  </>
                )}
                {leaderboard && leaderboard.volunteers.length > 0 && (
                  <>
                    <h4 style={{ margin: '18px 0 8px' }}>{t('volunteer.leaderboard_volunteers')}</h4>
                    {leaderboard.volunteers.map((v, i) => (
                      <div key={v.id} style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 4px', borderBottom: '1px solid #2a2a3a', fontSize: '0.9rem' }}>
                        <span>
                          {['🥇', '🥈', '🥉'][i] || `${i + 1}.`} {v.name}
                          <span style={{ color: '#888', fontSize: '0.78rem' }}> · {t(`volunteer.level_${v.level}`)}</span>
                        </span>
                        <span style={{ color: '#aaa' }}>{v.deliveries} {t('volunteer.total_deliveries').toLowerCase()}</span>
                      </div>
                    ))}
                  </>
                )}
              </>
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
            </div>
            <AccountLinks dashboardPath="/volunteer" />
            <PushToggle />
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
