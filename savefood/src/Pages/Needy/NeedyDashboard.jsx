import React, { useState, useEffect, useMemo } from 'react';
import AddressInput from '../Auth/AddressInput';
import './Needy.css';

const NeedyDashboard = () => {
  const [activeTab, setActiveTab] = useState('map');
  const [status, setStatus] = useState('verified'); // verified, pending, rejected
  const [activeOrder, setActiveOrder] = useState(null);
  const [lots, setLots] = useState([]);

  const lotPositions = useMemo(
    () => lots.map(lot => ({ ...lot, _top: Math.random() * 80, _left: Math.random() * 80 })),
    [lots]
  );

  useEffect(() => {
    fetch('http://localhost:8000/stats') // Mocking lot list fetch
      .then(() => {
        // Mock lots data
        setLots([
          { id: 1, shop: 'Магнит', description: 'Хлеб, молоко', distance: '300м' },
          { id: 2, shop: 'Азбука Вкуса', description: 'Готовые обеды', distance: '1.2км' },
        ]);
      });
  }, []);

  const renderVerification = () => (
    <div className="tab-content verification-page">
      <div className="moderation-box">
        <div className="spinner"></div>
        <h2>Документы на проверке</h2>
        <p>Наши модераторы проверяют вашу анкету. Обычно это занимает не более 24 часов.</p>
        <div className="warning-box">
          <p>⚠️ До одобрения профиля вы не сможете бронировать лоты.</p>
        </div>
        <button className="btn btn-secondary" onClick={() => setStatus('verified')}>Имитировать одобрение</button>
      </div>
    </div>
  );

  const renderProfile = () => (
    <div className="tab-content">
      <form className="admin-form">
        <h2>Анкета получателя</h2>
        <AddressInput label="Адрес проживания" onChange={() => {}} />
        <div className="form-row">
          <div className="form-group">
            <label>Состав семьи (чел)</label>
            <input type="number" defaultValue="3" />
          </div>
          <div className="form-group">
            <label>Уровень срочности</label>
            <select>
              <option>Обычный</option>
              <option>Высокий</option>
              <option>Критический</option>
            </select>
          </div>
        </div>
        <div className="form-group">
          <label>Пищевые ограничения</label>
          <textarea placeholder="Например: аллергия на лактозу"></textarea>
        </div>
        <button type="button" className="btn btn-primary">Сохранить изменения</button>
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
        {lots.map(lot => (
          <div key={lot.id} className="lot-card-compact">
            <h4>{lot.shop}</h4>
            <p>{lot.description}</p>
            <span className="distance">{lot.distance}</span>
            <button className="btn-small" onClick={() => {
              setActiveOrder({ ...lot, status: 'picking', eta: '15 мин' });
              setActiveTab('order');
            }}>Забронировать</button>
          </div>
        ))}
      </div>
      <div className="limit-notice" style={{marginTop: '20px'}}>
        <p>ℹ️ Вы можете оформить заявку не чаще 1 раза в неделю.</p>
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
            <p><strong>Магазин:</strong> {activeOrder.shop}</p>
            <p><strong>Статус:</strong> Волонтер собирает продукты</p>
            <p><strong>Ожидаемое время:</strong> {activeOrder.eta}</p>
          </div>
          <div className="qr-section">
            <p>Покажите этот код волонтеру при получении:</p>
            <div className="qr-mock">
              <div className="qr-inner"></div>
            </div>
            <span className="qr-code-text">SF-882-X9</span>
          </div>
        </div>
      ) : (
        <p className="empty-msg">У вас нет активных заказов.</p>
      )}
    </div>
  );

  if (status === 'pending') return renderVerification();

  return (
    <div className="dashboard-container">
      <aside className="sidebar">
        <h2>Моя Помощь</h2>
        <nav>
          <button className={activeTab === 'map' ? 'active' : ''} onClick={() => setActiveTab('map')}>Карта лотов</button>
          <button className={activeTab === 'order' ? 'active' : ''} onClick={() => setActiveTab('order')}>Текущий заказ</button>
          <button className={activeTab === 'profile' ? 'active' : ''} onClick={() => setActiveTab('profile')}>Анкета</button>
          <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>История</button>
        </nav>
      </aside>
      
      <main className="main-content">
        <header className="content-header">
          <h1>{activeTab === 'map' ? 'Доступная еда' : activeTab === 'order' ? 'Статус доставки' : activeTab === 'profile' ? 'Мой профиль' : 'Архив помощи'}</h1>
        </header>
        {activeTab === 'map' && renderMap()}
        {activeTab === 'order' && renderOrder()}
        {activeTab === 'profile' && renderProfile()}
        {activeTab === 'history' && <div className="tab-content"><p className="empty-msg">История пуста</p></div>}
      </main>
    </div>
  );
};

export default NeedyDashboard;
