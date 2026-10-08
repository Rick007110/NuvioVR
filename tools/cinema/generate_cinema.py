"""Procedural classic movie-theater hall for NuvioVR.

Generates (no external assets, everything is built here):
  app/src/main/assets/cinema/hall.glb    - room, stadium tiers, seats, stage, screen masking
  app/src/main/assets/cinema/lights.glb  - emissive "house lights" (wall sconces, aisle step lights,
                                           alpha-blended warm wall washes), toggled by the app

World coordinates (meters, glTF Y-up, right-handed): the viewer sits at the origin with the eye at
(0, 1.2, 0) looking toward +Z. The video panel (not part of the model) is centred at (0, 3.2, 11.0),
12.0 x 6.75 m. Nothing in the model is in front of z=11.0 inside the screen rectangle.

All materials are doubleSided PBR metallic-roughness. Triangle winding is made consistent with
the vertex normals, which point toward the inside of the room.

Usage:  python -I tools/cinema/generate_cinema.py [--preview-dir DIR]
Requires numpy + Pillow only. After writing, the GLBs are re-read and validated, and optional
software-rendered preview PNGs are written.
"""
import argparse
import io
import json
import math
import os
import struct
import sys

import numpy as np
from PIL import Image

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT_DIR = os.path.join(REPO, "app", "src", "main", "assets", "cinema")

# ----------------------------------------------------------------------------------------------
# Layout constants
# ----------------------------------------------------------------------------------------------
ROWS = 9
VIEWER_ROW = 4
ROW_PITCH = 1.1
RISE = 0.25
VIEWER_SEAT_Z = -0.25
FLOOR_Y = -1.25          # flat floor in front of the first row
WALL_X = 9.0
BACK_Z = -6.0
SCREEN_WALL_Z = 11.4
CEIL_Y = 7.5
STAGE_FRONT_Z = 7.0
STAGE_TOP_Y = -0.5
SEAT_W = 0.6
AISLE_X = 4.6
AISLE_W = 1.0
CENTER_SEATS = 13        # x = -3.6 .. 3.6, one seat exactly at x=0
SIDE_SEATS = 6           # x = +-5.4 .. +-8.4
SCREEN_CENTER_Y = 3.2
SCREEN_W, SCREEN_H = 12.0, 6.75
MASK_W, MASK_H, MASK_Z = 12.6, 7.3, 11.3


def row_seat_z(i):
    return VIEWER_SEAT_Z + (VIEWER_ROW - i) * ROW_PITCH


def row_floor_y(i):
    return (i - VIEWER_ROW) * RISE


def tier_z_range(i):
    c = row_seat_z(i)
    z0 = c - 0.6
    z1 = c + 0.5
    if i == ROWS - 1:
        z0 = BACK_Z
    return z0, z1


FRONT_TIER_Z = tier_z_range(0)[1]   # 4.65


def floor_at(z):
    """Walking surface height at depth z (full width)."""
    if z >= STAGE_FRONT_Z:
        return STAGE_TOP_Y
    if z >= FRONT_TIER_Z:
        return FLOOR_Y
    for i in range(ROWS):
        z0, z1 = tier_z_range(i)
        if z0 <= z <= z1:
            return row_floor_y(i)
    return row_floor_y(ROWS - 1)


def floor_max(z0, z1):
    return max(floor_at(z) for z in np.linspace(z0, z1, 12))


# ----------------------------------------------------------------------------------------------
# Materials (linear colors)
# ----------------------------------------------------------------------------------------------
def pbr(color, rough, metal=0.0, **extra):
    m = {"color": tuple(color), "rough": rough, "metal": metal}
    m.update(extra)
    return m


MATERIALS = {
    "carpet":        pbr((0.14, 0.035, 0.04), 0.95),
    "aisle_carpet":  pbr((0.20, 0.06, 0.05), 0.95),
    "tier_nosing":   pbr((0.10, 0.08, 0.06), 0.7),
    "wall_base":     pbr((0.09, 0.075, 0.08), 0.9),
    "wall_panel":    pbr((0.19, 0.05, 0.06), 0.85),
    "wainscot":      pbr((0.085, 0.055, 0.055), 0.8),
    "wood_trim":     pbr((0.13, 0.075, 0.045), 0.6),
    "ceiling":       pbr((0.07, 0.065, 0.07), 0.95),
    "ceiling_beam":  pbr((0.09, 0.08, 0.085), 0.9),
    "seat_frame":    pbr((0.085, 0.08, 0.09), 0.6),
    "seat_velvet":   pbr((0.45, 0.04, 0.06), 0.9),
    "screen_mask":   pbr((0.012, 0.012, 0.012), 1.0),
    "stage_wood":    pbr((0.12, 0.07, 0.04), 0.7),
    "brass":         pbr((0.60, 0.45, 0.20), 0.35, 1.0),
}

WARM = (1.0, 0.75, 0.45)
LIGHT_MATERIALS = {
    "lamp_emissive": pbr(WARM, 0.5, emissive=WARM),
    "lamp_fixture":  pbr((0.60, 0.45, 0.20), 0.35, 1.0),
    "wall_wash":     pbr(WARM + (1.0,), 1.0, emissive=WARM, blend=True, texture="wash"),
}


# ----------------------------------------------------------------------------------------------
# Geometry builder
# ----------------------------------------------------------------------------------------------
class Builder:
    def __init__(self):
        self.parts = {}   # material -> dict(pos=[], nrm=[], uv=[], idx=[], n=int)

    def _get(self, mat):
        if mat not in self.parts:
            self.parts[mat] = {"pos": [], "nrm": [], "uv": [], "idx": [], "n": 0}
        return self.parts[mat]

    def add(self, mat, pos, nrm, idx, uv=None):
        p = self._get(mat)
        pos = np.asarray(pos, np.float32).reshape(-1, 3)
        nrm = np.asarray(nrm, np.float32).reshape(-1, 3)
        idx = np.asarray(idx, np.int64).reshape(-1, 3)
        if uv is None:
            uv = np.zeros((len(pos), 2), np.float32)
        p["pos"].append(pos)
        p["nrm"].append(nrm)
        p["uv"].append(np.asarray(uv, np.float32).reshape(-1, 2))
        p["idx"].append(idx + p["n"])
        p["n"] += len(pos)

    def quad(self, mat, a, b, c, d, n=None, uv=None):
        a, b, c, d = (np.asarray(v, np.float64) for v in (a, b, c, d))
        if n is None:
            n = np.cross(b - a, d - a)
        n = np.asarray(n, np.float64)
        n = n / np.linalg.norm(n)
        if uv is None:
            uv = [(0, 1), (1, 1), (1, 0), (0, 0)]
        self.add(mat, [a, b, c, d], [n] * 4, [(0, 1, 2), (0, 2, 3)], uv)

    def box(self, mat, mn, mx, rot=None, pivot=None, skip=()):
        """Axis-aligned box mn..mx, optionally rotated by 3x3 `rot` about `pivot`.
        skip: face names to omit ('-x','+x','-y','+y','-z','+z')."""
        x0, y0, z0 = mn
        x1, y1, z1 = mx
        faces = {
            "+x": ([(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)], (1, 0, 0)),
            "-x": ([(x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0)], (-1, 0, 0)),
            "+y": ([(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)], (0, 1, 0)),
            "-y": ([(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)], (0, -1, 0)),
            "+z": ([(x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1)], (0, 0, 1)),
            "-z": ([(x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)], (0, 0, -1)),
        }
        pos, nrm, idx = [], [], []
        for name, (vs, n) in faces.items():
            if name in skip:
                continue
            b = len(pos)
            pos += vs
            nrm += [n] * 4
            idx += [(b, b + 1, b + 2), (b, b + 2, b + 3)]
        pos = np.array(pos, np.float64)
        nrm = np.array(nrm, np.float64)
        if rot is not None:
            pv = np.asarray(pivot, np.float64)
            pos = (pos - pv) @ rot.T + pv
            nrm = nrm @ rot.T
        self.add(mat, pos, nrm, idx)

    def grid(self, mat, P, uv=None, facing=(0, 0, -1)):
        """P: (ny, nx, 3) vertex grid. Smooth normals from the grid, oriented toward `facing`."""
        ny, nx, _ = P.shape
        du = np.gradient(P, axis=1)
        dv = np.gradient(P, axis=0)
        N = np.cross(du, dv)
        N /= np.linalg.norm(N, axis=2, keepdims=True) + 1e-12
        if (N.reshape(-1, 3) @ np.asarray(facing, float)).mean() < 0:
            N = -N
        idx = []
        for j in range(ny - 1):
            for i in range(nx - 1):
                a = j * nx + i
                idx += [(a, a + 1, a + nx + 1), (a, a + nx + 1, a + nx)]
        self.add(mat, P.reshape(-1, 3), N.reshape(-1, 3), idx, uv)

    def finalize(self, inward_hint=None):
        """Concatenate, then flip triangle winding where it disagrees with vertex normals.
        inward_hint(pos)->bool mask: for grids, normals may need flipping toward the audience
        (handled before calling)."""
        out = {}
        for mat, p in self.parts.items():
            pos = np.concatenate(p["pos"]).astype(np.float32)
            nrm = np.concatenate(p["nrm"]).astype(np.float32)
            nrm /= np.linalg.norm(nrm, axis=1, keepdims=True) + 1e-12
            uv = np.concatenate(p["uv"]).astype(np.float32)
            idx = np.concatenate(p["idx"])
            a, b, c = pos[idx[:, 0]], pos[idx[:, 1]], pos[idx[:, 2]]
            fn = np.cross(b - a, c - a)
            vn = nrm[idx[:, 0]] + nrm[idx[:, 1]] + nrm[idx[:, 2]]
            flip = (fn * vn).sum(1) < 0
            idx[flip] = idx[flip][:, [0, 2, 1]]
            out[mat] = (pos, nrm, uv, idx.astype(np.uint32))
        return out


def rot_x(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


# ----------------------------------------------------------------------------------------------
# Hall
# ----------------------------------------------------------------------------------------------
def seat_xs():
    xs = [(k - CENTER_SEATS // 2) * SEAT_W for k in range(CENTER_SEATS)]
    side0 = AISLE_X + AISLE_W / 2 + SEAT_W / 2      # 5.4
    side = [side0 + k * SEAT_W for k in range(SIDE_SEATS)]
    return xs, side


def blocks():
    """List of seat-x lists per block (left side, center, right side)."""
    center, side = seat_xs()
    return [[-x for x in reversed(side)], center, side]


def build_seat(B, x, y0, c):
    hw = 0.25
    # seat pan (frame) and velvet cushion; seat height 0.45 above the tier
    B.box("seat_frame", (x - hw, y0 + 0.33, c - 0.24), (x + hw, y0 + 0.39, c + 0.22))
    B.box("seat_velvet", (x - hw + 0.01, y0 + 0.39, c - 0.23), (x + hw - 0.01, y0 + 0.47, c + 0.24))
    # backrest, tilted back 12 degrees (top moves toward -Z), pivot at seat rear
    R = rot_x(-12)
    pv = (x, y0 + 0.42, c - 0.26)
    B.box("seat_velvet", (x - hw + 0.01, y0 + 0.42, c - 0.32), (x + hw - 0.01, y0 + 1.0, c - 0.22), R, pv)
    B.box("seat_frame", (x - hw, y0 + 0.40, c - 0.37), (x + hw, y0 + 1.02, c - 0.32), R, pv)


def build_armrest(B, x, y0, c):
    B.box("seat_frame", (x - 0.035, y0, c - 0.30), (x + 0.035, y0 + 0.60, c + 0.05))   # standard
    B.box("seat_frame", (x - 0.045, y0 + 0.60, c - 0.34), (x + 0.045, y0 + 0.66, c + 0.18))  # arm
    B.box("brass", (x - 0.046, y0 + 0.55, c + 0.051), (x + 0.046, y0 + 0.59, c + 0.06),
          skip=("-x", "+x", "-y", "+y", "-z"))  # tiny brass plaque (front face only)


def build_hall():
    B = Builder()
    aisles = [(-AISLE_X - AISLE_W / 2, -AISLE_X + AISLE_W / 2), (AISLE_X - AISLE_W / 2, AISLE_X + AISLE_W / 2)]

    # --- stepped floor tiers ---
    for i in range(ROWS):
        z0, z1 = tier_z_range(i)
        y = row_floor_y(i)
        # carpet split around the aisles so the aisles can have their own material
        xs = [-WALL_X, aisles[0][0], aisles[0][1], aisles[1][0], aisles[1][1], WALL_X]
        for k in range(5):
            mat = "aisle_carpet" if k in (1, 3) else "carpet"
            B.box(mat, (xs[k], FLOOR_Y, z0), (xs[k + 1], y, z1), skip=("-y",))
        # nosing trim on the riser edge
        B.box("tier_nosing", (-WALL_X, y - 0.04, z1), (WALL_X, y + 0.005, z1 + 0.02))
    # front flat floor
    B.box("carpet", (-WALL_X, FLOOR_Y - 0.1, FRONT_TIER_Z), (WALL_X, FLOOR_Y, STAGE_FRONT_Z), skip=("-y",))
    # aisle half-steps: one intermediate step on the back half of each lower tier (and on the floor)
    for (ax0, ax1) in aisles:
        for i in range(1, ROWS):
            lz0, lz1 = tier_z_range(i - 1)
            y = row_floor_y(i - 1)
            B.box("aisle_carpet", (ax0, y, lz0), (ax1, y + RISE / 2, lz0 + 0.55), skip=("-y",))
            B.box("tier_nosing", (ax0, y + RISE / 2 - 0.03, lz0 + 0.55), (ax1, y + RISE / 2 + 0.005, lz0 + 0.57))
        B.box("aisle_carpet", (ax0, FLOOR_Y, FRONT_TIER_Z), (ax1, FLOOR_Y + RISE / 2, FRONT_TIER_Z + 0.55), skip=("-y",))
        B.box("tier_nosing", (ax0, FLOOR_Y + RISE / 2 - 0.03, FRONT_TIER_Z + 0.55),
              (ax1, FLOOR_Y + RISE / 2 + 0.005, FRONT_TIER_Z + 0.57))

    # --- seats ---
    for i in range(ROWS):
        c = row_seat_z(i)
        y0 = row_floor_y(i)
        for blk in blocks():
            for x in blk:
                build_seat(B, x, y0, c)
            for x in [blk[0] - SEAT_W / 2] + [x + SEAT_W / 2 for x in blk]:
                build_armrest(B, x, y0, c)

    # --- stage ---
    B.box("stage_wood", (-WALL_X, FLOOR_Y, STAGE_FRONT_Z), (WALL_X, STAGE_TOP_Y, SCREEN_WALL_Z), skip=("-y",))
    B.box("wood_trim", (-WALL_X, STAGE_TOP_Y - 0.08, STAGE_FRONT_Z - 0.04), (WALL_X, STAGE_TOP_Y + 0.01, STAGE_FRONT_Z))
    B.box("wainscot", (-WALL_X, FLOOR_Y, STAGE_FRONT_Z - 0.03), (WALL_X, FLOOR_Y + 0.12, STAGE_FRONT_Z))

    # --- shell: walls, ceiling ---
    T = 0.2
    B.box("wall_base", (-WALL_X - T, FLOOR_Y, BACK_Z), (-WALL_X, CEIL_Y, SCREEN_WALL_Z))
    B.box("wall_base", (WALL_X, FLOOR_Y, BACK_Z), (WALL_X + T, CEIL_Y, SCREEN_WALL_Z))
    B.box("wall_base", (-WALL_X - T, FLOOR_Y, BACK_Z - T), (WALL_X + T, CEIL_Y, BACK_Z))
    B.box("wall_base", (-WALL_X - T, FLOOR_Y, SCREEN_WALL_Z), (WALL_X + T, CEIL_Y, SCREEN_WALL_Z + T))
    B.box("ceiling", (-WALL_X - T, CEIL_Y, BACK_Z - T), (WALL_X + T, CEIL_Y + T, SCREEN_WALL_Z + T))
    # coffer beams
    for x in np.arange(-6.0, 6.01, 3.0):
        B.box("ceiling_beam", (x - 0.15, CEIL_Y - 0.28, BACK_Z), (x + 0.15, CEIL_Y, SCREEN_WALL_Z))
    for z in np.arange(-3.5, 10.0, 3.0):
        B.box("ceiling_beam", (-WALL_X, CEIL_Y - 0.28, z - 0.15), (WALL_X, CEIL_Y, z + 0.15))
    B.box("wood_trim", (-WALL_X, CEIL_Y - 0.45, BACK_Z), (-WALL_X + 0.08, CEIL_Y - 0.28, SCREEN_WALL_Z))
    B.box("wood_trim", (WALL_X - 0.08, CEIL_Y - 0.45, BACK_Z), (WALL_X, CEIL_Y - 0.28, SCREEN_WALL_Z))
    B.box("wood_trim", (-WALL_X, CEIL_Y - 0.45, BACK_Z), (WALL_X, CEIL_Y - 0.28, BACK_Z + 0.08))

    # side wall paneling: wainscot per floor segment, chair-rail, vertical acoustic strips
    segs = [(STAGE_FRONT_Z, SCREEN_WALL_Z), (FRONT_TIER_Z, STAGE_FRONT_Z)] + [tier_z_range(i) for i in range(ROWS)]
    for side in (-1, 1):
        wx = side * WALL_X
        inner = wx - side * 0.06
        for z0, z1 in segs:
            fy = floor_at((z0 + z1) / 2)
            lo, hi = sorted((wx, inner))
            B.box("wainscot", (lo, FLOOR_Y, z0), (hi, fy + 1.0, z1))
            lo2, hi2 = sorted((wx, wx - side * 0.09))
            B.box("wood_trim", (lo2, fy + 1.0, z0), (hi2, fy + 1.08, z1))
        pitch = 0.6
        zs = np.arange(BACK_Z + 0.05, SCREEN_WALL_Z - 0.3, pitch)
        for z in zs:
            za, zb = z + 0.04, z + pitch - 0.04
            fy = floor_max(za, zb)
            lo, hi = sorted((wx, wx - side * 0.045))
            B.box("wall_panel", (lo, fy + 1.16, za), (hi, CEIL_Y - 0.55, zb), skip=("+x" if side > 0 else "-x",))
    # back wall
    for x in np.arange(-WALL_X + 0.05, WALL_X - 0.3, 0.6):
        B.box("wall_panel", (x + 0.04, row_floor_y(ROWS - 1) + 1.16, BACK_Z), (x + 0.56, CEIL_Y - 0.55, BACK_Z + 0.045),
              skip=("-z",))
    B.box("wainscot", (-WALL_X, FLOOR_Y, BACK_Z), (WALL_X, row_floor_y(ROWS - 1) + 1.0, BACK_Z + 0.06))
    B.box("wood_trim", (-WALL_X, row_floor_y(ROWS - 1) + 1.0, BACK_Z), (WALL_X, row_floor_y(ROWS - 1) + 1.08, BACK_Z + 0.09))
    # projection booth port (small dark window high on the back wall)
    B.box("screen_mask", (-0.5, 5.4, BACK_Z), (0.5, 5.9, BACK_Z + 0.05))
    B.box("brass", (-0.56, 5.34, BACK_Z), (0.56, 5.4, BACK_Z + 0.07))

    # --- screen wall: matte black masking behind the video panel ---
    B.box("screen_mask", (-MASK_W / 2, SCREEN_CENTER_Y - MASK_H / 2, MASK_Z),
          (MASK_W / 2, SCREEN_CENTER_Y + MASK_H / 2, SCREEN_WALL_Z))

    return B.finalize()


# ----------------------------------------------------------------------------------------------
# Lights
# ----------------------------------------------------------------------------------------------
def make_wash_png(w=32, h=64):
    """Vertical gradient: brightest just above the sconce (bottom), fading upward and to the sides."""
    img = np.zeros((h, w, 4), np.float32)
    ys = np.linspace(0, 1, h)[:, None]       # 0 = top row of the image
    xs = np.linspace(-1, 1, w)[None, :]
    up = 1.0 - ys                             # 0 at bottom (near lamp) .. 1 at top
    vert = np.clip(1.0 - up, 0, 1) ** 1.6 * np.clip(up * 12.0, 0, 1)  # fade near very bottom edge too
    horiz = np.clip(1.0 - (np.abs(xs) / (0.35 + 0.65 * up)) ** 2, 0, 1)
    a = np.clip(vert * horiz, 0, 1)
    img[..., 0] = 1.0
    img[..., 1] = 1.0
    img[..., 2] = 1.0
    img[..., :3] *= a[..., None] ** 0.5       # emissive texture also fades to black
    img[..., 3] = a * 0.85
    im = Image.fromarray((img * 255 + 0.5).astype(np.uint8), "RGBA")
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def build_lights():
    B = Builder()
    for side in (-1, 1):
        for z in np.arange(-4.0, 9.01, 3.0):
            wx = side * WALL_X
            # brass backplate + bracket
            lo, hi = sorted((wx - side * 0.07, wx - side * 0.045))
            B.box("lamp_fixture", (lo, 3.0, z - 0.09), (hi, 3.42, z + 0.09))
            lo, hi = sorted((wx - side * 0.12, wx - side * 0.07))
            B.box("lamp_fixture", (lo, 3.05, z - 0.03), (hi, 3.1, z + 0.03))
            # glowing shade: tapered (wider at top) 8-sided lantern
            cx = wx - side * 0.2
            n = 8
            r0, r1, y0, y1 = 0.07, 0.11, 3.08, 3.36
            for k in range(n):
                a0, a1 = 2 * math.pi * k / n, 2 * math.pi * (k + 1) / n
                p = [(cx + r0 * math.cos(a0), y0, z + r0 * math.sin(a0)),
                     (cx + r0 * math.cos(a1), y0, z + r0 * math.sin(a1)),
                     (cx + r1 * math.cos(a1), y1, z + r1 * math.sin(a1)),
                     (cx + r1 * math.cos(a0), y1, z + r1 * math.sin(a0))]
                am = (a0 + a1) / 2
                B.quad("lamp_emissive", *p, n=(math.cos(am), 0.15, math.sin(am)))
            B.box("lamp_fixture", (cx - 0.08, 3.04, z - 0.08), (cx + 0.08, 3.08, z + 0.08))
            # warm wash on the wall above (0.8 wide x 1.4 tall), just proud of the paneling
            px = wx - side * 0.05
            ya, yb = 3.05, 4.45
            B.quad("wall_wash", (px, ya, z - 0.4), (px, ya, z + 0.4), (px, yb, z + 0.4), (px, yb, z - 0.4),
                   n=(-side, 0, 0), uv=[(0, 1), (1, 1), (1, 0), (0, 0)])
    # aisle step lights: small emissive strips on every riser / half-step riser, both aisle edges
    risers = []
    for i in range(ROWS):
        z0, z1 = tier_z_range(i)
        risers.append((z1, row_floor_y(i)))                 # tier front edge, top
        lz0 = z0
        if i < ROWS - 1:
            risers.append((lz0 + 0.55, row_floor_y(i) + RISE / 2))  # half step on this tier
    risers.append((FRONT_TIER_Z + 0.55, FLOOR_Y + RISE / 2))
    for ax in (-AISLE_X, AISLE_X):
        for zf, ytop in risers:
            for ex in (ax - 0.42, ax + 0.42):
                B.box("lamp_emissive", (ex - 0.05, ytop - 0.09, zf + 0.02), (ex + 0.05, ytop - 0.06, zf + 0.03))
    return B.finalize()


# ----------------------------------------------------------------------------------------------
# GLB writer
# ----------------------------------------------------------------------------------------------
def write_glb(path, parts, materials, images):
    bin_chunks = []
    offset = 0
    buffer_views, accessors = [], []

    def add_view(data, target=None):
        nonlocal offset
        pad = (-offset) % 4
        if pad:
            bin_chunks.append(b"\0" * pad)
            offset += pad
        bv = {"buffer": 0, "byteOffset": offset, "byteLength": len(data)}
        if target:
            bv["target"] = target
        buffer_views.append(bv)
        bin_chunks.append(data)
        offset += len(data)
        return len(buffer_views) - 1

    def add_accessor(arr, ctype, typ, target, minmax=False):
        v = add_view(arr.tobytes(), target)
        acc = {"bufferView": v, "componentType": ctype, "count": int(arr.shape[0]), "type": typ}
        if minmax:
            acc["min"] = [float(x) for x in arr.min(0)]
            acc["max"] = [float(x) for x in arr.max(0)]
        accessors.append(acc)
        return len(accessors) - 1

    gl_images, textures = [], []
    tex_index = {}
    for name, png in images.items():
        v = add_view(png)
        gl_images.append({"bufferView": v, "mimeType": "image/png", "name": name})
        textures.append({"sampler": 0, "source": len(gl_images) - 1})
        tex_index[name] = len(textures) - 1

    gl_mats, meshes, nodes = [], [], []
    for mat_name, (pos, nrm, uv, idx) in parts.items():
        m = materials[mat_name]
        col = list(m["color"]) + ([1.0] if len(m["color"]) == 3 else [])
        pbrm = {"baseColorFactor": col, "metallicFactor": m["metal"], "roughnessFactor": m["rough"]}
        gm = {"name": mat_name, "pbrMetallicRoughness": pbrm, "doubleSided": True}
        if "emissive" in m:
            gm["emissiveFactor"] = list(m["emissive"])
        textured = "texture" in m
        if textured:
            t = tex_index[m["texture"]]
            pbrm["baseColorTexture"] = {"index": t}
            gm["emissiveTexture"] = {"index": t}
        if m.get("blend"):
            gm["alphaMode"] = "BLEND"
        gl_mats.append(gm)
        attrs = {
            "POSITION": add_accessor(pos.astype(np.float32), 5126, "VEC3", 34962, minmax=True),
            "NORMAL": add_accessor(nrm.astype(np.float32), 5126, "VEC3", 34962),
        }
        if textured:
            attrs["TEXCOORD_0"] = add_accessor(uv.astype(np.float32), 5126, "VEC2", 34962)
        flat = idx.reshape(-1)
        if len(pos) < 65536:
            ia = add_accessor(flat.astype(np.uint16), 5123, "SCALAR", 34963)
        else:
            ia = add_accessor(flat.astype(np.uint32), 5125, "SCALAR", 34963)
        meshes.append({"name": mat_name, "primitives": [{"attributes": attrs, "indices": ia,
                                                          "material": len(gl_mats) - 1, "mode": 4}]})
        nodes.append({"name": mat_name, "mesh": len(meshes) - 1})

    binary = b"".join(bin_chunks)
    binary += b"\0" * ((-len(binary)) % 4)
    gltf = {
        "asset": {"version": "2.0", "generator": "NuvioVR generate_cinema.py"},
        "scene": 0,
        "scenes": [{"name": "cinema", "nodes": list(range(len(nodes)))}],
        "nodes": nodes, "meshes": meshes, "materials": gl_mats,
        "accessors": accessors, "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(binary)}],
    }
    if gl_images:
        gltf["images"] = gl_images
        gltf["textures"] = textures
        gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9729, "wrapS": 33071, "wrapT": 33071}]
    js = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    js += b" " * ((-len(js)) % 4)
    total = 12 + 8 + len(js) + 8 + len(binary)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(struct.pack("<4sII", b"glTF", 2, total))
        f.write(struct.pack("<I4s", len(js), b"JSON"))
        f.write(js)
        f.write(struct.pack("<I4s", len(binary), b"BIN\0"))
        f.write(binary)


# ----------------------------------------------------------------------------------------------
# GLB reader / validator
# ----------------------------------------------------------------------------------------------
CTYPES = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def read_glb(path):
    data = open(path, "rb").read()
    magic, ver, total = struct.unpack_from("<4sII", data, 0)
    assert magic == b"glTF" and ver == 2, "bad header"
    assert total == len(data), f"header length {total} != file length {len(data)}"
    jl, jt = struct.unpack_from("<I4s", data, 12)
    assert jt == b"JSON" and jl % 4 == 0
    gltf = json.loads(data[20:20 + jl].decode("utf-8"))
    bo = 20 + jl
    bl, bt = struct.unpack_from("<I4s", data, bo)
    assert bt == b"BIN\0" and bl % 4 == 0
    assert bo + 8 + bl == len(data), "trailing bytes"
    binary = data[bo + 8: bo + 8 + bl]
    assert gltf["buffers"][0]["byteLength"] <= bl
    return gltf, binary


def get_accessor(gltf, binary, i):
    acc = gltf["accessors"][i]
    bv = gltf["bufferViews"][acc["bufferView"]]
    dt = CTYPES[acc["componentType"]]
    n = NCOMP[acc["type"]]
    off = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    need = acc["count"] * n * np.dtype(dt).itemsize
    assert off + need <= bv["byteOffset"] + bv["byteLength"] <= len(binary), f"accessor {i} overruns"
    assert off % np.dtype(dt).itemsize == 0, f"accessor {i} misaligned"
    return np.frombuffer(binary, dt, acc["count"] * n, off).reshape(acc["count"], n)


def load_and_validate(path):
    gltf, binary = read_glb(path)
    for bv in gltf["bufferViews"]:
        assert bv["byteOffset"] + bv["byteLength"] <= len(binary)
    out = []
    for mesh in gltf["meshes"]:
        for prim in mesh["primitives"]:
            a = prim["attributes"]
            pos = get_accessor(gltf, binary, a["POSITION"]).astype(np.float64)
            acc = gltf["accessors"][a["POSITION"]]
            assert np.allclose(acc["min"], pos.min(0), atol=1e-5) and np.allclose(acc["max"], pos.max(0), atol=1e-5), \
                f"{mesh['name']}: POSITION min/max mismatch"
            nrm = get_accessor(gltf, binary, a["NORMAL"]).astype(np.float64)
            assert len(nrm) == len(pos)
            assert np.all(np.abs(np.linalg.norm(nrm, axis=1) - 1) < 1e-3), f"{mesh['name']}: non-unit normals"
            uv = get_accessor(gltf, binary, a["TEXCOORD_0"]) if "TEXCOORD_0" in a else None
            idx = get_accessor(gltf, binary, prim["indices"]).reshape(-1).astype(np.int64)
            assert len(idx) % 3 == 0
            assert idx.max() < len(pos), f"{mesh['name']}: index {idx.max()} >= vertex count {len(pos)}"
            assert np.isfinite(pos).all() and np.isfinite(nrm).all()
            mat = gltf["materials"][prim["material"]]
            out.append({"name": mesh["name"], "pos": pos, "nrm": nrm, "uv": uv,
                        "idx": idx.reshape(-1, 3), "mat": mat})
    images = {}
    for k, im in enumerate(gltf.get("images", [])):
        bv = gltf["bufferViews"][im["bufferView"]]
        png = binary[bv["byteOffset"]: bv["byteOffset"] + bv["byteLength"]]
        images[k] = np.asarray(Image.open(io.BytesIO(png)).convert("RGBA"), np.float32) / 255.0
    return gltf, out, images


def report(path, prims):
    tris = sum(len(p["idx"]) for p in prims)
    allp = np.concatenate([p["pos"] for p in prims])
    print(f"\n{os.path.relpath(path, REPO)}: {os.path.getsize(path) / 1024:.0f} KiB, {tris} triangles, "
          f"bbox min {np.round(allp.min(0), 3).tolist()} max {np.round(allp.max(0), 3).tolist()}")
    for p in prims:
        print(f"  {p['name']:16s} {len(p['idx']):7d} tris  {len(p['pos']):7d} verts  "
              f"bbox {np.round(p['pos'].min(0), 2).tolist()} .. {np.round(p['pos'].max(0), 2).tolist()}")
    return tris


def check_layout(hall):
    """Nothing may be in front of the video panel plane (z<11.0 within the panel rectangle)."""
    hw, y0, y1 = SCREEN_W / 2, SCREEN_CENTER_Y - SCREEN_H / 2, SCREEN_CENTER_Y + SCREEN_H / 2
    for p in hall:
        P = p["pos"][p["idx"]]  # (T,3,3)
        # any vertex inside the panel's projected rectangle with z in [7.0, 11.0) would cover it
        cmin, cmax = P.min(1), P.max(1)
        cover = (cmax[:, 0] > -hw) & (cmin[:, 0] < hw) & (cmax[:, 1] > y0) & (cmin[:, 1] < y1) & \
                (cmax[:, 2] > 7.01) & (cmin[:, 2] < 11.0) & (cmin[:, 1] > STAGE_TOP_Y + 0.02)
        assert not cover.any(), f"{p['name']}: geometry in front of the screen panel"
    print("layout checks OK (nothing in front of the screen panel)")


# ----------------------------------------------------------------------------------------------
# Preview renderer (software z-buffer rasterizer, flat shaded)
# ----------------------------------------------------------------------------------------------
LIGHT_DIR = np.array([0.35, -1.0, 0.45])
LIGHT_DIR /= np.linalg.norm(LIGHT_DIR)


def shade_prims(prims, images, extra=None):
    """Return list of (tris (T,3,3) world, color (T,3) linear, alpha or None, uv (T,3,2), tex)."""
    out = []
    for p in prims + (extra or []):
        P = p["pos"][p["idx"]]
        N = p["nrm"][p["idx"]].mean(1)
        N /= np.linalg.norm(N, axis=1, keepdims=True) + 1e-12
        m = p["mat"]
        base = np.array(m["pbrMetallicRoughness"]["baseColorFactor"][:3])
        em = np.array(m.get("emissiveFactor", [0, 0, 0]))
        lam = np.abs(N @ -LIGHT_DIR)  # double sided
        col = base[None] * (0.35 + 0.9 * lam[:, None]) + em[None]
        tex = None
        uv = None
        if "baseColorTexture" in m["pbrMetallicRoughness"]:
            tex = images[m["pbrMetallicRoughness"]["baseColorTexture"]["index"]]
            uv = p["uv"][p["idx"]]
        out.append((P, col, m.get("alphaMode") == "BLEND", uv, tex))
    return out


def render(items, cam, size=(960, 600), ortho=None, exposure=2.2, xclip=None):
    W, H = size
    img = np.zeros((H, W, 3), np.float32) + 0.02
    zbuf = np.full((H, W), np.inf, np.float32)
    eye, fwd, upv, fov = cam
    f = np.asarray(fwd, float); f /= np.linalg.norm(f)
    r = np.cross(f, upv); r /= np.linalg.norm(r)
    u = np.cross(r, f)
    fx = (W / 2) / math.tan(math.radians(max(fov, 1)) / 2)

    def to_cam(P):
        d = P - eye
        return np.stack([d @ r, d @ u, d @ f], axis=-1)

    def project(C):
        if ortho:
            s = W / ortho
            return np.stack([W / 2 + C[..., 0] * s, H / 2 - C[..., 1] * s, C[..., 2]], -1)
        return np.stack([W / 2 + fx * C[..., 0] / C[..., 2], H / 2 - fx * C[..., 1] / C[..., 2], C[..., 2]], -1)

    near = 0.05
    blends = []
    for P, col, blend, uv, tex in items:
        if xclip is not None:
            keep = ~(P[:, :, 0] < xclip).all(1)
            P, col = P[keep], col[keep]
            if uv is not None:
                uv = uv[keep]
        C = to_cam(P)
        if uv is None:
            uv = np.zeros(P.shape[:2] + (2,))
        tris = []
        if ortho:
            tris = [(C[t], col[t], uv[t]) for t in range(len(C))]
        else:
            front = (C[:, :, 2] > near).all(1)
            some = (C[:, :, 2] > near).any(1) & ~front
            tris = [(C[t], col[t], uv[t]) for t in np.nonzero(front)[0]]
            for t in np.nonzero(some)[0]:   # clip against the near plane, fan triangulate
                poly = []
                v = C[t]; w = uv[t]
                for k in range(3):
                    a, b = v[k], v[(k + 1) % 3]
                    ta, tb = w[k], w[(k + 1) % 3]
                    if a[2] > near:
                        poly.append((a, ta))
                    if (a[2] > near) != (b[2] > near):
                        s = (near - a[2]) / (b[2] - a[2])
                        poly.append((a + s * (b - a), ta + s * (tb - ta)))
                for k in range(1, len(poly) - 1):
                    tris.append((np.array([poly[0][0], poly[k][0], poly[k + 1][0]]), col[t],
                                 np.array([poly[0][1], poly[k][1], poly[k + 1][1]])))
        for Ct, c, w in tris:
            if blend:
                blends.append((Ct, c, w, tex))
            else:
                raster(project(Ct), c, img, zbuf, ortho is not None)
    for Ct, c, w, tex in blends:
        raster(project(Ct), c, img, zbuf, ortho is not None, uv=w, tex=tex, blend=True)
    out = 1 - np.exp(-img * exposure)
    out = np.clip(out, 0, 1) ** (1 / 2.2)
    return Image.fromarray((out * 255).astype(np.uint8))


def raster(S, col, img, zbuf, ortho, uv=None, tex=None, blend=False):
    H, W = zbuf.shape
    x0 = max(int(math.floor(S[:, 0].min())), 0); x1 = min(int(math.ceil(S[:, 0].max())), W - 1)
    y0 = max(int(math.floor(S[:, 1].min())), 0); y1 = min(int(math.ceil(S[:, 1].max())), H - 1)
    if x0 > x1 or y0 > y1:
        return
    (ax, ay, az), (bx, by, bz), (cx, cy, cz) = S
    den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
    if abs(den) < 1e-9:
        return
    ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1].astype(np.float32) + 0.5
    l0 = ((by - cy) * (xs - cx) + (cx - bx) * (ys - cy)) / den
    l1 = ((cy - ay) * (xs - cx) + (ax - cx) * (ys - cy)) / den
    l2 = 1 - l0 - l1
    m = (l0 >= -1e-4) & (l1 >= -1e-4) & (l2 >= -1e-4)
    if not m.any():
        return
    if ortho:
        z = l0 * az + l1 * bz + l2 * cz
    else:  # perspective-correct depth: interpolate 1/z
        iz = l0 / az + l1 / bz + l2 / cz
        z = 1 / iz
    zb = zbuf[y0:y1 + 1, x0:x1 + 1]
    m &= z < zb
    if not m.any():
        return
    sub = img[y0:y1 + 1, x0:x1 + 1]
    if blend:
        if ortho:
            w0, w1, w2 = l0, l1, l2
        else:
            w0, w1, w2 = l0 / az * z, l1 / bz * z, l2 / cz * z
        U = w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]
        V = w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]
        th, tw = tex.shape[:2]
        ti = np.clip((V * th).astype(int), 0, th - 1)
        tj = np.clip((U * tw).astype(int), 0, tw - 1)
        t = tex[ti, tj]
        a = (t[..., 3] * m)[..., None]
        sub[:] = sub * (1 - a) + (col[None, None] * t[..., :3]) * a + 0 * sub
    else:
        sub[m] = col
        zb[m] = z[m]


def screen_placeholder():
    """The app's video panel, only for previews (not exported)."""
    hw, hh, cy, z = SCREEN_W / 2, SCREEN_H / 2, SCREEN_CENTER_Y, 11.0
    pos = np.array([(-hw, cy - hh, z), (hw, cy - hh, z), (hw, cy + hh, z), (-hw, cy + hh, z)])
    return {"name": "panel", "pos": pos, "nrm": np.tile([0, 0, -1.0], (4, 1)), "uv": None,
            "idx": np.array([[0, 1, 2], [0, 2, 3]]),
            "mat": {"pbrMetallicRoughness": {"baseColorFactor": [0, 0, 0, 1]}, "emissiveFactor": [0.35, 0.42, 0.55]}}


# ----------------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview-dir", default=None, help="write cinema_preview_*.png here")
    args = ap.parse_args()

    hall_path = os.path.join(OUT_DIR, "hall.glb")
    lights_path = os.path.join(OUT_DIR, "lights.glb")
    write_glb(hall_path, build_hall(), MATERIALS, {})
    write_glb(lights_path, build_lights(), LIGHT_MATERIALS, {"wash": make_wash_png()})

    _, hall, _ = load_and_validate(hall_path)
    _, lights, limgs = load_and_validate(lights_path)
    th = report(hall_path, hall)
    tl = report(lights_path, lights)
    assert th < 150_000, "hall.glb too heavy"
    check_layout(hall)
    print(f"\nvalidation OK: hall {th} tris, lights {tl} tris")

    if args.preview_dir:
        os.makedirs(args.preview_dir, exist_ok=True)
        items = shade_prims(hall, {}, [screen_placeholder()]) + shade_prims(lights, limgs)
        front = render(items, (np.array([0, 1.2, 0.0]), (0, 0, 1), (0, 1, 0), 90))
        front.save(os.path.join(args.preview_dir, "cinema_preview_front.png"))
        side = render(items, (np.array([-50.0, 3.0, 2.7]), (1, 0, 0), (0, 1, 0), 0), size=(1100, 560),
                      ortho=19.0, xclip=-8.85)
        side.save(os.path.join(args.preview_dir, "cinema_preview_side.png"))
        back = render(items, (np.array([0, 4.5, -5.5]), (0, -0.25, 1), (0, 1, 0), 80))
        back.save(os.path.join(args.preview_dir, "cinema_preview_back.png"))
        print("previews written to", args.preview_dir)


if __name__ == "__main__":
    sys.exit(main())
