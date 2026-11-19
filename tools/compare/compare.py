"""Side-by-side pictures: the prototype (prototype-rikka/cloudApp) on the left, the real app on a live server
(LiveShotsTest) on the right, one PNG per screen and window class.

    python3 tools/compare/compare.py   # → build/compare-proto-live/<class>-<theme>/NN-<screen>.png + index.html
"""
import glob, html, os, re
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PROTO = os.path.join(ROOT, "..", "prototype-rikka", "build", "screenshots-proto-all")
LIVE = os.path.join(ROOT, "composeApp", "build", "screenshots-live")
OUT = os.path.join(ROOT, "build", "compare-proto-live")
CLASSES = [("compact", 420, "телефон"), ("medium", 800, "планшет"), ("expanded", 1280, "десктоп")]
# (screen, prototype file, live role, live screen suffix)
PAIRS = [
    ("connect", "connect", "admin", "connect"),
    ("home-admin", "r-home", "admin", "home"),
    ("home-operator", "r-home-operator", "operator", "home"),
    ("home-stock", "r-home-stock", "stock", "home"),
    ("more", "r-more", "admin", "more"),
    ("servers", "switcher", "admin", "contexts"),
    ("orders", "r-orders", "admin", "orders"),
    ("order", "r-order", "admin", "orders-card"),
    ("pos", "r-pos", "admin", "pos"),
    ("dishes", "r-dishes", "admin", "dishes"),
    ("dish", "r-dish", "admin", "dishes-card"),
    ("clients", "r-clients", "admin", "clients"),
    ("client", "r-client", "admin", "clients-card"),
    ("marketing", "r-marketing", "admin", "marketing"),
    ("channels", "r-channels", "admin", "channels"),
    ("settings", "r-settings", "admin", "settings"),
    ("system", "r-programs", "admin", "system"),
]
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

def live_file(width, theme, role, screen):
    found = [f for f in glob.glob(os.path.join(LIVE, f"{width}-{theme}", f"{role}-*-{screen}.png"))
             if re.fullmatch(rf"{role}-\d+-{re.escape(screen)}\.png", os.path.basename(f))]
    return found[0] if found else None

def label(img, text):
    font = ImageFont.truetype(FONT, 22)
    bar = Image.new("RGB", (img.width, 44), (32, 32, 40))
    ImageDraw.Draw(bar).text((14, 9), text, fill=(255, 255, 255), font=font)
    out = Image.new("RGB", (img.width, img.height + 44), (255, 255, 255))
    out.paste(bar, (0, 0)); out.paste(img, (0, 44))
    return out

def fit(img, height):
    return img.resize((round(img.width * height / img.height), height), Image.LANCZOS)

rows = []
for cls, width, title in CLASSES:
    for theme in ("light", "dark"):
        made = []
        for n, (name, proto, role, screen) in enumerate(PAIRS, 1):
            p = os.path.join(PROTO, cls, f"{proto}-{theme}.png")
            l = live_file(width, theme, role, screen)
            if not (os.path.exists(p) and l):
                continue
            a, b = Image.open(p).convert("RGB"), Image.open(l).convert("RGB")
            h = min(a.height, b.height, 1400)
            a, b = label(fit(a, h), "Прототип"), label(fit(b, h), f"Приложение · {role}")
            gap = 24
            sheet = Image.new("RGB", (a.width + b.width + gap, max(a.height, b.height)), (200, 200, 205))
            sheet.paste(a, (0, 0)); sheet.paste(b, (a.width + gap, 0))
            folder = os.path.join(OUT, f"{cls}-{theme}")
            os.makedirs(folder, exist_ok=True)
            path = os.path.join(folder, f"{n:02d}-{name}.png")
            sheet.save(path, optimize=True)
            made.append(path)
        if made:
            rows.append((f"{title} ({width}) · {theme}", made))

PAGE_HEAD = """<!doctype html><meta charset=utf-8><title>Прототип и приложение</title>
<style>
body{font:15px/1.4 sans-serif;margin:24px auto;max-width:1500px;background:#eee;color:#222}
h2{margin-top:48px;padding-top:12px;border-top:2px solid #ccc}
.pair{background:#fff;padding:12px 16px 16px;margin:16px 0 28px;border-radius:8px;box-shadow:0 1px 4px #0002}
.pair img{max-width:100%;display:block;margin:8px 0}
.name{font-weight:bold}
textarea{width:100%;box-sizing:border-box;min-height:56px;font:14px/1.4 sans-serif;padding:8px;border:1px solid #bbb;border-radius:6px;resize:vertical}
textarea.filled{border-color:#6d55d6;background:#f6f3ff}
.section-note{margin:8px 0 4px}
#export{position:sticky;bottom:0;background:#fff;padding:16px;margin-top:40px;box-shadow:0 -2px 8px #0002;border-radius:8px 8px 0 0}
#export textarea{min-height:200px;font-family:monospace}
button{font:15px sans-serif;padding:8px 16px;margin-right:8px;border-radius:6px;border:1px solid #6d55d6;background:#6d55d6;color:#fff;cursor:pointer}
button.light{background:#fff;color:#6d55d6}
#status{margin-left:8px;color:#2a7}
nav a{margin-right:12px}
</style>
<h1>Прототип ↔ приложение на живом сервере</h1>
<p>Слева — прототип, справа — приложение на живом сервере. Под каждой парой и под каждым разделом — поле для комментария.
Комментарии сохраняются в этом браузере сами. Внизу кнопка собирает их все в один текст, который можно скопировать или скачать.</p>
"""

PAGE_TAIL = """<div id=export>
<button id=collect>Собрать все комментарии</button>
<button id=copy class=light>Скопировать</button>
<button id=download class=light>Скачать .md</button>
<button id=clear class=light>Очистить все</button>
<span id=status></span>
<p><textarea id=all readonly placeholder="Нажмите «Собрать все комментарии»"></textarea></p>
</div>
<script>
const KEY = 'compare-proto-live:';
const notes = [...document.querySelectorAll('textarea[data-key]')];
const mark = t => t.classList.toggle('filled', t.value.trim() !== '');
for (const t of notes) {
  t.value = localStorage.getItem(KEY + t.dataset.key) || '';
  mark(t);
  t.addEventListener('input', () => { localStorage.setItem(KEY + t.dataset.key, t.value); mark(t); });
}
function collect() {
  const lines = ['# Комментарии: прототип ↔ приложение', ''];
  let section = null;
  for (const t of notes) {
    const text = t.value.trim();
    if (!text) continue;
    if (t.dataset.section !== section) { section = t.dataset.section; lines.push('## ' + section, ''); }
    if (t.dataset.screen) lines.push('### ' + t.dataset.screen + '  (' + t.dataset.file + ')', '');
    else lines.push('**Весь раздел:**', '');
    lines.push(text, '');
  }
  const out = lines.length > 2 ? lines.join('\\n') : '';
  document.getElementById('all').value = out;
  status(out ? 'собрано: ' + notes.filter(t => t.value.trim()).length + ' комментариев' : 'комментариев пока нет');
  return out;
}
function status(text) { const s = document.getElementById('status'); s.textContent = text; setTimeout(() => s.textContent = '', 4000); }
document.getElementById('collect').onclick = collect;
document.getElementById('copy').onclick = async () => {
  const text = collect(); if (!text) return;
  try { await navigator.clipboard.writeText(text); status('скопировано в буфер обмена'); }
  catch { const a = document.getElementById('all'); a.focus(); a.select(); document.execCommand('copy'); status('скопировано'); }
};
document.getElementById('download').onclick = () => {
  const text = collect(); if (!text) return;
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([text], {type: 'text/markdown'}));
  a.download = 'comments-proto-live.md'; a.click();
};
document.getElementById('clear').onclick = () => {
  if (!confirm('Удалить все комментарии на этой странице?')) return;
  for (const t of notes) { t.value = ''; localStorage.removeItem(KEY + t.dataset.key); mark(t); }
  document.getElementById('all').value = '';
};
</script>
"""

SCREEN_TITLES = {
    "connect": "Подключение сервера", "home-admin": "Главная · администратор", "home-operator": "Главная · оператор",
    "home-stock": "Главная · склад", "more": "Ещё", "servers": "Список серверов", "orders": "Заказы",
    "order": "Карточка заказа", "pos": "Касса", "dishes": "Блюда", "dish": "Карточка блюда", "clients": "Клиенты",
    "client": "Карточка клиента", "marketing": "Маркетинг", "channels": "Каналы продаж", "settings": "Настройки",
    "system": "Система",
}

def esc(text):
    return html.escape(text, quote=True)

with open(os.path.join(OUT, "index.html"), "w") as page:
    page.write(PAGE_HEAD)
    page.write("<nav>" + "".join(f"<a href='#s{i}'>{esc(title)}</a>" for i, (title, _) in enumerate(rows)) + "</nav>")
    for i, (title, made) in enumerate(rows):
        page.write(f"<h2 id='s{i}'>{esc(title)}</h2>")
        page.write(f"<div class=section-note><textarea data-key='{esc(title)}#section' data-section='{esc(title)}' "
                   f"placeholder='Комментарий ко всему разделу «{esc(title)}»'></textarea></div>")
        for path in made:
            rel = os.path.relpath(path, OUT)
            base = os.path.basename(path)
            screen = SCREEN_TITLES.get(re.sub(r"^\d+-|\.png$", "", base), base)
            page.write(f"<div class=pair><div class=name>{esc(screen)} <small>({esc(rel)})</small></div><img src='{esc(rel)}' loading=lazy>"
                       f"<textarea data-key='{esc(title)}#{esc(base)}' data-section='{esc(title)}' data-screen='{esc(screen)}' "
                       f"data-file='{esc(rel)}' placeholder='Комментарий к экрану «{esc(screen)}»'></textarea></div>")
    page.write(PAGE_TAIL)
for title, made in rows:
    print(title, len(made))
print(OUT)
