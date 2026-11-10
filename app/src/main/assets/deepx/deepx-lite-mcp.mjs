#!/usr/bin/env node
import { createInterface } from "readline";
import { readFileSync } from "fs";
import { dirname, join } from "path";
import { fileURLToPath } from "url";
import { request as httpRequest } from "http";

const here = dirname(fileURLToPath(import.meta.url));

function bridgeBaseUrl() {
  if (process.env.DEEPX_BRIDGE_URL) return process.env.DEEPX_BRIDGE_URL.replace(/\/+$/, "");
  try {
    const port = readFileSync(join(here, "bridge-port"), "utf8").trim();
    if (port) return `http://127.0.0.1:${port}`;
  } catch {}
  return "http://127.0.0.1:8792";
}

function send(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

function sendResult(id, result) {
  send({ jsonrpc: "2.0", id, result });
}

function sendError(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } });
}

function textResult(value) {
  const text = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  return { content: [{ type: "text", text }] };
}

function errorResult(message) {
  return { content: [{ type: "text", text: `Error: ${message}` }], isError: true };
}

function postCommand(command, payload = {}, timeout = 30000) {
  return new Promise((resolve, reject) => {
    let base;
    try {
      base = new URL("/api/command", bridgeBaseUrl());
    } catch (failure) {
      reject(failure);
      return;
    }
    const body = JSON.stringify({ command, payload, timeout });
    const req = httpRequest(
      {
        hostname: base.hostname,
        port: base.port || 80,
        path: base.pathname,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body),
        },
      },
      (res) => {
        let raw = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => {
          raw += chunk;
        });
        res.on("end", () => {
          try {
            const parsed = JSON.parse(raw || "{}");
            if (!parsed.success) reject(new Error(parsed.error || `Bridge returned HTTP ${res.statusCode}`));
            else resolve(parsed.result);
          } catch (failure) {
            reject(new Error(`Invalid bridge response: ${raw.slice(0, 300)}`));
          }
        });
      },
    );
    req.setTimeout(Math.max(timeout + 5000, 10000), () => {
      req.destroy(new Error("DeepX bridge request timed out"));
    });
    req.on("error", (failure) => {
      const wrapped = new Error(failure.message || "DeepX bridge is not reachable");
      wrapped.code = failure.code;
      reject(wrapped);
    });
    req.write(body);
    req.end();
  });
}

const TOOLS = [
  {
    name: "deepx_status",
    description:
      "Check the QCODE DeepX bridge, Google Flow tab, project id, and signed-in account. Use this first.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "deepx_eval_script",
    description: "Evaluate JavaScript in the active Google Flow tab and return its JSON value.",
    inputSchema: {
      type: "object",
      properties: {
        code: { type: "string", description: "JavaScript expression or function body." },
      },
      required: ["code"],
    },
  },
  {
    name: "deepx_native_click",
    description: "Click the element at viewport coordinates in the active Google Flow tab.",
    inputSchema: {
      type: "object",
      properties: {
        x: { type: "number" },
        y: { type: "number" },
      },
      required: ["x", "y"],
    },
  },
  {
    name: "deepx_native_key",
    description: "Dispatch a keyboard key event to the active Google Flow tab.",
    inputSchema: {
      type: "object",
      properties: {
        key: { type: "string" },
        code: { type: "string" },
        keyCode: { type: "number" },
      },
      required: ["key"],
    },
  },
  {
    name: "deepx_navigate",
    description: "Open or navigate the QCODE Browser to a URL, normally https://flow.google.com.",
    inputSchema: {
      type: "object",
      properties: { url: { type: "string" } },
      required: ["url"],
    },
  },
  {
    name: "deepx_reload_tab",
    description: "Reload the active Google Flow tab.",
    inputSchema: { type: "object", properties: {} },
  },
];

const COMMANDS = {
  deepx_status: "status",
  deepx_eval_script: "eval_script",
  deepx_native_click: "native_click",
  deepx_native_key: "native_key",
  deepx_navigate: "navigate",
  deepx_reload_tab: "reload_tab",
};

function payloadFor(tool, args) {
  switch (tool) {
    case "deepx_eval_script":
      return { code: args.code || "" };
    case "deepx_native_click":
      return { x: Number(args.x || 0), y: Number(args.y || 0) };
    case "deepx_native_key":
      return {
        key: args.key || "Enter",
        code: args.code || args.key || "Enter",
        keyCode: Number(args.keyCode || 13),
      };
    case "deepx_navigate":
      return { url: args.url || "https://flow.google.com" };
    default:
      return {};
  }
}

async function callTool(id, name, args) {
  const command = COMMANDS[name];
  if (!command) {
    sendError(id, -32602, `Unknown tool: ${name}`);
    return;
  }
  try {
    const result = await postCommand(command, payloadFor(name, args), 30000);
    sendResult(id, textResult(result));
  } catch (failure) {
    if (name === "deepx_status") {
      sendResult(
        id,
        textResult({
          status: "disconnected",
          ready: false,
          connectionState: "disconnected",
          bridge: bridgeBaseUrl(),
          message: "QCODE DeepX bridge is not reachable.",
          error: failure.message,
          instructions: [
            "Open QCODE Browser.",
            "Open https://flow.google.com and sign in.",
            "Call deepx_status again.",
          ],
        }),
      );
      return;
    }
    sendResult(id, errorResult(failure.message || "DeepX command failed"));
  }
}

async function handle(message) {
  if (!message || typeof message !== "object") return;
  if (Array.isArray(message)) {
    for (const item of message) await handle(item);
    return;
  }
  const { id, method, params } = message;
  if (method === "initialize") {
    const protocolVersion = params?.protocolVersion || "2024-11-05";
    sendResult(id, {
      protocolVersion,
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "deepx-flow", version: "1.0.0" },
    });
    return;
  }
  if (method === "notifications/initialized") return;
  if (method === "ping") {
    sendResult(id, {});
    return;
  }
  if (method === "tools/list") {
    sendResult(id, { tools: TOOLS });
    return;
  }
  if (method === "tools/call") {
    await callTool(id, params?.name || "", params?.arguments || {});
    return;
  }
  if (id !== undefined && id !== null) sendError(id, -32601, `Method not found: ${method}`);
}

const input = createInterface({ input: process.stdin, terminal: false });
input.on("line", (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch (failure) {
    sendError(null, -32700, `Parse error: ${failure.message}`);
    return;
  }
  handle(message).catch((failure) => {
    if (message && message.id !== undefined && message.id !== null) {
      sendError(message.id, -32603, failure.message || "Internal error");
    }
  });
});
