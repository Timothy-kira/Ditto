(function () {
  if (window.__aetherWebMcpInstalled) return;
  window.__aetherWebMcpInstalled = true;
  var tools = new Map();
  var dialogs = [];
  var api = {
    registerTool: function (def) {
      if (!def || !def.name) return;
      tools.set(String(def.name), def);
    },
    getTools: function () {
      var out = [];
      tools.forEach(function (t) {
        out.push({
          name: t.name,
          description: t.description || "",
          inputSchema: t.inputSchema || t.parameters || {},
        });
      });
      return out;
    },
    executeTool: function (name, args) {
      var t = tools.get(String(name));
      if (!t) return Promise.reject(new Error("Unknown tool: " + name));
      var exec = t.execute || t.handler;
      return Promise.resolve(exec.call(t, args || {}));
    },
    dialogs: dialogs,
    dialogSnapshot: function () {
      return JSON.stringify(
        dialogs.map(function (d) {
          return { type: d.type, message: d.message || "", default: d.default || "" };
        }),
      );
    },
    clearDialogs: function () {
      dialogs.length = 0;
    },
  };
  function remember(type, message, fallback) {
    dialogs.push({ type: type, message: String(message || ""), default: fallback || "" });
    if (dialogs.length > 12) dialogs.splice(0, dialogs.length - 12);
  }
  try {
    var origAlert = window.alert;
    window.alert = function (message) {
      remember("alert", message, "");
    };
    var origConfirm = window.confirm;
    window.confirm = function (message) {
      remember("confirm", message, "true");
      return true;
    };
    var origPrompt = window.prompt;
    window.prompt = function (message, def) {
      var fallback = def == null ? "" : String(def);
      remember("prompt", message, fallback);
      return fallback;
    };
    api._native = { alert: origAlert, confirm: origConfirm, prompt: origPrompt };
  } catch (e) {}
  try {
    Object.defineProperty(document, "modelContext", { value: api, configurable: true });
  } catch (e) {
    document.modelContext = api;
  }
  try {
    Object.defineProperty(navigator, "modelContext", { value: api, configurable: true });
  } catch (e) {
    navigator.modelContext = api;
  }
  window.__aetherWebMcp = api;
})();
