(function () {
  function pick() {
    const links = document.querySelectorAll(
      'link[rel~="icon" i], link[rel="apple-touch-icon" i], link[rel="apple-touch-icon-precomposed" i]');
    let best = null;
    let bestScore = -1;
    for (const l of links) {
      const href = l.href;
      if (!href || /\.svg(\?|#|$)/i.test(href) || (l.type || "").includes("svg")) continue;
      const m = (l.getAttribute("sizes") || "").match(/(\d+)x\d+/i);
      let size = m ? +m[1] : /apple-touch/i.test(l.rel) ? 180 : 16;
      const score = size >= 64 && size <= 256 ? 1000 + size : size;
      if (score > bestScore) {
        best = href;
        bestScore = score;
      }
    }
    return best || location.origin + "/favicon.ico";
  }
  function theme() {
    for (const meta of document.querySelectorAll('meta[name="theme-color" i]')) {
      const media = meta.getAttribute("media");
      if (media && !matchMedia(media).matches) continue;
      const probe = document.createElement("i");
      probe.style.color = meta.content;
      if (!probe.style.color) continue;
      document.documentElement.appendChild(probe);
      const c = getComputedStyle(probe).color;
      probe.remove();
      const m = c.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/);
      if (m && (m[4] === undefined || +m[4] > 0.5)) return [+m[1], +m[2], +m[3]];
    }
    return null;
  }
  browser.runtime.sendNativeMessage("antite", {page: location.href, icon: pick(), theme: theme()});
})();
