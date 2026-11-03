/**
 * The whole restoapp backend on sails-disk, for walking the client through a real server: a lifted
 * Sails with the admin panel of adminizer 5.2, the app-mobile-api hook, real core dishes, places and
 * orders — no fakes anywhere. The counterpart of ../fake-server/serve.ts, which runs the hook's
 * Express application alone on the fake deps of its tests.
 *
 *   cd ../restoapp-dev && npx tsx ../restoapp-mobile-admin/tools/real-server/serve.ts [port]
 *
 * Prints the fixtures and `listening <url>`, then serves until killed. The admin panel is at
 * /admin, the mobile API at /api/mobile.
 *
 * Users, all with the password `secret-password`: admin (administrator), stock (Stock Manager on
 * p1 and p2), operator (Operator, no «Блюда»), nopoints (the token with no points ticked),
 * outsider (no panel access). Places: p1 «Ленина, 5» (cooking and pickup), p2 «Мира, 3» (cooking),
 * p3 «Выдача» (pickup only, no address). Dishes: d1 Борщ 300, d2 Плов 450, d3 Морс 120.
 *
 * Control, for the tests — the same paths the fake server answers, plus the orders:
 *   POST /__fake/stock-points {"places": ["p1"]}  regrants the points of the Stock Manager group
 *   POST /__fake/password {"login": "stock"}      changes a password; the devices fall off
 *   POST /__fake/reset-password {"login": "stock"} puts `secret-password` back
 *   POST /__fake/order {"dish": "d1", "amount": 2, "place": "p1"}  places an order, answers its id
 *   POST /__fake/move {"id": "...", "state": "COOKING"}           moves it on the orders board
 *   GET  /__fake/orders                           the orders with their state and lines
 *   POST /__fake/clear-attempts                   forgets the login attempts (the limit is 10/min/IP)
 *   POST /__fake/clear-stock                      drops every DishPlace row (a clean catalog)
 *   POST /__fake/reset-catalog                    leaves only d1, d2, d3, and no stock at all
 *   POST /__fake/bulk-dishes {"count": 60}        adds that many dishes, for the paging
 *   POST /__fake/drop-bulk-dishes                 removes them again
 *   GET  /__fake/web-stock?place=p1&id=d1         what the web Stock Manager reads there
 *   GET  /__fake/dish-place?place=p1&id=d1        the DishPlace row behind it
 */
import * as http from "http";
import * as path from "path";

const dev = process.cwd();
const SALT = "stand-salt";
const PASSWORD = "secret-password";

// eslint-disable-next-line @typescript-eslint/no-var-requires
const passwordHash = require(require.resolve("password-hash", { paths: [dev] }));

async function fixtures(sails: any) {
  const { place: Place, dish: Dish, group: Group, groupap: GroupAp, userap: User } = sails.models;

  const places = [
    { id: "p1", title: "Ленина, 5", address: "ул. Ленина, 5", isCookingPoint: true, isPickupPoint: true },
    { id: "p2", title: "Мира, 3", address: "пр. Мира, 3", isCookingPoint: true, isPickupPoint: false },
    { id: "p3", title: "Выдача", address: "", isCookingPoint: false, isPickupPoint: true },
  ];
  for (const place of places) await Place.create({ ...place, enable: true }).fetch();

  const groups = {
    soups: await Group.create({ id: "g-soups", name: "Супы" }).fetch(),
    hot: await Group.create({ id: "g-hot", name: "Горячее" }).fetch(),
    drinks: await Group.create({ id: "g-drinks", name: "Напитки" }).fetch(),
  };
  const dishes = [
    { id: "d1", name: "Борщ", code: "B1", price: 300, parentGroup: groups.soups.id },
    { id: "d2", name: "Плов", code: "P1", price: 450, parentGroup: groups.hot.id },
    { id: "d3", name: "Морс", code: "M1", price: 120, parentGroup: groups.drinks.id },
  ];
  for (const dish of dishes) await Dish.create({ ...dish, enable: true }).fetch();

  // «Stock Manager» the hook creates itself, «Operator» core's default groups do: upsert by name.
  const group = async (name: string, tokens: unknown[]) =>
    (await GroupAp.findOne({ name })) ? GroupAp.updateOne({ name }, { tokens }) : GroupAp.create({ name, tokens }).fetch();
  const stock = await group("Stock Manager", ["access-to-adminpanel", { tokenId: "stock-manager", rights: ["p1", "p2"] }]);
  const operator = await group("Operator", ["access-to-adminpanel", "order-kanban", "read-order-model"]);
  const nopoints = await group("Stock no points", ["access-to-adminpanel", "stock-manager"]);
  const nobody = await group("Nobody", []);

  const user = async (login: string, groupIds: string[], extra: Record<string, unknown> = {}) =>
    User.create({
      login,
      fullName: login,
      passwordHashed: passwordHash.generate(login + PASSWORD + SALT),
      isActive: true,
      isConfirmed: true,
      isAdministrator: false,
      groups: groupIds,
      ...extra,
    }).fetch();

  await user("admin", [], { isAdministrator: true });
  await user("stock", [stock.id]);
  await user("operator", [operator.id]);
  await user("nopoints", [nopoints.id]);
  await user("outsider", [nobody.id]);

  return { places, dishes };
}

/** A placed order, the way the storefront's resolvers make one (core test/places/support/storefront.ts). */
async function placeOrder(dish: string, amount: number, place: string): Promise<string> {
  const Order: any = (global as any).Order;
  const order = await Order.create({}).fetch();
  await Order.addDish({ id: order.id }, dish, amount, [], "", "user");
  await Order.update({ id: order.id }, { serviceType: "pickup", pickupPoint: place }).fetch();
  await Order.next({ id: order.id }, "CART");
  await Order.countCart({ id: order.id });
  await Order.check({ id: order.id }, { name: "Гость", phone: { code: "+7", number: "9990000001" } }, "pickup");
  await Order.order({ id: order.id });
  return order.id;
}

/** The move of the admin panel's orders board, as its controller does it. */
async function moveOrder(id: string, nextState: string, user: object): Promise<{ status: number; body: any }> {
  const controller = require(path.join(dev, "node_modules/@webresto/core/lib/adminpanel/src/controller/update-order-kanban-state"));
  const move = controller.default ?? controller;
  return new Promise((resolve) => {
    let status = 200;
    const res: any = {
      status(code: number) { status = code; return res; },
      json(body: any) { resolve({ status, body }); return res; },
      sendStatus(code: number) { resolve({ status: code, body: null }); return res; },
      redirect() { resolve({ status: 302, body: null }); return res; },
    };
    void move({ body: { id, nextState }, user, adminizer: { config: {} } }, res);
  });
}

async function main() {
  // Run under `npx tsx`: its ESM loader is what resolves adminizer's directory imports.
  const rc = require(require.resolve("rc", { paths: [dev] }));
  const SailsFactory = require(require.resolve("sails", { paths: [dev] })).Sails;

  for (const [key, value] of Object.entries({
    NODE_ENV: "development",
    // Without a timezone core refuses to place orders at all (models/Order.ts, code 19).
    TZ: "Europe/Moscow",
    ADMINPANEL_LAZY_GEN_ADMIN_DISABLE: "1",
    WEBRESTO_MODULES_PATH: path.join(dev, "modules"),
    MM_SETTINGS_MODEL_DENY: "1",
    MM_SYSTEM_MODULES: path.join(dev, "node_modules/@webresto/core"),
    AP_PASSWORD_SALT: SALT,
    MOBILE_API_ENABLED: "true",
    MOBILE_API_SECRET: "stand-secret",
    ENABLE_ADMIN_CAPTCHA: "true",
    CAPTCHA_POW_DIFFICULTY: process.env.CAPTCHA_POW_DIFFICULTY ?? "20",
  })) if (!process.env[key]) process.env[key] = value;

  const config = rc("sails");
  config.environment = "development";
  config.port = 42000 + Math.floor(Math.random() * 2000);
  config.log = { ...(config.log || {}), level: process.env.STAND_LOG_LEVEL ?? "error" };
  config.hooks = {
    ...(config.hooks || {}),
    grunt: false,
    // @webresto/graphql halts the whole process when its schema fails to build; the stand needs none.
    restographql: false,
    "app-manager-proto": require(path.join(dev, "api/hooks/app-manager-proto")),
    "app-mobile-api": require(path.join(dev, "api/hooks/app-mobile-api")),
  };
  const dir = path.join(require("os").tmpdir(), `restoapp-real-${process.pid}`);
  config.datastores = { default: { adapter: "sails-disk", dir } };

  const sails: any = await new Promise((resolve, reject) => {
    SailsFactory().lift(config, (error: Error, lifted: any) => (error ? reject(error) : resolve(lifted)));
  });
  await new Promise<void>((resolve) => {
    if (sails.hooks.adminpanel?.adminizer?.accessRightsHelper?.getTokens().length) return resolve();
    sails.on("Adminpanel:loaded", () => setImmediate(resolve));
  });

  const made = await fixtures(sails);

  const control = (req: http.IncomingMessage, res: http.ServerResponse) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", async () => {
      const data = body ? JSON.parse(body) : {};
      const answer = (value: unknown, status = 200) => {
        res.statusCode = status;
        res.setHeader("content-type", "application/json");
        res.end(JSON.stringify(value));
      };
      try {
        if (req.url === "/__fake/stock-points") {
          await sails.models.groupap.updateOne({ name: "Stock Manager" }, {
            tokens: ["access-to-adminpanel", { tokenId: "stock-manager", rights: data.places }],
          });
          return answer({ ok: true });
        }
        if (req.url === "/__fake/password") {
          await sails.models.userap.updateOne({ login: data.login }, {
            passwordHashed: passwordHash.generate(data.login + "changed" + SALT),
          });
          return answer({ ok: true });
        }
        if (req.url === "/__fake/order") {
          return answer({ id: await placeOrder(data.dish ?? "d1", data.amount ?? 1, data.place ?? "p1") });
        }
        if (req.url === "/__fake/move") {
          const user = data.login === "operator"
            ? { id: (await sails.models.userap.findOne({ login: "operator" })).id, isAdministrator: false, groups: [{ name: "Operator" }] }
            : { id: (await sails.models.userap.findOne({ login: "admin" })).id, isAdministrator: true };
          return answer(await moveOrder(data.id, data.state, user));
        }
        if (req.url === "/__fake/bulk-dishes") {
          // A catalog big enough for the 50-a-page cursor of «Блюда».
          const made = [];
          for (let index = 1; index <= (data.count ?? 60); index += 1) {
            const id = `bulk-${String(index).padStart(3, "0")}`;
            if (!(await sails.models.dish.findOne({ id }))) {
              await sails.models.dish.create({ id, name: `Позиция ${index}`, code: `X${index}`, price: 100 + index, enable: true, parentGroup: "g-hot" }).fetch();
            }
            made.push(id);
          }
          return answer({ created: made.length });
        }
        if (req.url === "/__fake/drop-bulk-dishes") {
          const gone = await sails.models.dish.destroy({ id: { startsWith: "bulk-" } }).fetch();
          return answer({ destroyed: gone.length });
        }
        if (req.url === "/__fake/reset-password") {
          // Back to `secret-password`, after a test used /__fake/password to drop the devices.
          await sails.models.userap.updateOne({ login: data.login }, {
            passwordHashed: passwordHash.generate(data.login + PASSWORD + SALT),
          });
          return answer({ ok: true });
        }
        if (req.url === "/__fake/reset-catalog") {
          // Only d1, d2, d3 again: the walk adds dishes in bulk and through the web panel.
          const gone = await sails.models.dish.destroy({ id: { nin: ["d1", "d2", "d3"] } }).fetch();
          await sails.models.dishplace.destroy({}).fetch();
          return answer({ destroyed: gone.length });
        }
        if (req.url === "/__fake/clear-stock") {
          // Every point's own stock, back to «no row» — the walk starts from a clean catalog.
          const gone = await sails.models.dishplace.destroy({}).fetch();
          return answer({ destroyed: gone.length });
        }
        if (req.url === "/__fake/clear-attempts") {
          // The login limit is 10 a minute per IP; a walkthrough from one machine trips it at once.
          await sails.models.mobileauthattempt.destroy({}).fetch();
          return answer({ ok: true });
        }
        if (req.url?.startsWith("/__fake/web-stock")) {
          // What the web Stock Manager of the admin panel reads for that point — core's own function.
          const query = new URL(req.url, "http://stand").searchParams;
          const { getStockProducts } = require(path.join(dev, "node_modules/@webresto/core/lib/adminpanel/src/controller/stock-place-items"));
          const { results, mode } = await getStockProducts(query.get("place"), { id: query.get("id") }, 1);
          return answer({ mode, item: results[0] ?? null });
        }
        if (req.url?.startsWith("/__fake/dish-place")) {
          const query = new URL(req.url, "http://stand").searchParams;
          return answer({ row: await sails.models.dishplace.findOne({ dish: query.get("id"), place: query.get("place") }) ?? null });
        }
        if (req.url?.startsWith("/__fake/orders")) {
          const orders = await sails.models.order.find({ where: { state: { "!=": "CART" } }, sort: "createdAt ASC" });
          const out = [];
          for (const order of orders) {
            const lines = await sails.models.orderdish.find({ order: order.id }).populate("dish");
            out.push({
              id: order.id,
              shortId: order.shortId,
              state: order.state,
              pickupPoint: order.pickupPoint,
              total: order.total ?? order.basketTotal,
              lines: lines.map((line: any) => ({ dish: line.dish?.name, amount: line.amount })),
            });
          }
          return answer({ orders: out });
        }
        answer({ error: "unknown control path" }, 404);
      } catch (error: any) {
        console.error("control", req.url, error);
        const details = error?.details ?? error?.raw?.details ?? error?.cause?.details;
        answer({ error: error?.message ?? String(error), code: error?.code, details, stack: error?.stack?.split("\n").slice(0, 6) }, 500);
      }
    });
  };

  const app = sails.hooks.http.app;
  const server = http.createServer((req, res) => (req.url?.startsWith("/__fake/") ? control(req, res) : app(req, res)));
  const port = Number(process.argv[2] ?? 0);
  server.listen(port, "127.0.0.1", () => {
    const address = server.address() as { port: number };
    console.log(`adminizer ${require(path.join(dev, "node_modules/adminizer/package.json")).version}`);
    console.log(`datastore sails-disk ${dir}`);
    console.log(`places ${made.places.map((p) => p.id).join(" ")}; dishes ${made.dishes.map((d) => d.id).join(" ")}`);
    console.log(`listening http://127.0.0.1:${address.port}`);
  });
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
