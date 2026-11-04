import * as path from "path";

/**
 * A module made only for mocks and tests. A real module lives in another repository and is not on a
 * clean checkout, while what the client has to prove about an app a module brings has nothing to do
 * with what that module is about: the declaration links its code, the code is downloaded and checked
 * against the hash, QuickJS runs it, and it talks to the server. So this one is here, always, and
 * small enough to read in a minute.
 *
 * It has the shape of a real one (docs.md of the hook, «Apps a module brings as JS»): a `package.json`
 * with its version, a bundle (`notes.js`, written by hand, so there is no build), the app and its
 * methods registered in the hook's registry, its own right and its own words. The notes are in memory
 * and start over with every server. The server is not touched by any of this: a test that needs what
 * a real module does — the editor of the menus — asks for that module and says SKIPPED without it.
 */
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { version } = require("./package.json");

/** The module as `modules.list` of a server answers it. */
export const MODULE = { id: "test-notes", version, kind: "installed", title: "Test notes" };

/** The right of the module; only the administrator of the fake server holds it. */
export const TOKEN = { id: "notes-manager", name: "Notes", description: "The notes of the test module", department: "Tests" };

const ACCESS = { token: TOKEN.id } as const;

/** The app's strings in the declaration; the screen's own strings live in the bundle. */
export const translations: Record<string, Record<string, string>> = {
  en: {
    "mobile.notes.title": "Notes",
    "mobile.notes.category": "Tests",
    "mobile.notes.list.title": "Notes",
    "mobile.notes.note.title": "Note",
    "mobile.notes.save.title": "Save the note",
    "mobile.notes.remove.title": "Delete the note",
    "mobile.notes.field.text": "Text",
  },
  ru: {
    "mobile.notes.title": "Заметки",
    "mobile.notes.category": "Тесты",
    "mobile.notes.list.title": "Заметки",
    "mobile.notes.note.title": "Заметка",
    "mobile.notes.save.title": "Сохранить заметку",
    "mobile.notes.remove.title": "Удалить заметку",
    "mobile.notes.field.text": "Текст",
  },
};

/** As much of the hook's registry as this module uses; the hook owns the full types. */
interface Registry {
  registerApp(definition: Record<string, unknown>): void;
  registerMethod(definition: Record<string, unknown>): void;
}

export function registerTestModule(registry: Registry): void {
  const notes = new Map([
    ["n1", "Первая"],
    ["n2", "Вторая"],
  ]);
  const view = (id: string) => ({ id, text: notes.get(id) });
  const ID = { type: "string", minLength: 1, maxLength: 64 };

  registry.registerApp({
    id: "notes",
    version,
    title: "mobile.notes.title",
    icon: { path: "M4 4h16v16H4z" },
    category: "mobile.notes.category",
    home: "notes.list",
    module: MODULE.id,
    script: { file: path.join(__dirname, "notes.js"), min: 1 },
    models: { note: { fields: [{ name: "text", title: "mobile.notes.field.text", format: "text" }] } },
  });

  registry.registerMethod({
    name: "notes.list",
    app: "notes",
    kind: "query",
    title: "mobile.notes.list.title",
    access: ACCESS,
    model: "note",
    input: { type: "object", properties: {} },
    handler: async () => ({ items: [...notes.keys()].map(view) }),
  });

  registry.registerMethod({
    name: "notes.note",
    app: "notes",
    kind: "query",
    title: "mobile.notes.note.title",
    access: ACCESS,
    model: "note",
    input: { type: "object", required: ["id"], properties: { id: ID } },
    handler: async (params: { id: string }, ctx: any) => {
      if (!notes.has(params.id)) ctx.fail("not_found", "No such note");
      return { item: view(params.id) };
    },
  });

  registry.registerMethod({
    name: "notes.save",
    app: "notes",
    kind: "mutation",
    title: "mobile.notes.save.title",
    access: ACCESS,
    input: { type: "object", required: ["id", "text"], properties: { id: ID, text: { type: "string", minLength: 1, maxLength: 500 } } },
    handler: async (params: { id: string; text: string }, ctx: any) => {
      if (!notes.has(params.id)) ctx.fail("not_found", "No such note");
      notes.set(params.id, params.text);
      return { item: view(params.id) };
    },
  });

  registry.registerMethod({
    name: "notes.remove",
    app: "notes",
    kind: "mutation",
    title: "mobile.notes.remove.title",
    access: ACCESS,
    input: { type: "object", required: ["id"], properties: { id: ID } },
    handler: async (params: { id: string }) => ({ removed: notes.delete(params.id) }),
  });
}
