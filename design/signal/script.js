(function () {
  "use strict";

  const $ = (selector, scope = document) => scope.querySelector(selector);
  const $$ = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));

  const header = $("[data-header]");
  const menuToggle = $("[data-menu-toggle]");
  const menu = $("[data-menu]");
  const toast = $("[data-toast]");
  let toastTimer;

  const showToast = (message) => {
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add("is-visible");
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => {
      toast.classList.remove("is-visible");
    }, 3200);
  };

  const closeMenu = () => {
    if (!menuToggle || !menu) return;
    menuToggle.setAttribute("aria-expanded", "false");
    menuToggle.setAttribute("aria-label", "Открыть меню");
    menu.classList.remove("is-open");
  };

  if (menuToggle && menu) {
    menuToggle.addEventListener("click", () => {
      const willOpen = menuToggle.getAttribute("aria-expanded") !== "true";
      menuToggle.setAttribute("aria-expanded", String(willOpen));
      menuToggle.setAttribute("aria-label", willOpen ? "Закрыть меню" : "Открыть меню");
      menu.classList.toggle("is-open", willOpen);
    });

    $$("a, button", menu).forEach((item) => {
      item.addEventListener("click", closeMenu);
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape") closeMenu();
    });
  }

  const updateHeader = () => {
    if (header) header.classList.toggle("is-scrolled", window.scrollY > 16);
  };

  updateHeader();
  window.addEventListener("scroll", updateHeader, { passive: true });

  const makeTabs = ({
    tabSelector,
    panelSelector,
    tabKey,
    panelKey,
    onActivate,
  }) => {
    const tabs = $$(tabSelector);
    const panels = $$(panelSelector);
    if (!tabs.length || !panels.length) return () => {};

    const activate = (key, shouldFocus = false) => {
      tabs.forEach((tab) => {
        const active = tab.dataset[tabKey] === key;
        tab.classList.toggle("is-active", active);
        tab.setAttribute("aria-selected", String(active));
        tab.tabIndex = active ? 0 : -1;
        if (active && shouldFocus) tab.focus();
      });

      panels.forEach((panel) => {
        const active = panel.dataset[panelKey] === key;
        panel.hidden = !active;
        panel.classList.toggle("is-active", active);
      });

      if (typeof onActivate === "function") onActivate(key);
    };

    tabs.forEach((tab, index) => {
      tab.addEventListener("click", () => activate(tab.dataset[tabKey]));
      tab.addEventListener("keydown", (event) => {
        let nextIndex = null;
        if (event.key === "ArrowRight" || event.key === "ArrowDown") {
          nextIndex = (index + 1) % tabs.length;
        }
        if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
          nextIndex = (index - 1 + tabs.length) % tabs.length;
        }
        if (event.key === "Home") nextIndex = 0;
        if (event.key === "End") nextIndex = tabs.length - 1;
        if (nextIndex === null) return;
        event.preventDefault();
        activate(tabs[nextIndex].dataset[tabKey], true);
      });
    });

    return activate;
  };

  makeTabs({
    tabSelector: "[data-dashboard-tab]",
    panelSelector: "[data-dashboard-panel]",
    tabKey: "dashboardTab",
    panelKey: "dashboardPanel",
  });

  const activateRole = makeTabs({
    tabSelector: "[data-role-tab]",
    panelSelector: "[data-role-panel]",
    tabKey: "roleTab",
    panelKey: "rolePanel",
  });

  const impactData = {
    week: {
      total: "642",
      unit: "кг",
      trend: "+14.8% к прошлой неделе",
      co2: "770",
      co2Unit: "кг",
      deliveries: "218",
      meals: "1 284",
      partners: "42",
      heights: [42, 58, 47, 76, 62, 93, 84],
      labels: ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"],
    },
    month: {
      total: "2 784",
      unit: "кг",
      trend: "+11.2% к прошлому месяцу",
      co2: "3.34",
      co2Unit: "т",
      deliveries: "942",
      meals: "5 568",
      partners: "58",
      heights: [38, 52, 66, 57, 78, 86, 95],
      labels: ["01", "05", "10", "15", "20", "25", "30"],
    },
    year: {
      total: "12 840",
      unit: "кг",
      trend: "+31.6% за 12 месяцев",
      co2: "15.4",
      co2Unit: "т",
      deliveries: "4 218",
      meals: "25 680",
      partners: "86",
      heights: [28, 41, 39, 55, 67, 73, 92],
      labels: ["Авг", "Окт", "Дек", "Фев", "Апр", "Июн", "Июл"],
    },
  };

  const setImpactPeriod = (period) => {
    const data = impactData[period];
    if (!data) return;

    $$("[data-period]").forEach((button) => {
      const active = button.dataset.period === period;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", String(active));
    });

    const total = $("[data-impact-total]");
    const trend = $("[data-impact-trend]");
    const co2 = $("[data-impact-co2]");
    const deliveries = $("[data-impact-deliveries]");
    const meals = $("[data-impact-meals]");
    const partners = $("[data-impact-partners]");

    if (total) total.innerHTML = `${data.total} <small>${data.unit}</small>`;
    if (trend) trend.textContent = data.trend;
    if (co2) co2.innerHTML = `${data.co2} <span>${data.co2Unit}</span>`;
    if (deliveries) deliveries.textContent = data.deliveries;
    if (meals) meals.textContent = data.meals;
    if (partners) partners.textContent = data.partners;

    $$("[data-impact-chart] > div").forEach((column, index) => {
      const bar = $("i", column);
      const label = $("span", column);
      if (bar) bar.style.height = `${data.heights[index]}%`;
      if (label) label.textContent = data.labels[index];
    });
  };

  $$("[data-period]").forEach((button) => {
    button.addEventListener("click", () => setImpactPeriod(button.dataset.period));
  });

  const dialog = $("#join-dialog");
  const dialogTitle = $("[data-dialog-title]");
  const dialogKicker = $("[data-dialog-kicker]");
  const dialogLead = $("[data-dialog-lead]");
  const roleNames = {
    shop: "магазина",
    volunteer: "волонтёра",
    recipient: "получателя",
  };
  let dialogMode = "join";

  const openDialog = (mode = "join", suggestedRole = "") => {
    if (!dialog) return;
    dialogMode = mode;

    if (mode === "login") {
      if (dialogKicker) dialogKicker.textContent = "Вход по роли";
      if (dialogTitle) dialogTitle.textContent = "В какой кабинет вы хотите войти?";
      if (dialogLead) dialogLead.textContent = "В рабочем продукте здесь откроется форма авторизации.";
    } else if (suggestedRole && roleNames[suggestedRole]) {
      if (dialogKicker) dialogKicker.textContent = "Следующий шаг";
      if (dialogTitle) dialogTitle.textContent = `Продолжить по сценарию ${roleNames[suggestedRole]}?`;
      if (dialogLead) dialogLead.textContent = "Можно подтвердить роль или выбрать другой сценарий.";
    } else {
      if (dialogKicker) dialogKicker.textContent = "Выберите роль";
      if (dialogTitle) dialogTitle.textContent = "Как вы хотите присоединиться?";
      if (dialogLead) dialogLead.textContent = "Покажем подходящий сценарий и следующий шаг.";
    }

    $$("[data-dialog-role]", dialog).forEach((button) => {
      button.classList.toggle("is-suggested", button.dataset.dialogRole === suggestedRole);
    });

    if (typeof dialog.showModal === "function") {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    document.body.classList.add("dialog-open");
  };

  const closeDialog = () => {
    if (!dialog) return;
    if (typeof dialog.close === "function" && dialog.open) {
      dialog.close();
    } else {
      dialog.removeAttribute("open");
    }
    document.body.classList.remove("dialog-open");
  };

  $$("[data-open-join]").forEach((button) => {
    button.addEventListener("click", () => {
      openDialog(button.dataset.dialogMode || "join", button.dataset.roleChoice || "");
    });
  });

  if (dialog) {
    dialog.addEventListener("close", () => {
      document.body.classList.remove("dialog-open");
    });
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) closeDialog();
    });
  }

  $$("[data-dialog-role]").forEach((button) => {
    button.addEventListener("click", () => {
      const role = button.dataset.dialogRole;
      activateRole(role);
      closeDialog();
      $("#roles")?.scrollIntoView({ behavior: "smooth", block: "start" });
      const action = dialogMode === "login" ? "Вход" : "Сценарий";
      showToast(`${action} для роли «${button.querySelector("strong")?.textContent || role}» выбран — это демонстрация интерфейса.`);
    });
  });

  $$("[data-demo-action]").forEach((button) => {
    button.addEventListener("click", () => {
      showToast("Действие доступно в рабочем продукте. В концепте показан интерфейс сценария.");
    });
  });

  $$(".faq-list details").forEach((details) => {
    details.addEventListener("toggle", () => {
      if (!details.open) return;
      $$(".faq-list details").forEach((other) => {
        if (other !== details) other.open = false;
      });
    });
  });

  const year = $("[data-year]");
  if (year) year.textContent = String(new Date().getFullYear());
})();
