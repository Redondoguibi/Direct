"""Compile the Minecraft 1.21.1 vanilla core-program inventory for the phase 0 spike."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[3]
SHADERS = "assets/minecraft/shaders/"
CORE = SHADERS + "core/"
IMPORT = re.compile(r'^\s*#moj_import\s+<([\w./-]+)>\s*$', re.MULTILINE)
VERSION = re.compile(r'^#version 150\s*$', re.MULTILINE)
UNIFORM = re.compile(r'^uniform (\w+) (\w+);$', re.MULTILINE)
IO = re.compile(r'^((?:flat )?)(in|out) (\w+) (\w+);$', re.MULTILINE)


def read_source(jar, name, seen):
    source = jar.read(name).decode("utf-8").replace("\r\n", "\n")
    if not VERSION.search(source):
        raise ValueError(f"{name}: expected Minecraft GLSL 150")

    def include(match):
        path = SHADERS + "include/" + match[1]
        if path in seen:
            return ""
        seen.add(path)
        return read_source(jar, path, seen)

    return IMPORT.sub(include, VERSION.sub("", source))


def locations(names):
    return {name: index for index, name in enumerate(dict.fromkeys(names))}


def normalize_pair(jar, program):
    sources = {}
    for stage, key, suffix in (("vert", "vertex", ".vsh"), ("frag", "fragment", ".fsh")):
        sources[stage] = read_source(jar, CORE + program[key] + suffix, set())

    declared = {}
    for source in sources.values():
        for glsl_type, name in UNIFORM.findall(source):
            if declared.setdefault(name, glsl_type) != glsl_type:
                raise ValueError(f"uniform types differ for {name}")

    # Both stages get the same block, in the descriptor's order. The reflection
    # files carry std140 member offsets and matrix strides for a future uploader.
    members = []
    for uniform in program["uniforms"]:
        name = uniform["name"]
        members.append(f"    {declared[name]} {name};")
    block = ""
    if members:
        block = "layout(set=0, binding=0, std140) uniform MinecraftUniforms {\n"
        block += "\n".join(members) + "\n};\n"
    samplers = locations(item["name"] for item in program["samplers"])

    def uniforms(match):
        glsl_type, name = match.groups()
        if glsl_type == "sampler2D":
            # One vanilla shader declares an unused sampler absent from its JSON.
            # Give it a private binding. Optimization removes it before reflection.
            slot = samplers.setdefault(name, len(samplers))
            return f"layout(set=1, binding={slot}) {match[0]}"
        return ""

    vertex_io = IO.findall(sources["vert"])
    fragment_io = IO.findall(sources["frag"])
    attributes = locations(name for _, mode, _, name in vertex_io if mode == "in")
    varyings = locations(name for _, mode, _, name in vertex_io if mode == "out")
    for _, mode, _, name in fragment_io:
        if mode == "in":
            varyings.setdefault(name, len(varyings))
    targets = locations(name for _, mode, _, name in fragment_io if mode == "out")
    for stage, source in sources.items():
        slots = {"in": attributes, "out": varyings} if stage == "vert" else {"in": varyings, "out": targets}

        def interface(match):
            return f"layout(location={slots[match[2]][match[4]]}) {match[0]}"

        source = IO.sub(interface, UNIFORM.sub(uniforms, source))
        source = re.sub(r"\bgl_VertexID\b", "gl_VertexIndex", source)
        sources[stage] = "#version 450\n" + block + source
    return sources


def compiler(name):
    path = shutil.which(name)
    if not path:
        raise SystemExit(f"Missing compiler on PATH: {name}")
    return path


def run(args, directory):
    proc = subprocess.run(args, cwd=directory, text=True, capture_output=True, errors="replace")
    if proc.returncode:
        raise RuntimeError(f"{directory.name}: {subprocess.list2cmdline(args)}\n{proc.stdout}{proc.stderr}")
    return proc.stdout


def verify_reflection(directory, program):
    vertex, fragment = (json.loads((directory / f"{stage}.json").read_text(encoding="utf-8"))
                        for stage in ("vert", "frag"))
    outputs = {item["location"]: item for item in vertex.get("outputs", [])}
    for item in fragment.get("inputs", []):
        if outputs.get(item["location"]) != item:
            raise ValueError(f"{directory.name}: incompatible stage interface for {item['name']}")

    uniforms = {item["name"]: item for item in program["uniforms"]}
    slots = locations(item["name"] for item in program["samplers"])
    # Optimization can omit inactive members, but shared members must retain
    # their offsets and the types and sampler order specified in the descriptor.
    layout = {}
    types = {("int", 1): "int", ("float", 1): "float", ("float", 2): "vec2",
             ("float", 3): "vec3", ("float", 4): "vec4", ("matrix4x4", 16): "mat4"}
    for stage in (vertex, fragment):
        for texture in stage.get("textures", []):
            if (texture["set"], texture["binding"]) != (1, slots.get(texture["name"])):
                raise ValueError(f"{directory.name}: invalid sampler binding {texture}")
        for block in stage.get("ubos", []):
            if (block["set"], block["binding"]) != (0, 0):
                raise ValueError(f"{directory.name}: invalid block binding {block}")
            for member in stage["types"][block["type"]]["members"]:
                name = member["name"]
                descriptor = uniforms[name]
                if types[descriptor["type"], descriptor["count"]] != member["type"]:
                    raise ValueError(f"{directory.name}: invalid uniform type {member}")
                if layout.setdefault(name, member) != member:
                    raise ValueError(f"{directory.name}: inconsistent uniform layout {member}")


def compile_pair(jar, descriptor, directory, glslang, cross, dxc):
    program = json.loads(jar.read(descriptor))
    directory.mkdir()
    for stage, source in normalize_pair(jar, program).items():
        (directory / f"core.{stage}").write_text(source, encoding="utf-8")
    # Link the pair as one GLSL program, then generate the per-stage SPIR-V files.
    run([glslang, "-V", "-Os", "-l", "core.vert", "core.frag"], directory)
    for stage, target in (("vert", "vs_6_0"), ("frag", "ps_6_0")):
        spv = f"{stage}.spv"
        hlsl = f"{stage}.hlsl"
        clip = ["--fixup-clipspace"] if stage == "vert" else []
        run([cross, spv, "--hlsl", "--shader-model", "60", *clip, "--output", hlsl], directory)
        run([cross, spv, "--reflect", "--output", f"{stage}.json"], directory)
        run([dxc, "-Ges", "-O3", "-T", target, "-E", "main", "-Fo", f"{stage}.dxil", hlsl], directory)
    verify_reflection(directory, program)
    return [program["vertex"], program["fragment"]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "build/moddev/artifacts/neoforge-21.1.265-client-extra-aka-minecraft-resources.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "build/phase0-shaders")
    args = parser.parse_args()
    glslang, cross, dxc = map(compiler, ("glslangValidator", "spirv-cross", "dxc"))
    args.output.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="run-", dir=args.output.resolve()))
    print(f"Artifacts: {directory}", flush=True)
    with ZipFile(args.jar) as jar:
        descriptors = sorted(p for p in jar.namelist() if p.startswith(CORE) and p.endswith(".json"))
        if len(descriptors) != 56:
            raise ValueError(f"Expected 56 program descriptors, found {len(descriptors)} in {args.jar}")
        pairs = {}
        for descriptor in descriptors:
            name = Path(descriptor).stem
            pairs[name] = compile_pair(jar, descriptor, directory / name, glslang, cross, dxc)
    report = {
        "input": str(args.jar.resolve()),
        "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        "compilers": {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in (glslang, cross, dxc)},
        "programs": pairs,
        "compiled_stages": 2 * len(pairs),
    }
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{len(pairs)}/56 linked programs, {2 * len(pairs)} DXIL stages")


if __name__ == "__main__":
    main()
