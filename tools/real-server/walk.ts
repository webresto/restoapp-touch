/**
 * The whole mobile API walked against a running stand, printed step by step: what every user of the
 * stand sees, «Блюда» at both points, the catalog, the audit, and the errors. Written for the
 * manual run of .ai-notes/real-server-walkthrough.md, not for CI — the tests of restoapp-dev assert
 * the same things.
 *
 *   cd ../restoapp-dev && npx tsx ../restoapp-mobile-admin/tools/real-server/walk.ts [url]
 */
import * as path from "path";
import { pathToFileURL } from "url";

const base = process.argv[2] ?? "http://127.0.0.1:1341";
const PASSWORD = "secret-password";

let failures = 0;
const ok = (good: boolean, what: string, got?: unknown) => {
  if (!good) failures += 1;
  console.log(`${good ? "  ok  " : " FAIL "} ${what}${good || got === undefined ? "" : ` — got ${JSON.stringify(got)}`}`);
};
const same = (got: unknown, want: unknown, what: string) =>
  ok(JSON.stringify(got) === JSON.stringify(want), what, got);

async function request(method: string, url: string, options: { token?: string; body?: unknown } = {}) {
  const response = await fetch(`${base}${url}`, {
    method,
    headers: {
      ...(options.body !== undefined ? { "content-type": "application/json" } : {}),
      ...(options.token ? { authorization: `Bearer ${options.token}` } : {}),
    },
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });
  const text = await response.text();
  let body: any = text;
  try { body = JSON.parse(text); } catch { /* left as text for the message */ }
  return { status: response.status, headers: response.headers, body };
}

let puzzleFailures = 0;

async function solve(task: number[]): Promise<string> {
  const adminizerDir = path.dirname(require.resolve("adminizer", { paths: [process.cwd()] }));
  const entry = require.resolve("crypto-puzzle", { paths: [adminizerDir] });
  const Puzzle = (await import(pathToFileURL(entry).href)).default;
  return Puzzle.solve(Uint8Array.from(task));
}

/**
 * `crypto-puzzle` on Node throws «Cipher job failed» on a task whose key starts with a zero byte —
 * the open question of the plan («Что нашлось попутно»); the Kotlin solver of the client opens those.
 * The walk asks for another task, and counts how often it had to.
 */
async function solved(task: number[]): Promise<string> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await solve(task);
    } catch (error) {
      puzzleFailures += 1;
      if (attempt >= 20) throw error;
      task = (await request("GET", "/api/mobile/auth/challenge")).body.result.task;
    }
  }
}

async function login(user: string, password = PASSWORD) {
  const challenge = await request("GET", "/api/mobile/auth/challenge");
  if (challenge.status !== 200) return { status: challenge.status, body: challenge.body, token: null as string | null };
  const captchaSolution = await solved(challenge.body.result.task);
  const signedIn = await request("POST", "/api/mobile/auth/login", {
    body: { login: user, password, captchaSolution, device: { name: "walk", platform: "cli" } },
  });
  return { status: signedIn.status, body: signedIn.body, token: signedIn.body?.result?.token ?? null };
}

const call = (token: string, method: string, body: unknown) =>
  request("POST", `/api/mobile/call/${method}`, { token, body });

/** The hook allows 10 logins a minute per IP; a walk from one machine would trip it. */
const forgetAttempts = () => fetch(`${base}/__fake/clear-attempts`, { method: "POST" });

/**
 * The web admin panel of adminizer, over HTTP: sign in as `admin`, create a dish in «Products», and
 * answer its id. The same panel the owner uses, so the dish is made the way they would make it.
 */
async function dishMadeInThePanel(name: string): Promise<string | null> {
  const jar = new Map<string, string>();
  const keep = (response: Response) => {
    for (const line of (response.headers as any).getSetCookie?.() ?? []) {
      const [key, value] = line.split(";")[0].split("=");
      jar.set(key, value);
    }
  };
  const headers = () => ({
    cookie: [...jar].map(([key, value]) => `${key}=${value}`).join("; "),
    "x-xsrf-token": jar.get("XSRF-TOKEN") ?? "",
    "x-csrf-token": jar.get("XSRF-TOKEN") ?? "",
  });
  const pageOf = (html: string) => JSON.parse(/<script data-page="app" type="application\/json">(.*?)<\/script>/s.exec(html)![1]);

  const loginUrl = `${base}/admin/model/User/login?redirectTo=%2Fadmin`;
  const first = await fetch(loginUrl);
  keep(first);
  let captchaSolution: string | null = null;
  let props = pageOf(await first.text()).props;
  for (let attempt = 0; attempt < 20 && captchaSolution === null; attempt += 1) {
    if (attempt > 0) props = pageOf(await (await fetch(loginUrl, { headers: headers() })).text()).props;
    try {
      captchaSolution = await solve(props.captchaTask);
    } catch {
      puzzleFailures += 1;
    }
  }
  const signedIn = await fetch(loginUrl, {
    method: "POST",
    redirect: "manual",
    headers: { ...headers(), "content-type": "application/json", accept: "text/html" },
    body: JSON.stringify({ login: "admin", password: PASSWORD, captchaSolution, redirectTo: "/admin" }),
  });
  keep(signedIn);
  ok(signedIn.status === 302, "the web panel signs the administrator in", signedIn.status);
  if (signedIn.status !== 302) return null;

  const form = await fetch(`${base}/admin/model/dish/add`, { headers: headers() });
  keep(form);
  ok(form.status === 200, "the «Products» add form opens", form.status);
  const created = await fetch(`${base}/admin/model/dish/add`, {
    method: "POST",
    redirect: "manual",
    headers: { ...headers(), "content-type": "application/json", accept: "text/html" },
    body: JSON.stringify({ name, code: `W${Date.now().toString().slice(-5)}`, price: 390, enable: true, parentGroup: "g-hot" }),
  });
  keep(created);
  const id = /\/edit\/([^?]+)/.exec(created.headers.get("location") ?? "")?.[1] ?? null;
  ok(created.status === 302 && Boolean(id), `the panel created «${name}»`, { status: created.status, location: created.headers.get("location") });
  return id;
}

async function main() {
  // The stand keeps what an earlier run left: the walk and the client's RealServerWalkTest both
  // change passwords on purpose at the end.
  for (const user of ["stock", "nopoints", "admin", "operator"]) {
    await fetch(`${base}/__fake/reset-password`, {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ login: user }),
    });
  }
  await fetch(`${base}/__fake/stock-points`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ places: ["p1", "p2"] }),
  });
  await fetch(`${base}/__fake/reset-catalog`, { method: "POST" });

  console.log(`\n== the server itself ==`);
  const about = await request("POST", "/api/mobile/call/server.about", { body: {} });
  ok(about.status === 200, "server.about answers without a token", about.status);
  console.log(`       ${JSON.stringify(about.body?.result)}`);

  console.log(`\n== who gets what ==`);
  await forgetAttempts();
  const tokens: Record<string, string> = {};
  for (const user of ["admin", "stock", "operator", "nopoints", "outsider"]) {
    const signedIn = await login(user);
    if (!signedIn.token) {
      ok(user === "outsider", `${user}: refused (${signedIn.body?.error?.code})`, signedIn.body);
      continue;
    }
    tokens[user] = signedIn.token;
    const declaration = (await request("GET", "/api/mobile/describe?lang=ru", { token: signedIn.token })).body.result;
    const apps = declaration.apps.map((app: any) => app.id);
    const places = [...(declaration.apps[0]?.places ?? [])].sort();
    console.log(`       ${user}: apps [${apps}] places [${places}] revision ${declaration.revision}`);
    if (user === "admin") same(places, ["p1", "p2"], "admin sees both cooking points");
    if (user === "stock") same(places, ["p1", "p2"], "stock sees the two points of its group");
    if (user === "operator") same(apps, [], "operator has no «Блюда»");
    // The token with no points ticked: the app is there, with no point to choose — the client then
    // has nothing to switch and the methods refuse every place (tests/mobile-api.test.ts:246).
    if (user === "nopoints") same([apps, places], [["dishes"], []], "the token with no points ticked: «Блюда» with no points");
  }
  ok(!tokens.outsider, "a user without panel access cannot sign in");

  console.log(`\n== a wrong password, and the same error either way ==`);
  await forgetAttempts();
  const wrong = await login("stock", "nope");
  ok(wrong.body?.error?.code === "invalid_credentials", "wrong password: invalid_credentials", wrong.body?.error);
  const noSuchUser = await login("nobody-at-all");
  ok(noSuchUser.body?.error?.code === "invalid_credentials", "unknown login: the very same code", noSuchUser.body?.error);

  console.log(`\n== a solved puzzle is not reusable ==`);
  await forgetAttempts();
  const challenge = await request("GET", "/api/mobile/auth/challenge");
  const solution = await solved(challenge.body.result.task);
  const body = { login: "stock", password: PASSWORD, captchaSolution: solution, device: { name: "walk", platform: "cli" } };
  ok((await request("POST", "/api/mobile/auth/login", { body })).status === 200, "first use signs in");
  const replay = await request("POST", "/api/mobile/auth/login", { body });
  ok(replay.body?.error?.code === "captcha_invalid", "second use: captcha_invalid", replay.body?.error);

  const token = tokens.stock;
  console.log(`\n== «Блюда» at p1 ==`);
  await fetch(`${base}/__fake/clear-stock`, { method: "POST" });
  const empty = (await call(token, "dishes.list", { params: {}, place: "p1" })).body.result;
  same(empty.items, [], "nothing is limited yet");
  const set = await call(token, "dishes.setBalance", { params: { id: "d1", balance: 7 }, place: "p1" });
  ok(set.status === 200, "setBalance 7 on Борщ", set.body);
  same([set.body.result.item.balance, set.body.result.item.localBalance, set.body.result.item.groupTitle], [7, 7, "Супы"], "the answer carries the updated record");
  const atP1 = (await call(token, "dishes.list", { params: {}, place: "p1" })).body.result;
  same(atP1.items.map((item: any) => item.id), ["d1"], "p1 now lists Борщ");
  const atP2 = (await call(token, "dishes.list", { params: {}, place: "p2" })).body.result;
  same(atP2.items, [], "p2 is untouched — the points do not mix");

  console.log(`\n== the web stock reads the same number ==`);
  // Core's stock functions need its globals, so the stand calls them inside the server process.
  const webStock = async (place: string, id: string) =>
    (await (await fetch(`${base}/__fake/web-stock?place=${place}&id=${id}`)).json()).item;
  const web = await webStock("p1", "d1");
  ok(web?.balance === 7, "getStockProducts of the web panel: balance 7", web);
  const webP2 = await webStock("p2", "d1");
  ok(webP2?.balance === -1, "at p2 Борщ is still unlimited", webP2);
  const row = await (await fetch(`${base}/__fake/dish-place?place=p1&id=d1`)).json();
  ok(row?.row?.localBalance === 7, "the DishPlace row carries localBalance 7", row?.row);

  console.log(`\n== the catalog, the search and the paging ==`);
  const all = (await call(token, "dishes.list", { params: { filter: "all" }, place: "p2" })).body.result;
  same([all.total, all.items.length], [3, 3], "all three dishes, with a total");
  const found = (await call(token, "dishes.list", { params: { filter: "all", q: "мор" }, place: "p1" })).body.result;
  // Finding 2 of the walkthrough: the catalog search goes to Waterline `contains`, which is
  // case-sensitive — the same as the web search of core, and wrong for a phone keyboard.
  same(found.items.map((item: any) => item.id), [], "a lowercase search finds nothing — `contains` is case-sensitive");
  const byCode = (await call(token, "dishes.list", { params: { filter: "all", q: "P1" }, place: "p1" })).body.result;
  same(byCode.items.map((item: any) => item.id), ["d2"], "a search by code finds Плов");
  const foundUpper = (await call(token, "dishes.list", { params: { filter: "all", q: "Мор" }, place: "p1" })).body.result;
  same(foundUpper.items.map((item: any) => item.id), ["d3"], "the same search, capitalised, does find it");
  const strict = await call(token, "dishes.list", { params: { filter: "all", limit: 2 }, place: "p1" });
  ok(strict.body?.error?.code === "invalid", "an unknown param («limit») is refused — the page size is the server's", strict.body?.error);
  const badCursor = await call(token, "dishes.list", { params: { filter: "all", cursor: "not-a-cursor" }, place: "p1" });
  ok(Boolean(badCursor.body?.error), "a cursor the server did not issue is refused", badCursor.body?.error);
  same(all.nextCursor, null, "three dishes need no second page");

  console.log(`\n== the cursor over a catalog of 63 ==`);
  const bulk = await (await fetch(`${base}/__fake/bulk-dishes`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ count: 60 }) })).json();
  ok(bulk.created === 60, "60 more dishes", bulk);
  const page1 = (await call(token, "dishes.list", { params: { filter: "all" }, place: "p1" })).body.result;
  ok(page1.total === 63 && page1.items.length === 50 && Boolean(page1.nextCursor), "the first page is 50 of 63, with a cursor", { total: page1.total, items: page1.items.length });
  const page2 = (await call(token, "dishes.list", { params: { filter: "all", cursor: page1.nextCursor }, place: "p1" })).body.result;
  ok(page2.items.length === 13 && page2.nextCursor === null, "the second page is the remaining 13, and the last", { items: page2.items.length, next: page2.nextCursor });
  const ids = [...page1.items, ...page2.items].map((item: any) => item.id);
  ok(new Set(ids).size === 63, "the two pages are 63 distinct dishes — nothing doubled or lost", new Set(ids).size);
  const counts = page1.counts;
  ok(counts.all === 63 && counts.limited === 1, "the chips count the catalog and the limited ones", counts);
  await fetch(`${base}/__fake/drop-bulk-dishes`, { method: "POST" });

  console.log(`\n== a card, the «В продаже» toggle and the specials ==`);
  const card = (await call(token, "dishes.get", { params: { id: "d1" }, place: "p1" })).body.result;
  ok(card.item.id === "d1" && card.item.placeEnable === true, "the card of Борщ, on sale", card.item);
  ok(Array.isArray(card.item._actions) && card.item._actions.length === 2, "the card carries the two actions the rights allow", card.item._actions);

  const off = await call(token, "dishes.setEnabled", { params: { id: "d1", enable: false }, place: "p1" });
  same(
    [off.body.result.item.placeEnable, off.body.result.item.localBalance, off.body.result.item.balance],
    [false, 7, 0],
    "«В продаже» off: the hand-set 7 stays, the effective stock is a stop",
  );
  const on = await call(token, "dishes.setEnabled", { params: { id: "d1", enable: true }, place: "p1" });
  same([on.body.result.item.placeEnable, on.body.result.item.balance], [true, 7], "back on sale: the 7 is in force again");
  const stopped = await call(token, "dishes.setBalance", { params: { id: "d1", balance: 0 }, place: "p1" });
  ok(stopped.body.result.item.balance === 0, "a stop — balance 0", stopped.body.result?.item);
  const unlimited = await call(token, "dishes.setBalance", { params: { id: "d1", balance: -1 }, place: "p1" });
  ok(unlimited.body.result.item.balance === -1, "«Безлимит» — balance -1", unlimited.body.result?.item);
  const tooLow = await call(token, "dishes.setBalance", { params: { id: "d1", balance: -2 }, place: "p1" });
  ok(tooLow.body?.error?.code === "invalid", "below «Безлимит» the schema refuses", tooLow.body?.error);
  await call(token, "dishes.setBalance", { params: { id: "d1", balance: 7 }, place: "p1" });

  console.log(`\n== what the server refuses ==`);
  const badPlace = await call(token, "dishes.list", { params: {}, place: "p3" });
  ok(badPlace.body?.error?.code === "place_forbidden", "a point outside the group: place_forbidden", badPlace.body?.error);
  const noPlace = await call(token, "dishes.list", { params: {} });
  ok(Boolean(noPlace.body?.error), "no point at all is refused", noPlace.body?.error);
  const badParams = await call(token, "dishes.setBalance", { params: { id: "d1", balance: "семь" }, place: "p1" });
  ok(badParams.body?.error?.code === "invalid", "a string instead of a number: invalid", badParams.body?.error);
  const noSuchDish = await call(token, "dishes.setBalance", { params: { id: "no-such", balance: 1 }, place: "p1" });
  ok(Boolean(noSuchDish.body?.error), "an unknown dish is refused", noSuchDish.body?.error);
  const asOperator = await call(tokens.operator, "dishes.list", { params: {}, place: "p1" });
  ok(asOperator.body?.error?.code === "forbidden", "an Operator calling a dishes method: forbidden", asOperator.body?.error);
  const noToken = await request("POST", "/api/mobile/call/dishes.list", { body: { params: {}, place: "p1" } });
  ok(noToken.status === 401, "without a token: 401", noToken.status);
  const unknownMethod = await call(token, "dishes.nope", { params: {}, place: "p1" });
  ok(Boolean(unknownMethod.body?.error), "an unknown method is refused", unknownMethod.body?.error);

  console.log(`\n== the rights change under the running device ==`);
  const before = await call(token, "dishes.list", { params: {}, place: "p2" });
  await fetch(`${base}/__fake/stock-points`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ places: ["p1"] }) });
  const after = await call(token, "dishes.list", { params: {}, place: "p2" });
  ok(after.body?.error?.code === "place_forbidden", "p2 is gone on the next request", after.body?.error);
  ok(
    before.headers.get("x-mobile-revision") !== after.headers.get("x-mobile-revision"),
    "the revision changed with it",
    [before.headers.get("x-mobile-revision"), after.headers.get("x-mobile-revision")],
  );
  const stillP1 = await call(token, "dishes.list", { params: {}, place: "p1" });
  ok(stillP1.status === 200, "p1 still works", stillP1.status);
  await fetch(`${base}/__fake/stock-points`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ places: ["p1", "p2"] }) });

  console.log(`\n== the login limit ==`);
  await forgetAttempts();
  let limited: any = null;
  for (let attempt = 0; attempt < 12 && !limited; attempt += 1) {
    const refused = await login("stock", "nope");
    if (refused.body?.error?.code === "rate_limited") limited = attempt + 1;
  }
  ok(limited !== null && limited <= 11, `the limit fires (attempt ${limited})`, limited);
  await forgetAttempts();

  console.log(`\n== the password changes, the device falls off ==`);
  await fetch(`${base}/__fake/password`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ login: "nopoints" }) });
  const dropped = await request("GET", "/api/mobile/describe", { token: tokens.nopoints });
  ok(dropped.status === 401, "the old token is 401 after a password change", dropped.status);

  console.log(`\n== the orders of the server ==`);
  const orders = await (await fetch(`${base}/__fake/orders`)).json();
  console.log(`       ${orders.orders.length} order(s): ${orders.orders.map((order: any) => `${order.shortId} ${order.state} ${order.total}₽ @${order.pickupPoint}`).join("; ")}`);
  const ordersApp = await call(tokens.operator, "orders.list", { params: {}, place: "p1" });
  ok(Boolean(ordersApp.body?.error), "the mobile API has no orders app yet (slice 2)", ordersApp.body?.error ?? ordersApp.body);

  console.log(`\n== a dish added in the web panel, seen from the app ==`);
  await forgetAttempts();
  const name = `Харчо ${new Date().toISOString().slice(11, 19)}`;
  const made = await dishMadeInThePanel(name);
  if (made) {
    const search = (await call(token, "dishes.list", { params: { filter: "all", q: name.split(" ")[0] }, place: "p1" })).body.result;
    const row = search.items.find((item: any) => item.id === made);
    ok(Boolean(row), "«Блюда» finds it in the catalog with no restart", search.items.map((item: any) => item.name));
    ok(row?.price === 390, "with the price the panel gave it", row);
    // The group stays empty: `parentGroup` as a plain id is not what the panel's form posts for an
    // association. Finding 4 of the walkthrough — the app shows such a dish with no group heading.
    ok(row?.groupTitle === "", "the association the panel did not take: no group heading", row?.groupTitle);
    const set = await call(token, "dishes.setBalance", { params: { id: made, balance: 3 }, place: "p1" });
    ok(set.body.result?.item?.balance === 3, "and the app can put a limit on it", set.body.result?.item ?? set.body);
    const web = await (await fetch(`${base}/__fake/web-stock?place=p1&id=${made}`)).json();
    ok(web.item?.balance === 3, "which the web stock reads back", web.item);
  }

  console.log(`\ncrypto-puzzle on Node refused ${puzzleFailures} task(s) with «Cipher job failed» along the way`);
  console.log(`${failures ? `${failures} FAILED` : "all green"}\n`);
  process.exit(failures ? 1 : 0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
