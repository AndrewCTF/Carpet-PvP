/**
 * CarpetLogic — API Client
 *
 * REST + SSE client for communicating with the CarpetLogic mod web server.
 * Automatically reconnects SSE on disconnect.
 */

const API = (() => {

    let baseUrl = '';
    let eventSource = null;
    let listeners = {};       // event-type → [callback]
    let reconnectTimer = null;
    let connected = false;

    // ── Initialisation ───────────────────────────────────────────

    /**
     * Set the server base URL and open the SSE stream.
     * @param {string} url e.g. "http://localhost:9876"
     */
    function connect(url) {
        baseUrl = url.replace(/\/+$/, '');
        _openSSE();
    }

    function disconnect() {
        if (eventSource) {
            eventSource.close();
            eventSource = null;
        }
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        connected = false;
        _emit('connectionChange', false);
    }

    function isConnected() {
        return connected;
    }

    // ── SSE Stream ───────────────────────────────────────────────

    function _openSSE() {
        if (eventSource) eventSource.close();

        try {
            eventSource = new EventSource(baseUrl + '/ws');

            eventSource.onopen = () => {
                connected = true;
                _emit('connectionChange', true);
                console.log('[API] SSE connected');
            };

            eventSource.onmessage = (e) => {
                try {
                    const data = JSON.parse(e.data);
                    if (data.type) {
                        _emit(data.type, data);
                    }
                    _emit('message', data);
                } catch (err) {
                    console.warn('[API] Failed to parse SSE message:', e.data);
                }
            };

            eventSource.onerror = () => {
                connected = false;
                _emit('connectionChange', false);
                eventSource.close();
                eventSource = null;
                // Auto-reconnect in 3 seconds
                reconnectTimer = setTimeout(() => _openSSE(), 3000);
            };
        } catch (err) {
            console.error('[API] SSE connection failed:', err);
            reconnectTimer = setTimeout(() => _openSSE(), 3000);
        }
    }

    // ── Event Bus ────────────────────────────────────────────────

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

    // ── REST Helpers ─────────────────────────────────────────────

    async function _fetch(path, options = {}) {
        const url = baseUrl + path;
        const defaults = {
            headers: { 'Content-Type': 'application/json' }
        };
        const opts = { ...defaults, ...options };
        if (opts.body && typeof opts.body === 'object') {
            opts.body = JSON.stringify(opts.body);
        }
        const resp = await fetch(url, opts);
        if (!resp.ok) {
            const text = await resp.text().catch(() => '');
            throw new Error(`API ${resp.status}: ${text || resp.statusText}`);
        }
        const ct = resp.headers.get('content-type') || '';
        if (ct.includes('application/json')) {
            return resp.json();
        }
        return resp.text();
    }

    // ── API Methods ──────────────────────────────────────────────

    // -- Status --
    function getStatus() {
        return _fetch('/api/status');
    }

    // -- Programs --
    function getPrograms() {
        return _fetch('/api/programs');
    }

    function saveProgram(program) {
        return _fetch('/api/programs', {
            method: 'POST',
            body: program
        });
    }

    function deleteProgram(id) {
        return _fetch('/api/programs/' + encodeURIComponent(id), {
            method: 'DELETE'
        });
    }

    // -- Presets --
    function getPresets() {
        return _fetch('/api/presets');
    }

    // -- Bots --
    function getBots() {
        return _fetch('/api/bots');
    }

    function spawnBot(name, options = {}) {
        return _fetch('/api/bots/spawn', {
            method: 'POST',
            body: { name, ...options }
        });
    }

    function killBot(name) {
        return _fetch('/api/bots/kill', {
            method: 'POST',
            body: { name }
        });
    }

    // -- Execution --
    function executeProgram(botName, programId) {
        return _fetch('/api/execute', {
            method: 'POST',
            body: { botName, programId }
        });
    }

    function executeProgramDirect(botName, actions) {
        return _fetch('/api/execute', {
            method: 'POST',
            body: { botName, actions }
        });
    }

    function stopProgram(botName) {
        return _fetch('/api/stop', {
            method: 'POST',
            body: { botName }
        });
    }

    // -- Settings --
    function getSettings() {
        return _fetch('/api/settings');
    }

    // -- AI --
    function generateAI(params) {
        return _fetch('/api/ai/generate', {
            method: 'POST',
            body: params
        });
    }

    // ── Public ───────────────────────────────────────────────────

    return {
        connect,
        disconnect,
        isConnected,
        on,
        off,
        getStatus,
        getPrograms,
        saveProgram,
        deleteProgram,
        getPresets,
        getBots,
        spawnBot,
        killBot,
        executeProgram,
        executeProgramDirect,
        stopProgram,
        getSettings,
        generateAI
    };

})();
