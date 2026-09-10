const nativeName = "aether";
let nativePort = null;

function connectNative() {
  try {
    nativePort = browser.runtime.connectNative(nativeName);
    nativePort.onMessage.addListener(async function (msg) {
      const result = await dispatchToTab(msg);
      try {
        nativePort.postMessage({ id: msg && msg.id, result: result });
      } catch (e) {}
    });
    nativePort.onDisconnect.addListener(function () {
      nativePort = null;
      setTimeout(connectNative, 750);
    });
  } catch (e) {
    nativePort = null;
    setTimeout(connectNative, 750);
  }
}

connectNative();

browser.runtime.onConnect.addListener(function (port) {
  if (port.name !== nativeName) return;
  port.onMessage.addListener(async function (msg) {
    const result = await dispatchToTab(msg);
    port.postMessage({ id: msg && msg.id, result: result });
  });
});

function urlsMatch(left, right) {
  if (!left || !right) return false;
  if (left === right) return true;
  try {
    const a = new URL(left);
    const b = new URL(right);
    return a.href === b.href;
  } catch (e) {
    return false;
  }
}

async function findTab(msg) {
  const wantedUrl = msg && typeof msg.url === "string" ? msg.url.trim() : "";
  const numericId = msg && typeof msg.geckoTabId === "number" ? msg.geckoTabId : null;
  if (numericId != null) {
    try {
      const tab = await browser.tabs.get(numericId);
      if (tab) return tab;
    } catch (e) {}
  }
  const all = await browser.tabs.query({});
  if (wantedUrl) {
    const exact = all.find(function (tab) { return urlsMatch(tab.url, wantedUrl); });
    if (exact) return exact;
  }
  const active = await browser.tabs.query({ active: true, currentWindow: true });
  return active && active[0] ? active[0] : null;
}

async function sendToTab(tab, msg) {
  try {
    return await browser.tabs.sendMessage(tab.id, msg);
  } catch (e) {
    const text = String(e && e.message ? e.message : e);
    if (text.indexOf("Receiving end does not exist") < 0 && text.indexOf("Could not establish connection") < 0) {
      return { ok: false, errmsg: text };
    }
    const delays = [300, 500, 800];
    for (let i = 0; i < delays.length; i++) {
      await new Promise(function (resolve) { setTimeout(resolve, delays[i]); });
      try {
        return await browser.tabs.sendMessage(tab.id, msg);
      } catch (retry) {
        if (i === delays.length - 1) {
          return { ok: false, errmsg: String(retry && retry.message ? retry.message : retry) };
        }
      }
    }
    return { ok: false, errmsg: text };
  }
}

async function dispatchToTab(msg) {
  try {
    const tab = await findTab(msg);
    if (!tab) return { ok: false, errmsg: "No tab." };
    return await sendToTab(tab, msg);
  } catch (e) {
    return { ok: false, errmsg: String(e && e.message ? e.message : e) };
  }
}

if (browser.webRequest && browser.webRequest.onCompleted) {
  try {
    browser.webRequest.onCompleted.addListener(
      function (details) {
        if (!nativePort) return;
        try {
          nativePort.postMessage({
            kind: "resource",
            url: details.url,
            method: details.method,
            type: details.type,
            status: details.statusCode,
            fromCache: details.fromCache,
          });
        } catch (e) {}
      },
      { urls: ["<all_urls>"] },
    );
  } catch (e) {}
}
