const api = globalThis.browser ?? globalThis.chrome;

const enabledBox = document.getElementById("enabled");
const siteBox = document.getElementById("site");
const siteRow = document.getElementById("siteRow");
const hostLabel = document.getElementById("host");
const status = document.getElementById("status");

let host = null;

function note() {
  status.textContent = "Reload the page to apply.";
}

async function init() {
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });
  try {
    const url = new URL(tab.url);
    if (url.protocol === "http:" || url.protocol === "https:") {
      host = url.hostname.replace(/^www\./, "");
    }
  } catch (e) {
    // No readable URL (browser page, new tab, etc.)
  }

  const s = await api.storage.local.get({ enabled: true, allowlist: [] });
  enabledBox.checked = s.enabled;

  if (host) {
    hostLabel.textContent = host;
    siteBox.checked = !s.allowlist.includes(host);
  } else {
    siteRow.style.display = "none";
  }
}

enabledBox.addEventListener("change", async () => {
  await api.storage.local.set({ enabled: enabledBox.checked });
  note();
});

siteBox.addEventListener("change", async () => {
  const s = await api.storage.local.get({ allowlist: [] });
  const list = s.allowlist.filter((d) => d !== host);
  if (!siteBox.checked) list.push(host);
  await api.storage.local.set({ allowlist: list });
  note();
});

init();
