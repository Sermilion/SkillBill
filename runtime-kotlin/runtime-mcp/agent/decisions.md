## [2026-10-04] Keep MCP argument classification in the adapter
Context: SKILL-400 subtask 6 replaces `InvalidMcpToolArgumentError`, whose only classification reader is `McpToolDispatcher` in runtime-mcp.
Decision: Keep `McpToolArgumentFailureCode.INVALID` and its factory in runtime-mcp, and classify the code through `uncapturedAtMcp()`.
Reason: Adapter-local classification preserves the existing no-capture behavior without introducing MCP vocabulary into runtime-contracts. Adding it to shared shell-content classification would require that outward dependency.
Alternatives considered: Registering the code in `isShellContentContractFailure()` was rejected by the spec because runtime-contracts must declare no MCP-only code.
