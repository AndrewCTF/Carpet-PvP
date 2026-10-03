/**
 * CarpetLogic — API client
 *
 * Every request carries the token from the link that /carpetlogic open prints in game.
 * Server events arrive over a streamed fetch rather than EventSource, so the token travels
 * in a header and never in a URL the server sees.
 */
const API = (() => {

    const TOKEN_KEY = 'carpetlogic.token';
    let token = null;
    let listeners = {};       // event-type → [callback]
    let reconnectTimer = null;
    let streamAbort = null;
    let connected = false;

    // ── Token ────────────────────────────────────────────────────

    // The link carries the token in its fragment. Keep it for this tab and take it out of the address bar.
    function loadToken() {
        const match = /[#&]token=([A-Za-z0-9_-]+)/.exec(window.location.hash);
        if (match) {
            try { sessionStorage.setItem(TOKEN_KEY, match[1]); } catch (e) { /* storage unavailable */ }
            token = match[1];
            history.replaceState(null, '', window.location.pathname);
        } else {
            try { token = sessionStorage.getItem(TOKEN_KEY); } catch (e) { token = null; }
        }
        return token;
    }

    function hasToken() {
        return Boolean(token);
    }

    // ── Connection ───────────────────────────────────────────────

    function connect() {
        loadToken();
        if (!token) {
            _emit('unauthorized', 'No access token. Run /carpetlogic open in game and use the link it prints.');
            return;
        }
        _openStream();
    }

    function disconnect() {
        if (streamAbort) streamAbort.abort();
        if (reconnectTimer) clearTimeout(reconnectTimer);
        streamAbort = null;
        reconnectTimer = null;
        _setConnected(false);
    }

    function isConnected() {
        return connected;
    }

    function _setConnected(value) {
        if (connected === value) return;
        connected = value;
        _emit('connectionChange', value);
    }

    // ── Event stream ─────────────────────────────────────────────

    async function _openStream() {
        const abort = new AbortController();
        streamAbort = abort;
        try {
            const resp = await fetch('/api/events', {
                headers: { 'Authorization': 'Bearer ' + token },
                signal: abort.signal
            });
            if (resp.status === 401 || resp.status === 403) {
                _emit('unauthorized', await _errorText(resp));
                return;
            }
            if (!resp.ok || !resp.body) throw new Error('HTTP ' + resp.status);
            _setConnected(true);

            const reader = resp.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                let end;
                while ((end = buffer.indexOf('\n\n')) >= 0) {
                    _dispatch(buffer.slice(0, end));
                    buffer = buffer.slice(end + 2);
                }
            }
        } catch (err) {
            if (abort.signal.aborted) return;
        }
        _setConnected(false);
        reconnectTimer = setTimeout(_openStream, 3000);
    }

    function _dispatch(event) {
        const data = event.split('\n')
            .filter(line => line.startsWith('data: '))
            .map(line => line.slice(6))
            .join('\n');
        if (!data) return;
        try {
            const message = JSON.parse(data);
            if (message.type) _emit(message.type, message);
        } catch (err) {
            console.warn('[API] Unreadable event:', data);
        }
    }

    // ── Event bus ────────────────────────────────────────────────

    function on(event, callback) {
        if (!listeners[event]) listeners[event] = [];
        listeners[event].push(callback);
    }

    function off(event, callback) {
        if (!listeners[event]) return;
        listeners[event] = listeners[event].filter(cb => cb !== callback);
    }

    function _emit(event, data) {
        (listeners[event] || []).forEach(cb => {
            try { cb(data); } catch (e) { console.error('[API] Listener error:', e); }
        });
    }

    // ── REST ─────────────────────────────────────────────────────

    async function _errorText(resp) {
        const text = await resp.text().catch(() => '');
        try { return JSON.parse(text).error || text; } catch (e) { return text || resp.statusText; }
    }

    async function _fetch(path, options = {}) {
        if (!token) throw new Error('No access token. Run /carpetlogic open in game.');
        const opts = {
            method: options.method || 'GET',
            headers: { 'Authorization': 'Bearer ' + token }
        };
        if (options.body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(options.body);
        }
        const resp = await fetch(path, opts);
        if (!resp.ok) {
            const message = await _errorText(resp);
            if (resp.status === 401) _emit('unauthorized', message);
            throw new Error(message);
        }
        return resp.json();
    }

    const getStatus = () => _fetch('/api/status');
    const getSettings = () => _fetch('/api/settings');
    const getPrograms = () => _fetch('/api/programs');
    const getPresets = () => _fetch('/api/presets');
    const saveProgram = (program) => _fetch('/api/programs', { method: 'POST', body: program });
    const deleteProgram = (id) => _fetch('/api/programs/' + encodeURIComponent(id), { method: 'DELETE' });
    const getBots = () => _fetch('/api/bots');
    const spawnBot = (name) => _fetch('/api/bots/spawn', { method: 'POST', body: { name } });
    const killBot = (name) => _fetch('/api/bots/kill', { method: 'POST', body: { name } });
    const runProgram = (botName, name, actions) => _fetch('/api/execute', { method: 'POST', body: { botName, name, actions } });
    const stopProgram = (botName) => _fetch('/api/stop', { method: 'POST', body: { botName } });

    return {
        connect, disconnect, isConnected, hasToken, on, off,
        getStatus, getSettings, getPrograms, getPresets, saveProgram, deleteProgram,
        getBots, spawnBot, killBot, runProgram, stopProgram
    };

})();
