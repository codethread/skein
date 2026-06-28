# Project Tenets

- **TEN-000**: This is alpha software.
  - All apis, contracts, db schemas, are subject to change. Changes can and should drop old ideas without migration plans should a better approach be presented.
- **TEN-001**: This is primarily an LLM coding agent tool.
  - All apis should favour their consumption (raw informative structure data over pretty ascii and layouts).
  - The apis exposed should offer flexibility over invariants
- **TEN-002**: Agents are trusted to not abuse their power.
  - In giving agents this tool, we expose helper functions along with clear guidance, such that they will use the 'blessed path' and not corrupt data or remove userland invariants without their user's sign-off. This allows us to keep the system malleable and flexible, without the need to constrain every possible misuse of the system
- **TEN-003**: FAIL LOUDLY.
  - we have a deliberately loose api contract via attributes, this provides flexibility, and we can build this tolerance into the system. However when we get something we do not expect, we do not try to work around it or choose 'sensible defaults'. We FAIL LOUDLY. If this provides api friction or poor ergonomics, we solve that in other ways like persisted config or better api design.
- **TEN-004**: Less is More.
  - We expose the minimum possible surface area over api, and what we expose, we make extremely robust. Everything else we delegate to userland via our attributes and query language
- **TEN-005**: Declared structural relations are DAGs.
  - The engine guarantees each declared acyclic relation is independently acyclic, and every engine traversal walks exactly one such relation or is explicitly cycle-aware. Annotation edges carry no acyclicity guarantee and may form cycles; consumers must not assume whole-graph acyclicity.
- **TEN-006**: The CLI is a thin JSON control surface; the daemon/REPL is the rich semantic surface.
  - The scripted CLI should expose simple commands, string flags, JSON machine output, and named handles to daemon-owned behavior. It should not parse, author, or debug rich Clojure/EDN userland structures.
  - Complex query definitions, runtime customization, inspection, and debugging belong in trusted daemon config and REPL workflows. The CLI can invoke those capabilities by stable names and simple JSON-shaped params.
  - The engine may translate between JSON wire data and Clojure-native/EDN data internally, but that translation is hidden behind daemon APIs.
