"""
Lists which members of the local player / camera entity Minecraft's client rendering reads, per class and method.

Input: a Minecraft sources jar (decompiled locally by Loom). Output: one line per method, member names only,
e.g. "render/GameRenderer.renderWorld: player{nauseaIntensity,prevNauseaIntensity,hasStatusEffect}".
No source code is printed.
"""
import collections
import re
import sys
import zipfile

SCOPES = [
    'net/minecraft/client/render/GameRenderer',
    'net/minecraft/client/render/WorldRenderer',
    'net/minecraft/client/render/LightmapTextureManager',
    'net/minecraft/client/render/BackgroundRenderer',
    'net/minecraft/client/render/Camera',
    'net/minecraft/client/render/item/HeldItemRenderer',
    'net/minecraft/client/render/item/ItemRenderer',
    'net/minecraft/client/gui/hud/',
    'net/minecraft/client/gui/screen/DeathScreen',
    'net/minecraft/client/render/block/',
    'net/minecraft/client/render/entity/PlayerEntityRenderer',
    'net/minecraft/client/render/entity/feature/',
    'net/minecraft/client/render/DimensionEffects',
    'net/minecraft/client/render/CloudRenderer',
    'net/minecraft/client/render/WeatherRendering',
    'net/minecraft/client/render/RenderTickCounter',
    'net/minecraft/client/sound/',
    'net/minecraft/client/particle/ParticleManager',
    'net/minecraft/client/MinecraftClient',
    'net/neoforged/neoforge/client/',
]
# Classes where only identity checks against the local player matter (packet handlers, entity code)
IDENTITY_SCOPES = [
    'net/minecraft/client/network/ClientPlayNetworkHandler',
    'net/minecraft/client/network/ClientCommonNetworkHandler',
    'net/minecraft/client/world/ClientWorld',
    'net/minecraft/entity/',
    'net/minecraft/client/network/OtherClientPlayerEntity',
]

SOURCES = {
    'player': r'(?:this\.)?(?:client|minecraft|mc)\.player',
    'camPlayer': r'(?:this\.)?getCameraPlayer\(\)',
    'camEntity': r'(?:this\.)?(?:client|minecraft|mc)\.getCameraEntity\(\)',
    'focused': r'\w+\.getFocusedEntity\(\)',
}
METHOD_RE = re.compile(r'^\s{3,5}(?:@\w+\s+)*(?:(?:public|private|protected|static|final|synchronized|abstract)\s+)*'
                       r'[\w<>\[\]?,. ]+\s+(\w+)\s*\([^;]*$')
ALIAS_RE = {k: re.compile(r'(\w+)\s*=\s*(?:\([\w.<>]+\)\s*)?' + v + r'\s*;') for k, v in SOURCES.items()}
DIRECT_RE = {k: re.compile(v + r'\s*\)?\s*\.(\w+)') for k, v in SOURCES.items()}
INSTANCEOF_RE = {k: re.compile(v + r'\s+instanceof\s+[\w.]+\s+(\w+)') for k, v in SOURCES.items()}
IDENTITY_RE = re.compile(r'(?:==|!=)\s*(?:this\.)?(?:client|minecraft|mc)\.player\b|'
                         r'(?:this\.)?(?:client|minecraft|mc)\.player\s*(?:==|!=)|\.isMainPlayer\(\)')


def scan(name, text, identity_only):
    out = collections.OrderedDict()
    method = '<init>'
    aliases = {}  # alias -> (source, line number)
    lines = text.splitlines()
    for no, line in enumerate(lines):
        m = METHOD_RE.match(line)
        if m and not line.strip().startswith(('return', 'if', 'for', 'while', 'new')):
            method = m.group(1)
            aliases = {}
        if identity_only:
            if IDENTITY_RE.search(line):
                out.setdefault(method, collections.defaultdict(set))['identity'].add('==player')
            continue
        for src, rx in ALIAS_RE.items():
            for a in rx.finditer(line):
                aliases[a.group(1)] = src
        for src, rx in INSTANCEOF_RE.items():
            for a in rx.finditer(line):
                aliases[a.group(1)] = src
        for src, rx in DIRECT_RE.items():
            for d in rx.finditer(line):
                out.setdefault(method, collections.defaultdict(set))[src].add(d.group(1))
        for alias, src in aliases.items():
            for d in re.finditer(r'\b' + re.escape(alias) + r'\.(\w+)', line):
                out.setdefault(method, collections.defaultdict(set))[src].add(d.group(1))
    short = name.replace('net/minecraft/client/', '').replace('net/minecraft/', '').replace('.java', '')
    for method, srcs in out.items():
        parts = ' '.join(f"{s}{{{','.join(sorted(v))}}}" for s, v in sorted(srcs.items()))
        yield f"{short}.{method}: {parts}"


def main(jars):
    seen = set()
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            for name in sorted(z.namelist()):
                if not name.endswith('.java') or name in seen:
                    continue
                identity = any(name.startswith(p) for p in IDENTITY_SCOPES)
                if not identity and not any(name.startswith(p) for p in SCOPES):
                    continue
                seen.add(name)
                text = z.read(name).decode('utf-8', 'replace')
                for line in scan(name, text, identity):
                    print(line)


if __name__ == '__main__':
    main(sys.argv[1:])
