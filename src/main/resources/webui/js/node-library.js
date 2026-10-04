/* ═══════════════════════════════════════════════════════════════
   Node Library — where nodes are found: every node type under its
   category, and a search across all of them.

   index() and search() know nothing of the page, so they are tested
   on their own; render() draws what they return.
   ═══════════════════════════════════════════════════════════════ */
const NodeLibrary = (() => {

    /**
     * One entry per node type, category by category in the order the categories are given.
     * @param types node type → { title, desc }, as LiteGraph keeps its registered types
     * @param categories category prefix → { label, color }
     */
    function index(types, categories) {
        const entries = [];
        for (const [prefix, category] of Object.entries(categories)) {
            for (const [type, info] of Object.entries(types)) {
                if (!type.startsWith(prefix + "/")) continue;
                entries.push({ type, category: prefix, label: category.label, color: category.color, title: info.title, desc: info.desc || "",
                    unbudgeted: Boolean(info.unbudgeted) });
            }
        }
        return entries;
    }

    // How well one word of the query fits an entry: its title first, then its category, then what it does.
    function fit(entry, word) {
        const title = entry.title.toLowerCase();
        if (title.startsWith(word)) return 4;
        if (title.split(/[^a-z0-9]+/).some(part => part.startsWith(word))) return 3;
        if (title.includes(word)) return 2;
        if (entry.label.toLowerCase().includes(word) || entry.desc.toLowerCase().includes(word)) return 1;
        return 0;
    }

    /** The entries every word of the query fits, best first. An empty query keeps them all, in their order. */
    function search(entries, query) {
        const words = String(query || "").toLowerCase().split(/\s+/).filter(Boolean);
        if (words.length === 0) return entries.slice();
        const found = [];
        entries.forEach((entry, position) => {
            let score = 0;
            for (const word of words) {
                const points = fit(entry, word);
                if (points === 0) return;
                score += points;
            }
            found.push({ entry, score, position });
        });
        found.sort((a, b) => b.score - a.score || a.position - b.position);
        return found.map(item => item.entry);
    }

    // ── The list on the page ─────────────────────────────────────

    const OPEN_KEY = "carpetlogic.library.open";
    let entries = [];
    let open = new Set(["Control"]);
    let active = 0;             // the search result Enter adds
    let handlers = { add() {}, drag() {} };

    function init(types, categories, onAdd, onDrag) {
        entries = index(types, categories);
        handlers = { add: onAdd, drag: onDrag };
        try {
            const stored = JSON.parse(localStorage.getItem(OPEN_KEY));
            if (Array.isArray(stored)) open = new Set(stored);
        } catch (e) { /* nothing remembered */ }

        const input = document.getElementById("node-search");
        input.addEventListener("input", () => { active = 0; render(); });
        input.addEventListener("keydown", (e) => {
            const results = search(entries, input.value);
            if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                if (!input.value.trim() || results.length === 0) return;
                active = (active + (e.key === "ArrowDown" ? 1 : results.length - 1)) % results.length;
                render();
            } else if (e.key === "Enter") {
                e.preventDefault();
                if (input.value.trim() && results[active]) handlers.add(results[active].type);
            } else if (e.key === "Escape" && input.value) {
                e.stopPropagation();
                input.value = "";
                active = 0;
                render();
            }
        });
        render();
    }

    function render() {
        const list = document.getElementById("node-list");
        const query = document.getElementById("node-search").value.trim();
        list.replaceChildren();
        if (query) {
            const results = search(entries, query);
            if (results.length === 0) {
                list.append(el("p", "lib-empty", "No node matches “" + query + "”. Try what it does: walk, hit, wait, health."));
                return;
            }
            results.forEach((entry, position) => list.append(row(entry, position === active, true)));
            const chosen = list.children[active];
            if (chosen && chosen.scrollIntoView) chosen.scrollIntoView({ block: "nearest" });
            return;
        }
        let category = null;
        let group = null;
        for (const entry of entries) {
            if (entry.category !== category) {
                category = entry.category;
                group = section(entry, entries.filter(other => other.category === category).length);
                list.append(group.head, group.body);
            }
            if (open.has(category)) group.body.append(row(entry, false, false));
        }
    }

    function section(entry, count) {
        const isOpen = open.has(entry.category);
        const head = el("button", "lib-group" + (isOpen ? " open" : ""));
        head.type = "button";
        head.setAttribute("aria-expanded", String(isOpen));
        const swatch = el("span", "swatch");
        swatch.style.background = entry.color;
        head.append(el("span", "glyph-caret"), swatch, el("span", "lib-group-name", entry.label), el("span", "count", count));
        head.addEventListener("click", () => {
            if (open.has(entry.category)) open.delete(entry.category); else open.add(entry.category);
            try { localStorage.setItem(OPEN_KEY, JSON.stringify([...open])); } catch (e) { /* not remembered */ }
            render();
        });
        return { head, body: el("div", "lib-group-body") };
    }

    function row(entry, isActive, showCategory) {
        const item = el("button", "node-row" + (isActive ? " active" : ""));
        item.type = "button";
        item.setAttribute("role", "option");
        item.setAttribute("aria-selected", String(isActive));
        item.title = entry.desc;
        const swatch = el("span", "swatch");
        swatch.style.background = entry.color;
        const text = el("span", "node-row-text");
        const title = el("span", "node-row-title", entry.title);
        if (entry.unbudgeted) title.append(el("span", "chip warn", "not budgeted"));
        text.append(title, el("span", "node-row-desc", showCategory ? entry.label + " · " + entry.desc : entry.desc));
        item.append(swatch, text);
        item.addEventListener("click", () => handlers.add(entry.type));
        item.addEventListener("mousedown", () => handlers.drag(entry.type));
        return item;
    }

    function focus() {
        const input = document.getElementById("node-search");
        input.focus();
        input.select();
    }

    return { index, search, init, render, focus };
})();

if (typeof module !== "undefined") module.exports = NodeLibrary;
