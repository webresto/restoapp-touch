// Runtime glue between Preact and the Kotlin host.
// Preact renders into fake DOM nodes; every DOM operation becomes a mutation in a batch;
// the batch goes to Kotlin in ONE call (__host.commit) after Preact has finished rendering.
import { render, options, h, type ComponentChild } from "preact";

declare const __host: {
  commit(json: string): void;
  scheduleTimer(id: number, ms: number): void;
  cancelTimer(id: number): void;
  rpc(id: number, service: string, method: string, argsJson: string): void;
  log(msg: string): void;
};

type Mutation =
  | { op: "create"; id: number; type: string }
  | { op: "text"; id: number; value: string }
  | { op: "set"; id: number; name: string; value: unknown }
  | { op: "listen"; id: number; event: string; on: boolean }
  | { op: "insert"; parent: number; child: number; before: number | null }
  | { op: "remove"; parent: number; child: number };

let pending: Mutation[] = [];
let commitId = 0;
let flushQueued = false;

function record(m: Mutation) {
  pending.push(m);
  if (!flushQueued) {
    flushQueued = true;
    // Queued after Preact's own render microtask, so the whole render lands in one batch.
    Promise.resolve().then(flush);
  }
}

function flush() {
  flushQueued = false;
  if (pending.length === 0) return;
  const batch = { protocol: 1, commitId: ++commitId, mutations: pending };
  pending = [];
  __host.commit(JSON.stringify(batch));
}

let nextId = 1;
const nodes = new Map<number, FakeNode>();

class FakeNode {
  _id = nextId++;
  parentNode: FakeNode | null = null;
  childNodes: FakeNode[] = [];
  namespaceURI = "http://www.w3.org/1999/xhtml";
  l?: Record<string, Function>; // Preact's listener map (mangled name)
  _listeners: Record<string, { fn: Function; type: string }> = {};

  constructor(public nodeType: number, public localName: string) {
    nodes.set(this._id, this);
    if (nodeType === 1) record({ op: "create", id: this._id, type: localName });
  }
  get nodeName() { return this.localName; }
  get firstChild() { return this.childNodes[0] ?? null; }
  get nextSibling() {
    if (!this.parentNode) return null;
    const s = this.parentNode.childNodes;
    return s[s.indexOf(this) + 1] ?? null;
  }

  contains(other: FakeNode | null): boolean {
    for (let n = other; n; n = n.parentNode) if (n === this) return true;
    return false;
  }
  insertBefore(child: FakeNode, before: FakeNode | null) {
    if (child.parentNode) detach(child);
    const i = before ? this.childNodes.indexOf(before) : -1;
    if (i < 0) this.childNodes.push(child); else this.childNodes.splice(i, 0, child);
    child.parentNode = this;
    record({ op: "insert", parent: this._id, child: child._id, before: before ? before._id : null });
    return child;
  }
  appendChild(child: FakeNode) { return this.insertBefore(child, null); }
  removeChild(child: FakeNode) {
    detach(child);
    record({ op: "remove", parent: this._id, child: child._id });
    forget(child);
    return child;
  }
  setAttribute(name: string, value: unknown) { record({ op: "set", id: this._id, name, value }); }
  removeAttribute(name: string) { record({ op: "set", id: this._id, name, value: null }); }
  addEventListener(type: string, fn: Function) {
    // Preact looks the handler up by the original case (e.type + capture), so keep it.
    this._listeners[type.toLowerCase()] = { fn, type };
    record({ op: "listen", id: this._id, event: type.toLowerCase(), on: true });
  }
  removeEventListener(type: string) {
    delete this._listeners[type.toLowerCase()];
    record({ op: "listen", id: this._id, event: type.toLowerCase(), on: false });
  }
}

class FakeText extends FakeNode {
  _text = "";
  constructor() { super(3, "#text"); }
  get data() { return this._text; }
  set data(v: string) { this._text = String(v); record({ op: "text", id: this._id, value: this._text }); }
}

function detach(child: FakeNode) {
  const p = child.parentNode!;
  p.childNodes.splice(p.childNodes.indexOf(child), 1);
  child.parentNode = null;
}
function forget(n: FakeNode) {
  nodes.delete(n._id);
  n.childNodes.forEach(forget);
}

const fakeDocument = {
  createElement: (type: string) => new FakeNode(1, type),
  createElementNS: (_ns: string, type: string) => new FakeNode(1, type),
  createTextNode: (text: string) => {
    const n = new FakeText();
    n.data = text;
    return n;
  },
};
(globalThis as any).document = fakeDocument;

// --- timers: owned by Kotlin, so the runtime has a real event loop ---
let timerSeq = 0;
const timers = new Map<number, () => void>();
(globalThis as any).setTimeout = (fn: () => void, ms = 0) => {
  const id = ++timerSeq;
  timers.set(id, fn);
  __host.scheduleTimer(id, ms | 0);
  return id;
};
(globalThis as any).clearTimeout = (id: number) => {
  if (timers.delete(id)) __host.cancelTimer(id);
};
(globalThis as any).__fireTimer = (id: number) => {
  const fn = timers.get(id);
  timers.delete(id);
  fn?.();
};

// --- typed RPC: Promise on the JS side, suspend on the Kotlin side ---
let rpcSeq = 0;
const calls = new Map<number, { resolve: (v: any) => void; reject: (e: any) => void }>();
export function call<T = unknown>(service: string, method: string, args: unknown = {}): Promise<T> {
  const id = ++rpcSeq;
  return new Promise<T>((resolve, reject) => {
    calls.set(id, { resolve, reject });
    __host.rpc(id, service, method, JSON.stringify(args));
  });
}
(globalThis as any).__rpcSettle = (id: number, ok: boolean, json: string) => {
  const c = calls.get(id);
  calls.delete(id);
  if (!c) return;
  const value = JSON.parse(json);
  if (ok) c.resolve(value);
  else c.reject(Object.assign(new Error(value.message), value));
};

// --- events from Compose ---
(globalThis as any).__dispatch = (nodeId: number, type: string, payloadJson: string) => {
  const node = nodes.get(nodeId);
  const l = node?._listeners[type];
  if (!node || !l) return false;
  l.fn.call(node, { type: l.type, payload: JSON.parse(payloadJson || "null") });
  return true;
};

options.debounceRendering = (cb) => Promise.resolve().then(cb);

// --- surfaces ---
const surfaces: Record<string, () => ComponentChild> = {};
export function registerSurface(name: string, root: () => ComponentChild) {
  surfaces[name] = root;
}
(globalThis as any).__mount = (name: string) => {
  const root = new FakeNode(1, "Root");
  render(h(surfaces[name] as any, null), root as any);
  return root._id;
};
