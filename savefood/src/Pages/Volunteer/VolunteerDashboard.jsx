import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Html5Qrcode, Html5QrcodeSupportedFormats } from 'html5-qrcode';
import { YMaps, Map, Placemark, useYMaps } from '@pbe/react-yandex-maps';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import { buildNavigatorUrls, haversineMeters } from '../../utils/geo';
import { hasValidCoordinates } from '../../utils/ticket';
import EmptyState from '../../components/EmptyState';
import AccountLinks from '../../components/AccountLinks';
import PushToggle from '../../components/PushToggle';
import OnboardingChecklist from '../../components/OnboardingChecklist';
import TicketChat from '../../components/TicketChat';
import MonoIcon from '../../components/MonoIcon';
import './Volunteer.css';

const CAT_KEYS = {
  'Выпечка': 'bakery',
  'Овощи/Фрукты': 'vegetables',
  'Готовая еда': 'prepared',
  'Молочные продукты': 'dairy',
};

const ACHIEVEMENT_ICONS = {
  first_delivery: 'award',
  kg_100: 'bag',
  night_courier: 'wait',
  sprinter: 'bicycle',
};

const LEVEL_ICONS = {
  novice: 'leaf',
  helper: 'users',
  courier: 'bicycle',
  guardian: 'shield',
  city_hero: 'award',
};

// Yandex Maps' balloonContent renders raw HTML, so any string interpolated
// here must be escaped to prevent stored XSS via shop name / lot description.
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (ch) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[ch]));

const isMobileDevice = () =>
  (typeof window !== 'undefined' && window.Capacitor?.isNativePlatform?.()) ||
  /Android|iPhone|iPad|iPod/i.test(navigator.userAgent || '');

// Open the device's navigation app routed to (lat, lon). On mobile we try the
// Yandex Navigator deep link and fall back to web Yandex Maps if the app isn't
// installed (the page stays visible, so the timeout fires). On desktop there is
// no nav app, so we just open the web route — the on-site map is the real fallback.
//
// `stops` is the remaining itinerary, nearest first. Web Yandex Maps takes the
// whole thing (`rtext=` accepts `~`-separated waypoints) and gives a real
// traffic-aware route for the entire trip; the Navigator deep link only accepts
// a single destination, so on mobile we hand it the next stop and the driver
// re-opens it at each point.
const openInNavigator = (stops) => {
  const urls = buildNavigatorUrls(stops);
  if (!urls) return;
  if (!isMobileDevice()) {
    window.open(urls.web, '_blank', 'noopener');
    return;
  }
  // Try the Navigator app; if nothing takes over the page within 1.5 s it is not
  // installed, so fall back to web maps.
  let opened = false;
  const onHide = () => { opened = true; };
  document.addEventListener('visibilitychange', onHide, { once: true });
  const fallback = setTimeout(() => {
    document.removeEventListener('visibilitychange', onHide);
    if (!opened && document.visibilityState === 'visible') window.location.href = urls.web;
  }, 1500);
  window.location.href = urls.app;
  setTimeout(() => clearTimeout(fallback), 4000);
};

const displayRouteAddress = (point) => {
  if (point.kind === 'ticket') {
    return point.address || point.recipient_address || point.description || '';
  }
  return point.address || point.description || '';
};

const RoutePointIcon = ({ kind }) => (
  <span className="point-kind-icon" aria-hidden="true">
    {kind === 'shop' ? (
      <svg viewBox="0 0 24 24" fill="none">
        <path d="M4 10.5V20h16v-9.5M3 9l2-5h14l2 5" />
        <path d="M3 9a2.5 2.5 0 0 0 5 0 2.5 2.5 0 0 0 5 0 2.5 2.5 0 0 0 5 0 2.5 2.5 0 0 0 3-2" />
        <path d="M9 20v-5h6v5" />
      </svg>
    ) : (
      <svg viewBox="0 0 24 24" fill="none">
        <path d="M3 11.5 12 4l9 7.5" />
        <path d="M5.5 10v10h13V10M9.5 20v-6h5v6" />
      </svg>
    )}
  </span>
);

const RouteStatusCheckbox = ({ done, current }) => (
  <span
    className={`point-checkbox${done ? ' is-checked' : current ? ' is-current' : ''}`}
    role="checkbox"
    aria-checked={done}
    aria-readonly="true"
  >
    {done && (
      <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
        <path d="m5 10.5 3.1 3L15.5 6" />
      </svg>
    )}
    {!done && current && <span className="point-checkbox-dot" aria-hidden="true" />}
  </span>
);

const NavigatorIcon = () => (
  <svg className="navigator-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
    <path d="M16.2 3.8 11.8 16l-2.5-5.3L4 8.2l12.2-4.4Z" />
  </svg>
);

const mapLotId = (lot) => lot?.lot_id ?? lot?.lotId ?? lot?.id ?? null;
const ticketLotId = (ticket) => ticket?.lot_id ?? ticket?.lotId ?? null;
const isReservedMapLot = (lot) => lot?.status === 'reserved'
  || lot?.reserved === true
  || (Number(lot?.quantity) <= 0
    && (Array.isArray(lot?.open_ticket_ids) ? lot.open_ticket_ids.length > 0 : Number(lot?.open_ticket_count) > 0));

// Inner component — must be rendered inside <YMaps> to use useYMaps
const RouteMapView = ({ points }) => {
  const ymaps = useYMaps(['multiRouter.MultiRoute']);
  const mapRef = useRef(null);
  const routeRef = useRef(null);
  const shopPoint = points.find(p => p.kind === 'shop');
  const center = hasValidCoordinates(shopPoint?.lat, shopPoint?.lon)
    ? [shopPoint.lat, shopPoint.lon]
    : [55.75, 37.62];

  useEffect(() => {
    if (!ymaps || !mapRef.current) return;
    // clean up previous route
    if (routeRef.current) {
      mapRef.current.geoObjects.remove(routeRef.current);
      routeRef.current = null;
    }
    const coords = points.filter(p => hasValidCoordinates(p.lat, p.lon)).map(p => [p.lat, p.lon]);
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
      {points.map((p, i) => hasValidCoordinates(p.lat, p.lon) && (
        <Placemark
          key={i}
          geometry={[p.lat, p.lon]}
          properties={{ balloonContent: escapeHtml(displayRouteAddress(p)) }}
          options={{ preset: p.kind === 'shop' ? 'islands#redShoppingIcon' : 'islands#greenHomeIcon' }}
        />
      ))}
    </Map>
  );
};

const VolunteerDashboard = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const volunteerId = user?.relatedId;
  const authHeader = { Authorization: `Bearer ${user?.token}` };

  const [activeTab, setActiveTab] = useState('map');
  const [mapData, setMapData] = useState({ shops: [], tickets: [] });
  const [filterCategory, setFilterCategory] = useState('');
  const [activeRoute, setActiveRoute] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [scanError, setScanError] = useState('');
  // Retain a scanned QR in memory only until the courier adds the proof photo.
  const [pendingDelivery, setPendingDelivery] = useState(null);
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
  // The scanner effect only depends on `scanning`, so its decode callback would
  // capture a stale nextTicket if the route updates mid-scan — read via ref.
  const nextTicketRef = useRef(null);

  // html5-qrcode's stop() may only run after start() has resolved: calling it
  // while the camera is still warming up (user taps «Отмена» right away) throws
  // synchronously and used to crash the dashboard. The cleanup below therefore
  // chains the stop onto the start promise instead of racing it.
  useEffect(() => {
    if (!scanning) return;
    let disposed = false;
    const scanner = new Html5Qrcode('qr-reader', {
      formatsToSupport: [Html5QrcodeSupportedFormats.QR_CODE],
    });
    let handled = false;
    const startPromise = scanner.start(
      { facingMode: 'environment' },
      {
        fps: 10,
        qrbox: (width, height) => {
          const size = Math.floor(Math.min(width, height) * 0.8);
          return { width: size, height: size };
        },
      },
      async (decodedText) => {
        if (handled) return; // decode keeps firing ~10 fps until the camera stops
        const current = nextTicketRef.current;
        // SF-{id} or SF-{id}-{secret}; the full string (incl. secret) goes to
        // the server for verification, match[1] only routes it to the right stop.
        const match = decodedText.match(/^SF-(\d+)(?:-[A-Za-z0-9_-]+)?$/);
        if (!match || !current || parseInt(match[1]) !== current.ticket_id) {
          setScanError(t('volunteer.error_qr', { id: current?.ticket_id ?? '?' }));
          return;
        }
        handled = true;
        setPendingDelivery({ ticketId: current.ticket_id, qrCode: decodedText });
        setScanning(false); // cleanup stops the camera
      },
      () => {}
    );
    startPromise.catch((error) => {
      console.error('Unable to start the delivery QR scanner', error);
      if (!disposed) {
        setScanError(t('volunteer.camera_error'));
        setScanning(false);
      }
    });
    return () => {
      disposed = true;
      startPromise
        .then(() => scanner.stop())
        .then(() => scanner.clear())
        .catch(() => {});
    };
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

  // Carrying capacity (§14): a lot is claimed whole, so the server refuses lots
  // heavier than this. Empty = no limit declared, which is the default.
  const setCapacity = async (value) => {
    if (!volunteerId) return;
    const capacity = value === '' ? null : Number(value);
    const previous = volunteerInfo?.capacity_kg ?? null;
    setVolunteerInfo(v => ({ ...(v || {}), capacity_kg: capacity }));
    try {
      await fetch(`${API_URL}/volunteers/${volunteerId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ capacity_kg: capacity }),
      });
    } catch {
      setVolunteerInfo(v => ({ ...(v || {}), capacity_kg: previous }));
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
    // A failed-delivery attempt is an on-site event too. The server verifies
    // this fix against the route point, so never submit a client-only attempt.
    const pos = await getCurrentPosition();
    if (!pos) {
      alert(t('volunteer.gps_no_location'));
      return;
    }
    try {
      const res = await fetch(`${API_URL}/volunteers/route/${activeRoute.id}/attempt_delivery`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({
          volunteer_id: volunteerId,
          ticket_id: ticketId,
          lat: pos.lat,
          lon: pos.lon,
        }),
      });
      if (!res.ok) { const e = await res.json(); alert(e.detail || t('common.error')); return; }
      const data = await res.json();
      setAttemptMsgs(prev => ({ ...prev, [ticketId]: t('volunteer.attempt_registered', { count: data.attempt_count }) }));
      await fetchActiveRoute();
    } catch { alert(t('common.connection_error')); }
  };

  const fetchMapData = async () => {
    try {
      const res = await fetch(`${API_URL}/volunteers/map`, { headers: authHeader });
      if (res.ok) {
        const data = await res.json();
        setMapData({
          shops: Array.isArray(data?.shops) ? data.shops : [],
          tickets: Array.isArray(data?.tickets) ? data.tickets : [],
        });
      }
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

  const handleCompletePoint = async (ticketId = null, { qrCode = null, proofFile = null } = {}) => {
    if (!activeRoute) return false;
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
      return false;
    }
    body.lat = pos.lat;
    body.lon = pos.lon;
    try {
      if (ticketId != null) {
        if (!proofFile) {
          alert(t('volunteer.photo_proof_required'));
          return false;
        }
        const form = new FormData();
        form.append('file', proofFile);
        form.append('lat', String(pos.lat));
        form.append('lon', String(pos.lon));
        const proofRes = await fetch(
          `${API_URL}/volunteers/route/${activeRoute.id}/ticket/${ticketId}/photo`,
          { method: 'POST', headers: authHeader, body: form },
        );
        if (!proofRes.ok) {
          const e = await proofRes.json().catch(() => ({}));
          alert(e.detail || t('common.error'));
          return false;
        }
      }
      const res = await fetch(`${API_URL}/volunteers/route/${activeRoute.id}/complete_point`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify(body),
      });
      if (!res.ok) { const e = await res.json(); alert(e.detail || t('common.error')); return false; }
      await fetchActiveRoute();
      return true;
    } catch {
      alert(t('common.connection_error'));
      return false;
    }
  };

  const handleDeliveryPhoto = async (file) => {
    if (!file || !pendingDelivery) return;
    setLoading(true);
    try {
      const completed = await handleCompletePoint(pendingDelivery.ticketId, {
        qrCode: pendingDelivery.qrCode,
        proofFile: file,
      });
      if (completed) setPendingDelivery(null);
    } finally {
      setLoading(false);
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
    if (!hasValidCoordinates(nextTicket?.lat, nextTicket?.lon)) { setGpsStatus('error'); return 'error'; }
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
  const mapShops = Array.isArray(mapData.shops) ? mapData.shops : [];
  const mapTickets = Array.isArray(mapData.tickets) ? mapData.tickets : [];
  const listedMapLotIds = new Set(mapShops
    .flatMap(shop => Array.isArray(shop.lots) ? shop.lots : [])
    .map(lot => mapLotId(lot))
    .filter(id => id !== null && id !== undefined)
    .map(String));
  // A new map API may expose a zero-quantity reservation through tickets before
  // it has a full shop/lot card. Keep that delivery task actionable instead of
  // rendering it as a marker only.
  const unlistedTicketTasks = mapTickets.filter(ticket => {
    const lotId = ticketLotId(ticket);
    return lotId !== null && lotId !== undefined && !listedMapLotIds.has(String(lotId));
  });

  const renderMap = () => (
    <div className="volunteer-tab">
      <div className="map-container-mobile">
        <YMaps query={{ apikey: import.meta.env.VITE_YANDEX_MAPS_API_KEY }}>
          <Map defaultState={{ center: [55.75, 37.62], zoom: 12 }} width="100%" height="100%">
            {mapShops.map(s => hasValidCoordinates(s.lat, s.lon) && (
              <Placemark
                key={`shop-${s.shop_id}`}
                geometry={[s.lat, s.lon]}
                properties={{ balloonContent: `<strong>${escapeHtml(s.name)}</strong><br/>${(Array.isArray(s.lots) ? s.lots : []).map(l => escapeHtml(l.description)).join(', ')}` }}
              />
            ))}
            {mapTickets.map(tick => hasValidCoordinates(tick.lat, tick.lon) && (
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
        {mapShops.length === 0 && mapTickets.length === 0 && (
          <EmptyState icon={<MonoIcon name="map" />} title={t('empty.map_title')} description={t('empty.map_desc')} />
        )}
        {mapShops.flatMap(s =>
          (Array.isArray(s.lots) ? s.lots : [])
            .filter(lot => !filterCategory || lot.category === filterCategory)
            .map(lot => {
              const lotId = mapLotId(lot);
              const reserved = isReservedMapLot(lot);
              const routeAvailable = lot.route_available !== false;
              return (
                <div key={`${s.shop_id}-${lotId}`} className="task-card-mobile">
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
                    {reserved && (
                      <span className="category-badge" style={{ background: '#4CAF5022', color: '#81C784', borderColor: '#4CAF5044', marginLeft: 4 }}>
                        {t('volunteer.reserved_for_delivery')}
                      </span>
                    )}
                    {s.kind === 'private' && (
                      <span className="category-badge" style={{ background: '#FF980022', color: '#FFB74D', borderColor: '#FF980044', marginLeft: 4 }}>
                        <MonoIcon name="home" /> {t('donor.badge')}
                      </span>
                    )}
                    <h4>{s.name}</h4>
                    <p>
                      {lot.description} — {reserved
                        ? t('volunteer.reserved_for_delivery')
                        : `${lot.quantity} ${t('volunteer.qty_pcs')}`}
                    </p>
                    {!routeAvailable && (
                      <p style={{ fontSize: '0.75rem', color: '#999', margin: '2px 0 0' }}>
                        {t('volunteer.no_delivery_requests')}
                      </p>
                    )}
                  </div>
                  <button
                    className="btn btn-primary"
                    disabled={loading || !!activeRoute || !routeAvailable || lotId === null || lotId === undefined}
                    onClick={() => handleTakeTask(lotId)}
                  >
                    {t('volunteer.take')}
                  </button>
                </div>
              );
            })
        )}
        {unlistedTicketTasks.map(ticket => {
          const lotId = ticketLotId(ticket);
          return (
            <div key={`reserved-ticket-${ticket.ticket_id}`} className="task-card-mobile">
              <div className="task-info">
                <span className="category-badge" style={{ background: '#4CAF5022', color: '#81C784', borderColor: '#4CAF5044' }}>
                  {t('volunteer.reserved_for_delivery')}
                </span>
                <h4>{ticket.shop_name || t('volunteer.delivery_task')}</h4>
                <p>{ticket.items || ticket.lot_description || t('common.description')}</p>
              </div>
              <button
                className="btn btn-primary"
                disabled={loading || !!activeRoute}
                onClick={() => handleTakeTask(lotId)}
              >
                {t('volunteer.take')}
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );

  const renderRoute = () => (
    <div className="volunteer-tab route-page">
      {!activeRoute ? (
        <EmptyState
          icon={<span className="empty-route-icon"><NavigatorIcon /></span>}
          title={t('empty.route_title')}
          description={t('empty.route_desc')}
          action={t('empty.route_action')}
          onAction={() => setActiveTab('map')}
        />
      ) : (
        <>
          {!isOnline && (
            <div className="offline-banner"><span className="offline-dot" aria-hidden="true" />{t('volunteer.offline_route')}</div>
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
              // Everything still ahead: the shop until pickup is confirmed, then
              // every undelivered stop in visiting order. On desktop this opens as
              // one multi-waypoint route instead of the next point only.
              const remaining = [
                ...(!isShopDone && shopPoint ? [shopPoint] : []),
                ...points.filter(p => p.kind === 'ticket' && !p.done),
              ].filter(p => hasValidCoordinates(p?.lat, p?.lon));
              if (remaining.length === 0) return null;
              return (
                <button
                  className="btn btn-secondary btn-full"
                  style={{ marginBottom: 12 }}
                  onClick={() => openInNavigator(remaining)}
                >
                  <NavigatorIcon />
                  {t('volunteer.open_navigator')}
                  {remaining.length > 1 && !isMobileDevice() && ` (${remaining.length})`}
                </button>
              );
            })()}

            <div className="route-points">
              {points.map((p, i) => {
                const address = displayRouteAddress(p);
                const recipientItems = p.kind === 'ticket' ? (p.items || p.description) : null;
                const hasRecipientAddress = p.kind === 'ticket' && Boolean(p.address || p.recipient_address);
                const isCurrent = !p.done && p === (isShopDone ? (nextTicket || null) : shopPoint);
                return (
                  <div key={i} className={`point${isCurrent ? ' current' : p.done ? ' done' : ''}`}>
                    <RoutePointIcon kind={p.kind} />
                    <div className="point-text">
                      <div className="point-meta">
                        <p className="point-label">{p.kind === 'shop' ? t('volunteer.shop_label') : t('volunteer.recipient_label')}</p>
                        <span className="point-sequence">{String(i + 1).padStart(2, '0')}</span>
                      </div>
                      <p className="point-addr">{address || t('needy.address_tbd')}</p>
                      {hasRecipientAddress && recipientItems && recipientItems !== address && (
                        <p className="point-detail point-items">
                          {t('needy.items_label')} {recipientItems}
                        </p>
                      )}
                      {p.kind === 'ticket' && p.addr_detail && (
                        <p className="point-detail">{p.addr_detail}</p>
                      )}
                      {p.kind === 'ticket' && p.attempt_count > 0 && (
                        <p className="point-attempts">{t('volunteer.attempts_count', { count: p.attempt_count })}</p>
                      )}
                      {p.kind === 'ticket' && !p.done && isShopDone && (
                        <div className="point-actions">
                          <button className="btn-small btn-warning" onClick={() => handleAttemptDelivery(p.ticket_id)}>
                            {t('volunteer.no_answer')}
                          </button>
                          {attemptMsgs[p.ticket_id] && (
                            <span className="point-attempt-message">{attemptMsgs[p.ticket_id]}</span>
                          )}
                        </div>
                      )}
                    </div>
                    <RouteStatusCheckbox done={Boolean(p.done)} current={isCurrent} />
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
                    {scanError && <p role="alert" style={{ color: '#e57373', margin: '8px 0 0' }}>{scanError}</p>}
                    <button className="btn-small" style={{ marginTop: 10, width: '100%' }} onClick={() => setScanning(false)}>{t('common.cancel')}</button>
                  </div>
                ) : pendingDelivery ? (
                  <div className="scanner-container">
                    <p style={{ textAlign: 'center', color: '#aaa', marginBottom: 8 }}>
                      {t('volunteer.photo_proof_hint')}
                    </p>
                    <label className="btn btn-primary btn-full" style={{ cursor: loading ? 'wait' : 'pointer', display: 'block' }}>
                      {loading ? '…' : t('volunteer.photo_proof_take')}
                      <input
                        type="file"
                        accept="image/jpeg,image/png"
                        capture="environment"
                        disabled={loading}
                        style={{ display: 'none' }}
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          e.target.value = '';
                          handleDeliveryPhoto(file);
                        }}
                      />
                    </label>
                    <button
                      className="btn-small"
                      style={{ marginTop: 10, width: '100%' }}
                      disabled={loading}
                      onClick={() => setPendingDelivery(null)}
                    >
                      {t('common.cancel')}
                    </button>
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
                        if (status === 'ok') {
                          setScanError('');
                          setScanning(true);
                        }
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
          <MonoIcon name={rejected ? 'warning' : 'id'} />{' '}
          {rejected ? t('volunteer.kyc_rejected_title') : t('volunteer.kyc_pending_title')}
        </strong>
        <p style={{ fontSize: '0.82rem', color: '#bbb', margin: '0 0 10px' }}>
          {rejected ? t('volunteer.kyc_rejected_hint') : t('volunteer.kyc_pending_hint')}
        </p>
        <label className="btn-small btn-primary" style={{ cursor: kycBusy ? 'wait' : 'pointer', display: 'inline-block' }}>
          {kycBusy ? '…' : t('volunteer.kyc_upload')}
          <input type="file" accept="image/jpeg,image/png,.pdf" disabled={kycBusy} style={{ display: 'none' }}
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
              <span><MonoIcon name="snow" /> {t('volunteer.thermal_bag')}</span>
            </label>
            <p style={{ fontSize: '0.78rem', color: '#888', marginTop: -8, marginBottom: 14 }}>{t('volunteer.thermal_bag_hint')}</p>

            <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <span><MonoIcon name="bag" /> {t('volunteer.capacity')}</span>
              <select
                value={volunteerInfo?.capacity_kg ?? ''}
                onChange={(e) => setCapacity(e.target.value)}
              >
                <option value="">{t('volunteer.capacity_any')}</option>
                <option value="10">{t('volunteer.capacity_foot')}</option>
                <option value="25">{t('volunteer.capacity_bike')}</option>
                <option value="100">{t('volunteer.capacity_car')}</option>
              </select>
            </label>
            <p style={{ fontSize: '0.78rem', color: '#888', marginBottom: 14 }}>{t('volunteer.capacity_hint')}</p>

            <div style={{ background: '#1a1a26', border: '1px solid #2a2a3a', borderRadius: 12, padding: 12, marginBottom: 16 }}>
              <h4 style={{ margin: '0 0 4px' }}><MonoIcon name="calendar" /> {t('volunteer.availability_title')}</h4>
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
                        <MonoIcon name={LEVEL_ICONS[stats.level.code] || 'award'} />{' '}
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
                    <h4 style={{ margin: '18px 0 8px' }}><MonoIcon name="mail" /> {t('volunteer.thanks_title')}</h4>
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
                        <MonoIcon name={ACHIEVEMENT_ICONS[a] || 'award'} />{' '}
                        {t(`volunteer.ach_${a}`)}
                      </span>
                    ))}
                  </div>
                )}
                <h4 style={{ margin: '18px 0 8px' }}><MonoIcon name="users" /> {t('volunteer.team_title')}</h4>
                {team === undefined ? (
                  <p className="empty-msg" style={{ fontSize: '0.85rem' }}>{t('common.loading')}</p>
                ) : team ? (
                  <div className="team-card">
                    <div className="team-card-header">
                      <span className="team-card-icon"><MonoIcon name="building" /></span>
                      <div className="team-card-title">
                        <strong>{team.name}</strong>
                        <span>{t('volunteer.team_members', { count: team.members })}</span>
                      </div>
                    </div>
                    <div className="team-card-stats">
                      <div className="team-stat">
                        <span className="team-stat-value">{team.deliveries}</span>
                        <span className="team-stat-label">{t('volunteer.total_deliveries')}</span>
                      </div>
                      <div className="team-stat">
                        <span className="team-stat-value">{Math.round(team.kg)}</span>
                        <span className="team-stat-label">{t('volunteer.total_kg')}</span>
                      </div>
                    </div>
                    <div className="team-code-row">
                      <span>{t('volunteer.team_code_hint')}:</span>
                      <code className="team-code">{team.join_code}</code>
                    </div>
                    <button className="btn-small btn-warning" disabled={teamBusy}
                      onClick={() => window.confirm(t('volunteer.team_leave_confirm')) && teamAction('leave')}>
                      {t('volunteer.team_leave')}
                    </button>
                  </div>
                ) : (
                  <div className="team-join-card">
                    <p className="team-intro">{t('volunteer.team_intro')}</p>
                    <div className="team-join-row">
                      <input type="text" placeholder={t('volunteer.team_code_placeholder')} value={teamCode}
                        onChange={e => setTeamCode(e.target.value.toUpperCase())} />
                      <button className="btn-small btn-success" disabled={teamBusy || !teamCode.trim()}
                        onClick={() => teamAction('join', { code: teamCode })}>
                        {t('volunteer.team_join')}
                      </button>
                    </div>
                    <div className="team-join-divider"><span>{t('volunteer.team_or')}</span></div>
                    <div className="team-join-row">
                      <input type="text" placeholder={t('volunteer.team_name_placeholder')} value={teamName}
                        onChange={e => setTeamName(e.target.value)} />
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
                          {i + 1}. {v.name}
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
          {routes.length === 0 ? (
            <EmptyState icon={<MonoIcon name="clipboard" />} title={t('empty.history_title')} description={t('empty.history_desc')} />
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
        {activeTab === 'profile' && (
          <div className="volunteer-tab profile-page">
            <div className="profile-head">
              <div className="profile-avatar"><MonoIcon name="bicycle" /></div>
              <div className="profile-head-text">
                <p className="profile-name">{volunteerInfo?.name || t('nav.roles.volunteer')}</p>
                <p className="profile-role">{t('nav.roles.volunteer')}
                  {kycStatus && (
                    <span className={`kyc-chip kyc-${kycStatus}`}>
                      {kycStatus === 'approved' ? `✓ ${t('volunteer.kyc_status_approved')}`
                        : kycStatus === 'rejected' ? `✕ ${t('volunteer.kyc_status_rejected')}`
                        : <><MonoIcon name="wait" /> {t('volunteer.kyc_status_pending')}</>}
                    </span>
                  )}
                </p>
              </div>
            </div>

            <div className="profile-section">
              <h4><MonoIcon name="id" /> {t('volunteer.kyc_section')}</h4>
              <p className="profile-hint">
                {kycStatus === 'approved' ? t('volunteer.kyc_ok_hint')
                  : kycStatus === 'rejected' ? t('volunteer.kyc_rejected_hint')
                  : t('volunteer.kyc_pending_hint')}
              </p>
              <label className="btn-small btn-primary" style={{ cursor: kycBusy ? 'wait' : 'pointer', display: 'inline-block', width: 'auto' }}>
                {kycBusy ? '…' : t('volunteer.kyc_upload')}
                <input type="file" accept="image/jpeg,image/png,.pdf" disabled={kycBusy} style={{ display: 'none' }}
                  onChange={(e) => { uploadKycDocument(e.target.files?.[0]); e.target.value = ''; }} />
              </label>
            </div>

            <div className="profile-section">
              <h4><MonoIcon name="gear" /> {t('volunteer.equipment_title')}</h4>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={!!volunteerInfo?.has_thermal_bag}
                  onChange={(e) => toggleThermalBag(e.target.checked)}
                />
                <span><MonoIcon name="snow" /> {t('volunteer.thermal_bag')}</span>
              </label>
              <p className="profile-hint" style={{ marginTop: 6 }}>{t('volunteer.thermal_bag_hint')}</p>
            </div>

            <div className="profile-section">
              <h4><MonoIcon name="calendar" /> {t('volunteer.availability_title')}</h4>
              <p className="profile-hint">{t('volunteer.availability_hint')}</p>
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

            <AccountLinks dashboardPath="/volunteer" />
            <PushToggle />

            <button
              className="profile-logout-btn"
              onClick={() => { logout(); navigate('/'); }}
            >
              {t('profile.logout')}
            </button>
          </div>
        )}
      </main>

      <nav className="mobile-nav">
        <button className={activeTab === 'map' ? 'active' : ''} onClick={() => setActiveTab('map')}>{t('volunteer.map')}</button>
        <button className={activeTab === 'route' ? 'active' : ''} onClick={() => { setActiveTab('route'); fetchActiveRoute(); }}>{t('volunteer.route')}</button>
        <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>{t('volunteer.history')}</button>
        <button className={activeTab === 'stats' ? 'active' : ''} onClick={() => { setActiveTab('stats'); fetchStats(); }}>{t('volunteer.stats')}</button>
        <button className={activeTab === 'profile' ? 'active' : ''} onClick={() => { setActiveTab('profile'); refreshVolunteerInfo(); }}>{t('common.profile')}</button>
      </nav>
    </div>
  );
};

export default VolunteerDashboard;
