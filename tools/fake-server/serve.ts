/**
 * The app-mobile-api hook of ../restoapp-dev on the fake deps of its own tests — no Sails, no
 * database: the real Express application, the real PoW CAPTCHA, adminizer's real AccessRightsHelper.
 * For developing the client against the protocol, and for its live test (HttpMobileApiLiveTest).
 *
 *   cd ../restoapp-dev && npx tsx ../restoapp-mobile-admin/tools/fake-server/serve.ts [port]
 *
 * Prints `listening <url>` and serves until killed. Users: admin, stock (points p1 and p2), operator
 * (no «Блюда»), nopoints, outsider — all with the password `secret-password`. Points: «Ленина, 5»
 * (p1), «Мира, 3» (p2), «Выдача» (p3, a pickup point, never a kitchen).
 *
 * Control, for the tests: POST /__fake/stock-points {"places": ["p1"]} regrants the points of
 * `stock`; POST /__fake/password {"login": "stock"} changes a password (devices fall off).
 *
 * «Навигация» is here too when the module is installed (D12): the app a module brings as JS, so the
 * client can be walked through downloading a script. The registration is the module's own code —
 * `modules/simple-navigation/src/mobile/NavigationMobileApp` — over menus in memory instead of
 * Waterline, and the bundle served by its hash is the module's `assets/mobile/navigation.js`. Only
 * the administrator holds its right here, and the modules of the server are answered as a real one
 * does.
 */
import * as http from "http";
import * as path from "path";

const dev = process.cwd();
// eslint-disable-next-line @typescript-eslint/no-var-requires
const fakes = require(path.join(dev, "tests/support/mobileApiFakes"));
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { createMobileApi } = require(path.join(dev, "api/hooks/app-mobile-api/http/app"));
// eslint-disable-next-line @typescript-eslint/no-var-requires
const passwordHash = require(require.resolve("password-hash", { paths: [dev] }));

/** The menus of simple-navigation, in memory: enough for the client to read, save and reorder. */
function memoryNavigation() {
  const rows = new Map<string, { name: string; mnemonicId: string; items: any[] }>([
    ["m1", { name: "Шапка", mnemonicId: "header", items: [{ label: "Меню", link: "/menu", visible: true }] }],
    ["m2", { name: "Подвал", mnemonicId: "footer", items: [] }],
  ]);
  const view = (id: string, row: any) => ({ id, name: row.name, mnemonicId: row.mnemonicId, count: row.items.length });
  return {
    list: async () => [...rows].map(([id, row]) => view(id, row)),
    links: async () => [
      { link: "/menu", title: "Menu", kind: "site" },
      { link: "/stocks", title: "Stocks", kind: "site" },
    ],
    get: async (id: string) => {
      const row = rows.get(id);
      return row ? { ...view(id, row), items: row.items } : null;
    },
    create: async (name: string, mnemonicId: string) => {
      const id = `m${rows.size + 1}`;
      rows.set(id, { name, mnemonicId, items: [] });
      return view(id, rows.get(id));
    },
    save: async (id: string, name: string, items: any[]) => {
      const row = rows.get(id);
      if (!row) return null;
      row.name = name;
      row.items = items;
      return view(id, row);
    },
    remove: async (id: string) => rows.delete(id),
  };
}

/** The app of the module, when the module is installed: its own registration code, over the menus above. */
function withNavigation(made: any) {
  const module = path.join(dev, "modules/simple-navigation");
  let app: any;
  try {
    app = require(path.join(module, "src/mobile/NavigationMobileApp"));
  } catch {
    console.log("no simple-navigation in this server — serving without «Навигация»");
    return;
  }
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const { Translator } = require(path.join(dev, "api/hooks/app-mobile-api/lib/i18n"));
  const translator = new Translator();
  for (const [lang, strings] of Object.entries(app.translations)) translator.extend(lang, strings);
  made.deps.translator = translator;
  made.deps.modules = {
    list: async () => [
      { id: "@webresto/core", version: "2.0.0", kind: "system", title: "RestoApp core" },
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      { id: "simple-navigation", version: require(path.join(module, "package.json")).version, kind: "installed", title: "simple-navigation" },
    ],
  };
  made.deps.access.registerToken({ id: "navigation-manager", name: "Navigation", description: "The site's menus", department: "Marketing" });
  app.registerNavigationApp(made.deps.registry, memoryNavigation(), app.navigationScriptFile());
}

async function main() {
  const made = await fakes.makeFakes();
  // Small puzzles: the client really solves them, in a fraction of a second.
  made.deps.settings.captchaBaseDifficultyMs = () => Number(process.env.FAKE_POW_MS ?? 40);
  withNavigation(made);
  const app = createMobileApi(made.deps);

  const control = (req: http.IncomingMessage, res: http.ServerResponse) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      const data = body ? JSON.parse(body) : {};
      if (req.url === "/__fake/stock-points") {
        const user = made.users.get("stock");
        user.groups = [{ name: "Stock Manager", tokens: ["access-to-adminpanel", { tokenId: "stock-manager", rights: data.places }] }];
      } else if (req.url === "/__fake/password") {
        const user = made.users.get(data.login);
        user.passwordHashed = passwordHash.generate(data.login + "changed" + fakes.SALT);
      }
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true }));
    });
  };

  const server = http.createServer((req, res) => (req.url?.startsWith("/__fake/") ? control(req, res) : app(req, res)));
  const port = Number(process.argv[2] ?? 0);
  server.listen(port, "127.0.0.1", () => {
    const address = server.address() as { port: number };
    console.log(`listening http://127.0.0.1:${address.port}`);
  });
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
