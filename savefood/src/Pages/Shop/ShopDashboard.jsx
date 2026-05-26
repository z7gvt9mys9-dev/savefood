import React, { useState, useEffect } from 'react';
import AddressInput from '../Auth/AddressInput';
import { useAuth } from '../../context/AuthContext';
import './Shop.css';

const ShopDashboard = () => {
  const { user } = useAuth();
  const shopId = user?.relatedId;

  const [activeTab, setActiveTab] = useState('overview');
  const [lots, setLots] = useState([]);
  const [history, setHistory] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [shopInfo, setShopInfo] = useState({ name: 'Загрузка...' });
  const [photoFile, setPhotoFile] = useState(null);

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
    fetch(`http://localhost:8000/shops/${shopId}`)
      .then(res => res.json())
      .then(data => setShopInfo(data))
      .catch(() => {});

    fetch(`http://localhost:8000/shops/${shopId}/lots`)
      .then(res => res.json())
      .then(data => setLots(data))
      .catch(() => {});

    fetch(`http://localhost:8000/shops/${shopId}/history`)
      .then(res => res.json())
      .then(data => setHistory(data))
      .catch(() => {});

    fetch(`http://localhost:8000/shops/${shopId}/notifications`)
      .then(res => res.json())
      .then(data => setNotifications(Array.isArray(data) ? data : []))
      .catch(() => {});
  };

  useEffect(() => {
    fetchShopData();
  }, [shopId]);

  const handleCreateLot = async (e) => {
    e.preventDefault();
    if (!shopId) { alert('Не удалось определить магазин'); return; }
    const fd = new FormData();
    fd.append('description', newLot.description);
    fd.append('quantity', String(newLot.quantity));
    if (newLot.expiry_date) fd.append('expiry_date', newLot.expiry_date.split('T')[0]);
    if (newLot.address) fd.append('address', newLot.address);
    if (newLot.time_slot) fd.append('time_slot', newLot.time_slot);
    if (photoFile) fd.append('file', photoFile);

    try {
      const res = await fetch(`http://localhost:8000/shops/${shopId}/lots/upload`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user?.token}` },
        body: fd,
      });
      if (!res.ok) {
        const err = await res.json();
        alert(err.detail || 'Ошибка создания лота');
        return;
      }
      alert('Лот успешно создан!');
      setNewLot({ description: '', quantity: 1, category: 'Выпечка', expiry_date: '', address: '', time_slot: '18:00 - 20:00' });
      setPhotoFile(null);
      fetchShopData();
      setActiveTab('active');
    } catch {
      alert('Ошибка подключения к серверу');
    }
  };

  const handleConfirmTransfer = async (lotId) => {
    try {
      const res = await fetch(`http://localhost:8000/lots/${lotId}/confirm_transfer`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert('Не удалось подтвердить передачу'); return; }
      fetchShopData();
    } catch {
      alert('Ошибка подключения к серверу');
    }
  };

  const handleDeleteLot = async (lotId) => {
    if (!window.confirm('Удалить лот?')) return;
    try {
      const res = await fetch(`http://localhost:8000/lots/${lotId}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${user?.token}` },
      });
      if (!res.ok) { alert('Не удалось удалить лот'); return; }
      fetchShopData();
    } catch {
      alert('Ошибка подключения к серверу');
    }
  };

  const renderOverview = () => (
    <div className="tab-content">
      <div className="stats-grid">
        <div className="stat-box">
          <span className="stat-value">{lots.length}</span>
          <span className="stat-label">Активных лотов</span>
        </div>
        <div className="stat-box">
          <span className="stat-value">{history.reduce((acc, l) => acc + (l.quantity || 0), 0)} кг</span>
          <span className="stat-label">Всего спасено еды</span>
        </div>
        <div className="stat-box">
          <span className="stat-value">{history.length}</span>
          <span className="stat-label">Завершенных раздач</span>
        </div>
      </div>
      <div className="info-section">
        <h3>Ваш статус: Партнер (Активен)</h3>
        <p>Адрес: {shopInfo.address || '—'}</p>
      </div>
    </div>
  );

  const renderCreateLot = () => (
    <div className="tab-content">
      <form className="admin-form" onSubmit={handleCreateLot}>
        <h2>Новый лот</h2>
        <div className="form-group">
          <label>Описание продуктов</label>
          <input
            type="text"
            placeholder="Например: Пакет с выпечкой (5 круассанов, 2 багета)"
            value={newLot.description}
            onChange={(e) => setNewLot({...newLot, description: e.target.value})}
            required
          />
        </div>

        <div className="form-row">
          <div className="form-group">
            <label>Категория</label>
            <select value={newLot.category} onChange={(e) => setNewLot({...newLot, category: e.target.value})}>
              <option>Выпечка</option>
              <option>Овощи/Фрукты</option>
              <option>Готовая еда</option>
              <option>Молочные продукты</option>
            </select>
          </div>
          <div className="form-group">
            <label>Вес/Кол-во (кг/шт)</label>
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
            <label>Срок годности (до)</label>
            <input
              type="datetime-local"
              value={newLot.expiry_date}
              onChange={(e) => setNewLot({...newLot, expiry_date: e.target.value})}
              required
            />
          </div>
          <div className="form-group">
            <label>Окно выдачи (время)</label>
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
          label="Адрес выдачи (подтвержденный)"
          value={newLot.address || shopInfo.address}
          onChange={(addr) => setNewLot({...newLot, address: addr.address})}
        />

        <div className="form-group">
          <label>Фотография лота</label>
          <input type="file" onChange={(e) => setPhotoFile(e.target.files[0])} />
        </div>

        <div className="warning-box">
          <p>Лот будет автоматически скрыт за 24 часа до истечения срока годности.</p>
        </div>

        <button type="submit" className="btn btn-primary">Опубликовать лот</button>
      </form>
    </div>
  );

  const renderActiveLots = () => (
    <div className="tab-content">
      <div className="lot-list">
        {lots.length === 0 ? <p className="empty-msg">У вас пока нет активных лотов.</p> : lots.map(lot => (
          <div key={lot.id} className="lot-item">
            <div className="lot-info">
              <h4>{lot.description}</h4>
              <p>Статус: <span className={`status-${lot.status}`}>{lot.status === 'active' ? 'Ожидает волонтера' : 'Забран волонтером'}</span></p>
              {lot.time_slot && <p>Время выдачи: {lot.time_slot}</p>}
              <p>Истекает: {lot.expiry_date ? new Date(lot.expiry_date).toLocaleDateString() : '—'}</p>
            </div>
            <div className="lot-actions">
              {lot.status === 'taken' && (
                <button className="btn-small btn-success" onClick={() => handleConfirmTransfer(lot.id)}>Подтвердить передачу</button>
              )}
              <button className="btn-small btn-danger" onClick={() => handleDeleteLot(lot.id)}>Удалить</button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );

  const renderNotifications = () => (
    <div className="tab-content">
      <h3>Уведомления</h3>
      {notifications.length === 0 ? (
        <p className="empty-msg">Нет уведомлений</p>
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
      <table className="history-table">
        <thead>
          <tr>
            <th>Дата</th>
            <th>Описание</th>
            <th>Кол-во</th>
            <th>Статус</th>
          </tr>
        </thead>
        <tbody>
          {history.length === 0 ? (
            <tr><td colSpan="4" style={{textAlign: 'center', padding: '20px'}}>История пуста</td></tr>
          ) : history.map(h => (
            <tr key={h.id}>
              <td>{new Date(h.created_at).toLocaleDateString()}</td>
              <td>{h.description}</td>
              <td>{h.quantity} кг</td>
              <td>{h.status === 'taken' ? 'Передано' : 'Утилизировано'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  return (
    <div className="dashboard-container">
      <aside className="sidebar">
        <h2>{shopInfo.name}</h2>
        <nav>
          <button className={activeTab === 'overview' ? 'active' : ''} onClick={() => setActiveTab('overview')}>Дашборд</button>
          <button className={activeTab === 'create' ? 'active' : ''} onClick={() => setActiveTab('create')}>Создать лот</button>
          <button className={activeTab === 'active' ? 'active' : ''} onClick={() => setActiveTab('active')}>Активные лоты</button>
          <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>История</button>
          <button className={activeTab === 'notifications' ? 'active' : ''} onClick={() => setActiveTab('notifications')}>
            Уведомления {notifications.filter(n => !n.read).length > 0 && `(${notifications.filter(n => !n.read).length})`}
          </button>
        </nav>
      </aside>

      <main className="main-content">
        <header className="content-header">
          <h1>{activeTab === 'overview' ? 'Обзор' : activeTab === 'create' ? 'Новый лот' : activeTab === 'active' ? 'Мониторинг лотов' : activeTab === 'notifications' ? 'Уведомления' : 'Архив списаний'}</h1>
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
