#!/usr/bin/env python3
"""Gera a árvore de pastas/arquivos do projeto."""
import os
import sys

IGNORE_DIRS = {
    ".git", ".gradle", ".idea", ".vscode", "build", "out", "run", "bin",
    "cmake-build-debug", "cmake-build-release", "CMakeFiles", "__pycache__",
    "node_modules", "logs", "crash-reports", "saves", ".vs", "x64",
}
IGNORE_EXT = {".class", ".jar", ".dll", ".obj", ".pdb", ".lib", ".exp",
              ".ilk", ".log", ".png", ".zip"}

def tree(root, prefix="", lines=None):
    if lines is None:
        lines = []
    try:
        entries = sorted(os.scandir(root), key=lambda e: (not e.is_dir(), e.name.lower()))
    except PermissionError:
        return lines
    entries = [e for e in entries
               if not (e.is_dir() and e.name in IGNORE_DIRS)
               and not (e.is_file() and os.path.splitext(e.name)[1].lower() in IGNORE_EXT)]
    for i, e in enumerate(entries):
        last = i == len(entries) - 1
        branch = "└── " if last else "├── "
        if e.is_dir():
            lines.append(f"{prefix}{branch}{e.name}/")
            tree(e.path, prefix + ("    " if last else "│   "), lines)
        else:
            size = e.stat().st_size
            lines.append(f"{prefix}{branch}{e.name} ({size:,} B)")
    return lines

if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    output = [os.path.basename(os.path.abspath(root)) + "/"] + tree(root)
    text = "\n".join(output)
    with open("project_tree.txt", "w", encoding="utf-8") as f:
        f.write(text)
    print(text)
    print(f"\nSalvo em project_tree.txt ({len(output)} linhas)")