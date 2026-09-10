import { stripInjected } from "./text.mjs";

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

export function extractText(message) {
  if (!message) return "";
  if (typeof message === "string") return message;
  if (typeof message.content === "string") return message.content;
  const parts = asArray(message.content);
  const texts = [];
  for (const part of parts) {
    if (typeof part === "string") {
      texts.push(part);
      continue;
    }
    if (!part || typeof part !== "object") continue;
    if (typeof part.text === "string") texts.push(part.text);
    else if (typeof part.content === "string") texts.push(part.content);
    else if (typeof part.output === "string") texts.push(part.output);
  }
  if (texts.length) return texts.join("\n");
  if (typeof message.text === "string") return message.text;
  return "";
}

/** Spoken turns only. Thinking / tool dumps stay out of EverMe ingest. */
export const INDEXABLE_ROLES = Object.freeze(["user", "assistant"]);

const HIDDEN_PART_TYPES = new Set([
  "thinking",
  "reasoning",
  "redacted_thinking",
  "tool_use",
  "toolcall",
  "tool_call",
  "function_call",
]);

function isHiddenContentPart(part) {
  if (!part || typeof part !== "object") return false;
  const type = String(part.type || "").toLowerCase();
  if (HIDDEN_PART_TYPES.has(type)) return true;
  if (typeof part.thinking === "string" && part.text == null && part.content == null) return true;
  return false;
}

export function indexableText(message) {
  if (!message) return "";
  const role = message.role;
  if (!INDEXABLE_ROLES.includes(role)) return "";
  const text = extractVisibleText(message);
  return role === "user" ? stripInjected(text) : text;
}

function extractVisibleText(message) {
  if (typeof message === "string") return message;
  if (typeof message.content === "string") return message.content;
  const parts = asArray(message.content);
  const texts = [];
  for (const part of parts) {
    if (typeof part === "string") {
      texts.push(part);
      continue;
    }
    if (!part || typeof part !== "object" || isHiddenContentPart(part)) continue;
    if (typeof part.text === "string") texts.push(part.text);
    else if (typeof part.content === "string") texts.push(part.content);
  }
  if (texts.length) return texts.join("\n");
  if (typeof message.text === "string") return message.text;
  return "";
}
