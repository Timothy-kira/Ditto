import { existsSync, readFileSync } from "node:fs";
import { spawn, spawnSync } from "node:child_process";
import { basename, delimiter } from "node:path";
import { fileURLToPath } from "node:url";

import {
  readPendingIngest,
  readSessionId,
  readUploadedHashes,
  writePendingIngest,
  writeUploadedHashes,
} from "./store.mjs";

const OK_MARKER = "ditto_everme_upload_ok";
const PLUGIN_ROOT = "/root/.kimi-code/plugins/managed/everme";
const ENV_FILE = "/root/.kimi-code/everme.env";

let testUploader = null;

/** Selftest / host stubs. Production drain uses the bundled @everme/agent-sdk. */
export function setEverMeUploader(fn) {
  testUploader = typeof fn === "function" ? fn : null;
}

export function evermeEnvPath() {
  return process.env.AETHER_EVERME_ENV || ENV_FILE;
}

function everMeAvailable() {
  return existsSync(evermeEnvPath());
}

function loadEverMeEnv() {
  const path = evermeEnvPath();
  if (!existsSync(path)) return {};
  const env = {};
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const cut = trimmed.indexOf("=");
    if (cut <= 0) continue;
    let value = trimmed.slice(cut + 1).trim();
    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    env[trimmed.slice(0, cut).trim()] = value;
  }
  return env;
}

const UPLOAD_SCRIPT = `
import { createClient, resolveConfig, assertConfigUsable, savePersonalMemory, redactError }
  from "@everme/agent-sdk";
try {
  const payload = JSON.parse(process.env.DITTO_MEMORY_JSON || "{}");
  const messages = Array.isArray(payload.messages) ? payload.messages : [];
  if (!messages.length) { console.log("${OK_MARKER}"); process.exit(0); }
  const cfg = resolveConfig({});
  assertConfigUsable(cfg, { requireAgentId: false });
  const client = createClient(cfg);
  await savePersonalMemory(client, {
    conversationId: payload.conversationId,
    messages,
  });
  console.log("${OK_MARKER}");
} catch (error) {
  console.log("ditto_everme_upload_failed: " + redactError(error));
  process.exit(1);
}
`;

function defaultUpload(payload) {
  if (!everMeAvailable()) return false;
  const nodePath = process.env.NODE_PATH
    ? `${PLUGIN_ROOT}/node_modules${delimiter}${process.env.NODE_PATH}`
    : `${PLUGIN_ROOT}/node_modules`;
  const result = spawnSync(process.execPath, ["--input-type=module", "-e", UPLOAD_SCRIPT], {
    env: {
      ...process.env,
      ...loadEverMeEnv(),
      NODE_PATH: nodePath,
      DITTO_MEMORY_JSON: JSON.stringify(payload),
    },
    encoding: "utf8",
    timeout: 8_000,
    maxBuffer: 2 * 1024 * 1024,
  });
  const output = `${result.stdout || ""}${result.stderr || ""}`;
  return result.status === 0 && output.includes(OK_MARKER);
}

function uploadBatch(payload) {
  if (testUploader) {
    try {
      return Boolean(testUploader(payload));
    } catch {
      return false;
    }
  }
  return defaultUpload(payload);
}

function conversationIdOf(item) {
  return String(item.sessionId || item.fingerprint || readSessionId() || "aether-session").trim();
}

/**
 * Drain the pending spoken-turn queue.
 *
 * Failure leaves the queue intact so the next fold retries. Compact is a different
 * safety valve and must not wait on this.
 */
export function drainEverMeQueue() {
  const pending = readPendingIngest();
  if (!pending.length) return { uploaded: 0, failed: pending.length };
  const uploaded = readUploadedHashes();
  const remaining = [];
  const groups = new Map();
  for (const item of pending) {
    if (!item?.hash || uploaded.has(item.hash)) continue;
    const id = conversationIdOf(item);
    if (!groups.has(id)) groups.set(id, []);
    groups.get(id).push(item);
  }
  let uploadedCount = 0;
  for (const [conversationId, items] of groups) {
    const payload = {
      conversationId,
      messages: items.map((item) => ({
        role: item.role,
        content: `${item.content}\n[aether_hash=${item.hash}]`,
        timestamp: item.timestamp || Date.now(),
      })),
    };
    if (uploadBatch(payload)) {
      for (const item of items) uploaded.add(item.hash);
      uploadedCount += items.length;
    } else {
      remaining.push(...items);
    }
  }
  writeUploadedHashes(uploaded);
  writePendingIngest(remaining);
  return { uploaded: uploadedCount, failed: remaining.length };
}

/**
 * Fold must not wait on EverMe. Tests keep the sync path; production detaches.
 */
export function drainEverMeQueueAsync() {
  if (testUploader) return drainEverMeQueue();
  if (!everMeAvailable()) return { uploaded: 0, failed: readPendingIngest().length };
  try {
    const child = spawn(process.execPath, [fileURLToPath(import.meta.url), "drain"], {
      detached: true,
      stdio: "ignore",
      env: { ...process.env },
    });
    child.unref();
  } catch {
    try { drainEverMeQueue(); } catch { /* next fold retries */ }
  }
  return { uploaded: 0, failed: 0, deferred: true };
}

export function enqueueSpoken(items) {
  const uploaded = readUploadedHashes();
  const pending = readPendingIngest();
  const seen = new Set(pending.map((item) => item.hash));
  let added = 0;
  for (const item of items || []) {
    if (!item?.hash || uploaded.has(item.hash) || seen.has(item.hash)) continue;
    pending.push(item);
    seen.add(item.hash);
    added += 1;
  }
  if (added) writePendingIngest(pending);
  return added;
}

export function pendingCount() {
  return readPendingIngest().length;
}

export function uploadedCount() {
  return readUploadedHashes().size;
}

if (basename(process.argv[1] || "") === "everme.mjs" && process.argv[2] === "drain") {
  drainEverMeQueue();
}
