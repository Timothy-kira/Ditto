import { existsSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { extractText, indexableText, INDEXABLE_ROLES } from "./cards.mjs";
import { drainEverMeQueueAsync, enqueueSpoken } from "./everme.mjs";
import {
  ensureStore,
  memoryRoot,
  markActiveFingerprint,
  readActiveFingerprint,
  readNoteMarkdown,
  readOriginal,
  readSessionId,
  readWindowStart,
  sha256Hex,
  shortId,
  writeNoteMarkdown,
  writeOriginal,
  writeSessionId,
  writeWindowStart,
} from "./store.mjs";
import { stripInjected } from "./text.mjs";

export const NOTE_ORIGIN = "aether-memory-note";

const MAX_HISTORY_CHARS = 6_000;
const MAX_CURRENT_TASK_CHARS = 3_000;

function isInjectedVariant(variant) {
  return variant === NOTE_ORIGIN;
}

function isNoteMessage(message) {
  return isInjectedVariant(message?.origin?.variant) || isInjectedVariant(message?.origin?.kind);
}

function cloneMessage(message) {
  return JSON.parse(JSON.stringify(message));
}

function capSection(text, max) {
  const body = String(text || "");
  if (body.length <= max) return body;
  return body.slice(body.length - max);
}

function jsonStringifySpoken(role, content) {
  return JSON.stringify({
    role: String(role),
    content: String(content ?? "").replace(/\r\n/g, "\n").trim(),
  });
}

export function spokenTurnHash(role, content) {
  return sha256Hex(jsonStringifySpoken(role, content));
}

/**
 * Identity of this conversation, stable across days and distinct between sessions.
 *
 * Hashes the whole opening message rather than a prefix: the shared environment line would
 * otherwise collide unrelated conversations onto one note file.
 */
export function conversationFingerprint(history) {
  const first = (history || []).find((message) => message?.role === "user" && !isNoteMessage(message));
  const text = stripInjected(extractText(first));
  const stamp = first?.id || first?.timestamp || first?.createdAt || "";
  return `h-${shortId(`${stamp} ${text}`, 20)}`;
}

export function splitTurns(history) {
  const turns = [];
  let current = null;
  for (const message of history || []) {
    if (isNoteMessage(message)) continue;
    if (message?.role === "user") {
      if (current) turns.push(current);
      current = { messages: [message], open: true };
      continue;
    }
    if (!current) current = { messages: [], open: true };
    current.messages.push(message);
  }
  if (current) turns.push(current);
  for (const turn of turns) {
    const hasAssistant = turn.messages.some((message) => message.role === "assistant");
    const toolCalls = new Set();
    const toolResults = new Set();
    for (const message of turn.messages) {
      for (const call of message.toolCalls || message.tool_calls || []) {
        const id = call.id || call.toolCallId || call.tool_call_id;
        if (id) toolCalls.add(id);
      }
      if (message.role === "tool" || message.toolCallId || message.tool_call_id) {
        const id = message.toolCallId || message.tool_call_id;
        if (id) toolResults.add(id);
      }
    }
    const pendingTools = [...toolCalls].some((id) => !toolResults.has(id));
    turn.open = !hasAssistant || pendingTools;
  }
  return turns;
}

function messageKey(message) {
  if (!message) return "";
  return String(message.id || message.timestamp || message.createdAt || spokenTurnHash(message.role || "", indexableText(message) || extractText(message)));
}

function turnStartKey(turn) {
  const first = (turn?.messages || []).find((message) => message?.role === "user") || turn?.messages?.[0];
  return messageKey(first);
}

function renderNoteBody(history, currentTask) {
  return `### History\n${String(history || "").trim()}\n\n### Current task\n${String(currentTask || "").trim()}\n`;
}

function parseNoteMarkdown(markdown) {
  const text = String(markdown || "");
  if (!text.trim()) return { history: "", current_task: "" };
  const historyMatch = text.match(/### History\s*\n([\s\S]*?)(?=\n### Current task\b|$)/i);
  const taskMatch = text.match(/### Current task\s*\n([\s\S]*)$/i);
  return {
    history: String(historyMatch?.[1] || "").trim(),
    current_task: String(taskMatch?.[1] || "").trim(),
  };
}

export function readSessionNote(fingerprint = "") {
  ensureStore();
  const fp = fingerprint || readActiveFingerprint();
  if (!fp) return null;
  const markdown = readNoteMarkdown(fp);
  if (!markdown.trim()) return null;
  const parsed = parseNoteMarkdown(markdown);
  return {
    id: fp,
    fingerprint: fp,
    origin: "note",
    history: parsed.history,
    current_task: parsed.current_task,
    summary: markdown.trim(),
    updatedAt: Date.now(),
  };
}

/**
 * Replace the whole note. Codex-style: one file per conversation, no chapters, no clustering.
 */
export function writeSessionNote({
  history = "",
  current_task = "",
  fingerprint = "",
} = {}) {
  ensureStore();
  const fp = fingerprint || readActiveFingerprint();
  if (!fp) return { ok: false, reason: "no conversation fingerprint" };
  const nextHistory = capSection(history, MAX_HISTORY_CHARS);
  const nextCurrent = capSection(current_task, MAX_CURRENT_TASK_CHARS);
  writeNoteMarkdown(fp, renderNoteBody(nextHistory, nextCurrent));
  return { ok: true, id: fp };
}

const NOTE_INSTRUCTIONS = [
  "## Session note",
  "Your own running note for this conversation. Long-term recall is EverMe; this note is only the current working layer.",
  "Call notes before this turn ends — every turn, including the ones where nothing finished. Rewrite BOTH sections: move anything you have finished into History, and replace Current task with what you are working on now. Rewrite them, do not append.",
  "Before starting a new context window, call notes first, then new_context. new_context cuts the live window without summarizing.",
  "When EverMe recall (or mem_search) returns an aether_hash, use read_original with that hash to recover the exact wording. Do not invent hashes.",
].join("\n");

function injectedMessage(text, origin) {
  return {
    role: "user",
    content: [{ type: "text", text }],
    origin: { variant: origin, kind: origin },
  };
}

function noteMessage(note) {
  return injectedMessage(
    `${NOTE_INSTRUCTIONS}\n\n${renderNoteBody(note?.history, note?.current_task)}`,
    NOTE_ORIGIN,
  );
}

export function collectSpokenMessages(history, fingerprint = "") {
  const fp = fingerprint || conversationFingerprint(history);
  const sessionId = readSessionId();
  const spoken = [];
  for (const message of history || []) {
    if (isNoteMessage(message)) continue;
    const role = message?.role;
    if (!INDEXABLE_ROLES.includes(role)) continue;
    const content = indexableText(message).trim();
    if (!content) continue;
    const hash = spokenTurnHash(role, content);
    const ids = [message.id, message.messageId].filter(Boolean).map(String);
    const record = {
      hash,
      role,
      content,
      timestamp: Number(message.timestamp || message.createdAt || Date.now()),
      fingerprint: fp,
      sessionId,
      messageIds: ids,
    };
    spoken.push(record);
  }
  return spoken;
}

function ingestSpokenTurns(history, fingerprint) {
  const spoken = collectSpokenMessages(history, fingerprint);
  for (const record of spoken) writeOriginal(record);
  enqueueSpoken(spoken);
  try {
    drainEverMeQueueAsync();
  } catch {
    // EverMe is long-term memory. A failed drain must not throw into fold, or Kimi compact
    // never runs and the window grows until the provider rejects the request.
  }
}

export function requestNewContext() {
  ensureStore();
  try {
    writeFileSync(join(memoryRoot(), "new-context.request"), String(Date.now()));
  } catch {
    // A failed request is a missed cut, not an error worth surfacing to the model.
  }
}

function consumeNewContextRequest() {
  const path = join(memoryRoot(), "new-context.request");
  try {
    if (!existsSync(path)) return false;
    rmSync(path, { force: true });
    return true;
  } catch {
    return false;
  }
}

function cutWindow(fingerprint, turns) {
  const lastUser = [...turns].reverse().find((turn) => (turn.messages || []).some((message) => message?.role === "user"));
  const start = lastUser || turns[turns.length - 1];
  if (!start) return;
  writeWindowStart(fingerprint, turnStartKey(start));
}

function applyWindowCut(turns, fingerprint) {
  const startKey = readWindowStart(fingerprint);
  if (!startKey) return turns;
  const index = turns.findIndex((turn) => turnStartKey(turn) === startKey);
  if (index < 0) return turns.slice(-1);
  return turns.slice(index);
}

function injectNote(messages, note) {
  if (!note) return messages;
  const kept = messages.map(cloneMessage);
  let at = kept.length;
  for (let i = kept.length - 1; i >= 0; i -= 1) {
    if (kept[i]?.role === "user") {
      at = i;
      break;
    }
  }
  kept.splice(at, 0, noteMessage(note));
  return kept;
}

/**
 * Notes, new_context, and EverMe ingest. Does not drop turns for budget —
 * Kimi compact is the window safety valve.
 */
export function foldForModel(history) {
  if (!Array.isArray(history) || history.length === 0) return history;
  ensureStore();
  const sessionId = readSessionId();
  if (sessionId) writeSessionId(sessionId);
  const fingerprint = conversationFingerprint(history);
  markActiveFingerprint(fingerprint);
  ingestSpokenTurns(history, fingerprint);
  const turns = splitTurns(history);
  if (consumeNewContextRequest()) cutWindow(fingerprint, turns);
  const visibleTurns = applyWindowCut(turns, fingerprint);
  const keptMessages = visibleTurns.flatMap((turn) => turn.messages.map(cloneMessage));
  const note = readSessionNote(fingerprint);
  return injectNote(keptMessages, note);
}

export function foldStats(history) {
  const turns = splitTurns(history);
  const fingerprint = conversationFingerprint(history);
  const cut = applyWindowCut(turns, fingerprint);
  return {
    fingerprint,
    turns: turns.length,
    visibleTurns: cut.length,
    windowStart: readWindowStart(fingerprint),
    note: Boolean(readSessionNote(fingerprint)),
  };
}

export function readOriginalByHash(hash) {
  ensureStore();
  const record = readOriginal(String(hash || "").trim());
  if (!record) return `original ${hash} not found`;
  const ids = Array.isArray(record.messageIds) && record.messageIds.length
    ? record.messageIds.join(", ")
    : "(none)";
  return [
    `## original ${record.hash}`,
    `role: ${record.role || ""}`,
    `session: ${record.sessionId || ""}`,
    `fingerprint: ${record.fingerprint || ""}`,
    `message_ids: ${ids}`,
    "",
    record.content || "",
  ].join("\n");
}

export { drainEverMeQueue, drainEverMeQueueAsync, enqueueSpoken, pendingCount, setEverMeUploader, uploadedCount } from "./everme.mjs";
export { sha256Hex };
