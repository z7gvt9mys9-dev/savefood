"use strict";

const header = document.querySelector("[data-header]");
const menuButton = document.querySelector("[data-menu-button]");
const navigation = document.querySelector("[data-nav]");
const previewTabs = Array.from(document.querySelectorAll("[data-preview-tab]"));
const previewPanels = Array.from(document.querySelectorAll("[data-preview-panel]"));
const dialog = document.querySelector("#join-dialog");
const dialogForm = document.querySelector("[data-join-form]");
const dialogClose = document.querySelector("[data-dialog-close]");
const dialogEyebrow = document.querySelector("[data-dialog-eyebrow]");
const dialogTitle = document.querySelector("[data-dialog-title]");
const dialogLead = document.querySelector("[data-dialog-lead]");
const toast = document.querySelector("[data-toast]");

let toastTimer;

function showToast(message) {
  window.clearTimeout(toastTimer);
  toast.textContent = message;
  toast.classList.add("is-visible");
  toastTimer = window.setTimeout(() => {
    toast.classList.remove("is-visible");
  }, 2800);
}

function closeMenu() {
  if (!menuButton || !navigation) return;
  menuButton.setAttribute("aria-expanded", "false");
  navigation.classList.remove("is-open");
}

if (menuButton && navigation) {
  menuButton.addEventListener("click", () => {
    const isOpen = menuButton.getAttribute("aria-expanded") === "true";
    menuButton.setAttribute("aria-expanded", String(!isOpen));
    navigation.classList.toggle("is-open", !isOpen);
  });

  navigation.querySelectorAll("a").forEach((link) => {
    link.addEventListener("click", closeMenu);
  });
}

window.addEventListener(
  "scroll",
  () => {
    header?.classList.toggle("is-scrolled", window.scrollY > 20);
  },
  { passive: true },
);

function activatePreview(role, focusTab = false) {
  previewTabs.forEach((tab) => {
    const isSelected = tab.dataset.previewTab === role;
    tab.setAttribute("aria-selected", String(isSelected));
    tab.tabIndex = isSelected ? 0 : -1;
    if (isSelected && focusTab) tab.focus();
  });

  previewPanels.forEach((panel) => {
    panel.hidden = panel.dataset.previewPanel !== role;
  });
}

previewTabs.forEach((tab, index) => {
  tab.addEventListener("click", () => activatePreview(tab.dataset.previewTab));
  tab.addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();

    let nextIndex = index;
    if (event.key === "ArrowRight") nextIndex = (index + 1) % previewTabs.length;
    if (event.key === "ArrowLeft") {
      nextIndex = (index - 1 + previewTabs.length) % previewTabs.length;
    }
    if (event.key === "Home") nextIndex = 0;
    if (event.key === "End") nextIndex = previewTabs.length - 1;

    activatePreview(previewTabs[nextIndex].dataset.previewTab, true);
  });
});

const lotContent = {
  bread: {
    title: "Хлеб и выпечка",
    time: "Забрать сегодня, 19:00–20:30",
  },
  produce: {
    title: "Овощи и фрукты",
    time: "Забрать сегодня, 18:30–20:00",
  },
  meal: {
    title: "Готовая еда",
    time: "Доставка сегодня, 19:30–21:00",
  },
};

const lotControls = Array.from(document.querySelectorAll("[data-lot]"));
const selectedLotTitle = document.querySelector("[data-lot-title]");
const selectedLotTime = document.querySelector("[data-lot-time]");

function selectLot(lotKey) {
  const content = lotContent[lotKey];
  if (!content) return;

  lotControls.forEach((control) => {
    const isSelected = control.dataset.lot === lotKey;
    control.classList.toggle("is-active", isSelected);
    if (control.classList.contains("mini-lot")) {
      control.setAttribute("aria-pressed", String(isSelected));
    }
  });

  selectedLotTitle.textContent = content.title;
  selectedLotTime.textContent = content.time;
}

lotControls.forEach((control) => {
  control.addEventListener("click", () => selectLot(control.dataset.lot));
});

document.querySelectorAll("[data-demo-action]").forEach((button) => {
  button.addEventListener("click", () => {
    showToast(`${button.dataset.demoAction}. Это интерактивный дизайн-прототип.`);
  });
});

const roleLabels = {
  shop: "магазин",
  volunteer: "волонтёр",
  recipient: "получатель",
};

function setDialogMode(mode, roleChoice) {
  const isLogin = mode === "login";

  dialogEyebrow.textContent = isLogin ? "С возвращением" : "Начнём знакомство";
  dialogTitle.textContent = isLogin
    ? "Войти в SaveFood"
    : "Кем вы хотите присоединиться?";
  dialogLead.textContent = isLogin
    ? "В прототипе форма показывает будущий сценарий авторизации."
    : "Роль определяет набор инструментов в личном кабинете.";

  const roleChoiceElement = roleChoice
    ? dialogForm.querySelector(`input[name="role"][value="${roleChoice}"]`)
    : null;
  if (roleChoiceElement) roleChoiceElement.checked = true;
}

document.querySelectorAll("[data-open-dialog]").forEach((trigger) => {
  trigger.addEventListener("click", () => {
    if (!dialog) return;
    setDialogMode(trigger.dataset.openDialog, trigger.dataset.roleChoice);
    dialog.showModal();
  });
});

dialogClose?.addEventListener("click", () => dialog.close());

dialog?.addEventListener("click", (event) => {
  if (event.target === dialog) dialog.close();
});

dialogForm?.addEventListener("submit", (event) => {
  event.preventDefault();
  if (!dialogForm.reportValidity()) return;

  const data = new FormData(dialogForm);
  const selectedRole = roleLabels[data.get("role")] || "участник";
  dialog.close();
  dialogForm.reset();
  showToast(`Роль «${selectedRole}» выбрана. В рабочей версии откроется регистрация.`);
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeMenu();
});

document.querySelectorAll("[data-current-year]").forEach((node) => {
  node.textContent = String(new Date().getFullYear());
});
