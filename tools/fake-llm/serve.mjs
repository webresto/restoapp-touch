#!/usr/bin/env node
// An OpenAI-compatible LLM that answers without a model — for the assistant of a panel on this computer, so the
// «Ассистент» of the app is walked end to end without a key: `node tools/fake-llm/serve.mjs 17321`, then start the
// cloud panel with CLOUD_AI_ENABLED=true CLOUD_AI_API_KEY=fake CLOUD_AI_BASE_URL=http://127.0.0.1:17321/v1
// CLOUD_AI_MODEL=fake (or RestoApp with OPENHARNESS_BASE_URL / OPENHARNESS_API_KEY).
//
// GET /v1/models; POST /v1/chat/completions, streamed or not. A message with «tools» / «инструмент» in it gets one
// call of a tool without arguments (list_tools in the cloud), then an answer about its result; anything else
// is answered in Markdown with the text it was asked.
import { createServer } from 'node:http';

const port = Number(process.argv[2] ?? process.env.PORT ?? 17321);

function lastUserText(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role !== 'user') continue;
    if (typeof m.content === 'string') return m.content;
    if (Array.isArray(m.content)) return m.content.filter((p) => p.type === 'text').map((p) => p.text).join('\n');
  }
  return '';
}

function replyTo(body) {
  const messages = body.messages ?? [];
  const last = messages[messages.length - 1];
  const text = lastUserText(messages);
  if (last?.role === 'tool') {
    const result = String(last.content ?? '').slice(0, 120);
    return { text: `The tool answered: \`${result}\`\n\n**Done.**` };
  }
  // A tool that needs no arguments: list_tools of the cloud, or the first one without required ones.
  const fns = (body.tools ?? []).map((t) => t.function).filter(Boolean);
  const tool = (fns.find((f) => f.name === 'list_tools') ?? fns.find((f) => !(f.parameters?.required ?? []).length))?.name;
  if (tool && /tools|инструмент/i.test(text)) {
    return { tool: { id: `call_${Date.now()}`, name: tool, args: '{}' } };
  }
  return { text: `**Echo:** ${text}\n\n- one\n- two\n\nSee [the console](/console).` };
}

function chunk(id, delta, finish = null) {
  return `data: ${JSON.stringify({ id, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: 'fake', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
}

const usage = { prompt_tokens: 12, completion_tokens: 8, total_tokens: 20 };

createServer((req, res) => {
  let raw = '';
  req.on('data', (c) => (raw += c));
  req.on('end', async () => {
    if (req.method === 'GET' && req.url.endsWith('/models')) {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ object: 'list', data: [{ id: 'fake', object: 'model' }] }));
    }
    if (req.method !== 'POST' || !req.url.endsWith('/chat/completions')) {
      res.writeHead(404, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: { message: `no ${req.method} ${req.url}` } }));
    }
    const body = JSON.parse(raw || '{}');
    const reply = replyTo(body);
    const id = `chatcmpl-${Date.now()}`;
    if (!body.stream) {
      res.writeHead(200, { 'content-type': 'application/json' });
      const message = reply.tool
        ? { role: 'assistant', content: null, tool_calls: [{ id: reply.tool.id, type: 'function', function: { name: reply.tool.name, arguments: reply.tool.args } }] }
        : { role: 'assistant', content: reply.text };
      return res.end(JSON.stringify({ id, object: 'chat.completion', model: 'fake', choices: [{ index: 0, message, finish_reason: reply.tool ? 'tool_calls' : 'stop' }], usage }));
    }
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
    res.write(chunk(id, { role: 'assistant', content: '' }));
    if (reply.tool) {
      res.write(chunk(id, { tool_calls: [{ index: 0, id: reply.tool.id, type: 'function', function: { name: reply.tool.name, arguments: reply.tool.args } }] }));
      res.write(chunk(id, {}, 'tool_calls'));
    } else {
      for (const word of reply.text.split(/(?<= )/)) {
        res.write(chunk(id, { content: word }));
        await new Promise((r) => setTimeout(r, 15));
      }
      res.write(chunk(id, {}, 'stop'));
    }
    res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', model: 'fake', choices: [], usage })}\n\n`);
    res.end('data: [DONE]\n\n');
  });
}).listen(port, '127.0.0.1', () => console.log(`fake LLM at http://127.0.0.1:${port}/v1`));
