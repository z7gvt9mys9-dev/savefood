import React, { useState, useEffect, useMemo } from 'react';
import QRCode from 'react-qr-code';
import AddressInput from '../Auth/AddressInput';
import { useAuth } from '../../context/AuthContext';
import './Needy.css';

const NeedyDashboard = () => {
  const { user } = useAuth();
  const needyId = user?.relatedId;

  const [activeTab, setActiveTab] = useState('map');
  const [activeOrder, setActiveOrder] = useState(null);
  const [lots, setLots] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [history, setHistory] = useState([]);
  const [profile, setProfile] = useState({ address: '', family_size: 1, preferences: '', urgency: 'normal', available_time: '' });

  const lotPositions = useMemo(
    () => lots.map(lot => ({ ...lot, _top: Math.random() * 80, _left: Math.random() * 80 })),
    [lots]
  );

  useEffect(() => {
    fetch('http://localhost:8000/lots')
      .then(res => res.json())
      .then(data => setLots(Array.isArray(data) ? data : []))
      .catch(() => {});

    if (!needyId) return;

    fetch(`http://localhost:8000/needy/${needyId}/profile`, {
      headers: { Authorization: `Bearer ${user?.token}` },
    })
      .then(res => res.ok ? res.json() : null)
      .then(data => { if (data) setProfile(prev => ({ ...prev, ...data })); })
      .catch(() => {});

    fetch(`http://localhost:8000/needy/${needyId}/notifications`, {
      headers: { Authorization: `Bearer ${user?.token}` },
    })
      .then(res => res.json())
      .then(data => setNotifications(Array.isArray(data) ? data : []))
      .catch(() => {});

    fetch(`http://localhost:8000/needy/${needyId}/history`, {
      headers: { Authorization: `Bearer ${user?.token}` },
    })
      .then(res => res.json())
      .then(data => setHistory(Array.isArray(data) ? data : []))
      .catch(() => {});
  }, [needyId]);

  const handleBook = async (lot) => {
    if (!needyId) { alert('Необходима авторизация'); return; }
    try {
      const res = await fetch(`http://localhost:8000/needy/${needyId}/ticket`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify({
          items: lot.description,
          address: lot.address || '',
          lot_id: lot.id,
          available_time: profile.available_time || '',
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

  const handleSaveProfile = async (e) => {
    e.preventDefault();
    if (!needyId) { alert('Необходима авторизация'); return; }
    try {
      const res = await fetch(`http://localhost:8000/needy/${needyId}/profile`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user?.token}` },
        body: JSON.stringify({
          address: profile.address,
          family_size: Number(profile.family_size),
          preferences: profile.preferences,
          urgency: profile.urgency,
          available_time: profile.available_time,
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
          onChange={(addr) => setProfile({ ...profile, address: addr.address })}
        />
        <div className="form-row">
          <div className="form-group">
            <label>Состав семьи (чел)</label>
            <input
              type="number"
              value={profile.family_size}
              onChange={(e) => setProfile({ ...profile, family_size: e.target.value })}
            />
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
          <input
            type="text"
            placeholder="14:00-18:00"
            value={profile.available_time}
            onChange={(e) => setProfile({ ...profile, available_time: e.target.value })}
          />
        </div>
        <div className="form-group">
          <label>Пищевые ограничения</label>
          <textarea
            placeholder="Например: аллергия на лактозу"
            value={profile.preferences}
            onChange={(e) => setProfile({ ...profile, preferences: e.target.value })}
          />
        </div>
        <button type="submit" className="btn btn-primary">Сохранить изменения</button>
      </form>
    </div>
  );

  const renderMap = () => (
    <div className="tab-content">
      <div className="map-placeholder">
        <p>Интерактивная карта лотов</p>
        <div className="map-mock">
          {lotPositions.map(lot => (
            <div key={lot.id} className="map-marker" style={{top: `${lot._top}%`, left: `${lot._left}%`}}>
              📍
            </div>
          ))}
        </div>
      </div>
      <div className="lot-grid">
        {lots.length === 0 && <p className="empty-msg">Нет доступных лотов</p>}
        {lots.map(lot => (
          <div key={lot.id} className="lot-card-compact">
            <h4>{lot.description}</h4>
            <p>{lot.address || 'Адрес уточняется'}</p>
            <span className="distance">{lot.quantity} кг/шт</span>
            {lot.time_slot && <p style={{ fontSize: '0.8em', color: '#aaa' }}>Выдача: {lot.time_slot}</p>}
            <button className="btn-small" onClick={() => handleBook(lot)}>Забронировать</button>
          </div>
        ))}
      </div>
      <div className="limit-notice" style={{marginTop: '20px'}}>
        <p>Вы можете оформить заявку не чаще 1 раза в неделю.</p>
      </div>
    </div>
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
        </div>
      ) : (
        <p className="empty-msg">У вас нет активных заказов.</p>
      )}
    </div>
  );

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
            Уведомления {notifications.filter(n => !n.read).length > 0 && `(${notifications.filter(n => !n.read).length})`}
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
                </div>
              ))
            )}
          </div>
        )}
      </main>
    </div>
  );
};

export default NeedyDashboard;
