import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import AddressInput from '../Auth/AddressInput';
import EmptyState from '../../components/EmptyState';
import AccountLinks from '../../components/AccountLinks';
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
  const [photoFile, setPhotoFile] = useState(null);
  const [editLot, setEditLot] = useState(null);
  const [historyOffset, setHistoryOffset] = useState(0);
  const [historyHasMore, setHistoryHasMore] = useState(true);
  const [pickupCode, setPickupCode] = useState('');
  const [pickupBusy, setPickupBusy] = useState(false);
  const [plan, setPlan] = useState(null);
  // OCR receipt flow: upload photo → review parsed lot drafts → confirm
  const [receiptFile, setReceiptFile] = useState(null);
  const [receiptBusy, setReceiptBusy] = useState(false);
  const [receipt, setReceipt] = useState(null);
  const [receiptDrafts, setReceiptDrafts] = useState([]);
  const [receiptCommon, setReceiptCommon] = useState({ expiry_date: '', address: '', time_slot: '18:00 - 20:00' });
  const [esgReport, setEsgReport] = useState(null);
  const [esgError, setEsgError] = useState(null);

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
    if (photoFile) fd.append('file', photoFile);

    try {
      const res = await fetch(`${API_URL}/shops/${shopId}/lots/upload`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user?.token}` },
        body: fd,
      });
      if (!res.ok) {
        const err = await res.json();
        alert(err.detail || t('shop.error_create'));
        return;
      }
      alert(t('shop.lot_created'));
      setNewLot({ description: '', quantity: 1, category: 'Выпечка', expiry_date: '', address: '', time_slot: '18:00 - 20:00' });
      setPhotoFile(null);
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
          <button type="submit" className="btn btn-primary" disabled={pickupBusy || !pickupCode.trim()}>
            {pickupBusy ? t('common.loading') : t('shop.self_pickup_confirm')}
          </button>
        </form>
      </div>
      <AccountLinks dashboardPath="/shop" />
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
          <label>{t('shop.photo')}</label>
          <input type="file" onChange={(e) => setPhotoFile(e.target.files[0])} />
        </div>

        <div className="warning-box">
          <p>{t('shop.auto_hide')}</p>
        </div>

        <button type="submit" className="btn btn-primary">{t('shop.publish')}</button>
      </form>
    </div>
  );

  const renderUpgradeNotice = () => (
    <div className="warning-box" style={{ marginTop: 16 }}>
      <p>💎 {t('shop.plan_upgrade_hint')}</p>
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
            <input type="file" accept="image/*" capture="environment" onChange={(e) => setReceiptFile(e.target.files[0])} required />
          </div>
          <button type="submit" className="btn btn-primary" disabled={receiptBusy || !receiptFile}>
            {receiptBusy ? t('shop.ocr_processing') : t('shop.ocr_recognize')}
          </button>
        </form>
      ) : (
        <form className="admin-form" onSubmit={handleConfirmReceipt}>
          <h2>{t('shop.ocr_review')}</h2>
          <p>
            {receipt.merchant && <>🏪 {receipt.merchant} · </>}
            {receipt.receipt_date && <>📅 {receipt.receipt_date} · </>}
            {receipt.total != null && <>💰 {receipt.total} {receipt.currency || ''}</>}
          </p>
          {receipt.status === 'rejected' ? (
            <div className="warning-box">
              <p>🚫 {t('shop.ocr_rejected')}</p>
              <p>{receipt.fraud_reasons}</p>
              <button type="button" className="btn btn-secondary" onClick={() => { setReceipt(null); setReceiptFile(null); }}>
                {t('common.cancel')}
              </button>
            </div>
          ) : (
            <>
              {receipt.fraud_flagged && (
                <div className="warning-box">
                  <p>⚠️ {t('shop.ocr_flagged')}: {receipt.fraud_reasons}</p>
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
        </>
      )}
    </div>
  );

  const renderActiveLots = () => (
    <div className="tab-content">
      <div className="lot-list">
        {lots.length === 0
          ? <EmptyState icon="📦" title={t('empty.lots_title')} description={t('empty.lots_shop_desc')} action={t('empty.lots_action')} onAction={() => setActiveTab('create')} />
          : lots.map(lot => (
          <div key={lot.id} className="lot-item">
            <div className="lot-info">
              <h4>{lot.description}</h4>
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
        <EmptyState icon="🔔" title={t('empty.notifications_title')} description={t('empty.notifications_desc')} />
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
        <EmptyState icon="📋" title={t('empty.history_title')} description={t('empty.history_desc')} />
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
              <div style={{ display: 'flex', gap: '8px', marginTop: '12px' }}>
                <button type="submit" className="btn btn-primary">{t('common.save')}</button>
                <button type="button" className="btn btn-secondary" onClick={() => setEditLot(null)}>{t('common.cancel')}</button>
              </div>
            </form>
          </div>
        </div>
      )}
      <aside className="sidebar">
        <h2>{shopInfo.name || t('common.loading')}</h2>
        {plan && (
          <p style={{ fontSize: '0.85rem', opacity: 0.8 }}>
            💎 {t('shop.plan_label')}: {plan.label}
            {plan.monthly_lot_limit != null && ` (${plan.lots_used_this_month}/${plan.monthly_lot_limit})`}
          </p>
        )}
        <nav>
          <button className={activeTab === 'overview' ? 'active' : ''} onClick={() => setActiveTab('overview')}>{t('shop.overview')}</button>
          <button className={activeTab === 'create' ? 'active' : ''} onClick={() => setActiveTab('create')}>{t('shop.add_lot')}</button>
          <button className={activeTab === 'ocr' ? 'active' : ''} onClick={() => setActiveTab('ocr')}>{t('shop.ocr_tab')}</button>
          <button className={activeTab === 'active' ? 'active' : ''} onClick={() => setActiveTab('active')}>{t('shop.lots')}</button>
          <button className={activeTab === 'esg' ? 'active' : ''} onClick={() => setActiveTab('esg')}>{t('shop.esg_tab')}</button>
          <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>{t('shop.history')}</button>
          <button className={activeTab === 'notifications' ? 'active' : ''} onClick={() => setActiveTab('notifications')}>
            {t('shop.notifications')} {notifications.filter(n => !n.read).length > 0 && `(${notifications.filter(n => !n.read).length})`}
          </button>
        </nav>
      </aside>

      <main className="main-content">
        <header className="content-header">
          <h1>{activeTab === 'overview' ? t('shop.overview') : activeTab === 'create' ? t('shop.add_lot') : activeTab === 'ocr' ? t('shop.ocr_tab') : activeTab === 'active' ? t('shop.lots') : activeTab === 'esg' ? t('shop.esg_tab') : activeTab === 'notifications' ? t('shop.notifications') : t('shop.history')}</h1>
        </header>
        {activeTab === 'overview' && renderOverview()}
        {activeTab === 'create' && renderCreateLot()}
        {activeTab === 'ocr' && renderOcr()}
        {activeTab === 'active' && renderActiveLots()}
        {activeTab === 'esg' && renderEsg()}
        {activeTab === 'history' && renderHistory()}
        {activeTab === 'notifications' && renderNotifications()}
      </main>
    </div>
  );
};

export default ShopDashboard;
