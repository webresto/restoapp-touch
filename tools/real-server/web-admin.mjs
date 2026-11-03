/**
 * The web admin panel of adminizer 5.2 on the stand, driven over HTTP: sign in as `admin`, read the
 * «Products» form, create a dish through it, and print what came back. Used to check that a dish the
 * owner adds in the panel is the dish the mobile «Блюда» then shows.
 *
 *   cd ../restoapp-dev && node ../restoapp-mobile-admin/tools/real-server/web-admin.mjs [url] [name]
 *
 * Prints the created id on the last line, so a test can read it.
 */
import path from "path";
import { createRequire } from "module";
import { pathToFileURL } from "url";

const dev = process.cwd();
const require = createRequire(path.join(dev, "index.js"));
const base = process.argv[2] ?? "http://127.0.0.1:1341";
const name = process.argv[3] ?? "Солянка";

const adminizerDir = path.dirname(require.resolve("adminizer"));
const Puzzle = (await import(pathToFileURL(require.resolve("crypto-puzzle", { paths: [adminizerDir] })).href)).default;

const pageOf = (html) => JSON.parse(/<script data-page="app" type="application\/json">(.*?)<\/script>/s.exec(html)[1]);
const cookiesOf = (response) => (response.headers.getSetCookie?.() ?? []).map((line) => line.split(";")[0]).join("; ");

/** A cookie jar: the panel regenerates the session cookie at the login, and XSRF rides along. */
const jar = new Map();
function keep(response) {
  for (const line of response.headers.getSetCookie?.() ?? []) {
    const [name, value] = line.split(";")[0].split("=");
    jar.set(name, value);
  }
}
const cookie = () => [...jar].map(([name, value]) => `${name}=${value}`).join("; ");
const headers = () => ({ cookie: cookie(), "x-xsrf-token": jar.get("XSRF-TOKEN"), "x-csrf-token": jar.get("XSRF-TOKEN") });

const loginUrl = `${base}/admin/model/User/login?redirectTo=%2Fadmin`;
const first = await fetch(loginUrl);
keep(first);

/** crypto-puzzle on Node throws on a task whose key starts with a zero byte: ask for another. */
async function solvedTask(props) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const task = attempt === 0 ? props.captchaTask : pageOf(await (await fetch(loginUrl, { headers: headers() })).text()).props.captchaTask;
    try {
      return await Puzzle.solve(Uint8Array.from(task));
    } catch {
      // another task
    }
  }
  throw new Error("crypto-puzzle never solved a task");
}

const captchaSolution = await solvedTask(pageOf(await first.text()).props);
const signedIn = await fetch(loginUrl, {
  method: "POST",
  redirect: "manual",
  headers: { ...headers(), "content-type": "application/json", accept: "text/html" },
  body: JSON.stringify({ login: "admin", password: "secret-password", captchaSolution, redirectTo: "/admin" }),
});
keep(signedIn);
if (signedIn.status !== 302) throw new Error(`the panel refused the login: ${signedIn.status}`);
console.log(`signed in to the panel as admin (${signedIn.status} -> ${signedIn.headers.get("location")})`);

const formPage = await fetch(`${base}/admin/model/dish/add`, { headers: headers() });
keep(formPage);
if (formPage.status !== 200) throw new Error(`the add form is not open: ${formPage.status} ${formPage.headers.get("location") ?? ""}`);
const form = pageOf(await formPage.text()).props;
const fields = Object.keys(form.fields ?? form.form?.fields ?? {});
console.log(`the «Products» form offers ${fields.length} field(s)${fields.length ? `: ${fields.slice(0, 12).join(", ")}` : ""}`);

const code = `W${Date.now().toString().slice(-5)}`;
const created = await fetch(`${base}/admin/model/dish/add`, {
  method: "POST",
  redirect: "manual",
  headers: { ...headers(), "content-type": "application/json", accept: "text/html" },
  body: JSON.stringify({ name, code, price: 390, enable: true, parentGroup: "g-hot" }),
});
keep(created);
console.log(`create: ${created.status} ${created.headers.get("location") ?? ""}`);
const listPage = await fetch(`${base}/admin/model/dish?search=${encodeURIComponent(name)}`, { headers: headers() });
keep(listPage);
const listed = pageOf(await listPage.text()).props;
const rows = listed.data?.data ?? listed.data ?? [];
const row = (Array.isArray(rows) ? rows : []).find((each) => each.name === name);
const id = row?.id ?? /\/edit\/([^?]+)/.exec(created.headers.get("location") ?? "")?.[1];
console.log(`the panel lists it: ${row ? `${row.id} «${row.name}» ${row.price} code ${row.code}` : `not in the list, but created as ${id}`}`);
console.log(id ?? "");
