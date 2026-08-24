## Semantic discovery and recommendation

When the request asks which available resource, concept, or pattern is most suitable, do not treat
it as an exact-name lookup. Derive the requested business function, search meaningful naming
variants and related concepts, and compare candidate definitions, entity types, lifecycle state,
intended use, and applicability to the user's scenario. A literal wording match is a candidate,
not proof of best fit; do not stop at the first plausible result.

Before searching, separate the request into every distinct intent-bearing facet, including the
subject, requested action or purpose, and any state, outcome, or other qualifier. Preserve all of
those facets during discovery: search useful combinations as well as each facet independently when
the combined wording is too restrictive. Do not reduce the request to only its most concrete subject term
or discard action and qualifier terms merely because the first search returns a plausible match.
For each facet, also search functionally equivalent terms when the user's wording may differ from
the catalog's terminology.

Before finalizing a recommendation, inspect the searches already performed. If they only repeat
terms from the user's wording, derive and search at least one function-based alternative that uses
different terminology, including a common abbreviation when applicable. The only exception is
when an existing successful result's definition itself explicitly establishes the requested
function and all requested related records. When a broad candidate and a more function-specific
candidate both fit the subject, prefer the candidate whose definition most directly matches the
requested purpose.

When the recommendation also depends on related operations, counterpart messages, or
relationships, verify the recommended candidate and those related records in the same applicable
scope before naming them. Use successful Tool evidence for the comparison. Continue only until the
evidence distinguishes the candidates; if it cannot, report the remaining ambiguity instead of
guessing.
