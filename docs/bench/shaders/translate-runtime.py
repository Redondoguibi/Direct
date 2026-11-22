"""Generate the DX12 backend's vanilla shader set (phase 1) from the Minecraft 1.21.1 resources jar.

For every core program (shaders/core) and post program (shaders/program) descriptor:
GLSL 150 -> GLSL 450 with fixed locations/bindings -> SPIR-V (glslangValidator) -> HLSL SM 5.0 (spirv-cross,
clip-space fixup and flipped Y so D3D12 memory rows match OpenGL's bottom-up rows) -> validated with fxc.
Writes <out>/<kind>/<name>/{vert.hlsl, frag.hlsl, program.json}; the runtime compiles the HLSL with D3DCompile.
Tools: glslangValidator and spirv-cross (Vulkan SDK), fxc (Windows SDK), found on PATH or by --tools.
"""

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

import importlib.util

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("translate_core", HERE / "translate-core.py")
core = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(core)

ROOT = Path(__file__).resolve().parents[3]
KINDS = {"core": core.SHADERS + "core/", "program": core.SHADERS + "program/"}
TYPE_BYTES = {"int": 4, "float": 4, "vec2": 8, "vec3": 12, "vec4": 16, "ivec2": 8, "ivec3": 12, "ivec4": 16, "mat4": 64,
              "mat3": 48, "mat2": 32}


def tool(name, extra):
    for directory in extra:
        candidate = Path(directory) / (name + ".exe")
        if candidate.exists():
            return str(candidate)
    path = shutil.which(name)
    if not path:
        raise SystemExit(f"Missing tool: {name}")
    return path


def read_pair(jar, kind, program):
    """translate_core.normalize_pair for both folders. Post-program descriptors may list uniforms the GLSL never
    declares (box_blur's InSize); the block keeps only declared ones, in descriptor order."""
    base = KINDS[kind]
    sources = {}
    for stage, key, suffix in (("vert", "vertex", ".vsh"), ("frag", "fragment", ".fsh")):
        sources[stage] = core.read_source(jar, base + program[key] + suffix, set())
    declared = {}
    for source in sources.values():
        for glsl_type, name in core.UNIFORM.findall(source):
            if declared.setdefault(name, glsl_type) != glsl_type:
                raise ValueError(f"uniform types differ for {name}")
    members = [f"    {declared[u['name']]} {u['name']};" for u in program.get("uniforms", [])
               if u["name"] in declared and declared[u["name"]] != "sampler2D"]
    block = ""
    if members:
        block = "layout(set=0, binding=0, std140) uniform MinecraftUniforms {\n" + "\n".join(members) + "\n};\n"
    samplers = core.locations(item["name"] for item in program.get("samplers", []))

    def uniforms(match):
        glsl_type, name = match.groups()
        if glsl_type == "sampler2D":
            slot = samplers.setdefault(name, len(samplers))
            return f"layout(set=1, binding={slot}) {match[0]}"
        if not any(u["name"] == name for u in program.get("uniforms", [])):
            raise ValueError(f"{program['vertex']}/{program['fragment']}: uniform {name} missing from descriptor")
        return ""

    vertex_io = core.IO.findall(sources["vert"])
    fragment_io = core.IO.findall(sources["frag"])
    attributes = core.locations(name for _, mode, _, name in vertex_io if mode == "in")
    varyings = core.locations(name for _, mode, _, name in vertex_io if mode == "out")
    for _, mode, _, name in fragment_io:
        if mode == "in":
            varyings.setdefault(name, len(varyings))
    targets = core.locations(name for _, mode, _, name in fragment_io if mode == "out")
    for stage, source in sources.items():
        slots = {"in": attributes, "out": varyings} if stage == "vert" else {"in": varyings, "out": targets}

        def interface(match):
            return f"layout(location={slots[match[2]][match[4]]}) {match[0]}"

        source = core.IO.sub(interface, core.UNIFORM.sub(uniforms, source))
        source = core.re.sub(r"\bgl_VertexID\b", "gl_VertexIndex", source)
        sources[stage] = "#version 450\n" + block + source
    return sources


def layout(directory, program):
    vertex = json.loads((directory / "vert.json").read_text(encoding="utf-8"))
    fragment = json.loads((directory / "frag.json").read_text(encoding="utf-8"))
    inputs = sorted(({"name": item["name"], "location": item["location"], "type": item["type"]}
                     for item in vertex.get("inputs", [])), key=lambda item: item["location"])
    members = {}
    size = 0
    for stage in (vertex, fragment):
        for block in stage.get("ubos", []):
            size = max(size, block.get("block_size", 0))
            for member in stage["types"][block["type"]]["members"]:
                members[member["name"]] = {"name": member["name"], "offset": member["offset"], "type": member["type"]}
    samplers = [item["name"] for item in program.get("samplers", [])]
    used = sorted({texture["name"] for stage in (vertex, fragment) for texture in stage.get("textures", [])})
    return {
        "inputs": inputs,
        "cbufferSize": (size + 15) // 16 * 16,
        "uniforms": sorted(members.values(), key=lambda item: item["offset"]),
        "samplers": samplers,
        "usedSamplers": used,
        "blend": program.get("blend"),
    }


def translate(jar, kind, descriptor, work, out, tools):
    glslang, cross, fxc = tools
    program = json.loads(jar.read(descriptor))
    name = Path(descriptor).stem
    directory = work / kind / name
    directory.mkdir(parents=True)
    for stage, source in read_pair(jar, kind, program).items():
        (directory / f"core.{stage}").write_text(source, encoding="utf-8")
    core.run([glslang, "-V", "-Os", "-l", "core.vert", "core.frag"], directory)
    for stage, profile in (("vert", "vs_5_0"), ("frag", "ps_5_0")):
        flags = ["--fixup-clipspace", "--flip-vert-y"] if stage == "vert" else []
        core.run([cross, f"{stage}.spv", "--hlsl", "--shader-model", "50", *flags, "--output", f"{stage}.hlsl"], directory)
        core.run([cross, f"{stage}.spv", "--reflect", "--output", f"{stage}.json"], directory)
        core.run([fxc, "/nologo", "/O3", "/T", profile, "/E", "main", "/Fo", f"{stage}.dxbc", f"{stage}.hlsl"], directory)
    target = out / kind / name
    target.mkdir(parents=True, exist_ok=True)
    for stage in ("vert", "frag"):
        shutil.copyfile(directory / f"{stage}.hlsl", target / f"{stage}.hlsl")
    data = layout(directory, program)
    data["name"] = name
    data["kind"] = kind
    data["vertexSource"] = program["vertex"]
    data["fragmentSource"] = program["fragment"]
    (target / "program.json").write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")
    return name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "build/moddev/artifacts/neoforge-21.1.265-client-extra-aka-minecraft-resources.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "src/main/resources/assets/direct/dx12")
    parser.add_argument("--tools", action="append", default=[
        "C:/VulkanSDK/1.4.363.0/Bin", "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64"])
    args = parser.parse_args()
    tools = (tool("glslangValidator", args.tools), tool("spirv-cross", args.tools), tool("fxc", args.tools))
    generated = {}
    with tempfile.TemporaryDirectory(prefix="direct-dx12-shaders-") as temp, ZipFile(args.jar) as jar:
        work = Path(temp)
        for kind, base in KINDS.items():
            descriptors = sorted(p for p in jar.namelist() if p.startswith(base) and p.endswith(".json"))
            generated[kind] = [translate(jar, kind, d, work, args.output, tools) for d in descriptors]
    index = {
        "minecraft": "1.21.1",
        "jarSha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        "convention": "spirv-cross HLSL SM5.0 (register spaces dropped), --fixup-clipspace --flip-vert-y; cbuffer b0 "
                      "(std140); textures tN/samplers sN in descriptor sampler order; vertex inputs TEXCOORD<location>",
        "programs": generated,
    }
    (args.output / "index.json").write_text(json.dumps(index, indent=1) + "\n", encoding="utf-8")
    print(", ".join(f"{len(v)} {k}" for k, v in generated.items()), "programs written to", args.output)


if __name__ == "__main__":
    main()
