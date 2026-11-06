// Latency check: node server/test-client.mjs ws://<bridge-tailscale-ip>:8000/ws
// Sends a new-chat message, then a follow-up in the same session; prints time to first token.
const url = process.argv[2];
if (!url) { console.error('Usage: node test-client.mjs ws://<bridge-tailscale-ip>:8000/ws'); process.exit(1); }
const ws = new WebSocket(url);
const t0 = Date.now();
const turns = ['Reply with one short sentence: what is 17*23?', 'And that number minus 91? One short sentence.'];
let session = null, started = 0, first = 0, text = '';

function next() {
  const prompt = turns.shift();
  if (!prompt) { ws.close(); return; }
  text = ''; first = 0; started = Date.now();
  ws.send(JSON.stringify({ t: 'send', conversation: session, model: 'haiku', effort: 'low', content: [{ type: 'text', text: prompt }] }));
}

ws.onopen = () => console.log(`connected in ${Date.now() - t0} ms`);
ws.onmessage = ({ data }) => {
  const f = JSON.parse(data);
  if (f.t === 'ready') return next();
  if (f.t === 'session') session = f.conversation;
  if ((f.t === 'text' || f.t === 'thinking') && !first) first = Date.now() - started;
  if (f.t === 'text') text += f.text;
  if (f.t === 'done') { console.log(`first token ${first} ms, total ${Date.now() - started} ms: ${text.trim()}`); next(); }
  if (f.t === 'error') { console.log('error:', f.message); ws.close(); }
};
