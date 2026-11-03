// Generates PoW test tasks with the crypto-puzzle 5 that adminizer (and the app-mobile-api hook) uses.
// Run from ../restoapp-dev: node ../restoapp-mobile-admin/tools/pow-testdata.mjs > out.json
// Picks ordinary tasks plus ones whose key starts with a zero byte, and records whether the JS solver
// itself manages those.
import { createRequire } from "module";
import { pathToFileURL } from "url";
import path from "path";

const require = createRequire(path.join(process.cwd(), "package.json"));
const adminizerDir = path.dirname(require.resolve("adminizer"));
const entry = require.resolve("crypto-puzzle", { paths: [adminizerDir] });
const Puzzle = (await import(pathToFileURL(entry).href)).default;
const pkgDir = path.dirname(path.dirname(entry));
const version = require(path.join(pkgDir, "package.json")).version;
const Archiver = (await import(pathToFileURL(path.join(path.dirname(entry), "archiver.js")).href)).default;
const { sfme } = await import(pathToFileURL(path.join(path.dirname(entry), "utils.js")).href);

function keyLength(task) {
  const [n, a, t, Ck] = Archiver.unarchive(task);
  const b = sfme(a, t, n);
  let k = Ck - b, len = 0;
  while (k) { len++; k >>= 8n; }
  return len;
}

const out = { cryptoPuzzle: version, tasks: [] };
let plain = 0, short = 0;
for (let i = 0; (plain < 4 || short < 2) && i < 5000; i++) {
  const message = `id-${i}:login:127.0.0.1:1700000000:sig-${i}`;
  const duration = i % 4 === 0 ? 30 : 1;
  const task = await Puzzle.generate({ message, duration });
  const len = keyLength(task);
  if (len === 32 && plain >= 4) continue;
  if (len < 32 && short >= 2) continue;
  let jsSolves;
  try { jsSolves = (await Puzzle.solve(task)) === message; } catch { jsSolves = false; }
  out.tasks.push({ message, duration, keyLength: len, jsSolves, task: Array.from(task) });
  if (len === 32) plain++; else short++;
}
console.log(JSON.stringify(out));
