/* ═══════════════════════════════════════════════════════════════
   Autosave — saves the open program by itself a short time after
   the last change, tries again when a save fails, and keeps what
   has not reached the server in the browser so that it can be
   offered back.

   Nothing here knows the page: the save, the clock and the
   browser's storage are handed in, so all of it is tested on its own.
   ═══════════════════════════════════════════════════════════════ */
const Autosave = (() => {

    /** How long after the last change a program is saved. */
    const QUIET_MILLIS = 2000;
    const FIRST_RETRY_MILLIS = 5000;
    const LONGEST_RETRY_MILLIS = 60000;
    const ENABLED_KEY = "carpetlogic.autosave";
    const COPIES_KEY = "carpetlogic.unsaved";
    const MAX_COPIES = 8;

    /**
     * @param hooks save(): saves the open program and resolves to { ok } or { ok: false, reason, conflict, offline };
     *              setTimer(callback, millis) and clearTimer(handle); changed(): called whenever the state did;
     *              failed(reason): called once when saving starts to fail, not on every retry after it
     */
    function create(hooks) {
        const state = {
            enabled: true,          // the switch in the page
            writable: true,         // false while the server takes no writes from this page (viewer mode)
            dirty: false,           // there is something the server does not have
            saving: false,
            failed: null,           // why the last save did not go through
            offline: false,         // ... because the server could not be reached
            conflict: false,        // ... because somebody else saved the program since; nothing is tried until that is settled
            retryMillis: FIRST_RETRY_MILLIS
        };
        let timer = null;
        let again = false;          // a change arrived while a save was under way

        function schedule(millis) {
            if (timer !== null) hooks.clearTimer(timer);
            timer = hooks.setTimer(() => { timer = null; run(); }, millis);
        }

        function cancel() {
            if (timer !== null) hooks.clearTimer(timer);
            timer = null;
        }

        function mayRun() {
            return state.enabled && state.writable && !state.conflict;
        }

        /** The program changed. It is saved once it has been left alone for a moment. */
        function changed() {
            state.dirty = true;
            if (state.saving) again = true;
            else if (mayRun()) schedule(QUIET_MILLIS);
            hooks.changed();
        }

        async function run() {
            if (state.saving) return state.saving;
            if (!state.dirty) return { ok: true };
            cancel();
            again = false;
            state.saving = hooks.save().catch((e) => ({ ok: false, reason: e && e.message ? e.message : String(e), offline: true }));
            hooks.changed();
            const result = await state.saving;
            state.saving = false;
            if (result.ok) {
                state.dirty = again;
                state.failed = null;
                state.offline = false;
                state.retryMillis = FIRST_RETRY_MILLIS;
                if (again && mayRun()) schedule(QUIET_MILLIS);
            } else {
                const first = state.failed === null;
                state.failed = result.reason || "the server refused it";
                state.offline = Boolean(result.offline);
                state.conflict = Boolean(result.conflict);
                if (mayRun()) {
                    schedule(state.retryMillis);
                    state.retryMillis = Math.min(LONGEST_RETRY_MILLIS, state.retryMillis * 2);
                }
                if (first) hooks.failed(state.failed);
            }
            hooks.changed();
            return result;
        }

        /** Saves now if there is anything to save and saving is on: before a run, and when the page is left. */
        function flush() {
            return state.dirty && mayRun() ? run() : Promise.resolve({ ok: !state.dirty });
        }

        /** Saves now because somebody asked, whether saving by itself is on or not. */
        function saveNow() {
            state.dirty = true;
            state.conflict = false;
            return run();
        }

        /** The program on the canvas is what the server has: it was just opened, or started anew. */
        function settled() {
            cancel();
            again = false;
            state.dirty = false;
            state.failed = null;
            state.offline = false;
            state.conflict = false;
            state.retryMillis = FIRST_RETRY_MILLIS;
            hooks.changed();
        }

        function setEnabled(on) {
            state.enabled = Boolean(on);
            if (!state.enabled) cancel();
            else if (state.dirty && mayRun() && !state.saving) schedule(QUIET_MILLIS);
            hooks.changed();
        }

        function setWritable(on) {
            if (state.writable === Boolean(on)) return;
            state.writable = Boolean(on);
            if (!state.writable) cancel();
            else if (state.dirty && mayRun() && !state.saving) schedule(QUIET_MILLIS);
            hooks.changed();
        }

        return { state, changed, flush, saveNow, settled, setEnabled, setWritable };
    }

    // ── The switch, remembered ───────────────────────────────────

    /** Whether saving by itself is on in this browser. It is, until somebody turns it off. */
    function readEnabled(storage) {
        try { return storage.getItem(ENABLED_KEY) !== "off"; } catch (e) { return true; }
    }

    function writeEnabled(storage, on) {
        try { storage.setItem(ENABLED_KEY, on ? "on" : "off"); } catch (e) { /* not remembered */ }
    }

    // ── What has not reached the server ──────────────────────────

    function copies(storage) {
        try {
            const kept = JSON.parse(storage.getItem(COPIES_KEY));
            return Array.isArray(kept) ? kept.filter(copy => copy && copy.key && copy.graph) : [];
        } catch (e) {
            return [];
        }
    }

    /**
     * Keeps a program's unsaved graph in the browser, in place of the copy it kept of that program before.
     * @param copy key: the program's id, or what stands for one that has none yet; name; graph; baseUpdatedAt; at
     * @return whether it was kept: a browser may have no room, or no storage at all
     */
    function keep(storage, copy) {
        const kept = copies(storage).filter(other => other.key !== copy.key);
        kept.unshift(copy);
        try {
            storage.setItem(COPIES_KEY, JSON.stringify(kept.slice(0, MAX_COPIES)));
            return true;
        } catch (e) {
            return false;
        }
    }

    /** Forgets the copy of a program: the server has it now, or its owner threw it away. */
    function forget(storage, key) {
        const kept = copies(storage);
        if (!kept.some(copy => copy.key === key)) return;
        try { storage.setItem(COPIES_KEY, JSON.stringify(kept.filter(copy => copy.key !== key))); } catch (e) { /* nothing to do */ }
    }

    /** The copies this browser holds, newest first. */
    function kept(storage) {
        return copies(storage).sort((a, b) => (b.at || 0) - (a.at || 0));
    }

    return { create, readEnabled, writeEnabled, keep, forget, kept, QUIET_MILLIS, FIRST_RETRY_MILLIS, LONGEST_RETRY_MILLIS, MAX_COPIES };
})();

if (typeof module !== "undefined") module.exports = Autosave;
