#!/usr/bin/env python3
"""Offline preview of the OpenGL 3D map renderer.

Mirrors Map3DRenderer's camera (perspective + orbit), wall extrusion, path ribbon
and colours so we can tune the look without the phone. Outputs a PNG.
"""
import json
import sys
from collections import deque
import numpy as np
from PIL import Image

JSON = "app/src/main/res/raw/floor_plan_mask_labels.json"

# ---- Tunable params (keep in sync with Map3DRenderer / MainActivity) ----
AZIMUTH = float(sys.argv[1]) if len(sys.argv) > 1 else 0.6
ELEV_DEG = float(sys.argv[2]) if len(sys.argv) > 2 else 52.0
RADIUS = float(sys.argv[3]) if len(sys.argv) > 3 else 2.4
WALL_CELLS = float(sys.argv[4]) if len(sys.argv) > 4 else 60.0
DILATE = int(sys.argv[5]) if len(sys.argv) > 5 else 2
FOVY = 45.0
W, H = 540, 1180

FLOOR = np.array([0.925, 0.933, 0.949])
WALL = np.array([0.255, 0.275, 0.318])
PATH_CORE = np.array([0.149, 0.471, 1.0])
PATH_CASE = np.array([0.94, 0.96, 1.0])
BG = np.array([0.078, 0.094, 0.118])
LIGHT = np.array([-0.4, 1.0, -0.55])
LIGHT = LIGHT / np.linalg.norm(LIGHT)


def load():
    j = json.load(open(JSON))
    rows, cols, data = j["rows"], j["cols"], j["data"]
    grid = np.zeros((rows, cols), np.uint8)
    for y in range(rows):
        grid[y] = np.frombuffer(data[y].encode(), np.uint8) - ord("0")
    return grid, cols, rows


def drop_small_components(isw, min_area):
    rows, cols = isw.shape
    lab = np.zeros_like(isw, np.int32)
    cur = 0
    out = isw.copy()
    visited = np.zeros_like(isw)
    for sy in range(rows):
        for sx in range(cols):
            if not isw[sy, sx] or visited[sy, sx]:
                continue
            cur += 1
            comp = []
            dq = deque([(sx, sy)])
            visited[sy, sx] = True
            while dq:
                x, y = dq.popleft()
                comp.append((x, y))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < cols and 0 <= ny < rows and isw[ny, nx] and not visited[ny, nx]:
                        visited[ny, nx] = True
                        dq.append((nx, ny))
            if len(comp) < min_area:
                for (x, y) in comp:
                    out[y, x] = False
    return out


def wall_rects(grid, cols, rows, dilate):
    nonwalk = grid == 3
    ext = np.zeros_like(nonwalk)
    dq = deque()
    def push(x, y):
        if 0 <= x < cols and 0 <= y < rows and nonwalk[y, x] and not ext[y, x]:
            ext[y, x] = True; dq.append((x, y))
    for x in range(cols): push(x, 0); push(x, rows - 1)
    for y in range(rows): push(0, y); push(cols - 1, y)
    while dq:
        x, y = dq.popleft()
        push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1)
    trav = (grid == 1) | (grid == 2)
    tt = np.zeros_like(nonwalk)
    tt[:, :-1] |= trav[:, 1:]; tt[:, 1:] |= trav[:, :-1]
    tt[:-1, :] |= trav[1:, :]; tt[1:, :] |= trav[:-1, :]
    isw = nonwalk & ((~ext) | tt)
    isw = drop_small_components(isw, 40)
    for _ in range(dilate):
        d = isw.copy()
        d[:, :-1] |= isw[:, 1:]; d[:, 1:] |= isw[:, :-1]
        d[:-1, :] |= isw[1:, :]; d[1:, :] |= isw[:-1, :]
        isw = d
    # greedy mesh
    used = np.zeros_like(isw)
    rects = []
    for y in range(rows):
        x = 0
        while x < cols:
            if not isw[y, x] or used[y, x]: x += 1; continue
            rw = 1
            while x + rw < cols and isw[y, x + rw] and not used[y, x + rw]: rw += 1
            rh = 1
            ok = True
            while y + rh < rows and ok:
                if used[y + rh, x:x + rw].any() or not isw[y + rh, x:x + rw].all(): ok = False
                else: rh += 1
            used[y:y + rh, x:x + rw] = True
            rects.append((x, y, rw, rh)); x += rw
    return rects


def route_cells(grid, cols, rows):
    # crude: just read baked? we don't have planner here; approximate a path is not
    # needed for composition — draw the known demo endpoints polyline straight.
    return [(670, 1028), (520, 470), (340, 206)]


def look_at(eye, c, up):
    f = (c - eye); f /= np.linalg.norm(f)
    s = np.cross(f, up); s /= np.linalg.norm(s)
    u = np.cross(s, f)
    m = np.eye(4)
    m[0, :3] = s; m[1, :3] = u; m[2, :3] = -f
    m[0, 3] = -s @ eye; m[1, 3] = -u @ eye; m[2, 3] = f @ eye
    return m


def persp(fovy, aspect, n, f):
    t = np.tan(np.radians(fovy) / 2)
    m = np.zeros((4, 4))
    m[0, 0] = 1 / (aspect * t); m[1, 1] = 1 / t
    m[2, 2] = (f + n) / (n - f); m[2, 3] = 2 * f * n / (n - f); m[3, 2] = -1
    return m


def main():
    grid, cols, rows = load()
    cell = 2.0 / max(cols, rows)
    wallH = cell * WALL_CELLS
    def wx(c): return (c - cols / 2) * cell
    def wz(r): return (r - rows / 2) * cell

    el = np.radians(ELEV_DEG)
    eye = np.array([RADIUS * np.cos(el) * np.sin(AZIMUTH), RADIUS * np.sin(el), RADIUS * np.cos(el) * np.cos(AZIMUTH)])
    V = look_at(eye, np.zeros(3), np.array([0, 1.0, 0]))
    P = persp(FOVY, W / H, 0.02, 30)
    VP = P @ V

    tris = []  # (z_avg, color, screen_pts[3,2])

    def project(p):
        v = VP @ np.array([p[0], p[1], p[2], 1.0])
        if v[3] <= 0.05 or v[2] < -v[3]: return None  # reject behind near plane
        ndc = v[:3] / v[3]
        sx = (ndc[0] * 0.5 + 0.5) * W
        sy = (1 - (ndc[1] * 0.5 + 0.5)) * H
        return np.array([sx, sy]), ndc[2], v[3]

    def add_quad(a, b, c, d, n, col):
        shade = 0.45 + 0.55 * max(np.dot(n, LIGHT), 0)
        color = np.clip(col * shade, 0, 1)
        for tri in ((a, b, c), (a, c, d)):
            pr = [project(p) for p in tri]
            if any(x is None for x in pr): return
            pts = np.array([x[0] for x in pr])
            zavg = np.mean([x[2] for x in pr])
            tris.append((zavg, color, pts))

    # floor
    add_quad((wx(0), 0, wz(0)), (wx(cols), 0, wz(0)), (wx(cols), 0, wz(rows)), (wx(0), 0, wz(rows)), np.array([0, 1.0, 0]), FLOOR)

    # walls
    for (rx, ry, rw, rh) in wall_rects(grid, cols, rows, DILATE):
        x0, x1, z0, z1 = wx(rx), wx(rx + rw), wz(ry), wz(ry + rh)
        y0, y1 = 0, wallH
        add_quad((x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1), np.array([1.0, 0, 0]), WALL)
        add_quad((x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0), np.array([-1.0, 0, 0]), WALL)
        add_quad((x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1), np.array([0, 0, 1.0]), WALL)
        add_quad((x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0), np.array([0, 0, -1.0]), WALL)
        add_quad((x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), np.array([0, 1.0, 0]), WALL)

    # path ribbons
    def ribbon(cells, halfw, y, col):
        for i in range(len(cells) - 1):
            ax, az = wx(cells[i][0]), wz(cells[i][1])
            bx, bz = wx(cells[i + 1][0]), wz(cells[i + 1][1])
            dx, dz = bx - ax, bz - az
            L = np.hypot(dx, dz)
            if L < 1e-7: continue
            dx, dz = dx / L, dz / L
            px, pz = -dz * halfw, dx * halfw
            add_quad((ax + px, y, az + pz), (ax - px, y, az - pz), (bx - px, y, bz - pz), (bx + px, y, bz + pz), np.array([0, 1.0, 0]), col)
    cells = route_cells(grid, cols, rows)
    ribbon(cells, cell * 11.5, cell * 5.0, PATH_CASE)
    ribbon(cells, cell * 7.0, cell * 6.5, PATH_CORE)

    # rasterize with z-buffer (painter by depth as fallback)
    img = np.tile(BG, (H, W, 1)).astype(np.float32)
    zbuf = np.full((H, W), 1e9, np.float32)
    for zavg, color, pts in tris:
        minx = max(int(np.floor(pts[:, 0].min())), 0)
        maxx = min(int(np.ceil(pts[:, 0].max())), W - 1)
        miny = max(int(np.floor(pts[:, 1].min())), 0)
        maxy = min(int(np.ceil(pts[:, 1].max())), H - 1)
        if minx > maxx or miny > maxy: continue
        xs, ys = np.meshgrid(np.arange(minx, maxx + 1), np.arange(miny, maxy + 1))
        x0, y0 = pts[0]; x1, y1 = pts[1]; x2, y2 = pts[2]
        denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(denom) < 1e-9: continue
        a = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / denom
        b = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / denom
        c = 1 - a - b
        mask = (a >= 0) & (b >= 0) & (c >= 0)
        zz = zbuf[miny:maxy + 1, minx:maxx + 1]
        m2 = mask & (zavg < zz)
        zz[m2] = zavg
        sub = img[miny:maxy + 1, minx:maxx + 1]
        sub[m2] = color
    out = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    out.save(".toolchain/preview3d.png")
    print(f"saved .toolchain/preview3d.png  az={AZIMUTH} el={ELEV_DEG} r={RADIUS} wallcells={WALL_CELLS} rects via mesh")


if __name__ == "__main__":
    main()
