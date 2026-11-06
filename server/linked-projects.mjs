/* Claude Code project directories that are symlinks (e.g. ~/.claude/projects/F--home-me-app
   pointing at another machine's transcripts). The SDK's all-folders session listing skips them,
   since a symlink's Dirent isn't a directory, but listing one folder follows them. This finds
   the folder each one belongs to so the bridge can list it by folder. */
import fs from 'node:fs';
import path from 'node:path';

// Claude Code's project directory name for a folder (its sanitizePath). Paths long enough to
// get a hash suffix never match, so those directories are skipped.
const projectKey = p => p.replace(/[^a-zA-Z0-9]/g, '-');

const HEAD_BYTES = 64 * 1024;   // a transcript's cwd is in its first entries
const MAX_FILES = 20;           // transcripts checked per directory, newest first

// dir name -> { mtimeMs, folder }: re-read only when the directory changes.
const cache = new Map();

/** Every `cwd` in the first HEAD_BYTES of a transcript. */
function cwdsIn(file) {
  let text;
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const buf = Buffer.alloc(HEAD_BYTES);
      text = buf.toString('utf8', 0, fs.readSync(fd, buf, 0, HEAD_BYTES, 0));
    } finally { fs.closeSync(fd); }
  } catch { return []; }
  const out = new Set();
  for (const line of text.split('\n')) {
    if (!line.includes('"cwd"')) continue;
    try { const cwd = JSON.parse(line).cwd; if (typeof cwd === 'string' && cwd) out.add(cwd); } catch { /* cut-off last line */ }
  }
  return [...out];
}

/**
 * The existing folder whose project key is [key], found by walking down from the root and
 * following, at each level, the children whose own key continues it ("F--home-me-my-app" ->
 * F:\home\me\my-app or F:\home\me\my\app, whichever exists). Null when none does.
 */
export function folderForKey(key) {
  let root, rest;
  const drive = /^([A-Za-z])--(.*)$/.exec(key);
  if (drive && process.platform === 'win32') [root, rest] = [`${drive[1].toUpperCase()}:\\`, drive[2]];
  else if (key.startsWith('-')) [root, rest] = ['/', key.slice(1)];
  else return null;
  let steps = 0;
  const walk = (dir, rest) => {
    if (!rest) return dir;
    if (++steps > 2000) return null;   // bound the search in huge, ambiguous trees
    let names;
    try { names = fs.readdirSync(dir, { withFileTypes: true }); } catch { return null; }
    for (const d of names) {
      const k = projectKey(d.name);
      if (!k || !(rest === k || rest.startsWith(k + '-'))) continue;
      const child = path.join(dir, d.name);
      if (!d.isDirectory()) { try { if (!fs.statSync(child).isDirectory()) continue; } catch { continue; } }
      const found = walk(child, rest.slice(k.length + 1));
      if (found) return found;
    }
    return null;
  };
  return walk(root, rest);
}

/** The folder whose sessions live in project directory [dir] (named [name]), or null. */
function folderOf(dir, name) {
  let st;
  try { st = fs.statSync(dir); } catch { return null; }   // dangling link
  if (!st.isDirectory()) return null;
  const hit = cache.get(name);
  if (hit && hit.mtimeMs === st.mtimeMs) return hit.folder;
  let folder = null;
  try {
    const files = fs.readdirSync(dir)
      .filter(f => f.endsWith('.jsonl'))
      .map(f => { const p = path.join(dir, f); try { return { p, t: fs.statSync(p).mtimeMs }; } catch { return null; } })
      .filter(Boolean)
      .sort((a, b) => b.t - a.t)
      .slice(0, MAX_FILES);
    // Sessions recorded before the folder moved carry the old path: take the one that maps here.
    outer: for (const { p } of files) for (const cwd of cwdsIn(p)) if (projectKey(cwd) === name) { folder = cwd; break outer; }
  } catch { /* unreadable: no folder */ }
  // Transcripts copied from elsewhere record their old folder: find the one the name encodes.
  folder ??= folderForKey(name);
  cache.set(name, { mtimeMs: st.mtimeMs, folder });
  return folder;
}

/** Folders of the symlinked project directories under [claudeDir]/projects. */
export function linkedProjectFolders(claudeDir) {
  const root = path.join(claudeDir, 'projects');
  let entries;
  try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch { return []; }
  const out = [];
  for (const e of entries) {
    if (!e.isSymbolicLink()) continue;
    const folder = folderOf(path.join(root, e.name), e.name);
    if (folder) out.push(folder);
  }
  return out;
}
