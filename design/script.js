const tabs = [...document.querySelectorAll(".option-tab")];
const frame = document.querySelector("#preview-frame");
const title = document.querySelector("#preview-title");
const note = document.querySelector("#preview-note");
const openLink = document.querySelector("#open-preview");

function selectDesign(tab) {
  const design = tab.dataset.design;

  tabs.forEach((item) => {
    const isCurrent = item === tab;
    item.classList.toggle("is-active", isCurrent);
    item.setAttribute("aria-selected", String(isCurrent));
  });

  frame.src = `./${design}/`;
  frame.title = `Предпросмотр варианта ${tab.dataset.title}`;
  title.textContent = tab.dataset.title;
  note.textContent = tab.dataset.note;
  openLink.href = `./${design}/`;
}

tabs.forEach((tab, index) => {
  tab.addEventListener("click", () => selectDesign(tab));
  tab.addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;

    event.preventDefault();
    let nextIndex = index;

    if (event.key === "ArrowLeft") nextIndex = (index - 1 + tabs.length) % tabs.length;
    if (event.key === "ArrowRight") nextIndex = (index + 1) % tabs.length;
    if (event.key === "Home") nextIndex = 0;
    if (event.key === "End") nextIndex = tabs.length - 1;

    tabs[nextIndex].focus();
    selectDesign(tabs[nextIndex]);
  });
});
