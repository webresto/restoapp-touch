package cx.m42.restoapp.script

/**
 * What the script finds already defined when it runs: `app()`, `ui`, `act` and the entry points the
 * host calls. The same text is the source of truth for what a script may use — `@superizer/ui` of the
 * long plan is this prelude published as a package, so a module can be typed against it.
 *
 * Stage 0 of `js-module.plan.md`: the screen is a tree of plain objects, handlers are kept in JS
 * under numbers (a function cannot cross into Kotlin), state lives in JS, and the rows come with the
 * tree. Host collections, row templates and `$`-expressions are stage 2.
 */
internal val PRELUDE: String = """
(function () {
  var app = null;
  var handlers = [];
  var route = null;     // { name, params, definition, state }
  var strings = {};
  var lang = "en";

  /** The script's one call: everything else hangs off what it passed. */
  globalThis.app = function (definition) { app = definition; };

  function keep(fn) {
    if (typeof fn !== "function") return null;
    handlers.push(fn);
    return handlers.length - 1;
  }

  function flat(children) {
    var out = [];
    (children || []).forEach(function (child) {
      if (child === null || child === undefined || child === false) return;
      if (Array.isArray(child)) out = out.concat(flat(child));
      else out.push(child);
    });
    return out;
  }

  function row(item) {
    return {
      key: item.key === undefined ? null : String(item.key),
      title: item.title === undefined ? null : item.title,
      subtitle: item.subtitle === undefined ? null : item.subtitle,
      value: item.value === undefined ? null : item.value,
      tone: item.tone === undefined ? null : item.tone,
      toggle: item.toggle === undefined ? null : item.toggle,
      onToggle: keep(item.onToggle),
      press: item.onPress && item.onPress.do ? item.onPress : null,
      onPress: item.onPress && item.onPress.do ? null : keep(item.onPress),
      actions: flat(item.actions).map(function (action) {
        return {
          title: action.title,
          tone: action.tone === undefined ? null : action.tone,
          press: action.onPress && action.onPress.do ? action.onPress : null,
          onPress: action.onPress && action.onPress.do ? null : keep(action.onPress),
        };
      }),
    };
  }

  globalThis.ui = {
    column: function (children) { return { type: "column", children: flat(children) }; },
    section: function (title, children) { return { type: "section", title: title, children: flat(children) }; },
    text: function (value) { return { type: "text", title: String(value) }; },
    list: function (props) {
      return {
        type: "list",
        items: (props.items || []).map(row),
        empty: props.empty === undefined ? null : props.empty,
        // With it the rows are dragged by a handle; the handler is called once, on release,
        // with { from, to } — the script moves the item itself and returns the new state.
        onReorder: keep(props.onReorder),
      };
    },
    /**
     * A line of text to type. The host keeps the letters and the cursor; `onChange` hears the whole
     * value, not a keystroke, so the script never deals with typing.
     */
    field: function (props) {
      return {
        type: "field",
        id: String(props.id),
        title: props.label === undefined ? null : props.label,
        value: props.value === undefined || props.value === null ? "" : String(props.value),
        placeholder: props.placeholder === undefined ? null : props.placeholder,
        onChange: keep(props.onChange),
      };
    },

    /** One of a list, chosen in a sheet: the link of a menu item, for one. */
    select: function (props) {
      return {
        type: "select",
        id: String(props.id),
        title: props.label === undefined ? null : props.label,
        value: props.value === undefined || props.value === null ? null : String(props.value),
        placeholder: props.placeholder === undefined ? null : props.placeholder,
        options: (props.options || []).map(function (option) {
          return {
            value: String(option.value),
            title: option.title === undefined ? String(option.value) : option.title,
            subtitle: option.subtitle === undefined ? null : option.subtitle,
          };
        }),
        onChange: keep(props.onChange),
      };
    },

    button: function (props) {
      return {
        type: "button",
        title: props.title,
        tone: props.tone === undefined ? null : props.tone,
        // Asked before the handler runs: deleting a menu is not a tap away from nothing.
        confirm: props.confirm === undefined ? null : props.confirm,
        enabled: props.enabled === undefined ? true : !!props.enabled,
        press: props.onPress && props.onPress.do ? props.onPress : null,
        onPress: props.onPress && props.onPress.do ? null : keep(props.onPress),
      };
    },
    empty: function (text) { return { type: "empty", title: text }; },
  };

  /** Actions the host performs without asking JS again. */
  globalThis.act = {
    push: function (name, params) { return { do: "push", route: name, params: params || {} }; },
    back: function () { return { do: "back" }; },
    toast: function (text) { return { do: "toast", text: text }; },
    refresh: function () { return { do: "refresh" }; },
  };

  var pending = {};
  var nextCall = 1;

  /** The one way out: the host calls the method, with the token and the point of its own. */
  function serverCall(method, params) {
    var id = nextCall++;
    return new Promise(function (resolve, reject) {
      pending[id] = { resolve: resolve, reject: reject };
      __host.call(id, method, JSON.stringify(params || {}));
    });
  }

  globalThis.__settle = function (id, ok, json) {
    var waiting = pending[id];
    delete pending[id];
    if (!waiting) return;
    if (ok) waiting.resolve(JSON.parse(json));
    else waiting.reject(new Error(json));
  };

  function ctx() {
    return {
      server: { call: serverCall },
      t: function (key) {
        var table = strings[lang] || strings[lang.split("-")[0]] || strings.en || {};
        return table[key] === undefined ? key : table[key];
      },
      toast: function (text) { __host.toast(String(text)); },
      // What the host does to the stack, when the script knows better: the menu it just deleted is
      // not a screen to stay on.
      back: function () { __host.command("back"); },
      refresh: function () { __host.command("refresh"); },
      log: function (text) { __host.log(String(text)); },
      // Stage 0 knows no rights of its own: what the person may call is what the declaration gave.
      can: function () { return true; },
    };
  }

  /** Enter a route: `enter` may be async, so the host is told when the state is ready. */
  globalThis.__enter = function (name, paramsJson) {
    var definition = app && app.routes ? app.routes[name] : null;
    if (!definition) { __host.failed("no such route: " + name); return; }
    strings = app.strings || {};
    var params = JSON.parse(paramsJson || "{}");
    route = { name: name, params: params, definition: definition, state: {} };
    Promise.resolve(definition.enter ? definition.enter(ctx(), params) : {})
      .then(function (state) {
        route.state = state || {};
        __host.ready();
      })
      .catch(function (error) { __host.failed(String(error && error.message ? error.message : error)); });
  };

  globalThis.__setLang = function (value) { lang = String(value || "en"); };

  /** Which route opens first: what the script said, or the first one it declared. */
  globalThis.__home = function () {
    if (!app || !app.routes) return null;
    return app.home || Object.keys(app.routes)[0] || null;
  };

  /** The screen as data: handlers are numbers the host sends back to `__handle`. */
  globalThis.__render = function () {
    if (!route) return JSON.stringify({ error: "nothing entered" });
    handlers = [];
    var title = typeof route.definition.title === "function" ? route.definition.title(route.state, ctx()) : null;
    var tree = route.definition.view(route.state, ctx());
    return JSON.stringify({ route: route.name, title: title, tree: tree });
  };

  /**
   * A handler of the rendered screen. What it returns is merged into the state; a Promise is waited
   * for, so a handler may call the server itself. The host is told when it may render again.
   */
  globalThis.__handle = function (id, argJson) {
    var fn = handlers[id];
    if (!fn) { __host.failed("no such handler: " + id); return; }
    var arg = argJson === undefined || argJson === null ? undefined : JSON.parse(argJson);
    Promise.resolve(fn(arg))
      .then(function (patch) {
        if (patch && typeof patch === "object") {
          Object.keys(patch).forEach(function (key) { route.state[key] = patch[key]; });
        }
        __host.ready();
      })
      .catch(function (error) { __host.failed(String(error && error.message ? error.message : error)); });
  };
})();
"""
