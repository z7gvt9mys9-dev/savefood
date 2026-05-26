import React, { useState, useEffect } from 'react';
import AddressInput from '../Auth/AddressInput';
import './Shop.css';

const ShopDashboard = () => {
  const [activeTab, setActiveTab] = useState('overview');
  const [lots, setLots] = useState([]);
  const [history, setHistory] = useState([]);
  const [shopInfo, setShopInfo] = useState({ name: 'Загрузка...', id: 1 }); // Mock ID for now
  
  // Form State
  const [newLot, setNewLot] = useState({
    description: '',
    quantity: 1,
    category: 'Выпечка',
    expiry_date: '',
    address: '',
    time_slot: '18:00 - 20:00',
    photo: null
  });

  useEffect(() => {
    // In a real app, we'd get shop_id from auth context
    const shopId = 1; 
    fetch(`http://localhost:8000/shops/${shopId}`)
      .then(res => res.json())
      .then(data => setShopInfo(data));

    fetch(`http://localhost:8000/shops/${shopId}/lots`)
      .then(res => res.json())
      .then(data => setLots(data));

    fetch(`http://localhost:8000/shops/${shopId}/history`)
      .then(res => res.json())
      .then(data => setHistory(data));
  }, []);

  const handleCreateLot = (e) => {
    e.preventDefault();
    console.log("Creating lot:", newLot);
    // Mock success
    alert("Лот успешно создан!");
    setActiveTab('active');
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
        <p>Адрес: {shopInfo.address || 'Москва, ул. Тверская, 10'}</p>
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
          <input type="file" />
        </div>

        <div className="warning-box">
          <p>⚠️ Мы автоматически скроем этот лот за 24 часа до истечения срока годности.</p>
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
              <p>Истекает: {new Date(lot.expiry_date).toLocaleString()}</p>
            </div>
            <div className="lot-actions">
              <button className="btn-small">Изменить</button>
              <button className="btn-small btn-danger">Удалить</button>
            </div>
          </div>
        ))}
      </div>
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
        </nav>
      </aside>
      
      <main className="main-content">
        <header className="content-header">
          <h1>{activeTab === 'overview' ? 'Обзор' : activeTab === 'create' ? 'Новый лот' : activeTab === 'active' ? 'Мониторинг лотов' : 'Архив списаний'}</h1>
        </header>
        {activeTab === 'overview' && renderOverview()}
        {activeTab === 'create' && renderCreateLot()}
        {activeTab === 'active' && renderActiveLots()}
        {activeTab === 'history' && renderHistory()}
      </main>
    </div>
  );
};

export default ShopDashboard;
