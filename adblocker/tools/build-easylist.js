// Downloads EasyList (ads), the Adblock Warning Removal List (anti-adblock) and
// EasyPrivacy (trackers) and converts them into files the extension can load:
//   rules/easylist.json  - blocking rules (declarativeNetRequest format)
//   rules/generic.css    - hiding rules that apply on every site
//   rules/specific.json  - hiding rules for individual sites
//
// Run:  node tools/build-easylist.js   (from the adblocker folder)
// Then reload the extension on the browser's extensions page.
"use strict";

const fs = require("fs");
const path = require("path");
const https = require("https");

const LISTS = [
  {
    name: "EasyList",
    url: "https://easylist.to/easylist/easylist.txt",
    minLines: 10000,
  },
  {
    // Removes "please turn off your ad blocker" messages on sites that show them.
    name: "Adblock Warning Removal List",
    url: "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt",
    minLines: 100,
  },
  {
    // Tracker blocking: analytics, tracking pixels and data collectors.
    // Kept last so that, if the rule limit is ever reached, ad rules are kept first.
    name: "EasyPrivacy",
    url: "https://easylist.to/easylist/easyprivacy.txt",
    minLines: 10000,
  },
];
const OUT_DIR = path.join(__dirname, "..", "rules");
const MAX_RULES = 29000; // browsers guarantee 30,000 static rules per extension
const DOMAIN_CHUNK = 5000;

const DOMAIN_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
const TYPE_MAP = {
  script: "script",
  image: "image",
  stylesheet: "stylesheet",
  object: "object",
  "object-subrequest": "object",
  xmlhttprequest: "xmlhttprequest",
  subdocument: "sub_frame",
  ping: "ping",
  websocket: "websocket",
  media: "media",
  font: "font",
  other: "other",
  document: "main_frame",
};

function download(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    https
      .get(url, { headers: { "User-Agent": "adblock-list-builder" } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          if (redirects > 5) return reject(new Error("Too many redirects"));
          return resolve(download(res.headers.location, redirects + 1));
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error("HTTP " + res.statusCode));
        }
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
      })
      .on("error", reject);
  });
}

// ---------- hiding (cosmetic) rules ----------

const UNSUPPORTED_SELECTOR =
  /:-abp-|:has-text\(|:contains\(|:matches-css|:xpath\(|:upward\(|:remove\(|:style\(|:matches-path\(|:min-text-length\(|:watch-attr\(|:others\(/;

function selectorOk(sel) {
  if (!sel || sel.includes("{") || sel.includes("}")) return false;
  if (UNSUPPORTED_SELECTOR.test(sel)) return false;
  let round = 0;
  let square = 0;
  let quote = null;
  for (let i = 0; i < sel.length; i++) {
    const c = sel[i];
    if (quote) {
      if (c === "\\") i++;
      else if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") quote = c;
    else if (c === "(") round++;
    else if (c === ")") round--;
    else if (c === "[") square++;
    else if (c === "]") square--;
    if (round < 0 || square < 0) return false;
  }
  return round === 0 && square === 0 && quote === null;
}

// ---------- blocking (network) rules ----------

/** Returns a rule description, a {domain, thirdParty} shortcut, or null when unsupported. */
function parseNetwork(line) {
  let allow = false;
  let pattern = line;
  if (pattern.startsWith("@@")) {
    allow = true;
    pattern = pattern.slice(2);
  }

  let options = [];
  const dollar = pattern.lastIndexOf("$");
  if (dollar >= 0 && /^[a-zA-Z0-9_~,=|.\-]+$/.test(pattern.slice(dollar + 1))) {
    options = pattern.slice(dollar + 1).split(",");
    pattern = pattern.slice(0, dollar);
  }

  // /.../ is a regular expression in filter syntax: not converted.
  if (pattern.length > 1 && pattern.startsWith("/") && pattern.endsWith("/")) return null;
  if (/[^\x21-\x7e]/.test(pattern)) return null; // spaces or non-ASCII
  if (pattern.startsWith("||*")) return null;

  const condition = {};
  const types = [];
  const excludedTypes = [];
  const initiators = [];
  const excludedInitiators = [];
  let priority = allow ? 2 : 1;
  let onlyPartyOption = true;

  for (const raw of options) {
    const negated = raw.startsWith("~");
    const name = negated ? raw.slice(1) : raw;
    const lower = name.toLowerCase();

    if (lower === "third-party") {
      condition.domainType = negated ? "firstParty" : "thirdParty";
      if (negated) onlyPartyOption = false;
    } else if (lower === "match-case") {
      condition.isUrlFilterCaseSensitive = true;
      onlyPartyOption = false;
    } else if (lower === "important") {
      priority = 3;
      onlyPartyOption = false;
    } else if (lower.startsWith("domain=")) {
      onlyPartyOption = false;
      for (const entry of name.slice(7).split("|")) {
        const not = entry.startsWith("~");
        const domain = (not ? entry.slice(1) : entry).toLowerCase();
        if (!DOMAIN_RE.test(domain)) return null;
        (not ? excludedInitiators : initiators).push(domain);
      }
    } else if (TYPE_MAP[lower]) {
      onlyPartyOption = false;
      (negated ? excludedTypes : types).push(TYPE_MAP[lower]);
    } else {
      return null; // popup, csp, rewrite, generichide, elemhide and so on
    }
  }

  // "||example.com^" with nothing else: collect the domain instead of making a rule.
  if (!allow && onlyPartyOption) {
    const m = /^\|\|([a-z0-9.-]+)\^$/i.exec(pattern);
    if (m && DOMAIN_RE.test(m[1].toLowerCase())) {
      return { domain: m[1].toLowerCase(), thirdParty: condition.domainType === "thirdParty" };
    }
  }

  if (pattern === "" || pattern === "*") {
    if (!initiators.length) return null; // would match everything
  } else {
    if (pattern.replace(/[*^|]/g, "").length < 3) return null; // too broad to be safe
    condition.urlFilter = pattern;
  }

  if (initiators.length) condition.initiatorDomains = [...new Set(initiators)];
  if (excludedInitiators.length) {
    condition.excludedInitiatorDomains = [...new Set(excludedInitiators)];
  }

  let action = allow ? "allow" : "block";
  if (types.length) {
    const unique = [...new Set(types)];
    condition.resourceTypes = unique;
    // "@@...$document" means: leave this whole page alone.
    if (allow && unique.every((t) => t === "main_frame" || t === "sub_frame")) {
      action = "allowAllRequests";
    }
  } else if (excludedTypes.length) {
    // Without this, listing only exclusions would also match page navigations.
    condition.excludedResourceTypes = [...new Set([...excludedTypes, "main_frame"])];
  }

  return { rule: { priority, action: { type: action }, condition }, allow };
}

// ---------- main ----------

async function main() {
  const lines = [];
  for (const list of LISTS) {
    console.log("Downloading " + list.name + "...");
    const part = (await download(list.url)).split(/\r?\n/);
    if (part.length < list.minLines || !/^\[Adblock/i.test(part[0])) {
      throw new Error(list.name + " download looks wrong. Nothing was changed.");
    }
    console.log("  " + part.length + " lines");
    lines.push(...part);
  }

  const generic = new Set();
  const exceptions = new Set();
  const specific = {};
  const plainDomains = new Set();
  const thirdPartyDomains = new Set();
  const allowRules = [];
  const blockRules = [];
  const stats = { network: 0, networkSkipped: 0, cosmetic: 0, cosmeticSkipped: 0 };

  for (let line of lines) {
    line = line.trim();
    if (!line || line.startsWith("!") || line.startsWith("[")) continue;

    const cosmetic = /^([a-z0-9.,~*-]*)(##|#@#|#\?#|#\$#)(.+)$/i.exec(line);
    if (cosmetic) {
      stats.cosmetic++;
      const [, domainPart, separator, selector] = cosmetic;
      if (separator === "#@#") {
        exceptions.add(selector);
        continue;
      }
      if (separator !== "##" || !selectorOk(selector)) {
        stats.cosmeticSkipped++;
        continue;
      }
      if (!domainPart) {
        generic.add(selector);
        continue;
      }
      const domains = domainPart.toLowerCase().split(",");
      if (domains.some((d) => !DOMAIN_RE.test(d))) {
        stats.cosmeticSkipped++; // "~domain" exclusions and wildcards are not supported
        continue;
      }
      for (const d of domains) {
        if (!Object.prototype.hasOwnProperty.call(specific, d)) specific[d] = [];
        specific[d].push(selector);
      }
      continue;
    }

    stats.network++;
    const parsed = parseNetwork(line);
    if (!parsed) {
      stats.networkSkipped++;
    } else if (parsed.domain) {
      (parsed.thirdParty ? thirdPartyDomains : plainDomains).add(parsed.domain);
    } else {
      (parsed.allow ? allowRules : blockRules).push(parsed.rule);
    }
  }

  // A hiding rule that needs an exception somewhere is a known false-positive risk:
  // leave it out everywhere rather than break those sites.
  let removedByException = 0;
  for (const selector of exceptions) {
    if (generic.delete(selector)) removedByException++;
  }

  const rules = [];
  const addDomainRules = (set, thirdParty) => {
    const all = [...set].sort();
    for (let i = 0; i < all.length; i += DOMAIN_CHUNK) {
      const condition = { requestDomains: all.slice(i, i + DOMAIN_CHUNK) };
      if (thirdParty) condition.domainType = "thirdParty";
      rules.push({ priority: 1, action: { type: "block" }, condition });
    }
  };
  addDomainRules(plainDomains, false);
  addDomainRules(thirdPartyDomains, true);
  rules.push(...allowRules); // exceptions first, so a size cut never drops them
  rules.push(...blockRules);

  const dropped = Math.max(0, rules.length - MAX_RULES);
  const finalRules = rules.slice(0, MAX_RULES).map((r, i) => ({ id: i + 1, ...r }));

  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, "easylist.json"), JSON.stringify(finalRules));
  fs.writeFileSync(
    path.join(OUT_DIR, "generic.css"),
    [...generic].map((s) => s + "{display:none!important}").join("\n") + "\n"
  );
  fs.writeFileSync(path.join(OUT_DIR, "specific.json"), JSON.stringify(specific));

  console.log("Blocking rules in the lists:  " + stats.network);
  console.log("  not convertible (skipped):  " + stats.networkSkipped);
  console.log("  plain ad domains:           " + (plainDomains.size + thirdPartyDomains.size));
  console.log("  exception rules:            " + allowRules.length);
  console.log("  pattern rules:              " + blockRules.length);
  console.log("  rules written:              " + finalRules.length + " (dropped for size: " + dropped + ")");
  console.log("Hiding rules in the lists:    " + stats.cosmetic);
  console.log("  not convertible (skipped):  " + stats.cosmeticSkipped);
  console.log("  left out as risky:          " + removedByException);
  console.log("  for every site:             " + generic.size);
  console.log("  site-specific sites:        " + Object.keys(specific).length);
}

main().catch((e) => {
  console.error("Failed: " + e.message);
  process.exit(1);
});
