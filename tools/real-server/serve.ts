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
 * p1 and p2), operator (Operator, no «Блюда»), taker (Order Taker: «Клиенты» and the POS on p1 and
 * p2), marketer («Маркетинг» with both its rights and the orders), promoter (the promotions alone),
 * channeler («Каналы продаж», both its rights), channelview (the same section read-only),
 * nopoints (the token with no points ticked), outsider (no panel access). Places: p1
 * «Ленина, 5» (cooking and pickup), p2 «Мира, 3» (cooking), p3 «Выдача» (pickup only, no address).
 * Dishes: d1 Борщ 300, d2 Плов 450, d3 Морс 120.
 *
 * Sales channels: `stand-bot` «Бот стенда», whose provider module is alive here and says it is not
 * set up — switching it on is refused with the provider's own words; and `web-main` «Основной сайт»
 * of the type `web-storefront`, whose provider (`admin-frontend`) is not installed at all, so the
 * card carries the «provider is not installed» notice. /admin/sales-channels-manager shows the same
 * two, which is what «one to one» is compared against.
 *
 * Settings: all 102 of core's manifests are seeded (CORE_LOAD_SETTINGS_MANIFESTS), plus the five of
 * @webresto/graphql as an installed module brings them — JWT_SECRET among them, the one secret.
 * `TZ` is pinned by the environment here, so the stand also shows a setting nobody may edit.
 *
 * Customers to find: Ольга Белова +7 999 000 00 07 (with a saved address, «ул. Мира, 3»), Иван
 * Петров +7 999 000 00 01. A delivery of the POS needs a kitchen and a tariff, so p1 and p2 carry
 * a coordinate, one zone covers them, and `KITCHEN_RESOLVE_CHAIN` is set — nothing of that is
 * seeded on a lifted stand.
 *
 * Control, for the tests — the same paths the fake server answers, plus the orders:
 *   POST /__fake/stock-points {"places": ["p1"]}  regrants the points of the Stock Manager group
 *   POST /__fake/password {"login": "stock"}      changes a password; the devices fall off
 *   POST /__fake/reset-password {"login": "stock"} puts `secret-password` back
 *   POST /__fake/order {"dish": "d1", "amount": 2, "place": "p1"}  places an order, answers its id
 *   POST /__fake/order-without-kitchen {"dish": "d1"}             places a delivery with no kitchen
 *   POST /__fake/order-delivery {"dish": "d1", "place": "p2"}     places a delivery cooked at that kitchen
 *   POST /__fake/move {"id": "...", "state": "COOKING"}           moves it on the orders board
 *   GET  /__fake/orders                           the orders with their state, lines, address and owner
 *   GET  /__fake/customers                        the customers with their id, number and saved addresses
 *   POST /__fake/channel-status {"status": "ready", "enabled": false}  what the stand's channel
 *                                                 provider answers next, and the switch put back
 *   POST /__fake/clear-attempts                   forgets the login attempts (the limit is 10/min/IP)
 *   POST /__fake/clear-stock                      drops every DishPlace row (a clean catalog)
 *   POST /__fake/reset-catalog                    leaves only d1, d2, d3, and no stock at all
 *   POST /__fake/reset-menus                      the site's menus back as seeded (simple-navigation)
 *   POST /__fake/bulk-dishes {"count": 60}        adds that many dishes, for the paging
 *   POST /__fake/drop-bulk-dishes                 removes them again
 *   GET  /__fake/promocode?code=ЛЕТО10            the promo code as the database holds it
 *   GET  /__fake/setting?key=TZ                   the Settings row as the database holds it
 *   POST /__fake/setting {"key": "...", "value": …} writes one, for a test that needs a value back
 *   GET  /__fake/web-stock?place=p1&id=d1         what the web Stock Manager reads there
 *   GET  /__fake/dish-place?place=p1&id=d1        the DishPlace row behind it
 */
import * as fs from "fs";
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
    // The kitchens stand on the map: that is how core's chain finds one for a delivery.
    { id: "p1", title: "Ленина, 5", address: "ул. Ленина, 5", isCookingPoint: true, isPickupPoint: true, coordinate: { lat: 55.75, lon: 37.62 } },
    { id: "p2", title: "Мира, 3", address: "пр. Мира, 3", isCookingPoint: true, isPickupPoint: false, coordinate: { lat: 55.76, lon: 37.64 } },
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
  // `order-kanban` is contextual since core C1: p1 and «Без кухни», never p2.
  const operator = await group("Operator", [
    "access-to-adminpanel",
    { tokenId: "order-kanban", rights: ["p1", "no-kitchen"] },
    "read-order-model",
    "update-order-model",
  ]);
  // «Order Taker» the hook creates itself, with `order-entry` and no points ticked: the stand ticks
  // p1 and p2, so the POS has a terminal to work at and «Клиенты» a search to answer.
  const taker = await group("Order Taker", [
    "access-to-adminpanel",
    { tokenId: "order-entry", rights: ["p1", "p2"] },
    "read-customer-model",
    "create-customer-model",
  ]);
  const nopoints = await group("Stock no points", ["access-to-adminpanel", "stock-manager"]);
  const nobody = await group("Nobody", []);
  // «Маркетинг»: the two tokens of the section are core's own (`lib/adminpanel/manifest.ts`), and the
  // group `Marketer` of a real installation holds both. `marketer` also reads orders, so the summary
  // counts the money; `promoter` has one right of the section and nothing else (marketing.plan.md).
  const marketer = await group("Marketer", ["access-to-adminpanel", "promocodes-manager", "promotions-manager", "read-order-model"]);
  const promoter = await group("Promotions only", ["access-to-adminpanel", "promotions-manager"]);
  // «Каналы продаж» has two levels of right in one screen (sales-channels.plan.md, Q52): the reader
  // sees every channel and no action at all, the manager switches and deletes.
  const channeler = await group("Sales channels", ["access-to-adminpanel", "sales-channels-view", "sales-channels-manage"]);
  const channelview = await group("Sales channels read-only", ["access-to-adminpanel", "sales-channels-view"]);

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
  await user("taker", [taker.id]);
  await user("nopoints", [nopoints.id]);
  await user("outsider", [nobody.id]);
  await user("marketer", [marketer.id]);
  await user("promoter", [promoter.id]);
  await user("channeler", [channeler.id]);
  await user("channelview", [channelview.id]);

  await customers(sails);
  await delivery(sails);
  await settings(sails);
  await menus(sails);
  await marketing(sails);
  await salesChannels(sails);

  return { places, dishes };
}

/**
 * The menus of `simple-navigation`, when that module is installed: the app «Навигация» of the mobile
 * API reads them, and its screen comes from the server as JS (js-module.plan.md). Without the module
 * there is no model and nothing to seed.
 */
async function menus(sails: any) {
  const Navigation = sails.models?.navigation;
  if (!Navigation) return;
  const rows = [
    {
      name: "Шапка",
      mnemonicId: "header",
      navigation_menu: [
        { label: "Меню", link: "/menu", active: true, visible: true },
        { label: "Акции", link: "/stocks", active: true, visible: true },
        { label: "О нас", link: "/static/about", active: true, visible: false },
      ],
    },
    {
      name: "Подвал",
      mnemonicId: "footer",
      navigation_menu: [{ label: "Доставка и оплата", link: "/static/delivery", active: true, visible: true }],
    },
  ];
  for (const row of rows) {
    if (!(await Navigation.findOne({ mnemonicId: row.mnemonicId }))) await Navigation.create(row).fetch();
  }
}

/**
 * «Маркетинг» of the stand: three promotions and three codes, the way an owner would have them —
 * one promotion that applies by itself, one that is «только по коду» (C22), and one switched off
 * whose code therefore gives nothing at all. The orders of the stand give the applications.
 */
async function marketing(sails: any) {
  const Promotion = sails.models.promotion;
  const PromotionCode = sails.models.promotioncode;

  const promotion = async (id: string, name: string, discount: Record<string, unknown>, values: Record<string, unknown>) => {
    await Promotion.createOrUpdate({
      id, name, badge: "configured-promotion", description: `Акция «${name}»`, externalId: id,
      isJoint: true, isPublic: true, createdByUser: true, concept: ["origin"],
      configDiscount: { dishes: [], groups: [], excludeModifiers: true, ...discount },
    });
    await Promotion.update({ id }, { sortOrder: 0, ...values }).fetch();
  };

  await promotion("pr-summer", "Летняя скидка", { discountType: "percentage", discountAmount: 10 }, { enable: true, onlyByCode: true });
  await promotion("pr-lunch", "Бизнес-ланч", { discountType: "flat", discountAmount: 150, minBasketTotal: 700 }, { enable: true });
  await promotion("pr-old", "Прошлогодняя акция", { discountType: "percentage", discountAmount: 5 }, { enable: false });

  const code = async (id: string, value: string, description: string, promotions: string[], values: Record<string, unknown> = {}) => {
    await PromotionCode.create({ id, code: value, description, ...values }).fetch();
    for (const promotion of promotions) await PromotionCode.addToCollection(id, "promotion", promotion);
  };
  await code("code-leto", "ЛЕТО10", "Скидка 10% по коду из рассылки", ["pr-summer"]);
  await code("code-autumn", "ОСЕНЬ", "Откроется к сентябрю", ["pr-lunch"], { startDate: "2099-09-01" });
  await code("code-dead", "СТАРЫЙ", "Код прошлогодней акции", ["pr-old"]);
}

/**
 * What the channel provider of the stand answers. A real provider module is a marketplace module
 * (`admin-frontend`, `sales-channel-telegram`); a lifted stand has none, so the stand plays one —
 * and /__fake/channel-status is how a test makes it say «ready» or «not set up» in turn.
 */
const standProviderStatus = { status: "needs_setup", message: "Не задан токен бота" } as { status: string; message?: string };

/**
 * «Каналы продаж» of the stand: a channel whose provider is alive and not finished, and one whose
 * provider module is not installed at all. Those are the two states the screen exists for — "what
 * is wrong" and "what to do next" (sales-channels.plan.md) — and the web page shows the same.
 */
async function salesChannels(sails: any) {
  const SalesChannel: any = (global as any).SalesChannel;
  // The country of the project: what «Рекомендуем» of the catalog of types goes by.
  await (global as any).Settings.set("COUNTRY_ISO", { value: "RU", type: "string" });
  const adapterModule = require(path.join(dev, "node_modules/@webresto/core/adapters/sales-channel/SalesChannelAdapter"));
  const SalesChannelAdapter = adapterModule.default ?? adapterModule;

  class StandBotProvider extends SalesChannelAdapter {
    constructor() {
      super({
        type: {
          type: "stand-bot",
          title: "Бот стенда",
          category: "messenger",
          providerModule: "sales-channel-stand",
          marketplaceAppId: "sales-channel-stand",
          icon: "send",
          settingsUrl: "/admin/sales-channel-stand/setup",
          capabilities: ["orders:create", "menu:browse", "customers:identify"],
        },
        defaults: { key: "stand-bot", title: "Бот стенда", platforms: ["stand-bot"] },
      });
    }

    async getStatus() {
      return standProviderStatus.message ? { status: standProviderStatus.status, message: standProviderStatus.message } : { status: standProviderStatus.status };
    }

    async onChannelDeleted() {
      /* the stand's provider has nothing of its own to clean up */
    }
  }

  // The module of that provider, as the module manager would have recorded it: without the row core
  // reads the provider as "not installed", and a provider's own channel may then be deleted — on a
  // real server it may not be, and that refusal is one of the things this stand is for.
  const Module = sails.models.module;
  if (Module && !(await Module.findOne({ appId: "sales-channel-stand" }))) {
    await Module.create({ name: "Бот стенда", appId: "sales-channel-stand", hookName: "salesChannelStand", version: "1.0.0", enable: true, isDeleted: false }).fetch();
  }

  // alive() registers the type and creates the provider's channel: disabled, needs_setup.
  await SalesChannel.alive(new StandBotProvider());

  // The website of the stand: its provider module (`admin-frontend`) is not installed here, so core
  // answers «Not working: provider is not installed» and the switch is not even offered.
  if (!(await SalesChannel.findOne({ key: "web-main" }))) {
    await SalesChannel.create({
      key: "web-main", title: "Основной сайт", type: "web-storefront", providerModule: "admin-frontend",
      managedBy: "operator", enabled: false, status: "needs_setup", platforms: ["web", "pwa-ios"],
      countries: ["RU"], url: "https://example.org", sortOrder: 0,
    }).fetch();
  }
}

/**
 * People for «Клиенты» to find, made the way the app makes them — core's own guest factory, so the
 * number lands in `AuthIdentity` and the search by its digits has something to find.
 */
async function customers(sails: any) {
  const core = require(require.resolve("@webresto/core/lib/AuthService", { paths: [dev] }));
  const AuthService = core.default ?? core;

  const olga = (await AuthService.materializeUser({ firstName: "Ольга Белова", phone: { code: "+7", number: "9990000007" } })).user;
  await AuthService.materializeUser({ firstName: "Иван Петров", phone: { code: "+7", number: "9990000001" } });

  // A saved address with the house and the flat on it: the POS takes it by its id, and core refuses
  // a delivery whose address has no house number. The coordinate spares the stand a geocoder.
  await sails.models.userlocation.create({
    user: String(olga.id), formatted: "ул. Мира, 3", home: "3", apartment: "7", entrance: "2", comment: "код 1234",
    coordinate: { lat: 55.752, lon: 37.615 },
  }).fetch();
}

/**
 * What a delivery needs and a lifted stand has not: the chain that names a kitchen (no settings are
 * seeded here, so it is empty and core answers NO_KITCHEN) and one zone to price the address
 * (without a zone there is no tariff at all, and that is refused even with soft calculation).
 */
async function delivery(sails: any) {
  const Settings: any = (global as any).Settings;
  await Settings.set("KITCHEN_RESOLVE_CHAIN", {
    value: ["nearest-geo", "single-point"],
    type: "json",
    jsonSchema: { type: "array", items: { type: "string", enum: ["delivery-zone", "rms", "nearest-geo", "single-point"] }, uniqueItems: true },
  });
  // Points are [lon, lat]; the square holds both kitchens and the saved address.
  await sails.models.deliveryzone.create({
    id: "z1", name: "Центр", enable: true, sortOrder: 0, minDeliveryTime: 60, deliveryCost: 150,
    polygon: [[37.58, 55.72], [37.68, 55.72], [37.68, 55.78], [37.58, 55.78]],
  }).fetch();
}

/**
 * The settings of a module, as an installed module brings them: @webresto/graphql declares five of
 * its own in `settings/*.json`, and the module manager seeds them with `module` filled in. The hook
 * of graphql is off on the stand (its schema build is not needed here), so nothing would seed them
 * — and JWT_SECRET is the one secret setting a real server has, which «Настройки» must never show.
 *
 * Registering the directory with core is what the module's own `initialize` does: it is where the
 * human wording of a `pattern` rule lives, as the stored row has no column for it.
 */
async function settings(sails: any) {
  const Settings: any = (global as any).Settings;
  const graphqlDir = path.dirname(require.resolve("@webresto/graphql/package.json", { paths: [dev] }));
  const manifestDir = path.join(graphqlDir, "settings");
  require(require.resolve("@webresto/core/lib/settings/schema", { paths: [dev] })).addSettingsManifestDir(manifestDir);

  for (const file of fs.readdirSync(manifestDir)) {
    if (!file.endsWith(".json")) continue;
    const manifest = JSON.parse(fs.readFileSync(path.join(manifestDir, file), "utf8"));
    await Settings.set(manifest.key, {
      key: manifest.key,
      type: manifest.type,
      name: manifest.name,
      description: manifest.description,
      jsonSchema: manifest.jsonSchema,
      isRequired: manifest.isRequired,
      secret: manifest.secret,
      readOnly: manifest.readOnly,
      restartRequired: manifest.restartRequired,
      // `Settings.set` takes the owning module as `appId` and stores it in `module`.
      appId: "graphql",
    });
  }
  // A secret that is set: the editor then says "stored and not shown" rather than "not set yet".
  await Settings.set("JWT_SECRET", { value: "stand-jwt-secret-0123456789abcdef0123" });
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

/**
 * A placed delivery at [kitchen] — or, with none, one no kitchen was resolved for, what a chain with
 * soft calculation lets through for an operator to decide. Written as such, because the checkout of
 * this stand has no delivery adapter to resolve an address with and would refuse the order.
 */
async function placeDelivery(dish: string, amount: number, kitchen: string | null): Promise<string> {
  const Order: any = (global as any).Order;
  const order = await Order.create({}).fetch();
  await Order.addDish({ id: order.id }, dish, amount, [], "", "user");
  await Order.update({ id: order.id }, {
    state: "ORDER",
    serviceType: "delivery",
    cookingPoints: kitchen ? [kitchen] : [],
    orderedAt: Math.floor(Date.now() / 1000),
    customer: { name: "Гость", phone: { code: "+7", number: "9990000002", additionalNumber: "" } },
    address: { formatted: "ул. Садовая, 1", home: "1" },
    total: 100 * amount,
  }).fetch();
  // Not through `Order.order`, so the stops a board selects by are written by hand (core C2).
  const { syncOrderStops } = require(path.join(dev, "node_modules/@webresto/core/lib/order/order-stops"));
  await syncOrderStops({ id: order.id });
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
    void move({ body: { id, nextState }, user, query: {}, adminizer: { config: {} } }, res);
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
    // Core seeds its 102 settings from settings/*.json only when asked (hook/afterHook.ts): a
    // legacy install gets them through the module manager instead. The stand asks, so «Настройки»
    // has the list a real server has.
    CORE_LOAD_SETTINGS_MANIFESTS: "true",
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
        if (req.url === "/__fake/order-without-kitchen") {
          return answer({ id: await placeDelivery(data.dish ?? "d1", data.amount ?? 1, null) });
        }
        if (req.url === "/__fake/order-delivery") {
          return answer({ id: await placeDelivery(data.dish ?? "d1", data.amount ?? 1, data.place ?? "p1") });
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
        if (req.url === "/__fake/reset-menus") {
          // The menus of simple-navigation, as they were seeded: the screenshots of «Навигация» are
          // then the same every run, however the last one dragged and saved them.
          if (sails.models.navigation) {
            await sails.models.navigation.destroy({}).fetch();
            await menus(sails);
          }
          return answer({ ok: true });
        }
        if (req.url === "/__fake/clear-stock") {
          // Every point's own stock, back to «no row» — the walk starts from a clean catalog.
          const gone = await sails.models.dishplace.destroy({}).fetch();
          return answer({ destroyed: gone.length });
        }
        if (req.url === "/__fake/channel-status") {
          // What the stand's channel provider says from now on: «ready» lets the channel be
          // switched on, anything else is the refusal a person reads.
          standProviderStatus.status = data.status ?? "needs_setup";
          standProviderStatus.message = data.message === undefined ? standProviderStatus.message : data.message;
          // `enabled` puts the operator's switch back where a walk expects to find it: a channel
          // already on cannot be refused being switched on.
          if (data.enabled !== undefined) {
            await (global as any).SalesChannel.update({ type: "stand-bot" }, { enabled: Boolean(data.enabled) }).fetch();
          }
          // The provider pushes its news the way a real one does, so nothing stale is read.
          const refreshed = await (global as any).SalesChannel.refreshStatus("stand-bot");
          return answer({ ok: true, status: standProviderStatus.status, channels: refreshed });
        }
        if (req.url === "/__fake/clear-attempts") {
          // The login limit is 10 a minute per IP; a walkthrough from one machine trips it at once.
          await sails.models.mobileauthattempt.destroy({}).fetch();
          return answer({ ok: true });
        }
        if (req.url?.startsWith("/__fake/setting")) {
          // One Settings row as the database holds it — what a test reads back after a write from the
          // app — and a way to put a value there without going through the app at all.
          const query = new URL(req.url, "http://stand").searchParams;
          const Settings: any = (global as any).Settings;
          if (req.method === "GET") {
            return answer({ row: (await sails.models.settings.findOne({ key: query.get("key") })) ?? null });
          }
          await Settings.set(data.key, { value: data.value });
          return answer({ row: await sails.models.settings.findOne({ key: data.key }) });
        }
        if (req.url?.startsWith("/__fake/web-stock")) {
          // What the web Stock Manager of the admin panel reads for that point — core's own function.
          const query = new URL(req.url, "http://stand").searchParams;
          const { getStockProducts } = require(path.join(dev, "node_modules/@webresto/core/lib/menu/stock-items"));
          const { results, mode } = await getStockProducts(query.get("place"), { id: query.get("id") }, 1);
          return answer({ mode, item: results[0] ?? null });
        }
        if (req.url?.startsWith("/__fake/promocode")) {
          // The code as the database holds it, with its promotions: what the phone's switch is checked against.
          const query = new URL(req.url, "http://stand").searchParams;
          const code = await sails.models.promotioncode.findOne({ code: query.get("code") }).populate("promotion");
          return answer({
            code: code
              ? {
                  id: code.id,
                  code: code.code,
                  enable: code.enable !== false,
                  promotions: (code.promotion ?? []).map((promotion: any) => ({ id: promotion.id, enable: promotion.enable !== false, onlyByCode: promotion.onlyByCode === true })),
                }
              : null,
          });
        }
        if (req.url?.startsWith("/__fake/dish-place")) {
          const query = new URL(req.url, "http://stand").searchParams;
          return answer({ row: await sails.models.dishplace.findOne({ dish: query.get("id"), place: query.get("place") }) ?? null });
        }
        if (req.url?.startsWith("/__fake/customers")) {
          const people = await sails.models.user.find({ where: { isDeleted: false }, sort: "createdAt ASC" });
          const out = [];
          for (const person of people) {
            const locations = await sails.models.userlocation.find({ user: String(person.id) });
            const identities = await sails.models.authidentity.find({ user: String(person.id), provider: "phone" });
            out.push({
              id: String(person.id),
              name: [person.firstName, person.lastName].filter(Boolean).join(" ").trim(),
              phone: identities[0]?.externalId ?? null,
              addresses: locations.map((location: any) => ({ id: String(location.id), formatted: location.formatted })),
            });
          }
          return answer({ customers: out });
        }

        if (req.url?.startsWith("/__fake/orders")) {
          const orders = await sails.models.order.find({ where: { state: { "!=": "CART" } }, sort: "createdAt ASC" });
          const out = [];
          for (const order of orders) {
            const lines = await sails.models.orderdish.find({ order: order.id }).populate("dish");
            const { orderPlaceKey } = require(path.join(dev, "node_modules/@webresto/core/lib/order/orders-of-place"));
            out.push({
              id: order.id,
              shortId: order.shortId,
              state: order.state,
              pickupPoint: order.pickupPoint,
              cookingPoints: order.cookingPoints ?? [],
              /** The point the board and the phone lead it by, or `no-kitchen`. */
              placeId: orderPlaceKey(order),
              total: order.total ?? order.basketTotal,
              serviceType: order.serviceType,
              /** Whose order it is, when an account was picked or made for it. */
              user: order.user ? String(order.user) : null,
              address: order.address ?? null,
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
