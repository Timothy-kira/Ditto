#!/usr/bin/env node
import {
  readOriginalByHash,
  requestNewContext,
  writeSessionNote,
} from "./index.mjs";
import { writeSessionId } from "./store.mjs";

const sessionId = String(process.env.AETHER_MEMORY_SESSION || "").trim();
if (sessionId) writeSessionId(sessionId);

const TOOLS = [
  {
    name: "notes",
    description:
      "Rewrite your running note for this conversation. Call this before every turn ends, including turns where nothing finished. Pass both sections in full: this REPLACES the note rather than appending. Write the note before calling new_context.",
    inputSchema: {
      type: "object",
      properties: {
        history: {
          type: "string",
          description: "Everything already settled in this conversation. Carry the previous history forward and add what just finished; drop detail that no longer matters.",
        },
        current_task: {
          type: "string",
          description: "What is being worked on right now and how far it has got. Replaced wholesale each turn — not a log.",
        },
      },
      required: ["history", "current_task"],
      additionalProperties: false,
    },
  },
  {
    name: "new_context",
    description:
      "Start a new context window now, without summarizing the conversation. Does not clear, reset, or otherwise affect anything outside the context. Call notes first — that is what makes this safe.",
    inputSchema: { type: "object", properties: {}, required: [], additionalProperties: false },
  },
  {
    name: "read_original",
    description:
      "Look up the original spoken wording for an aether_hash returned by EverMe recall or mem_search. This is a primary-key lookup, not search.",
    inputSchema: {
      type: "object",
      properties: {
        hash: { type: "string", description: "The aether_hash value from an EverMe memory entry." },
      },
      required: ["hash"],
      additionalProperties: false,
    },
  },
];

function okText(text) {
  return {
    content: [{ type: "text", text: String(text ?? "") }],
  };
}

function handleCall(name, args) {
  if (name === "notes" || name === "update_session_note") {
    const result = writeSessionNote({
      history: String(args?.history ?? ""),
      current_task: String(args?.current_task ?? ""),
    });
    if (!result.ok) return okText(result.reason || "write failed");
    return okText("Session note updated.");
  }
  if (name === "new_context") {
    requestNewContext();
    return okText("A new context window will start without summarizing conversation history.");
  }
  if (name === "read_original") {
    const hash = String(args?.hash || args?.aether_hash || "").trim();
    if (!hash) return okText("hash is required");
    return okText(readOriginalByHash(hash));
  }
  return { content: [{ type: "text", text: `Unknown tool ${name}` }], isError: true };
}

function writeMessage(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

function reply(id, result) {
  writeMessage({ jsonrpc: "2.0", id, result });
}

function handleRequest(message) {
  const { id, method, params } = message;
  if (method === "initialize") {
    reply(id, {
      protocolVersion: params?.protocolVersion || "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "session_memory", version: "9" },
    });
    return;
  }
  if (method === "notifications/initialized" || method === "initialized") return;
  if (method === "tools/list") {
    reply(id, { tools: TOOLS });
    return;
  }
  if (method === "tools/call") {
    const name = params?.name;
    const args = params?.arguments || {};
    reply(id, handleCall(name, args));
    return;
  }
  if (method === "ping") {
    reply(id, {});
    return;
  }
  if (id !== undefined) {
    writeMessage({
      jsonrpc: "2.0",
      id,
      error: { code: -32601, message: `Method not found: ${method}` },
    });
  }
}

let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  while (true) {
    const newline = buffer.indexOf("\n");
    if (newline < 0) break;
    const line = buffer.slice(0, newline).trim();
    buffer = buffer.slice(newline + 1);
    if (!line) continue;
    try {
      handleRequest(JSON.parse(line));
    } catch (error) {
      writeMessage({
        jsonrpc: "2.0",
        id: null,
        error: { code: -32700, message: String(error?.message || error) },
      });
    }
  }
});

process.stdin.on("end", () => process.exit(0));
