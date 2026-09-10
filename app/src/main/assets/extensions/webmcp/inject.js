(function () {
  try {
    var script = document.createElement("script");
    script.src = browser.runtime.getURL("polyfill.js");
    script.async = false;
    script.onload = function () {
      script.remove();
    };
    (document.documentElement || document.head || document).appendChild(script);
  } catch (e) {}
})();
