import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import QRCode from 'react-qr-code';
import { Html5Qrcode, Html5QrcodeSupportedFormats } from 'html5-qrcode';
import AddressInput from '../Auth/AddressInput';
import EmptyState from '../../components/EmptyState';
import AccountLinks from '../../components/AccountLinks';
import PushToggle from '../../components/PushToggle';
import OnboardingChecklist from '../../components/OnboardingChecklist';
import MonoIcon from '../../components/MonoIcon';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import './Shop.css';

const CAT_KEYS = {
  'Выпечка': 'bakery',
  'Овощи/Фрукты': 'vegetables',
  'Готовая еда': 'prepared',
  'Молочные продукты': 'dairy',
};

const ShopDashboard = () => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const shopId = user?.relatedId;

  const [activeTab, setActiveTab] = useState('overview');
  const [lots, setLots] = useState([]);
  const [history, setHistory] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [shopInfo, setShopInfo] = useState({});
  // Photos of the lot being created: up to MAX_LOT_PHOTOS files with previews.
  const [photoFiles, setPhotoFiles] = useState([]);
  const [editLot, setEditLot] = useState(null);
  const [labelLot, setLabelLot] = useState(null);
  const [embedCopied, setEmbedCopied] = useState(false);
  const [historyOffset, setHistoryOffset] = useState(0);
  const [historyHasMore, setHistoryHasMore] = useState(true);
  const [pickupCode, setPickupCode] = useState('');
  const [pickupBusy, setPickupBusy] = useState(false);
  const [pickupScanning, setPickupScanning] = useState(false);
  const [pickupScanError, setPickupScanError] = useState('');
  const [plan, setPlan] = useState(null);
  const [forecast, setForecast] = useState(null);
  // OCR receipt flow: upload photo → review parsed lot drafts → confirm
  const [receiptFile, setReceiptFile] = useState(null);
  const [receiptBusy, setReceiptBusy] = useState(false);
  const [receipt, setReceipt] = useState(null);
  const [receiptDrafts, setReceiptDrafts] = useState([]);
  const [receiptCommon, setReceiptCommon] = useState({ expiry_date: '', address: '', time_slot: '18:00 - 20:00' });
  const [esgReport, setEsgReport] = useState(null);
  const [esgError, setEsgError] = useState(null);
  // Enterprise partner API: keys + webhooks management
  const [apiKeys, setApiKeys] = useState([]);
  const [webhooks, setWebhooks] = useState([]);
  const [newSecret, setNewSecret] = useState(null);
  const [newHook, setNewHook] = useState({ url: '', events: ['*'] });
  const [newHookSecret, setNewHookSecret] = useState(null);
  const [apiBusy, setApiBusy] = useState(false);

  const [newLot, setNewLot] = useState({
    description: '',
    quantity: 1,
    category: 'Выпечка',
    expiry_date: '',
    address: '',
    time_slot: '18:00 - 20:00',
  });

  const fetchShopData = () => {
    if (!shopId) return;
    const authHeader = { Authorization: `Bearer ${user?.token}` };
    fetch(`${API_URL}/shops/${shopId}`, { headers: authHeader })
      .then(res => res.json())
      .then(data => setShopInfo(data))
      .catch(() => {});

    fetch(`${API_URL}/shops/${shopId}/lots`, { headers: authHeader })
      .then(res => res.json())
      .then(data => setLots(Array.isArray(data) ? data : []))
      .catch(() => {});

    fetch(`${API_URL}/shops/${shopId}/history?limit=20&offset=0`, { headers: authHeader })
      .then(res => res.json())
      .then(data => { const arr = Array.isArray(data) ? data : []; setHistory(arr); setHistoryHasMore(arr.length === 20); setHistoryOffset(arr.length); })
      .catch(() => {});

    fetch(`${API_URL}/shops/${shopId}/notifications`, { headers: authHeader })
      .then(res => res.json())
      .then(data => setNotifications(Array.isArray(data) ? data : []))
      .catch(() => {});

    fetch(`${API_URL}/shops/${shopId}/plan`, { headers: authHeader })
      .then(res => res.json())
      .then(data => setPlan(data && data.plan ? data : null))
      .catch(() => {});

    fetch(`${API_URL}/shops/${shopId}/forecast`, { headers: authHeader })
      .then(res => res.ok ? res.json() : null)
      .then(data => setForecast(data))
      .catch(() => {});
  };

  useEffect(() => {
    fetchShopData();
  }, [shopId]);

  // Opening the notifications tab marks everything as read — otherwise the
  // unread badge only ever grows (the read endpoint was never called).
  useEffect(() => {
    if (activeTab !== 'notifications' || !shopId) return;
    const unread = notifications.filter(n => !n.read);
    if (unread.length === 0) return;
    unread.forEach(n => {
      fetch(`${API_URL}/shops/notifications/${n.id}/read`, {
        method: 'PATCH',
        headers: { Authorization: `Bearer ${user?.token}` },
      }).catch(() => {});
    });
    setNotifications(prev => prev.map(n => ({ ...n, read: 1 })));
  }, [activeTab, shopId]);

  // ESG report is loaded lazily — only when the tab is opened (pro+ plans).
  useEffect(() => {
    if (activeTab !== 'esg' || !shopId) return;
    setEsgError(null);
    fetch(`${API_URL}/shops/${shopId}/esg?months=12`, { headers: { Authorization: `Bearer ${user?.token}` } })
      .then(async res => {
        const data = await res.json();
        if (!res.ok) { setEsgError(data.detail || t('common.error')); return; }
        setEsgReport(data);
      })
      .catch(() => setEsgError(t('common.connection_error')));
  }, [activeTab, shopId]);

  // Partner API tab: load keys + webhooks lazily (enterprise plan only).
  useEffect(() => {
    if (activeTab !== 'api' || !shopId || !plan?.api) return;
    const authHeader = { Authorization: `Bearer ${user?.token}` };
    fetch(`${API_URL}/shops/${shopId}/api_keys`, { headers: authHeader })
      .then(r => r.ok ? r.json() : [])
      .then(data => setApiKeys(Array.isArray(data) ? data : []))
      .catch(() => {});
    fetch(`${API_URL}/shops/${shopId}/webhooks`, { headers: authHeader })
      .then(r => r.ok ? r.json() : [])
      .then(data => setWebhooks(Array.isArray(data) ? data : []))
      .catch(() => {});
  }, [activeTab, shopId, plan?.api]);

  const handleCreateApiKey = async () => {
    setApiBusy(true);
    try {
      const res = await fetch(`${API_URL}/shops/${shopId}/api_keys`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      const data = await res.json();
      if (!res.ok) { alert(data.detail || t('common.error')); return; }
      setNewSecret(data.key);
      setApiKeys(prev => [{ id: data.id, prefix: data.prefix, revoked: false, created_at: new Date().toISOString() }, ...prev]);
    } catch { alert(t('common.connection_error')); }
    finally { setApiBusy(false); }
  };

  const handleRevokeApiKey = async (keyId) => {
    if (!window.confirm(t('shop.api_revoke_confirm'))) return;
    try {
      const res = await fetch(`${API_URL}/shops/${shopId}/api_keys/${keyId}/revoke`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (res.ok) setApiKeys(prev => prev.map(k => k.id === keyId ? { ...k, revoked: true } : k));
    } catch {}
  };

  const handleCreateWebhook = async (e) => {
    e.preventDefault();
    setApiBusy(true);
    try {
      const res = await fetch(`${API_URL}/shops/${shopId}/webhooks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify(newHook),
      });
      const data = await res.json();
      if (!res.ok) { alert(data.detail || t('common.error')); return; }
      setNewHookSecret(data.secret);
      setWebhooks(prev => [{ id: data.id, url: newHook.url, events: newHook.events.join(','), active: true }, ...prev]);
      setNewHook({ url: '', events: ['*'] });
    } catch { alert(t('common.connection_error')); }
    finally { setApiBusy(false); }
  };

  const handleDeleteWebhook = async (hookId) => {
    if (!window.confirm(t('shop.api_hook_delete_confirm'))) return;
    try {
      const res = await fetch(`${API_URL}/shops/${shopId}/webhooks/${hookId}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (res.ok) setWebhooks(prev => prev.filter(h => h.id !== hookId));
    } catch {}
  };

  const handleUploadReceipt = async (e) => {
    e.preventDefault();
    if (!receiptFile || !shopId) return;
    setReceiptBusy(true);
    setReceipt(null);
    try {
      const fd = new FormData();
      fd.append('file', receiptFile);
      const res = await fetch(`${API_URL}/shops/${shopId}/receipts`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user?.token}` },
        body: fd,
      });
      const data = await res.json();
      if (!res.ok) {
        alert(data.detail || t('shop.ocr_error'));
        return;
      }
      setReceipt(data);
      setReceiptDrafts(data.suggested_lots || []);
    } catch {
      alert(t('common.connection_error'));
    } finally {
      setReceiptBusy(false);
    }
  };

  const handleConfirmReceipt = async (e) => {
    e.preventDefault();
    if (!receipt || receiptDrafts.length === 0) return;
    setReceiptBusy(true);
    try {
      const body = {
        lots: receiptDrafts.map(d => ({ ...d, quantity: Math.max(1, Number(d.quantity) || 1) })),
      };
      if (receiptCommon.expiry_date) body.expiry_date = receiptCommon.expiry_date;
      if (receiptCommon.address) body.address = receiptCommon.address;
      if (receiptCommon.time_slot) body.time_slot = receiptCommon.time_slot;
      const res = await fetch(`${API_URL}/shops/${shopId}/receipts/${receipt.id}/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) {
        alert(data.detail || t('shop.ocr_error'));
        return;
      }
      alert(t('shop.ocr_lots_created', { count: (data.lot_ids || []).length }));
      setReceipt(null);
      setReceiptDrafts([]);
      setReceiptFile(null);
      fetchShopData();
      setActiveTab('active');
    } catch {
      alert(t('common.connection_error'));
    } finally {
      setReceiptBusy(false);
    }
  };

  const widgetUrl = `${API_URL}/impact/widget/${shopId}.svg`;
  const embedCode = `<a href="https://savefood.kz" target="_blank" rel="noopener">\n  <img src="${widgetUrl}" alt="SaveFood impact" width="320" height="120" />\n</a>`;

  const copyEmbed = () => {
    try {
      navigator.clipboard.writeText(embedCode);
      setEmbedCopied(true);
      setTimeout(() => setEmbedCopied(false), 2000);
    } catch { /* clipboard unavailable */ }
  };

  const downloadEsgCsv = async () => {
    try {
      const res = await fetch(`${API_URL}/shops/${shopId}/esg/report.csv?months=12`, {
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert(t('common.connection_error')); return; }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `savefood_donations_${shopId}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      alert(t('common.connection_error'));
    }
  };

  // Print just the label block (QR + lot details) in a clean popup window.
  const printLabel = () => {
    const node = document.getElementById('lot-label-printable');
    if (!node) return;
    const win = window.open('', '_blank', 'width=420,height=520');
    if (!win) return;
    win.document.write(
      `<!doctype html><html><head><title>SaveFood label</title>` +
      `<style>body{font-family:Arial,sans-serif;margin:0;padding:24px;text-align:center}` +
      `@media print{@page{margin:8mm}}</style></head><body>` +
      node.innerHTML +
      `<script>window.onload=function(){window.print();}<\/script></body></html>`
    );
    win.document.close();
  };

  const MAX_LOT_PHOTOS = 5;

  const addPhotoFiles = (fileList) => {
    const incoming = Array.from(fileList || []).filter(f => f.type.startsWith('image/'));
    setPhotoFiles(prev => [...prev, ...incoming].slice(0, MAX_LOT_PHOTOS));
  };

  const removePhotoFile = (idx) => {
    setPhotoFiles(prev => prev.filter((_, i) => i !== idx));
  };

  const handleCreateLot = async (e) => {
    e.preventDefault();
    if (!shopId) { alert(t('shop.error_no_shop')); return; }
    const fd = new FormData();
    fd.append('description', newLot.description);
    fd.append('quantity', String(newLot.quantity));
    if (newLot.category) fd.append('category', newLot.category);
    if (newLot.expiry_date) fd.append('expiry_date', newLot.expiry_date.split('T')[0]);
    if (newLot.address) fd.append('address', newLot.address);
    if (newLot.time_slot) fd.append('time_slot', newLot.time_slot);
    fd.append('requires_cold', String(!!newLot.requires_cold));
    // Все фотографии уходят полем `files`; первое также дублируется в `file`,
    // чтобы старый бэкенд (одно фото) продолжал принимать форму.
    photoFiles.forEach(f => fd.append('files', f));
    if (photoFiles[0]) fd.append('file', photoFiles[0]);

    try {
      const res = await fetch(`${API_URL}/shops/${shopId}/lots/upload`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user?.token}` },
        body: fd,
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        alert(err.detail || t('shop.error_create'));
        return;
      }
      alert(t('shop.lot_created'));
      setNewLot({ description: '', quantity: 1, category: 'Выпечка', expiry_date: '', address: '', time_slot: '18:00 - 20:00', requires_cold: false });
      setPhotoFiles([]);
      fetchShopData();
      setActiveTab('active');
    } catch {
      alert(t('common.connection_error'));
    }
  };

  // Close a self-pickup ticket by the recipient's QR/code (SF-<id>). Without
  // this, self-pickup tickets stay open forever and block new requests.
  const handleConfirmSelfPickup = async (e) => {
    e.preventDefault();
    if (!pickupCode.trim()) return;
    setPickupBusy(true);
    try {
      const res = await fetch(`${API_URL}/shops/${shopId}/self_pickup/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify({ code: pickupCode.trim() }),
      });
      if (!res.ok) {
        const err = await res.json();
        alert(err.detail || t('common.error'));
        return;
      }
      alert(t('shop.self_pickup_done'));
      setPickupCode('');
    } catch {
      alert(t('common.connection_error'));
    } finally {
      setPickupBusy(false);
    }
  };

  // Camera scan for the recipient's QR (SF-{id}-{secret}) — the secret is too
  // long to type, so scanning is the primary path; the text field is a fallback.
  // stop() may only run after start() has resolved: racing them (user taps
  // «Отмена» while the camera is warming up) threw synchronously and broke the
  // page, so the cleanup chains onto the start promise.
  useEffect(() => {
    if (!pickupScanning) return;
    let disposed = false;
    const scanner = new Html5Qrcode('pickup-qr-reader', {
      formatsToSupport: [Html5QrcodeSupportedFormats.QR_CODE],
    });
    const startPromise = scanner.start(
      { facingMode: 'environment' },
      {
        fps: 10,
        qrbox: (width, height) => {
          const size = Math.floor(Math.min(width, height) * 0.8);
          return { width: size, height: size };
        },
      },
      (decodedText) => {
        if (/^SF-\d+(?:-[A-Za-z0-9_-]+)?$/.test(decodedText.trim())) {
          setPickupCode(decodedText.trim());
          setPickupScanning(false);
        }
      },
      () => {},
    );
    startPromise.catch((error) => {
      console.error('Unable to start the self-pickup QR scanner', error);
      if (!disposed) {
        setPickupScanError(t('shop.self_pickup_camera_error'));
        setPickupScanning(false);
      }
    });
    return () => {
      disposed = true;
      startPromise
        .then(() => scanner.stop())
        .then(() => scanner.clear())
        .catch(() => {});
    };
  }, [pickupScanning]);

  const handleConfirmTransfer = async (lotId) => {
    try {
      const res = await fetch(`${API_URL}/lots/${lotId}/confirm_transfer`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert(t('shop.error_confirm')); return; }
      fetchShopData();
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const handleSaveEdit = async (e) => {
    e.preventDefault();
    if (!editLot) return;
    try {
      const body = {
        description: editLot.description,
        quantity: Number(editLot.quantity),
        address: editLot.address,
        category: editLot.category,
        comment: editLot.comment,
        requires_cold: !!editLot.requires_cold,
      };
      if (editLot.expiry_date) body.expiry_date = editLot.expiry_date;
      const res = await fetch(`${API_URL}/lots/${editLot.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify(body),
      });
      if (!res.ok) { alert(t('shop.error_save')); return; }
      setEditLot(null);
      fetchShopData();
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const handleDeleteLot = async (lotId) => {
    if (!window.confirm(t('shop.confirm_delete'))) return;
    try {
      const res = await fetch(`${API_URL}/lots/${lotId}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert(t('shop.error_delete')); return; }
      fetchShopData();
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const catLabel = cat => t(`categories.${CAT_KEYS[cat]}`, { defaultValue: cat });

  const renderOverview = () => (
    <div className="tab-content">
      <OnboardingChecklist
        storageKey="shop"
        items={[
          { id: 'lot', label: t('onboarding.shop_first_lot'), done: lots.length + history.length > 0 },
          { id: 'handover', label: t('onboarding.shop_first_handover'), done: history.some(l => l.status === 'taken' || l.status === 'confirmed') },
        ]}
      />
      <div className="stats-grid">
        <div className="stat-box">
          <span className="stat-value">{lots.length}</span>
          <span className="stat-label">{t('shop.active_lots')}</span>
        </div>
        <div className="stat-box">
          {/* only lots actually handed over count as "saved" — not expired/removed */}
          <span className="stat-value">{history.filter(l => l.status === 'taken' || l.status === 'confirmed').reduce((acc, l) => acc + (l.quantity || 0), 0)} {t('shop.kg')}</span>
          <span className="stat-label">{t('shop.saved_food')}</span>
        </div>
        <div className="stat-box">
          <span className="stat-value">{history.filter(l => l.status === 'taken' || l.status === 'confirmed').length}</span>
          <span className="stat-label">{t('shop.completed')}</span>
        </div>
      </div>
      <div className="info-section">
        <h3>{t('shop.your_status')}: {t('shop.status_active')}</h3>
        <p>{t('common.address')}: {shopInfo.city || shopInfo.contact || '—'}</p>
      </div>
      {forecast && (forecast.today.items.length > 0 || forecast.tomorrow.items.length > 0) && (
        <div className="info-section">
          <h3><MonoIcon name="chart" /> {t('shop.forecast_title')}</h3>
          <p style={{ opacity: 0.75, fontSize: '0.85rem' }}>
            {t('shop.forecast_hint', { weeks: forecast.basis_weeks })}
          </p>
          {[['today', forecast.today], ['tomorrow', forecast.tomorrow]].map(([key, day]) => (
            day.items.length > 0 && (
              <div key={key} style={{ marginTop: 8 }}>
                <strong>{t(`shop.forecast_${key}`)} ({day.day_name}):</strong>
                <ul style={{ margin: '4px 0 0 18px' }}>
                  {day.items.map(item => (
                    <li key={item.category}>
                      {item.category} — ~{item.avg_kg} {t('shop.kg')}
                    </li>
                  ))}
                </ul>
              </div>
            )
          ))}
          <button className="btn-small" style={{ marginTop: 10 }} onClick={() => setActiveTab('create')}>
            {t('shop.forecast_cta')}
          </button>
        </div>
      )}
      <div className="info-section">
        <h3>{t('shop.self_pickup_title')}</h3>
        <form onSubmit={handleConfirmSelfPickup} style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <input
            type="text"
            placeholder={t('shop.self_pickup_placeholder')}
            value={pickupCode}
            onChange={(e) => setPickupCode(e.target.value)}
            style={{ flex: 1, minWidth: 160 }}
          />
          <button type="button" className="btn btn-secondary" onClick={() => {
            setPickupScanError('');
            setPickupScanning(s => !s);
          }}>
            {pickupScanning ? t('common.cancel') : <><MonoIcon name="camera" /> {t('shop.self_pickup_scan')}</>}
          </button>
          <button type="submit" className="btn btn-primary" disabled={pickupBusy || !pickupCode.trim()}>
            {pickupBusy ? t('common.loading') : t('shop.self_pickup_confirm')}
          </button>
        </form>
        {pickupScanning && <div id="pickup-qr-reader" style={{ width: '100%', maxWidth: 320, marginTop: 10 }} />}
        {pickupScanError && <p role="alert" style={{ color: '#e57373', marginTop: 8 }}>{pickupScanError}</p>}
      </div>
      <AccountLinks dashboardPath="/shop" />
      <PushToggle />
    </div>
  );

  const renderCreateLot = () => (
    <div className="tab-content">
      <form className="admin-form" onSubmit={handleCreateLot}>
        <h2>{t('shop.add_lot')}</h2>
        <div className="form-group">
          <label>{t('shop.lot_name')}</label>
          <input
            type="text"
            placeholder={t('shop.lot_placeholder')}
            value={newLot.description}
            onChange={(e) => setNewLot({...newLot, description: e.target.value})}
            required
          />
        </div>

        <div className="form-row">
          <div className="form-group">
            <label>{t('shop.category')}</label>
            <select value={newLot.category} onChange={(e) => setNewLot({...newLot, category: e.target.value})}>
              <option value="Выпечка">{t('categories.bakery')}</option>
              <option value="Овощи/Фрукты">{t('categories.vegetables')}</option>
              <option value="Готовая еда">{t('categories.prepared')}</option>
              <option value="Молочные продукты">{t('categories.dairy')}</option>
            </select>
          </div>
          <div className="form-group">
            <label>{t('shop.weight')}</label>
            <input
              type="number"
              value={newLot.quantity}
              onChange={(e) => setNewLot({...newLot, quantity: e.target.value})}
              required
            />
          </div>
        </div>

        <div className="form-row">
          <div className="form-group">
            <label>{t('shop.expiry')}</label>
            <input
              type="datetime-local"
              value={newLot.expiry_date}
              onChange={(e) => setNewLot({...newLot, expiry_date: e.target.value})}
              required
            />
          </div>
          <div className="form-group">
            <label>{t('shop.time_slot')}</label>
            <input
              type="text"
              placeholder="18:00 - 21:00"
              value={newLot.time_slot}
              onChange={(e) => setNewLot({...newLot, time_slot: e.target.value})}
              required
            />
          </div>
        </div>

        <AddressInput
          label={t('shop.address_label')}
          value={newLot.address || shopInfo.address}
          onChange={(addr) => setNewLot({...newLot, address: addr.address})}
        />

        <div className="form-group">
          <label>{t('shop.photos')}{shopInfo.kind === 'private' && ' *'}</label>
          <input
            type="file"
            accept="image/jpeg,image/png"
            multiple
            onChange={(e) => { addPhotoFiles(e.target.files); e.target.value = ''; }}
            required={shopInfo.kind === 'private' && photoFiles.length === 0}
          />
          <p style={{ fontSize: '0.78rem', color: '#888', margin: '4px 0 0' }}>
            {t('shop.photos_hint', { max: MAX_LOT_PHOTOS })}
          </p>
          {shopInfo.kind === 'private' && (
            <p style={{ fontSize: '0.78rem', color: '#FFB74D', margin: '4px 0 0' }}>{t('donor.photo_required')}</p>
          )}
          {photoFiles.length > 0 && (
            <div className="photo-preview-row">
              {photoFiles.map((f, i) => (
                <div key={i} className="photo-preview">
                  <img src={URL.createObjectURL(f)} alt={f.name} onLoad={(e) => URL.revokeObjectURL(e.target.src)} />
                  <button type="button" className="photo-preview-remove" title={t('common.delete')}
                    onClick={() => removePhotoFile(i)}>✕</button>
                  {i === 0 && <span className="photo-preview-main">{t('shop.photo_main')}</span>}
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="form-group">
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={!!newLot.requires_cold}
              onChange={(e) => setNewLot({ ...newLot, requires_cold: e.target.checked })}
              style={{ width: 'auto' }}
            />
            <MonoIcon name="snow" /> {t('shop.cold_chain')}
          </label>
        </div>

        <div className="warning-box">
          <p>{t('shop.auto_hide')}</p>
        </div>

        <button type="submit" className="btn btn-primary">{t('shop.publish')}</button>
      </form>
    </div>
  );

  const WEBHOOK_EVENTS = ['*', 'lot.taken', 'lot.confirmed', 'receipt.parsed'];

  const renderApi = () => (
    <div className="tab-content">
      <h3>{t('shop.api_title')}</h3>
      <p style={{ opacity: 0.8 }}>{t('shop.api_intro')}</p>
      {plan && !plan.api ? renderUpgradeNotice() : (
        <>
          <div className="info-section">
            <h3>{t('shop.api_keys_title')}</h3>
            {newSecret && (
              <div className="warning-box" style={{ wordBreak: 'break-all' }}>
                <p>{t('shop.api_key_once')}</p>
                <code>{newSecret}</code>
              </div>
            )}
            <button className="btn-small btn-success" disabled={apiBusy} onClick={handleCreateApiKey}>
              {t('shop.api_key_create')}
            </button>
            {apiKeys.length > 0 && (
              <table className="admin-table" style={{ marginTop: 10 }}>
                <thead>
                  <tr><th>{t('shop.api_col_key')}</th><th>{t('shop.api_col_used')}</th><th>{t('common.status')}</th><th></th></tr>
                </thead>
                <tbody>
                  {apiKeys.map(k => (
                    <tr key={k.id} style={{ opacity: k.revoked ? 0.5 : 1 }}>
                      <td><code>{k.prefix}…</code></td>
                      <td>{k.last_used_at ? new Date(k.last_used_at).toLocaleString() : '—'}</td>
                      <td>{k.revoked ? t('shop.api_revoked') : t('admin.active')}</td>
                      <td>{!k.revoked && (
                        <button className="btn-small btn-danger" onClick={() => handleRevokeApiKey(k.id)}>
                          {t('shop.api_revoke')}
                        </button>
                      )}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <div className="info-section">
            <h3>{t('shop.api_hooks_title')}</h3>
            <p style={{ opacity: 0.75, fontSize: '0.85rem' }}>{t('shop.api_hooks_hint')}</p>
            {newHookSecret && (
              <div className="warning-box" style={{ wordBreak: 'break-all' }}>
                <p>{t('shop.api_hook_secret_once')}</p>
                <code>{newHookSecret}</code>
              </div>
            )}
            <form onSubmit={handleCreateWebhook} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
              <input
                type="url"
                placeholder="https://erp.example.com/savefood-hook"
                value={newHook.url}
                onChange={e => setNewHook(prev => ({ ...prev, url: e.target.value }))}
                style={{ flex: 1, minWidth: 220 }}
                required
              />
              <select
                value={newHook.events[0]}
                onChange={e => setNewHook(prev => ({ ...prev, events: [e.target.value] }))}
              >
                {WEBHOOK_EVENTS.map(ev => (
                  <option key={ev} value={ev}>{ev === '*' ? t('shop.api_all_events') : ev}</option>
                ))}
              </select>
              <button type="submit" className="btn-small btn-success" disabled={apiBusy || !newHook.url}>
                {t('common.save')}
              </button>
            </form>
            {webhooks.length > 0 && (
              <table className="admin-table" style={{ marginTop: 10 }}>
                <thead>
                  <tr><th>URL</th><th>{t('shop.api_col_events')}</th><th>{t('shop.api_col_last')}</th><th></th></tr>
                </thead>
                <tbody>
                  {webhooks.map(h => (
                    <tr key={h.id}>
                      <td style={{ wordBreak: 'break-all' }}>{h.url}</td>
                      <td>{h.events}</td>
                      <td>{h.last_status ? `HTTP ${h.last_status}` : '—'}</td>
                      <td>
                        <button className="btn-small btn-danger" onClick={() => handleDeleteWebhook(h.id)}>
                          {t('common.delete')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}
    </div>
  );

  const renderUpgradeNotice = () => (
    <div className="warning-box" style={{ marginTop: 16 }}>
      <p><MonoIcon name="diamond" /> {t('shop.plan_upgrade_hint')}</p>
      <p style={{ opacity: 0.8 }}>{t('shop.plan_current')}: {plan?.label || '—'}</p>
    </div>
  );

  const renderOcr = () => (
    <div className="tab-content">
      {plan && !plan.ocr ? (
        <>
          <h3>{t('shop.ocr_title')}</h3>
          <p>{t('shop.ocr_intro')}</p>
          {renderUpgradeNotice()}
        </>
      ) : !receipt ? (
        <form className="admin-form" onSubmit={handleUploadReceipt}>
          <h2>{t('shop.ocr_title')}</h2>
          <p>{t('shop.ocr_intro')}</p>
          <div className="form-group">
            <label>{t('shop.ocr_photo')}</label>
            <input type="file" accept="image/jpeg,image/png" capture="environment" onChange={(e) => setReceiptFile(e.target.files[0])} required />
          </div>
          <button type="submit" className="btn btn-primary" disabled={receiptBusy || !receiptFile}>
            {receiptBusy ? t('shop.ocr_processing') : t('shop.ocr_recognize')}
          </button>
        </form>
      ) : (
        <form className="admin-form" onSubmit={handleConfirmReceipt}>
          <h2>{t('shop.ocr_review')}</h2>
          <p>
            {receipt.merchant && <><MonoIcon name="store" /> {receipt.merchant} · </>}
            {receipt.receipt_date && <><MonoIcon name="calendar" /> {receipt.receipt_date} · </>}
            {receipt.total != null && <><MonoIcon name="money" /> {receipt.total} {receipt.currency || ''}</>}
          </p>
          {receipt.status === 'rejected' ? (
            <div className="warning-box">
              <p><MonoIcon name="blocked" /> {t('shop.ocr_rejected')}</p>
              <p>{receipt.fraud_reasons}</p>
              <button type="button" className="btn btn-secondary" onClick={() => { setReceipt(null); setReceiptFile(null); }}>
                {t('common.cancel')}
              </button>
            </div>
          ) : (
            <>
              {receipt.fraud_flagged && (
                <div className="warning-box">
                  <p><MonoIcon name="warning" /> {t('shop.ocr_flagged')}: {receipt.fraud_reasons}</p>
                </div>
              )}
              {receiptDrafts.map((d, i) => (
                <div key={i} className="form-row" style={{ alignItems: 'flex-end' }}>
                  <div className="form-group" style={{ flex: 2 }}>
                    <label>{t('shop.lot_name')}</label>
                    <input value={d.description} onChange={e => setReceiptDrafts(ds => ds.map((x, j) => j === i ? { ...x, description: e.target.value } : x))} required />
                  </div>
                  <div className="form-group">
                    <label>{t('shop.weight')}</label>
                    <input type="number" min="1" value={d.quantity} onChange={e => setReceiptDrafts(ds => ds.map((x, j) => j === i ? { ...x, quantity: e.target.value } : x))} required />
                  </div>
                  <div className="form-group">
                    <label>{t('shop.category')}</label>
                    <select value={d.category} onChange={e => setReceiptDrafts(ds => ds.map((x, j) => j === i ? { ...x, category: e.target.value } : x))}>
                      <option value="Выпечка">{t('categories.bakery')}</option>
                      <option value="Овощи/Фрукты">{t('categories.vegetables')}</option>
                      <option value="Готовая еда">{t('categories.prepared')}</option>
                      <option value="Молочные продукты">{t('categories.dairy')}</option>
                    </select>
                  </div>
                  <div className="form-group">
                    <button type="button" className="btn-small btn-danger" onClick={() => setReceiptDrafts(ds => ds.filter((_, j) => j !== i))}>✕</button>
                  </div>
                </div>
              ))}
              <div className="form-row">
                <div className="form-group">
                  <label>{t('shop.expiry')}</label>
                  <input type="date" value={receiptCommon.expiry_date} onChange={e => setReceiptCommon({ ...receiptCommon, expiry_date: e.target.value })} />
                </div>
                <div className="form-group">
                  <label>{t('shop.time_slot')}</label>
                  <input type="text" value={receiptCommon.time_slot} onChange={e => setReceiptCommon({ ...receiptCommon, time_slot: e.target.value })} />
                </div>
              </div>
              <AddressInput
                label={t('shop.address_label')}
                value={receiptCommon.address}
                onChange={(addr) => setReceiptCommon({ ...receiptCommon, address: addr.address })}
              />
              <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                <button type="submit" className="btn btn-primary" disabled={receiptBusy || receiptDrafts.length === 0}>
                  {receiptBusy ? t('common.loading') : t('shop.ocr_publish', { count: receiptDrafts.length })}
                </button>
                <button type="button" className="btn btn-secondary" onClick={() => { setReceipt(null); setReceiptFile(null); }}>
                  {t('common.cancel')}
                </button>
              </div>
            </>
          )}
        </form>
      )}
    </div>
  );

  const renderEsg = () => (
    <div className="tab-content">
      {esgError ? (
        <>
          <h3>{t('shop.esg_title')}</h3>
          <div className="warning-box"><p>{esgError}</p></div>
          {plan && !plan.esg && renderUpgradeNotice()}
        </>
      ) : !esgReport ? (
        <p>{t('common.loading')}</p>
      ) : (
        <>
          <div className="stats-grid">
            <div className="stat-box">
              <span className="stat-value">{esgReport.totals.kg} {t('shop.kg')}</span>
              <span className="stat-label">{t('shop.esg_kg_saved')}</span>
            </div>
            <div className="stat-box">
              <span className="stat-value">{esgReport.totals.co2_kg} {t('shop.kg')}</span>
              <span className="stat-label">{t('shop.esg_co2')}</span>
            </div>
            <div className="stat-box">
              <span className="stat-value">{esgReport.totals.meals}</span>
              <span className="stat-label">{t('shop.esg_meals')}</span>
            </div>
            <div className="stat-box">
              <span className="stat-value">{esgReport.totals.lots}</span>
              <span className="stat-label">{t('shop.esg_lots')}</span>
            </div>
          </div>
          {esgReport.by_category.length > 0 && (
            <table className="history-table" style={{ marginTop: 16 }}>
              <thead>
                <tr><th>{t('shop.category')}</th><th>{t('shop.kg')}</th><th>CO₂ ({t('shop.kg')})</th><th>{t('shop.esg_lots')}</th></tr>
              </thead>
              <tbody>
                {esgReport.by_category.map(c => (
                  <tr key={c.category}>
                    <td>{catLabel(c.category)}</td><td>{c.kg}</td><td>{c.co2_kg}</td><td>{c.lots}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {esgReport.by_month.length > 0 && (
            <table className="history-table" style={{ marginTop: 16 }}>
              <thead>
                <tr><th>{t('shop.esg_month')}</th><th>{t('shop.kg')}</th><th>CO₂ ({t('shop.kg')})</th></tr>
              </thead>
              <tbody>
                {esgReport.by_month.map(m => (
                  <tr key={m.month}><td>{m.month}</td><td>{m.kg}</td><td>{m.co2_kg}</td></tr>
                ))}
              </tbody>
            </table>
          )}
          <p style={{ marginTop: 16, fontSize: '0.85rem', opacity: 0.7 }}>{t('shop.esg_methodology')}: {esgReport.methodology}</p>
          <button className="btn-small" style={{ marginTop: 8 }} onClick={downloadEsgCsv}><MonoIcon name="download" /> {t('shop.download_csv')}</button>

          <div style={{ marginTop: 24, borderTop: '1px solid rgba(255,255,255,0.1)', paddingTop: 16 }}>
            <h4>{t('shop.embed_title')}</h4>
            <img src={`${API_URL}/impact/widget/${shopId}.svg`} alt="SaveFood widget" style={{ maxWidth: 320, display: 'block', margin: '8px 0' }} />
            <p style={{ fontSize: '0.85rem', opacity: 0.8 }}>{t('shop.embed_hint')}</p>
            <textarea
              readOnly
              rows={3}
              onFocus={e => e.target.select()}
              style={{ width: '100%', fontFamily: 'monospace', fontSize: '0.75rem' }}
              value={embedCode}
            />
            <button className="btn-small" style={{ marginTop: 6 }} onClick={copyEmbed}>{embedCopied ? t('shop.embed_copied') : t('shop.embed_copy')}</button>
          </div>
        </>
      )}
    </div>
  );

  const renderActiveLots = () => (
    <div className="tab-content">
      <div className="lot-list">
        {lots.length === 0
          ? <EmptyState icon={<MonoIcon name="box" />} title={t('empty.lots_title')} description={t('empty.lots_shop_desc')} action={t('empty.lots_action')} onAction={() => setActiveTab('create')} />
          : lots.map(lot => (
          <div key={lot.id} className="lot-item">
            <div className="lot-info">
              <h4>{lot.description} {lot.requires_cold && <span className="cold-badge"><MonoIcon name="snow" /> {t('shop.cold_badge')}</span>}</h4>
              <p>{t('common.status')}: <span className={`status-${lot.status}`}>{lot.status === 'active' ? t('shop.status_waiting') : t('shop.status_taken')}</span></p>
              {lot.time_slot && <p>{t('shop.time_slot')}: {lot.time_slot}</p>}
              <p>{t('shop.expiry')}: {lot.expiry_date ? new Date(lot.expiry_date).toLocaleDateString() : '—'}</p>
            </div>
            <div className="lot-actions">
              {lot.status === 'taken' && (
                <button className="btn-small btn-success" onClick={() => handleConfirmTransfer(lot.id)}>{t('shop.confirm_transfer')}</button>
              )}
              {lot.status === 'active' && (
                <button className="btn-small" onClick={() => setEditLot({ ...lot, expiry_date: lot.expiry_date ? lot.expiry_date.slice(0,10) : '' })}>{t('common.edit')}</button>
              )}
              <button className="btn-small" onClick={() => setLabelLot(lot)}>{t('shop.print_label')}</button>
              <button className="btn-small btn-danger" onClick={() => handleDeleteLot(lot.id)}>{t('common.delete')}</button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );

  const renderNotifications = () => (
    <div className="tab-content">
      <h3>{t('common.notifications')}</h3>
      {notifications.length === 0 ? (
        <EmptyState icon={<MonoIcon name="bell" />} title={t('empty.notifications_title')} description={t('empty.notifications_desc')} />
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

  const renderHistory = () => (
    <div className="tab-content">
      {history.length === 0 ? (
        <EmptyState icon={<MonoIcon name="clipboard" />} title={t('empty.history_title')} description={t('empty.history_desc')} />
      ) : (
      <table className="history-table">
        <thead>
          <tr>
            <th>{t('shop.col_date')}</th>
            <th>{t('shop.col_desc')}</th>
            <th>{t('shop.col_qty')}</th>
            <th>{t('shop.col_status')}</th>
          </tr>
        </thead>
        <tbody>
          {history.map(h => (
            <tr key={h.id}>
              <td>{new Date(h.created_at).toLocaleDateString()}</td>
              <td>{h.description}</td>
              <td>{h.quantity} {t('shop.kg')}</td>
              <td>{(h.status === 'taken' || h.status === 'confirmed') ? t('shop.transferred') : t('shop.disposed')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      )}
      {historyHasMore && (
        <button className="btn btn-secondary" style={{ marginTop: '12px' }} onClick={() => {
          fetch(`${API_URL}/shops/${shopId}/history?limit=20&offset=${historyOffset}`, { headers: { Authorization: `Bearer ${user?.token}` } })
            .then(r => r.json())
            .then(data => { const arr = Array.isArray(data) ? data : []; setHistory(prev => [...prev, ...arr]); setHistoryHasMore(arr.length === 20); setHistoryOffset(h => h + arr.length); })
            .catch(() => {});
        }}>{t('common.show_more')}</button>
      )}
    </div>
  );

  return (
    <div className="dashboard-container">
      {editLot && (
        <div className="modal-overlay" onClick={() => setEditLot(null)}>
          <div className="modal-card" onClick={e => e.stopPropagation()}>
            <h3>{t('shop.edit_lot')}</h3>
            <form onSubmit={handleSaveEdit} className="admin-form">
              <div className="form-group">
                <label>{t('common.description')}</label>
                <input value={editLot.description || ''} onChange={e => setEditLot({...editLot, description: e.target.value})} required />
              </div>
              <div className="form-row">
                <div className="form-group">
                  <label>{t('shop.weight')}</label>
                  <input type="number" value={editLot.quantity || ''} onChange={e => setEditLot({...editLot, quantity: e.target.value})} required />
                </div>
                <div className="form-group">
                  <label>{t('shop.category')}</label>
                  <select value={editLot.category || 'Выпечка'} onChange={e => setEditLot({...editLot, category: e.target.value})}>
                    <option value="Выпечка">{t('categories.bakery')}</option>
                    <option value="Овощи/Фрукты">{t('categories.vegetables')}</option>
                    <option value="Готовая еда">{t('categories.prepared')}</option>
                    <option value="Молочные продукты">{t('categories.dairy')}</option>
                  </select>
                </div>
              </div>
              <div className="form-group">
                <label>{t('shop.expiry')}</label>
                <input type="date" value={editLot.expiry_date || ''} onChange={e => setEditLot({...editLot, expiry_date: e.target.value})} />
              </div>
              <div className="form-group">
                <label>{t('common.address')}</label>
                <input value={editLot.address || ''} onChange={e => setEditLot({...editLot, address: e.target.value})} />
              </div>
              <div className="form-group">
                <label>{t('shop.comment')}</label>
                <input value={editLot.comment || ''} onChange={e => setEditLot({...editLot, comment: e.target.value})} />
              </div>
              <div className="form-group">
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={!!editLot.requires_cold}
                    onChange={e => setEditLot({...editLot, requires_cold: e.target.checked})}
                    style={{ width: 'auto' }}
                  />
                  <MonoIcon name="snow" /> {t('shop.cold_chain')}
                </label>
              </div>
              <div style={{ display: 'flex', gap: '8px', marginTop: '12px' }}>
                <button type="submit" className="btn btn-primary">{t('common.save')}</button>
                <button type="button" className="btn btn-secondary" onClick={() => setEditLot(null)}>{t('common.cancel')}</button>
              </div>
            </form>
          </div>
        </div>
      )}
      {labelLot && (
        <div className="modal-overlay" onClick={() => setLabelLot(null)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>{t('shop.label_title')}</h3>
            <div id="lot-label-printable" style={{ background: '#fff', color: '#000', padding: 20, textAlign: 'center', borderRadius: 8 }}>
              <div style={{ background: '#fff', padding: 8, display: 'inline-block' }}>
                <QRCode value={`SF-LOT-${labelLot.id}`} size={160} />
              </div>
              <p style={{ fontWeight: 700, margin: '10px 0 2px' }}>{labelLot.description}</p>
              <p style={{ margin: '2px 0', fontSize: '0.85rem' }}>SaveFood · лот #{labelLot.id}</p>
              {labelLot.requires_cold && <p style={{ margin: '2px 0', fontSize: '0.85rem' }}><MonoIcon name="snow" /> {t('shop.cold_badge')}</p>}
              {labelLot.expiry_date && <p style={{ margin: '2px 0', fontSize: '0.85rem' }}>{t('shop.expiry')}: {new Date(labelLot.expiry_date).toLocaleDateString()}</p>}
            </div>
            <div style={{ display: 'flex', gap: '8px', marginTop: '12px' }}>
              <button type="button" className="btn btn-primary" onClick={() => printLabel()}>{t('shop.print_label')}</button>
              <button type="button" className="btn btn-secondary" onClick={() => setLabelLot(null)}>{t('common.cancel')}</button>
            </div>
          </div>
        </div>
      )}
      <aside className="sidebar">
        <h2>{shopInfo.name || t('common.loading')}</h2>
        {plan && (
          <p style={{ fontSize: '0.85rem', opacity: 0.8 }}>
            <MonoIcon name="diamond" /> {t('shop.plan_label')}: {plan.label}
            {plan.monthly_lot_limit != null && ` (${plan.lots_used_this_month}/${plan.monthly_lot_limit})`}
          </p>
        )}
        {shopInfo.kind === 'private' && (
          <p style={{ fontSize: '0.85rem', color: '#FFB74D' }}><MonoIcon name="home" /> {t('donor.badge')}</p>
        )}
        <nav>
          <button className={activeTab === 'overview' ? 'active' : ''} onClick={() => setActiveTab('overview')}>{t('shop.overview')}</button>
          <button className={activeTab === 'create' ? 'active' : ''} onClick={() => setActiveTab('create')}>{t('shop.add_lot')}</button>
          <button className={activeTab === 'ocr' ? 'active' : ''} onClick={() => setActiveTab('ocr')}>{t('shop.ocr_tab')}</button>
          <button className={activeTab === 'active' ? 'active' : ''} onClick={() => setActiveTab('active')}>{t('shop.lots')}</button>
          <button className={activeTab === 'esg' ? 'active' : ''} onClick={() => setActiveTab('esg')}>{t('shop.esg_tab')}</button>
          <button className={activeTab === 'api' ? 'active' : ''} onClick={() => setActiveTab('api')}>{t('shop.api_tab')}</button>
          <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>{t('shop.history')}</button>
          <button className={activeTab === 'notifications' ? 'active' : ''} onClick={() => setActiveTab('notifications')}>
            {t('shop.notifications')} {notifications.filter(n => !n.read).length > 0 && `(${notifications.filter(n => !n.read).length})`}
          </button>
        </nav>
      </aside>

      <main className="main-content">
        <header className="content-header">
          <h1>{activeTab === 'overview' ? t('shop.overview') : activeTab === 'create' ? t('shop.add_lot') : activeTab === 'ocr' ? t('shop.ocr_tab') : activeTab === 'active' ? t('shop.lots') : activeTab === 'esg' ? t('shop.esg_tab') : activeTab === 'api' ? t('shop.api_tab') : activeTab === 'notifications' ? t('shop.notifications') : t('shop.history')}</h1>
        </header>
        {activeTab === 'overview' && renderOverview()}
        {activeTab === 'create' && renderCreateLot()}
        {activeTab === 'ocr' && renderOcr()}
        {activeTab === 'active' && renderActiveLots()}
        {activeTab === 'esg' && renderEsg()}
        {activeTab === 'api' && renderApi()}
        {activeTab === 'history' && renderHistory()}
        {activeTab === 'notifications' && renderNotifications()}
      </main>
    </div>
  );
};

export default ShopDashboard;
