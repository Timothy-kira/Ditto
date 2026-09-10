import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const dir = mkdtempSync(join(tmpdir(), "aether-mem-"));
process.env.KIMI_CODE_HOME = dir;

const {
  NOTE_ORIGIN,
  collectSpokenMessages,
  conversationFingerprint,
  foldForModel,
  foldStats,
  pendingCount,
  readOriginalByHash,
  readSessionNote,
  requestNewContext,
  setEverMeUploader,
  spokenTurnHash,
  splitTurns,
  uploadedCount,
  writeSessionNote,
} = await import("./index.mjs");
const { cutPoint, elideMiddle, estimateTokens, firstSentence } = await import("./text.mjs");
const { memoryRoot, readPendingIngest } = await import("./store.mjs");

let failures = 0;
function check(name, condition, detail = "") {
  if (condition) return;
  failures += 1;
  console.error(`FAIL ${name}${detail ? ` — ${detail}` : ""}`);
}

function user(text, extra = {}) {
  return { role: "user", content: [{ type: "text", text }], ...extra };
}
function assistant(text) {
  return { role: "assistant", content: [{ type: "text", text }] };
}
function thinkingAssistant(visible, thinking) {
  return {
    role: "assistant",
    content: [
      { type: "thinking", thinking },
      { type: "text", text: visible },
    ],
  };
}

function turnsOf(n, prefix = "") {
  const history = [];
  for (let i = 1; i <= n; i += 1) {
    history.push(
      user(`${prefix}第 ${i} 轮：帮我查一下 nvidia 报名页怎么填。`),
      assistant(`第 ${i} 轮的结论是报名页在 developer.nvidia.com 上，需要登录。`),
    );
  }
  return history;
}

function storeHas(name) {
  return existsSync(join(memoryRoot(), name)) && readdirSync(join(memoryRoot(), name)).length > 0;
}

{
  const text = "第一段的内容。\n\n第二段的内容也在这里。\n\n第三段收尾。";
  const at = cutPoint(text, 10);
  check("cutPoint lands on a boundary", /\n$|。$/.test(text.slice(0, at)), JSON.stringify(text.slice(0, at)));

  const long = `HEAD-START ${"a".repeat(4000)}\n\nMIDDLE\n\n${"b".repeat(4000)} TAIL-END`;
  const { text: folded } = elideMiddle(long, 600, "deadbeef");
  check("elideMiddle keeps the head", folded.startsWith("HEAD-START"));
  check("elideMiddle keeps the tail", folded.endsWith("TAIL-END"));

  check("estimateTokens counts CJK per character", estimateTokens("中文中文") === 4);
  check(
    "firstSentence stops at the sentence",
    firstSentence("帮我查一下报名页怎么填。这是第二句话。", 120) === "帮我查一下报名页怎么填。",
  );
}

{
  const a = conversationFingerprint([user("hello there, this is session one")]);
  const b = conversationFingerprint([user("hello there, this is session two")]);
  check("fingerprints differ across opening questions", a !== b, `${a} vs ${b}`);
  const again = conversationFingerprint([user("hello there, this is session one")]);
  check("fingerprint is stable", a === again, `${a} vs ${again}`);
}

{
  const history = turnsOf(2);
  foldForModel(history);
  const fp = conversationFingerprint(history);
  const first = writeSessionNote({
    fingerprint: fp,
    history: "Applied 2026-03.",
    current_task: "Chasing the decision.",
  });
  check("first note write succeeds", first.ok);
  const second = writeSessionNote({
    fingerprint: fp,
    history: "Applied 2026-03, decided 2026-11.",
    current_task: "Drafting the appeal.",
  });
  check("second note write succeeds", second.ok);
  const note = readSessionNote(fp);
  check("REPLACE drops the previous current_task", note.current_task === "Drafting the appeal.");
  check("REPLACE keeps the rewritten history", note.history.includes("decided 2026-11"));
  check("REPLACE does not append the old current_task", !note.current_task.includes("Chasing"));
}

{
  const history = turnsOf(6);
  const fp = conversationFingerprint(history);
  writeSessionNote({ fingerprint: fp, history: "Settled.", current_task: "Still going." });
  const folded = foldForModel(history);
  const spokenTurns = splitTurns(folded);
  check("fold keeps every spoken turn", spokenTurns.length === 6, String(spokenTurns.length));
  const noteIndex = folded.findIndex((message) => message?.origin?.variant === NOTE_ORIGIN);
  const lastUser = (() => {
    for (let i = folded.length - 1; i >= 0; i -= 1) {
      if (folded[i]?.role === "user" && folded[i]?.origin?.variant !== NOTE_ORIGIN) return i;
    }
    return -1;
  })();
  check("note is injected", noteIndex >= 0);
  check("note sits before the last real user", noteIndex >= 0 && noteIndex < lastUser, `${noteIndex} vs ${lastUser}`);
  check("fold does not write cards", !storeHas("cards"));
  check("fold does not write blobs", !storeHas("blobs"));
  check("fold does not write ledgers", !storeHas("ledgers"));
}

{
  const history = turnsOf(5);
  const fp = conversationFingerprint(history);
  writeSessionNote({ fingerprint: fp, history: "Keep this.", current_task: "Cut now." });
  requestNewContext();
  const folded = foldForModel(history);
  const spokenTurns = splitTurns(folded);
  check("new_context keeps the last turn", spokenTurns.length === 1, String(spokenTurns.length));
  check("new_context does not archive cards", !storeHas("cards"));
  const again = foldForModel(history);
  check("the cut sticks on the next fold", splitTurns(again).length === 1, String(splitTurns(again).length));
}

{
  setEverMeUploader(() => true);
  const history = [
    user("remember the gate code is 9921", { id: "u1" }),
    thinkingAssistant("I will keep that.", "secret scratchpad"),
  ];
  foldForModel(history);
  const spoken = collectSpokenMessages(history);
  check("user speech is collected", spoken.some((item) => item.content.includes("9921")));
  check("thinking is not collected", spoken.every((item) => !item.content.includes("secret scratchpad")));
  const userHash = spokenTurnHash("user", "remember the gate code is 9921");
  const original = readOriginalByHash(userHash);
  check("read_original resolves by hash", original.includes("9921"), original);
  check("assistant visible text is hashed separately", spoken.some((item) => item.role === "assistant"));
}

{
  let calls = 0;
  setEverMeUploader(() => {
    calls += 1;
    return false;
  });
  const history = turnsOf(3, "retry-");
  const before = history.length;
  const folded = foldForModel(history);
  check("EverMe failure does not drop history", splitTurns(folded).length === 3, String(splitTurns(folded).length));
  check("failed ingest stays queued", pendingCount() > 0, String(pendingCount()));
  check("fold input length is unchanged besides the note", folded.length >= before);
  const queued = readPendingIngest().length;
  setEverMeUploader(() => true);
  foldForModel(history);
  check("queued ingest retries and drains", pendingCount() === 0, `pending=${pendingCount()} queued=${queued} calls=${calls}`);
  check("successful hashes are recorded", uploadedCount() > 0);

  const beforeCalls = calls;
  setEverMeUploader(() => {
    calls += 1;
    return true;
  });
  foldForModel(history);
  check("already-uploaded hashes are not sent again", calls === beforeCalls, `calls ${calls} vs ${beforeCalls}`);
}

{
  const here = dirname(fileURLToPath(import.meta.url));
  const mcpSource = readFileSync(join(here, "mcp.mjs"), "utf8");
  check("notes is offered", mcpSource.includes('name: "notes"'));
  check("new_context is offered", mcpSource.includes('name: "new_context"'));
  check("read_original is offered", mcpSource.includes('name: "read_original"'));
  check("search_memory_nodes is gone", !mcpSource.includes("search_memory_nodes"));
  check("read_node_detail is gone", !mcpSource.includes("read_node_detail"));
  check("update_session_note is not the public name", !mcpSource.match(/name: "update_session_note"/));
}

const stats = foldStats(turnsOf(2));
check("foldStats reports a fingerprint", Boolean(stats.fingerprint));

rmSync(dir, { recursive: true, force: true });

if (failures) {
  console.error(`session-memory selftest failed: ${failures}`);
  process.exit(1);
}
console.log("session-memory selftest ok");
