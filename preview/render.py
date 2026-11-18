"""
Headless preview of the LIFE ENGINE renderer using Mesa llvmpipe (EGL) and the *same* GLSL ES 3.00
shader files shipped in the APK. Mirrors the Android render pipeline:
  shadow pass -> (reflection pass) -> terrain -> objects -> grass -> sky -> water -> waterfall
Usage: python3 preview/render.py <shot-name> [hour] [eye x y z] [target x y z]
Data comes from tools/PreviewExport (preview/out) and tools/AtmoDump.
"""
import json, math, os, subprocess, sys, time
import numpy as np
import moderngl
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'preview', 'out')
SH = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'shaders')
W, H = int(os.environ.get('PW', 1280)), int(os.environ.get('PH', 800))
SIZE, RES = 3072.0, 769
HALF = SIZE / 2

HEADER = ("#version 300 es\nprecision highp float;\nprecision highp int;\n"
          "precision highp sampler2D;\nprecision highp sampler2DShadow;\n")


def src(name, defines):
    s = open(os.path.join(SH, name)).read()
    common = open(os.path.join(SH, 'common.glsl')).read()
    s = s.replace('#include "common.glsl"', common)
    return HEADER + ''.join('#define %s 1\n' % d for d in defines) + s


def f32(n): return np.fromfile(os.path.join(OUT, n + '.f32'), dtype='<f4')
def i32(n): return np.fromfile(os.path.join(OUT, n + '.i32'), dtype='<i4')
def u8(n): return np.fromfile(os.path.join(OUT, n + '.u8'), dtype='u1')


def perspective(fovy, aspect, near, far):
    f = 1 / math.tan(math.radians(fovy) / 2)
    m = np.zeros((4, 4), 'f4')
    m[0, 0] = f / aspect; m[1, 1] = f
    m[2, 2] = (far + near) / (near - far); m[2, 3] = 2 * far * near / (near - far); m[3, 2] = -1
    return m


def ortho(l, r, b, t, n, f):
    m = np.identity(4, 'f4')
    m[0, 0] = 2 / (r - l); m[1, 1] = 2 / (t - b); m[2, 2] = -2 / (f - n)
    m[0, 3] = -(r + l) / (r - l); m[1, 3] = -(t + b) / (t - b); m[2, 3] = -(f + n) / (f - n)
    return m


def look_at(eye, target, up=(0, 1, 0)):
    eye, target, up = map(lambda v: np.array(v, 'f4'), (eye, target, up))
    f = target - eye; f /= np.linalg.norm(f)
    s = np.cross(f, up); s /= np.linalg.norm(s)
    u = np.cross(s, f)
    m = np.identity(4, 'f4')
    m[0, :3] = s; m[1, :3] = u; m[2, :3] = -f
    m[0, 3] = -s @ eye; m[1, 3] = -u @ eye; m[2, 3] = f @ eye
    return m


def frustum_planes(vp):
    p = [vp[3] + vp[0], vp[3] - vp[0], vp[3] + vp[1], vp[3] - vp[1], vp[3] + vp[2], vp[3] - vp[2]]
    return np.array([q / np.linalg.norm(q[:3]) for q in p], 'f4')


def sun_dir(hour):
    a = (hour - 6.0) / 12.0 * math.pi
    tilt = math.radians(20)
    d = np.array([math.cos(a), math.sin(a) * math.cos(tilt), math.sin(a) * math.sin(tilt)], 'f4')
    return d / np.linalg.norm(d)


class Renderer:
    def __init__(self, quality='ultra'):
        self.ctx = ctx = moderngl.create_standalone_context(backend='egl')
        self.q = quality
        self.world = json.load(open(os.path.join(OUT, 'world.json')))
        self.height = f32('height').reshape(RES, RES)
        P = lambda v, f, d=(): ctx.program(vertex_shader=src(v, d), fragment_shader=src(f, d))
        sd = ('SHADOWS',)
        self.p_terrain = P('terrain.vert', 'terrain.frag', sd)
        self.p_terrain_d = P('terrain.vert', 'terrain.frag', ('DEPTH_ONLY',))
        self.p_terrain_r = P('terrain.vert', 'terrain.frag', ('REFLECTION',))
        self.p_obj = P('object.vert', 'object.frag', sd)
        self.p_obj_d = P('object.vert', 'object.frag', ('DEPTH_ONLY',))
        self.p_obj_r = P('object.vert', 'object.frag', ('REFLECTION',))
        self.p_grass = P('grass.vert', 'grass.frag', sd)
        self.p_sky = P('sky.vert', 'sky.frag')
        self.p_sky_r = P('sky.vert', 'sky.frag', ('REFLECTION',))
        self.p_water = P('water.vert', 'water.frag', sd)
        self.p_fall = P('fall.vert', 'fall.frag')

        # textures
        def tex(data, size, comps, dtype='f1', repeat=True, mip=True):
            t = ctx.texture(size, comps, data.tobytes(), dtype=dtype)
            t.repeat_x = t.repeat_y = repeat
            if mip:
                t.build_mipmaps(); t.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR); t.anisotropy = 8
            else:
                t.filter = (moderngl.LINEAR, moderngl.LINEAR)
            return t
        self.t_noise = tex(u8('tex_noise'), (256, 256), 4)
        self.t_water = tex(u8('tex_water'), (256, 256), 4)
        self.t_leaf = tex(u8('tex_leaf'), (512, 512), 4, repeat=False)
        self.t_height = tex(self.height.astype('f2'), (RES, RES), 1, 'f2', repeat=False, mip=False)
        self.t_fields = tex(u8('fields'), (RES, RES), 4, repeat=False, mip=False)
        self.shadow_size = 2048
        self.t_shadow = ctx.depth_texture((self.shadow_size, self.shadow_size))
        self.t_shadow.compare_func = '<='
        self.t_shadow.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.t_shadow.repeat_x = self.t_shadow.repeat_y = False
        self.fbo_shadow = ctx.framebuffer(depth_attachment=self.t_shadow)
        rw, rh = W // 2, H // 2
        self.t_refl = ctx.texture((rw, rh), 4); self.t_refl.repeat_x = self.t_refl.repeat_y = False
        self.fbo_refl = ctx.framebuffer([self.t_refl], ctx.depth_renderbuffer((rw, rh)))

        # terrain lods
        self.terrain = {}
        for step in (1, 2, 4, 8):
            vb = ctx.buffer(f32('terrain%d_v' % step).tobytes())
            ib = ctx.buffer(i32('terrain%d_i' % step).tobytes())
            vaos = {p: ctx.vertex_array(p, [(vb, '3f 3f 4f', 'aPos', 'aNormal', 'aSplat')], ib, 4, skip_errors=True)
                    for p in (self.p_terrain, self.p_terrain_d, self.p_terrain_r)}
            self.terrain[step] = (vaos, i32('terrain%d_co' % step), i32('terrain%d_cc' % step), f32('terrain%d_cb' % step).reshape(-1, 6))
        # objects
        names = ["acacia", "riverine", "baobab", "bush", "rock0", "rock1", "rock2", "rock3", "palm"]
        self.objs = []
        for n in names:
            lods = []
            for lod in (0, 1):
                vb = ctx.buffer(f32('mesh_%s%d_v' % (n, lod)).tobytes())
                ib = ctx.buffer(i32('mesh_%s%d_i' % (n, lod)).tobytes())
                lods.append((vb, ib, len(i32('mesh_%s%d_i' % (n, lod)))))
            inst = f32('inst_' + n).reshape(-1, 6)
            self.objs.append((n, lods, inst))
        self.inst_buf = ctx.buffer(reserve=6 * 4 * 20000, dynamic=True)
        self.grass_vaos = {}
        for gname in ('grass', 'grassfar'):
            g_vb = ctx.buffer(f32('mesh_%s_v' % gname).tobytes()); g_ib = ctx.buffer(i32('mesh_%s_i' % gname).tobytes())
            self.grass_vaos[gname] = ctx.vertex_array(self.p_grass, [(g_vb, '3f 3f 3f 4f', 'aPos', 'aNormal', 'aColor', 'aAux')], g_ib, 4)
        wv = f32('water_v'); wi = f32('water_i').astype('i4')
        self.water_vao = ctx.vertex_array(self.p_water, [(ctx.buffer(wv.tobytes()), '4f', 'aPos')], ctx.buffer(wi.tobytes()), 4)
        rv = f32('ring_v'); ri = f32('ring_i').astype('i4')
        self.ring_vao = ctx.vertex_array(self.p_terrain, [(ctx.buffer(rv.tobytes()), '3f 3f 4f', 'aPos', 'aNormal', 'aSplat')], ctx.buffer(ri.tobytes()), 4)
        self.ring_vao_r = ctx.vertex_array(self.p_terrain_r, [(ctx.buffer(rv.tobytes()), '3f 3f 4f', 'aPos', 'aNormal', 'aSplat')], ctx.buffer(ri.tobytes()), 4)
        fv = f32('fall_v'); fi = f32('fall_i').astype('i4')
        self.fall_vao = ctx.vertex_array(self.p_fall, [(ctx.buffer(fv.tobytes()), '3f 2f', 'aPos', 'aUv')], ctx.buffer(fi.tobytes()), 4)
        self.sky_vao = ctx.vertex_array(self.p_sky, [])
        self.sky_vao_r = ctx.vertex_array(self.p_sky_r, [])

        self.fbo = ctx.framebuffer([ctx.renderbuffer((W, H), 4, samples=4)], ctx.depth_renderbuffer((W, H), samples=4))
        self.fbo_out = ctx.simple_framebuffer((W, H))

    # ------------------------------------------------------------------
    def set(self, prog, name, val):
        if name not in prog:
            return
        u = prog[name]
        if isinstance(val, np.ndarray) and val.shape == (4, 4):
            u.write(val.T.astype('f4').tobytes())
        else:
            u.value = val

    def common(self, prog, vp, eye, env):
        s = self.set
        s(prog, 'uViewProj', vp); s(prog, 'uCamPos', tuple(eye))
        for k in ('uSunDir', 'uSunColor', 'uMoonDir', 'uMoonColor', 'uAmbSky', 'uAmbGround', 'uFog', 'uWind', 'uClouds'):
            s(prog, k, tuple(env[k]))
        for k in ('uTime', 'uExposure', 'uWetness', 'uShadowTexel'):
            s(prog, k, float(env[k]))
        s(prog, 'uLightVP', env['uLightVP'])
        s(prog, 'uSkyLut', 0); s(prog, 'uNoise', 1); s(prog, 'uShadowMap', 2); s(prog, 'uWaterNormal', 3)
        s(prog, 'uHeight', 4); s(prog, 'uFields', 5); s(prog, 'uLeafAtlas', 6); s(prog, 'uReflection', 7)
        s(prog, 'uWorld', (SIZE, HALF))

    def draw_terrain(self, which, vp, eye, env, planes, lod_bias=1.0, min_step=1):
        prog = {'main': self.p_terrain, 'depth': self.p_terrain_d, 'refl': self.p_terrain_r}[which]
        self.common(prog, vp, eye, env)
        for step in (1, 2, 4, 8):
            vaos, co, cc, cb = self.terrain[step]
            vao = vaos[prog]
            for c in range(len(co)):
                b = cb[c]
                cx, cz = (b[0] + b[3]) / 2, (b[2] + b[5]) / 2
                cy = (b[1] + b[4]) / 2
                d = math.dist((cx, cy, cz), eye) * lod_bias
                want = 1 if d < 300 else 2 if d < 750 else 4 if d < 1500 else 8
                want = max(want, min_step)
                if want != step:
                    continue
                if not aabb_visible(planes, b):
                    continue
                vao.render(moderngl.TRIANGLES, vertices=int(cc[c]), first=int(co[c]))

    def draw_objects(self, which, vp, eye, env, planes, lod_dist=320.0, max_dist=4000.0):
        prog = {'main': self.p_obj, 'depth': self.p_obj_d, 'refl': self.p_obj_r}[which]
        self.common(prog, vp, eye, env)
        for name, lods, inst in self.objs:
            if len(inst) == 0:
                continue
            r = 12.0 * inst[:, 4] + 2
            d = np.linalg.norm(inst[:, :3] - np.array(eye, 'f4'), axis=1)
            vis = np.ones(len(inst), bool)
            for pl in planes:
                vis &= (inst[:, :3] @ pl[:3] + pl[3]) > -r
            vis &= d < max_dist
            for lod in (0, 1):
                sel = inst[vis & ((d < lod_dist) if lod == 0 else (d >= lod_dist))]
                if len(sel) == 0:
                    continue
                vb, ib, n = lods[lod]
                self.inst_buf.write(sel.astype('f4').tobytes())
                vao = self.ctx.vertex_array(prog, [(vb, '3f 3f 3f 4f', 'aPos', 'aNormal', 'aColor', 'aAux'),
                                                   (self.inst_buf, '4f 2f/i', 'iPosRot', 'iScaleTint')], ib, 4, skip_errors=True)
                vao.render(moderngl.TRIANGLES, vertices=n, instances=len(sel))
                vao.release()

    def light_vp(self, center, L, extent):
        eye = center + L * 1500.0
        view = look_at(eye, center, (0, 1, 0) if abs(L[1]) < 0.99 else (1, 0, 0))
        # snap to texel grid to avoid shimmering
        texel = 2 * extent / self.shadow_size
        o = view @ np.array([0, 0, 0, 1], 'f4')
        view[0, 3] -= o[0] % texel; view[1, 3] -= o[1] % texel
        return ortho(-extent, extent, -extent, extent, 1.0, 3200.0) @ view

    def render(self, eye, target, hour, out_png, fov=55.0, coverage=0.4, wind=0.35, t=40.0, wetness=0.0, reflect=True):
        ctx = self.ctx
        t0 = time.time()
        sun = sun_dir(hour)
        moon = -sun + np.array([0, 0.25, 0.1], 'f4'); moon /= np.linalg.norm(moon)
        pref = os.path.join(OUT, 'atmo')
        subprocess.run(['java', '-cp', os.path.join(ROOT, 'tools', 'out'), 'AtmoDump', *map(str, [*sun, *moon, 1.6, coverage * 0.6]), pref],
                       check=True, capture_output=True)
        a = json.load(open(pref + '.json'))
        lut = np.fromfile(pref + '.f32', dtype='<f4')
        t_lut = ctx.texture((96, 48), 3, lut.tobytes(), dtype='f4'); t_lut.repeat_x = True; t_lut.repeat_y = False
        t_lut.filter = (moderngl.LINEAR, moderngl.LINEAR)
        light = sun if a['sun'][0] + a['sun'][1] > 0.02 else moon
        env = dict(uSunDir=sun, uSunColor=a['sun'], uMoonDir=moon, uMoonColor=a['moon'], uAmbSky=a['ambSky'], uAmbGround=a['ambGround'],
                   uFog=(0.00024, 0.0020, 1.0, 0.0), uWind=(0.8, 0.6, wind, 0.0), uClouds=(coverage, 1800.0, t * 0.0004, t * 0.0002),
                   uTime=t, uExposure=a['exposure'], uWetness=wetness, uShadowTexel=1.0 / self.shadow_size)
        eye = np.array(eye, 'f4'); target = np.array(target, 'f4')
        proj = perspective(fov, W / H, 1.0, 24000.0)
        view = look_at(eye, target)
        vp = proj @ view
        planes = frustum_planes(vp)

        # shadow pass, centred between camera and target
        fwd = target - eye; dist = np.linalg.norm(fwd); fwd /= dist
        center = eye + fwd * min(dist, 220.0)
        center[1] = self.height_at(center[0], center[2])
        extent = float(np.clip(dist * 0.9 + 80.0, 140.0, 520.0))
        lvp = self.light_vp(center, np.array(light, 'f4'), extent)
        env['uLightVP'] = lvp
        self.fbo_shadow.use(); self.fbo_shadow.clear(depth=1.0)
        ctx.enable(moderngl.DEPTH_TEST); ctx.disable(moderngl.CULL_FACE)
        ctx.polygon_offset = (2.0, 4.0)
        lplanes = frustum_planes(lvp)
        self.draw_terrain('depth', lvp, center + np.array(light) * 1500, env, lplanes, min_step=2)
        self.draw_objects('depth', lvp, center, env, lplanes, lod_dist=extent * 0.7, max_dist=extent * 1.6)
        ctx.polygon_offset = (0.0, 0.0)

        t_lut.use(0); self.t_noise.use(1); self.t_shadow.use(2); self.t_water.use(3)
        self.t_height.use(4); self.t_fields.use(5); self.t_leaf.use(6)

        # planar reflection (mirror camera about the lowland water plane y=0)
        use_refl = reflect
        if use_refl:
            M = np.identity(4, 'f4'); M[1, 1] = -1
            rview = view @ M
            reye = eye * np.array([1, -1, 1], 'f4')
            rvp = proj @ rview
            rplanes = frustum_planes(rvp)
            self.fbo_refl.use(); self.fbo_refl.clear(0, 0, 0, 0, depth=1.0)
            ctx.front_face = 'cw'
            for p in (self.p_terrain_r, self.p_obj_r, self.p_sky_r):
                self.set(p, 'uClipY', -0.3)
            self.draw_terrain('refl', rvp, reye, env, rplanes, lod_bias=2.0, min_step=2)
            self.ring_vao_r.render(moderngl.TRIANGLES)
            self.draw_objects('refl', rvp, reye, env, rplanes, lod_dist=0.0, max_dist=2000.0)
            self.common(self.p_sky_r, rvp, reye, env); self.set(self.p_sky_r, 'uInvViewProj', np.linalg.inv(rvp))
            ctx.depth_func = '<='
            self.sky_vao_r.render(moderngl.TRIANGLES, vertices=3)
            ctx.depth_func = '<'
            ctx.front_face = 'ccw'
        self.t_refl.use(7)

        self.fbo.use(); self.fbo.clear(0, 0, 0, 1, depth=1.0)
        ctx.enable(moderngl.DEPTH_TEST)
        self.draw_terrain('main', vp, eye, env, planes)
        self.ring_vao.render(moderngl.TRIANGLES)
        self.draw_objects('main', vp, eye, env, planes)
        # grass
        self.common(self.p_grass, vp, eye, env)
        for layer in grass_layers(self.q):
            self.draw_grass_layer(layer, eye, planes)
        # sky
        self.common(self.p_sky, vp, eye, env); self.set(self.p_sky, 'uInvViewProj', np.linalg.inv(vp))
        ctx.depth_func = '<='; ctx.depth_mask = False
        self.sky_vao.render(moderngl.TRIANGLES, vertices=3)
        # transparent: water then waterfall
        ctx.enable(moderngl.BLEND); ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        ctx.depth_func = '<'
        self.common(self.p_water, vp, eye, env)
        self.set(self.p_water, 'uUseReflection', 1.0 if use_refl else 0.0)
        self.set(self.p_water, 'uViewport', (float(W), float(H)))
        w = self.world
        self.set(self.p_water, 'uFallFoot', (w['fall'][0], 0.0, w['fall'][3]))
        self.water_vao.render(moderngl.TRIANGLES)
        self.common(self.p_fall, vp, eye, env)
        self.fall_vao.render(moderngl.TRIANGLES)
        ctx.disable(moderngl.BLEND); ctx.depth_mask = True
        ctx.copy_framebuffer(self.fbo_out, self.fbo)
        img = Image.frombytes('RGB', (W, H), self.fbo_out.read(components=3)).transpose(Image.FLIP_TOP_BOTTOM)
        img.save(out_png)
        t_lut.release()
        print('%s: %.1fs exp=%.3f sun=%s' % (out_png, time.time() - t0, a['exposure'], [round(x, 2) for x in a['sun']]))

    def draw_grass_layer(self, layer, eye, planes):
        sp, r_in, r_out, vao, B = layer['spacing'], layer['inner'], layer['outer'], self.grass_vaos[layer['mesh']], 16
        bs = B * sp
        self.set(self.p_grass, 'uSpacing', sp); self.set(self.p_grass, 'uBlock', B)
        self.set(self.p_grass, 'uFade', (r_in * 0.85, r_in if r_in > 0 else 0.0, r_out))
        bx0, bx1 = int(math.floor((eye[0] - r_out) / bs)), int(math.floor((eye[0] + r_out) / bs))
        bz0, bz1 = int(math.floor((eye[2] - r_out) / bs)), int(math.floor((eye[2] + r_out) / bs))
        n = 0
        for bz in range(bz0, bz1 + 1):
            for bx in range(bx0, bx1 + 1):
                x0, z0 = bx * bs, bz * bs
                # distance from camera to block rectangle
                dx = max(x0 - eye[0], 0, eye[0] - (x0 + bs)); dz = max(z0 - eye[2], 0, eye[2] - (z0 + bs))
                if math.hypot(dx, dz) > r_out:
                    continue
                fx = max(abs(x0 - eye[0]), abs(x0 + bs - eye[0])); fz = max(abs(z0 - eye[2]), abs(z0 + bs - eye[2]))
                if r_in > 0 and math.hypot(fx, fz) < r_in * 0.85:
                    continue
                cy = self.height_at(min(max(x0 + bs / 2, -HALF), HALF), min(max(z0 + bs / 2, -HALF), HALF))
                if not aabb_visible(planes, (x0, cy - 30, z0, x0 + bs, cy + 30, z0 + bs)):
                    continue
                self.set(self.p_grass, 'uBlockCell', (float(bx * B), float(bz * B)))
                vao.render(moderngl.TRIANGLES, instances=B * B)
                n += 1
        return n

    def height_at(self, x, z):
        gx = np.clip((x + HALF) / (SIZE / (RES - 1)), 0, RES - 1.001); gz = np.clip((z + HALF) / (SIZE / (RES - 1)), 0, RES - 1.001)
        i, j = int(gx), int(gz); fx, fz = gx - i, gz - j
        h = self.height
        return float((h[j, i] * (1 - fx) + h[j, i + 1] * fx) * (1 - fz) + (h[j + 1, i] * (1 - fx) + h[j + 1, i + 1] * fx) * fz)


def grass_layers(q):
    # (spacing m, inner radius, outer radius) per quality — must match GraphicsProfile.kt
    t = {'ultra': ((0.75, 0, 48), (1.9, 44, 150)), 'high': ((0.85, 0, 38), (2.1, 34, 115)),
         'medium': ((1.0, 0, 28), (2.4, 25, 80)), 'battery': ((1.25, 0, 18), (2.8, 16, 45))}[q]
    return [dict(spacing=t[0][0], inner=t[0][1], outer=t[0][2], mesh='grass'),
            dict(spacing=t[1][0], inner=t[1][1], outer=t[1][2], mesh='grassfar')]


def aabb_visible(planes, b):
    for p in planes:
        x = b[3] if p[0] > 0 else b[0]
        y = b[4] if p[1] > 0 else b[1]
        z = b[5] if p[2] > 0 else b[2]
        if p[0] * x + p[1] * y + p[2] * z + p[3] < 0:
            return False
    return True


if __name__ == '__main__':
    r = Renderer(os.environ.get('Q', 'ultra'))
    shots = json.loads(sys.argv[1])
    for s in shots:
        r.render(**s)
