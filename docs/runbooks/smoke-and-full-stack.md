# API smoke and deployed frontend acceptance

The maintained runbook is [Full stack smoke acceptance](./full-stack-smoke.md).

Use that document for required cloud read checks, exact staging fixture policy,
credential delivery, cleanup, and browser acceptance limits. This compatibility
link replaces the earlier draft whose optional cloud checks are no longer valid.

Cloud checks must not silently skip authentication, known-object retrieval, or
CORS. Production mutation is always forbidden. Mocked API tests and browser test
discovery do not establish real full-stack acceptance.
