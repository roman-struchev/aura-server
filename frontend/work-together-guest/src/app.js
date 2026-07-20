/**
 * Work Together - guest-facing collaborative editor.
 *
 * This file is bundled by esbuild into a single IIFE (dist/app.js -> copied to
 * src/main/resources/static/work-together/app.js). It assumes:
 *  - Monaco's AMD loader has already run and `window.monaco` is available
 *    (this file is loaded from inside the `require(['vs/editor/editor.main'], ...)`
 *    callback - see guest-template.html).
 *  - `window.__WORK_TOGETHER__` has been populated by the server-rendered HTML
 *    template with the session/token/role/language/filePath/initialContent.
 */

import * as Y from 'yjs'
import { WebsocketProvider } from 'y-websocket'
import { MonacoBinding } from 'y-monaco'

// -----------------------------------------------------------------------
// Config
// -----------------------------------------------------------------------

const config = window.__WORK_TOGETHER__ || {}
const sessionId = config.sessionId
const token = config.token
const role = config.role === 'write' ? 'write' : 'read'
const language = config.language || 'plaintext'
const filePath = config.filePath || 'untitled'
const fileName = filePath.split(/[\\/]/).pop() || filePath
const initialContent = typeof config.initialContent === 'string' ? config.initialContent : ''

const PARTICIPANT_COLORS = [
  '#5B8DEF', '#E85D75', '#4CAF9A', '#F2A93B',
  '#9B6EF3', '#3BC4E8', '#E8783B', '#6BBF59'
]

function pickColor () {
  return PARTICIPANT_COLORS[Math.floor(Math.random() * PARTICIPANT_COLORS.length)]
}

function pickDisplayName () {
  return 'Guest ' + Math.floor(Math.random() * 9000 + 100)
}

// -----------------------------------------------------------------------
// DOM scaffolding (header bar, banners, participant panel, editor host)
// -----------------------------------------------------------------------

const root = document.getElementById('app')

const header = document.createElement('div')
header.className = 'wt-header'

const filePathEl = document.createElement('div')
filePathEl.className = 'wt-filepath'
filePathEl.textContent = fileName
filePathEl.title = filePath
header.appendChild(filePathEl)

const headerRight = document.createElement('div')
headerRight.className = 'wt-header-right'
header.appendChild(headerRight)

const readonlyBadge = document.createElement('div')
readonlyBadge.className = 'wt-badge wt-badge-readonly'
readonlyBadge.textContent = '👁 read-only'
readonlyBadge.title = 'You can view and follow along, but not edit.'
readonlyBadge.style.display = 'none'
headerRight.appendChild(readonlyBadge)

const statusEl = document.createElement('div')
statusEl.className = 'wt-status'
statusEl.textContent = 'connecting'
headerRight.appendChild(statusEl)

const participantsEl = document.createElement('div')
participantsEl.className = 'wt-participants'
headerRight.appendChild(participantsEl)

const editorHost = document.createElement('div')
editorHost.id = 'wt-editor'
editorHost.className = 'wt-editor'

root.appendChild(header)
root.appendChild(editorHost)

if (role === 'read') {
  readonlyBadge.style.display = 'inline-flex'
}

// -----------------------------------------------------------------------
// Monaco editor
// -----------------------------------------------------------------------

const monacoNs = window.monaco
const editorModel = monacoNs.editor.createModel(initialContent, language)
const editor = monacoNs.editor.create(editorHost, {
  model: editorModel,
  automaticLayout: true,
  readOnly: role === 'read',
  minimap: { enabled: true },
  theme: 'vs-dark'
})

// -----------------------------------------------------------------------
// Yjs + y-websocket wiring
// -----------------------------------------------------------------------

const ydoc = new Y.Doc()
const ytext = ydoc.getText('monaco')

const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
const wsBase = wsProtocol + '//' + window.location.host

const provider = new WebsocketProvider(
  wsBase + '/v1/sessions/' + sessionId,
  'connect',
  ydoc,
  {
    params: { token },
    disableBc: true
  }
)

const awareness = provider.awareness

// y-monaco / the awareness convention expects the display name and color
// nested inside a "user" object (specification.md §4.2), not as flat fields.
awareness.setLocalState({
  user: { name: pickDisplayName(), color: pickColor() },
  role,
  cursor: null,
  selection: null
})

// eslint-disable-next-line no-new
new MonacoBinding(ytext, editorModel, new Set([editor]), awareness)

editor.onDidChangeCursorPosition((e) => {
  const local = awareness.getLocalState() || {}
  awareness.setLocalState({
    ...local,
    cursor: { line: e.position.lineNumber, column: e.position.column }
  })
})

editor.onDidChangeCursorSelection((e) => {
  const local = awareness.getLocalState() || {}
  awareness.setLocalState({
    ...local,
    selection: {
      startLine: e.selection.startLineNumber,
      startColumn: e.selection.startColumn,
      endLine: e.selection.endLineNumber,
      endColumn: e.selection.endColumn
    }
  })
})

// -----------------------------------------------------------------------
// Participant panel
// -----------------------------------------------------------------------

function renderParticipants () {
  const states = awareness.getStates()
  participantsEl.innerHTML = ''
  states.forEach((state) => {
    if (!state || !state.user || !state.user.name) return
    const chip = document.createElement('div')
    chip.className = 'wt-participant'

    const dot = document.createElement('span')
    dot.className = 'wt-participant-dot'
    dot.style.backgroundColor = state.user.color || '#888'
    chip.appendChild(dot)

    const name = document.createElement('span')
    name.className = 'wt-participant-name'
    name.textContent = state.user.name
    chip.appendChild(name)

    participantsEl.appendChild(chip)
  })
}

renderParticipants()
awareness.on('change', renderParticipants)

// -----------------------------------------------------------------------
// Connection status + close-code handling
// -----------------------------------------------------------------------

const EXPIRED = 4001
const REVOKED = 4002
const ENDED = 4003
const INVALID = 4004

const TERMINAL_MESSAGES = {
  [EXPIRED]: 'This link has expired.',
  [REVOKED]: 'This link was revoked by the host.',
  [ENDED]: 'This session has ended.',
  [INVALID]: 'This link is invalid.'
}

function showTerminalMessage (message) {
  document.body.innerHTML = ''
  const wrap = document.createElement('div')
  wrap.className = 'wt-terminal'
  const box = document.createElement('div')
  box.className = 'wt-terminal-box'
  box.textContent = message
  wrap.appendChild(box)
  document.body.appendChild(wrap)
}

// If we never manage a single successful connection after a handful of
// attempts, something more permanent is wrong (link died between page load
// and now, proxy dropping the upgrade, server unreachable, ...). Reloading
// re-runs the server-side link check in GuestPageController, which will
// either show a clean "expired/revoked/ended" page or just try again with a
// clean slate - better than looping "reconnecting" forever with no way out.
const MAX_ATTEMPTS_BEFORE_RELOAD = 5
let attemptsSinceConnected = 0

provider.on('status', ({ status }) => {
  statusEl.textContent = status
  statusEl.className = 'wt-status wt-status-' + status
  if (status === 'connected') {
    attemptsSinceConnected = 0
  }
})

provider.on('connection-close', (event) => {
  const code = event && event.code
  if (code && TERMINAL_MESSAGES[code]) {
    provider.destroy()
    showTerminalMessage(TERMINAL_MESSAGES[code])
    return
  }
  // Anything else (network blip, server restart, ...): let the provider's
  // built-in exponential-backoff reconnect proceed; the status pill above
  // already reflects "connecting"/"disconnected" - unless it's been failing
  // for a while, in which case fall back to a reload (see comment above).
  attemptsSinceConnected += 1
  if (attemptsSinceConnected >= MAX_ATTEMPTS_BEFORE_RELOAD) {
    window.location.reload()
  }
})
