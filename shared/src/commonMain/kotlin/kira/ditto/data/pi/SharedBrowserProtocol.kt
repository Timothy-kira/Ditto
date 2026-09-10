package kira.ditto.data.pi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val BrowserToolName = "browser"
const val LegacyChromeToolName = "chrome"

internal val SharedBrowserActions = listOf(
    "start",
    "status",
    "navigate",
    "click",
    "type",
    "get_text",
    "scroll",
    "get_page_info",
    "execute_js",
    "find_elements",
    "hover",
    "get_readable",
    "get_backbone",
    "scroll_and_collect",
    "set_user_agent",
    "set_viewport",
    "new_tab",
    "close_tab",
    "list_tabs",
    "back",
    "forward",
    "reload",
    "screenshot",
    "wait_for_dom_stable",
    "stop",
)

internal fun sharedBrowserToolDefinitions(enabled: Boolean): JsonArray =
    if (!enabled) {
        JsonArray(emptyList())
    } else {
        buildJsonArray {
            add(buildJsonObject {
                put("name", BrowserToolName)
                put(
                    "description",
                    "Control Aether's browser. Prefer CSS selectors and DOM-reading actions; use normalized 0..1000 coordinates only as a fallback.",
                )
                put("execution_mode", "sequential")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { SharedBrowserActions.forEach { add(JsonPrimitive(it)) } })
                        })
                        put("url", stringProperty("URL for navigate."))
                        put("selector", stringProperty("CSS selector for click, type, get_text, scroll, or find_elements."))
                        put("text", stringProperty("Text for type."))
                        put("x", numberProperty("Normalized horizontal coordinate from 0 to 1000."))
                        put("y", numberProperty("Normalized vertical coordinate from 0 to 1000."))
                        put("direction", stringProperty("Scroll direction: up or down."))
                        put("amount", numberProperty("Scroll distance in CSS pixels."))
                        put("script", stringProperty("JavaScript source for execute_js."))
                        put("max_depth", numberProperty("Maximum DOM depth for get_backbone."))
                        put("timeout", numberProperty("Maximum wait in milliseconds for wait_for_dom_stable."))
                        put("user_agent", stringProperty("User-Agent value for set_user_agent."))
                        put("width", numberProperty("Viewport width in CSS pixels for set_viewport."))
                        put("height", numberProperty("Viewport height in CSS pixels for set_viewport."))
                        put("steps", numberProperty("Maximum scroll steps for scroll_and_collect."))
                        put("tab_id", stringProperty("Target browser tab ID. Omit to use the active tab."))
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("action")) })
                    put("additionalProperties", false)
                })
            })
        }
    }

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun numberProperty(description: String) = buildJsonObject {
    put("type", "number")
    put("description", description)
}

fun browserClickScript(selector: String, x: Double?, y: Double?): String {
    val selectorLiteral = selector.takeIf(String::isNotBlank)?.let(::jsStringLiteral) ?: "null"
    val xLiteral = x?.toString() ?: "null"
    val yLiteral = y?.toString() ?: "null"
    return """
        (() => {
          const selector = $selectorLiteral;
          const normalizedX = $xLiteral;
          const normalizedY = $yLiteral;
          const element = selector
            ? document.querySelector(selector)
            : (normalizedX == null || normalizedY == null
                ? null
                : document.elementFromPoint(
                    Math.max(0, Math.min(window.innerWidth - 1, normalizedX * window.innerWidth / 1000)),
                    Math.max(0, Math.min(window.innerHeight - 1, normalizedY * window.innerHeight / 1000))
                  ));
          if (!element) return JSON.stringify({ok:false,error:'Element not found.'});
          element.scrollIntoView({block:'center', inline:'center'});
          element.focus?.();
          element.click();
          const rect = element.getBoundingClientRect();
          return JSON.stringify({
            ok:true,
            tag:element.tagName,
            text:(element.innerText || element.textContent || '').trim().slice(0,200),
            cursor_x:Math.round(rect.left + rect.width / 2),
            cursor_y:Math.round(rect.top + rect.height / 2),
            cursor_animation_duration_ms:220
          });
        })()
    """.trimIndent()
}

fun browserTypeScript(selector: String, text: String): String {
    val selectorLiteral = selector.takeIf(String::isNotBlank)?.let(::jsStringLiteral) ?: "null"
    val textLiteral = jsStringLiteral(text)
    return """
        (() => {
          const selector = $selectorLiteral;
          const element = selector ? document.querySelector(selector) : document.activeElement;
          if (!element) return JSON.stringify({ok:false,error:'Element not found.'});
          const text = $textLiteral;
          element.focus?.();
          if ('value' in element) {
            const prototype = element.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
            if (setter) setter.call(element, text); else element.value = text;
          } else if (element.isContentEditable) {
            element.textContent = text;
          } else {
            return JSON.stringify({ok:false,error:'Element is not editable.'});
          }
          element.dispatchEvent(new InputEvent('input', {bubbles:true, data:text, inputType:'insertText'}));
          element.dispatchEvent(new Event('change', {bubbles:true}));
          const rect = element.getBoundingClientRect();
          return JSON.stringify({
            ok:true,
            tag:element.tagName,
            length:text.length,
            cursor_x:Math.round(rect.left + rect.width / 2),
            cursor_y:Math.round(rect.top + rect.height / 2),
            cursor_animation_duration_ms:220
          });
        })()
    """.trimIndent()
}

fun browserGetTextScript(selector: String): String {
    val selectorLiteral = selector.takeIf(String::isNotBlank)?.let(::jsStringLiteral) ?: "null"
    return """
        (() => {
          const selector = $selectorLiteral;
          const element = selector ? document.querySelector(selector) : document.body;
          if (!element) return JSON.stringify({ok:false,error:'Element not found.'});
          const text = (element.innerText || element.textContent || '').trim();
          return JSON.stringify({ok:true,text:text.slice(0,20000),length:text.length,truncated:text.length > 20000,url:location.href,title:document.title});
        })()
    """.trimIndent()
}

fun browserScrollScript(selector: String, direction: String, amount: Int): String {
    val selectorLiteral = selector.takeIf(String::isNotBlank)?.let(::jsStringLiteral) ?: "null"
    val signedAmount = if (direction.equals("up", ignoreCase = true)) -amount else amount
    return """
        (() => {
          const selector = $selectorLiteral;
          const element = selector ? document.querySelector(selector) : document.scrollingElement;
          if (!element) return JSON.stringify({ok:false,error:'Scrollable element not found.'});
          if (element === document.scrollingElement) window.scrollBy({top:$signedAmount,behavior:'instant'});
          else element.scrollBy({top:$signedAmount,behavior:'instant'});
          return JSON.stringify({ok:true,scrollX:window.scrollX,scrollY:window.scrollY});
        })()
    """.trimIndent()
}

fun browserHoverScript(selector: String, x: Double?, y: Double?): String {
    val selectorLiteral = selector.takeIf(String::isNotBlank)?.let(::jsStringLiteral) ?: "null"
    val xLiteral = x?.toString() ?: "null"
    val yLiteral = y?.toString() ?: "null"
    return """
        (() => {
          const selector = $selectorLiteral;
          const normalizedX = $xLiteral;
          const normalizedY = $yLiteral;
          const element = selector
            ? document.querySelector(selector)
            : (normalizedX == null || normalizedY == null
                ? null
                : document.elementFromPoint(
                    Math.max(0, Math.min(window.innerWidth - 1, normalizedX * window.innerWidth / 1000)),
                    Math.max(0, Math.min(window.innerHeight - 1, normalizedY * window.innerHeight / 1000))
                  ));
          if (!element) return JSON.stringify({ok:false,error:'Element not found.'});
          element.scrollIntoView({block:'center',inline:'center'});
          const rect = element.getBoundingClientRect();
          const clientX = rect.left + rect.width / 2;
          const clientY = rect.top + rect.height / 2;
          ['pointerover','pointerenter','mouseover','mouseenter','mousemove'].forEach(type => {
            element.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,clientX,clientY,view:window}));
          });
          return JSON.stringify({
            ok:true,
            tag:element.tagName,
            text:(element.innerText || element.textContent || '').trim().slice(0,200),
            cursor_x:Math.round(clientX),
            cursor_y:Math.round(clientY),
            cursor_animation_duration_ms:260
          });
        })()
    """.trimIndent()
}

fun browserScrollAndCollectScript(maxSteps: Int, amount: Int): String = """
    (() => new Promise(async (resolve) => {
      const maxSteps = ${maxSteps.coerceIn(1, 50)};
      const amount = ${amount.coerceIn(100, 5_000)};
      const chunks = [];
      const seen = new Set();
      let steps = 0;
      for (; steps < maxSteps; steps += 1) {
        const root = document.querySelector('article,main,[role="main"]') || document.body;
        const text = (root?.innerText || '').replace(/\n{3,}/g,'\n\n').trim();
        if (text && !seen.has(text)) { seen.add(text); chunks.push(text); }
        const scrolling = document.scrollingElement || document.documentElement;
        const before = scrolling.scrollTop;
        window.scrollBy({top:amount,behavior:'instant'});
        await new Promise(done => setTimeout(done, 180));
        if (scrolling.scrollTop === before || scrolling.scrollTop + window.innerHeight >= scrolling.scrollHeight) break;
      }
      const text = chunks.join('\n\n').slice(0,50000);
      resolve(JSON.stringify({
        ok:true,
        url:location.href,
        title:document.title,
        text,
        length:text.length,
        truncated:chunks.join('\n\n').length > 50000,
        steps:steps + 1,
        scroll_y:window.scrollY
      }));
    }))()
""".trimIndent()

fun browserSetViewportScript(width: Int, height: Int): String = """
    (() => {
      let viewport = document.querySelector('meta[name="viewport"]');
      if (!viewport) {
        viewport = document.createElement('meta');
        viewport.name = 'viewport';
        document.head?.appendChild(viewport);
      }
      viewport.content = 'width=${width.coerceIn(240, 4_096)}, initial-scale=1.0';
      window.dispatchEvent(new Event('resize'));
      return JSON.stringify({ok:true,requested_width:${width.coerceIn(240, 4_096)},requested_height:${height.coerceIn(240, 4_096)}});
    })()
""".trimIndent()

val BrowserPageInfoScript = """
    (() => JSON.stringify({
      ok:true,
      url:location.href,
      title:document.title,
      ready_state:document.readyState,
      viewport:{width:window.innerWidth,height:window.innerHeight},
      document:{width:document.documentElement.scrollWidth,height:document.documentElement.scrollHeight}
    }))()
""".trimIndent()

fun browserFindElementsScript(selector: String): String = """
    (() => {
      const selector = ${jsStringLiteral(selector)};
      const elements = Array.from(document.querySelectorAll(selector)).slice(0,100).map((element, index) => {
        const rect = element.getBoundingClientRect();
        return {
          index,
          tag:element.tagName,
          id:element.id || null,
          classes:typeof element.className === 'string' ? element.className.split(/\s+/).filter(Boolean).slice(0,5) : [],
          text:(element.innerText || element.textContent || '').trim().slice(0,300),
          role:element.getAttribute('role'),
          aria_label:element.getAttribute('aria-label'),
          visible:rect.width > 0 && rect.height > 0,
          bounds:{x:rect.x,y:rect.y,width:rect.width,height:rect.height}
        };
      });
      return JSON.stringify({ok:true,count:elements.length,elements});
    })()
""".trimIndent()

val BrowserReadableScript = """
    (() => {
      const root = document.querySelector('article,main,[role="main"]') || document.body;
      const text = (root?.innerText || '').replace(/\n{3,}/g,'\n\n').trim();
      return JSON.stringify({ok:true,title:document.title,url:location.href,text:text.slice(0,30000),length:text.length,truncated:text.length > 30000});
    })()
""".trimIndent()

fun browserBackboneScript(maxDepth: Int): String = """
    (() => {
      const maxDepth = ${maxDepth.coerceIn(1, 12)};
      const interactive = new Set(['A','BUTTON','INPUT','TEXTAREA','SELECT','SUMMARY']);
      function visit(element, depth) {
        if (!element || depth > maxDepth) return null;
        const rect = element.getBoundingClientRect();
        const role = element.getAttribute?.('role');
        const keep = depth === 0 || interactive.has(element.tagName) || role || rect.width > 0 || rect.height > 0;
        if (!keep) return null;
        const node = {
          tag:element.tagName?.toLowerCase() || 'document',
          id:element.id || null,
          role:role || null,
          name:element.getAttribute?.('aria-label') || element.getAttribute?.('name') || null,
          text:(element.childElementCount === 0 ? (element.innerText || element.textContent || '').trim().slice(0,160) : '') || null
        };
        if (depth < maxDepth) {
          const children = Array.from(element.children || []).slice(0,80).map(child => visit(child, depth + 1)).filter(Boolean);
          if (children.length) node.children = children;
        }
        return node;
      }
      return JSON.stringify({ok:true,url:location.href,title:document.title,tree:visit(document.body,0)});
    })()
""".trimIndent()

val BrowserDomStableScript: String = """
    (() => JSON.stringify({ok:true,ready_state:document.readyState,element_count:document.querySelectorAll('*').length,url:location.href}))()
""".trimIndent()

fun browserWaitForDomStableScript(timeoutMillis: Int): String = """
    (() => new Promise((resolve) => {
      const timeout = ${timeoutMillis.coerceIn(1_000, 60_000)};
      const quietWindow = 500;
      const startedAt = Date.now();
      let lastMutationAt = startedAt;
      let mutationCount = 0;
      const observer = new MutationObserver((mutations) => {
        mutationCount += mutations.length;
        lastMutationAt = Date.now();
      });
      observer.observe(document.documentElement || document, {
        childList:true,
        subtree:true,
        attributes:true,
        characterData:true
      });
      const finish = (stable) => {
        observer.disconnect();
        resolve(JSON.stringify({
          ok:true,
          stable,
          timed_out:!stable,
          elapsed_ms:Date.now() - startedAt,
          mutation_count:mutationCount,
          ready_state:document.readyState,
          element_count:document.querySelectorAll('*').length,
          url:location.href
        }));
      };
      const poll = () => {
        const now = Date.now();
        if (document.readyState !== 'loading' && now - lastMutationAt >= quietWindow) finish(true);
        else if (now - startedAt >= timeout) finish(false);
        else setTimeout(poll, 100);
      };
      setTimeout(poll, 100);
    }))()
""".trimIndent()

private fun jsStringLiteral(value: String): String = JsonPrimitive(value).toString()
