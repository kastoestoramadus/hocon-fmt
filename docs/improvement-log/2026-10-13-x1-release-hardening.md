# Release ancestry and upload preflight

Release tags must point to a commit on main's history, checked with a full-history checkout
and a fresh main fetch in the secret-free version job. Before any registry upload, missing
Gradle Portal credentials produce a warning and job-summary entry in wave 1, or stop a real
wave-2 release. Manual dry runs still default to building and signing without uploading.
Only the wave-2 GitHub release job has contents write permission. Release actions are pinned
to commit SHAs verified against their existing major tags via GitHub's ref and commit APIs.

**Look at again before a release:** run `python3 scripts/release-workflow-test.py` and actionlint;
then exercise the signing paths with real credentials through the approved environment.
Local guards use upload stubs and Git fixtures; they do not validate registry acceptance,
GitHub expression evaluation, or environment policy. Review action-pin updates against their tags.
