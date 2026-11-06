// End-to-end check of a bridge from any device on your tailnet:
//   node test-client.mjs ws://<bridge-tailscale-ip>:8000/ws [folder]
// Prints the computer's info and account, opens a session in [folder] (default: its home
// folder), sends one short message, prints the events as they stream, then closes it.
const [url, folder] = process.argv.slice(2);
if (!url) { console.error('Usage: node test-client.mjs ws://<bridge-tailscale-ip>:8000/ws [folder]'); process.exit(1); }

const ws = new WebSocket(url);
let n = 0;
const pending = new Map();
const ask = (t, fields = {}) => new Promise((resolve, reject) => {
  const id = String(++n);
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ t, id, ...fields }));
});
const t0 = Date.now();
const ms = () => `${String(Date.now() - t0).padStart(6)} ms`;

ws.onmessage = async ({ data }) => {
  const f = JSON.parse(data);
  if (f.id && pending.has(f.id)) {
    const p = pending.get(f.id);
    pending.delete(f.id);
    return f.t === 'error' ? p.reject(new Error(f.message)) : p.resolve(f);
  }
  if (f.t === 'ready') return run().catch(err => { console.error('FAILED:', err.message); ws.close(); process.exitCode = 1; });
  if (f.t === 'ev') {
    const e = f.e;
    if (e.k === 'text') process.stdout.write(e.text);
    else if (e.k === 'step') console.log(`\n${ms()} step: ${e.label}`);
    else if (e.k === 'stepDone') console.log(`${ms()} done${e.error ? ' (error)' : ''}: ${(e.output ?? '').split('\n')[0].slice(0, 100)}`);
    else if (e.k === 'state') console.log(`\n${ms()} state: ${e.state}`);
    else if (e.k === 'note') console.log(`\n${ms()} note: ${e.text}`);
    if (e.k === 'state' && e.state === 'idle' && session) finish();
  }
};
ws.onerror = () => { console.error(`Can't reach ${url}. Is Tailscale on and the bridge running?`); process.exit(1); };

let session = null;
async function run() {
  const info = await ask('info');
  console.log(`${ms()} connected to "${info.name}" (${info.platform})`);
  const acct = await ask('account');
  console.log(`${ms()} Claude Code: ${acct.loggedIn ? `signed in as ${acct.email} (${acct.plan})` : 'NOT signed in'}`);
  const { key } = await ask('open', { cwd: folder || info.home });
  session = key;
  const snap = await ask('attach', { key });
  console.log(`${ms()} session open in ${snap.session.cwd}`);
  await ask('send', { key, text: 'Run `echo bridge-ok` with Bash, then reply with one short sentence.' });
}

async function finish() {
  const s = session;
  session = null;
  await ask('close', { key: s }).catch(() => {});
  console.log(`${ms()} closed. OK`);
  ws.close();
}
