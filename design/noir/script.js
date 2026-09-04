(() => {
  "use strict";
  const one = (selector, scope = document) => scope.querySelector(selector);
  const all = (selector, scope = document) => [...scope.querySelectorAll(selector)];
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const toast = one("[data-toast]");
  let toastTimer;
  const showToast = (message) => {
    if (!toast) return;
    one("span", toast).textContent = message;
    toast.classList.add("is-visible");
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => toast.classList.remove("is-visible"), 2800);
  };
  const header = one("[data-header]");
  const menu = one("[data-menu]");
  const menuToggle = one("[data-menu-toggle]");
  const setMenu = (isOpen) => {
    if (!menu || !menuToggle) return;
    menu.classList.toggle("is-open", isOpen);
    menuToggle.setAttribute("aria-expanded", String(isOpen));
    menuToggle.setAttribute("aria-label", isOpen ? "Закрыть меню" : "Открыть меню");
    document.body.classList.toggle("menu-open", isOpen);
  };
  menuToggle?.addEventListener("click", () => {
    setMenu(menuToggle.getAttribute("aria-expanded") !== "true");
  });
  all("a", menu).forEach((link) => link.addEventListener("click", () => setMenu(false)));
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && menuToggle?.getAttribute("aria-expanded") === "true") {
      setMenu(false);
      menuToggle.focus();
    }
  });
  const updateHeader = () => header?.classList.toggle("is-scrolled", window.scrollY > 24);
  updateHeader();
  window.addEventListener("scroll", updateHeader, { passive: true });
  const navLinks = all('.site-nav a[href^="#"]');
  const navSections = navLinks
    .map((link) => one(link.getAttribute("href")))
    .filter(Boolean);
  if ("IntersectionObserver" in window && navSections.length) {
    const navObserver = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
        if (!visible) return;
        navLinks.forEach((link) => {
          const active = link.getAttribute("href") === `#${visible.target.id}`;
          link.classList.toggle("is-active", active);
          if (active) link.setAttribute("aria-current", "location");
          else link.removeAttribute("aria-current");
        });
      },
      { rootMargin: "-20% 0px -65% 0px", threshold: [0.05, 0.25, 0.6] },
    );
    navSections.forEach((section) => navObserver.observe(section));
  }
  const reveals = all(".reveal");
  if (reducedMotion || !("IntersectionObserver" in window)) {
    reveals.forEach((element) => element.classList.add("is-visible"));
  } else {
    const revealObserver = new IntersectionObserver(
      (entries, observer) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -30px" },
    );
    reveals.forEach((element) => revealObserver.observe(element));
  }
  const counters = all("[data-counter]");
  const formatCounter = (value, decimals) =>
    new Intl.NumberFormat("ru-RU", {
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    }).format(value);
  const runCounter = (element) => {
    if (element.dataset.ran === "true") return;
    element.dataset.ran = "true";
    const target = Number(element.dataset.counter);
    const decimals = Number(element.dataset.decimals || 0);
    if (reducedMotion) {
      element.textContent = formatCounter(target, decimals);
      return;
    }
    const startedAt = performance.now();
    const duration = 1250;
    const step = (now) => {
      const progress = Math.min((now - startedAt) / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      element.textContent = formatCounter(target * eased, decimals);
      if (progress < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  };
  if ("IntersectionObserver" in window) {
    const counterObserver = new IntersectionObserver(
      (entries, observer) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          runCounter(entry.target);
          observer.unobserve(entry.target);
        });
      },
      { threshold: 0.55 },
    );
    counters.forEach((counter) => counterObserver.observe(counter));
  } else {
    counters.forEach(runCounter);
  }
  const productTabs = all("[data-product-tab]");
  const productPanels = all("[data-product-panel]");
  const activateProductTab = (role, shouldFocus = false) => {
    productTabs.forEach((tab) => {
      const active = tab.dataset.productTab === role;
      tab.setAttribute("aria-selected", String(active));
      tab.tabIndex = active ? 0 : -1;
      if (active && shouldFocus) tab.focus();
    });
    productPanels.forEach((panel) => {
      panel.hidden = panel.dataset.productPanel !== role;
    });
  };
  productTabs.forEach((tab, index) => {
    tab.addEventListener("click", () => activateProductTab(tab.dataset.productTab));
    tab.addEventListener("keydown", (event) => {
      let nextIndex;
      if (event.key === "ArrowRight" || event.key === "ArrowDown") {
        nextIndex = (index + 1) % productTabs.length;
      } else if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
        nextIndex = (index - 1 + productTabs.length) % productTabs.length;
      } else if (event.key === "Home") {
        nextIndex = 0;
      } else if (event.key === "End") {
        nextIndex = productTabs.length - 1;
      }
      if (nextIndex === undefined) return;
      event.preventDefault();
      activateProductTab(productTabs[nextIndex].dataset.productTab, true);
    });
  });
  one('[data-demo-action="lot"]')?.addEventListener("click", (event) => {
    const count = one("[data-lot-count]");
    if (count && count.textContent.trim() === "03") count.textContent = "04";
    event.currentTarget.textContent = "Лот добавлен ✓";
    showToast("Черновик лота добавлен в список");
  });
  one('[data-demo-action="route"]')?.addEventListener("click", (event) => {
    const button = event.currentTarget;
    const routeStatus = one(".route-status");
    button.classList.add("is-taken");
    button.innerHTML = 'Маршрут принят <span aria-hidden="true">✓</span>';
    button.disabled = true;
    if (routeStatus) routeStatus.textContent = "Следующая точка: кафе «Хлеб и точка» · 8 минут";
    showToast("Маршрут закреплён за вами");
  });
  one('[data-demo-action="message"]')?.addEventListener("click", () => {
    showToast("Чат с волонтёром открыт в приложении");
  });
  all(".demo-pin").forEach((pin) => {
    pin.addEventListener("click", () => showToast(pin.getAttribute("aria-label")));
  });
  one(".product-preview__topbar > button")?.addEventListener("click", () => {
    showToast("2 новых уведомления о доставках");
  });
  const faqButtons = all(".faq__list button[aria-controls]");
  faqButtons.forEach((button) => {
    button.addEventListener("click", () => {
      const isOpen = button.getAttribute("aria-expanded") === "true";
      faqButtons.forEach((item) => {
        const answer = one(`#${item.getAttribute("aria-controls")}`);
        item.setAttribute("aria-expanded", "false");
        if (answer) answer.hidden = true;
      });
      if (!isOpen) {
        const answer = one(`#${button.getAttribute("aria-controls")}`);
        button.setAttribute("aria-expanded", "true");
        if (answer) answer.hidden = false;
      }
    });
  });
  const authDialog = one("[data-auth-dialog]");
  const closeAuthButton = one("[data-close-auth]");
  const authRoleButtons = all("[data-auth-role]");
  const authModeButtons = all("[data-auth-mode]");
  const registerFields = one("[data-register-fields]");
  const consent = one("[data-consent]");
  const authForm = one("[data-auth-form]");
  const authSubmit = one(".auth-submit");
  const passwordInput = one('input[name="password"]', authForm);
  let authMode = "register";
  const roleAliases = {
    shop: "shop",
    volunteer: "volunteer",
    receiver: "receiver",
  };
  const setAuthRole = (role) => {
    const selectedRole = roleAliases[role] || "shop";
    authRoleButtons.forEach((button) => {
      button.setAttribute("aria-pressed", String(button.dataset.authRole === selectedRole));
    });
  };
  const setAuthMode = (mode) => {
    authMode = mode === "login" ? "login" : "register";
    authModeButtons.forEach((button) => {
      button.setAttribute("aria-selected", String(button.dataset.authMode === authMode));
    });
    const isRegister = authMode === "register";
    if (registerFields) registerFields.hidden = !isRegister;
    if (consent) {
      consent.hidden = !isRegister;
      const checkbox = one('input[type="checkbox"]', consent);
      if (checkbox) checkbox.required = isRegister;
    }
    if (passwordInput) {
      passwordInput.autocomplete = isRegister ? "new-password" : "current-password";
      passwordInput.placeholder = isRegister ? "Минимум 8 символов" : "Ваш пароль";
    }
    if (authSubmit) {
      authSubmit.innerHTML = isRegister
        ? 'Создать аккаунт <span aria-hidden="true">↗</span>'
        : 'Войти <span aria-hidden="true">↗</span>';
    }
  };
  const openAuth = (role) => {
    if (!authDialog) return;
    if (role) {
      setAuthMode("register");
      setAuthRole(role);
    }
    if (typeof authDialog.showModal === "function") authDialog.showModal();
    else authDialog.setAttribute("open", "");
    document.body.classList.add("dialog-open");
  };
  const closeAuth = () => {
    if (!authDialog) return;
    if (typeof authDialog.close === "function") authDialog.close();
    else authDialog.removeAttribute("open");
    document.body.classList.remove("dialog-open");
  };
  all("[data-open-auth]").forEach((button) => {
    button.addEventListener("click", () => openAuth());
  });
  all("[data-role]").forEach((button) => {
    button.addEventListener("click", () => openAuth(button.dataset.role));
  });
  closeAuthButton?.addEventListener("click", closeAuth);
  authDialog?.addEventListener("cancel", (event) => {
    event.preventDefault();
    closeAuth();
  });
  authDialog?.addEventListener("click", (event) => {
    const rect = authDialog.getBoundingClientRect();
    const outside =
      event.clientX < rect.left ||
      event.clientX > rect.right ||
      event.clientY < rect.top ||
      event.clientY > rect.bottom;
    if (outside) closeAuth();
  });
  authRoleButtons.forEach((button) => {
    button.addEventListener("click", () => setAuthRole(button.dataset.authRole));
  });
  authModeButtons.forEach((button) => {
    button.addEventListener("click", () => setAuthMode(button.dataset.authMode));
  });
  authForm?.addEventListener("submit", (event) => {
    event.preventDefault();
    if (!authForm.reportValidity()) return;
    closeAuth();
    authForm.reset();
    setAuthRole("shop");
    showToast(authMode === "register" ? "Демо-аккаунт создан" : "Демо-вход выполнен");
  });
  setAuthMode("register");
  const clock = one("[data-clock]");
  const updateClock = () => {
    if (clock) {
      clock.textContent = new Date().toLocaleTimeString("ru-RU", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
      });
    }
  };
  updateClock();
  window.setInterval(updateClock, 1000);
  all("[data-year]").forEach((element) => {
    element.textContent = String(new Date().getFullYear());
  });
})();
