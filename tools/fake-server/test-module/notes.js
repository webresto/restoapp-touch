// notes.js: the whole app of the test module, as a client downloads it.
app({
  id: "notes",
  uiVersion: 1,
  strings: {
    ru: {
      notes: "Заметки",
      empty: "Заметок нет",
      text: "Текст",
      save: "Сохранить",
      saved: "Сохранено",
      remove: "Удалить",
      removeAsk: "Удалить заметку?",
      removed: "Удалено"
    },
    en: {
      notes: "Notes",
      empty: "No notes",
      text: "Text",
      save: "Save",
      saved: "Saved",
      remove: "Delete",
      removeAsk: "Delete the note?",
      removed: "Deleted"
    }
  },
  routes: {
    list: {
      async enter(ctx) {
        const { items } = await ctx.server.call("notes.list", {});
        return { notes: items };
      },
      title: (state, ctx) => ctx.t("notes"),
      view: (state, ctx) => ui.list({
        items: state.notes.map((note) => ({ key: note.id, title: note.text, onPress: act.push("note", { id: note.id }) })),
        empty: ctx.t("empty")
      })
    },
    note: {
      path: "note/:id",
      async enter(ctx, params) {
        const { item } = await ctx.server.call("notes.note", { id: params.id });
        return { id: item.id, text: item.text, dirty: false };
      },
      title: (state) => state.text,
      view: (state, ctx) => ui.column([
        ui.field({ id: "text", label: ctx.t("text"), value: state.text, onChange: (text) => ({ text, dirty: true }) }),
        state.dirty ? ui.button({
          title: ctx.t("save"),
          tone: "success",
          onPress: async () => {
            await ctx.server.call("notes.save", { id: state.id, text: state.text });
            ctx.toast(ctx.t("saved"));
            return { dirty: false };
          }
        }) : null,
        ui.button({
          title: ctx.t("remove"),
          tone: "danger",
          confirm: ctx.t("removeAsk"),
          onPress: async () => {
            await ctx.server.call("notes.remove", { id: state.id });
            ctx.toast(ctx.t("removed"));
            ctx.back();
            return {};
          }
        })
      ])
    }
  }
});
