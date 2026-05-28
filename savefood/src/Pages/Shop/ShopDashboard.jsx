import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import AddressInput from '../Auth/AddressInput';
import EmptyState from '../../components/EmptyState';
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
  const [tgLink, setTgLink] = useState(null);
  const [tgLoading, setTgLoading] = useState(false);

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
  };

  useEffect(() => {
    fetchShopData();
  }, [shopId]);

  const handleCreateLot = async (e) => {
    e.preventDefault();
    if (!shopId) { alert(t('shop.error_no_shop')); return; }
    const fd = new FormData();
    fd.append('description', newLot.description);
    fd.append('quantity', String(newLot.quantity));
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
          <span className="stat-value">{history.reduce((acc, l) => acc + (l.quantity || 0), 0)} {t('shop.kg')}</span>
          <span className="stat-label">{t('shop.saved_food')}</span>
        </div>
        <div className="stat-box">
          <span className="stat-value">{history.length}</span>
          <span className="stat-label">{t('shop.completed')}</span>
        </div>
      </div>
      <div className="info-section">
        <h3>{t('shop.your_status')}: {t('shop.status_active')}</h3>
        <p>{t('common.address')}: {shopInfo.address || '—'}</p>
      </div>
      <div className="tg-connect-section">
        <h3>{t('shop.telegram_title')}</h3>
        {tgLink ? (
          <div className="tg-link-box">
            <p>{t('shop.telegram_link_label')}</p>
            <a href={tgLink.link} target="_blank" rel="noreferrer" className="btn btn-primary tg-btn">
              {t('shop.open_telegram', { name: tgLink.bot_name })}
            </a>
          </div>
        ) : (
          <button className="btn btn-secondary" onClick={async () => {
            setTgLoading(true);
            try {
              const res = await fetch(`${API_URL}/auth/telegram/init-link`, {
                headers: { Authorization: `Bearer ${user?.token}` },
              });
              if (res.ok) setTgLink(await res.json());
              else alert(t('shop.error_tg'));
            } catch { alert(t('common.connection_error')); }
            finally { setTgLoading(false); }
          }} disabled={tgLoading}>
            {tgLoading ? t('common.loading') : t('shop.telegram_connect')}
          </button>
        )}
      </div>
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
        <nav>
          <button className={activeTab === 'overview' ? 'active' : ''} onClick={() => setActiveTab('overview')}>{t('shop.overview')}</button>
          <button className={activeTab === 'create' ? 'active' : ''} onClick={() => setActiveTab('create')}>{t('shop.add_lot')}</button>
          <button className={activeTab === 'active' ? 'active' : ''} onClick={() => setActiveTab('active')}>{t('shop.lots')}</button>
          <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>{t('shop.history')}</button>
          <button className={activeTab === 'notifications' ? 'active' : ''} onClick={() => setActiveTab('notifications')}>
            {t('shop.notifications')} {notifications.filter(n => !n.read).length > 0 && `(${notifications.filter(n => !n.read).length})`}
          </button>
        </nav>
      </aside>

      <main className="main-content">
        <header className="content-header">
          <h1>{activeTab === 'overview' ? t('shop.overview') : activeTab === 'create' ? t('shop.add_lot') : activeTab === 'active' ? t('shop.lots') : activeTab === 'notifications' ? t('shop.notifications') : t('shop.history')}</h1>
        </header>
        {activeTab === 'overview' && renderOverview()}
        {activeTab === 'create' && renderCreateLot()}
        {activeTab === 'active' && renderActiveLots()}
        {activeTab === 'history' && renderHistory()}
        {activeTab === 'notifications' && renderNotifications()}
      </main>
    </div>
  );
};

export default ShopDashboard;
