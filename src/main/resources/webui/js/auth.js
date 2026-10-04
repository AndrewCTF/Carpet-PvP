/* ═══════════════════════════════════════════════════════════════
   Auth — the screen a page shows when it has no session: how to get
   a link from the game, the admin sign-in when the server offers it,
   and the page a set-password link opens.

   What decides, checks and words things is in functions that know
   nothing of the page, so they are tested on their own.
   ═══════════════════════════════════════════════════════════════ */
const Auth = (() => {

    const MIN_PASSWORD = 10;
    const MAX_PASSWORD = 128;

    // ── Deciding and wording ─────────────────────────────────────

    /** What a set-password link carries in its fragment, #setup=<ticket>&name=<name>, or null. */
    function parseSetup(hash) {
        const ticket = /[#&]setup=([A-Za-z0-9_-]+)/.exec(hash || "");
        const name = /[#&]name=([^&]+)/.exec(hash || "");
        if (!ticket || !name) return null;
        try {
            return { ticket: ticket[1], name: decodeURIComponent(name[1]) };
        } catch (e) {
            return null;
        }
    }

    /** Which of the screen's views a page is shown: "setup", "signin" or "none". */
    function viewFor(state) {
        if (state.setup) return "setup";
        return state.offersSignIn ? "signin" : "none";
    }

    /** Why a new password cannot be sent, or null when it can. The server checks the length again. */
    function passwordProblem(password, repeat) {
        if (password.length < MIN_PASSWORD) {
            return "A password needs at least " + MIN_PASSWORD + " characters. This one has " + password.length + ".";
        }
        if (password.length > MAX_PASSWORD) return "A password can have at most " + MAX_PASSWORD + " characters.";
        if (password !== repeat) return "The two passwords are not the same.";
        return null;
    }

    function waitText(seconds) {
        const time = seconds < 120 ? seconds + (seconds === 1 ? " second" : " seconds") : Math.ceil(seconds / 60) + " minutes";
        return "Too many attempts. Try again in " + time + ".";
    }

    /** What to tell somebody whose sign-in was not accepted. */
    function signInProblem(answer) {
        if (answer.status === 429) return waitText(Number(answer.body.retryAfter) || 5);
        if (answer.status === 401 && !answer.body.adminLogin && /token/i.test(answer.body.error || "")) {
            return "The admin sign-in has been turned off on this server.";
        }
        if (answer.status === 401 || answer.status === 400 || answer.status === 503) return answer.body.error;
        return "The server answered " + answer.status + ". Its log says why.";
    }

    /** What to tell somebody whose set-password link did not work. */
    function setupProblem(answer) {
        if (answer.status === 429) return waitText(Number(answer.body.retryAfter) || 5);
        if (answer.status === 401) return "The admin sign-in is turned off on this server, so this link cannot be used.";
        if (answer.status === 403 || answer.status === 400 || answer.status === 503 || answer.status === 500) return answer.body.error;
        return "The server answered " + answer.status + ". Its log says why.";
    }

    /** Whether what is typed here crosses a network unencrypted: plain HTTP to anything but this machine. */
    function overPlainHttp(location) {
        return location.protocol === "http:" && !["localhost", "127.0.0.1", "[::1]", "::1"].includes(location.hostname);
    }

    // ── The screen ───────────────────────────────────────────────

    let setup = null;           // the set-password link this page was opened with, until it has been used
    let enter = () => {};       // what to do once this page has a session
    let waitTimer = null;

    function $(id) { return document.getElementById(id); }

    function init(onEnter) {
        enter = onEnter;
        setup = parseSetup(window.location.hash);
        if (setup) history.replaceState(null, "", window.location.pathname);

        $("auth-host").textContent = window.location.host;
        $("auth-wire").classList.toggle("hidden", !overPlainHttp(window.location));
        $("auth-signin").addEventListener("submit", (e) => { e.preventDefault(); signIn(); });
        $("auth-setup").addEventListener("submit", (e) => { e.preventDefault(); setPassword(); });
        $("auth-back").addEventListener("click", () => {
            const again = $("auth-back").dataset.again === "true";
            close();
            if (again) enter();
        });
    }

    /** Whether this page was opened with a set-password link it has not used yet. */
    function hasSetup() {
        return setup !== null;
    }

    const TITLES = { checking: "CarpetLogic", none: "Open the editor from the game", signin: "Admin sign in", setup: "Set your admin password" };

    function show(view) {
        for (const name of ["checking", "none", "signin", "setup"]) $("auth-" + name).classList.toggle("hidden", name !== view);
        $("auth-title").textContent = TITLES[view];
        $("auth-screen").classList.remove("hidden");
        document.body.classList.add("signed-out");
        const first = { signin: $("auth-name").value ? "auth-password" : "auth-name", setup: "auth-new" }[view];
        if (first) $(first).focus();
    }

    /**
     * Shows the screen.
     * @param options reason: why the page is here; dismissible: there is a session to go back to;
     *                denied: the session is a real one that the server will not serve right now;
     *                offersSignIn: known already, otherwise the server is asked
     */
    async function open(options = {}) {
        fail("auth-error", null);
        fail("auth-setup-error", null);
        const back = $("auth-back");
        back.classList.toggle("hidden", !options.dismissible && !options.denied);
        back.textContent = options.denied ? "Try again" : "Back to the editor";
        back.dataset.again = String(Boolean(options.denied));

        if (setup) {
            $("auth-setup-name").textContent = setup.name;
            $("auth-setup-user").value = setup.name;
            show("setup");
            return;
        }
        show("checking");
        let offers = options.offersSignIn;
        if (offers === undefined && !options.denied) {
            try {
                offers = await API.offersSignIn();
            } catch (e) {
                $("auth-none-reason").textContent = "The server did not answer. Is it still running?";
                back.classList.remove("hidden");
                back.textContent = "Try again";
                back.dataset.again = "true";
                show("none");
                return;
            }
        }
        $("auth-none-reason").textContent = options.reason || "This page has no session.";
        $("auth-signin-reason").textContent = options.reason || "For the players who may change this server's Carpet rules.";
        show(viewFor({ setup: null, offersSignIn: offers && !options.denied }));
    }

    function close() {
        $("auth-screen").classList.add("hidden");
        document.body.classList.remove("signed-out");
        $("auth-password").value = "";
        $("auth-new").value = "";
        $("auth-repeat").value = "";
        clearInterval(waitTimer);
    }

    function isOpen() {
        return !$("auth-screen").classList.contains("hidden");
    }

    function fail(id, message) {
        $(id).textContent = message || "";
        $(id).classList.toggle("hidden", !message);
    }

    function busy(button, label) {
        if (!button.dataset.label) button.dataset.label = button.textContent;
        button.disabled = label !== null;
        button.textContent = label === null ? button.dataset.label : label;
    }

    // Keeps the button shut while the server would refuse anyway, and counts the wait down.
    function holdOff(button, errorId, seconds) {
        let left = seconds;
        clearInterval(waitTimer);
        const tick = () => {
            if (left <= 0) {
                clearInterval(waitTimer);
                busy(button, null);
                fail(errorId, null);
                return;
            }
            busy(button, "Wait " + left + " s");
            fail(errorId, waitText(left));
            left--;
        };
        tick();
        waitTimer = setInterval(tick, 1000);
    }

    async function signIn() {
        const name = $("auth-name").value.trim();
        const password = $("auth-password").value;
        if (!name || !password) {
            fail("auth-error", "Give your Minecraft name and your web password.");
            return;
        }
        const button = $("auth-submit");
        busy(button, "Signing in");
        let answer;
        try {
            answer = await API.signIn(name, password);
        } catch (e) {
            busy(button, null);
            fail("auth-error", "The server did not answer. Is it still running?");
            return;
        }
        busy(button, null);
        if (answer.ok) {
            close();
            enter();
            return;
        }
        $("auth-password").value = "";
        $("auth-password").focus();
        if (answer.status === 429) holdOff(button, "auth-error", Number(answer.body.retryAfter) || 5);
        else fail("auth-error", signInProblem(answer));
    }

    async function setPassword() {
        const password = $("auth-new").value;
        const problem = passwordProblem(password, $("auth-repeat").value);
        if (problem) {
            fail("auth-setup-error", problem);
            return;
        }
        const button = $("auth-setup-submit");
        busy(button, "Setting the password");
        let answer;
        try {
            answer = await API.setPassword(setup.ticket, setup.name, password);
            if (answer.ok) {
                const name = answer.body.name || setup.name;
                setup = null;
                const session = await API.signIn(name, password);
                busy(button, null);
                if (session.ok) {
                    close();
                    enter();
                } else {
                    // The password is set; only the sign-in after it did not go through.
                    $("auth-name").value = name;
                    await open({ reason: "Your password is set. Sign in with it.", offersSignIn: true });
                }
                return;
            }
        } catch (e) {
            busy(button, null);
            fail("auth-setup-error", "The server did not answer. Is it still running?");
            return;
        }
        busy(button, null);
        if (answer.status === 429) holdOff(button, "auth-setup-error", Number(answer.body.retryAfter) || 5);
        else fail("auth-setup-error", setupProblem(answer));
    }

    return { parseSetup, viewFor, passwordProblem, waitText, signInProblem, setupProblem, overPlainHttp,
        init, open, close, isOpen, hasSetup };
})();

if (typeof module !== "undefined") module.exports = Auth;
