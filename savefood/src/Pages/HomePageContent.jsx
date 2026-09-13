import { Children, cloneElement, isValidElement } from 'react';
import {
  LANDING_LOTS,
  translateLandingAttribute,
  translateLandingText,
} from './HomePage.locale';

const LOCALIZED_ATTRIBUTES = ['aria-label', 'placeholder', 'data-demo-action'];

function localizeLandingNode(node, language) {
  if (typeof node === 'string') {
    return translateLandingText(node, language);
  }
  if (!isValidElement(node)) return node;
  const props = {};
  LOCALIZED_ATTRIBUTES.forEach((attribute) => {
    if (node.props[attribute]) {
      props[attribute] = translateLandingAttribute(node.props[attribute], language);
    }
  });
  if (node.props.children !== undefined) {
    props.children = Children.map(
      node.props.children,
      (child) => localizeLandingNode(child, language),
    );
  }
  return cloneElement(node, props);
}

function AccountIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <circle cx="12" cy="8" r="3.25" />
      <path d="M5.25 19c.6-3.6 3.05-5.4 6.75-5.4s6.15 1.8 6.75 5.4" />
    </svg>
  );
}

export default function HomePageContent({
  copy,
  impactState,
  isAuthenticated = false,
  language,
  previewRole = 'recipient',
  selectedLot = 'bread',
  toastMessage = '',
}) {
  const currentYear = new Date().getFullYear();
  const format = (value, options) => (
    new Intl.NumberFormat(copy.locale, options).format(Number(value) || 0)
  );
  const impactReady = impactState?.status === 'ready' && impactState.summary?.totals;
  const totals = impactReady ? impactState.summary.totals : {};
  const kg = Number(totals.kg) || 0;
  const meals = Number(totals.meals) || 0;
  const deliveries = Number(impactState?.summary?.deliveries_completed) || 0;
  const activeVolunteers = Number(impactState?.summary?.active_volunteers) || 0;
  const co2Kg = Number(totals.co2_kg) || 0;
  const co2Display = co2Kg >= 1000
    ? { value: format(co2Kg / 1000, { maximumFractionDigits: 1 }), unit: copy.tons }
    : { value: format(co2Kg, { maximumFractionDigits: 1 }), unit: copy.kg };
  const months = impactReady && Array.isArray(impactState.summary.by_month)
    ? impactState.summary.by_month.slice(-6)
    : [];
  const maxMonthKg = Math.max(...months.map((entry) => Number(entry.kg) || 0), 1);
  const previousMonthKg = Number(months.at(-2)?.kg) || 0;
  const currentMonthKg = Number(months.at(-1)?.kg) || 0;
  const trend = previousMonthKg > 0
    ? `${currentMonthKg >= previousMonthKg ? '↑' : '↓'} ${format(
      Math.abs((currentMonthKg - previousMonthKg) / previousMonthKg) * 100,
      { maximumFractionDigits: 1 },
    )}%`
    : '';
  const visibleVolunteers = impactReady && Array.isArray(impactState.volunteers)
    ? impactState.volunteers.slice(0, 3)
    : [];
  const visibleCities = impactReady && Array.isArray(impactState.cities)
    ? impactState.cities.slice(0, 3)
    : [];
  const topCityKg = Number(visibleCities[0]?.kg) || 1;
  const impactStatus = copy[`impact${String(impactState?.status || 'loading')
    .replace(/^./, (letter) => letter.toUpperCase())}`] || copy.impactLoading;
  const selectedLotDetails = LANDING_LOTS[language]?.[selectedLot] || LANDING_LOTS[language].bread;
  const content = (
    <>
    <header className="site-header" data-header>
      <div className={`shell header-inner${isAuthenticated ? ' header-inner--authenticated' : ''}`}>
        <a className="brand" href="#top" aria-label="SaveFood — на главную">
          <img className="brand-symbol" src="/savefood-logo.png" alt="" />
          <span className="brand-name">Save<span className="brand-name__accent">Food</span></span>
        </a>

        <button
          className="menu-button"
          type="button"
          aria-expanded="false"
          aria-controls="main-navigation"
          data-menu-button
        >
          <span className="sr-only">Открыть меню</span>
          <span aria-hidden="true"></span>
          <span aria-hidden="true"></span>
        </button>

        <nav className="main-nav" id="main-navigation" aria-label="Основная навигация" data-nav>
          <a href="#how">Как это работает</a>
          <a href="#roles">Для кого</a>
          <a href="#impact">Результаты</a>
          <a href="#faq">Вопросы</a>
        </nav>

        <div className={`header-actions${isAuthenticated ? ' header-actions--authenticated' : ''}`}>
          {isAuthenticated ? (
            <button
              className="button button--small button--primary"
              type="button"
              data-account-action="logout"
            >
              {copy.logout}
            </button>
          ) : (
            <>
              <button className="text-button" type="button" data-auth-mode="login">Войти</button>
              <button className="button button--small button--primary" type="button" data-auth-mode="register">
                Присоединиться
              </button>
            </>
          )}
          <div className="ember-language-switcher" aria-label={language === 'en' ? 'Choose language' : 'Выбор языка'}>
            <button className={language === 'ru' ? 'is-active' : ''} type="button" data-language="ru">RU</button>
            <button className={language === 'en' ? 'is-active' : ''} type="button" data-language="en">EN</button>
          </div>
          {isAuthenticated && (
            <button
              className="landing-account-avatar"
              type="button"
              data-account-action="dashboard"
              aria-label={copy.profileLabel}
              title={copy.profileLabel}
            >
              <AccountIcon />
            </button>
          )}
        </div>
      </div>
    </header>

    <main id="main">
      <section className="hero" id="top" aria-labelledby="hero-title">
        <div className="hero-glow hero-glow--one" aria-hidden="true"></div>
        <div className="hero-glow hero-glow--two" aria-hidden="true"></div>

        <div className="shell hero-grid">
          <div className="hero-copy">
            <p className="eyebrow">
              <span className="eyebrow-dot" aria-hidden="true"></span>
              Еда должна кормить, а не пропадать
            </p>
            <h1 id="hero-title">
              Спасаем еду.
              <span>Доставляем заботу.</span>
            </h1>
            <p className="hero-lead">
              SaveFood соединяет магазины с излишками, волонтёров и людей, которым
              нужна помощь, в один понятный маршрут.
            </p>
            <div className="hero-actions">
              <a className="button button--primary" href="#roles">
                Найти свою роль
                <span aria-hidden="true">→</span>
              </a>
              <a className="button button--ghost" href="#how">
                Как всё устроено
                <span className="play-mark" aria-hidden="true">▶</span>
              </a>
            </div>
            <ul className="trust-list" aria-label="Преимущества платформы">
              <li><span aria-hidden="true">✓</span> Бесплатно для получателей</li>
              <li><span aria-hidden="true">✓</span> QR и GPS-подтверждение</li>
              <li><span aria-hidden="true">✓</span> RU / EN</li>
            </ul>
          </div>

          <div className="product-preview" aria-label="Пример интерфейса SaveFood">
            <div className="product-preview__halo" aria-hidden="true"></div>
            <article className="app-window">
              <div className="app-window__top">
                <div className="app-wordmark">
                  <img className="brand-symbol brand-symbol--mini" src="/savefood-logo.png" alt="" />
                  SaveFood
                </div>
                <div className="preview-location">
                  <span aria-hidden="true">⌖</span>
                  Москва
                </div>
                <button className="avatar" type="button" aria-label="Открыть профиль">А</button>
              </div>

              <div className="preview-tabs" role="tablist" aria-label="Интерфейс участника">
                <button
                  id="tab-recipient"
                  type="button"
                  role="tab"
                  aria-selected={previewRole === 'recipient'}
                  aria-controls="panel-recipient"
                  tabIndex={previewRole === 'recipient' ? 0 : -1}
                  data-preview-tab="recipient"
                >
                  Получатель
                </button>
                <button
                  id="tab-volunteer"
                  type="button"
                  role="tab"
                  aria-selected={previewRole === 'volunteer'}
                  aria-controls="panel-volunteer"
                  tabIndex={previewRole === 'volunteer' ? 0 : -1}
                  data-preview-tab="volunteer"
                >
                  Волонтёр
                </button>
                <button
                  id="tab-shop"
                  type="button"
                  role="tab"
                  aria-selected={previewRole === 'shop'}
                  aria-controls="panel-shop"
                  tabIndex={previewRole === 'shop' ? 0 : -1}
                  data-preview-tab="shop"
                >
                  Магазин
                </button>
              </div>

              <div className="preview-body">
                <section
                  className={`preview-panel${previewRole === 'recipient' ? ' is-entering' : ''}`}
                  id="panel-recipient"
                  role="tabpanel"
                  aria-labelledby="tab-recipient"
                  data-preview-panel="recipient"
                  hidden={previewRole !== 'recipient'}
                >
                  <div className="preview-heading">
                    <div>
                      <p className="preview-kicker">Хамовники · рядом с вами</p>
                      <h2>Доступно 8 лотов</h2>
                    </div>
                    <span className="live-pill"><span aria-hidden="true"></span> live</span>
                  </div>

                  <div className="mini-map" aria-label="Схема доступных лотов">
                    <span className="map-road map-road--one" aria-hidden="true"></span>
                    <span className="map-road map-road--two" aria-hidden="true"></span>
                    <span className="map-road map-road--three" aria-hidden="true"></span>
                    <button
                      className={`map-pin${selectedLot === 'bread' ? ' is-active' : ''}`}
                      type="button"
                      style={{ '--x': '24%', '--y': '38%' }}
                      aria-label="Выбрать лот: хлеб и выпечка"
                      data-lot="bread"
                    >
                      <span aria-hidden="true">1</span>
                    </button>
                    <button
                      className={`map-pin${selectedLot === 'produce' ? ' is-active' : ''}`}
                      type="button"
                      style={{ '--x': '67%', '--y': '27%' }}
                      aria-label="Выбрать лот: овощи и фрукты"
                      data-lot="produce"
                    >
                      <span aria-hidden="true">2</span>
                    </button>
                    <button
                      className={`map-pin${selectedLot === 'meal' ? ' is-active' : ''}`}
                      type="button"
                      style={{ '--x': '76%', '--y': '72%' }}
                      aria-label="Выбрать лот: готовая еда"
                      data-lot="meal"
                    >
                      <span aria-hidden="true">3</span>
                    </button>
                    <span className="you-marker" style={{ '--x': '47%', '--y': '61%' }} aria-hidden="true"></span>
                    <span className="map-label map-label--one" aria-hidden="true">Плющиха</span>
                    <span className="map-label map-label--two" aria-hidden="true">Смоленская</span>
                  </div>

                  <div className="lot-strip" aria-label="Доступные лоты">
                    <button
                      className={`mini-lot${selectedLot === 'bread' ? ' is-active' : ''}`}
                      type="button"
                      aria-pressed={selectedLot === 'bread'}
                      data-lot="bread"
                    >
                      <span className="food-chip food-chip--bread" aria-hidden="true">ХЛ</span>
                      <span>
                        <strong>Хлеб и выпечка</strong>
                        <small>Добрый маркет · 0,8 км</small>
                      </span>
                    </button>
                    <button
                      className={`mini-lot${selectedLot === 'produce' ? ' is-active' : ''}`}
                      type="button"
                      aria-pressed={selectedLot === 'produce'}
                      data-lot="produce"
                    >
                      <span className="food-chip food-chip--produce" aria-hidden="true">ОВ</span>
                      <span>
                        <strong>Овощи и фрукты</strong>
                        <small>Фермерская лавка · 1,2 км</small>
                      </span>
                    </button>
                    <button
                      className={`mini-lot${selectedLot === 'meal' ? ' is-active' : ''}`}
                      type="button"
                      aria-pressed={selectedLot === 'meal'}
                      data-lot="meal"
                    >
                      <span className="food-chip food-chip--meal" aria-hidden="true">ЕД</span>
                      <span>
                        <strong>Готовая еда</strong>
                        <small>Тёплый стол · 1,7 км</small>
                      </span>
                    </button>
                  </div>

                  <div className="selection-bar">
                    <div className="selection-lot">
                      <span>Выбран лот</span>
                      <strong data-lot-title>{selectedLotDetails[0]}</strong>
                      <small data-lot-time>{selectedLotDetails[1]}</small>
                    </div>
                    <div className="selection-statuses" aria-label="Статус доставки">
                      <div className="selection-status selection-status--weight">
                        <span className="selection-status__icon" aria-hidden="true">↗</span>
                        <span className="selection-status__copy">
                          <strong>18 кг</strong>
                          <small>еды в пути</small>
                        </span>
                      </div>
                      <div className="selection-status selection-status--verified">
                        <span className="selection-status__icon" aria-hidden="true">✓</span>
                        <span className="selection-status__copy">
                          <strong>готово</strong>
                          <small>доставка подтверждена</small>
                        </span>
                      </div>
                    </div>
                    <button
                      className="button button--preview"
                      type="button"
                      data-demo-action="Лот добавлен в заявку"
                    >
                      Забронировать
                    </button>
                  </div>
                </section>

                <section
                  className={`preview-panel${previewRole === 'volunteer' ? ' is-entering' : ''}`}
                  id="panel-volunteer"
                  role="tabpanel"
                  aria-labelledby="tab-volunteer"
                  data-preview-panel="volunteer"
                  hidden={previewRole !== 'volunteer'}
                >
                  <div className="preview-heading">
                    <div>
                      <p className="preview-kicker">Маршрут на сегодня</p>
                      <h2>3 точки · 42 мин</h2>
                    </div>
                    <span className="route-distance">7,4 км</span>
                  </div>

                  <div className="route-summary">
                    <div className="route-stat">
                      <span>18 кг</span>
                      <small>вес лотов</small>
                    </div>
                    <div className="route-stat">
                      <span>2 семьи</span>
                      <small>получателя</small>
                    </div>
                    <div className="route-stat">
                      <span>+180</span>
                      <small>баллов</small>
                    </div>
                  </div>

                  <ol className="route-list">
                    <li className="route-point route-point--pickup">
                      <span className="route-node" aria-hidden="true">1</span>
                      <span className="route-line" aria-hidden="true"></span>
                      <div>
                        <small>Забрать · 18:40</small>
                        <strong>Добрый маркет</strong>
                        <span>ул. Плющиха, 22 · 3 лота</span>
                      </div>
                      <span className="route-badge">магазин</span>
                    </li>
                    <li className="route-point">
                      <span className="route-node" aria-hidden="true">2</span>
                      <span className="route-line" aria-hidden="true"></span>
                      <div>
                        <small>Доставить · 19:05</small>
                        <strong>Получатель № 418</strong>
                        <span>2,6 км · подъезд указан</span>
                      </div>
                      <span className="route-badge route-badge--warm">QR</span>
                    </li>
                    <li className="route-point">
                      <span className="route-node" aria-hidden="true">3</span>
                      <div>
                        <small>Доставить · 19:22</small>
                        <strong>Получатель № 573</strong>
                        <span>1,9 км · есть чат</span>
                      </div>
                      <span className="route-badge route-badge--warm">QR</span>
                    </li>
                  </ol>

                  <div className="selection-bar selection-bar--route">
                    <p><span className="verified-dot" aria-hidden="true"></span> Маршрут проверен и оптимизирован</p>
                    <button
                      className="button button--preview"
                      type="button"
                      data-demo-action="Демо-маршрут запущен"
                    >
                      Начать маршрут
                    </button>
                  </div>
                </section>

                <section
                  className={`preview-panel${previewRole === 'shop' ? ' is-entering' : ''}`}
                  id="panel-shop"
                  role="tabpanel"
                  aria-labelledby="tab-shop"
                  data-preview-panel="shop"
                  hidden={previewRole !== 'shop'}
                >
                  <div className="preview-heading">
                    <div>
                      <p className="preview-kicker">Добрый маркет · Профи</p>
                      <h2>Обзор магазина</h2>
                    </div>
                    <span className="live-pill live-pill--calm">июль</span>
                  </div>

                  <div className="shop-stats">
                    <article>
                      <small>Спасено еды</small>
                      <strong>87,4 <span>кг</span></strong>
                      <em>+18% за месяц</em>
                    </article>
                    <article>
                      <small>Активные лоты</small>
                      <strong>12 <span>шт.</span></strong>
                      <em>7 уже забронированы</em>
                    </article>
                    <article>
                      <small>Предотвращено</small>
                      <strong>134 <span>кг CO₂</span></strong>
                      <em>методология v1</em>
                    </article>
                  </div>

                  <div className="shop-overview">
                    <div className="shop-lots">
                      <div className="overview-title">
                        <strong>Лоты сегодня</strong>
                        <span>12 всего</span>
                      </div>
                      <div className="shop-lot-row">
                        <span className="food-chip food-chip--bread" aria-hidden="true">ХЛ</span>
                        <div><strong>Выпечка</strong><small>8,5 кг · до 21:00</small></div>
                        <span className="status status--reserved">бронь</span>
                      </div>
                      <div className="shop-lot-row">
                        <span className="food-chip food-chip--produce" aria-hidden="true">ОВ</span>
                        <div><strong>Овощи</strong><small>12 кг · до 20:30</small></div>
                        <span className="status status--open">доступен</span>
                      </div>
                      <div className="shop-lot-row">
                        <span className="food-chip food-chip--meal" aria-hidden="true">ЕД</span>
                        <div><strong>Кулинария</strong><small>6 порций · до 20:00</small></div>
                        <span className="status status--open">доступен</span>
                      </div>
                    </div>

                    <div className="esg-card">
                      <small>ESG-цель месяца</small>
                      <strong>87 из 120 кг</strong>
                      <div className="progress-track" aria-label="Выполнено 73 процента">
                        <span style={{ '--progress': '73%' }}></span>
                      </div>
                      <p>Ещё 33 кг до цели</p>
                    </div>
                  </div>

                  <div className="selection-bar selection-bar--route">
                    <p><span className="verified-dot" aria-hidden="true"></span> 4 лота распознаны по чеку</p>
                    <button
                      className="button button--preview"
                      type="button"
                      data-demo-action="Открыта форма нового лота"
                    >
                      + Добавить лот
                    </button>
                  </div>
                </section>
              </div>
            </article>
            <p className="preview-caption">Интерактивный preview — переключите роль</p>
          </div>
        </div>
      </section>

      <section className="proof-strip" aria-label="Пример показателей платформы">
        <div className="shell proof-grid">
          <div className="proof-intro">
            <span className="live-pulse" aria-hidden="true"></span>
            <span><strong>Impact</strong> · {impactStatus}</span>
          </div>
          <div className="proof-item">
            <strong>{impactReady ? format(kg) : '—'}</strong>
            <span>кг еды спасено</span>
          </div>
          <div className="proof-item">
            <strong>{impactReady ? format(meals) : '—'}</strong>
            <span>приёмов пищи</span>
          </div>
          <div className="proof-item">
            <strong>{impactReady ? `${co2Display.value} ${co2Display.unit}` : '—'}</strong>
            <span>CO₂ предотвращено</span>
          </div>
          <div className="proof-item">
            <strong>{impactReady ? format(deliveries) : '—'}</strong>
            <span>доставок</span>
          </div>
        </div>
      </section>

      <section className="section section--how" id="how" aria-labelledby="how-title">
        <div className="shell">
          <div className="section-heading section-heading--split">
            <div>
              <p className="eyebrow eyebrow--small">Один поток помощи</p>
              <h2 id="how-title">От полки магазина<br />до семейного стола</h2>
            </div>
            <p>
              Платформа берёт на себя поиск, приоритет заявок, маршрут и подтверждение —
              участникам остаётся сделать один понятный шаг.
            </p>
          </div>

          <ol className="journey">
            <li className="journey-card">
              <div className="journey-card__top">
                <span className="journey-number">01</span>
                <span className="role-tag role-tag--shop">магазин</span>
              </div>
              <div className="journey-symbol journey-symbol--crate" aria-hidden="true">
                <span></span><span></span><span></span>
              </div>
              <h3>Публикует лот</h3>
              <p>
                Добавляет хлеб, овощи или готовую еду вручную либо фотографирует чек —
                позиции распознаются автоматически.
              </p>
              <small>окно выдачи · вес · категория</small>
            </li>
            <li className="journey-card">
              <div className="journey-card__top">
                <span className="journey-number">02</span>
                <span className="role-tag role-tag--volunteer">волонтёр</span>
              </div>
              <div className="journey-symbol journey-symbol--route" aria-hidden="true">
                <span></span><span></span><span></span>
              </div>
              <h3>Берёт маршрут</h3>
              <p>
                Видит ближайшие точки на карте, забирает продукты и следует по
                оптимальному маршруту.
              </p>
              <small>карта · навигация · чат</small>
            </li>
            <li className="journey-card">
              <div className="journey-card__top">
                <span className="journey-number">03</span>
                <span className="role-tag role-tag--recipient">получатель</span>
              </div>
              <div className="journey-symbol journey-symbol--home" aria-hidden="true">
                <span></span><span></span>
              </div>
              <h3>Получает продукты</h3>
              <p>
                Выбирает лот, получает доставку или забирает сам. Передача
                подтверждается QR-кодом и геопроверкой.
              </p>
              <small>QR · GPS до 100 м · уведомление</small>
            </li>
          </ol>
        </div>
      </section>

      <section className="section section--roles" id="roles" aria-labelledby="roles-title">
        <div className="shell roles-layout">
          <div className="roles-copy">
            <p className="eyebrow eyebrow--small">Три роли — одна система</p>
            <h2 id="roles-title">Каждому — свой простой интерфейс</h2>
            <p>
              Внутри нет общего перегруженного кабинета. Каждый участник видит только
              те действия, которые нужны ему прямо сейчас.
            </p>
            <button className="button button--primary" type="button" data-auth-mode="register">
              Выбрать роль
              <span aria-hidden="true">→</span>
            </button>
          </div>

          <div className="role-cards">
            <article className="role-card">
              <div className="role-card__index">01</div>
              <div className="role-card__body">
                <p className="role-card__label">Для магазинов и кафе</p>
                <h3>Передавайте излишки за 2 минуты</h3>
                <ul>
                  <li>Лоты вручную и OCR чеков</li>
                  <li>Уведомления о выдаче</li>
                  <li>ESG-отчёт по кг, CO₂ и приёмам пищи</li>
                </ul>
              </div>
              <button
                className="circle-action"
                type="button"
                aria-label="Присоединиться как магазин"
                data-auth-mode="register"
                data-role-choice="shop"
              >
                <span aria-hidden="true">↗</span>
              </button>
            </article>

            <article className="role-card">
              <div className="role-card__index">02</div>
              <div className="role-card__body">
                <p className="role-card__label">Для волонтёров</p>
                <h3>Помогайте, когда удобно</h3>
                <ul>
                  <li>Маршруты рядом и навигатор</li>
                  <li>Гибкий график и вместимость</li>
                  <li>Баллы, достижения и команды</li>
                </ul>
              </div>
              <button
                className="circle-action"
                type="button"
                aria-label="Присоединиться как волонтёр"
                data-auth-mode="register"
                data-role-choice="volunteer"
              >
                <span aria-hidden="true">↗</span>
              </button>
            </article>

            <article className="role-card">
              <div className="role-card__index">03</div>
              <div className="role-card__body">
                <p className="role-card__label">Для получателей</p>
                <h3>Находите помощь поблизости</h3>
                <ul>
                  <li>Лоты на карте по категориям</li>
                  <li>Доставка или самовывоз</li>
                  <li>История, чат и статус заказа</li>
                </ul>
              </div>
              <button
                className="circle-action"
                type="button"
                aria-label="Подать заявку как получатель"
                data-auth-mode="register"
                data-role-choice="needy"
              >
                <span aria-hidden="true">↗</span>
              </button>
            </article>
          </div>
        </div>
      </section>

      <section className="section section--impact" id="impact" aria-labelledby="impact-title">
        <div className="impact-glow" aria-hidden="true"></div>
        <div className="shell">
          <div className="section-heading section-heading--split impact-heading">
            <div>
              <p className="eyebrow eyebrow--small">
                <span className="eyebrow-dot" aria-hidden="true"></span>
                Открытый impact-дашборд
              </p>
              <h2 id="impact-title">Помощь, которую<br />можно измерить</h2>
            </div>
            <p>
              Публичные показатели по спасённой еде, доставкам, городам и командам
              обновляются в реальном времени.
            </p>
          </div>

          <div className="impact-dashboard">
            <div className="impact-main">
              <div className="impact-main__header">
                <div>
                  <small>{copy.rescuedFood}</small>
                  <strong>{impactReady ? <>{format(kg)} <span>{copy.kg}</span></> : '—'}</strong>
                </div>
                <span className="trend" hidden={!trend}>{trend}</span>
              </div>
              <div
                className="impact-chart"
                role="img"
                aria-label="График спасённой еды за последние месяцы"
              >
                <div className="chart-grid" aria-hidden="true"></div>
                <div className="bars" aria-hidden="true">
                  {months.map((entry, index) => (
                    <span
                      className={index === months.length - 1 ? 'is-current' : ''}
                      key={entry.month}
                      style={{ '--bar': `${Math.max(4, ((Number(entry.kg) || 0) / maxMonthKg) * 100)}%` }}
                    >
                      <i>{new Intl.DateTimeFormat(copy.locale, { month: 'short' })
                        .format(new Date(`${entry.month}-01T00:00:00`)).replace('.', '')}</i>
                    </span>
                  ))}
                </div>
              </div>
            </div>

            <div className="impact-side">
              <article className="impact-metric impact-metric--warm">
                <small>Предотвращено CO₂</small>
                <strong>{impactReady ? <>{co2Display.value} <span>{co2Display.unit}</span></> : '—'}</strong>
                <p>рассчитывается по подтверждённым передачам</p>
              </article>
              <article className="impact-metric">
                <small>Активное сообщество</small>
                <strong>{impactReady ? <>{format(activeVolunteers)} <span>{copy.people}</span></> : '—'}</strong>
                <div className="avatar-stack" aria-label="Участники сообщества">
                  {visibleVolunteers.map((person, index) => (
                    <span key={`${person.name || 'volunteer'}-${index}`}>
                      {String(person.name || (language === 'en' ? 'V' : 'В')).slice(0, 2).toUpperCase()}
                    </span>
                  ))}
                  {impactReady && <span>+{Math.max(0, activeVolunteers - visibleVolunteers.length)}</span>}
                </div>
              </article>
            </div>

            <div className="city-ranking">
              <div className="overview-title">
                <strong>Рейтинг городов</strong>
                <span>по спасённым кг</span>
              </div>
              <ol>
                {visibleCities.map((city, index) => (
                  <li key={`${city.city || 'city'}-${index}`}>
                    <span className="rank">{index + 1}</span>
                    <strong>{city.city || copy.unnamedCity}</strong>
                    <div className="city-bar">
                      <span style={{
                        '--city': `${Math.max(0, Math.min(100, (Number(city.kg) || 0) / topCityKg * 100))}%`,
                      }}></span>
                    </div>
                    <em>{format(city.kg)} {copy.kg}</em>
                  </li>
                ))}
              </ol>
            </div>
          </div>
        </div>
      </section>

      <section className="section section--faq" id="faq" aria-labelledby="faq-title">
        <div className="shell faq-layout">
          <div className="faq-intro">
            <p className="eyebrow eyebrow--small">Коротко о важном</p>
            <h2 id="faq-title">Частые вопросы</h2>
            <p>
              Не нашли ответ? Напишите в Telegram — команда поможет разобраться.
            </p>
            <a className="inline-link" href="https://t.me/My_funny550_bot" target="_blank" rel="noreferrer">
              Открыть Telegram
              <span aria-hidden="true">↗</span>
            </a>
          </div>

          <div className="faq-list">
            <details name="savefood-faq">
              <summary>
                Это бесплатно?
                <span aria-hidden="true"></span>
              </summary>
              <div className="faq-answer"><div>
                <p>
                  Для получателей и волонтёров — да. Ритейлу доступны тарифы с
                  дополнительными возможностями: OCR чеков, расширенные лимиты и ESG-отчёт.
                </p>
              </div></div>
            </details>
            <details name="savefood-faq">
              <summary>
                Кто может получить помощь?
                <span aria-hidden="true"></span>
              </summary>
              <div className="faq-answer"><div>
                <p>
                  Семьи и люди в трудной жизненной ситуации после проверки анкеты.
                  Подтверждающий документ загружается при регистрации.
                </p>
              </div></div>
            </details>
            <details name="savefood-faq">
              <summary>
                Как подтверждается доставка?
                <span aria-hidden="true"></span>
              </summary>
              <div className="faq-answer"><div>
                <p>
                  Получатель показывает QR-код, а платформа проверяет, что волонтёр находится
                  рядом с точкой передачи. Для отдельных сценариев добавляется фото.
                </p>
              </div></div>
            </details>
            <details name="savefood-faq">
              <summary>
                Можно забрать продукты самостоятельно?
                <span aria-hidden="true"></span>
              </summary>
              <div className="faq-answer"><div>
                <p>
                  Да. При бронировании доступен самовывоз, если магазин разрешил этот способ.
                  Выдачу также подтверждают QR-кодом.
                </p>
              </div></div>
            </details>
          </div>
        </div>
      </section>

      <section className="final-cta" aria-labelledby="final-cta-title">
        <div className="final-cta__glow" aria-hidden="true"></div>
        <div className="shell final-cta__inner">
          <p className="eyebrow eyebrow--small">Присоединяйтесь</p>
          <h2 id="final-cta-title">У еды есть второй шанс.<br />И он начинается с вас.</h2>
          <p>Выберите роль — регистрация займёт несколько минут.</p>
          <button className="button button--light" type="button" data-auth-mode="register">
            Начать сейчас
            <span aria-hidden="true">→</span>
          </button>
        </div>
      </section>
    </main>

    <footer className="site-footer">
      <div className="shell footer-main">
        <div className="footer-brand">
          <a className="brand" href="#top" aria-label="SaveFood — на главную">
            <img className="brand-symbol" src="/savefood-logo.png" alt="" />
            <span className="brand-name">Save<span className="brand-name__accent">Food</span></span>
          </a>
          <p>Превращаем излишки продуктов в реальную помощь.</p>
        </div>
        <div className="footer-links">
          <div>
            <h2>Платформа</h2>
            <a href="/auth?mode=register&amp;role=shop">Магазинам</a>
            <a href="/auth?mode=register&amp;role=volunteer">Волонтёрам</a>
            <a href="/auth?mode=register&amp;role=needy">Получателям</a>
          </div>
          <div>
            <h2>О проекте</h2>
            <a href="#how">Как это работает</a>
            <a href="/impact">Impact-дашборд</a>
            <a href="#faq">FAQ</a>
          </div>
          <div>
            <h2>Документы</h2>
            <a href="/terms">Публичная оферта</a>
            <a href="/privacy">Конфиденциальность</a>
          </div>
        </div>
      </div>
      <div className="shell footer-bottom" id="footer-note">
        <p>© <span data-current-year>{currentYear}</span> SaveFood. Дизайн-концепт Ember.</p>
        <p>Сделано с заботой к еде и людям.</p>
      </div>
    </footer>

    <div
      className={`toast${toastMessage ? ' is-visible' : ''}`}
      role="status"
      aria-live="polite"
      aria-atomic="true"
      data-toast
    >
      {toastMessage}
    </div>

    </>
  );
  return localizeLandingNode(content, language);
}
