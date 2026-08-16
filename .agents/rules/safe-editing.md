---
trigger: always_on
---

# Safe Code Editing Protocol
* Never execute structural modifications or deletions by targeting a raw closing brace (`}`) string match.
* When executing a text-based search-and-replace, the target block MUST explicitly include the structural parent declaration (e.g., the complete method signature or class line) to maintain context.
* If a file change involves removing a method or code block entirely, you must explicitly count the opening and closing brackets of the surrounding code blocks before executing the write tool to ensure zero clipping errors.
