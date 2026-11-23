// Site-specific hiding rules: asks the background which EasyList hiding rules apply to
// this site and hides those elements. (The rules for every site are in rules/generic.css,
// which the background registers separately.)
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  const host = location.hostname;
  if (!host) return;

  function siteMatches(list) {
    return list.some((d) => host === d || host.endsWith("." + d));
  }

  api.storage.local
    .get({ enabled: true, allowlist: [] })
    .then((s) => {
      if (!s.enabled || siteMatches(s.allowlist)) return null;
      return api.runtime.sendMessage({ type: "cosmetic", host });
    })
    .then((selectors) => {
      if (!Array.isArray(selectors) || !selectors.length) return;
      // One rule per selector, so a selector the browser rejects cannot disable the rest.
      const style = document.createElement("style");
      style.textContent = selectors
        .map((sel) => sel + "{display:none!important}")
        .join("\n");
      (document.head || document.documentElement).appendChild(style);
    })
    .catch(() => {
      // Extension was reloaded while the page was open; nothing to do.
    });
})();
