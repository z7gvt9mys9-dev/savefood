import React, { useState } from 'react';
import { YMaps, Map, Placemark } from '@pbe/react-yandex-maps';
import './Volunteer.css';

const VolunteerDashboard = () => {
  const [activeTab, setActiveTab] = useState('map');
  const [activeRoute, setActiveRoute] = useState(null);
  const [scanning, setScanning] = useState(false);

  const tasks = [
    { 
      id: 1, 
      shop: 'ВкусВилл', 
      target: 'ул. Ленина, 5', 
      items: '3 кг овощей', 
      distance: '500м', 
      shopCoords: [55.751244, 37.618423],
      targetCoords: [55.755244, 37.625423]
    },
    { 
      id: 2, 
      shop: 'Перекресток', 
      target: 'пр. Мира, 12', 
      items: '5 кг набора', 
      distance: '1.5км', 
      shopCoords: [55.761244, 37.628423],
      targetCoords: [55.771244, 37.638423]
    },
  ];

  const handleTakeTask = (task) => {
    setActiveRoute({ ...task, step: 'pickup', gps_ok: true });
    setActiveTab('route');
  };

  const renderMap = () => (
    <div className="volunteer-tab">
      <div className="map-container-mobile">
        <YMaps query={{ apikey: process.env.REACT_APP_YANDEX_MAPS_API_KEY }}>
          <Map 
            defaultState={{ center: [55.75, 37.62], zoom: 12 }} 
            width="100%" 
            height="100%"
          >
            {tasks.map(t => (
              <Placemark 
                key={t.id} 
                geometry={t.shopCoords} 
                properties={{
                  balloonContent: `<strong>${t.shop}</strong><br/>${t.items}`
                }}
                onClick={() => handleTakeTask(t)}
              />
            ))}
          </Map>
        </YMaps>
      </div>
      <div className="task-list-mobile">
        <h3>Доступные задачи</h3>
        {tasks.map(t => (
          <div key={t.id} className="task-card-mobile" onClick={() => handleTakeTask(t)}>
            <div className="task-info">
              <h4>{t.shop} → {t.target}</h4>
              <p>{t.items}</p>
            </div>
            <span className="task-dist">{t.distance}</span>
          </div>
        ))}
      </div>
    </div>
  );

  const renderRoute = () => (
    <div className="volunteer-tab route-page">
      {!activeRoute ? (
        <p className="empty-msg">У вас нет активного маршрута.</p>
      ) : (
        <>
          <div className="map-container-mobile mini-map">
            <YMaps query={{ apikey: process.env.REACT_APP_YANDEX_MAPS_API_KEY }}>
              <Map 
                state={{ 
                  center: activeRoute.shopCoords, 
                  zoom: 14 
                }} 
                width="100%" 
                height="100%"
              >
                <Placemark 
                  geometry={activeRoute.shopCoords} 
                  properties={{ balloonContent: 'Магазин (A)' }}
                  options={{ preset: 'islands#redShoppingIcon' }}
                />
                <Placemark 
                  geometry={activeRoute.targetCoords} 
                  properties={{ balloonContent: 'Получатель (B)' }}
                  options={{ preset: 'islands#greenHomeIcon' }}
                />
              </Map>
            </YMaps>
          </div>
          <div className="navigator-card">
          <div className="route-header">
            <h2>{activeRoute.step === 'pickup' ? 'Заберите из магазина' : 'Доставьте получателю'}</h2>
            <span className="badge">В пути</span>
          </div>

          <div className="route-points">
            <div className={`point ${activeRoute.step === 'pickup' ? 'current' : 'done'}`}>
              <div className="point-icon">A</div>
              <div className="point-text">
                <p className="point-label">Магазин</p>
                <p className="point-addr">{activeRoute.shop}</p>
              </div>
            </div>
            <div className={`point ${activeRoute.step === 'delivery' ? 'current' : ''}`}>
              <div className="point-icon">B</div>
              <div className="point-text">
                <p className="point-label">Получатель</p>
                <p className="point-addr">{activeRoute.target}</p>
              </div>
            </div>
          </div>

          <div className="navigation-actions">
            {activeRoute.step === 'pickup' ? (
              <button className="btn btn-primary btn-full" onClick={() => setActiveRoute({...activeRoute, step: 'delivery'})}>
                Я ЗАБРАЛ (Уведомить получателя)
              </button>
            ) : (
              <>
                <div className="gps-status">
                  <span className={activeRoute.gps_ok ? 'gps-on' : 'gps-off'}>
                    {activeRoute.gps_ok ? '● Геолокация подтверждена (<100м)' : '● Вы слишком далеко'}
                  </span>
                </div>
                
                {scanning ? (
                  <div className="scanner-mock" onClick={() => {
                    alert('QR-код успешно отсканирован! Доставка завершена.');
                    setActiveRoute(null);
                    setActiveTab('history');
                  }}>
                    <div className="scanner-frame"></div>
                    <p>Наведите камеру на QR-код получателя</p>
                    <button className="btn-small" onClick={() => setScanning(false)}>Отмена</button>
                  </div>
                ) : (
                  <button 
                    className="btn btn-primary btn-full" 
                    disabled={!activeRoute.gps_ok}
                    onClick={() => setScanning(true)}
                  >
                    СКАНЕР QR-КОДА
                  </button>
                )}
              </>
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
        {activeTab === 'history' && (
          <div className="volunteer-tab">
            <h3>Моя статистика</h3>
            <div className="stats-row">
              <div className="v-stat"><span>12</span> Доставок</div>
              <div className="v-stat"><span>45км</span> Пройдено</div>
            </div>
            <p className="empty-msg">История пуста</p>
          </div>
        )}
      </main>
      
      <nav className="mobile-nav">
        <button className={activeTab === 'map' ? 'active' : ''} onClick={() => setActiveTab('map')}>Карта</button>
        <button className={activeTab === 'route' ? 'active' : ''} onClick={() => setActiveTab('route')}>Маршрут</button>
        <button className={activeTab === 'history' ? 'active' : ''} onClick={() => setActiveTab('history')}>История</button>
      </nav>
    </div>
  );
};

export default VolunteerDashboard;
