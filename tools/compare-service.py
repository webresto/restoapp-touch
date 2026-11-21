#!/usr/bin/env python3
"""The screens of one Resto Cloud service: the prototype (left) next to the app (right), and a page to comment them.

    tools/compare-service.py [<prototype screenshots/cloud dir>]

App shots — composeApp/build/screenshots-service/<420|800|1280>/ (ServiceShotsTest). Prototype —
prototype-rikka's screenshots of cloudApp, {compact,medium,expanded}/<screen>-light.png (tools/screenshots/shoot-cloud.mjs).
Out — build/compare-service/<width>-<screen>.png and build/compare-service/index.html: a field under every pair and
every width, kept in the browser, and a button that gathers them into one text. Needs Pillow.
"""
import html
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
PROTO = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT.parent / 'prototype-rikka/.gradle/9.5.0/screenshots/cloud'
SHOTS = ROOT / 'composeApp/build/screenshots-service'
OUT = ROOT / 'build/compare-service'

# app shot → (prototype screen or None, the title of the pair)
PAIRS = [
    ('01-overview', 'service-overview', 'Обзор работающего сервиса'),
    ('02-events', 'service-events', 'События'),
    ('03-logs', 'service-logs', 'Логи'),
    ('04-spend', 'service-billing', 'Расходы'),
    ('05-settings', 'service-settings', 'Настройки'),
    ('06-starting', 'service-starting', 'Запуск: шаги, обновляется сам'),
    ('07-error', 'service-error', 'Ошибка: что случилось и что делать'),
    ('08-stopped', None, 'Выключен (в прототипе — пустой экран «Сервис выключен»)'),
]
WIDTHS = [(420, 'compact', 'телефон (420)'), (800, 'medium', 'планшет (800)'), (1280, 'expanded', 'десктоп (1280)')]
HEIGHT = 900


def font(size):
    for name in ('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', '/usr/share/fonts/TTF/DejaVuSans-Bold.ttf'):
        if Path(name).exists():
            return ImageFont.truetype(name, size)
    return ImageFont.load_default()


def fit(path: Path) -> Image.Image:
    img = Image.open(path).convert('RGB')
    scale = HEIGHT / img.height
    return img.resize((round(img.width * scale), HEIGHT), Image.LANCZOS)


def pair(proto: Path | None, app: Path, title: str, out: Path):
    left = fit(proto) if proto and proto.exists() else None
    right = fit(app)
    gap, head = 24, 44
    width = (left.width + gap if left else 0) + right.width
    canvas = Image.new('RGB', (width, HEIGHT + head), (226, 226, 232))
    draw = ImageDraw.Draw(canvas)
    f = font(24)
    x = 0
    if left:
        canvas.paste(left, (0, head))
        draw.text((10, 8), 'Прототип', fill=(20, 20, 20), font=f)
        x = left.width + gap
    canvas.paste(right, (x, head))
    draw.text((x + 10, 8), 'Приложение', fill=(20, 20, 20), font=f)
    canvas.save(out)


def main():
    if not SHOTS.exists():
        sys.exit(f'No app shots at {SHOTS}: run ./gradlew :composeApp:desktopTest --tests cx.m42.restoapp.ServiceShotsTest')
    OUT.mkdir(parents=True, exist_ok=True)
    sections = []
    for width, cls, label in WIDTHS:
        rows = []
        for shot, screen, title in PAIRS:
            app = SHOTS / str(width) / f'{shot}.png'
            if not app.exists():
                continue
            proto = PROTO / cls / f'{screen}-light.png' if screen else None
            name = f'{width}-{shot}.png'
            pair(proto, app, title, OUT / name)
            rows.append((name, title))
        sections.append((label, rows))
    write_page(sections)
    print(OUT / 'index.html')


def write_page(sections):
    e = html.escape
    parts = ['''<!doctype html><meta charset=utf-8><title>Сервис: прототип и приложение</title>
<style>
body{font:15px/1.4 sans-serif;margin:24px auto;max-width:1500px;background:#eee;color:#222}
h2{margin-top:48px;padding-top:12px;border-top:2px solid #ccc}
.pair{background:#fff;padding:12px 16px 16px;margin:16px 0 28px;border-radius:8px;box-shadow:0 1px 4px #0002}
.pair img{max-width:100%;display:block;margin:8px 0}
.name{font-weight:bold}
textarea{width:100%;box-sizing:border-box;min-height:56px;font:14px/1.4 sans-serif;padding:8px;border:1px solid #bbb;border-radius:6px;resize:vertical}
textarea.filled{border-color:#6d55d6;background:#f6f3ff}
#export{position:sticky;bottom:0;background:#fff;padding:16px;margin-top:40px;box-shadow:0 -2px 8px #0002;border-radius:8px 8px 0 0}
#export textarea{min-height:200px;font-family:monospace}
button{font:15px sans-serif;padding:8px 16px;margin-right:8px;border-radius:6px;border:1px solid #6d55d6;background:#6d55d6;color:#fff;cursor:pointer}
button.light{background:#fff;color:#6d55d6}
#status{margin-left:8px;color:#2a7}
nav a{margin-right:12px}
</style>
<h1>Экраны сервиса: прототип ↔ приложение</h1>
<p>Слева — прототип (prototype-rikka/cloudApp), справа — приложение на демо-облаке с теми же сервисами (ServiceShotsTest).
Под каждой парой и под каждым разделом — поле для комментария; комментарии сохраняются в этом браузере.
Внизу кнопка собирает их в один текст.</p><nav>''']
    parts += [f"<a href='#s{i}'>{e(label)}</a>" for i, (label, _) in enumerate(sections)]
    parts.append('</nav>')
    for i, (label, rows) in enumerate(sections):
        parts.append(f"<h2 id='s{i}'>{e(label)}</h2>")
        parts.append(f"<div><textarea data-key='{e(label)}#section' data-section='{e(label)}' placeholder='Комментарий ко всему разделу «{e(label)}»'></textarea></div>")
        for name, title in rows:
            parts.append(
                f"<div class=pair><div class=name>{e(title)} <small>({e(name)})</small></div><img src='{e(name)}' loading=lazy>"
                f"<textarea data-key='{e(label)}#{e(name)}' data-section='{e(label)}' data-screen='{e(title)}' data-file='{e(name)}' "
                f"placeholder='Комментарий к экрану «{e(title)}»'></textarea></div>")
    parts.append('''<div id=export>
<button id=collect>Собрать все комментарии</button>
<button id=copy class=light>Скопировать</button>
<button id=download class=light>Скачать .md</button>
<button id=clear class=light>Очистить все</button>
<span id=status></span>
<p><textarea id=all readonly placeholder="Нажмите «Собрать все комментарии»"></textarea></p>
</div>
<script>
const KEY = 'compare-service:';
const notes = [...document.querySelectorAll('textarea[data-key]')];
const mark = t => t.classList.toggle('filled', t.value.trim() !== '');
for (const t of notes) {
  t.value = localStorage.getItem(KEY + t.dataset.key) || '';
  mark(t);
  t.addEventListener('input', () => { localStorage.setItem(KEY + t.dataset.key, t.value); mark(t); });
}
function collect() {
  const lines = ['# Комментарии: экраны сервиса, прототип ↔ приложение', ''];
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
  a.download = 'comments-service.md'; a.click();
};
document.getElementById('clear').onclick = () => {
  if (!confirm('Удалить все комментарии на этой странице?')) return;
  for (const t of notes) { t.value = ''; localStorage.removeItem(KEY + t.dataset.key); mark(t); }
  document.getElementById('all').value = '';
};
</script>''')
    (OUT / 'index.html').write_text('\n'.join(parts), encoding='utf-8')


if __name__ == '__main__':
    main()
