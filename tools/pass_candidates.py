#!/usr/bin/env python3
# Lists blocks that look see-through: their block models use texture areas with transparent pixels. The output is
# a checklist for picking which blocks bullets fly through (tools/bullet_masks.py, MeshShapes "generate").
# Reads blockstates -> models (with parents) -> element faces -> the texture area each face shows, from the
# Minecraft client jar and every mod jar (and the jars nested in them).
# Usage: tools/pass_candidates.py <minecraft client jar> <mods dir> <output.txt> [launcher assets/indexes/<n>.json]
import io, json, os, re, sys, zipfile, zlib, struct

def decode(data):
    i = 8; idat = b''; trns = b''; W = H = bd = ct = il = None
    while i < len(data):
        l = struct.unpack('>I', data[i:i+4])[0]; t = data[i+4:i+8]; d = data[i+8:i+8+l]
        if t == b'IHDR': W, H, bd, ct = struct.unpack('>IIBB', d[:10]); il = d[12]
        elif t == b'tRNS': trns = d
        elif t == b'IDAT': idat += d
        i += 12 + l
    if il != 0 or bd == 16: return None
    channels = {6: 4, 2: 3, 3: 1, 4: 2, 0: 1}[ct]
    bits = channels * bd
    bpp = max(1, bits // 8)
    stride = (W * bits + 7) // 8
    raw = zlib.decompress(idat); rows = []; prev = bytearray(stride)
    for y in range(H):
        f = raw[y*(stride+1)]; line = bytearray(raw[y*(stride+1)+1:(y+1)*(stride+1)])
        for x in range(stride):
            a = line[x-bpp] if x >= bpp else 0; b = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line)); prev = line
    def sample(r, x):
        # palette index or grey value of pixel x in a row of bd-bit samples (1 channel)
        if bd == 8: return r[x]
        per = 8 // bd; byte = r[x // per]; shift = 8 - bd * (x % per + 1)
        return (byte >> shift) & ((1 << bd) - 1)
    def alpha(x, y):
        r = rows[y]
        if ct == 6: return r[x*4+3]
        if ct == 4: return r[x*2+1]
        if ct == 3: k = sample(r, x); return trns[k] if k < len(trns) else 255
        if ct == 0 and len(trns) >= 2:
            g = sample(r, x); return 0 if g == struct.unpack('>H', trns[:2])[0] else 255
        return 255
    return W, H, alpha

# every file under assets/, later jars do not replace earlier ones
files = {}
def add_jar(z, label):
    for n in z.namelist():
        if n.startswith('assets/') and n not in files: files[n] = (z, label)
        if n.startswith('META-INF/jarjar/') and n.endswith('.jar'):
            try: add_jar(zipfile.ZipFile(io.BytesIO(z.read(n))), label + '>' + os.path.basename(n))
            except zipfile.BadZipFile: pass

add_jar(zipfile.ZipFile(sys.argv[1]), 'minecraft')
for f in sorted(os.listdir(sys.argv[2])):
    if f.endswith('.jar'):
        try: add_jar(zipfile.ZipFile(os.path.join(sys.argv[2], f)), f)
        except zipfile.BadZipFile: pass

def read(path):
    e = files.get(path)
    return e[0].read(path) if e else None

def rid(s, kind):
    ns, _, p = s.partition(':') if ':' in s else ('minecraft', '', s)
    return 'assets/%s/%s/%s' % (ns, kind, p)

models = {}
def model(mid):
    if mid in models: return models[mid]
    models[mid] = None
    data = read(rid(mid, 'models') + '.json')
    if data is None: return None
    try: m = json.loads(data)
    except ValueError: return None
    tex = {}; elements = None; chain = []
    parent = m.get('parent')
    if parent and not parent.startswith('builtin/'):
        pm = model(parent)
        if pm:
            tex.update(pm['textures']); elements = pm['elements']; chain = pm['chain']
    tex.update(m.get('textures', {}) or {})
    if m.get('elements') is not None: elements = m['elements']
    out = {'textures': tex, 'elements': elements or [], 'chain': chain + [mid]}
    models[mid] = out
    return out

def resolve(tex, ref, depth=0):
    # 26.x: a texture may be {"sprite": id, ...}
    if isinstance(ref, dict): ref = ref.get('sprite')
    while isinstance(ref, str) and ref.startswith('#') and depth < 16:
        ref = tex.get(ref[1:]); depth += 1
        if isinstance(ref, dict): ref = ref.get('sprite')
    return ref if isinstance(ref, str) else None

textures = {}
def texture(tid):
    if tid in textures: return textures[tid]
    textures[tid] = None
    data = read(rid(tid, 'textures') + '.png')
    if data:
        try: textures[tid] = decode(data)
        except Exception: pass
    return textures[tid]

def default_uv(face, fr, to):
    x0, y0, z0 = fr; x1, y1, z1 = to
    return {'down': (x0, 16-z1, x1, 16-z0), 'up': (x0, z0, x1, z1),
            'north': (16-x1, 16-y1, 16-x0, 16-y0), 'south': (x0, 16-y1, x1, 16-y0),
            'west': (z0, 16-y1, z1, 16-y0), 'east': (16-z1, 16-y1, 16-z0, 16-y0)}[face]

def count(m):
    # pixels the model's faces show: (all, transparent, translucent)
    total = clear = half = 0
    for el in m['elements']:
        fr, to = el.get('from', [0]*3), el.get('to', [16]*3)
        for face, fd in (el.get('faces') or {}).items():
            t = texture(resolve(m['textures'], fd.get('texture', '')) or '')
            if not t: continue
            W, H, alpha = t; n = W  # animated: first square frame
            uv = fd.get('uv') or default_uv(face, fr, to)
            u0, u1 = sorted((uv[0], uv[2])); v0, v1 = sorted((uv[1], uv[3]))
            px0, px1 = int(u0 * n / 16), int(round(u1 * n / 16)); py0, py1 = int(v0 * n / 16), int(round(v1 * n / 16))
            for y in range(max(0, py0), min(n, H, py1)):
                for x in range(max(0, px0), min(W, px1)):
                    a = alpha(x, y); total += 1
                    if a < 26: clear += 1
                    elif a < 250: half += 1
    return total, clear, half

def full_cube(m):
    return any(el.get('from') == [0, 0, 0] and el.get('to') == [16, 16, 16] for el in m['elements'])

lang = {}
if len(sys.argv) > 4:
    # vanilla languages other than en_us are not in the client jar: launcher assets/indexes/<n>.json + objects/
    idx = json.load(open(sys.argv[4]))['objects'].get('minecraft/lang/ru_ru.json')
    if idx:
        h = idx['hash']
        obj = os.path.join(os.path.dirname(os.path.dirname(sys.argv[4])), 'objects', h[:2], h)
        lang.setdefault('ru_ru', {}).update(json.load(open(obj, encoding='utf-8')))
for code in ('en_us', 'ru_ru'):
    for path in [p for p in files if p.endswith('/lang/%s.json' % code)]:
        try:
            for k, v in json.loads(read(path)).items(): lang.setdefault(code, {}).setdefault(k, v)
        except ValueError: pass

# ids with no collision box: bullets already fly through them
NO_COLLISION = re.compile(r'(flower|sapling|(?<!_)grass$|short_grass|tall_grass|fern|vine|button|pressure_plate|sign$|banner|'
                          r'lever|tripwire|redstone_wire|torch|rail$|_rail|cobweb|(?<!_)mushroom$|coral(?!_block)|dead_bush|'
                          r'sugar_cane|kelp|seagrass|roots|sprouts|_crop|carrots|potatoes|beetroots|wheat$|_stem|propagule|'
                          r'lichen|moss_carpet|petals|hanging_moss|bush$|dandelion|poppy|tulip|orchid|allium|bluet|daisy|'
                          r'cornflower|lily_of|rose|peony|lilac|sunflower|pitcher|torchflower|frogspawn|nether_wart|fire$|portal$|'
                          r'light$|structure_void|air$|lily_pad|spore_blossom|eyeblossom|leaf_litter|cactus_flower|firefly_bush|wildflowers)')
NO_COLLISION_PARENTS = ('block/cross', 'block/tinted_cross', 'block/crop', 'block/stem_growth', 'block/template_torch',
                        'block/rail_flat', 'block/template_rail_raised', 'block/flower_pot_cross')

rows = []
for path in sorted(p for p in files if re.match(r'assets/[^/]+/blockstates/[^/]+\.json$', p)):
    ns = path.split('/')[1]; name = path.split('/')[-1][:-5]
    try: bs = json.loads(read(path))
    except ValueError: continue
    mids = []
    def take(v):
        for x in (v if isinstance(v, list) else [v]):
            if isinstance(x, dict) and 'model' in x: mids.append(x['model'])
    for v in (bs.get('variants') or {}).values(): take(v)
    for part in bs.get('multipart') or []: take(part.get('apply'))
    total = clear = half = 0; cube = False; chains = set()
    for mid in dict.fromkeys(mids):
        m = model(mid)
        if not m: continue
        t, c, h = count(m); total += t; clear += c; half += h
        cube |= full_cube(m); chains.update(m['chain'])
    if total == 0 or (clear + half) * 100 < total * 3: continue
    bid = '%s:%s' % (ns, name)
    key = 'block.%s.%s' % (ns, name)
    title = lang.get('ru_ru', {}).get(key) or lang.get('en_us', {}).get(key) or ''
    nocol = bool(NO_COLLISION.search(name)) or any(any(c.endswith(p) for p in NO_COLLISION_PARENTS) for c in chains)
    rows.append((nocol, ns, -clear * 100 // total, bid, title, clear * 100 // total, half * 100 // total, cube))

# already flown through: masks (tools/bullet_masks.py) and generated shapes
done = set()
here = os.path.dirname(os.path.abspath(__file__))
for f, pick in (('ntmgunfeel-bullet-masks.properties', lambda k: k.rsplit('.', 1)[0]),
                ('ntmgunfeel-bullet-shapes.properties', lambda k: k.split('[')[0])):
    try:
        for line in open(os.path.join(here, '..', 'res', f), encoding='utf-8'):
            line = line.strip()
            if not line or line.startswith('#'): continue
            k, _, v = line.partition('=')
            k = k.strip().replace('\\:', ':')
            if k == 'generate': done.update(x.strip() for x in v.split(','))
            else: done.add(pick(k))
    except OSError: pass

# what I suggest keeps stopping bullets: glass and other see-through solids, ores (transparent only in an overlay
# layer), machines and decorations whose model is mostly a solid body
SOLID = re.compile(r'(glass|laminate|[:_]ice$|slime_block|honey_block|barrier|chlorine_gas|beacon|spawner|vault|ore_|radgem|'
                   r'^ae2:|^flywheel:|^ftbquests:|radiorec|broadcaster|crane_router|foundry_|cm_tank|red_cable_gauge|'
                   r'sky_stone_tank|cooking_pot|skillet|_block$|cactus$|sculk_shrieker|sculk_sensor)')

with open(sys.argv[3], 'w') as out:
    out.write('# Блоки, у моделей которых есть прозрачные пиксели (tools/pass_candidates.py).\n'
              '# Строка без # станет простреливаемой: пуля летит сквозь прозрачные пиксели модели и упирается в\n'
              '# непрозрачные, как у железных прутьев. Строка с # в начале держит пулю, как сейчас.\n'
              '# Я разложил по своему мнению; переставь # где не согласен (раздел значения не имеет).\n'
              '# Колонки: id | название | прозрачно %% | полупрозрачно %% (стекло) | куб = полный блок\n'
              '# Уже простреливаются и сюда не попали: %s\n' % ', '.join(sorted(done)))
    groups = [('ПРЕДЛАГАЮ ПРОСТРЕЛИВАЕМЫМИ', ''), ('ПРЕДЛАГАЮ ОСТАВИТЬ ТВЁРДЫМИ (стекло, руды, машины...)', '# '),
              ('СКОРЕЕ ВСЕГО БЕЗ КОЛЛИЗИИ: пуля их и так пролетает, можно не смотреть', '# ')]
    for gi, (title, mark) in enumerate(groups):
        out.write('\n\n# ===== %s =====\n' % title)
        ns_now = None
        for nocol, ns, _, bid, name, clear, half, cube in sorted(rows):
            if bid in done: continue
            g = 2 if nocol else 1 if SOLID.search(bid) or clear < 3 else 0
            if g != gi: continue
            if ns != ns_now:
                out.write('\n# ----- %s -----\n' % ns); ns_now = ns
            out.write('%s%s | %s | %d%% | %d%% |%s\n' % (mark, bid, name, clear, half, ' куб' if cube else ''))
print(len(rows), 'blocks,', sum(1 for r in rows if not r[0]), 'with collision (probably)')
