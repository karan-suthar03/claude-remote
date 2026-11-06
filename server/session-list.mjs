/* The `sessions` reply: sessions open in the bridge, sessions running in a terminal, and
   sessions Claude Code saved on this computer, optionally for one folder only. */
import path from 'node:path';
import { listSessions } from '@anthropic-ai/claude-agent-sdk';
import { linkedProjectFolders } from './linked-projects.mjs';

// Windows and macOS file systems are case-insensitive by default.
const FOLD_CASE = process.platform === 'win32' || process.platform === 'darwin';

/** A folder path in one canonical form: absolute, no trailing separator, case folded where the OS ignores case. */
export function normPath(p) {
  const r = path.resolve(String(p));
  return FOLD_CASE ? r.toLowerCase() : r;
}

const titleOf = s => s.customTitle || s.summary || s.firstPrompt || '';

// Linked project directories often sit on a network drive and the phone polls every few
// seconds: their listing is reused for this long (and shared by overlapping requests).
const LINKED_TTL_MS = 15_000;
let linkedCache = null;   // { at, limit, promise }

/** [session, folder] for every session in a symlinked project directory, newest first per folder. */
function linkedSessions(claudeDir, limit) {
  const now = Date.now();
  if (linkedCache && now - linkedCache.at < LINKED_TTL_MS && linkedCache.limit >= limit) return linkedCache.promise;
  const promise = Promise.all(linkedProjectFolders(claudeDir).map(dir =>
    listSessions({ dir, includeWorktrees: false, limit })
      .then(list => list.map(s => [s, dir]))
      .catch(e => { console.error(`sessions: listing ${dir} failed:`, e?.message || e); return []; })))
    .then(lists => lists.flat());
  linkedCache = { at: now, limit, promise };
  return promise;
}

/**
 * @param live         info() of the sessions open in this bridge
 * @param cwd          only sessions whose working folder is this one (null: every folder)
 * @param limit        maximum closed sessions returned
 * @param activeMs     a transcript written this recently counts as running in a terminal
 * @param touchedSince (sessionId, mtime) -> whether someone other than the bridge wrote it
 * @param claudeDir    Claude Code's config folder, for its projects/ directory
 */
export async function sessionsReply({ live, cwd, limit, activeMs, touchedSince, claudeDir }) {
  const folder = cwd ? normPath(cwd) : null;
  const inFolder = dir => !folder || (!!dir && normPath(dir) === folder);

  const openList = live.filter(s => inFolder(s.cwd));
  const liveIds = new Set(openList.map(s => s.sessionId).filter(Boolean));
  // includeWorktrees false: a folder's sessions are its own, not those of sibling worktrees.
  const opts = folder ? { dir: cwd, includeWorktrees: false } : {};
  let saved;
  try {
    saved = await listSessions({ ...opts, limit: limit + liveIds.size });
  } catch (e) {
    // A folder Claude Code never ran in has no project directory: that just means no sessions.
    if (e?.code !== 'ENOENT') console.error(`sessions: listing ${cwd || 'all folders'} failed:`, e?.message || e);
    saved = [];
  }
  // Listing every folder skips symlinked project directories: add each one, listed by its folder.
  const linkedFolder = new Map();   // sessionId -> folder it resumes in
  if (!folder) {
    const known = new Set(saved.map(s => s.sessionId));
    for (const [s, dir] of await linkedSessions(claudeDir, limit + liveIds.size)) {
      if (known.has(s.sessionId)) continue;
      known.add(s.sessionId);
      saved.push(s);
      linkedFolder.set(s.sessionId, dir);
    }
    saved.sort((a, b) => b.lastModified - a.lastModified);
  }

  // No filtering on each session's recorded cwd: like Claude Code's /resume, a folder's sessions
  // are the ones in its project directory, including those recorded before the folder was moved.
  // They resume in the folder asked about (or the linked directory's folder), so that is the cwd reported.
  const cwdOf = s => (folder ? cwd : linkedFolder.get(s.sessionId) ?? s.cwd) ?? '';

  // Sessions running in a terminal right now count as open, mirrored live on attach.
  const now = Date.now();
  for (const s of saved) {
    if (liveIds.has(s.sessionId) || now - s.lastModified > activeMs || !touchedSince(s.sessionId, s.lastModified)) continue;
    openList.push({
      key: `term:${s.sessionId}`, sessionId: s.sessionId, cwd: cwdOf(s), title: titleOf(s),
      state: now - s.lastModified < 8_000 ? 'running' : 'idle', lastActive: Math.min(now, s.lastModified),
      model: null, effort: null, costUsd: 0, contextTokens: 0, background: 0, origin: 'terminal',
    });
    liveIds.add(s.sessionId);
  }
  openList.sort((a, b) => b.lastActive - a.lastActive);

  const closed = saved
    .filter(s => !liveIds.has(s.sessionId))
    .sort((a, b) => b.lastModified - a.lastModified)
    .slice(0, limit)
    .map(s => ({ sessionId: s.sessionId, cwd: cwdOf(s), title: titleOf(s), state: 'idle', lastActive: s.lastModified }));
  return { open: openList, closed };
}
