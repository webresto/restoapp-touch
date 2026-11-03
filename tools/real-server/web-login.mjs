import fs from "fs";
import path from "path";
import { createRequire } from "module";
import { pathToFileURL } from "url";

const dev = "/Users/hanna/prj/restoapp-dev";
const require = createRequire(path.join(dev, "index.js"));
const base = process.argv[2] ?? "http://127.0.0.1:1341";

const adminizerDir = path.dirname(require.resolve("adminizer"));
const entry = require.resolve("crypto-puzzle", { paths: [adminizerDir] });
const Puzzle = (await import(pathToFileURL(entry).href)).default;

function pageOf(html) {
  const match = /<script data-page="app" type="application\/json">(.*?)<\/script>/s.exec(html);
  return JSON.parse(match[1]);
}
const cookiesOf = (response) =>
  (response.headers.getSetCookie?.() ?? []).map((line) => line.split(";")[0]).join("; ");

const loginUrl = `${base}/admin/model/User/login?redirectTo=%2Fadmin`;
const first = await fetch(loginUrl);
const cookies = cookiesOf(first);
const xsrf = /XSRF-TOKEN=([^;]+)/.exec(cookies)?.[1];
const props = pageOf(await first.text()).props;
console.log(`login page: adminizer props, captchaTask ${props.captchaTask.length} bytes, auth.user ${JSON.stringify(props.auth.user)}`);

let solution = null;
for (let attempt = 0; attempt < 20 && solution === null; attempt += 1) {
  const page = attempt === 0 ? props : pageOf(await (await fetch(loginUrl, { headers: { cookie: cookies } })).text()).props;
  try {
    solution = await Puzzle.solve(Uint8Array.from(page.captchaTask));
  } catch (error) {
    console.log(`  crypto-puzzle refused a task: ${error.message ?? error}`);
  }
}
if (solution === null) throw new Error("crypto-puzzle never solved a task");

const answer = await fetch(loginUrl, {
  method: "POST",
  redirect: "manual",
  headers: {
    "content-type": "application/json",
    accept: "text/html, application/xhtml+xml",
    cookie: cookies,
    "x-xsrf-token": xsrf,
    "x-csrf-token": xsrf,
  },
  body: JSON.stringify({ login: process.argv[3] ?? "admin", password: "secret-password", captchaSolution: solution, redirectTo: "/admin" }),
});
console.log(`login POST: ${answer.status} ${answer.headers.get("location") ?? ""}`);
const after = cookiesOf(answer) || cookies;
const home = await fetch(`${base}/admin`, { headers: { cookie: after } });
const text = await home.text();
console.log(`/admin after the login: ${home.status}, ${text.length} bytes`);
if (text.includes("data-page")) {
  const page = pageOf(text);
  const user = page.props.auth?.user;
  console.log(`auth.user keys: ${user ? Object.keys(user).join(", ") : "null"}`);
  for (const secret of ["passwordHashed", "apiKey", "password"]) {
    console.log(`  ${secret} in the page: ${text.includes(secret)}`);
  }
  fs.writeFileSync("/tmp/admin-home-props.json", JSON.stringify(page.props, null, 1));
} else {
  console.log(text.slice(0, 300));
}
