// YouTube: video ads come from the same servers as the videos, so they cannot be
// blocked by domain. Instead this script hides ad boxes on the page and, when a video
// ad starts playing, mutes it and jumps to its end so the real video starts.
(() => {
  const api = globalThis.browser ?? globalThis.chrome;

  const HIDE = [
    "#masthead-ad",
    "#player-ads",
    "ytd-ad-slot-renderer",
    "ytd-rich-item-renderer:has(ytd-ad-slot-renderer)",
    "ytd-rich-section-renderer:has(ytd-ad-slot-renderer)",
    "ytd-in-feed-ad-layout-renderer",
    "ytd-display-ad-renderer",
    "ytd-promoted-video-renderer",
    "ytd-promoted-sparkles-web-renderer",
    "ytd-promoted-sparkles-text-search-renderer",
    "ytd-search-pyv-renderer",
    "ytd-banner-promo-renderer",
    "ytd-statement-banner-renderer",
    "ytd-companion-slot-renderer",
    "ytd-action-companion-ad-renderer",
    "ytd-player-legacy-desktop-watch-ads-renderer",
    "ytd-engagement-panel-section-list-renderer[target-id='engagement-panel-ads']",
    "ytm-promoted-video-renderer",
    "ytm-companion-ad-renderer",
    "ytm-promoted-sparkles-web-renderer",
    ".ytp-ad-overlay-container",
    ".ytp-ad-image-overlay",
    ".ytp-ad-text-overlay",
    ".ytp-featured-product",
    ".video-ads .ytp-ad-player-overlay-layout",
  ];

  const SKIP_BUTTONS = [
    ".ytp-skip-ad-button",
    ".ytp-ad-skip-button",
    ".ytp-ad-skip-button-modern",
    ".ytp-ad-skip-button-container button",
  ].join(",");

  let mutedByUs = false;

  function hideAdBoxes() {
    const style = document.createElement("style");
    style.textContent = HIDE.join(",\n") + " { display: none !important; }";
    (document.head || document.documentElement).appendChild(style);
  }

  function skipVideoAd() {
    const player = document.querySelector(".html5-video-player");
    if (!player) return;
    const video = player.querySelector("video");
    if (!video) return;

    const adPlaying =
      player.classList.contains("ad-showing") ||
      player.classList.contains("ad-interrupting");

    if (adPlaying) {
      if (!video.muted) {
        video.muted = true;
        mutedByUs = true;
      }
      // Jump to the end of the ad; YouTube then moves on to the next ad or the video.
      if (Number.isFinite(video.duration) && video.duration > 0) {
        if (video.currentTime < video.duration - 0.1) {
          video.currentTime = video.duration;
        }
      }
      document.querySelectorAll(SKIP_BUTTONS).forEach((b) => b.click());
    } else if (mutedByUs) {
      video.muted = false; // give the sound back once the real video is playing
      mutedByUs = false;
    }
  }

  function siteAllowed(list) {
    return list.some(
      (d) => location.hostname === d || location.hostname.endsWith("." + d)
    );
  }

  api.storage.local.get({ enabled: true, allowlist: [] }).then((s) => {
    if (!s.enabled || siteAllowed(s.allowlist)) return;
    hideAdBoxes();
    setInterval(skipVideoAd, 250);
  });
})();
