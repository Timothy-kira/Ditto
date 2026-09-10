import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  writeFileSync,
} from "node:fs";
import { dirname, join } from "node:path";

function homeRoot() {
  return process.env.KIMI_CODE_HOME || join(process.env.HOME || "/root", ".kimi-code");
}

export function memoryRoot() {
  return join(homeRoot(), "session-memory");
}

function notesDir() {
  return join(memoryRoot(), "notes");
}

function originalsDir() {
  return join(memoryRoot(), "originals");
}

function ingestDir() {
  return join(memoryRoot(), "ingest");
}

function windowsDir() {
  return join(memoryRoot(), "windows");
}

function ensureDir(path) {
  mkdirSync(path, { recursive: true });
}

export function sha256Hex(text) {
  return createHash("sha256").update(String(text ?? ""), "utf8").digest("hex");
}

export function shortId(text, size = 12) {
  return sha256Hex(text).slice(0, size);
}

function atomicWrite(path, body) {
  ensureDir(dirname(path));
  const temporary = `${path}.tmp`;
  writeFileSync(temporary, body, "utf8");
  renameSync(temporary, path);
}

function readJson(path, fallback) {
  if (!existsSync(path)) return fallback;
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return fallback;
  }
}

export function writeNoteMarkdown(fingerprint, markdown) {
  ensureDir(notesDir());
  const path = join(notesDir(), `${fingerprint}.md`);
  atomicWrite(path, String(markdown ?? ""));
  markActiveFingerprint(fingerprint);
  return path;
}

export function readNoteMarkdown(fingerprint) {
  if (!fingerprint) return "";
  const path = join(notesDir(), `${fingerprint}.md`);
  if (!existsSync(path)) return "";
  return readFileSync(path, "utf8");
}

export function listNoteFingerprints() {
  if (!existsSync(notesDir())) return [];
  return readdirSync(notesDir())
    .filter((name) => name.endsWith(".md"))
    .map((name) => name.slice(0, -3));
}

export function writeOriginal(record) {
  if (!record?.hash) return;
  ensureDir(originalsDir());
  atomicWrite(join(originalsDir(), `${record.hash}.json`), JSON.stringify(record, null, 2));
}

export function readOriginal(hash) {
  const id = String(hash || "").trim();
  if (!id) return null;
  const path = join(originalsDir(), `${id}.json`);
  if (!existsSync(path)) return null;
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return null;
  }
}

export function readUploadedHashes() {
  const parsed = readJson(join(ingestDir(), "uploaded.json"), []);
  return new Set(Array.isArray(parsed) ? parsed.map(String) : []);
}

export function writeUploadedHashes(hashes) {
  ensureDir(ingestDir());
  atomicWrite(join(ingestDir(), "uploaded.json"), JSON.stringify([...hashes], null, 2));
}

export function readPendingIngest() {
  const parsed = readJson(join(ingestDir(), "pending.json"), []);
  return Array.isArray(parsed) ? parsed : [];
}

export function writePendingIngest(items) {
  ensureDir(ingestDir());
  atomicWrite(join(ingestDir(), "pending.json"), JSON.stringify(items, null, 2));
}

export function readWindowStart(fingerprint) {
  if (!fingerprint) return "";
  const parsed = readJson(join(windowsDir(), `${fingerprint}.json`), null);
  return parsed?.startKey || "";
}

export function writeWindowStart(fingerprint, startKey) {
  if (!fingerprint) return;
  ensureDir(windowsDir());
  atomicWrite(
    join(windowsDir(), `${fingerprint}.json`),
    JSON.stringify({ fingerprint, startKey: String(startKey || ""), updatedAt: Date.now() }),
  );
}

export function readSessionId() {
  const fromEnv = String(process.env.AETHER_MEMORY_SESSION || "").trim();
  if (fromEnv) return fromEnv;
  const path = join(memoryRoot(), "session-id");
  if (!existsSync(path)) return "";
  try {
    return readFileSync(path, "utf8").trim();
  } catch {
    return "";
  }
}

export function writeSessionId(sessionId) {
  const id = String(sessionId || "").trim();
  if (!id) return;
  ensureDir(memoryRoot());
  writeFileSync(join(memoryRoot(), "session-id"), id, "utf8");
}

export function markActiveFingerprint(fingerprint) {
  const fp = String(fingerprint || "").trim();
  if (!fp) return;
  ensureDir(memoryRoot());
  writeFileSync(
    join(memoryRoot(), "active.json"),
    JSON.stringify({ fingerprint: fp, updatedAt: Date.now() }),
    "utf8",
  );
}

export function readActiveFingerprint() {
  const path = join(memoryRoot(), "active.json");
  if (!existsSync(path)) return "";
  try {
    return JSON.parse(readFileSync(path, "utf8")).fingerprint || "";
  } catch {
    return "";
  }
}

export function ensureStore() {
  ensureDir(notesDir());
  ensureDir(originalsDir());
  ensureDir(ingestDir());
  ensureDir(windowsDir());
}
