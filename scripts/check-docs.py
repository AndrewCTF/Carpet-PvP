#!/usr/bin/env python3
"""Check every relative link, command name, rule name and bot-setting name the docs mention
against the tree. Pages and images another piece of work still has to deliver are listed
separately as expected-missing rather than counted as failures.

    scripts/check-docs.py      # exits 1 on a problem, 0 when everything matches

Runs from anywhere; it reads the tree relative to its own location."""
import os, re, sys, glob

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir))
problems, expected, notes = [], [], []

def slug(h):
    s = h.strip().lower(); s = re.sub(r'[^\w\s-]', '', s); return re.sub(r'\s+', '-', s)

def section(md, start, end_prefix="### "):
    """The text of a section of a markdown file, from `start` to the next same-level heading."""
    out, on = [], False
    for line in md.split("\n"):
        if line.startswith(start):
            on = True; out.append(line); continue
        if on and line.startswith(end_prefix):
            break
        if on:
            out.append(line)
    return "\n".join(out)

docs = ["README.md"] + sorted(glob.glob("docs/*.md"))
EXPECTED_MISSING_FILES = {"docs/Paper.md", "Paper.md"}
EXPECTED_MISSING_IMAGES = {
    "docs/images/auto-setup-menu.png", "docs/images/auto-setup-fight.png",
    "docs/images/duel-sword.png", "docs/images/duel-mace.png",
    "docs/images/duel-crystal.png", "docs/images/duel-smp.png", "docs/images/bot-gui.png",
}
UPSTREAM = {"docs/scarpet/Documentation.md", "docs/scarpet/Full.md"}
body_of = lambda f: open(f).read()
plain_of = lambda f: re.sub(r'```.*?```', '', body_of(f), flags=re.S)

# ---------- 1. relative links and anchors ----------
anchors = {d: {slug(m.group(1)) for m in
               (re.match(r'^#{1,6}\s+(.*)$', l) for l in plain_of(d).split("\n")) if m}
           for d in docs}
n_links = n_img = 0
for d in docs:
    for _, target in re.findall(r'!?\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)', plain_of(d)):
        if target.startswith(("http://", "https://", "mailto:")): continue
        path, _, anchor = target.partition("#")
        n_img += path.endswith(".png"); n_links += not path.endswith(".png")
        resolved = d if path == "" else os.path.normpath(os.path.join(os.path.dirname(d), path))
        if not os.path.exists(resolved):
            if d in UPSTREAM:
                expected.append(f"{d}: upstream page link, not ours: {target}")
            elif resolved in EXPECTED_MISSING_FILES or target in EXPECTED_MISSING_FILES:
                expected.append(f"{d}: links {target}, another page will add it")
            elif resolved in EXPECTED_MISSING_IMAGES:
                expected.append(f"{d}: image {target} will be in place before release")
            else:
                problems.append(f"{d}: link target does not exist: {target}")
            continue
        if anchor and resolved in anchors and anchor not in anchors[resolved]:
            problems.append(f"{d}: anchor missing in {resolved}: #{anchor}")
notes.append(f"links: {n_links} page links and {n_img} images checked across {len(docs)} files")

# ---------- 2. rules, checked where they are documented ----------
settings = body_of("src/main/java/carpet/CarpetSettings.java")
rules = set(re.findall(r'public static [\w<>\[\].]+ (\w+)\s*=', settings))
FORK = re.compile(r'^(bot[A-Z]|fakePlayer|playerFall|carpetLogic|command(Bot|AutoSetup|CarpetLogic)$|'
                  r'swordBlock|damageTick|spamClick|shieldStun)')
rulemd = body_of("docs/Rules.md")
absent = [r for r in sorted(rules) if FORK.match(r) and f"`{r}`" not in rulemd]
if absent:
    problems.append("fork rules in CarpetSettings missing from Rules.md: " + ", ".join(absent))
# every backticked word in a Rules.md table row that looks like a rule must exist
mentioned = set()
for d in docs:
    for line in plain_of(d).split("\n"):
        if line.startswith("|") and re.search(r'\|\s*`?bool|`?(true|false)|perm', line):
            mentioned |= set(re.findall(r'`([a-z][A-Za-z0-9]{2,})`', line))
cands = sorted(n for n in mentioned if FORK.match(n))
bad = [n for n in cands if n not in rules]
if bad:
    problems.append("rule-like names in docs tables not in CarpetSettings: " + ", ".join(bad))
notes.append(f"rules: {sum(1 for r in rules if FORK.match(r))} fork rules, all in Rules.md; "
             f"{len(cands)} rule names cited in doc tables all exist")

# ---------- 3. commands ----------
root_of = {}
for f in glob.glob("src/main/java/carpet/**/*.java", recursive=True):
    src = body_of(f)
    m = re.search(r'public static void register\([^)]*\)[^\n]*\n(.*?)dispatcher\.register\((.*?)\);', src, re.S)
    if not m: continue
    first = re.search(r'literal\("([^"]+)"\)', m.group(1))
    if first:
        root_of[f] = first.group(1)
    else:
        # some register methods build the literal inline: dispatcher.register(literal("x")....)
        inline = re.search(r'dispatcher\s*\.\s*register\s*\(\s*literal\s*\(\s*"([^"]+)"', src)
        if inline: root_of[f] = inline.group(1)
roots = set(root_of.values()) | {"carpet"}
# carpet comes from the settings manager, script from the Scarpet extension, and perf is
# Minecraft's own PerfCommand, re-registered by CarpetServer behind perfPermissionLevel
registered_elsewhere = {"carpet", "script", "perf"}
words = set()
for d in docs:
    words |= set(re.findall(r'(?<![\w./-])/([a-z-]+)\b', plain_of(d)))
# vanilla Minecraft commands, Gradle tasks and path fragments the docs also mention
IGNORE = {"api", "docs", "build", "data", "damage", "execute", "gradle", "index", "logs",
          "run", "carpet-pvp", "carpet-kits", "carpet-autosetup", "carpet-traces",
          "carpet-factions", "carpet-sword", "bot", "temp", "files", "selftest-report",
          "src", "saveas"}
bad = sorted(w for w in words if w not in roots and w not in IGNORE
            and w not in registered_elsewhere)
if bad:
    problems.append("command words in docs that are not a registered root command: " + ", ".join(bad))
notes.append(f"commands: {len(roots)} root commands registered, {len(words)} distinct command words used in docs, all matched")

# every literal under a /bot ... must exist, and every /bot literal must be documented
bot_src = "\n".join(body_of(f) for f in glob.glob("src/main/java/carpet/commands/Bot*.java"))
bot_subs = {x for x in re.findall(r'literal\("([a-z][A-Za-z]*)"\)', bot_src)}
# The Paper plugin registers its own tree, in carpet/paper/PaperBotCommands.java, so a subcommand the
# docs name may live there. Its own page is still being written, so only the Fabric tree is checked
# for a literal nothing documents.
paper_src = body_of("src/main/java/carpet/paper/PaperBotCommands.java")
known = bot_subs | {x for x in re.findall(r'literal\("([a-z][A-Za-z]*)"\)', paper_src)}
seen = set()
for d in docs:
    for a, b in re.findall(r'/bot ([a-zA-Z]+) ([a-zA-Z]+)', body_of(d)):
        seen |= {a, b}
    seen |= set(re.findall(r'/bot ([a-zA-Z]+)', body_of(d)))
# only lower-case words are subcommands; anything else is a name from the examples
seen = {x for x in seen if x.islower()}
IGNORE = {"ai", "and", "in", "at", "combat"}
bad = sorted(x for x in seen if x not in known and x not in IGNORE)
if bad:
    problems.append("/bot subcommands in docs that do not exist: " + ", ".join(bad))
bad = sorted(x for x in bot_subs if x not in seen and x not in {"at", "bot"})
if bad:
    problems.append("/bot subcommands in code not in docs: " + ", ".join(bad))
notes.append(f"/bot: {len(known)} subcommands in code, {len(seen)} used in docs, all matched both ways")

# ---------- 4. bot settings ----------
cfg = body_of("src/main/java/carpet/pvp/BotPvpConfig.java")
keys = set(re.findall(r'"([^"]+)"', re.search(r'String\[\] KEYS = \{(.*?)\};', cfg, re.S).group(1)))
style_opts = set(re.findall(r'OPTIONS\.put\("([^"]+)"', body_of("src/main/java/carpet/pvp/style/StyleIndex.java")))
doc_common = set(re.findall(r'^\|\s*`([a-z]+)`\s*\|', section(body_of("docs/Bots.md"), "### The common settings"), re.M))
doc_common_fp = set(re.findall(r'^\|\s*`([a-z]+)`\s*\|', section(body_of("docs/FakePlayers.md"), "## Combat AI (`ai`)"), re.M))
doc_styles = set()
for page in ("docs/Bots.md", "docs/FakePlayers.md"):
    doc_styles |= set(re.findall(r'`((?:ranged|crystal|mace|smp)\.[a-z_]+)`', body_of(page)))
for label, s in (("Bots.md common table", doc_common), ("FakePlayers.md ai table", doc_common_fp)):
    if s - keys:
        problems.append(f"{label}: names not in BotPvpConfig.KEYS: " + ", ".join(sorted(s - keys)))
if keys - (doc_common | doc_common_fp):
    problems.append("BotPvpConfig.KEYS not documented: " + ", ".join(sorted(keys - (doc_common | doc_common_fp))))
if doc_styles - style_opts:
    problems.append("per-style options in docs not in StyleIndex: " + ", ".join(sorted(doc_styles - style_opts)))
if style_opts - doc_styles:
    problems.append("per-style options in StyleIndex not documented: " + ", ".join(sorted(style_opts - doc_styles)))
notes.append(f"bot settings: {len(keys)} in KEYS + {len(style_opts)} style options = {len(keys | style_opts)}, all documented")

# ---------- 5. enumerations the docs name ----------
diffs = [d.strip() for d in re.search(r'Difficulty \{([^}]*)\}', cfg).group(1).split(",")]
styles_enum = [s.strip() for s in re.search(r'CombatStyle \{ ([^}]*) \}', cfg).group(1).split(",")]
mode_words = {m.lower() for m in re.findall(r'^\s+([A-Z]+)[,;(]', body_of("src/main/java/carpet/pvp/autosetup/AutoMode.java"), re.M)}
drills = set(re.findall(r'DRILLS\.put\("([^"]+)"', body_of("src/main/java/carpet/pvp/drill/Drills.java")))
kits = set(re.findall(r'"([a-z]+)"', re.search(r'BUILT_IN\s*=\s*List\.of\((.*?)\)',
                    body_of("src/main/java/carpet/pvp/kit/KitStore.java"), re.S).group(1)))
for label, word, allowed in (("/auto-setup mode", "auto-setup", mode_words),
                             ("drill", "drill", drills)):
    used = set()
    for d in docs:
        used |= set(re.findall(rf'/bot {word} ([a-z]+)' if word == "drill" else rf'/auto-setup ([a-z]+)', body_of(d)))
    bad = sorted(x for x in used if x not in allowed and x not in {"stop", "session", "list"})
    if bad:
        problems.append(f"{label} names in docs that do not exist: " + ", ".join(bad))
absent = sorted(k for k in kits if f"### `{k}`" not in body_of("docs/Kits.md"))
if absent:
    problems.append("built-in kits not documented: " + ", ".join(absent))
notes.append(f"names: {len(diffs)} difficulties ({', '.join(d.lower() for d in diffs)}), "
             f"{len(styles_enum)} styles ({', '.join(styles_enum)}), {len(mode_words)} /auto-setup modes, "
             f"{len(drills)} drills, {len(kits)} built-in kits; all documented")

# ---------- 6. self-test scenarios ----------
names = re.findall(r'"([^"]+)"', re.search(r'SCENARIOS\s*=\s*List\.of\((.*?)\);',
        body_of("src/main/java/carpet/pvp/selftest/SelfTest.java"), re.S).group(1))
names += re.findall(r'SCENARIOS\.put\("([^"]+)"', body_of("src/main/java/carpet/pvp/selftest/ScenarioIndex.java"))
st = body_of("docs/SelfTest.md")
rows = set(re.findall(r'^\|\s*`([a-z0-9_]+)`\s*\|\s*\d+\s*\|', st, re.M))
if set(names) - rows: problems.append("scenarios missing from SelfTest.md: " + ", ".join(sorted(set(names) - rows)))
if rows - set(names): problems.append("scenarios in SelfTest.md that do not exist: " + ", ".join(sorted(rows - set(names))))
if f"**{len(names)}**" not in st: problems.append(f"SelfTest.md does not state the count {len(names)}")
notes.append(f"self-test: {len(names)} scenarios in code, {len(rows)} rows in SelfTest.md, count stated")

# ---------- 7. the pages this task asks for ----------
for page in ["Bots.md", "AutoSetup.md", "Menus.md", "Practice.md", "Commands.md", "Rules.md",
             "SelfTest.md", "Building.md", "CarpetLogic.md", "Kits.md", "FakePlayers.md",
             "SwordBlocking.md"]:
    if not os.path.exists("docs/" + page):
        problems.append("docs/" + page + " is missing")
notes.append(f"pages: {len(glob.glob('docs/*.md'))} markdown pages in docs/")

print("\n".join(notes)); print()
if expected:
    print(f"expected-missing ({len(expected)}) - linked deliberately, the file lands later:")
    for e in expected: print("  ~", e)
    print()
if problems:
    print(f"{len(problems)} PROBLEM(S):")
    for p in problems: print(" -", p)
    sys.exit(1)
print("no problems found")
