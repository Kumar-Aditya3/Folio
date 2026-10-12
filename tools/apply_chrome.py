import io, re, json

P = r"shared/src/composeUi/kotlin/com/folio/reader/ui/theme/FolioArtDirection.kt"
picks = json.load(io.open("chrome_pick.json", encoding="utf-8"))
s = io.open(P, encoding="utf-8", newline="").read()

pat = re.compile(r"    FolioArtDirection\(\n(.*?)\n    \),", re.S)
count = 0


def repl(m):
    global count
    body = m.group(1)
    idm = re.search(r'id = "([^"]+)"', body).group(1)
    light = re.search(r"light = (true|false)", body).group(1) == "true"
    key = "%s:%s" % (idm, "L" if light else "D")
    hexes = picks[key]
    ins = "        navAccent = 0x%08X, mastheadAccent = 0x%08X, readerOverlayAccent = 0x%08X,\n" % tuple(hexes)
    newbody, n = re.subn(
        r" *navAccent = 0x[0-9A-Fa-f]{8}, mastheadAccent = 0x[0-9A-Fa-f]{8}, readerOverlayAccent = 0x[0-9A-Fa-f]{8},\n",
        ins, body, count=1)
    if n != 1:
        print("NO MATCH", key)
        return m.group(0)
    count += 1
    return "    FolioArtDirection(\n" + newbody + "\n    ),\n"


s2 = pat.sub(repl, s)
io.open(P, "w", encoding="utf-8", newline="").write(s2)
print("patched", count)
