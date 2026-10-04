/**
 * CarpetLogic — API client
 *
 * Every request carries a session token: the one in the link /carpetlogic open prints in game, or the one
 * the admin sign-in answers with. Server events arrive over a streamed fetch rather than EventSource, so the
 * token travels in a header and never in a URL the server sees.
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
            setToken(match[1]);
            history.replaceState(null, '', window.location.pathname);
        } else {
            try { token = sessionStorage.getItem(TOKEN_KEY); } catch (e) { token = null; }
        }
        return token;
    }

    function setToken(value) {
        token = value || null;
        try {
            if (token) sessionStorage.setItem(TOKEN_KEY, token); else sessionStorage.removeItem(TOKEN_KEY);
        } catch (e) { /* storage unavailable */ }
    }

    function hasToken() {
        return Boolean(token);
    }

    // ── Connection ───────────────────────────────────────────────

    function connect() {
        if (!token) loadToken();
        if (!token) {
            _emit('unauthorized', { message: 'This page has no session.' });
            return;
        }
        disconnect();
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
                _refused(resp.status, await _body(resp));
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
        if (abort.signal.aborted) return;
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

    // What the server answered, as an object: its JSON, or its text as the error.
    async function _body(resp) {
        const text = await resp.text().catch(() => '');
        try {
            const body = JSON.parse(text);
            return body && typeof body === 'object' ? body : { error: text };
        } catch (e) {
            return { error: text || resp.statusText };
        }
    }

    // A 401 means the token is no session any more; a 403 on the stream that its player may not use it now.
    function _refused(status, body) {
        if (status === 401) setToken(null);
        disconnect();
        _emit('unauthorized', { message: body.error, adminLogin: Boolean(body.adminLogin), denied: status === 403 });
    }

    /** Sends a request with the token and answers { ok, status, body } whatever the status. */
    async function _send(path, options = {}) {
        const opts = { method: options.method || 'GET', headers: {} };
        if (token && options.anonymous !== true) opts.headers['Authorization'] = 'Bearer ' + token;
        if (options.body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(options.body);
        }
        const resp = await fetch(path, opts);
        return { ok: resp.ok, status: resp.status, body: await _body(resp) };
    }

    async function _fetch(path, options = {}) {
        if (!token) throw new Error('This page has no session');
        const answer = await _send(path, options);
        if (!answer.ok) {
            if (answer.status === 401) _refused(401, answer.body);
            throw new Error(answer.body.error || 'HTTP ' + answer.status);
        }
        return answer.body;
    }

    const getStatus = () => _fetch('/api/status');
    const getSettings = () => _fetch('/api/settings');
    const getSchema = () => _fetch('/api/schema');
    const getPrograms = () => _fetch('/api/programs');
    const getPresets = () => _fetch('/api/presets');
    const saveProgram = (program) => _fetch('/api/programs', { method: 'POST', body: program });
    const deleteProgram = (id) => _fetch('/api/programs/' + encodeURIComponent(id), { method: 'DELETE' });
    const getBots = () => _fetch('/api/bots');
    const getMatches = () => _fetch('/api/matches');
    const spawnBot = (name) => _fetch('/api/bots/spawn', { method: 'POST', body: { name } });
    const removeBot = (name) => _fetch('/api/bots/remove', { method: 'POST', body: { name } });
    // One combat setting of one bot, by the name /player <name> ai takes.
    const setBotConfig = (name, key, value) => _fetch('/api/bots/config', { method: 'POST', body: { name, key, value } });
    const tpBot = (name) => _fetch('/api/bots/tp', { method: 'POST', body: { name } });
    const runProgram = (botName, name, actions) => _fetch('/api/execute', { method: 'POST', body: { botName, name, actions } });
    const stopProgram = (botName) => _fetch('/api/stop', { method: 'POST', body: { botName } });

    // ── Getting in and out ───────────────────────────────────────

    /** Asks without a token, which the server refuses, and reads from the refusal whether it offers the admin sign-in. */
    async function offersSignIn() {
        const answer = await _send('/api/status', { anonymous: true });
        return Boolean(answer.body.adminLogin);
    }

    /** Signs an admin in. Answers { ok, status, body }; when ok, the session is this page's from then on. */
    async function signIn(name, password) {
        const answer = await _send('/api/login', { method: 'POST', body: { name, password }, anonymous: true });
        if (answer.ok && answer.body.token) {
            // A session this page had before, from a link, is ended rather than left behind.
            await signOut();
            setToken(answer.body.token);
        }
        return answer;
    }

    const setPassword = (ticket, name, password) =>
        _send('/api/password', { method: 'POST', body: { ticket, name, password }, anonymous: true });

    /** Ends the session on the server and forgets it here. */
    async function signOut() {
        const had = token;
        setToken(null);
        disconnect();
        if (!had) return;
        try {
            await fetch('/api/logout', { method: 'POST', headers: { 'Authorization': 'Bearer ' + had } });
        } catch (e) { /* the server is away; the token is forgotten here all the same */ }
    }

    /** Changes a rule as the admin this session belongs to. Answers { ok, status, body } with the rule as it now is. */
    const setRule = (rule, value) => _send('/api/settings', { method: 'POST', body: { rule, value } });

    return {
        connect, disconnect, isConnected, hasToken, loadToken, on, off,
        getStatus, getSettings, getSchema, getPrograms, getPresets, saveProgram, deleteProgram,
        getBots, getMatches, spawnBot, removeBot, setBotConfig, tpBot, runProgram, stopProgram,
        offersSignIn, signIn, setPassword, signOut, setRule
    };

})();
