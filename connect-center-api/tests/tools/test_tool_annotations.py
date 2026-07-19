"""Tripwire for MCP tool ``readOnlyHint`` annotations.

Enumerates every FastMCP server defined in ``app/tools/*.py`` at runtime
(the same module-level ``mcp`` instances that ``app.main`` mounts) and
asserts the read-only annotation convention:

- every tool named ``get_*`` or ``who_am_i`` declares ``readOnlyHint=True``;
- no other tool declares ``readOnlyHint=True``.

``readOnlyHint`` is a security classification, not naming cosmetics: the
connectCenter assistant exempts hinted tools from mutation approval and hands
them to read-only fan-out specialists. A tool may declare the hint ONLY if it
is behaviorally read-only (no writes, no state transitions, no side effects).
If a tool with any side effect trips the first assertion because of its
``get_*`` name, the fix is to RENAME the tool so it does not look like a read
— never to add the hint.

The check is import-only: tool metadata is registered at import time and the
database engine is created lazily, so no database or network access occurs.
"""

from __future__ import annotations

import importlib
import pkgutil
from pathlib import Path

from fastmcp import FastMCP
from fastmcp.tools import Tool

TOOLS_PACKAGE = "app.tools"
TOOLS_DIR = Path(__file__).resolve().parents[2] / "app" / "tools"

READ_ONLY_TOOL_NAMES = frozenset({"who_am_i"})
READ_ONLY_PREFIXES = ("get_",)


def _is_read_only_by_convention(tool_name: str) -> bool:
    """Return whether the naming convention marks this tool as read-only."""
    return tool_name.startswith(READ_ONLY_PREFIXES) or tool_name in READ_ONLY_TOOL_NAMES


def _declares_read_only_hint(tool: Tool) -> bool:
    """Return whether the tool declares ``ToolAnnotations(readOnlyHint=True)``."""
    return tool.annotations is not None and tool.annotations.readOnlyHint is True


async def _collect_registered_tools() -> dict[str, Tool]:
    """Import every module in ``app/tools`` and enumerate its registered tools."""
    tools_by_name: dict[str, Tool] = {}
    module_names = [
        module_info.name
        for module_info in pkgutil.iter_modules([str(TOOLS_DIR)])
        if not module_info.ispkg
    ]
    assert module_names, f"No tool modules found under {TOOLS_DIR}"

    for module_name in sorted(module_names):
        module = importlib.import_module(f"{TOOLS_PACKAGE}.{module_name}")
        servers = [value for value in vars(module).values() if isinstance(value, FastMCP)]
        assert servers, f"{module.__name__} defines no module-level FastMCP server"
        for server in servers:
            for tool in await server.list_tools():
                assert (
                    tool.name not in tools_by_name
                ), f"Tool name '{tool.name}' is registered more than once across app/tools modules"
                tools_by_name[tool.name] = tool

    assert tools_by_name, "No MCP tools were enumerated from app/tools"
    return tools_by_name


async def test_get_and_who_am_i_tools_declare_read_only_hint():
    tools_by_name = await _collect_registered_tools()
    read_only_names = [name for name in tools_by_name if _is_read_only_by_convention(name)]
    assert read_only_names, "Expected at least one get_*/who_am_i tool to be registered"

    missing = sorted(
        name for name in read_only_names if not _declares_read_only_hint(tools_by_name[name])
    )
    assert not missing, (
        "Tools named get_*/who_am_i without annotations=ToolAnnotations(readOnlyHint=True): "
        f"{missing}. If a tool is behaviorally read-only, add the hint; if it has ANY "
        "side effect, RENAME it so it does not look like a read — do NOT add the hint, "
        "because hinted tools bypass mutation approval in the connectCenter assistant."
    )


async def test_no_other_tool_declares_read_only_hint():
    tools_by_name = await _collect_registered_tools()
    other_names = [name for name in tools_by_name if not _is_read_only_by_convention(name)]
    assert other_names, "Expected at least one non-read-only tool to be registered"

    mislabeled = sorted(
        name for name in other_names if _declares_read_only_hint(tools_by_name[name])
    )
    assert not mislabeled, (
        "Tools declare readOnlyHint=True but are not named get_*/who_am_i "
        f"(rename them or drop the hint): {mislabeled}"
    )
