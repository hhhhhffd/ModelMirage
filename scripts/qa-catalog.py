"""Writes a localhost QA catalog: an OBJ sword with MTL and PNG, a GLB cube with an embedded PNG and the
same OBJ as a player model.

Usage: python3 scripts/qa-catalog.py <empty dir>, then serve it with `python3 -m http.server 8765 --bind 127.0.0.1`
and start the client with -Dmodelmirage.catalogUrl=http://localhost:8765/index.json.
"""
import struct, zlib, json, hashlib, os, sys
root = sys.argv[1]
def png(w, h, px):
    raw = b''.join(b'\x00' + bytes(sum((px(x, y) for x in range(w)), ())) for y in range(h))
    c = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
    return b'\x89PNG\r\n\x1a\n' + c(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + c(b'IDAT', zlib.compress(raw)) + c(b'IEND', b'')
# 4 quadrants: red TL, green TR, blue BL, yellow BR + white border lines -> shows UV orientation
def quad(x, y):
    if x % 16 == 0 or y % 16 == 0: return (255, 255, 255, 255)
    return [(220, 40, 40, 255), (40, 200, 60, 255), (40, 80, 230, 255), (240, 220, 40, 255)][(x >= 16) + 2 * (y >= 16)]
tex = png(32, 32, quad)
# sword-ish: long blade box + crossguard box, OBJ with mtl
def box(x0, y0, z0, x1, y1, z1, base):
    v = [(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
    faces = [(0,1,3,2),(4,6,7,5),(0,4,5,1),(2,3,7,6),(0,2,6,4),(1,5,7,3)]
    return v, [tuple(base + i for i in f) for f in faces]
verts, faces = [], []
for b in [(-0.1, 0, -0.03, 0.1, 1.6, 0.03), (-0.35, -0.05, -0.06, 0.35, 0.05, 0.06), (-0.05, -0.45, -0.05, 0.05, -0.05, 0.05)]:
    v, f = box(*b, len(verts) + 1); verts += v; faces += f
obj = 'mtllib Sword.mtl\nusemtl blade\n' + ''.join(f'v {x} {y} {z}\n' for x, y, z in verts) + 'vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n'
for i, f in enumerate(faces):
    if i == 6: obj += 'usemtl guard\n'
    obj += 'f ' + ' '.join(f'{a}/{k+1}' for k, a in enumerate(f)) + '\n'
mtl = 'newmtl blade\nKd 1 1 1\nmap_Kd textures/Quadrants.png\nnewmtl guard\nKd 0.9 0.6 0.1\n'
# GLB cube with embedded PNG
pos, uv, idx = [], [], []
cv, cf = box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5, 0)
for f in cf:
    b = len(pos)
    for k, a in enumerate(f): pos.append(cv[a]); uv.append([(0,1),(1,1),(1,0),(0,0)][k])
    idx += [b, b+1, b+2, b, b+2, b+3]
pb = b''.join(struct.pack('<3f', *p) for p in pos); ub = b''.join(struct.pack('<2f', *t) for t in uv); ib = b''.join(struct.pack('<H', i) for i in idx)
ib += b'\x00' * (-len(ib) % 4)
img = tex + b'\x00' * (-len(tex) % 4)
binc = pb + ub + ib + img
offs = [0, len(pb), len(pb) + len(ub), len(pb) + len(ub) + len(ib)]
gl = {"asset": {"version": "2.0"}, "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
      "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "TEXCOORD_0": 1}, "indices": 2, "material": 0}]}],
      "materials": [{"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}}], "textures": [{"source": 0}],
      "images": [{"bufferView": 3, "mimeType": "image/png"}],
      "buffers": [{"byteLength": len(binc)}],
      "bufferViews": [{"buffer": 0, "byteOffset": offs[0], "byteLength": len(pb)}, {"buffer": 0, "byteOffset": offs[1], "byteLength": len(ub)},
                      {"buffer": 0, "byteOffset": offs[2], "byteLength": len(idx) * 2}, {"buffer": 0, "byteOffset": offs[3], "byteLength": len(tex)}],
      "accessors": [{"bufferView": 0, "componentType": 5126, "count": len(pos), "type": "VEC3", "min": [-0.5]*3, "max": [0.5]*3},
                    {"bufferView": 1, "componentType": 5126, "count": len(uv), "type": "VEC2"},
                    {"bufferView": 2, "componentType": 5123, "count": len(idx), "type": "SCALAR"}]}
js = json.dumps(gl).encode(); js += b' ' * (-len(js) % 4)
glb = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(binc)) + struct.pack('<II', len(js), 0x4E4F534A) + js + struct.pack('<II', len(binc), 0x004E4942) + binc
files = {'models/sword/sword.obj': obj.encode(), 'models/sword/sword.mtl': mtl.encode(), 'models/sword/quadrants.png': tex, 'models/cube/cube.glb': glb}
for p, d in files.items():
    os.makedirs(os.path.dirname(os.path.join(root, p)), exist_ok=True); open(os.path.join(root, p), 'wb').write(d)
a = lambda p: {"path": p, "sha256": hashlib.sha256(files[p]).hexdigest(), "bytes": len(files[p])}
index = {"schema": 2, "models": [
  {"id": "qa-obj-sword", "name": "QA OBJ Sword", "kind": "item", "format": "obj", "group": "QA", "model": a('models/sword/sword.obj'),
   "textures": {"modelmirage:qa/quadrants": a('models/sword/quadrants.png')}, "parents": {"modelmirage:qa/sword.mtl": a('models/sword/sword.mtl')}},
  {"id": "qa-glb-cube", "name": "QA GLB Cube", "kind": "item", "format": "glb", "group": "QA", "model": a('models/cube/cube.glb')},
  {"id": "qa-obj-player", "name": "QA OBJ Player", "kind": "player", "format": "obj", "group": "QA", "model": a('models/sword/sword.obj'),
   "textures": {"modelmirage:qa/quadrants": a('models/sword/quadrants.png')}, "parents": {"modelmirage:qa/sword.mtl": a('models/sword/sword.mtl')}}]}
open(os.path.join(root, 'index.json'), 'w').write(json.dumps(index, indent=1))
