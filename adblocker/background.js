// Background: keeps blocking state in sync with saved settings.
const api = globalThis.browser ?? globalThis.chrome;
const ALLOW_RULE_ID = 1000;
const RULESETS = ["ads", "popups", "easylist"];
const COSMETIC_SCRIPT_ID = "cosmetic";

// When a pop-up tab is opened straight onto a blocked ad page, close it instead of
// leaving a "blocked" error tab behind. Only tabs opened by another tab are closed.
if (api.webNavigation && api.webNavigation.onErrorOccurred) {
  api.webNavigation.onErrorOccurred.addListener(async (details) => {
    if (details.frameId !== 0) return;
    if (details.error !== "net::ERR_BLOCKED_BY_CLIENT") return;
    try {
      const tab = await api.tabs.get(details.tabId);
      if (tab.openerTabId !== undefined) await api.tabs.remove(details.tabId);
    } catch (e) {
      // Tab already gone.
    }
  });
}

// Site-specific hiding rules (from EasyList), loaded the first time a page asks.
let specificRules = null;
function loadSpecificRules() {
  if (!specificRules) {
    specificRules = fetch(api.runtime.getURL("rules/specific.json"))
      .then((r) => r.json())
      .catch(() => ({}));
  }
  return specificRules;
}

// A page asks: "which hiding rules apply to my site?" The answer covers the host and
// its parent domains (news.example.com also gets the rules for example.com).
api.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message || message.type !== "cosmetic") return;
  loadSpecificRules().then((map) => {
    const selectors = [];
    let domain = String(message.host || "");
    while (domain) {
      if (Object.prototype.hasOwnProperty.call(map, domain)) {
        selectors.push(...map[domain]);
      }
      const dot = domain.indexOf(".");
      if (dot < 0) break;
      domain = domain.slice(dot + 1);
    }
    sendResponse(selectors);
  });
  return true; // the answer is sent later
});

// The hiding rules for every site are one stylesheet, registered here so that it can be
// switched off globally and skipped on allowlisted sites.
async function applyCosmetics(enabled, allowlist) {
  if (!api.scripting || !api.scripting.registerContentScripts) return;
  try {
    await api.scripting.unregisterContentScripts({ ids: [COSMETIC_SCRIPT_ID] });
  } catch (e) {
    // Was not registered.
  }
  if (!enabled) return;

  const script = {
    id: COSMETIC_SCRIPT_ID,
    css: ["rules/generic.css"],
    matches: ["<all_urls>"],
    allFrames: true,
    runAt: "document_start",
  };
  const excludeMatches = allowlist.flatMap((d) => [`*://${d}/*`, `*://*.${d}/*`]);
  if (excludeMatches.length) script.excludeMatches = excludeMatches;
  await api.scripting.registerContentScripts([script]);
}

async function applySettings() {
  const { enabled, allowlist } = await api.storage.local.get({
    enabled: true,
    allowlist: [],
  });

  // Global on/off: enable or disable the static rulesets.
  await api.declarativeNetRequest.updateEnabledRulesets(
    enabled ? { enableRulesetIds: RULESETS } : { disableRulesetIds: RULESETS }
  );

  // Per-site allowlist: one dynamic rule that lets everything through on pages whose
  // top-level site is in the list. Its priority is above every blocking rule.
  const addRules = allowlist.length
    ? [
        {
          id: ALLOW_RULE_ID,
          priority: 100,
          action: { type: "allowAllRequests" },
          condition: {
            requestDomains: allowlist,
            resourceTypes: ["main_frame", "sub_frame"],
          },
        },
      ]
    : [];
  await api.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: [ALLOW_RULE_ID],
    addRules,
  });

  await applyCosmetics(enabled, allowlist);
}

// Run settings updates one after another, never two at once.
let queue = Promise.resolve();
function scheduleApply() {
  queue = queue.then(applySettings).catch((e) => console.warn("applySettings failed", e));
}

async function setUpBadge() {
  // Shows the number of blocked requests on the toolbar icon (Chrome/Edge only).
  try {
    await api.declarativeNetRequest.setExtensionActionOptions({
      displayActionCountAsBadgeText: true,
    });
  } catch (e) {
    // Not supported in Firefox; blocking still works.
  }
}

api.runtime.onInstalled.addListener(() => {
  setUpBadge();
  scheduleApply();
});
api.runtime.onStartup.addListener(() => {
  setUpBadge();
  scheduleApply();
});
api.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && (changes.enabled || changes.allowlist)) {
    scheduleApply();
  }
});
