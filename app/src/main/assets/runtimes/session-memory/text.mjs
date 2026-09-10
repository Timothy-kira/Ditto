/**
 * Semantic boundaries and token estimates.
 *
 * Everything in this file exists to keep the folder from cutting text in the middle of a meaning.
 * A budget that slices at character N produces a half sentence, a truncated JSON object, a URL that
 * no longer resolves — and the model reads the fragment as if it were the whole thing. Cutting at a
 * paragraph, a sentence, or at worst a word costs a few characters of budget and keeps every unit
 * that survives readable on its own.
 */

const PARAGRAPH_BREAK = /\n[ \t]*\n/g;
const LINE_BREAK = /\n/g;
// CJK sentence enders carry no trailing space, so they need their own class.
const SENTENCE_END = /[。！？；!?;]|[.](?=\s|$)/g;
const LINE_SPLIT = /\n/;
const WORD_BREAK = /[\s,、，)\]}]/g;

/**
 * Rough token count, biased to overestimate rather than under.
 *
 * A budget that underestimates lets the real prompt exceed the model's window, which fails the turn;
 * one that overestimates only folds slightly earlier than it had to. CJK runs about one token per
 * character while Latin runs about four characters per token, and mixed CJK/Latin text is the norm
 * here, so counting the two separately is worth the single pass.
 */
export function estimateTokens(text) {
  const str = String(text ?? "");
  let cjk = 0;
  let other = 0;
  for (let i = 0; i < str.length; i += 1) {
    const code = str.charCodeAt(i);
    // CJK ideographs, kana, Hangul, and the full-width forms that travel with them.
    if (
      (code >= 0x3040 && code <= 0x30ff) ||
      (code >= 0x3400 && code <= 0x9fff) ||
      (code >= 0xac00 && code <= 0xd7af) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xff00 && code <= 0xffef)
    ) {
      cjk += 1;
    } else {
      other += 1;
    }
  }
  return cjk + Math.ceil(other / 4);
}

export function estimateMessageTokens(message) {
  if (!message) return 0;
  // Serialising is the honest measure: tool call arguments, ids and roles all reach the provider.
  return estimateTokens(typeof message === "string" ? message : JSON.stringify(message));
}

export function estimateMessagesTokens(messages) {
  let total = 0;
  for (const message of messages || []) total += estimateMessageTokens(message);
  return total;
}

function lastIndexIn(text, from, to, pattern) {
  pattern.lastIndex = 0;
  let found = -1;
  let match;
  while ((match = pattern.exec(text))) {
    const end = match.index + match[0].length;
    if (end > to) break;
    if (end >= from) found = end;
  }
  return found;
}

function firstIndexIn(text, from, to, pattern) {
  pattern.lastIndex = from;
  const match = pattern.exec(text);
  if (!match) return -1;
  const end = match.index + match[0].length;
  return end <= to ? end : -1;
}

/**
 * The best place to cut at or near `target`, preferring the strongest boundary available.
 *
 * Searches a window around the target rather than scanning the whole string: a paragraph break
 * eight characters away is worth taking, one three thousand characters away would blow the budget
 * it was supposed to respect. Falls back through paragraph, line, sentence, word, and finally the
 * exact position — the last of these is a real cut mid-word, and it only happens for text that has
 * no boundary at all within the window (a base64 blob, a minified bundle), where there is no
 * meaning to preserve anyway.
 */
export function cutPoint(text, target, slack = 0) {
  const str = String(text ?? "");
  if (target <= 0) return 0;
  if (target >= str.length) return str.length;
  // The search window scales with the budget rather than being a flat 400 characters. A fixed
  // window means a boundary far from the target still wins - and on text with no interior structure
  // (a snapshot of repeated markup, a base64 payload) the only boundary in range sits near one end,
  // so honouring it throws away most of the content the budget was meant to keep. Proportional
  // slack says: take a boundary if one is close, otherwise cut where asked.
  const window = slack > 0 ? slack : Math.max(24, Math.floor(target * 0.25));
  const from = Math.max(0, target - window);
  const to = Math.min(str.length, target + window);
  for (const pattern of [PARAGRAPH_BREAK, LINE_BREAK, SENTENCE_END, WORD_BREAK]) {
    const before = lastIndexIn(str, from, target, pattern);
    if (before > 0) return before;
    const after = firstIndexIn(str, target, to, pattern);
    if (after > 0) return after;
  }
  return target;
}

/**
 * Where the last `target` characters begin, snapped to a boundary.
 *
 * The window is measured from the tail's own size, so a short tail gets a short search and cannot be
 * shortened much further by a distant boundary.
 */
export function cutPointFromEnd(text, target, slack = 0) {
  const str = String(text ?? "");
  if (target <= 0) return str.length;
  if (target >= str.length) return 0;
  return cutPoint(str, str.length - target, slack > 0 ? slack : Math.max(24, Math.floor(target * 0.25)));
}

/**
 * The first sentence of a piece of text, as a label.
 *
 * Used for card intents and outcomes. `slice(0, 80)` was what this replaced, and the difference
 * shows up in the ledger the model reads: "帮我查一下 2026 年 9 月国内几家大模型厂商的" is not a
 * summary of anything, while the sentence it came from is.
 */
export function firstSentence(text, maxChars = 160) {
  const str = String(text ?? "").trim();
  if (!str) return "";
  const firstLine = str.split(LINE_SPLIT).find((line) => line.trim().length > 0) || "";
  const trimmed = firstLine.trim();
  SENTENCE_END.lastIndex = 0;
  const first = SENTENCE_END.exec(trimmed);
  if (first) {
    const stop = first.index + first[0].length;
    // A sentence that fits is the answer even when the line would also have fit: the point is the
    // unit of meaning, not the character count.
    if (stop <= maxChars) return trimmed.slice(0, stop).trim();
  }
  if (trimmed.length <= maxChars) return trimmed;
  return trimmed.slice(0, cutPoint(trimmed, maxChars, 40)).trim();
}

/** The last sentence, for the "what came of it" half of a card. */
export function lastSentence(text, maxChars = 160) {
  const str = String(text ?? "").trim();
  if (!str) return "";
  const lines = str.split(LINE_SPLIT).filter((line) => line.trim().length > 0);
  const lastLine = (lines[lines.length - 1] || "").trim();
  if (lastLine.length <= maxChars) return lastLine;
  const start = cutPointFromEnd(lastLine, maxChars, 40);
  return lastLine.slice(start).trim();
}

/**
 * Keep the head and the tail of a long text, eliding the middle at semantic boundaries.
 *
 * Head and tail rather than head alone, because tool output has meaning at both ends and different
 * meaning at each: the head says what was attempted and against what, the tail carries the exit
 * code, the error, the conclusion. A head-only truncation of a failed command is a transcript of a
 * command that appears to have worked.
 *
 * The elision marker names the blob, so the full text is one `read_node_detail` away rather than
 * gone.
 */
export function elideMiddle(text, budgetChars, blobSha, { headRatio = 0.6 } = {}) {
  const str = String(text ?? "");
  if (str.length <= budgetChars) return { text: str, elided: 0 };
  const headChars = Math.max(1, Math.floor(budgetChars * headRatio));
  const tailChars = Math.max(1, budgetChars - headChars);
  const headEnd = cutPoint(str, headChars);
  const tailStart = cutPointFromEnd(str, tailChars);
  // The marker is the only handle on the elided text, so every branch that elides has to carry
  // it - including this one, where head and tail overlap and there is no middle left to keep.
  const markerFor = (elided) => (blobSha
    ? `\n\n[… ${elided} chars elided · full text in blob:${blobSha} via read_node_detail scope=tool_outputs …]\n\n`
    : `\n\n[… ${elided} chars elided …]\n\n`);
  if (tailStart <= headEnd) {
    const dropped = str.length - headEnd;
    return { text: str.slice(0, headEnd) + markerFor(dropped).trimEnd(), elided: dropped };
  }
  const elided = tailStart - headEnd;
  return { text: str.slice(0, headEnd) + markerFor(elided) + str.slice(tailStart), elided };
}

/**
 * Host-injected scaffolding, removed before anything is indexed or hashed.
 *
 * Two shapes, and they fail differently. The line prefixes (`[环境]`, a date line) push the real
 * question down; the paired blocks (`<system-reminder>`, `<everme_profile>`, `<everme_recall>`) are
 * hundreds of words that repeat *verbatim in every turn*. The blocks are the worse of the two for
 * retrieval: identical text in every card is a large constant component of every BM25 document, so
 * it does not merely eat the card's character budget, it deflates the idf of the words that
 * actually distinguish one turn from another.
 *
 * The fingerprint needs the same cleaning for a different reason, and in both directions: the date
 * line is stamped per calendar day, so a session running past midnight would compute a new
 * fingerprint tomorrow, find an empty ledger, and silently lose every node it had frozen; while the
 * environment and reminder blocks are identical across sessions, so leaving them in collides
 * unrelated conversations onto one ledger. Same cause, opposite failures.
 *
 * Only the index and the fingerprint are cleaned. The blob keeps the turn verbatim, because
 * `read_node_detail` exists to show what was really there — an index wants the distinguishing
 * words, a record wants all of them, and those two goals point in opposite directions.
 */
const INJECTED_BLOCK_TAGS = [
  "system-reminder",
  "everme_profile",
  "everme_recall",
  "plugin_session_start",
  "plugin_session_end",
  "untrusted-web-content",
  "untrusted_gui_content",
];

/**
 * Host scaffolding that arrives as a *prefix of the user's own message*.
 *
 * `ensureDeskLeadPromptBlocks` and `ensureRestoredHistoryPromptBlocks` prepend their text as extra
 * blocks of the user prompt, and `extractText` joins blocks with a newline - so by the time a turn
 * reaches here, the desk lead, the restore lead and the skill preamble are indistinguishable from
 * something the user typed. They are matched by their own marker sentences, which are the same
 * sentences `kimiPromptHasDeskLead` tests on the Kotlin side: one list of judgements rather than two
 * that drift apart.
 *
 * Matched as a literal line prefix rather than compiled into a pattern. The sentences contain regex
 * metacharacters, and an escape helper is one more thing to get wrong for no gain - a line either
 * starts with the lead or it does not.
 */
const SCAFFOLD_SENTENCES = [
  "agent mode is on",
  "you are the desk lead",
  "you are researching a multi-app phone task",
  "a long-horizon plan was approved",
  "web, search, and fetch use aether",
  "web, search, fetch, and image work uses aether",
  "websearch and fetchurl are the browser group",
  "the previous live session was not available",
  "skill tool loaded",
  "the following skills are available",
];

const INJECTED_PATTERNS = [
  // `(\n+|$)` rather than `\n+`, and full-width brackets alongside half-width ones.
  //
  // stripInjected trims between passes, so a message that is *only* an environment line has no
  // trailing newline left by the time the pattern runs - the old `\n+` could therefore never match
  // it, which is precisely how `［环境］今天是 …` ended up as a card's intent and as the visible
  // label of a page in the settings screen.
  /^[[［]\s*环境\s*[\]］][^\n]*(\n+|$)/,
  /^[[［]\s*environment\s*[\]］][^\n]*(\n+|$)/i,
  /^今天是\s*\d{4}-\d{2}-\d{2}[^\n]*(\n+|$)/,
  /^Today is\s+\d{4}-\d{2}-\d{2}[^\n]*(\n+|$)/i,
  // What a partially stripped date line leaves behind on its own line.
  /^[（(]\s*星期[一二三四五六日天]\s*[)）]\s*[。.]?\s*(\n+|$)/,
  // String.raw so the backslashes reach RegExp intact.
  ...INJECTED_BLOCK_TAGS.map(
    (tag) => new RegExp(String.raw`<${tag}\b[^>]*>[\s\S]*?</${tag}>`, "gi"),
  ),
  // An opener with no closer: a truncated injection must not become the intent either.
  ...INJECTED_BLOCK_TAGS.map((tag) => new RegExp(String.raw`<${tag}\b[^>]*>[\s\S]*$`, "i")),
];

/**
 * Drop a leading scaffold line, if the text starts with one.
 *
 * Line-scoped, and only at the head. Consuming to the next blank line would be wrong for the shape
 * that actually occurs - prompt blocks joined by a single newline, so the user's real question sits
 * on the very next line - and a match further down is the user quoting the lead, where deleting the
 * remainder of their message is much worse than indexing one boilerplate line.
 */
function stripScaffoldLine(text) {
  const newline = text.indexOf("\n");
  const head = (newline < 0 ? text : text.slice(0, newline)).trim().toLowerCase();
  if (!head) return text;
  if (!SCAFFOLD_SENTENCES.some((sentence) => head.startsWith(sentence))) return text;
  return newline < 0 ? "" : text.slice(newline + 1);
}

export function stripInjected(text) {
  let out = String(text ?? "").trim();
  let changed = true;
  // Looped rather than single-pass: removing a block can expose a prefix that was not at the start
  // before, so the patterns are deliberately order-independent.
  while (changed) {
    changed = false;
    for (const pattern of INJECTED_PATTERNS) {
      // Trimmed after every removal, not only at the end: the prefix patterns are anchored with
      // `^`, and a removed block leaves the newline that followed it, so an un-trimmed pass would
      // silently stop matching the very prefix the block was hiding.
      const next = out.replace(pattern, "").trim();
      if (next !== out) {
        out = next;
        changed = true;
      }
    }
    const unled = stripScaffoldLine(out).trim();
    if (unled !== out) {
      out = unled;
      changed = true;
    }
  }
  return out.trim();
}
