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

async function main() {
  const made = await fakes.makeFakes();
  // Small puzzles: the client really solves them, in a fraction of a second.
  made.deps.settings.captchaBaseDifficultyMs = () => Number(process.env.FAKE_POW_MS ?? 40);
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
