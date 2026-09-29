// SPDX-License-Identifier: MIT
// The appearance, applied before the page is drawn (no flash of the wrong theme): Day, Night, or Auto, which
// follows the device's light or dark setting, live. The choice is kept in this browser only. The themes share
// the same markup and geometry; only the CSS variables change. The rest of the app listens to the
// "marvin-theme" event on the document.
"use strict";
(function () {
  var KEY = "marvin.appearance";
  var dark = window.matchMedia("(prefers-color-scheme: dark)");
  var choice = "auto";
  try {
    var v = JSON.parse(window.localStorage.getItem(KEY) || "null");
    if (v === "day" || v === "night" || v === "auto") choice = v;
  } catch (e) { /* no storage: Auto */ }

  function apply() {
    var theme = choice === "auto" ? (dark.matches ? "night" : "day") : choice;
    var root = document.documentElement;
    var changed = root.getAttribute("data-theme") !== theme || root.getAttribute("data-choice") !== choice;
    root.setAttribute("data-theme", theme);
    root.setAttribute("data-choice", choice);
    if (changed) document.dispatchEvent(new CustomEvent("marvin-theme", { detail: { theme: theme, choice: choice } }));
  }

  window.marvinTheme = {
    get choice() { return choice; },
    get theme() { return document.documentElement.getAttribute("data-theme"); },
    set: function (c) {
      if (c !== "day" && c !== "night" && c !== "auto") return;
      choice = c;
      try { window.localStorage.setItem(KEY, JSON.stringify(c)); } catch (e) { /* kept for this page only */ }
      apply();
    },
  };
  if (dark.addEventListener) dark.addEventListener("change", apply);
  else if (dark.addListener) dark.addListener(apply);
  apply();
})();
